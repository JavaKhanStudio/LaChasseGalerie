package jks.online;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import jks.net.Net_Codec;
import jks.net.Net_Input;
import jks.net.Net_Listener;
import jks.net.Net_Message;
import jks.net.Net_Peer;
import jks.net.Net_Rejected;
import jks.net.Net_Snapshot;
import jks.net.Net_Transport;

/**
 * The client's half of phase 1.4 (docs/online-multiplayer.md section 6) : it says hello, sends this
 * machine's buttons 60 times a second, and draws the run a little in the past, between the two
 * snapshots around that moment. IT OWNS NO PHYSICS : like {@link Snapshot_Mirror}, it imports no body,
 * no GVars_* and no asset, so a process that is only a client never loads the game's world.
 *
 * The picture is {@link #view()}, a Snapshot_Mirror whose entities are where they were
 * {@link #INTERPOLATION_TICKS} behind the newest snapshot : 100 ms, room for one lost snapshot and
 * some jitter. An entity is created and destroyed on it when the drawing time reaches the snapshot
 * that brought or dropped it, so a sprite hung on its Listener appears where the host had it, not
 * 100 ms early. Positions, velocities, angles and the story clock are interpolated ; everything else
 * - hp, animation, facing - is the older snapshot's, because none of it is in between.
 *
 * Its clock is its own guess at the host's tick : WELCOME sets it, it advances one per tick, it
 * catches up at once when a snapshot is newer, and it drifts back a tick per second while every
 * snapshot is older than it thinks. Input frames are stamped with it, so the host can put each one
 * on a tick, once.
 *
 * Joining is asked, not assumed : {@link #join()} asks for a hero, and keeps asking until one for
 * this player is in a snapshot. A hero that dies is gone from the snapshots, and the next join()
 * brings it back - the online form of pressing a key after dying.
 *
 * Not thread safe : tick it from the loop that draws.
 */
public final class ClientSession implements AutoCloseable
{
	/** How far behind the newest snapshot the picture is drawn : 100 ms, two snapshot intervals. */
	public static final int INTERPOLATION_TICKS = 6;
	/** How often a HELLO or a JOIN is repeated until it is answered : half a second. */
	static final int RETRY_TICKS = 30;
	/** Snapshots kept to interpolate from. A second of them : more than any buffer needs. */
	static final int KEPT = 24;
	/** How often the clock checks it has not run ahead of the host. */
	static final int DRIFT_WINDOW = 60;

	public enum State
	{
		/** HELLO sent, no WELCOME yet. */
		CONNECTING,
		/** Welcomed : this machine is {@link #player()}. */
		IN,
		/** The host said LEAVE : {@link #endedBecause()} says why. */
		ENDED,
		/** Nothing heard from the host for the transport's timeout. */
		LOST
	}

	private final Net_Transport transport;
	private final Net_Peer host;
	private final Net_Listener listener = new Net_Listener()
	{
		@Override
		public void received(Net_Peer from, ByteBuffer payload)
		{
			// The socket takes bytes from anyone : only the host speaks to a client
			if (from.equals(host))
				ClientSession.this.received(payload);
		}

		@Override
		public void lost(Net_Peer peer)
		{
			if (peer.equals(host) && state != State.ENDED)
				state = State.LOST;
		}
	};

	private State state = State.CONNECTING;
	private Net_Message.Leave.Reason endedBecause;
	/** The version the host speaks, when it turned us away for speaking another. */
	private int hostVersion = -1;
	private int player;

	/** The client's guess at the host's tick, and the tick the last input frame claimed. */
	private int clock, lastFrameTick;
	private int driftTicks, driftLead = Integer.MAX_VALUE;
	private final Net_Input input = new Net_Input();
	private int held;

	private boolean wantsHero;
	private int retry;

	/** By tick : the newest snapshots, to interpolate between. */
	private final TreeMap<Integer, Net_Snapshot> snapshots = new TreeMap<Integer, Net_Snapshot>();
	private final Map<Net_Snapshot, Index> indexes = new HashMap<Net_Snapshot, Index>();
	private final Snapshot_Mirror view;
	private float drawTick = -1;
	private int drawnFrom = -1, drawnTo = -1;

	public int snapshotsReceived, rejected;

	public ClientSession(Net_Transport transport, String hostAddress)
	{
		this(transport, hostAddress, new Snapshot_Mirror.Listener() {});
	}

	/** The listener hears the {@link #view()}'s entities come and go : where phase 1.5 hangs its sprites. */
	public ClientSession(Net_Transport transport, String hostAddress, Snapshot_Mirror.Listener entities)
	{
		this.transport = transport;
		this.host = transport.resolve(hostAddress);
		this.view = new Snapshot_Mirror(entities);
		send(new Net_Message.Hello());
	}

	/**
	 * One tick of this machine's loop, at 60 Hz : read what arrived, send the buttons held this tick
	 * (Net_Input bits ; JUMP and the swings are presses, set on the tick they happened), and move the
	 * picture on.
	 */
	public void tick(int buttons)
	{
		transport.pump(listener);

		switch (state)
		{
			case CONNECTING:
				if (++retry >= RETRY_TICKS)
				{
					retry = 0;
					send(new Net_Message.Hello());
				}
				break;
			case IN:
				clock++;
				sendInput(buttons);
				if (wantsHero && hasHeroInNewest())
					wantsHero = false;
				if (wantsHero && ++retry >= RETRY_TICKS)
				{
					retry = 0;
					send(new Net_Message.Join());
				}
				checkDrift();
				draw();
				break;
			default:
				break;
		}
	}

	/** Asks for a hero, now and every half second until one for this player is in a snapshot. */
	public void join()
	{
		if (wantsHero || hasHeroInNewest())
			return;
		wantsHero = true;
		retry = 0;
		if (state == State.IN)
			send(new Net_Message.Join());
		else
			retry = RETRY_TICKS; // sent on the first tick after the WELCOME
	}

	/** Tells the host this player is gone, a few times since any one packet may be lost. */
	@Override
	public void close()
	{
		if (state == State.IN)
			for (int i = 0; i < 3; i++)
				send(new Net_Message.Leave(player, Net_Message.Leave.Reason.QUIT));
		state = State.ENDED;
		endedBecause = Net_Message.Leave.Reason.QUIT;
	}

	public State state()
	{
		return state;
	}

	public Net_Message.Leave.Reason endedBecause()
	{
		return endedBecause;
	}

	/** The protocol version the host speaks, when it refused us for speaking another ; -1 otherwise. */
	public int hostVersion()
	{
		return hostVersion;
	}

	/** This machine's PlayerId number, 0 before the WELCOME. */
	public int player()
	{
		return player;
	}

	/** The picture : the run as the host had it at {@link #drawTick()}. */
	public Snapshot_Mirror view()
	{
		return view;
	}

	/** The host tick the picture shows, fractional between two snapshots ; -1 before there is one. */
	public float drawTick()
	{
		return drawTick;
	}

	/**
	 * The ticks of the two snapshots the picture is drawn between : equal when it holds one, -1 before
	 * any. Three apart is every snapshot arriving ; six is one lost, and a straight line drawn over a
	 * longer gap cuts a longer corner.
	 */
	public int drawnFrom()
	{
		return drawnFrom;
	}

	public int drawnTo()
	{
		return drawnTo;
	}

	/** This client's guess at the host's current tick. */
	public int clock()
	{
		return clock;
	}

	/** The newest snapshot that arrived, or null : what is known, as opposed to what is drawn. */
	public Net_Snapshot newest()
	{
		return snapshots.isEmpty() ? null : snapshots.lastEntry().getValue();
	}

	/** Whether this player's hero is alive in the newest snapshot. */
	public boolean hasHeroInNewest()
	{
		Net_Snapshot newest = newest();
		if (newest == null || player == 0)
			return false;
		for (Net_Snapshot.Hero hero : newest.heroes)
			if (hero.player == player)
				return true;
		return false;
	}

	// ---------------------------------------------------------------- arriving

	void received(ByteBuffer payload)
	{
		Net_Message message;
		try
		{
			message = Net_Codec.decode(payload);
		}
		catch (Net_Rejected e)
		{
			rejected++;
			// A host of another version cannot be read, but its refusal still says which version it speaks
			if (e.reason == Net_Rejected.Reason.VERSION && state == State.CONNECTING)
			{
				state = State.ENDED;
				endedBecause = Net_Message.Leave.Reason.VERSION;
				hostVersion = e.version;
			}
			return;
		}

		switch (message.type())
		{
			case WELCOME:
				if (state != State.CONNECTING)
					break;
				Net_Message.Welcome welcome = (Net_Message.Welcome) message;
				player = welcome.player;
				clock = welcome.tick;
				lastFrameTick = clock - 1;
				state = State.IN;
				break;
			case SNAPSHOT:
				if (state == State.IN)
					keep((Net_Snapshot) message);
				break;
			case LEAVE:
				Net_Message.Leave leave = (Net_Message.Leave) message;
				if (leave.player == player || leave.player == HostSession.NOBODY)
				{
					state = State.ENDED;
					endedBecause = leave.reason;
				}
				break;
			default:
				break;
		}
	}

	/** Keeps a snapshot to draw from, and lets a newer one pull the clock forward. A copy, or one older than all kept, is dropped. */
	void keep(Net_Snapshot snapshot)
	{
		if (snapshots.containsKey(snapshot.tick) || (!snapshots.isEmpty() && snapshot.tick < snapshots.firstKey()))
			return;
		snapshotsReceived++;
		snapshots.put(snapshot.tick, snapshot);
		while (snapshots.size() > KEPT)
			indexes.remove(snapshots.pollFirstEntry().getValue());

		if (snapshot.tick > clock)
			clock = snapshot.tick;
		driftLead = Math.min(driftLead, clock - snapshot.tick);
	}

	/**
	 * Once a second : if even the freshest snapshot of the last second was older than the clock by
	 * more than a snapshot interval, this machine's ticks run faster than the host's. Step back one.
	 */
	void checkDrift()
	{
		if (++driftTicks < DRIFT_WINDOW)
			return;
		if (driftLead != Integer.MAX_VALUE && driftLead > HostSession.TICKS_PER_SNAPSHOT)
			clock--;
		driftTicks = 0;
		driftLead = Integer.MAX_VALUE;
	}

	// ---------------------------------------------------------------- sending

	void sendInput(int buttons)
	{
		// The clock may have jumped ahead to a snapshot : the ticks skipped keep the buttons held, without the presses
		int frameTick = Math.max(lastFrameTick + 1, clock);
		for (int skipped = Math.max(lastFrameTick + 1, frameTick - Net_Input.FRAMES + 1); skipped < frameTick; skipped++)
			input.push(skipped, held);
		input.push(frameTick, buttons);
		lastFrameTick = frameTick;
		held = buttons & (Net_Input.LEFT | Net_Input.RIGHT);
		send(input);
	}

	void send(Net_Message message)
	{
		transport.send(host, Net_Codec.encode(message));
	}

	// ---------------------------------------------------------------- drawing

	/** Moves the picture to {@link #INTERPOLATION_TICKS} behind the clock, never backwards. */
	void draw()
	{
		if (snapshots.isEmpty())
			return;
		float target = clock - INTERPOLATION_TICKS;
		// Never before the first snapshot, never past the newest, never back in time
		target = Math.max(target, snapshots.firstKey());
		target = Math.min(target, snapshots.lastKey());
		drawTick = Math.max(drawTick, target);

		Map.Entry<Integer, Net_Snapshot> from = snapshots.floorEntry((int) Math.floor(drawTick));
		Map.Entry<Integer, Net_Snapshot> to = snapshots.higherEntry(from.getKey());
		Net_Snapshot a = from.getValue();
		// Refused, and harmless, when it is the one already applied ; refused as a lie (an id twice), nothing to place
		if (!view.apply(a) && view.tick != a.tick)
			return;

		if (to == null || drawTick <= a.tick)
		{
			drawnFrom = drawnTo = a.tick;
			place(a, null, 0);
			return;
		}
		Net_Snapshot b = to.getValue();
		drawnFrom = a.tick;
		drawnTo = b.tick;
		place(a, b, (drawTick - a.tick) / (b.tick - a.tick));
	}

	/** Writes every entity of the view at its place between a and b ; an entity b does not have stays where a had it. */
	void place(Net_Snapshot a, Net_Snapshot b, float t)
	{
		Index next = b == null ? null : index(b);
		view.storyTime = b == null ? a.storyTime : lerp(a.storyTime, b.storyTime, t);
		view.skyScroll = b == null ? a.skyScroll : lerp(a.skyScroll, b.skyScroll, t);
		view.canoeAngle = b == null ? a.canoeAngle : lerpAngle(a.canoeAngle, b.canoeAngle, t);

		for (Net_Snapshot.Hero from : a.heroes)
		{
			Net_Snapshot.Hero hero = view.hero(from.id);
			Net_Snapshot.Hero to = next == null ? null : next.heroes.get(from.id);
			float s = to == null ? 0 : t;
			if (to == null)
				to = from;
			hero.x = lerp(from.x, to.x, s);
			hero.y = lerp(from.y, to.y, s);
			hero.vx = lerp(from.vx, to.vx, s);
			hero.vy = lerp(from.vy, to.vy, s);
			hero.axeX = lerp(from.axeX, to.axeX, s);
			hero.axeY = lerp(from.axeY, to.axeY, s);
			hero.axeAngle = lerpAngle(from.axeAngle, to.axeAngle, s);
		}
		for (Net_Snapshot.Monster from : a.monsters)
		{
			Net_Snapshot.Monster monster = view.monster(from.id);
			Net_Snapshot.Monster to = next == null ? null : next.monsters.get(from.id);
			float s = to == null ? 0 : t;
			if (to == null)
				to = from;
			monster.x = lerp(from.x, to.x, s);
			monster.y = lerp(from.y, to.y, s);
		}
		for (Net_Snapshot.Potion from : a.potions)
		{
			Net_Snapshot.Potion potion = view.potion(from.id);
			Net_Snapshot.Potion to = next == null ? null : next.potions.get(from.id);
			float s = to == null ? 0 : t;
			if (to == null)
				to = from;
			potion.x = lerp(from.x, to.x, s);
			potion.y = lerp(from.y, to.y, s);
		}
	}

	Index index(Net_Snapshot snapshot)
	{
		Index index = indexes.get(snapshot);
		if (index == null)
			indexes.put(snapshot, index = new Index(snapshot));
		return index;
	}

	static float lerp(float a, float b, float t)
	{
		return a + (b - a) * t;
	}

	/** The short way round : an axe crossing +-pi does not spin the long way. */
	static float lerpAngle(float a, float b, float t)
	{
		double turn = 2 * Math.PI;
		double difference = ((b - a) % turn + turn * 1.5) % turn - turn / 2;
		return (float) (a + difference * t);
	}

	/** A snapshot's entities by id, built once per snapshot. */
	static final class Index
	{
		final Map<Integer, Net_Snapshot.Hero> heroes = new HashMap<Integer, Net_Snapshot.Hero>();
		final Map<Integer, Net_Snapshot.Monster> monsters = new HashMap<Integer, Net_Snapshot.Monster>();
		final Map<Integer, Net_Snapshot.Potion> potions = new HashMap<Integer, Net_Snapshot.Potion>();

		Index(Net_Snapshot snapshot)
		{
			for (Net_Snapshot.Hero hero : snapshot.heroes)
				heroes.put(hero.id, hero);
			for (Net_Snapshot.Monster monster : snapshot.monsters)
				monsters.put(monster.id, monster);
			for (Net_Snapshot.Potion potion : snapshot.potions)
				potions.put(potion.id, potion);
		}
	}
}
