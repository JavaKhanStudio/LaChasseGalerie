package jks.smoke;

import static jks.smoke.Net_Run.eq;
import static jks.smoke.Net_Run.is;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jks.lobby.Lobby_Client;
import jks.lobby.Lobby_Ice;
import jks.net.Lobby_Codec;
import jks.net.Lobby_Message;
import jks.net.Net_Listener;
import jks.net.Net_Loopback;
import jks.net.Net_Loopback.Nat;
import jks.net.Net_Peer;
import jks.net.Net_Rejected;
import jks.net.Net_Transport;
import jks.net.Stun_Codec;
import jks.net.Transport_Udp;
import jks.net.Turn_Client;
import jks.net.Turn_Codec;

/**
 * nettest's checks for the relay (r45, d6 -> relay) : the TURN codec against RFC 5769's signed vector,
 * the lobby naming the relay with a credential it minted, and pairs no check gets through going through
 * a relay on the in-memory wire - while every pair that punches still goes DIRECT.
 *
 * The relay here is {@link Fake_Relay}, the subset of coturn the game uses, checking credentials the way
 * coturn's use-auth-secret does. It proves the game's side. The real coturn on VPS_1 is proven by
 * `./gradlew netrelay` (two sockets on this machine through it, over the internet) and by
 * deploy/relay/check.sh (what it refuses).
 */
class Net_Relay_Checks
{
	static final String RELAY = "192.0.2.50:3478", SECRET = "a secret the relay and the service share";
	static final long EPOCH = 1_800_000_000L;

	/**
	 * A TURN server on the wire : Allocate behind a 401 challenge, CreatePermission, Refresh, Send and Data
	 * indications, each allocation a transport of its own on the relay's ip. A credential is good when its
	 * password is the secret's HMAC of its username, as coturn's use-auth-secret checks it.
	 */
	static final class Fake_Relay implements Runnable
	{
		final Net_Loopback wire;
		final Net_Transport front;
		final String secret, ip, realm = "lachassegalerie", nonce = "0123456789abcdef";
		final Map<String, Allocation> byClient = new LinkedHashMap<String, Allocation>();
		int nextPort = 49160, unauthorised, forbidden;

		final class Allocation
		{
			final Net_Peer client;
			final Net_Transport relayed;
			final Set<String> permits = new HashSet<String>();

			Allocation(Net_Peer client, Net_Transport relayed)
			{
				this.client = client;
				this.relayed = relayed;
			}
		}

		Fake_Relay(Net_Loopback wire, String address, String secret)
		{
			this.wire = wire;
			this.front = wire.open(address);
			this.secret = secret;
			this.ip = address.substring(0, address.lastIndexOf(':'));
		}

		@Override
		public void run()
		{
			front.pump((from, payload) -> received(from, payload));
			for (Allocation allocation : new ArrayList<Allocation>(byClient.values()))
				allocation.relayed.pump((from, payload) ->
				{
					if (!allocation.permits.contains(ipOf(from.address())))
						return; // no permission : dropped, as a relay does
					Turn_Codec.Message data = new Turn_Codec.Message(Turn_Codec.DATA, Turn_Codec.INDICATION, transaction());
					data.peer = from.address();
					data.data = new byte[payload.remaining()];
					payload.get(data.data);
					front.send(allocation.client, Turn_Codec.encode(data, null));
				});
		}

		void received(Net_Peer from, ByteBuffer payload)
		{
			ByteBuffer packet = payload.duplicate();
			Turn_Codec.Message message;
			try
			{
				message = Turn_Codec.decode(payload);
			}
			catch (Net_Rejected e)
			{
				return;
			}
			Allocation allocation = byClient.get(from.address());
			if (message.kind == Turn_Codec.INDICATION)
			{
				if (message.method == Turn_Codec.SEND && allocation != null && allocation.permits.contains(ipOf(message.peer)))
					allocation.relayed.send(allocation.relayed.resolve(message.peer), ByteBuffer.wrap(message.data));
				return;
			}
			if (message.kind != Turn_Codec.REQUEST)
				return;
			byte[] key = message.username == null ? null : Turn_Codec.key(message.username, realm, password(secret, message.username));
			if (key == null || !Turn_Codec.authentic(packet, message, key))
			{
				unauthorised++;
				Turn_Codec.Message no = new Turn_Codec.Message(message.method, Turn_Codec.ERROR, message.transaction);
				no.error = 401;
				no.reason = "Unauthorized";
				no.realm = realm;
				no.nonce = nonce;
				front.send(from, Turn_Codec.encode(no, null));
				return;
			}
			Turn_Codec.Message yes = new Turn_Codec.Message(message.method, Turn_Codec.SUCCESS, message.transaction);
			switch (message.method)
			{
				case Turn_Codec.ALLOCATE:
					if (allocation == null)
					{
						allocation = new Allocation(from, wire.open(ip + ":" + nextPort++));
						byClient.put(from.address(), allocation);
					}
					yes.relayed = ip + ":" + (nextPort - 1);
					yes.mapped = from.address();
					yes.lifetime = 600;
					break;
				case Turn_Codec.CREATE_PERMISSION:
					if (allocation == null)
						return;
					allocation.permits.add(ipOf(message.peer));
					break;
				case Turn_Codec.REFRESH:
					if (message.lifetime == 0 && allocation != null)
						byClient.remove(from.address());
					yes.lifetime = message.lifetime;
					break;
				default:
					return;
			}
			front.send(from, Turn_Codec.encode(yes, key));
		}

		static String ipOf(String address)
		{
			return address.substring(0, address.lastIndexOf(':'));
		}

		final Random random = new Random(7);

		byte[] transaction()
		{
			byte[] transaction = new byte[12];
			random.nextBytes(transaction);
			return transaction;
		}
	}

	/** coturn's use-auth-secret : base64 HMAC-SHA1 of the username under the secret. */
	static String password(String secret, String username)
	{
		try
		{
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
			return Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** A rig whose service names a relay on the wire, signing with this secret ; the relay checks with its own. */
	static Net_Ice_Checks.Rig rig(long seed, String serviceSecret, String relaySecret)
	{
		Net_Ice_Checks.Rig rig = new Net_Ice_Checks.Rig(seed);
		rig.service.relay(RELAY, serviceSecret);
		rig.service.epochSeconds = () -> EPOCH;
		rig.servers.add(new Fake_Relay(rig.wire, RELAY, relaySecret));
		return rig;
	}

	static byte[] hex(String text)
	{
		return Net_Ice_Checks.hex(text);
	}

	// ---------------------------------------------------------------- the codec

	/** RFC 5769 2.4 : a request signed with a long-term credential by another implementation. Our key and HMAC must agree with it. */
	static void codecReadsTheRfcSignedVector() throws Exception
	{
		byte[] signed = hex("000100602112a44278ad3433c6ad72c029da412e"
				+ "00060012e3839ee38388e383aae38383e382afe382b90000"
				+ "0015001c662f2f3439396b39353464364f4c33346f4c394653547679363473 41".replace(" ", "")
				+ "0014000b6578616d706c652e6f726700"
				+ "00080014f67024656dd64a3e02b8e0712e85c9a28ca89666");
		Turn_Codec.Message read = Turn_Codec.decode(ByteBuffer.wrap(signed));
		eq("マトリックス", read.username, "the vector's USERNAME, UTF-8");
		eq("example.org", read.realm, "the vector's REALM");
		eq("f//499k954d6OL34oL9FSTvy64sA", read.nonce, "the vector's NONCE");
		byte[] key = Turn_Codec.key(read.username, read.realm, "TheMatrIX");
		is(Turn_Codec.authentic(ByteBuffer.wrap(signed), read, key), "the vector's MESSAGE-INTEGRITY is the one our key makes");
		is(!Turn_Codec.authentic(ByteBuffer.wrap(signed), read, Turn_Codec.key(read.username, read.realm, "TheMatrix")), "another password's key is not");
	}

	/** Every message the client and the relay trade reads back ; a signed answer with any bit flipped is refused or not authentic. */
	static void codecRoundTripsAndRefuses() throws Exception
	{
		byte[] transaction = new byte[12];
		new Random(11).nextBytes(transaction);
		byte[] key = Turn_Codec.key("1800086400:ABCDEF", "lachassegalerie", "pw");

		Turn_Codec.Message allocate = new Turn_Codec.Message(Turn_Codec.ALLOCATE, Turn_Codec.REQUEST, transaction);
		allocate.username = "1800086400:ABCDEF";
		allocate.realm = "lachassegalerie";
		allocate.nonce = "n";
		ByteBuffer packet = Turn_Codec.encode(allocate, key);
		is(Turn_Codec.isTurnPacket(packet), "an Allocate is a TURN packet");
		is(Stun_Codec.isStunPacket(packet), "and STUN-framed");
		Turn_Codec.Message read = Turn_Codec.decode(packet);
		is(read.is(Turn_Codec.ALLOCATE, Turn_Codec.REQUEST), "an Allocate request reads back as one : " + read);
		eq("1800086400:ABCDEF", read.username, "its USERNAME");
		is(Turn_Codec.authentic(packet, read, key), "its MESSAGE-INTEGRITY");

		Turn_Codec.Message success = new Turn_Codec.Message(Turn_Codec.ALLOCATE, Turn_Codec.SUCCESS, transaction);
		success.relayed = "192.0.2.50:49160";
		success.mapped = "[2001:db8:0:0:0:0:0:7]:40000";
		success.lifetime = 600;
		byte[] signed = array(Turn_Codec.encode(success, key));
		read = Turn_Codec.decode(ByteBuffer.wrap(signed));
		eq("192.0.2.50:49160", read.relayed, "XOR-RELAYED-ADDRESS");
		eq("[2001:db8:0:0:0:0:0:7]:40000", read.mapped, "XOR-MAPPED-ADDRESS, IPv6");
		eq(600, read.lifetime, "LIFETIME");
		for (int bit = 0; bit < signed.length * 8; bit++)
		{
			byte[] flipped = signed.clone();
			flipped[bit / 8] ^= 1 << (bit % 8);
			try
			{
				Turn_Codec.Message lie = Turn_Codec.decode(ByteBuffer.wrap(flipped));
				// A flip past MESSAGE-INTEGRITY (the FINGERPRINT's type) may still pass : then nothing read may have changed
				if (Turn_Codec.authentic(ByteBuffer.wrap(flipped), lie, key))
					is("192.0.2.50:49160".equals(lie.relayed) && "[2001:db8:0:0:0:0:0:7]:40000".equals(lie.mapped) && lie.lifetime == 600,
							"a signed success with bit " + bit + " flipped passed as the relay's, and reads " + lie.relayed + " " + lie.mapped + " " + lie.lifetime);
			}
			catch (Net_Rejected expected)
			{
				// refused whole
			}
		}

		Turn_Codec.Message error = new Turn_Codec.Message(Turn_Codec.ALLOCATE, Turn_Codec.ERROR, transaction);
		error.error = 486;
		error.reason = "Allocation Quota Reached";
		read = Turn_Codec.decode(Turn_Codec.encode(error, null));
		eq(486, read.error, "ERROR-CODE");
		eq("Allocation Quota Reached", read.reason, "its reason");

		Turn_Codec.Message data = new Turn_Codec.Message(Turn_Codec.DATA, Turn_Codec.INDICATION, transaction);
		data.peer = "[2001:db8:1:2:3:4:5:6]:65535";
		data.data = new byte[Turn_Codec.MAX_RELAYED];
		new Random(3).nextBytes(data.data);
		ByteBuffer biggest = Turn_Codec.encode(data, null);
		is(biggest.remaining() <= Net_Transport.MAX_PAYLOAD, "the biggest relayed payload to an IPv6 peer fits a packet : " + biggest.remaining() + " B");
		read = Turn_Codec.decode(biggest);
		eq(data.peer, read.peer, "XOR-PEER-ADDRESS, IPv6");
		is(java.util.Arrays.equals(data.data, read.data), "DATA, byte for byte");

		// Never a game or lobby packet, and the ICE side's Bindings are not TURN
		ByteBuffer binding = Stun_Codec.encode(Stun_Codec.Binding.request(transaction, "1.1.1.1:1"));
		is(!Turn_Codec.isTurnPacket(binding), "a Binding is ICE's, not the relay's");
		is(!Lobby_Codec.isLobbyPacket(packet), "a TURN packet is not a lobby packet");
		try
		{
			jks.net.Net_Codec.decode(packet);
			is(false, "the game codec read a TURN packet");
		}
		catch (Net_Rejected e)
		{
			eq(Net_Rejected.Reason.NOT_OURS, e.reason, "a TURN packet at a game session");
		}
	}

	/** The service names the relay in HOSTED and JOINED with a credential coturn would take ; none when it has no relay. */
	static void lobbyNamesTheRelay() throws Exception
	{
		Net_Ice_Checks.Rig rig = rig(1, SECRET, SECRET);
		Lobby_Client host = rig.client(Net_Ice_Checks.HOST_INSIDE, Net_Ice_Checks.HOST_IP, Nat.Kind.FULL_CONE);
		Lobby_Client joiner = rig.client(Net_Ice_Checks.JOINER_INSIDE, Net_Ice_Checks.JOINER_IP, Nat.Kind.FULL_CONE);
		host.host(4);
		rig.until(() -> host.code() != null, 5_000, "no code");
		joiner.join(host.code());
		rig.until(() -> joiner.joined() != null, 5_000, "no JOINED");
		Lobby_Message.Relay relay = joiner.joined().relay;
		is(relay != null, "JOINED names the relay");
		eq(RELAY, relay.server, "the relay's address");
		eq((EPOCH + jks.lobby.Lobby_Service.RELAY_CREDENTIAL_S) + ":" + host.code(), relay.username, "the username is the credential's expiry");
		eq(password(SECRET, relay.username), relay.password, "the password is the secret's signature of it");

		Lobby_Message.Joined joined = new Lobby_Message.Joined();
		joined.code = "ABCDEF";
		joined.you = "198.51.100.7:1";
		joined.host.add("203.0.113.1:2");
		joined.relay = relay;
		eq(relay, ((Lobby_Message.Joined) Lobby_Codec.decode(Lobby_Codec.encode(joined))).relay, "a JOINED's relay round trip");
		Lobby_Message.Hosted hosted = new Lobby_Message.Hosted("ABCDEF", "203.0.113.1:2");
		is(((Lobby_Message.Hosted) Lobby_Codec.decode(Lobby_Codec.encode(hosted))).relay == null, "a HOSTED with no relay round trip");
		eq(2, Lobby_Codec.VERSION, "the relay block is lobby version 2");

		Net_Ice_Checks.Rig none = new Net_Ice_Checks.Rig(1);
		Lobby_Client alone = none.client(Net_Ice_Checks.HOST_INSIDE, Net_Ice_Checks.HOST_IP, Nat.Kind.FULL_CONE);
		Lobby_Client other = none.client(Net_Ice_Checks.JOINER_INSIDE, Net_Ice_Checks.JOINER_IP, Nat.Kind.FULL_CONE);
		alone.host(4);
		none.until(() -> alone.code() != null, 5_000, "no code");
		other.join(alone.code());
		none.until(() -> other.joined() != null, 5_000, "no JOINED");
		is(other.joined().relay == null && other.relay() == null, "a service with no relay names none, and nobody allocates");
	}

	// ---------------------------------------------------------------- pairs

	/**
	 * Every router against every router with a relay on the wire : the relay changes nothing for a pair that
	 * punches (DIRECT, as fast as without it), and both symmetric - the pair r42 had to call CANNOT_CONNECT -
	 * is RELAYED on both rows, with a game packet across it each way.
	 */
	static void everyRouterWithARelay() throws Exception
	{
		StringBuilder table = new StringBuilder();
		for (Nat.Kind hostKind : Net_Ice_Checks.KINDS)
			for (Nat.Kind joinerKind : Net_Ice_Checks.KINDS)
			{
				String what = "host " + Net_Ice_Checks.name(hostKind) + ", joiner " + Net_Ice_Checks.name(joinerKind) + ", with a relay";
				Net_Ice_Checks.Pair pair = new Net_Ice_Checks.Pair(rig(1, SECRET, SECRET), hostKind, joinerKind);
				pair.rig.until(() -> pair.joinerRow().usable() && pair.hostRoute() == pair.joinerRow().route(), Lobby_Ice.GIVE_UP_MS + 2_000,
						what + " : the rows never settled : " + pair.hostRow() + " / " + pair.joinerRow());
				boolean hopeless = hostKind == Nat.Kind.SYMMETRIC && joinerKind == Nat.Kind.SYMMETRIC;
				eq(hopeless ? Lobby_Ice.Route.RELAYED : Lobby_Ice.Route.DIRECT, pair.joinerRow().route(), what);
				pair.rig.until(() -> pair.joiner.relay().state() == Turn_Client.State.ALLOCATED, 2_000, what + " : the joiner allocates whatever happens : "
						+ pair.joiner.relay().state() + " " + pair.joiner.relay().why());
				if (hopeless)
				{
					is(pair.joinerRow().address().startsWith(Turn_Client.PREFIX), what + " : the game goes through the relay : " + pair.joinerRow().address());
					is(pair.joinerRow().settledMs() >= Lobby_Ice.RELAY_AFTER_MS, what + " : the relay was taken before the punch had its chance");
					is(pair.joinerRow().advice() != null && pair.joinerRow().advice().contains("Relayed"), what + " : the row says it is relayed : " + pair.joinerRow().advice());
				}
				pair.gamePacketsCross(what);
				table.append(String.format("%n      %-16s x %-16s %-15s %5d ms", Net_Ice_Checks.name(hostKind), Net_Ice_Checks.name(joinerKind),
						pair.joinerRow().route(), pair.joinerRow().settledMs()));
			}
		System.out.println("NET      host router x joiner router with a relay, the joiner's row :" + table);
	}

	/** A credential the relay does not take : the relay says why, and the row is CANNOT_CONNECT, as with no relay. */
	static void refusedCredentialIsHonest() throws Exception
	{
		Net_Ice_Checks.Pair pair = new Net_Ice_Checks.Pair(rig(1, SECRET, "another secret"), Nat.Kind.SYMMETRIC, Nat.Kind.SYMMETRIC);
		pair.rig.until(() -> pair.joiner.relay() != null && pair.joiner.relay().state() == Turn_Client.State.FAILED, 3_000,
				"a refused credential never failed : " + (pair.joiner.relay() == null ? null : pair.joiner.relay().state()));
		is(pair.joiner.relay().why().startsWith("401"), "it says why : " + pair.joiner.relay().why());
		pair.rig.run(Lobby_Ice.GIVE_UP_MS + 500);
		eq(Lobby_Ice.Route.CANNOT_CONNECT, pair.joinerRow().route(), "the joiner's row");
		eq(Lobby_Ice.Route.CANNOT_CONNECT, pair.hostRoute(), "the host's row");
	}

	/**
	 * The relayed packet's size limit, both ways ; the relay freed on close ; and a host that goes quiet is
	 * lost to the joiner's game through the relay as it would be straight.
	 */
	static void relayedSizeCloseAndSilence() throws Exception
	{
		Net_Ice_Checks.Rig rig = rig(1, SECRET, SECRET);
		Fake_Relay relay = (Fake_Relay) rig.servers.get(0);
		Net_Ice_Checks.Pair pair = new Net_Ice_Checks.Pair(rig, Nat.Kind.SYMMETRIC, Nat.Kind.SYMMETRIC);
		rig.until(() -> pair.joinerRow().route() == Lobby_Ice.Route.RELAYED && pair.hostRoute() == Lobby_Ice.Route.RELAYED, Lobby_Ice.GIVE_UP_MS + 2_000,
				"never RELAYED");
		String address = pair.joinerRow().address();

		byte[] biggest = new byte[Turn_Codec.MAX_RELAYED];
		new Random(5).nextBytes(biggest);
		rig.game(pair.host).clear();
		pair.joiner.game().send(pair.joiner.game().resolve(address), ByteBuffer.wrap(biggest));
		rig.step(20);
		eq(1, rig.game(pair.host).size(), "the biggest relayed packet reached the host");
		try
		{
			pair.joiner.game().send(pair.joiner.game().resolve(address), ByteBuffer.allocate(Turn_Codec.MAX_RELAYED + 1));
			is(false, "one byte past a relayed packet's limit was taken");
		}
		catch (IllegalArgumentException expected)
		{
			is(expected.getMessage().contains(String.valueOf(Turn_Codec.MAX_RELAYED)), "the refusal names the limit : " + expected.getMessage());
		}

		// The host stops : its game view and its lobby client go quiet together
		int h = rig.clients.indexOf(pair.host);
		rig.clients.remove(h);
		rig.games.remove(h);
		rig.lost.remove(h);
		List<String> lost = rig.lost.get(rig.clients.indexOf(pair.joiner));
		rig.run(12_000);
		is(lost.contains(address), "the relayed host was never reported lost to the joiner's game : " + lost);

		pair.joiner.close();
		rig.step(20);
		is(relay.byClient.isEmpty(), "close frees the allocation on the relay");
	}

	/**
	 * A relay's Data indication is a payload plus its framing : over real UDP, the one kind of datagram past
	 * MAX_PAYLOAD a transport delivers. Any other that size is still dropped.
	 */
	static void udpTakesARelayedDatagram() throws Exception
	{
		try (Net_Transport end = Transport_Udp.open(); DatagramSocket raw = new DatagramSocket())
		{
			// A raw socket stands in for the relay : Transport_Udp itself will not send past MAX_PAYLOAD
			Turn_Codec.Message data = new Turn_Codec.Message(Turn_Codec.DATA, Turn_Codec.INDICATION, new byte[12]);
			data.peer = "203.0.113.1:4000";
			data.data = new byte[Net_Transport.MAX_PAYLOAD];
			byte[] indication = rawEncode(data);
			is(indication.length > Net_Transport.MAX_PAYLOAD && indication.length <= Transport_Udp.MAX_RELAYED_DATAGRAM,
					"a full payload relayed is " + indication.length + " B, past MAX_PAYLOAD and within MAX_RELAYED_DATAGRAM");
			byte[] junk = new byte[indication.length];
			InetAddress loopback = InetAddress.getLoopbackAddress();
			raw.send(new DatagramPacket(junk, junk.length, loopback, end.localPort()));
			raw.send(new DatagramPacket(indication, indication.length, loopback, end.localPort()));
			List<Integer> sizes = new ArrayList<Integer>();
			long deadline = System.currentTimeMillis() + Net_Run.DEADLINE_MS;
			while (sizes.isEmpty() && System.currentTimeMillis() < deadline)
			{
				end.pump(new Net_Listener()
				{
					@Override
					public void received(Net_Peer from, ByteBuffer payload)
					{
						sizes.add(payload.remaining());
					}
				});
				Thread.sleep(5);
			}
			eq(List.of(indication.length), sizes, "the Data indication arrived whole, the junk of the same size did not");
			eq(data.data.length, Turn_Codec.decode(ByteBuffer.wrap(indication)).data.length, "and it unwraps to the whole payload");
		}
	}

	/** Turn_Codec.encode refuses past MAX_PAYLOAD, as the relay would not : this is the relay's framing, by hand. */
	static byte[] rawEncode(Turn_Codec.Message data)
	{
		byte[] payload = data.data;
		data.data = new byte[0];
		byte[] head = array(Turn_Codec.encode(data, null));
		data.data = payload;
		ByteBuffer out = ByteBuffer.allocate(head.length + payload.length + 3);
		out.put(head);
		out.position(head.length - 4);
		out.putShort((short) 0x0013).putShort((short) payload.length).put(payload);
		while (out.position() % 4 != 0)
			out.put((byte) 0);
		out.putShort(2, (short) (out.position() - 20));
		return java.util.Arrays.copyOf(out.array(), out.position());
	}

	static byte[] array(ByteBuffer buffer)
	{
		byte[] bytes = new byte[buffer.remaining()];
		buffer.duplicate().get(bytes);
		return bytes;
	}
}
