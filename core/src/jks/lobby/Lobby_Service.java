package jks.lobby;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jks.net.Lobby_Codec;
import jks.net.Lobby_Message;
import jks.net.Net_Listener;
import jks.net.Net_Peer;
import jks.net.Net_Rejected;
import jks.net.Net_Transport;

/**
 * The lobby service : the small always-on process WE host (phase 2.1, docs/online-multiplayer.md
 * sections 2, 5 and 7). No game logic, no world, no snapshot : it holds open lobbies, lists them,
 * gates on the game's protocol version, mirrors each side's public endpoint to the other so they can
 * punch, and forgets a lobby whose host went quiet.
 *
 * WHAT IT SEES IS THE POINT. A player talks to it over the same socket the game uses (the rule in
 * {@link Net_Transport}'s javadoc), so the address a packet arrives from IS the public mapping that
 * player's game traffic will use : every HOSTED, JOINED, PEER and PONG hands that address back, and
 * the service is the players' STUN server for free.
 *
 * It talks only to a {@link Net_Transport} and holds addresses as the transport's text, so nothing in
 * here is UDP : the WebRTC signalling a browser player needs (d7, r46) is a front that feeds the same
 * lobbies, not a second service.
 *
 * A lobby lives as long as its host keeps sending HOST ({@link Lobby_Client#REFRESH_MS}) : it is gone
 * {@link #REAP_MS} after the last one, and at once on CLOSE. The transport's own timeout is not asked :
 * it differs between transports, and a lobby must not die sooner on one than on another.
 * A host that comes back asking for its old code gets it, if nobody took it meanwhile.
 *
 * The socket takes bytes from anyone and the answers are bigger than the questions, so each address
 * gets at most {@link #ANSWERS_PER_SECOND} answers : a forged source cannot turn the service into a
 * hose pointed at somebody else.
 *
 * Not thread safe : pump it from one loop.
 */
public final class Lobby_Service
{
	/** Four missed refreshes : one lost packet never closes a lobby. */
	public static final long REAP_MS = 20_000;
	public static final int MAX_LOBBIES = 4096;
	public static final int ANSWERS_PER_SECOND = 20;

	/** One open lobby. */
	public static final class Lobby
	{
		public final String code;
		public final Net_Peer host;
		public int game, players, seats;
		/** The host's public address first, then what it said it can also be reached at. */
		public final List<String> candidates = new ArrayList<String>();
		long lastHeard;

		Lobby(String code, Net_Peer host)
		{
			this.code = code;
			this.host = host;
		}
	}

	/** What the process around it may want to log. */
	public interface Events
	{
		default void opened(Lobby lobby) {}
		default void closed(Lobby lobby, String why) {}
		default void joining(Lobby lobby, Net_Peer joiner) {}
	}

	private final Net_Transport transport;
	private final LongSupplier clock;
	private final Random random;
	public Events events = new Events() {};

	/** In the order they opened, so a listing can show the newest first. */
	private final Map<String, Lobby> byCode = new LinkedHashMap<String, Lobby>();
	private final Map<Net_Peer, Lobby> byHost = new HashMap<Net_Peer, Lobby>();
	/** Per address : the second its answers are counted in, and how many it had. */
	private final Map<String, long[]> answered = new HashMap<String, long[]>();
	private long lastReap;

	/** The relay HOSTED and JOINED name (r45), and the secret its credentials are signed with ; null for none. */
	private String relayServer;
	private byte[] relaySecret;
	/** Wall-clock seconds, for the credential's expiry : the relay reads it against its own clock, not ours. */
	public LongSupplier epochSeconds = () -> System.currentTimeMillis() / 1000L;

	public int joins, refusals, rejected, outdated, throttled;

	/** A transport reporting a peer lost changes nothing : the reap decides, whatever the transport's timeout. */
	private final Net_Listener listener = (from, payload) -> received(from, payload);

	/** The random only draws codes : seed it in a test, give it a SecureRandom in the service. */
	public Lobby_Service(Net_Transport transport, LongSupplier clock, Random random)
	{
		this.transport = transport;
		this.clock = clock;
		this.random = random;
	}

	/** How long a credential the service mints is good for : a long evening's play, and a leak that dies overnight. */
	public static final long RELAY_CREDENTIAL_S = 24 * 3600;

	/**
	 * Names the game's relay in every HOSTED and JOINED, with a credential minted for each : coturn's
	 * use-auth-secret (TURN REST), the username its expiry, the password base64 HMAC-SHA1 of it under the
	 * relay's secret. The secret never leaves this process.
	 */
	public void relay(String server, String secret)
	{
		relayServer = server;
		relaySecret = secret == null ? null : secret.getBytes(StandardCharsets.UTF_8);
	}

	/** A fresh credential for the relay, for this lobby ; null when there is no relay. */
	Lobby_Message.Relay mintRelay(String code)
	{
		if (relayServer == null || relaySecret == null)
			return null;
		String username = (epochSeconds.getAsLong() + RELAY_CREDENTIAL_S) + ":" + code;
		try
		{
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(relaySecret, "HmacSHA1"));
			String password = Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
			return new Lobby_Message.Relay(relayServer, username, password);
		}
		catch (GeneralSecurityException e)
		{
			throw new IllegalStateException("every JDK has HmacSHA1", e);
		}
	}

	/** Reads what arrived, answers it, and reaps the lobbies whose host went quiet. Returns the packets read. */
	public int pump()
	{
		int read = transport.pump(listener);
		long now = clock.getAsLong();
		if (now - lastReap >= 1000)
		{
			lastReap = now;
			reap(now);
		}
		return read;
	}

	public List<Lobby> lobbies()
	{
		return new ArrayList<Lobby>(byCode.values());
	}

	public Lobby lobby(String code)
	{
		return byCode.get(code);
	}

	// ---------------------------------------------------------------- arriving

	void received(Net_Peer from, ByteBuffer payload)
	{
		Lobby_Message message;
		try
		{
			message = Lobby_Codec.decode(payload);
		}
		catch (Net_Rejected e)
		{
			rejected++;
			// The one refusal worth answering : an old game can tell a person to update
			if (e.reason == Net_Rejected.Reason.VERSION)
			{
				outdated++;
				answer(from, new Lobby_Message.Outdated());
			}
			return;
		}

		switch (message.type())
		{
			case HOST:
				host(from, (Lobby_Message.Host) message);
				break;
			case CLOSE:
				Lobby lobby = byHost.get(from);
				// Only its own host closes a lobby
				if (lobby != null && lobby.code.equals(((Lobby_Message.Close) message).code))
					remove(lobby, "closed");
				break;
			case BROWSE:
				browse(from, (Lobby_Message.Browse) message);
				break;
			case JOIN:
				join(from, (Lobby_Message.Join) message);
				break;
			case PING:
				answer(from, new Lobby_Message.Pong(from.address()));
				break;
			default:
				// What the service sends is not a player's to send it
				break;
		}
	}

	void host(Net_Peer from, Lobby_Message.Host message)
	{
		Lobby lobby = byHost.get(from);
		if (lobby == null)
		{
			if (byCode.size() >= MAX_LOBBIES)
			{
				refusals++;
				answer(from, new Lobby_Message.Refused(message.code, Lobby_Message.Refused.Reason.SERVICE_FULL, message.game));
				return;
			}
			// Its old code back, if it had one and nobody took it : its friends were told that one
			String code = message.code != null && !byCode.containsKey(message.code) ? message.code : newCode();
			lobby = new Lobby(code, from);
			byCode.put(code, lobby);
			byHost.put(from, lobby);
			update(lobby, from, message);
			events.opened(lobby);
		}
		else
			update(lobby, from, message);
		Lobby_Message.Hosted hosted = new Lobby_Message.Hosted(lobby.code, from.address());
		// The host does not allocate today : it reads the relay's ip, to call a route through it RELAYED
		hosted.relay = mintRelay(lobby.code);
		answer(from, hosted);
	}

	void update(Lobby lobby, Net_Peer from, Lobby_Message.Host message)
	{
		lobby.game = message.game;
		lobby.players = message.players;
		lobby.seats = message.seats;
		lobby.candidates.clear();
		addCandidates(lobby.candidates, from.address(), message.candidates);
		lobby.lastHeard = clock.getAsLong();
	}

	void browse(Net_Peer from, Lobby_Message.Browse message)
	{
		Lobby_Message.Listing listing = new Lobby_Message.Listing();
		listing.game = message.game;
		List<Lobby> newestFirst = new ArrayList<Lobby>(byCode.values());
		for (int i = newestFirst.size() - 1; i >= 0; i--)
		{
			Lobby lobby = newestFirst.get(i);
			if (lobby.game != message.game)
				continue;
			listing.total++;
			if (listing.rows.size() < Lobby_Codec.MAX_ROWS)
				listing.rows.add(new Lobby_Message.Row(lobby.code, lobby.players, lobby.seats));
		}
		listing.total = Math.min(listing.total, 0xFFFF);
		answer(from, listing);
	}

	void join(Net_Peer from, Lobby_Message.Join message)
	{
		Lobby lobby = byCode.get(message.code);
		Lobby_Message.Refused.Reason no = null;
		if (lobby == null)
			no = Lobby_Message.Refused.Reason.NO_SUCH_LOBBY;
		else if (lobby.game != message.game)
			no = Lobby_Message.Refused.Reason.VERSION;
		else if (lobby.players >= lobby.seats)
			no = Lobby_Message.Refused.Reason.FULL;
		if (no != null)
		{
			refusals++;
			answer(from, new Lobby_Message.Refused(message.code, no, lobby != null ? lobby.game : message.game));
			return;
		}

		// The mirror : each side learns where the other's packets will come from
		Lobby_Message.Joined joined = new Lobby_Message.Joined();
		joined.code = lobby.code;
		joined.you = from.address();
		joined.host.addAll(lobby.candidates);
		joined.relay = mintRelay(lobby.code);
		Lobby_Message.Peer peer = new Lobby_Message.Peer();
		peer.code = lobby.code;
		addCandidates(peer.joiner, from.address(), message.candidates);

		if (answer(from, joined))
		{
			joins++;
			// Counted against the host too : a flood of forged joins must not become a flood at a player
			answer(lobby.host, peer);
			events.joining(lobby, from);
		}
	}

	// ---------------------------------------------------------------- leaving

	void reap(long now)
	{
		for (Lobby lobby : new ArrayList<Lobby>(byCode.values()))
			if (now - lobby.lastHeard > REAP_MS)
				remove(lobby, "no refresh for " + (now - lobby.lastHeard) / 1000 + " s");
		// A second's count is only worth keeping during that second
		for (Iterator<long[]> it = answered.values().iterator(); it.hasNext();)
			if (it.next()[0] < now / 1000)
				it.remove();
	}

	void remove(Lobby lobby, String why)
	{
		byCode.remove(lobby.code);
		byHost.remove(lobby.host);
		events.closed(lobby, why);
	}

	// ---------------------------------------------------------------- sending

	/** Sends unless this address had its share of answers this second. */
	boolean answer(Net_Peer to, Lobby_Message message)
	{
		long second = clock.getAsLong() / 1000;
		long[] count = answered.get(to.address());
		if (count == null)
			answered.put(to.address(), count = new long[2]);
		if (count[0] != second)
		{
			count[0] = second;
			count[1] = 0;
		}
		if (++count[1] > ANSWERS_PER_SECOND)
		{
			throttled++;
			return false;
		}
		transport.send(to, Lobby_Codec.encode(message));
		return true;
	}

	String newCode()
	{
		char[] code = new char[Lobby_Codec.CODE_LENGTH];
		do
		{
			for (int i = 0; i < code.length; i++)
				code[i] = Lobby_Codec.CODE_ALPHABET.charAt(random.nextInt(Lobby_Codec.CODE_ALPHABET.length()));
		}
		while (byCode.containsKey(new String(code)));
		return new String(code);
	}

	/** The address the service saw first, then the others once each, as many as a packet carries. */
	static void addCandidates(List<String> into, String seen, List<String> said)
	{
		into.add(seen);
		for (String address : said)
			if (!into.contains(address) && into.size() < Lobby_Codec.MAX_CANDIDATES)
				into.add(address);
	}
}
