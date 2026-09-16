package jks.smoke;

import static jks.smoke.Net_Run.eq;
import static jks.smoke.Net_Run.is;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

import jks.lobby.Lobby_Client;
import jks.lobby.Lobby_Service;
import jks.net.Lobby_Codec;
import jks.net.Lobby_Message;
import jks.net.Net_Codec;
import jks.net.Net_Loopback;
import jks.net.Net_Message;
import jks.net.Net_Peer;
import jks.net.Net_Rejected;
import jks.net.Net_Transport;
import jks.net.Transport_Udp;
import jks.online.ClientSession;
import jks.online.HostSession;

/**
 * nettest's checks for phase 2.1 (r41) : the lobby protocol, the service and the client that shares the
 * game's socket. Over the in-memory wire with a virtual clock, so two minutes of a lobby sitting idle
 * take milliseconds ; and once over real UDP, where the shared socket is the thing being proven.
 * `./gradlew netlobby` plays the same across a service JVM, a host JVM and client JVMs.
 */
class Net_Lobby_Checks
{
	/** A service and its players on one wire, on a clock that only moves when told. */
	static final class Rig
	{
		final Net_Loopback wire;
		final long[] now = { 0 };
		final Net_Transport serviceEnd;
		final Lobby_Service service;
		final List<Lobby_Client> clients = new ArrayList<Lobby_Client>();

		Rig(long seed)
		{
			wire = new Net_Loopback(seed);
			serviceEnd = wire.open("service");
			service = new Lobby_Service(serviceEnd, () -> now[0], new Random(seed));
		}

		Lobby_Client client(String name)
		{
			Lobby_Client client = new Lobby_Client(wire.open(name), "service", () -> now[0]);
			clients.add(client);
			return client;
		}

		/** Moves the clock and lets everyone read and answer, twice, so a question and its answer both land. */
		void step(long millis)
		{
			now[0] += millis;
			wire.advance(millis);
			for (int round = 0; round < 2; round++)
			{
				for (Lobby_Client client : clients)
					client.pump();
				service.pump();
			}
			for (Lobby_Client client : clients)
				client.pump();
		}

		void run(long millis, long every)
		{
			for (long t = 0; t < millis; t += every)
				step(every);
		}

		void until(BooleanSupplier condition, long millis, String failure)
		{
			for (long t = 0; t < millis && !condition.getAsBoolean(); t += 50)
				step(50);
			is(condition.getAsBoolean(), failure);
		}
	}

	// ---------------------------------------------------------------- the codec

	static void codecRoundTrip() throws Exception
	{
		Lobby_Message.Host host = new Lobby_Message.Host();
		host.code = "ABC234";
		host.players = 3;
		host.seats = 8;
		host.candidates.add("192.168.1.20:40000");
		host.candidates.add("[2001:db8::7]:40000");
		Lobby_Message.Host back = (Lobby_Message.Host) roundTrip(host);
		eq("ABC234", back.code, "HOST code");
		eq(3, back.players, "HOST players");
		eq(8, back.seats, "HOST seats");
		eq(host.candidates, back.candidates, "HOST candidates");

		Lobby_Message.Host fresh = new Lobby_Message.Host();
		fresh.seats = 8;
		is(((Lobby_Message.Host) roundTrip(fresh)).code == null, "a HOST with no code yet reads back with none");

		Lobby_Message.Listing listing = new Lobby_Message.Listing();
		listing.total = 250;
		for (int i = 0; i < Lobby_Codec.MAX_ROWS; i++)
			listing.rows.add(new Lobby_Message.Row("ZZZZ" + Lobby_Codec.CODE_ALPHABET.charAt(i % 32) + Lobby_Codec.CODE_ALPHABET.charAt(i / 32), i % 9, 8 + i % 2));
		Lobby_Message.Listing listingBack = (Lobby_Message.Listing) roundTrip(listing);
		eq(listing.rows, listingBack.rows, "LISTING rows");
		eq(250, listingBack.total, "LISTING total");
		is(Lobby_Codec.sizeOf(listing) <= Net_Transport.MAX_PAYLOAD, "a full LISTING fits a packet : " + Lobby_Codec.sizeOf(listing) + " B");

		Lobby_Message.Joined joined = new Lobby_Message.Joined();
		joined.code = "ABC234";
		joined.you = "203.0.113.9:51000";
		for (int i = 0; i < Lobby_Codec.MAX_CANDIDATES; i++)
			joined.host.add(("[2001:db8:aaaa:bbbb:cccc:dddd:eeee:" + i + "]:65535"));
		Lobby_Message.Joined joinedBack = (Lobby_Message.Joined) roundTrip(joined);
		eq(joined.host, joinedBack.host, "JOINED host candidates");
		eq(joined.you, joinedBack.you, "JOINED you");

		Lobby_Message.Refused refused = (Lobby_Message.Refused) roundTrip(new Lobby_Message.Refused("ABC234", Lobby_Message.Refused.Reason.VERSION, 7));
		eq(Lobby_Message.Refused.Reason.VERSION, refused.reason, "REFUSED reason");
		eq(7, refused.game, "REFUSED names the lobby's version");
		eq("198.51.100.1:7", ((Lobby_Message.Pong) roundTrip(new Lobby_Message.Pong("198.51.100.1:7"))).you, "PONG you");

		eq("ABC234", Lobby_Codec.normalise(" abc-234 "), "a code as a person types it");
		is(Lobby_Codec.normalise("ABC23O") == null && Lobby_Codec.normalise("ABC23") == null, "O is not in the alphabet, five letters are not a code");
	}

	/** Every truncation and bit flip refused ; a game packet is not a lobby packet and back ; a lie is refused. */
	static void codecRefuses() throws Exception
	{
		Lobby_Message.Join join = new Lobby_Message.Join();
		join.code = "ABC234";
		join.candidates.add("10.0.0.2:5000");
		ByteBuffer packet = Lobby_Codec.encode(join);
		byte[] bytes = new byte[packet.remaining()];
		packet.get(bytes);

		for (int length = 0; length < bytes.length; length++)
			refused(ByteBuffer.wrap(bytes, 0, length), "a JOIN cut to " + length + " B");
		for (int bit = 0; bit < bytes.length * 8; bit++)
		{
			byte[] flipped = bytes.clone();
			flipped[bit / 8] ^= 1 << (bit % 8);
			refused(ByteBuffer.wrap(flipped), "a JOIN with bit " + bit + " flipped");
		}

		eq(Net_Rejected.Reason.NOT_OURS, reason(Net_Codec.encode(new Net_Message.Hello(1))), "a game HELLO at the lobby");
		try
		{
			Net_Codec.decode(Lobby_Codec.encode(new Lobby_Message.Ping()));
			is(false, "the game codec read a lobby PING");
		}
		catch (Net_Rejected e)
		{
			// NOT a version refusal : a session must not answer the lobby with LEAVE VERSION
			eq(Net_Rejected.Reason.NOT_OURS, e.reason, "a lobby PING at a game session");
		}

		// Checksummed lies : right CRC, content no encoder writes
		eq(Net_Rejected.Reason.BAD_VALUE, reason(withCrc(bytes, 4, (byte) 'O')), "a code with an O in it");
		Lobby_Message.Host host = new Lobby_Message.Host();
		host.seats = 4;
		host.players = 2;
		ByteBuffer hostPacket = Lobby_Codec.encode(host);
		byte[] hostBytes = new byte[hostPacket.remaining()];
		hostPacket.get(hostBytes);
		eq(Net_Rejected.Reason.BAD_VALUE, reason(withCrc(hostBytes, 3 + 1 + 6, (byte) 5)), "5 players of 4 seats");

		try
		{
			Lobby_Message.Pong pong = new Lobby_Message.Pong("x".repeat(Lobby_Codec.MAX_ADDRESS + 1));
			Lobby_Codec.encode(pong);
			is(false, "an address past 64 characters was encoded");
		}
		catch (IllegalArgumentException expected)
		{
			// refused out loud
		}
	}

	/** A service speaks one lobby version ; an older or newer game is told which, in a layout that never changes. */
	static void outdatedIsAnswered() throws Exception
	{
		byte[] future = { (byte) Lobby_Codec.MAGIC, (byte) (Lobby_Codec.VERSION + 1), 10, 0, 0, 0, 0 };
		ByteBuffer frame = ByteBuffer.wrap(future);
		int crc = crc(future, future.length - 4);
		frame.putInt(future.length - 4, crc);
		eq(Net_Rejected.Reason.VERSION, reason(ByteBuffer.wrap(future)), "a PING of a lobby version this game does not speak");

		Lobby_Message.Outdated outdated = new Lobby_Message.Outdated();
		outdated.version = 9;
		eq(9, ((Lobby_Message.Outdated) Lobby_Codec.decode(Lobby_Codec.encode(outdated))).version, "an OUTDATED of ANY version is read");

		Rig rig = new Rig(1);
		Net_Transport old = rig.wire.open("old game");
		Net_Peer service = old.resolve("service");
		old.send(service, ByteBuffer.wrap(future));
		rig.step(10);
		Net_Run.Inbox inbox = new Net_Run.Inbox();
		old.pump(inbox);
		eq(1, inbox.size(), "the service answers a game of another lobby version");
		Lobby_Message answer = Lobby_Codec.decode(ByteBuffer.wrap(inbox.bytes(0)));
		eq(Lobby_Message.Type.OUTDATED, answer.type(), "and the answer is");
		eq(Lobby_Codec.VERSION, ((Lobby_Message.Outdated) answer).version, "naming the version it speaks");
	}

	// ---------------------------------------------------------------- the service

	/** Host, list, join by a typed code : the joiner learns the host's address and the host the joiner's. */
	static void hostListJoinMirror() throws Exception
	{
		Rig rig = new Rig(2);
		Lobby_Client host = rig.client("host"), browser = rig.client("browser"), joiner = rig.client("joiner");
		host.candidates.add("192.168.1.20:40000");
		joiner.candidates.add("192.168.1.30:40001");
		host.host(8);
		host.players(1);
		rig.until(() -> host.code() != null, 2000, "the host was never given a code");
		eq("host", host.publicAddress(), "the host's public address is the one the service saw");

		browser.browse();
		rig.until(() -> browser.listing() != null, 2000, "no listing came");
		eq(1, browser.listing().rows.size(), "one lobby open");
		eq(new Lobby_Message.Row(host.code(), 1, 8), browser.listing().rows.get(0), "listed with its players and seats");

		is(joiner.join(host.code().toLowerCase().substring(0, 3) + "-" + host.code().substring(3)), "a typed code is a code");
		rig.until(() -> joiner.joined() != null, 2000, "the joiner never heard where the host is");
		eq(List.of("host", "192.168.1.20:40000"), joiner.joined().host, "the host's addresses, the one the service saw first");
		eq("joiner", joiner.publicAddress(), "the joiner learns its own public address");

		List<List<String>> coming = host.takeJoiners();
		is(!coming.isEmpty(), "the host was told someone is coming");
		eq(List.of("joiner", "192.168.1.30:40001"), coming.get(0), "from the joiner's addresses, the one the service saw first");

		// Every copy of the JOIN re-tells the host until the joiner stops : a lost PEER is not a lost player
		rig.run(1600, 50);
		is(host.takeJoiners().size() >= 2, "a joiner still asking keeps the host told");
		joiner.stopJoining();
		rig.run(1600, 50);
		eq(0, host.takeJoiners().size(), "a joiner that stopped asking stops the mirror");

		eq(0, host.rejected + joiner.rejected + browser.rejected, "nothing the service sent was refused");
	}

	/** Only a lobby of the same game protocol is listed or joined ; a full one and a missing one say so. */
	static void versionFullAndMissing() throws Exception
	{
		Rig rig = new Rig(3);
		Lobby_Client host = rig.client("host");
		host.host(2);
		host.players(2);
		rig.until(() -> host.code() != null, 2000, "no code");

		Lobby_Client full = rig.client("full");
		full.join(host.code());
		rig.until(() -> full.refused() != null, 2000, "joining a full lobby got no answer");
		eq(Lobby_Message.Refused.Reason.FULL, full.refused().reason, "a full lobby");
		host.players(1);
		rig.step(50);
		full.join(host.code());
		rig.until(() -> full.joined() != null, 2000, "a seat came free and the join still failed");

		Lobby_Client missing = rig.client("missing");
		missing.join("ZZZZZZ".equals(host.code()) ? "YYYYYY" : "ZZZZZZ");
		rig.until(() -> missing.refused() != null, 2000, "joining a code nobody holds got no answer");
		eq(Lobby_Message.Refused.Reason.NO_SUCH_LOBBY, missing.refused().reason, "a code nobody holds");

		// A game of another protocol version, by hand : the client always offers its own
		Net_Transport other = rig.wire.open("other version");
		Net_Peer service = other.resolve("service");
		Lobby_Message.Join join = new Lobby_Message.Join();
		join.game = Net_Codec.VERSION + 1;
		join.code = host.code();
		other.send(service, Lobby_Codec.encode(join));
		Lobby_Message.Browse browse = new Lobby_Message.Browse();
		browse.game = Net_Codec.VERSION + 1;
		other.send(service, Lobby_Codec.encode(browse));
		rig.step(10);
		Net_Run.Inbox inbox = new Net_Run.Inbox();
		other.pump(inbox);
		eq(2, inbox.size(), "both questions of the other version are answered");
		Lobby_Message.Refused no = (Lobby_Message.Refused) Lobby_Codec.decode(ByteBuffer.wrap(inbox.bytes(0)));
		eq(Lobby_Message.Refused.Reason.VERSION, no.reason, "a join across versions");
		eq(Net_Codec.VERSION, no.game, "the refusal names the lobby's version");
		Lobby_Message.Listing listing = (Lobby_Message.Listing) Lobby_Codec.decode(ByteBuffer.wrap(inbox.bytes(1)));
		eq(0, listing.total, "a lobby of another version is not listed");
	}

	/** Refreshes keep a lobby, silence reaps it, CLOSE ends it at once, only its host can close it, and a host that comes back gets its code. */
	static void reapCloseAndOldCode() throws Exception
	{
		Rig rig = new Rig(4);
		Lobby_Client host = rig.client("host");
		host.host(8);
		rig.until(() -> host.code() != null, 2000, "no code");
		String code = host.code();

		rig.run(120_000, 250);
		is(rig.service.lobby(code) != null, "a lobby its host keeps refreshing is open two minutes later");

		// A stranger's CLOSE of somebody else's code
		Net_Transport stranger = rig.wire.open("stranger");
		stranger.send(stranger.resolve("service"), Lobby_Codec.encode(new Lobby_Message.Close(code)));
		rig.step(10);
		is(rig.service.lobby(code) != null, "only its host closes a lobby");

		host.close();
		rig.step(10);
		is(rig.service.lobby(code) == null, "CLOSE ends it at once");

		// A host whose refreshes stopped (a crash, a pulled cable) : reaped, then back under its code
		Lobby_Client crashed = rig.client("crashed");
		crashed.host(4);
		rig.until(() -> crashed.code() != null, 2000, "no code");
		String old = crashed.code();
		rig.clients.remove(crashed);
		rig.run(Lobby_Service.REAP_MS - 2000, 250);
		is(rig.service.lobby(old) != null, "a lobby is not reaped before " + Lobby_Service.REAP_MS + " ms");
		rig.run(4000, 250);
		is(rig.service.lobby(old) == null, "a lobby whose host went quiet is reaped");
		rig.clients.add(crashed);
		rig.step(Lobby_Client.REFRESH_MS);
		is(rig.service.lobby(old) != null, "the host that came back has its lobby again");
		eq(old, crashed.code(), "under the code its friends were told");
	}

	/** Two idle minutes over a lossy wire : a host's lobby and a lobby screen's link both survive, on keepalives alone. */
	static void idleTwoMinutes() throws Exception
	{
		Rig rig = new Rig(5);
		rig.wire.loss = 0.05f;
		Lobby_Client host = rig.client("host"), idle = rig.client("idle");
		host.host(8);
		rig.until(() -> host.code() != null, 5000, "no code over 5% loss");
		idle.browse();
		rig.until(() -> idle.listing() != null, 5000, "no listing over 5% loss");

		boolean[] everLost = { false };
		for (int t = 0; t < 120_000; t += 250)
		{
			rig.step(250);
			everLost[0] |= idle.serviceLost() || host.serviceLost();
			is(rig.service.lobby(host.code()) != null, "the lobby vanished at " + t + " ms");
		}
		is(!everLost[0], "a client kept alive by its PINGs never lost the service");
		idle.join(host.code());
		rig.until(() -> idle.joined() != null, 5000, "a lobby screen that sat two minutes could not join");
	}

	/** A hundred questions in a second from one address get at most ANSWERS_PER_SECOND answers. */
	static void answersAreThrottled() throws Exception
	{
		Rig rig = new Rig(6);
		Net_Transport flood = rig.wire.open("flood");
		Net_Peer service = flood.resolve("service");
		for (int i = 0; i < 100; i++)
			flood.send(service, Lobby_Codec.encode(new Lobby_Message.Ping()));
		rig.step(10);
		Net_Run.Inbox inbox = new Net_Run.Inbox();
		flood.pump(inbox);
		eq(Lobby_Service.ANSWERS_PER_SECOND, inbox.size(), "answers to one address in one second");
		rig.step(1000);
		flood.send(service, Lobby_Codec.encode(new Lobby_Message.Ping()));
		rig.step(10);
		flood.pump(inbox);
		eq(Lobby_Service.ANSWERS_PER_SECOND + 1, inbox.size(), "the next second answers again");
	}

	// ---------------------------------------------------------------- the shared socket

	/**
	 * THE RULE, over real UDP : a host and a client each talk to the service and play on ONE socket. The
	 * client reaches the host at the address the service handed it, the host's session sees the client at
	 * exactly the address the service mirrored, and neither session ever sees a lobby packet.
	 */
	static void sharedSocketOverUdp() throws Exception
	{
		try (Transport_Udp serviceEnd = Transport_Udp.open(); Transport_Udp hostEnd = Transport_Udp.open(); Transport_Udp clientEnd = Transport_Udp.open())
		{
			long start = System.nanoTime();
			Lobby_Service service = new Lobby_Service(serviceEnd, () -> (System.nanoTime() - start) / 1_000_000L, new Random(7));
			String serviceAddress = "127.0.0.1:" + serviceEnd.localPort();
			Lobby_Client hostLobby = new Lobby_Client(hostEnd, serviceAddress, () -> (System.nanoTime() - start) / 1_000_000L);
			Lobby_Client clientLobby = new Lobby_Client(clientEnd, serviceAddress, () -> (System.nanoTime() - start) / 1_000_000L);

			Net_Toy_World world = new Net_Toy_World();
			HostSession host = new HostSession(hostLobby.game(), world);
			hostLobby.host(HostSession.MAX_PLAYERS);

			long deadline = System.currentTimeMillis() + 3000;
			ClientSession client = null;
			List<String> mirrored = null;
			boolean asked = false;
			while (System.currentTimeMillis() < deadline)
			{
				service.pump();
				host.tick(world::step);
				hostLobby.players(host.seats().size());
				for (List<String> joiner : hostLobby.takeJoiners())
					mirrored = joiner;

				if (client == null)
				{
					clientLobby.pump();
					if (hostLobby.code() != null && !asked)
						asked = clientLobby.join(hostLobby.code());
					if (clientLobby.joined() != null)
						client = new ClientSession(clientLobby.game(), clientLobby.joined().host.get(0));
				}
				else
				{
					client.tick(0);
					if (client.state() == ClientSession.State.IN)
					{
						clientLobby.stopJoining();
						if (host.seats().size() == 1 && mirrored != null)
							break;
					}
				}
				Thread.sleep(2);
			}
			// The host's new player count on its way to the service
			for (int i = 0; i < 50; i++)
			{
				service.pump();
				host.tick(world::step);
				if (client != null)
					client.tick(0);
				Thread.sleep(2);
			}

			is(client != null && client.state() == ClientSession.State.IN, "the client never got into the game it found by code : " + (client == null ? "no JOINED" : client.state()));
			eq(1, host.seats().size(), "the host's players");
			String seen = host.seats().get(0).peer.address();
			eq(mirrored.get(0), seen, "the host's session sees the client at the address the service mirrored");
			eq(clientLobby.publicAddress(), seen, "which is the address the client was told is its own");
			eq(hostLobby.publicAddress(), clientLobby.joined().host.get(0), "and the client reached the host where the service saw it");
			eq(0, host.rejected, "the host's session never saw a lobby packet");
			eq(0, client.rejected, "the client's session never saw a lobby packet");
			eq(1, service.lobbies().get(0).players, "the lobby counts the player the host let in");
		}
	}

	/** Game packets that arrive while only the lobby client pumps wait for the session, in order ; lobby packets never do. */
	static void gamePacketsWaitForTheSession() throws Exception
	{
		Rig rig = new Rig(8);
		Lobby_Client lobby = rig.client("player");
		Net_Transport friend = rig.wire.open("friend");
		Net_Peer player = friend.resolve("player");
		for (int i = 0; i < 5; i++)
			friend.send(player, ByteBuffer.wrap(new byte[] { 1, (byte) i }));
		lobby.browse();
		rig.step(10);
		is(lobby.listing() != null, "the lobby answer was read while the game packets waited");

		Net_Run.Inbox inbox = new Net_Run.Inbox();
		lobby.game().pump(inbox);
		eq(5, inbox.size(), "every game packet reached the session's pump");
		for (int i = 0; i < 5; i++)
			eq(i, (int) inbox.bytes(i)[1], "game packet " + i + " in order");
		for (int i = 0; i < inbox.size(); i++)
			is(!Lobby_Codec.isLobbyPacket(ByteBuffer.wrap(inbox.bytes(i))), "a lobby packet reached the game");

		// A lobby packet from someone who is not the service is not believed
		Lobby_Message.Hosted forged = new Lobby_Message.Hosted("ABC234", "6.6.6.6:6");
		friend.send(player, Lobby_Codec.encode(forged));
		rig.step(10);
		is(!"6.6.6.6:6".equals(lobby.publicAddress()), "a forged answer from a stranger changed the public address");
		inbox = new Net_Run.Inbox();
		lobby.game().pump(inbox);
		eq(0, inbox.size(), "and it did not reach the game either");
	}

	// ---------------------------------------------------------------- plumbing

	static Lobby_Message roundTrip(Lobby_Message message) throws Net_Rejected
	{
		ByteBuffer packet = Lobby_Codec.encode(message);
		eq(Lobby_Codec.sizeOf(message), packet.remaining(), "sizeOf a " + message.type());
		Lobby_Message back = Lobby_Codec.decode(packet);
		eq(message.type(), back.type(), "type");
		return back;
	}

	static void refused(ByteBuffer packet, String what)
	{
		try
		{
			Lobby_Codec.decode(packet);
			is(false, what + " was read");
		}
		catch (Net_Rejected expected)
		{
			// refused whole
		}
	}

	static Net_Rejected.Reason reason(ByteBuffer packet)
	{
		try
		{
			Lobby_Codec.decode(packet);
			return null;
		}
		catch (Net_Rejected e)
		{
			return e.reason;
		}
	}

	/** The packet with one byte changed and its checksum made right again : a lie the CRC cannot catch. */
	static ByteBuffer withCrc(byte[] bytes, int index, byte value)
	{
		byte[] lie = bytes.clone();
		lie[index] = value;
		ByteBuffer buffer = ByteBuffer.wrap(lie);
		buffer.putInt(lie.length - 4, crc(lie, lie.length - 4));
		return buffer;
	}

	static int crc(byte[] bytes, int length)
	{
		java.util.zip.CRC32 crc = new java.util.zip.CRC32();
		crc.update(bytes, 0, length);
		return (int) crc.getValue();
	}
}
