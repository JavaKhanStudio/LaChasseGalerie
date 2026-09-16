package jks.lobby;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.function.LongSupplier;

import jks.net.Lobby_Codec;
import jks.net.Lobby_Message;
import jks.net.Net_Listener;
import jks.net.Net_Peer;
import jks.net.Net_Rejected;
import jks.net.Net_Transport;
import jks.net.Stun_Codec;

/**
 * A player's side of the lobby service (phase 2.1) : host a lobby, list lobbies, join one by code,
 * and learn this machine's public address on the way.
 *
 * IT SHARES THE GAME'S SOCKET, and that is the whole design (the trap in docs/online-multiplayer.md
 * section 3) : the address the service sees is then exactly the mapping game traffic will arrive on.
 * So a client is built on the ONE transport the game will use, and hands the session {@link #game()},
 * a view of the same socket with the service's packets taken out. A HostSession or ClientSession
 * pumps that view as it always did ; whoever pumps - the session, or {@link #pump()} while there is no
 * session yet - the service's packets come here and the rest go to the game, in the order they arrived.
 *
 * Nothing is reliable, so everything is repeated : a host sends HOST every {@link #REFRESH_MS} (it is
 * the lobby's heartbeat and the NAT keepalive at once), a join sends JOIN every {@link #RETRY_MS}
 * until {@link #stopJoining()} - which a caller does when the game connection answers, not when the
 * service does, because every copy also re-tells the host where to punch - and a client with nothing
 * else to say PINGs every {@link #REFRESH_MS} so a lobby screen that sat for two minutes can still join.
 *
 * Which way the players can reach each other is {@link #ice()}'s (phase 2.2, r42) : the checks start by
 * themselves, for a host on every joiner the service mirrors and for a joiner on the host it is told
 * about, and their STUN packets are taken off the same socket as the service's. {@link #candidates}
 * starts as the transport's own addresses.
 *
 * Addresses are the transport's text : hand {@link #joined()}'s to the same transport, and a joiner's
 * game to {@code ice().link(joined().host.get(0)).address()} once that says DIRECT.
 *
 * Not thread safe : pump it, or its game view, from the loop.
 */
public final class Lobby_Client implements AutoCloseable
{
	/**
	 * A NAT mapping dies after about 30 s, and a transport forgets a peer after 10 s of silence : at 3 s,
	 * two answers in a row can be lost without either happening, and six refreshes fit in the service's reap.
	 */
	public static final long REFRESH_MS = 3_000;
	/** A question with nobody waiting on the answer yet : half a second. */
	public static final long RETRY_MS = 500;
	/** Game packets held while nobody pumps the game view. Past it, the oldest go, like a full socket buffer. */
	static final int HELD = 256;

	private final Net_Transport shared;
	private final Net_Peer service;
	private final LongSupplier clock;

	/** Addresses this machine can also be reached at, sent with HOST and JOIN : the transport's IPv6 and LAN ones to start with. */
	public final List<String> candidates = new ArrayList<String>();

	private final Lobby_Ice ice;
	private String publicAddress;
	private int outdated = -1;
	private boolean serviceLost;

	private boolean hosting;
	private String code;
	private int players, seats;
	private final List<List<String>> joiners = new ArrayList<List<String>>();

	private boolean browsing;
	private Lobby_Message.Listing listing;

	private String joining;
	private Lobby_Message.Joined joined;
	private Lobby_Message.Refused refused;

	private long lastSent = Long.MIN_VALUE / 2, lastHost = Long.MIN_VALUE / 2, lastJoin = Long.MIN_VALUE / 2, lastBrowse = Long.MIN_VALUE / 2;
	/** Game packets that arrived while this client was the one pumping. */
	private final Deque<Object[]> held = new ArrayDeque<Object[]>();
	public int rejected, heldDropped;

	private final Net_Transport game = new Net_Transport()
	{
		@Override
		public Net_Peer resolve(String address)
		{
			return shared.resolve(address);
		}

		@Override
		public void send(Net_Peer peer, ByteBuffer payload)
		{
			ice.played(peer.address());
			shared.send(peer, payload);
		}

		@Override
		public int pump(Net_Listener listener)
		{
			int delivered = 0;
			while (!held.isEmpty())
			{
				Object[] event = held.pollFirst();
				if (event[1] == null)
					listener.lost((Net_Peer) event[0]);
				else
				{
					listener.received((Net_Peer) event[0], ByteBuffer.wrap((byte[]) event[1]));
					delivered++;
				}
			}
			delivered += shared.pump(route(listener));
			update();
			return delivered;
		}

		@Override
		public int localPort()
		{
			return shared.localPort();
		}

		@Override
		public List<String> localAddresses()
		{
			return shared.localAddresses();
		}

		@Override
		public int dropped()
		{
			return shared.dropped();
		}

		/** The socket is the lobby client's owner's to close : a session ending does not end the lobby. */
		@Override
		public void close()
		{
		}
	};

	public Lobby_Client(Net_Transport shared, String serviceAddress, LongSupplier clock)
	{
		this.shared = shared;
		this.service = shared.resolve(serviceAddress);
		this.clock = clock;
		this.ice = new Lobby_Ice(shared, clock, new Random(), this::publicAddress);
		candidates.addAll(shared.localAddresses());
	}

	/** Who this machine can reach, and how : the probe, and a check per player the lobby put it in touch with. */
	public Lobby_Ice ice()
	{
		return ice;
	}

	/** The same socket, for HostSession or ClientSession : the service's packets never reach them. */
	public Net_Transport game()
	{
		return game;
	}

	/** Reads what arrived and sends what is due, when no session is pumping {@link #game()}. Game packets wait for it. */
	public void pump()
	{
		shared.pump(route(null));
		update();
	}

	// ---------------------------------------------------------------- asking

	/** Opens a lobby with this many seats, and keeps it open until {@link #close()}. */
	public void host(int seats)
	{
		hosting = true;
		this.seats = seats;
		players = Math.min(players, seats);
		sendHost();
	}

	/** How many players the host has now : the list shows it, and a full lobby refuses joins. Sent at once when it changes. */
	public void players(int players)
	{
		players = Math.max(0, Math.min(players, seats));
		if (players == this.players)
			return;
		this.players = players;
		if (hosting)
			sendHost();
	}

	/** Asks for the open lobbies of this game's version, until a {@link #listing()} comes. */
	public void browse()
	{
		browsing = true;
		listing = null;
		send(new Lobby_Message.Browse());
		lastBrowse = clock.getAsLong();
	}

	/**
	 * Asks to join the lobby of this code, as a person typed it, and keeps asking until
	 * {@link #stopJoining()} or a refusal. Returns false if it cannot be a code at all.
	 */
	public boolean join(String typed)
	{
		String code = Lobby_Codec.normalise(typed);
		if (code == null)
			return false;
		joining = code;
		joined = null;
		refused = null;
		sendJoin();
		return true;
	}

	/** The game connection answered : the host does not need telling again. */
	public void stopJoining()
	{
		joining = null;
	}

	/** Closes the lobby this machine hosts, if any. A few copies : any one may be lost, and the reap is 20 s away. */
	@Override
	public void close()
	{
		if (hosting && code != null)
			for (int i = 0; i < 3; i++)
				send(new Lobby_Message.Close(code));
		hosting = false;
		code = null;
		joining = null;
		browsing = false;
	}

	// ---------------------------------------------------------------- what is known

	/** The code of the lobby this machine hosts, once the service opened it ; null before. */
	public String code()
	{
		return code;
	}

	/** This machine's address as the service sees it - where game traffic will arrive - or null before any answer. */
	public String publicAddress()
	{
		return publicAddress;
	}

	/** The last list of lobbies, or null while it is being asked for. */
	public Lobby_Message.Listing listing()
	{
		return listing;
	}

	/** Where the host of the code being joined can be reached, public address first ; null until the service answers. */
	public Lobby_Message.Joined joined()
	{
		return joined;
	}

	/** The last no : a code that is not open, a lobby full or of another version, a service full. Null otherwise. */
	public Lobby_Message.Refused refused()
	{
		return refused;
	}

	/** For a host : the players the service said are coming, each as its addresses, since the last call. The punch goes to them. */
	public List<List<String>> takeJoiners()
	{
		List<List<String>> taken = new ArrayList<List<String>>(joiners);
		joiners.clear();
		return taken;
	}

	/** The lobby version the service speaks, when it is not this game's ; -1 otherwise. Worth telling a person : update the game. */
	public int serviceOutdated()
	{
		return outdated;
	}

	/** The transport heard nothing from the service for its timeout. The next answer clears it. */
	public boolean serviceLost()
	{
		return serviceLost;
	}

	// ---------------------------------------------------------------- arriving

	/** Sends the service's packets here and the rest to the game listener, or holds them when there is none. */
	Net_Listener route(Net_Listener gameListener)
	{
		return new Net_Listener()
		{
			@Override
			public void received(Net_Peer from, ByteBuffer payload)
			{
				// A connectivity check, a probe's answer : ICE's, whoever it is from
				if (Stun_Codec.isStunPacket(payload))
				{
					ice.received(from, payload);
					return;
				}
				if (Lobby_Codec.isLobbyPacket(payload))
				{
					// Only the service speaks the lobby protocol to us : anyone else is not believed
					if (from.equals(service))
						fromService(payload);
					return;
				}
				if (gameListener != null)
					gameListener.received(from, payload);
				else
				{
					byte[] copy = new byte[payload.remaining()];
					payload.duplicate().get(copy);
					hold(new Object[] { from, copy });
				}
			}

			@Override
			public void lost(Net_Peer peer)
			{
				if (peer.equals(service))
					serviceLost = true;
				else if (ice.swallowLost(peer))
					return; // only checks ever went there
				else if (gameListener != null)
					gameListener.lost(peer);
				else
					hold(new Object[] { peer, null });
			}
		};
	}

	void hold(Object[] event)
	{
		held.addLast(event);
		while (held.size() > HELD)
		{
			held.pollFirst();
			heldDropped++;
		}
	}

	void fromService(ByteBuffer payload)
	{
		Lobby_Message message;
		try
		{
			message = Lobby_Codec.decode(payload);
		}
		catch (Net_Rejected e)
		{
			rejected++;
			return;
		}
		serviceLost = false;

		switch (message.type())
		{
			case OUTDATED:
				outdated = ((Lobby_Message.Outdated) message).version;
				break;
			case HOSTED:
				Lobby_Message.Hosted hosted = (Lobby_Message.Hosted) message;
				if (hosting)
					code = hosted.code;
				publicAddress = hosted.you;
				break;
			case LISTING:
				if (browsing)
				{
					listing = (Lobby_Message.Listing) message;
					browsing = false;
				}
				break;
			case JOINED:
				Lobby_Message.Joined answer = (Lobby_Message.Joined) message;
				publicAddress = answer.you;
				if (answer.code.equals(joining))
				{
					joined = answer;
					ice.check(answer.host);
				}
				break;
			case PEER:
				Lobby_Message.Peer peer = (Lobby_Message.Peer) message;
				if (hosting && peer.code.equals(code))
				{
					joiners.add(new ArrayList<String>(peer.joiner));
					ice.check(peer.joiner);
				}
				break;
			case REFUSED:
				Lobby_Message.Refused no = (Lobby_Message.Refused) message;
				refused = no;
				if (no.code != null && no.code.equals(joining))
					joining = null;
				break;
			case PONG:
				publicAddress = ((Lobby_Message.Pong) message).you;
				break;
			default:
				break;
		}
	}

	// ---------------------------------------------------------------- sending

	/** Everything that is due : the host's heartbeat, a join or a list not answered yet, a keepalive. */
	void update()
	{
		long now = clock.getAsLong();
		if (hosting && now - lastHost >= REFRESH_MS)
			sendHost();
		if (joining != null && now - lastJoin >= RETRY_MS)
			sendJoin();
		if (browsing && now - lastBrowse >= RETRY_MS)
			browse();
		if (now - lastSent >= REFRESH_MS)
			send(new Lobby_Message.Ping());
		ice.update();
	}

	void sendHost()
	{
		Lobby_Message.Host host = new Lobby_Message.Host();
		host.code = code;
		host.players = players;
		host.seats = seats;
		host.candidates.addAll(candidates);
		send(host);
		lastHost = clock.getAsLong();
	}

	void sendJoin()
	{
		Lobby_Message.Join join = new Lobby_Message.Join();
		join.code = joining;
		join.candidates.addAll(candidates);
		send(join);
		lastJoin = clock.getAsLong();
	}

	void send(Lobby_Message message)
	{
		shared.send(service, Lobby_Codec.encode(message));
		lastSent = clock.getAsLong();
	}
}
