package jks.smoke;

import static jks.smoke.Net_Run.eq;
import static jks.smoke.Net_Run.is;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

import jks.lobby.Lobby_Client;
import jks.lobby.Lobby_Ice;
import jks.lobby.Lobby_Service;
import jks.net.Lobby_Codec;
import jks.net.Lobby_Message;
import jks.net.Net_Codec;
import jks.net.Net_Listener;
import jks.net.Net_Loopback;
import jks.net.Net_Loopback.Nat;
import jks.net.Net_Peer;
import jks.net.Net_Rejected;
import jks.net.Net_Transport;
import jks.net.Stun_Codec;
import jks.net.Transport_Udp;

/**
 * nettest's checks for phase 2.2 (r42) : STUN on the game's socket, the NAT probe, and the connectivity
 * check that says direct / cannot connect / reconnecting while players are still in the lobby.
 *
 * The routers are the in-memory wire's ({@link Net_Loopback#nat}) : full cone, restricted, port-restricted
 * and symmetric, each against each, then again over loss, duplicates and reordering on many seeds - the
 * checks race both ends' punches through every interleaving the seed draws. None of this proves the
 * internet : r42's gate is two households and a phone hotspot, and that needs a person.
 */
class Net_Ice_Checks
{
	static final String HOST_INSIDE = "192.168.1.10:5000", JOINER_INSIDE = "10.0.0.20:5000";
	static final String HOST_IP = "203.0.113.1", JOINER_IP = "198.51.100.77";
	static final String[] REFLECTORS = { "192.0.2.1:3478", "192.0.2.2:3478" };
	/** Longer than Lobby_Ice gives the probe. */
	static final long PROBE_MS = 3_000;

	/** A lobby service, two STUN servers and players behind routers, on a clock that only moves when told. */
	static final class Rig
	{
		final Net_Loopback wire;
		final long[] now = { 0 };
		final Lobby_Service service;
		final List<Net_Transport> reflectors = new ArrayList<Net_Transport>();
		final List<Lobby_Client> clients = new ArrayList<Lobby_Client>();
		final List<List<String>> games = new ArrayList<List<String>>();
		final List<List<String>> lost = new ArrayList<List<String>>();
		/** Anything else on the wire that answers when pumped : a relay (r45). */
		final List<Runnable> servers = new ArrayList<Runnable>();

		Rig(long seed)
		{
			wire = new Net_Loopback(seed);
			service = new Lobby_Service(wire.open("service"), () -> now[0], new Random(seed));
			for (String address : REFLECTORS)
				reflectors.add(wire.open(address));
		}

		/** A player whose socket is this inside address, behind a router of this kind at this ip ; no router when kind is null. */
		Lobby_Client client(String inside, String publicIp, Nat.Kind kind)
		{
			Net_Transport end = kind == null ? wire.open(publicIp + inside.substring(inside.lastIndexOf(':'))) : wire.open(inside, wire.nat(publicIp, kind));
			return add(end);
		}

		Lobby_Client add(Net_Transport end)
		{
			Lobby_Client client = new Lobby_Client(end, "service", () -> now[0]);
			clients.add(client);
			games.add(new ArrayList<String>());
			lost.add(new ArrayList<String>());
			return client;
		}

		/** What the game view of this client read : "from text". */
		List<String> game(Lobby_Client client)
		{
			return games.get(clients.indexOf(client));
		}

		void step(long millis)
		{
			now[0] += millis;
			wire.advance(millis);
			for (int round = 0; round < 2; round++)
			{
				for (int i = 0; i < clients.size(); i++)
					pumpGame(i);
				service.pump();
				for (Net_Transport reflector : reflectors)
					reflect(reflector);
				for (Runnable server : servers)
					server.run();
			}
			for (int i = 0; i < clients.size(); i++)
				pumpGame(i);
		}

		void pumpGame(int i)
		{
			List<String> into = games.get(i), gone = lost.get(i);
			clients.get(i).game().pump(new Net_Listener()
			{
				@Override
				public void received(Net_Peer from, ByteBuffer payload)
				{
					into.add(from.address() + " " + StandardCharsets.UTF_8.decode(payload));
				}

				@Override
				public void lost(Net_Peer peer)
				{
					gone.add(peer.address());
				}
			});
		}

		void run(long millis)
		{
			for (long t = 0; t < millis; t += 20)
				step(20);
		}

		void until(BooleanSupplier condition, long millis, String failure)
		{
			for (long t = 0; t < millis && !condition.getAsBoolean(); t += 20)
				step(20);
			is(condition.getAsBoolean(), failure);
		}
	}

	/** A public STUN server : answers every Binding request with the address it came from. */
	static void reflect(Net_Transport reflector)
	{
		reflector.pump(new Net_Listener()
		{
			@Override
			public void received(Net_Peer from, ByteBuffer payload)
			{
				try
				{
					Stun_Codec.Binding binding = Stun_Codec.decode(payload);
					if (!binding.success)
						reflector.send(from, Stun_Codec.encode(binding.answer(from.address())));
				}
				catch (Net_Rejected e)
				{
					// not a question
				}
			}
		});
	}

	/** A host lobby and a joiner that asked for it, both behind the routers named. */
	static final class Pair
	{
		final Rig rig;
		final Lobby_Client host, joiner;

		Pair(long seed, Nat.Kind hostKind, Nat.Kind joinerKind)
		{
			this(new Rig(seed), hostKind, joinerKind);
		}

		Pair(Rig rig, Nat.Kind hostKind, Nat.Kind joinerKind)
		{
			this.rig = rig;
			host = rig.client(HOST_INSIDE, HOST_IP, hostKind);
			joiner = rig.client(JOINER_INSIDE, JOINER_IP, joinerKind);
			host.host(8);
			rig.until(() -> host.code() != null, 15_000, "the host never got a code"); // HOST is repeated every 3 s : two lost on a bad wire is 6 s
			joiner.join(host.code());
			rig.until(() -> joiner.joined() != null, 15_000, "the joiner never heard where the host is");
		}

		Lobby_Ice.Link hostRow()
		{
			return joiner.publicAddress() == null ? null : host.ice().link(joiner.publicAddress());
		}

		Lobby_Ice.Link joinerRow()
		{
			return joiner.ice().link(joiner.joined().host.get(0));
		}

		Lobby_Ice.Route hostRoute()
		{
			return hostRow() == null ? null : hostRow().route();
		}

		boolean settled()
		{
			Lobby_Ice.Link hostRow = hostRow(), joinerRow = joinerRow();
			return hostRow != null && joinerRow != null && hostRow.route() != Lobby_Ice.Route.CHECKING && joinerRow.route() != Lobby_Ice.Route.CHECKING
					&& (hostRow.route() == Lobby_Ice.Route.DIRECT) == (joinerRow.route() == Lobby_Ice.Route.DIRECT);
		}

		/** A game packet each way over the route the joiner nominated : what DIRECT has to mean. */
		void gamePacketsCross(String what)
		{
			String address = joinerRow().address();
			rig.game(host).clear();
			rig.game(joiner).clear();
			joiner.game().send(joiner.game().resolve(address), bytes("hello"));
			rig.step(20);
			is(rig.game(host).size() == 1 && rig.game(host).get(0).endsWith(" hello"), what + " : the host never read the joiner's game packet, sent to " + address + " : " + rig.game(host));
			String from = rig.game(host).get(0).substring(0, rig.game(host).get(0).indexOf(' '));
			host.game().send(host.game().resolve(from), bytes("welcome"));
			rig.step(20);
			eq(List.of(address + " welcome"), rig.game(joiner), what + " : the answer arrives from where the joiner sent");
		}
	}

	static ByteBuffer bytes(String text)
	{
		return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
	}

	// ---------------------------------------------------------------- the codec

	/** RFC 5769's sample answers, IPv4 and IPv6 : the XOR, the padding and the FINGERPRINT as another implementation wrote them. */
	static void codecReadsTheRfcVectors() throws Exception
	{
		byte[] v4 = hex("0101003c2112a442b7e7a701bc34d686fa87dfae"
				+ "8022000b7465737420766563746f7220"
				+ "002000080001a147e112a643"
				+ "000800142b91f599fd9e90c38c7489f92af9ba53f06be7d7"
				+ "80280004c07d4c96");
		Stun_Codec.Binding answer = Stun_Codec.decode(ByteBuffer.wrap(v4));
		is(answer.success, "the RFC 5769 IPv4 sample is a success");
		eq("192.0.2.1:32853", answer.mapped, "the RFC 5769 IPv4 mapped address");

		byte[] v6 = hex("010100482112a442b7e7a701bc34d686fa87dfae"
				+ "8022000b7465737420766563746f7220"
				+ "002000140002a1470113a9faa5d3f179bc25f4b5bed2b9d9"
				+ "00080014a382954e4be67bf11784c97c8292c275bfe3ed41"
				+ "80280004c8fb0b4c");
		eq("[2001:db8:1234:5678:11:2233:4455:6677]:32853", Stun_Codec.decode(ByteBuffer.wrap(v6)).mapped, "the RFC 5769 IPv6 mapped address");

		byte[] transaction = new byte[12];
		new Random(3).nextBytes(transaction);
		Stun_Codec.Binding request = Stun_Codec.decode(Stun_Codec.encode(Stun_Codec.Binding.request(transaction, "203.0.113.1:40000")));
		is(!request.success, "a request reads back as one");
		eq("203.0.113.1:40000", request.username, "the USERNAME, padded to 4");
		is(java.util.Arrays.equals(transaction, request.transaction), "the transaction");
		for (String mapped : new String[] { "198.51.100.7:1", "[2001:db8:0:0:0:0:0:7]:65535", "[0:0:0:0:0:0:0:1]:9" })
			eq(mapped, Stun_Codec.decode(Stun_Codec.encode(request.answer(mapped))).mapped, "a success mapping " + mapped);
		// The worst case : the shortest name an IPv4 player has, answered with an IPv6 address
		int smallest = Stun_Codec.encode(Stun_Codec.Binding.request(transaction, "1.1.1.1:1")).remaining();
		int biggest = Stun_Codec.encode(request.answer("[2001:db8:1:2:3:4:5:6]:65535")).remaining();
		is(biggest <= 1.2 * smallest, "an answer to a player's check is at most 1.2 times the check : nobody gains by using a player as an amplifier ("
				+ biggest + " B for " + smallest + " B)");
	}

	/** Every truncation and every bit flip of a request refused ; STUN never mixes with game or lobby packets. */
	static void codecRefusesAndNeverMixes() throws Exception
	{
		byte[] transaction = new byte[12];
		new Random(5).nextBytes(transaction);
		byte[] request = array(Stun_Codec.encode(Stun_Codec.Binding.request(transaction, "[2001:db8:0:0:0:0:0:7]:40000")));
		for (int length = 0; length < request.length; length++)
			refused(java.util.Arrays.copyOf(request, length), "a request cut to " + length + " B");
		for (int bit = 0; bit < request.length * 8; bit++)
		{
			byte[] flipped = request.clone();
			flipped[bit / 8] ^= 1 << (bit % 8);
			refused(flipped, "a request with bit " + bit + " flipped");
		}

		// A success may come from a public server without a FINGERPRINT : a flip is refused, or changes nothing that is read
		Stun_Codec.Binding question = Stun_Codec.Binding.request(transaction, null);
		byte[] success = array(Stun_Codec.encode(question.answer("198.51.100.7:4242")));
		for (int bit = 0; bit < success.length * 8; bit++)
		{
			byte[] flipped = success.clone();
			flipped[bit / 8] ^= 1 << (bit % 8);
			try
			{
				Stun_Codec.Binding read = Stun_Codec.decode(ByteBuffer.wrap(flipped));
				is(read.success && "198.51.100.7:4242".equals(read.mapped) && java.util.Arrays.equals(transaction, read.transaction),
						"a success with bit " + bit + " flipped was read as something else : " + read.mapped);
			}
			catch (Net_Rejected expected)
			{
				// refused whole
			}
		}

		is(!Stun_Codec.isStunPacket(Net_Codec.encode(new jks.net.Net_Message.Hello(1))), "a game HELLO is not STUN");
		is(!Stun_Codec.isStunPacket(Lobby_Codec.encode(new Lobby_Message.Ping())), "a lobby PING is not STUN");
		try
		{
			Net_Codec.decode(ByteBuffer.wrap(request));
			is(false, "the game codec read a STUN request");
		}
		catch (Net_Rejected e)
		{
			// NOT a version refusal : a session must not answer a check with LEAVE VERSION
			eq(Net_Rejected.Reason.NOT_OURS, e.reason, "a STUN request at a game session");
		}
		try
		{
			Lobby_Codec.decode(ByteBuffer.wrap(request));
			is(false, "the lobby codec read a STUN request");
		}
		catch (Net_Rejected e)
		{
			eq(Net_Rejected.Reason.NOT_OURS, e.reason, "a STUN request at the lobby service");
		}
	}

	/** The codec's address text is the UDP transport's, over a real socket, IPv4 and IPv6 ; and a literal parses without a lookup. */
	static void addressTextIsTheTransports() throws Exception
	{
		try (Net_Transport listener = Transport_Udp.open(); Net_Transport sender = Transport_Udp.open())
		{
			for (String loopback : new String[] { "127.0.0.1", "::1" })
			{
				String[] seen = { null };
				String target = loopback.contains(":") ? "[" + loopback + "]:" + listener.localPort() : loopback + ":" + listener.localPort();
				sender.send(sender.resolve(target), bytes("x"));
				long deadline = System.currentTimeMillis() + Net_Run.DEADLINE_MS;
				while (seen[0] == null && System.currentTimeMillis() < deadline)
				{
					listener.pump(new Net_Listener()
					{
						@Override
						public void received(Net_Peer from, ByteBuffer payload)
						{
							seen[0] = from.address();
						}
					});
					Thread.sleep(2);
				}
				eq(Stun_Codec.textOf(InetAddress.getByName(loopback).getAddress(), sender.localPort()), seen[0], "the codec's text for " + loopback);
			}

			for (String address : sender.localAddresses())
			{
				is(Stun_Codec.ipOf(address) != null, "a local address the codec cannot read : " + address);
				eq(sender.localPort(), Stun_Codec.portOf(address), "a local address carries the socket's port");
				is(address.indexOf('%') < 0, "a local address with an interface scope nobody else can use : " + address);
				is(!address.startsWith("127.") && !address.startsWith("[0:0:0:0:0:0:0:1]") && !address.startsWith("[fe80"), "the loopback or a link-local address offered : " + address);
			}
			is(sender.localAddresses().size() <= Transport_Udp.MAX_LOCAL_ADDRESSES, "more local addresses than a lobby packet carries");
		}
		eq("[2001:db8:0:0:0:0:0:7]:1", Stun_Codec.textOf(Stun_Codec.ipOf("[2001:db8::7]:1"), 1), "a compressed IPv6 literal");
		eq("192.0.2.1:9", Stun_Codec.textOf(Stun_Codec.ipOf("192.0.2.1:9"), 9), "an IPv4 literal");
		byte[] mapped = new byte[16];
		mapped[10] = mapped[11] = (byte) 0xFF;
		mapped[12] = (byte) 192;
		mapped[14] = 2;
		mapped[15] = 1;
		eq("192.0.2.1:9", Stun_Codec.textOf(mapped, 9), "an IPv4-mapped IPv6 address, as the transport writes it");
		is(Stun_Codec.ipOf("stun.l.google.com:19302") == null && Stun_Codec.ipOf("300.1.1.1:5") == null && Stun_Codec.ipOf("[1::2::3]:5") == null,
				"a name, an octet past 255 and two :: are not literals");
	}

	// ---------------------------------------------------------------- the probe

	/** Two STUN servers and the service from the same socket : one port everywhere is EASY, several are HARD, none is UNKNOWN with advice. */
	static void probeTellsTheMapping() throws Exception
	{
		eq(Lobby_Ice.Nat.OPEN, probe(null), "no router");
		eq(Lobby_Ice.Nat.EASY, probe(Nat.Kind.FULL_CONE), "a full cone router");
		eq(Lobby_Ice.Nat.EASY, probe(Nat.Kind.PORT_RESTRICTED), "a port-restricted router");
		eq(Lobby_Ice.Nat.HARD, probe(Nat.Kind.SYMMETRIC), "a symmetric router");

		Rig rig = new Rig(1);
		Lobby_Client hard = rig.client(JOINER_INSIDE, JOINER_IP, Nat.Kind.SYMMETRIC);
		hard.ice().probe(List.of(REFLECTORS));
		rig.until(() -> !hard.ice().probing() && hard.publicAddress() != null, 5_000, "the probe never ended");
		is(hard.ice().advice() != null && hard.ice().advice().contains("Wi-Fi"), "HARD advice names the fix : " + hard.ice().advice());
		is(!hard.ice().advice().toLowerCase().contains("symmetric") && !hard.ice().advice().toLowerCase().contains("nat type"), "advice does not name a NAT type");

		Rig silent = new Rig(1);
		for (Net_Transport reflector : silent.reflectors)
			reflector.close();
		Lobby_Client alone = silent.client(JOINER_INSIDE, JOINER_IP, Nat.Kind.FULL_CONE);
		alone.ice().probe(List.of(REFLECTORS));
		silent.run(PROBE_MS + 500);
		eq(Lobby_Ice.Nat.UNKNOWN, alone.ice().nat(), "nobody answered");
		is(alone.ice().advice() != null && alone.ice().advice().contains("network"), "no answer at all is worth telling : " + alone.ice().advice());
		eq(List.of(), silent.lost.get(0), "the silent servers' timeouts were the probe's, not the game's");

		Rig carrier = new Rig(1);
		Lobby_Client shared = carrier.client("100.64.3.4:5000", JOINER_IP, Nat.Kind.FULL_CONE);
		shared.ice().probe(List.of(REFLECTORS));
		carrier.until(() -> !shared.ice().probing(), 5_000, "the probe never ended");
		is(shared.ice().carrierNat() && shared.ice().advice() != null && shared.ice().advice().contains("Wi-Fi"), "a 100.64/10 address is carrier NAT : " + shared.ice().advice());

		// A lobby service on this machine or its LAN (r43's screen against a local service) sees a LAN address :
		// it is not the router's mapping, and beside a STUN server's public one it read as HARD
		Net_Transport end = new Net_Loopback(1).open("10.0.0.9:4000");
		Lobby_Ice local = new Lobby_Ice(end, () -> 0L, new Random(1), () -> "127.0.0.1:4000");
		eq(List.of(), local.mapped(), "a service seeing a loopback address is not a NAT sample");
		Lobby_Ice lan = new Lobby_Ice(end, () -> 0L, new Random(1), () -> "192.168.1.9:4000");
		eq(List.of(), lan.mapped(), "a service seeing a LAN address is not a NAT sample");
		Lobby_Ice far = new Lobby_Ice(end, () -> 0L, new Random(1), () -> "203.0.113.9:4000");
		eq(List.of("203.0.113.9:4000"), far.mapped(), "a service seeing a public address is one");

		Rig unresolvable = new Rig(1);
		Lobby_Client lookup = unresolvable.client(HOST_INSIDE, HOST_IP, Nat.Kind.FULL_CONE);
		lookup.ice().probe(List.of("no port here"));
		unresolvable.run(PROBE_MS + 500);
		eq(Lobby_Ice.Nat.UNKNOWN, lookup.ice().nat(), "a server that is not an address is one sample fewer, not a crash");
	}

	static Lobby_Ice.Nat probe(Nat.Kind kind)
	{
		Rig rig = new Rig(1);
		Lobby_Client client = rig.client(HOST_INSIDE, HOST_IP, kind);
		client.host(4); // the service's view is a third sample
		client.ice().probe(List.of(REFLECTORS));
		rig.until(() -> !client.ice().probing() && client.publicAddress() != null, 5_000, "the probe behind " + kind + " never ended");
		is(client.ice().mapped().size() == 3, "three samples behind " + kind + " : " + client.ice().mapped());
		return client.ice().nat();
	}

	// ---------------------------------------------------------------- the checks

	static final Nat.Kind[] KINDS = { null, Nat.Kind.FULL_CONE, Nat.Kind.RESTRICTED, Nat.Kind.PORT_RESTRICTED, Nat.Kind.SYMMETRIC };

	/**
	 * Every router against every router, from the moment the joiner asked : DIRECT on both rows and a game packet
	 * across it, except both symmetric, which is CANNOT_CONNECT on both rows with advice - what the relay is for.
	 */
	static void everyRouterAgainstEvery() throws Exception
	{
		StringBuilder table = new StringBuilder();
		for (Nat.Kind hostKind : KINDS)
			for (Nat.Kind joinerKind : KINDS)
			{
				String what = "host " + name(hostKind) + ", joiner " + name(joinerKind);
				Pair pair = new Pair(1, hostKind, joinerKind);
				pair.rig.until(pair::settled, Lobby_Ice.GIVE_UP_MS + 1_000, what + " : the rows never settled : " + pair.hostRow() + " / " + pair.joinerRow());
				boolean hopeless = hostKind == Nat.Kind.SYMMETRIC && joinerKind == Nat.Kind.SYMMETRIC;
				if (hopeless)
				{
					eq(Lobby_Ice.Route.CANNOT_CONNECT, pair.joinerRow().route(), what);
					eq(Lobby_Ice.Route.CANNOT_CONNECT, pair.hostRoute(), what);
					is(pair.joinerRow().advice() != null && pair.joinerRow().advice().contains("Wi-Fi"), what + " : advice that names a fix : " + pair.joinerRow().advice());
				}
				else
				{
					eq(Lobby_Ice.Route.DIRECT, pair.joinerRow().route(), what);
					pair.rig.until(() -> pair.hostRoute() == Lobby_Ice.Route.DIRECT, 1_000, what + " : the host's row stayed " + pair.hostRoute());
					pair.gamePacketsCross(what);
					boolean needsSearch = hostKind == Nat.Kind.SYMMETRIC && joinerKind == Nat.Kind.PORT_RESTRICTED || joinerKind == Nat.Kind.SYMMETRIC && hostKind == Nat.Kind.PORT_RESTRICTED;
					long limit = needsSearch ? 2 * Lobby_Ice.PUNCH_MS : Lobby_Ice.PUNCH_MS;
					is(pair.joinerRow().settledMs() <= limit, what + " : took " + pair.joinerRow().settledMs() + " ms, more than " + limit);
				}
				table.append(String.format("%n      %-16s x %-16s %-15s %5d ms", name(hostKind), name(joinerKind), pair.joinerRow().route(), pair.joinerRow().settledMs()));
			}
		System.out.println("NET      host router x joiner router, the joiner's row :" + table);
	}

	/** A symmetric router that picks ports at random cannot be found by the search : the row says so rather than hoping. */
	static void randomPortsAreHonest() throws Exception
	{
		Rig rig = new Rig(1);
		Lobby_Client host = rig.client(HOST_INSIDE, HOST_IP, Nat.Kind.PORT_RESTRICTED);
		Net_Loopback.Nat random = rig.wire.nat(JOINER_IP, Nat.Kind.SYMMETRIC);
		random.randomPorts = true;
		Lobby_Client joiner = rig.add(rig.wire.open(JOINER_INSIDE, random));
		// A lobby screen probes as it opens : that is how each end knows whose network to blame
		host.ice().probe(List.of(REFLECTORS));
		joiner.ice().probe(List.of(REFLECTORS));
		host.host(8);
		rig.until(() -> host.code() != null, 5_000, "no code");
		joiner.join(host.code());
		rig.until(() -> joiner.joined() != null, 5_000, "no JOINED");
		Lobby_Ice.Link row = joiner.ice().link(joiner.joined().host.get(0));
		rig.run(Lobby_Ice.GIVE_UP_MS + 500);
		eq(Lobby_Ice.Nat.HARD, joiner.ice().nat(), "the joiner's probe");
		eq(Lobby_Ice.Nat.EASY, host.ice().nat(), "the host's probe");
		eq(Lobby_Ice.Route.CANNOT_CONNECT, row.route(), "a random-port symmetric joiner against a port-restricted host");
		is(row.advice().startsWith("Your network"), "the joiner is told it is its own network : " + row.advice());
		eq(Lobby_Ice.Route.CANNOT_CONNECT, host.ice().link(joiner.publicAddress()).route(), "the host's row");
		is(host.ice().link(joiner.publicAddress()).advice().startsWith("Their network"), "the host is told it is the joiner's : " + host.ice().link(joiner.publicAddress()).advice());
		is(host.ice().searchesSent <= Lobby_Ice.SEARCH_PER_SECOND * (Lobby_Ice.GIVE_UP_MS - Lobby_Ice.PUNCH_MS) / 1000 + Lobby_Ice.SEARCH_PER_SECOND / 10,
				"the search keeps its pace : " + host.ice().searchesSent + " probes");
		eq(List.of(), rig.lost.get(0), "hundreds of searched addresses timed out, and the host's game heard none of it");
		eq(0, host.heldDropped, "and no game packet was pushed out for them");
	}

	/** Both players with IPv6 behind firewalls, and IPv4 behind easy routers : IPv6 is the route, even when IPv4 answers first. One side without it : IPv4. */
	static void ipv6First() throws Exception
	{
		for (boolean both : new boolean[] { true, false })
		{
			Rig rig = new Rig(1);
			// IPv6 answers come back 160 ms after IPv4's : the route must still be IPv6, which is what waiting SETTLE_MS is for
			rig.wire.ipv6LatencyMs = 80;
			// The client reads its transport's addresses when it is made : the IPv6 address comes first
			Net_Transport hostEnd = rig.wire.open(HOST_INSIDE, rig.wire.nat(HOST_IP, Nat.Kind.FULL_CONE));
			rig.wire.ipv6(hostEnd, "[2001:db8:0:0:0:0:0:10]:5000", true);
			Lobby_Client host = rig.add(hostEnd);
			Net_Transport joinerEnd = rig.wire.open(JOINER_INSIDE, rig.wire.nat(JOINER_IP, Nat.Kind.PORT_RESTRICTED));
			if (both)
				rig.wire.ipv6(joinerEnd, "[2001:db8:0:0:0:0:0:20]:5000", true);
			Lobby_Client joiner = rig.add(joinerEnd);
			host.host(8);
			rig.until(() -> host.code() != null, 5_000, "no code");
			joiner.join(host.code());
			rig.until(() -> joiner.joined() != null, 5_000, "no JOINED");
			Lobby_Ice.Link row = joiner.ice().link(joiner.joined().host.get(0));
			rig.until(() -> row.route() == Lobby_Ice.Route.DIRECT, Lobby_Ice.GIVE_UP_MS, "never DIRECT, both=" + both + " : " + row);
			if (both)
				eq("[2001:db8:0:0:0:0:0:10]:5000", row.address(), "with IPv6 on both ends the route is IPv6, through both firewalls");
			else
				is(!row.address().startsWith("["), "with IPv6 on one end only the route is IPv4 : " + row.address());
		}
	}

	/** Two players on the same LAN behind one router : the LAN address beats the public one. */
	static void sameLanGoesStraight() throws Exception
	{
		Rig rig = new Rig(1);
		Nat home = rig.wire.nat(HOST_IP, Nat.Kind.PORT_RESTRICTED);
		Lobby_Client host = rig.add(rig.wire.open(HOST_INSIDE, home));
		Lobby_Client joiner = rig.add(rig.wire.open("192.168.1.11:5000", home));
		host.host(8);
		rig.until(() -> host.code() != null, 5_000, "no code");
		joiner.join(host.code());
		rig.until(() -> joiner.joined() != null, 5_000, "no JOINED");
		Lobby_Ice.Link row = joiner.ice().link(joiner.joined().host.get(0));
		rig.until(() -> row.route() == Lobby_Ice.Route.DIRECT, Lobby_Ice.GIVE_UP_MS, "never DIRECT on one LAN : " + row);
		eq(HOST_INSIDE, row.address(), "the LAN address");
	}

	/**
	 * THE RACE : both ends punch at once over loss, duplicates and reordering, and the service mirrors every JOIN
	 * copy again. Over many seeds, every pair that can connect does on both rows, no pair is ever keyed twice or
	 * restarted by a copy, and a settled route holds.
	 */
	static void punchesRaceOverABadWire() throws Exception
	{
		int pairs = 0;
		for (long seed = 1; seed <= 12; seed++)
			for (Nat.Kind hostKind : KINDS)
				for (Nat.Kind joinerKind : KINDS)
				{
					if (hostKind == Nat.Kind.SYMMETRIC && joinerKind == Nat.Kind.SYMMETRIC)
						continue;
					String what = "seed " + seed + ", host " + name(hostKind) + ", joiner " + name(joinerKind);
					Rig bad = new Rig(seed);
					bad.wire.loss = 0.2f;
					bad.wire.duplicate = 0.1f;
					bad.wire.reorder = 0.2f;
					bad.wire.latencyMs = 20;
					bad.wire.jitterMs = 40;
					Pair pair = new Pair(bad, hostKind, joinerKind);
					pair.rig.until(() -> pair.joinerRow().route() == Lobby_Ice.Route.DIRECT && pair.hostRoute() == Lobby_Ice.Route.DIRECT, Lobby_Ice.GIVE_UP_MS,
							what + " : " + pair.hostRow() + " / " + pair.joinerRow());
					String address = pair.joinerRow().address();
					long settled = pair.joinerRow().settledMs();
					pair.rig.run(10_000);
					eq(Lobby_Ice.Route.DIRECT, pair.joinerRow().route(), what + " : the joiner's row did not hold");
					eq(Lobby_Ice.Route.DIRECT, pair.hostRoute(), what + " : the host's row did not hold");
					eq(address, pair.joinerRow().address(), what + " : the nominated address moved");
					eq(settled, pair.joinerRow().settledMs(), what + " : the route dropped, or a JOIN copy restarted the check");
					eq(1, pair.host.ice().links().size(), what + " : the host keyed the joiner more than once");
					eq(1, pair.joiner.ice().links().size(), what + " : the joiner keyed the host more than once");
					pairs++;
				}
		System.out.println("NET      " + pairs + " pairs connected and held over 20% loss, 10% duplicates, 20% reorder, 20-60 ms");
	}

	/** A player whose router forgets them (a network change) is RECONNECTING, then DIRECT again ; one who is gone ends CANNOT_CONNECT. */
	static void reconnectingIsSaid() throws Exception
	{
		Rig rig = new Rig(1);
		Lobby_Client host = rig.client(HOST_INSIDE, HOST_IP, Nat.Kind.PORT_RESTRICTED);
		Nat moved = rig.wire.nat(JOINER_IP, Nat.Kind.FULL_CONE);
		Net_Transport joinerEnd = rig.wire.open(JOINER_INSIDE, moved);
		Lobby_Client joiner = rig.add(joinerEnd);
		host.host(8);
		rig.until(() -> host.code() != null, 5_000, "no code");
		joiner.join(host.code());
		rig.until(() -> joiner.joined() != null, 5_000, "no JOINED");
		String key = joiner.publicAddress();
		joiner.stopJoining(); // the joiner is in : no more mirrors, so what follows is the checks' own doing
		rig.until(() -> host.ice().link(key) != null && host.ice().link(key).route() == Lobby_Ice.Route.DIRECT, Lobby_Ice.GIVE_UP_MS, "never DIRECT");
		Lobby_Ice.Link row = host.ice().link(key);

		moved.reset();
		rig.until(() -> row.route() == Lobby_Ice.Route.RECONNECTING, Lobby_Ice.STALE_MS + 1_000, "the host's row never said RECONNECTING : " + row);
		is(row.advice() != null && row.advice().contains("reconnecting"), "RECONNECTING says so : " + row.advice());
		rig.until(() -> row.route() == Lobby_Ice.Route.DIRECT, Lobby_Ice.GIVE_UP_MS, "the host's row never came back : " + row);

		// The game talks to the joiner, then the joiner is gone : that silence is the game's to hear, the checks' is not
		host.game().send(host.game().resolve(key), bytes("snapshot"));
		joinerEnd.close();
		rig.until(() -> row.route() == Lobby_Ice.Route.RECONNECTING, Lobby_Ice.STALE_MS + 1_000, "a player who is gone was not RECONNECTING first : " + row);
		rig.until(() -> row.route() == Lobby_Ice.Route.CANNOT_CONNECT, Lobby_Ice.GIVE_UP_MS + 500, "a player who is gone never became CANNOT_CONNECT : " + row);
		rig.run(11_000);
		eq(List.of(key), rig.lost.get(0), "the host's game heard the address it played with go quiet, and only that one");
	}

	/** A request with no name gets no answer : a player's socket is not a free STUN server for strangers. */
	static void strangersGetNoAnswer() throws Exception
	{
		Rig rig = new Rig(1);
		Lobby_Client client = rig.client(HOST_INSIDE, HOST_IP, null);
		Net_Transport stranger = rig.wire.open("192.0.2.99:1");
		List<String> heard = new ArrayList<String>();
		byte[] transaction = new byte[12];
		stranger.send(stranger.resolve(HOST_IP + ":5000"), Stun_Codec.encode(Stun_Codec.Binding.request(transaction, null)));
		stranger.send(stranger.resolve(HOST_IP + ":5000"), Stun_Codec.encode(Stun_Codec.Binding.request(transaction, null).answer("192.0.2.99:1")));
		rig.step(50);
		stranger.pump(new Net_Listener()
		{
			@Override
			public void received(Net_Peer from, ByteBuffer payload)
			{
				heard.add(from.address());
			}
		});
		eq(List.of(), heard, "what a stranger's nameless request got back");
		eq(0, client.ice().answered, "answers given");
		eq(List.of(), rig.game(client), "STUN reached the game");
	}

	// ---------------------------------------------------------------- helpers

	static String name(Nat.Kind kind)
	{
		return kind == null ? "none" : kind.name().toLowerCase();
	}

	static byte[] array(ByteBuffer buffer)
	{
		byte[] bytes = new byte[buffer.remaining()];
		buffer.get(bytes);
		return bytes;
	}

	static void refused(byte[] bytes, String what)
	{
		try
		{
			Stun_Codec.decode(ByteBuffer.wrap(bytes));
			is(false, what + " was read");
		}
		catch (Net_Rejected expected)
		{
			// refused whole
		}
	}

	static byte[] hex(String text)
	{
		byte[] bytes = new byte[text.length() / 2];
		for (int i = 0; i < bytes.length; i++)
			bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
		return bytes;
	}
}
