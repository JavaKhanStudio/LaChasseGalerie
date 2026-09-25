package jks.lobby;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import jks.net.Net_Peer;
import jks.net.Net_Rejected;
import jks.net.Net_Transport;
import jks.net.Stun_Codec;

/**
 * Which way two players can reach each other, found out while they are still in the lobby (phase 2.2,
 * r42, docs/online-multiplayer.md section 9) : IPv6 direct first, then the IPv4 punch, then a relay.
 * Never port forwarding.
 *
 * NOT ice4j, on purpose : every ice4j harvester binds a socket of its own, and the game has ONE (the
 * trap in the doc's section 3). This is the subset of RFC 8445 the game needs, on {@link Net_Transport},
 * with STUN Binding packets ({@link Stun_Codec}) as the checks :
 * <ul>
 * <li>{@link #probe} - RFC 5780 : ask two STUN servers from the SAME socket what port they see, beside
 *     the lobby service's own view. One port for all : the mapping is endpoint-independent and punchable.
 *     Different ports : symmetric, and {@link #advice()} says so before anyone picks a hero ;</li>
 * <li>{@link #check} - started for a joiner the moment the service mirrors it (host) or answers JOINED
 *     (joiner) : both ends send a Binding request to every address the other offered, best first
 *     (IPv6, then LAN, then public). Sending it opens this end's router, an answer proves the pair.
 *     A request arriving from an address nobody offered is the other end's real mapping (peer-reflexive)
 *     and is checked at once. After {@link #PUNCH_MS} with no answer, a port search around each public
 *     address at {@link #SEARCH_PER_SECOND} : against a router that hands ports out one after the other,
 *     that finds a symmetric player's mapping ;</li>
 * <li>{@link Link#route()} - what a lobby row says, and {@link Link#advice()} the fix it names. A route
 *     that worked keeps being checked every {@link #CONSENT_MS} : a mapping that expired or a player who
 *     went from Wi-Fi to mobile shows as RECONNECTING, not as a row that pretends.</li>
 * </ul>
 *
 * THERE IS NO RELAY YET : r45 decides and deploys it. {@link Route#RELAYED} is its slot ; until then a
 * pair no check gets through is CANNOT_CONNECT, and both sides being symmetric is exactly that case.
 *
 * The joiner nominates (it is the side that opens the game connection) : {@link Link#address()} on the
 * joiner is where its ClientSession should send. The host's row only needs the route.
 *
 * Owned by {@link Lobby_Client}, which routes STUN packets here off the shared socket. Not thread safe.
 */
public final class Lobby_Ice
{
	/** Two public servers on different networks : the RFC 5780 probe needs two destinations, measured at 67 ms together. */
	public static final List<String> STUN_SERVERS = Collections.unmodifiableList(Arrays.asList("stun.l.google.com:19302", "stun.cloudflare.com:3478"));

	/** A probe question not answered is asked again after this ; after {@link #PROBE_MS} the probe is over. */
	static final long PROBE_RETRY_MS = 250, PROBE_MS = 3_000;
	/** A round of checks to every address of a pair still being checked. */
	public static final long ROUND_MS = 100;
	/** Plain checks alone for this long, then the port search joins them. */
	public static final long PUNCH_MS = 1_000;
	/** The doc's rate : half the one-sided symmetric pairs are through in under two seconds at it. */
	public static final int SEARCH_PER_SECOND = 100;
	/** Ports either side of an offered public port the search tries, nearest first. */
	public static final int SEARCH_SPAN = 256;
	/** Nothing got through for this long : CANNOT_CONNECT, and checks go on at the consent pace in case it changes. */
	public static final long GIVE_UP_MS = 4_000;
	/** After a first answer, better addresses get this long to answer too : IPv6 or LAN beats a punched public one. */
	public static final long SETTLE_MS = 300;
	/** A working route is checked this often, every round once an answer is late ; it doubles as the NAT keepalive while nobody plays. */
	public static final long CONSENT_MS = 1_000;
	/** A working route with no answer for this long is RECONNECTING. */
	public static final long STALE_MS = 3_500;
	/** A question is forgotten after this : an answer later than that is noise. */
	static final long TRANSACTION_MS = 5_000;
	/** Pairs this machine keeps : a host with the maximum of players and some that came and went. */
	public static final int MAX_LINKS = 32;

	/** How this machine's router maps the socket, as far as punching cares. */
	public enum Nat
	{
		/** Not known : the probe has not run, is running, or nobody answered it. */
		UNKNOWN,
		/** No NAT : the address the internet sees is on this machine. */
		OPEN,
		/** Endpoint-independent mapping : every destination sees the same port. Punchable. */
		EASY,
		/** A different port for every destination : symmetric, or carrier NAT. Only a port search or a relay reaches it. */
		HARD
	}

	/** What a lobby row says about the pair. */
	public enum Route
	{
		/** Checks are out, nothing has answered yet. */
		CHECKING,
		/** A check was answered : the game can go straight there. */
		DIRECT,
		/** Through the relay. Nothing is, until r45 deploys one. */
		RELAYED,
		/** Nothing got through in {@link #GIVE_UP_MS}. Checks go on slowly, and a later answer makes it DIRECT. */
		CANNOT_CONNECT,
		/** It was DIRECT and stopped answering : a mapping expired, a network changed. Being checked again. */
		RECONNECTING
	}

	private final Net_Transport shared;
	private final LongSupplier clock;
	private final Random random;
	/** This machine's address as the lobby service sees it : the name its checks carry, and one probe sample. */
	private final Supplier<String> self;

	private final Map<String, Probe> probes = new LinkedHashMap<String, Probe>();
	private long probeStarted = Long.MIN_VALUE;

	private final Map<String, Link> links = new LinkedHashMap<String, Link>();
	private final Map<String, Pending> pending = new LinkedHashMap<String, Pending>();
	/** Addresses only checks were sent to : their silence is ours, not the game's. */
	private final Set<String> probed = new HashSet<String>();
	/** Addresses the game itself sent to : their silence IS the game's. */
	private final Set<String> played = new HashSet<String>();

	public int rejected, answered, checksSent, searchesSent;

	public Lobby_Ice(Net_Transport shared, LongSupplier clock, Random random, Supplier<String> self)
	{
		this.shared = shared;
		this.clock = clock;
		this.random = random;
		this.self = self;
	}

	// ---------------------------------------------------------------- this machine

	/**
	 * Asks each STUN server, from the shared socket, which address it sees. Resolving a name happens here,
	 * once, and may take as long as a name lookup does : call it while a screen is opening, not per frame.
	 */
	public void probe(List<String> servers)
	{
		probes.clear();
		probeStarted = clock.getAsLong();
		for (String server : servers)
		{
			Probe probe = new Probe(server);
			try
			{
				probe.peer = shared.resolve(server);
			}
			catch (RuntimeException e)
			{
				probe.failed = true; // not an address, or a name that does not resolve : one sample fewer
			}
			probes.put(server, probe);
		}
		updateProbes(probeStarted);
	}

	/** True while probe questions are still out. */
	public boolean probing()
	{
		return probeStarted != Long.MIN_VALUE && clock.getAsLong() - probeStarted < PROBE_MS && answeredProbes() < probes.size();
	}

	/** Every address a STUN server or the service said this socket comes from. */
	public List<String> mapped()
	{
		List<String> mapped = new ArrayList<String>();
		for (Probe probe : probes.values())
			if (probe.mapped != null)
				mapped.add(probe.mapped);
		// A service on this machine or its LAN sees a LAN address, not the router's mapping : set beside a
		// STUN server's public sample it would read as a different port every time, HARD (r43, a local service)
		String service = self.get();
		if (service != null && rank(service) != RANK_LAN)
			mapped.add(service);
		return mapped;
	}

	/** Compares every sample of the family most of them came in : one port everywhere is EASY, two are HARD. */
	public Nat nat()
	{
		List<String> v4 = new ArrayList<String>(), v6 = new ArrayList<String>();
		for (String address : mapped())
			(address.startsWith("[") ? v6 : v4).add(address);
		List<String> samples = v4.size() >= v6.size() ? v4 : v6;
		for (String address : samples)
			if (shared.localAddresses().contains(address))
				return Nat.OPEN;
		if (samples.size() < 2)
			return Nat.UNKNOWN;
		for (String address : samples)
			if (!address.equals(samples.get(0)))
				return Nat.HARD;
		return Nat.EASY;
	}

	/** A LAN address in 100.64.0.0/10 : the provider's NAT sits behind the home one (RFC 6598). */
	public boolean carrierNat()
	{
		for (String address : shared.localAddresses())
		{
			byte[] ip = Stun_Codec.ipOf(address);
			if (ip != null && ip.length == 4 && (ip[0] & 0xFF) == 100 && (ip[1] & 0xC0) == 64)
				return true;
		}
		return false;
	}

	/** A global IPv6 address to offer : with another such player there is no NAT to punch at all. */
	public boolean ipv6()
	{
		for (String address : shared.localAddresses())
			if (rank(address) == RANK_IPV6)
				return true;
		return false;
	}

	/** What a person on this machine can do about its network, or null when there is nothing to fix (or nothing known yet). */
	public String advice()
	{
		if (nat() == Nat.HARD)
			return "Your connection gives every player a different port (mobile data or a strict router), so some players will not reach you."
					+ (ipv6() ? " Players with IPv6 still will." : "") + " Join over Wi-Fi if you can.";
		if (carrierNat())
			return "Your provider shares one address between many customers (carrier NAT), so some players may not reach you. Join over Wi-Fi or another connection if you can.";
		if (probeStarted != Long.MIN_VALUE && !probing() && answeredProbes() == 0)
			return "No connection test server answered : this network may block the game. Try another network, or Wi-Fi instead of a work or school one.";
		return null;
	}

	int answeredProbes()
	{
		int count = 0;
		for (Probe probe : probes.values())
			if (probe.mapped != null || probe.failed)
				count++;
		return count;
	}

	// ---------------------------------------------------------------- pairs

	/**
	 * Starts checking a pair, or adds addresses to one being checked. The first address is the key : what
	 * the lobby service saw, the name the other end's checks carry. Safe to call with every copy of a PEER
	 * or JOINED : a pair is never restarted by hearing about it again.
	 */
	public Link check(List<String> candidates)
	{
		if (candidates.isEmpty())
			return null;
		String key = candidates.get(0);
		Link link = links.get(key);
		if (link == null)
		{
			makeRoom();
			link = new Link(key, clock.getAsLong());
			links.put(key, link);
		}
		for (String address : candidates)
			link.offer(address, rank(address));
		return link;
	}

	/** The pair whose other end the lobby service sees at this address, or null. */
	public Link link(String key)
	{
		return links.get(key);
	}

	public Collection<Link> links()
	{
		return Collections.unmodifiableCollection(links.values());
	}

	/** Stops checking a pair : it left the lobby. */
	public void forget(String key)
	{
		links.remove(key);
	}

	void makeRoom()
	{
		if (links.size() < MAX_LINKS)
			return;
		// The oldest pair that is not working goes first ; if all work, the oldest
		Iterator<Link> it = links.values().iterator();
		String victim = null;
		while (it.hasNext())
		{
			Link link = it.next();
			if (victim == null)
				victim = link.key;
			if (link.route != Route.DIRECT)
			{
				victim = link.key;
				break;
			}
		}
		links.remove(victim);
	}

	// ---------------------------------------------------------------- the socket

	/** The game sent to this address : from now on its silence belongs to the game. */
	void played(String address)
	{
		played.add(address);
	}

	/** True when this peer was only ever a check's destination : its timeout is not the game's business. Forgets it if so. */
	boolean swallowLost(Net_Peer peer)
	{
		String address = peer.address();
		if (played.contains(address))
		{
			played.remove(address);
			return false;
		}
		return probed.remove(address);
	}

	/** A STUN packet off the shared socket. {@link Lobby_Client} routes them here ; a probe with no lobby (netnat) calls it itself. */
	public void received(Net_Peer from, ByteBuffer payload)
	{
		Stun_Codec.Binding binding;
		try
		{
			binding = Stun_Codec.decode(payload);
		}
		catch (Net_Rejected e)
		{
			rejected++;
			return;
		}
		long now = clock.getAsLong();

		if (!binding.success)
		{
			// Only a player's check is answered : a request with no name is a stranger using us as a free STUN server.
			// And only from an ip:port : a name on the in-memory wire has no bytes to answer with
			if (binding.username == null || Stun_Codec.ipOf(from.address()) == null)
				return;
			send(from, Stun_Codec.encode(binding.answer(from.address())));
			answered++;
			Link link = links.get(binding.username);
			// Their real mapping may be one nobody offered (symmetric, or a LAN we did not know) : check it now
			if (link != null && link.offer(from.address(), RANK_REFLEXIVE))
				sendCheck(link, from.address(), now, false);
			return;
		}

		Pending question = pending.remove(hex(binding.transaction));
		if (question == null)
			return; // not ours, or too late
		if (question.probe != null)
		{
			question.probe.mapped = binding.mapped;
			return;
		}
		Link link = links.get(question.key);
		if (link == null)
			return;
		if (from.address().equals(question.address))
			link.answered(question.address, now);
		else if (link.offer(from.address(), RANK_REFLEXIVE))
			sendCheck(link, from.address(), now, false); // answered from another mapping : that one is the route to try
	}

	/** Sends what is due : probe retries, check rounds, the search, consent. Lobby_Client calls it every pump. */
	public void update()
	{
		long now = clock.getAsLong();
		updateProbes(now);
		for (Iterator<Pending> it = pending.values().iterator(); it.hasNext();)
			if (now - it.next().sent > TRANSACTION_MS)
				it.remove();
		if (self.get() == null)
			return; // a check carries this machine's name, and the service has not told it yet
		for (Link link : links.values())
			update(link, now);
	}

	void updateProbes(long now)
	{
		if (probeStarted == Long.MIN_VALUE || now - probeStarted >= PROBE_MS)
			return;
		for (Probe probe : probes.values())
			if (probe.mapped == null && !probe.failed && now - probe.sent >= PROBE_RETRY_MS)
			{
				probe.sent = now;
				probed.add(probe.peer.address());
				byte[] transaction = transaction();
				pending.put(hex(transaction), new Pending(probe, now));
				if (!send(probe.peer, Stun_Codec.encode(Stun_Codec.Binding.request(transaction, null))))
					probe.failed = true;
			}
	}

	void update(Link link, long now)
	{
		switch (link.route)
		{
			case DIRECT:
				if (now - link.lastAnswer >= STALE_MS)
				{
					link.restart(Route.RECONNECTING, now);
					break;
				}
				// Unanswered for a while : ask every round, so a lossy minute is not a network change
				if (now - link.lastRound >= (now - link.lastAnswer > CONSENT_MS + ROUND_MS ? ROUND_MS : CONSENT_MS))
				{
					link.lastRound = now;
					sendCheck(link, link.address, now, false);
				}
				return;
			case RELAYED:
				return;
			default:
				break;
		}

		if (link.firstAnswer != Long.MIN_VALUE && (now - link.firstAnswer >= SETTLE_MS || link.bestAnswered() == link.bestOffered()))
		{
			link.nominate(now);
			return;
		}
		boolean trying = link.route != Route.CANNOT_CONNECT;
		if (trying && link.firstAnswer == Long.MIN_VALUE && now - link.started >= GIVE_UP_MS)
		{
			link.route = Route.CANNOT_CONNECT;
			trying = false;
		}
		if (now - link.lastRound >= (trying ? ROUND_MS : CONSENT_MS))
		{
			link.lastRound = now;
			// Best first : on the wire, IPv6 is tried before the LAN and the LAN before the punch
			for (Candidate candidate : link.byRank())
				sendCheck(link, candidate.address, now, false);
		}
		if (trying && now - link.started >= PUNCH_MS)
			search(link, now);
	}

	/**
	 * Ports around each public address offered, nearest first, paced : a router handing ports out in order
	 * gives one of these to the other end. Spans of 8, 32, 128 and 256 either side, then again.
	 */
	void search(Link link, long now)
	{
		link.budget = Math.min(link.budget + (now - link.lastSearch) * SEARCH_PER_SECOND / 1000.0, SEARCH_PER_SECOND / 10.0);
		link.lastSearch = now;
		List<String> around = new ArrayList<String>();
		for (Candidate candidate : link.candidates)
			if (candidate.rank == RANK_PUBLIC && Stun_Codec.ipOf(candidate.address) != null && Stun_Codec.ipOf(candidate.address).length == 4)
				around.add(candidate.address);
		if (around.isEmpty())
			return;
		while (link.budget >= 1)
		{
			// Widening passes, each from the nearest port out : a probe the internet lost is sent again soon, not after the whole span
			int span = Math.min(SEARCH_SPAN, 8 << (2 * link.pass));
			if (link.searched >= 2 * span * around.size())
			{
				link.searched = 0;
				link.pass = span == SEARCH_SPAN ? 0 : link.pass + 1;
				continue;
			}
			String base = around.get(link.searched % around.size());
			int step = link.searched / around.size();
			int offset = (step / 2 + 1) * (step % 2 == 0 ? 1 : -1);
			link.searched++;
			int port = Stun_Codec.portOf(base) + offset;
			if (port < 1 || port > 0xFFFF)
				continue;
			link.budget--;
			sendCheck(link, base.substring(0, base.lastIndexOf(':') + 1) + port, now, true);
		}
	}

	void sendCheck(Link link, String address, long now, boolean search)
	{
		String name = self.get();
		if (name == null)
			return;
		Net_Peer peer;
		try
		{
			peer = shared.resolve(address);
		}
		catch (RuntimeException e)
		{
			return; // an address the other end offered that this transport cannot even name
		}
		byte[] transaction = transaction();
		pending.put(hex(transaction), new Pending(link.key, address, now));
		if (!played.contains(address))
			probed.add(address);
		send(peer, Stun_Codec.encode(Stun_Codec.Binding.request(transaction, name)));
		if (search)
			searchesSent++;
		else
			checksSent++;
	}

	boolean send(Net_Peer peer, ByteBuffer packet)
	{
		try
		{
			shared.send(peer, packet);
			return true;
		}
		catch (RuntimeException e)
		{
			return false; // an unresolved name on UDP throws rather than failing to arrive
		}
	}

	byte[] transaction()
	{
		byte[] transaction = new byte[12];
		random.nextBytes(transaction);
		return transaction;
	}

	static String hex(byte[] bytes)
	{
		StringBuilder text = new StringBuilder();
		for (byte b : bytes)
			text.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		return text.toString();
	}

	// ---------------------------------------------------------------- ranks

	static final int RANK_IPV6 = 3, RANK_LAN = 2, RANK_PUBLIC = 1, RANK_REFLEXIVE = 0;

	/** IPv6 (global) before a LAN or loopback IPv4, before a public IPv4 ; a mapping nobody offered last. */
	static int rank(String address)
	{
		byte[] ip = Stun_Codec.ipOf(address);
		if (ip == null)
			return RANK_PUBLIC; // a name : the in-memory wire's, or a host that has one
		if (ip.length == 16)
			return (ip[0] & 0xFF) == 0xFE && (ip[1] & 0xC0) == 0x80 ? RANK_LAN : RANK_IPV6;
		int a = ip[0] & 0xFF, b = ip[1] & 0xFF;
		boolean lan = a == 10 || a == 127 || a == 172 && (b & 0xF0) == 16 || a == 192 && b == 168 || a == 100 && (b & 0xC0) == 64;
		return lan ? RANK_LAN : RANK_PUBLIC;
	}

	// ---------------------------------------------------------------- state

	/** One other player, as this machine can reach it. */
	public final class Link
	{
		/** The other end's address as the lobby service sees it. */
		public final String key;
		final List<Candidate> candidates = new ArrayList<Candidate>();
		Route route = Route.CHECKING;
		String address;
		long started, firstAnswer = Long.MIN_VALUE, lastAnswer, lastRound = Long.MIN_VALUE / 2, lastSearch, becameDirect;
		double budget;
		int searched, pass;

		Link(String key, long now)
		{
			this.key = key;
			this.started = now;
			this.lastSearch = now;
		}

		public Route route()
		{
			return route;
		}

		/** Where the game should send to reach the other end : set once DIRECT, kept while RECONNECTING, null before. */
		public String address()
		{
			return address;
		}

		/** Every address known for the other end, offered or learned, best first. */
		public List<String> addresses()
		{
			List<String> addresses = new ArrayList<String>();
			for (Candidate candidate : byRank())
				addresses.add(candidate.address);
			return addresses;
		}

		/** How long the last check took to settle, from the start of checking to DIRECT ; -1 while not DIRECT. */
		public long settledMs()
		{
			return route == Route.DIRECT ? becameDirect - started : -1;
		}

		/** What a person can do about this pair, or null when nothing is wrong. Names a fix, never a NAT type. */
		public String advice()
		{
			switch (route)
			{
				case RELAYED:
					return "Relayed through the game's server : you may see a little more lag.";
				case RECONNECTING:
					return "Lost contact, reconnecting. Switching between Wi-Fi and mobile data does this.";
				case CANNOT_CONNECT:
					if (nat() == Nat.HARD || carrierNat())
						return "Your network cannot be reached directly (often mobile data). Join over Wi-Fi if you can.";
					return "Their network cannot be reached directly (often mobile data). They should join over Wi-Fi if they can.";
				default:
					return null;
			}
		}

		/** Adds an address ; true when it is new. */
		boolean offer(String address, int rank)
		{
			for (Candidate candidate : candidates)
				if (candidate.address.equals(address))
					return false;
			candidates.add(new Candidate(address, rank));
			return true;
		}

		void answered(String address, long now)
		{
			offer(address, RANK_REFLEXIVE); // a search hit : an address nobody offered
			for (Candidate candidate : candidates)
				if (candidate.address.equals(address))
					candidate.answered = true;
			lastAnswer = now;
			if (route != Route.DIRECT && firstAnswer == Long.MIN_VALUE)
				firstAnswer = now;
			if (route != Route.DIRECT && bestAnswered() == bestOffered())
				nominate(now);
		}

		int bestOffered()
		{
			int best = -1;
			for (Candidate candidate : candidates)
				best = Math.max(best, candidate.rank);
			return best;
		}

		int bestAnswered()
		{
			int best = -1;
			for (Candidate candidate : candidates)
				if (candidate.answered)
					best = Math.max(best, candidate.rank);
			return best;
		}

		void nominate(long now)
		{
			for (Candidate candidate : byRank())
				if (candidate.answered)
				{
					address = candidate.address;
					route = Route.DIRECT;
					becameDirect = now;
					lastRound = now;
					return;
				}
		}

		void restart(Route as, long now)
		{
			route = as;
			started = now;
			firstAnswer = Long.MIN_VALUE;
			lastRound = Long.MIN_VALUE / 2;
			lastSearch = now;
			budget = 0;
			searched = 0;
			pass = 0;
			for (Candidate candidate : candidates)
				candidate.answered = false;
		}

		List<Candidate> byRank()
		{
			List<Candidate> sorted = new ArrayList<Candidate>(candidates);
			sorted.sort((a, b) -> b.rank - a.rank); // stable : equal ranks keep the order they were offered in
			return sorted;
		}

		@Override
		public String toString()
		{
			return key + " " + route + (address == null ? "" : " via " + address);
		}
	}

	static final class Candidate
	{
		final String address;
		final int rank;
		boolean answered;

		Candidate(String address, int rank)
		{
			this.address = address;
			this.rank = rank;
		}
	}

	static final class Probe
	{
		final String server;
		Net_Peer peer;
		String mapped;
		boolean failed;
		long sent = Long.MIN_VALUE / 2;

		Probe(String server)
		{
			this.server = server;
		}
	}

	/** A question out : a probe's, or a check of one address of one pair. */
	static final class Pending
	{
		final Probe probe;
		final String key, address;
		final long sent;

		Pending(Probe probe, long sent)
		{
			this.probe = probe;
			this.key = null;
			this.address = null;
			this.sent = sent;
		}

		Pending(String key, String address, long sent)
		{
			this.probe = null;
			this.key = key;
			this.address = address;
			this.sent = sent;
		}
	}
}
