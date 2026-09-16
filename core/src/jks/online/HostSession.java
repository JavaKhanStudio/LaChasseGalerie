package jks.online;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jks.input.PlayerId;
import jks.net.Net_Codec;
import jks.net.Net_Input;
import jks.net.Net_Listener;
import jks.net.Net_Message;
import jks.net.Net_Peer;
import jks.net.Net_Rejected;
import jks.net.Net_Snapshot;
import jks.net.Net_Transport;

/**
 * The host's half of phase 1.4 (docs/online-multiplayer.md section 6) : it owns the only simulation,
 * lets peers in, applies their input frames at the tick they claim, and sends every peer a snapshot
 * 20 times a second.
 *
 * NOTHING HERE ASSUMES THE HOST IS A PLAYER. The session has no hero, no device and no screen ; a
 * host with zero local heroes is the ordinary case, and a host that also plays does it beside the
 * session, through its own IKM_Game_*, on the same PlayerId numbering. That is what keeps a dedicated
 * server a deployment decision instead of a rewrite. And it talks to peers only through
 * {@link Net_Transport} : a tab behind a data channel (d7) is a peer like any other.
 *
 * Join and leave are network events, onto the game's own join and rejoin :
 * <ul>
 * <li>HELLO from a peer it does not know : a PlayerId, answered with WELCOME. A HELLO again (the
 *     WELCOME was lost) gets the same WELCOME. One MACHINE is one player (d5 -> A), and the HELLO's
 *     rejoin key is how a machine is known beyond its connection : a key this run has seen gets the
 *     player it had back, with its score row (d13 -> C). A new key is a new player.</li>
 * <li>A HELLO whose key a seat still holds, from another address : the machine came back before the
 *     host noticed it was gone (a crash and a restart, a new address). If that seat has been silent
 *     for {@link #TAKEOVER_TICKS}, the newcomer takes it over, hero and all. If the seat is still
 *     talking, two processes share one key - two windows on one machine - and the newcomer is a new
 *     player whose row goes when it leaves, since nothing could bring it back.</li>
 * <li>JOIN from a player with no living hero : a hero. It is how a player who died comes back, like the
 *     first key press offline. A JOIN while the hero lives changes nothing.</li>
 * <li>LEAVE from the peer, or the transport reporting it lost : the hero goes and the seat is free. The
 *     score row stays for the machine to come back to, until {@link #MAX_ROWS} would be passed : then the
 *     player who left longest ago is forgotten, so the snapshot's score table cannot grow without end.</li>
 * <li>The run full, or a packet of another protocol version : a LEAVE saying so, and nothing else.</li>
 * </ul>
 *
 * INPUT. Every packet carries the newest four frames, each stamped with the host tick the client
 * believes it is for. A frame is applied at the tick it claims ; if that tick is already simulated -
 * the usual case, since it took a round trip to get here and there is no prediction - at the next
 * one. A tick number is applied once : copies are ignored. When several frames land on one tick, the
 * held buttons are the newest frame's and a one-shot pressed in any of them fires once, so a burst of
 * late packets neither drops a jump nor repeats one. Nothing arriving means the buttons stay held.
 *
 * The bytes on the socket come from anyone (the transport never connects, a punch is an unknown
 * address) : a packet that does not decode is dropped, and anything but HELLO from a peer that was
 * never welcomed is ignored.
 *
 * Not thread safe : tick it from the loop that steps the world.
 */
public final class HostSession implements AutoCloseable
{
	/** The run this plan is sized for : about 430 B of snapshot and 0.5 Mbit/s up at 8 (section 4). */
	public static final int MAX_PLAYERS = 8;
	/** 60 Hz simulated, 20 Hz sent. */
	public static final int TICKS_PER_SNAPSHOT = 3;
	/** A frame claiming a tick further ahead than this comes from a broken clock : applied now. */
	public static final int MAX_LEAD = 30;
	/** Frames waiting for their tick, per player. Past it, the oldest go : a client cannot grow the host. */
	static final int MAX_PENDING = 64;
	/**
	 * The player a refusal names : the peer never got one, and the codec has no "no player" (a player 0
	 * is refused by it). The numbering never reaches it : a run that minted this many is refused as full.
	 */
	public static final int NOBODY = 0xFFFF;
	/**
	 * Score rows a run keeps, seated players and players who left together : 16 x 6 B in a snapshot, 48 B
	 * past the 8 players the budget was measured with (Net_Codec_Checks.measuredPeakFits holds it).
	 */
	public static final int MAX_ROWS = 16;
	/** How long a seat must have been silent before a HELLO with its key takes it over : a second. */
	public static final int TAKEOVER_TICKS = 60;
	/** Refusals and goodbyes go unanswered : sent a few times, since any one of them may be lost. */
	static final int GOODBYE_COPIES = 3;

	/** One peer that was welcomed. */
	public static final class Seat
	{
		public final Net_Peer peer;
		public final PlayerId player;
		/** The rejoin key the machine said HELLO with. */
		public final long key;
		/** Whether the key names this player : false for a second process that shares a seated key. */
		final boolean keyed;
		/** The tick anything from this peer was last read at. */
		int lastHeard;
		/** The last tick number whose frame was applied, or taken : copies at or below it are ignored. */
		int lastFrame = -1;
		/** Frames that arrived and wait for the tick they claim. */
		final TreeMap<Integer, Integer> pending = new TreeMap<Integer, Integer>();
		/** The held buttons last applied, kept while nothing arrives. */
		int held;
		/** Counters, for the gates. */
		public int framesApplied, framesIgnored, pressesApplied;

		Seat(Net_Peer peer, PlayerId player, long key, boolean keyed, int tick)
		{
			this.peer = peer;
			this.player = player;
			this.key = key;
			this.keyed = keyed;
			this.lastHeard = tick;
			// A client's first frames claim the tick it was welcomed at : nothing before it is theirs
			this.lastFrame = tick - 1;
		}
	}

	/** What a host may want to hear about, beyond the counters : a log, a lobby row. */
	public interface Events
	{
		default void joined(Seat seat) {}
		/** A machine this run had seen is seated again as the player it was : after leaving, or over a silent seat. */
		default void returned(Seat seat) {}
		default void left(Seat seat, Net_Message.Leave.Reason reason) {}
	}

	private final Net_Transport transport;
	private final Host_Simulation simulation;
	/** Settable, so a gate can listen to a session already running. */
	public Events events;
	private final Map<Net_Peer, Seat> seats = new LinkedHashMap<Net_Peer, Seat>();
	/** Every rejoin key this run has seated, and the player it is. */
	private final Map<Long, PlayerId> players = new HashMap<Long, PlayerId>();
	/** The keyed players who left and keep their row, the one who left longest ago first. */
	private final Map<Long, PlayerId> away = new LinkedHashMap<Long, PlayerId>();
	private final Net_Listener listener = new Net_Listener()
	{
		@Override
		public void received(Net_Peer from, ByteBuffer payload)
		{
			HostSession.this.received(from, payload);
		}

		@Override
		public void lost(Net_Peer peer)
		{
			Seat seat = seats.get(peer);
			if (seat != null)
				leave(seat, Net_Message.Leave.Reason.TIMEOUT);
		}
	};

	/** The ticks simulated so far : the next one to run is this one. */
	private int tick;
	public int snapshotsSent, rejected;

	public HostSession(Net_Transport transport, Host_Simulation simulation)
	{
		this(transport, simulation, new Events() {});
	}

	public HostSession(Net_Transport transport, Host_Simulation simulation, Events events)
	{
		this.transport = transport;
		this.simulation = simulation;
		this.events = events;
	}

	/**
	 * One tick of the run : what arrived is read, every player's frames for this tick are pressed,
	 * the world is stepped, and every third tick the world is read and sent to every peer.
	 */
	public void tick(Runnable step)
	{
		transport.pump(listener);

		for (Seat seat : seats.values())
			applyFrames(seat);

		step.run();
		tick++;

		if (tick % TICKS_PER_SNAPSHOT == 0 && !seats.isEmpty())
			broadcast(simulation.read(tick));
	}

	/** The run ends : every peer is told (no host migration, section 6), then forgotten. */
	@Override
	public void close()
	{
		for (Seat seat : new ArrayList<Seat>(seats.values()))
		{
			goodbye(seat.peer, seat.player.number(), Net_Message.Leave.Reason.HOST_ENDED);
			leave(seat, Net_Message.Leave.Reason.HOST_ENDED);
		}
	}

	/** The next tick to be simulated. Snapshots are stamped with the tick they are the state after. */
	public int tick()
	{
		return tick;
	}

	public List<Seat> seats()
	{
		return Collections.unmodifiableList(new ArrayList<Seat>(seats.values()));
	}

	// ---------------------------------------------------------------- arriving

	void received(Net_Peer from, ByteBuffer payload)
	{
		Net_Message message;
		try
		{
			message = Net_Codec.decode(payload);
		}
		catch (Net_Rejected e)
		{
			rejected++;
			// The one refusal worth answering : the other side can tell a person which version we speak
			if (e.reason == Net_Rejected.Reason.VERSION)
				goodbye(from, NOBODY, Net_Message.Leave.Reason.VERSION);
			return;
		}

		Seat seat = seats.get(from);
		if (seat != null)
			seat.lastHeard = tick;
		if (message.type() == Net_Message.Type.HELLO)
		{
			hello(from, seat, ((Net_Message.Hello) message).key);
			return;
		}
		// Not welcomed : whoever it is, it has not asked
		if (seat == null)
			return;

		switch (message.type())
		{
			case JOIN:
				if (!simulation.hasHero(seat.player))
				{
					simulation.spawn(seat.player);
					events.joined(seat);
				}
				break;
			case INPUT:
				queue(seat, (Net_Input) message);
				break;
			case LEAVE:
				leave(seat, Net_Message.Leave.Reason.QUIT);
				break;
			default:
				// KEEPALIVE : hearing it was the point. WELCOME and SNAPSHOT are not a client's to send
				break;
		}
	}

	void hello(Net_Peer from, Seat seat, long key)
	{
		if (seat == null)
			seat = seat(from, key);
		if (seat != null)
			send(from, new Net_Message.Welcome(seat.player.number(), tick));
	}

	/** The seat a HELLO from an unknown peer gets, or null when it was refused. */
	Seat seat(Net_Peer from, long key)
	{
		PlayerId known = players.get(Long.valueOf(key));
		Seat holder = null;
		for (Seat other : seats.values())
			if (other.keyed && other.player.equals(known))
				holder = other;

		// The machine is back before its old connection timed out : the silent seat is its, hero and all
		if (holder != null && tick - holder.lastHeard >= TAKEOVER_TICKS)
		{
			seats.remove(holder.peer);
			Seat seat = new Seat(from, holder.player, key, true, tick);
			seats.put(from, seat);
			events.returned(seat);
			return seat;
		}

		if (seats.size() >= MAX_PLAYERS)
		{
			goodbye(from, NOBODY, Net_Message.Leave.Reason.FULL);
			return null;
		}

		// Back after leaving : the row it left is still there
		if (holder == null && known != null)
		{
			away.remove(Long.valueOf(key));
			Seat seat = new Seat(from, known, key, true, tick);
			seats.put(from, seat);
			events.returned(seat);
			return seat;
		}

		PlayerId player = simulation.newPlayer();
		if (player.number() >= NOBODY)
		{
			goodbye(from, NOBODY, Net_Message.Leave.Reason.FULL);
			return null;
		}
		boolean keyed = holder == null;
		if (keyed)
			players.put(Long.valueOf(key), player);
		// Room for the new row : the player who left longest ago is not coming back for it
		Iterator<Map.Entry<Long, PlayerId>> oldest = away.entrySet().iterator();
		while (rows() + (keyed ? 0 : 1) > MAX_ROWS && oldest.hasNext())
		{
			Map.Entry<Long, PlayerId> gone = oldest.next();
			oldest.remove();
			players.remove(gone.getKey());
			simulation.forget(gone.getValue());
		}
		Seat seat = new Seat(from, player, key, keyed, tick);
		seats.put(from, seat);
		return seat;
	}

	/** The score rows this session keeps : every keyed player, seated or away, and the unkeyed ones seated. */
	int rows()
	{
		int rows = players.size();
		for (Seat seat : seats.values())
			if (!seat.keyed)
				rows++;
		return rows;
	}

	void queue(Seat seat, Net_Input input)
	{
		for (int age = input.count - 1; age >= 0; age--)
		{
			int frameTick = input.tick - age;
			if (frameTick <= seat.lastFrame || seat.pending.containsKey(frameTick))
			{
				seat.framesIgnored++;
				continue;
			}
			seat.pending.put(frameTick, input.frames[age]);
		}
		while (seat.pending.size() > MAX_PENDING)
			seat.pending.pollFirstEntry();
	}

	/** Every frame whose tick has come, or is too far ahead to be honest, lands on this tick. */
	void applyFrames(Seat seat)
	{
		int held = seat.held, pressed = 0, applied = 0;
		Iterator<Map.Entry<Integer, Integer>> it = seat.pending.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Integer, Integer> frame = it.next();
			int claims = frame.getKey().intValue();
			// Its tick has not come : it waits, and so does every frame after it
			if (claims > tick && claims - tick <= MAX_LEAD)
				break;
			int buttons = frame.getValue().intValue();
			held = buttons & (Net_Input.LEFT | Net_Input.RIGHT);
			pressed |= buttons & (Net_Input.JUMP | Net_Input.SWING_LEFT | Net_Input.SWING_RIGHT);
			seat.lastFrame = claims;
			applied++;
			it.remove();
		}
		seat.framesApplied += applied;
		seat.pressesApplied += Integer.bitCount(pressed);
		seat.held = held;
		simulation.press(seat.player, held, pressed);
	}

	// ---------------------------------------------------------------- leaving

	void leave(Seat seat, Net_Message.Leave.Reason reason)
	{
		seats.remove(seat.peer);
		simulation.remove(seat.player);
		if (seat.keyed)
			away.put(Long.valueOf(seat.key), seat.player);
		else
			simulation.forget(seat.player);
		events.left(seat, reason);
	}

	void goodbye(Net_Peer peer, int player, Net_Message.Leave.Reason reason)
	{
		for (int i = 0; i < GOODBYE_COPIES; i++)
			send(peer, new Net_Message.Leave(player, reason));
	}

	// ---------------------------------------------------------------- sending

	void broadcast(Net_Snapshot snapshot)
	{
		ByteBuffer packet = Net_Codec.encode(snapshot);
		for (Seat seat : seats.values())
		{
			transport.send(seat.peer, packet.duplicate());
			snapshotsSent++;
		}
	}

	void send(Net_Peer peer, Net_Message message)
	{
		transport.send(peer, Net_Codec.encode(message));
	}
}
