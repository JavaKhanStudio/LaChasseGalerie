package jks.lobby;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.function.LongSupplier;

import jks.net.Lobby_Chunks;
import jks.net.Lobby_Codec;
import jks.net.Lobby_Message;
import jks.net.Net_Listener;
import jks.net.Net_Peer;
import jks.net.Net_Rejected;
import jks.net.Net_Tabs;
import jks.net.Net_Transport;
import jks.net.Stun_Codec;
import jks.net.Turn_Client;
import jks.net.Turn_Codec;

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
 * THE RELAY (r45) : when JOINED names one, the joiner allocates on it at once ({@link #relay()}), offers
 * the relayed address with its next JOIN, and lets the host's ip through it. Its own checks to the host
 * through the relay go to {@code "relay/<host>"}, a peer only this client's views know : {@link #game()}
 * resolves and sends to it like any other, so a session built on a RELAYED row never knows.
 *
 * A BROWSER TAB (r79) reaches a host through the service's WebSocket front : its WebRTC offer arrives
 * here in OFFER_PARTs, whole or not at all, and a host takes it with {@link #takeOffers()} and gives its
 * description back with {@link #answer}, which goes to the service in parts under the same call.
 *
 * A HOST THAT TAKES TABS (r80) has a {@link Net_Tabs} plugged in with {@link #tabs(Net_Tabs)} : it then
 * answers each offer by itself, sends the answer once gathered, and {@link #game()} carries the tabs'
 * peers ("rtc/N") beside the socket's, pumped and held the same way, so HostSession sees a tab like any
 * joiner. With none plugged - no WebRTC on this machine - offers wait in {@link #takeOffers()}, at most
 * {@link #HELD_OFFERS} of them, and one nobody answers is a tab refused.
 *
 * Addresses are the transport's text : hand {@link #joined()}'s to the same transport, and a joiner's
 * game to {@code ice().link(joined().host.get(0)).address()} once that row is usable (DIRECT or RELAYED).
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
	/** Offers kept for {@link #takeOffers()} when nobody takes them : a host with no tabs refuses by never answering. */
	public static final int HELD_OFFERS = 16;

	private final Net_Transport shared;
	private final Net_Peer service;
	private final LongSupplier clock;

	/** Addresses this machine can also be reached at, sent with HOST and JOIN : the transport's IPv6 and LAN ones to start with. */
	public final List<String> candidates = new ArrayList<String>();

	private final Lobby_Ice ice;
	private final Random random = new Random();
	/** The joiner's allocation on the relay JOINED named ; null until then, or when there is none. */
	private Turn_Client relay;
	private boolean relayOffered;
	private String publicAddress;
	private int outdated = -1;
	private boolean serviceLost;

	private boolean hosting;
	private String code;
	private int players, seats;
	private boolean unlisted;
	private final List<List<String>> joiners = new ArrayList<List<String>>();

	/** A tab's WebRTC offer, whole (r79) : answer it under its {@link #call} with {@link Lobby_Client#answer}. */
	public static final class Call
	{
		/** The service's name for this offer, which the answer must carry back. */
		public final int call;
		public final String sdp;

		Call(int call, String sdp)
		{
			this.call = call;
			this.sdp = sdp;
		}
	}

	private final List<Call> offers = new ArrayList<Call>();
	private final Lobby_Chunks chunks = new Lobby_Chunks();
	/** Offers dropped unanswered : past {@link #HELD_OFFERS} with no tabs plugged, or answered with a failure. */
	public int offersRefused;

	/** A tab this host answered (r80) : its call, and whether the answer went. Gone once its peer is lost. */
	public static final class Tab
	{
		public final int call;
		public final Net_Tabs.Tab tab;
		boolean answered;

		Tab(int call, Net_Tabs.Tab tab)
		{
			this.call = call;
			this.tab = tab;
		}

		/** The answer went to the service : what is left is the tab's ICE and the channel. */
		public boolean answered()
		{
			return answered;
		}
	}

	private Net_Tabs tabs;
	private final List<Tab> answering = new ArrayList<Tab>();

	private boolean browsing;
	private Lobby_Message.Listing listing;

	private String joining;
	private Lobby_Message.Joined joined;
	private Lobby_Message.Refused refused;

	private long lastSent = Long.MIN_VALUE / 2, lastHost = Long.MIN_VALUE / 2, lastJoin = Long.MIN_VALUE / 2, lastBrowse = Long.MIN_VALUE / 2;
	/** Game packets that arrived while this client was the one pumping. */
	private final Deque<Object[]> held = new ArrayDeque<Object[]>();
	public int rejected, heldDropped;

	/** The socket, and the relay behind it : a "relay/" peer is sent through the relay, anything else straight. What ICE and the game both send on. */
	private final Net_Transport wire = new Net_Transport()
	{
		@Override
		public Net_Peer resolve(String address)
		{
			if (Turn_Client.isRelayed(address))
			{
				if (relay == null)
					throw new IllegalArgumentException("no relay to reach " + address + " through");
				return relay.peer(address);
			}
			return shared.resolve(address);
		}

		@Override
		public void send(Net_Peer peer, ByteBuffer payload)
		{
			if (Turn_Client.isRelayed(peer.address()))
				relay.send(peer, payload);
			else
				shared.send(peer, payload);
		}

		@Override
		public int pump(Net_Listener listener)
		{
			throw new UnsupportedOperationException("the lobby client pumps the socket");
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

		@Override
		public void close()
		{
		}
	};

	private final Net_Transport game = new Net_Transport()
	{
		@Override
		public Net_Peer resolve(String address)
		{
			if (tabs != null && Net_Tabs.isTab(address))
				return tabs.resolve(address);
			return wire.resolve(address);
		}

		@Override
		public void send(Net_Peer peer, ByteBuffer payload)
		{
			if (Net_Tabs.isTab(peer.address()))
			{
				// A tab that went with its tabs : dropped, like a packet to a peer that left
				if (tabs != null)
					tabs.send(peer, payload);
				return;
			}
			ice.played(peer.address());
			wire.send(peer, payload);
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
			Net_Listener routed = route(listener);
			delivered += shared.pump(routed);
			relayLost(routed);
			delivered += pumpTabs(listener);
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
		this.ice = new Lobby_Ice(wire, clock, random, this::publicAddress);
		candidates.addAll(shared.localAddresses());
	}

	/** Who this machine can reach, and how : the probe, and a check per player the lobby put it in touch with. */
	public Lobby_Ice ice()
	{
		return ice;
	}

	/** The joiner's allocation on the relay, or null : no relay named yet, or this machine hosts. */
	public Turn_Client relay()
	{
		return relay;
	}

	/** The same socket, for HostSession or ClientSession : the service's packets never reach them. */
	public Net_Transport game()
	{
		return game;
	}

	/** Reads what arrived and sends what is due, when no session is pumping {@link #game()}. Game packets wait for it. */
	public void pump()
	{
		Net_Listener routed = route(null);
		shared.pump(routed);
		relayLost(routed);
		pumpTabs(null);
		update();
	}

	/** The tabs' packets and losses, to the game listener or held for it like the socket's. A lost tab's row goes. */
	int pumpTabs(Net_Listener gameListener)
	{
		if (tabs == null)
			return 0;
		return tabs.pump(new Net_Listener()
		{
			@Override
			public void received(Net_Peer from, ByteBuffer payload)
			{
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
				for (Iterator<Tab> it = answering.iterator(); it.hasNext();)
				{
					Tab tab = it.next();
					if (!tab.tab.peer().address().equals(peer.address()))
						continue;
					// Gone before its answer could go : the tab was refused
					if (!tab.answered)
						offersRefused++;
					it.remove();
				}
				if (gameListener != null)
					gameListener.lost(peer);
				else
					hold(new Object[] { peer, null });
			}
		});
	}

	/**
	 * Takes browser tabs as players (r80) : from now on this host answers their offers itself, and
	 * {@link #game()} carries their peers. Null takes them away. The client owns it : {@link #close()} closes it.
	 */
	public void tabs(Net_Tabs tabs)
	{
		if (this.tabs != null && this.tabs != tabs)
			this.tabs.close();
		this.tabs = tabs;
		answering.clear();
	}

	/** Whether this host takes tabs : false when none is plugged, as on a machine whose WebRTC did not load. */
	public boolean takesTabs()
	{
		return tabs != null;
	}

	/** The tabs this host answered and has not lost yet, in the order they came : a lobby screen's rows. */
	public List<Tab> tabRows()
	{
		return Collections.unmodifiableList(answering);
	}

	/** Relayed peers that went quiet, told like the socket's own. */
	void relayLost(Net_Listener routed)
	{
		if (relay != null)
			for (Net_Peer peer : relay.takeLost())
				routed.lost(peer);
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

	/** A private lobby (r76) : Open games never lists it, and its code alone lets a friend in. Sent at once when it changes. */
	public void unlisted(boolean unlisted)
	{
		if (unlisted == this.unlisted)
			return;
		this.unlisted = unlisted;
		if (hosting)
			sendHost();
	}

	public boolean unlisted()
	{
		return unlisted;
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
		if (relay != null)
			relay.close();
		tabs(null);
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

	/** For a host : the tabs' offers that arrived whole since the last call (r79). */
	public List<Call> takeOffers()
	{
		chunks.expire(clock.getAsLong());
		List<Call> taken = new ArrayList<Call>(offers);
		offers.clear();
		return taken;
	}

	/** Parts of offers still missing one : a part lost on the way fails its offer whole, after {@link Lobby_Chunks#TIMEOUT_MS}. */
	public Lobby_Chunks offerParts()
	{
		return chunks;
	}

	/**
	 * For a host : this machine's description, for the tab that made that offer. Sent once, in parts : a
	 * part lost is an answer lost, and the tab offers again.
	 */
	public void answer(int call, String sdp)
	{
		if (!hosting || code == null)
			throw new IllegalStateException("only a host with a lobby answers an offer");
		for (Lobby_Message.Part part : Lobby_Chunks.split(Lobby_Message.Type.ANSWER_PART, code, call, sdp))
			send(part);
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
				// The relay's answers, and what it relays : unwrapped, then routed again as from the relayed peer
				if (relay != null && relay.fromServer(from) && Turn_Codec.isTurnPacket(payload))
				{
					relay.received(payload, this);
					return;
				}
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
				else if (relay != null && relay.fromServer(peer))
					return; // quiet between refreshes : the relay's own requests say when it is gone
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
				if (hosted.relay != null)
					ice.relayAt(hosted.relay.server);
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
					// One allocation per join, with the first credential : every JOINED after it mints another
					if (answer.relay != null && relay == null)
					{
						ice.relayAt(answer.relay.server);
						relay = new Turn_Client(shared, answer.relay.server, answer.relay.username, answer.relay.password, clock, random);
						relay.start();
					}
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
			case OFFER_PART:
				Lobby_Message.Part part = (Lobby_Message.Part) message;
				if (hosting && part.code.equals(code))
				{
					String sdp = chunks.add(service.address(), part, clock.getAsLong());
					if (sdp != null)
						offered(part.call, sdp);
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

	/** A tab's offer, whole : answered here when tabs are plugged, else kept for {@link #takeOffers()}, the oldest going first. */
	void offered(int call, String sdp)
	{
		if (tabs == null)
		{
			offers.add(new Call(call, sdp));
			while (offers.size() > HELD_OFFERS)
			{
				offers.remove(0);
				offersRefused++;
			}
			return;
		}
		answering.add(new Tab(call, tabs.answer(sdp)));
	}

	/** Each tab's answer once gathered, sent once ; a tab that failed before it could be answered is refused. */
	void updateTabs()
	{
		for (Iterator<Tab> it = answering.iterator(); it.hasNext();)
		{
			Tab tab = it.next();
			if (tab.answered)
				continue;
			if (tab.tab.failure() != null)
			{
				// Or when its loss is pumped, whichever comes first : once
				offersRefused++;
				it.remove();
			}
			else if (tab.tab.sdp() != null && hosting && code != null)
			{
				tab.answered = true;
				answer(tab.call, tab.tab.sdp());
			}
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
		if (relay != null)
			updateRelay();
		if (tabs != null)
			updateTabs();
		ice.update();
	}

	/** Once allocated : offer the relayed address to the host, let the host's ip through, check the host through it. */
	void updateRelay()
	{
		relay.update();
		String relayed = relay.relayed();
		if (relayed == null || relayOffered || joined == null)
			return;
		relayOffered = true;
		if (!candidates.contains(relayed))
		{
			if (candidates.size() >= Lobby_Codec.MAX_CANDIDATES)
				candidates.remove(candidates.size() - 1);
			candidates.add(relayed);
		}
		// The host's public address, as the service saw it : where its answers through the relay come from
		String host = joined.host.get(0);
		relay.permit(host);
		ice.check(java.util.Arrays.asList(host, Turn_Client.PREFIX + host));
		if (joining != null)
			sendJoin();
	}

	void sendHost()
	{
		Lobby_Message.Host host = new Lobby_Message.Host();
		host.code = code;
		host.players = players;
		host.seats = seats;
		host.unlisted = unlisted;
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
