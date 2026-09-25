package jks.smoke;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import jks.net.Net_Listener;
import jks.net.Net_Loopback;
import jks.net.Net_Peer;
import jks.net.Net_Transport;
import jks.net.Transport_Udp;

/**
 * The gate for jks.net : `./gradlew nettest`, a couple of seconds, no window and no game.
 *
 * It holds the transport seam to its contract (docs/online-multiplayer.md phase 1), over real UDP
 * on loopback and over the in-memory wire, because everything the netcode will be built on
 * assumes all of it : that a packet arrives whole or not at all, that a host learns a peer it has
 * never seen from the packet that punched its way in, that an oversized payload is refused out
 * loud instead of being fragmented, and that a peer which goes quiet is reported once and
 * forgotten.
 *
 * No assertion here waits on a wall clock it does not control : the timeout tests drive an
 * injected clock, and the UDP tests poll with a deadline, because a loopback packet is fast but
 * not instantaneous.
 *
 * Exits 1 on the first failed check, with the check named.
 */
public class Net_Run
{
	static final long DEADLINE_MS = 2000;
	static int failed, checks;

	public static void main(String[] args)
	{
		check("udp/ephemeral-port", Net_Run::udpEphemeralPort);
		check("udp/round-trip", Net_Run::udpRoundTrip);
		check("udp/host-learns-unknown-peers", Net_Run::udpHostLearnsPeers);
		check("udp/payload-cap", Net_Run::udpPayloadCap);
		check("udp/whole-or-nothing", Net_Run::udpWholeOrNothing);
		check("udp/pump-never-blocks", Net_Run::udpPumpNeverBlocks);
		check("udp/peer-timeout", Net_Run::udpPeerTimeout);
		check("udp/ipv6-loopback", Net_Run::udpIpv6);
		check("wire/round-trip", Net_Run::wireRoundTrip);
		check("wire/loss-is-seeded", Net_Run::wireLoss);
		check("wire/duplicates-and-reorder", Net_Run::wireDuplicatesAndReorder);
		check("wire/silent-peer-is-lost-once", Net_Run::wireSilentPeer);
		check("wire/keepalive-keeps-a-peer", Net_Run::wireKeepalive);
		check("wire/closed-end-stops-being-reachable", Net_Run::wireClosedEnd);
		check("codec/control-round-trip", Net_Codec_Checks::controlRoundTrip);
		check("codec/input-round-trip", Net_Codec_Checks::inputRoundTrip);
		check("codec/snapshot-round-trip-every-field", Net_Codec_Checks::snapshotRoundTrip);
		check("codec/quantizing-clamps-and-wraps", Net_Codec_Checks::quantizing);
		check("codec/truncated-is-rejected", Net_Codec_Checks::truncatedIsRejected);
		check("codec/corrupted-is-rejected", Net_Codec_Checks::corruptedIsRejected);
		check("codec/lies-are-rejected", Net_Codec_Checks::liesAreRejected);
		check("codec/oversized-is-refused-out-loud", Net_Codec_Checks::oversizedIsRefused);
		check("codec/measured-8-player-peak-fits", Net_Codec_Checks::measuredPeakFits);
		check("codec/input-survives-loss", Net_Codec_Checks::inputSurvivesLoss);
		check("mirror/create-move-destroy", Net_Mirror_Checks::createMoveDestroy);
		check("mirror/stale-is-ignored", Net_Mirror_Checks::staleIsIgnored);
		check("mirror/duplicate-ids-are-refused", Net_Mirror_Checks::duplicateIdsAreRefused);
		check("mirror/id-changing-kind", Net_Mirror_Checks::idChangingKind);
		check("mirror/late-joiner-and-rejoin", Net_Mirror_Checks::lateJoinerAndRejoin);
		check("wire/latency-and-jitter", Net_Session_Checks::latencyAndJitter);
		check("session/welcome-and-join-with-no-host-hero", Net_Session_Checks::welcomeAndJoin);
		check("session/hello-again-is-the-same-player", Net_Session_Checks::helloAgainIsTheSamePlayer);
		check("session/input-lands-once-in-order", Net_Session_Checks::inputLandsOnceInOrder);
		check("session/future-frames-wait-for-their-tick", Net_Session_Checks::futureFramesWaitForTheirTick);
		check("session/strangers-are-ignored", Net_Session_Checks::strangersAreIgnored);
		check("session/rejoin-after-death", Net_Session_Checks::rejoinAfterDeath);
		check("session/leave-and-timeout", Net_Session_Checks::leaveAndTimeout);
		check("session/full-and-host-ended", Net_Session_Checks::fullAndHostEnded);
		check("session/version-is-refused", Net_Session_Checks::versionIsRefused);
		check("session/returning-machine-gets-its-player-back", Net_Session_Checks::returningMachineGetsItsPlayerBack);
		check("session/silent-seat-is-taken-over", Net_Session_Checks::silentSeatIsTakenOver);
		check("session/shared-key-while-seated-is-a-new-player", Net_Session_Checks::sharedKeyWhileSeatedIsANewPlayer);
		check("session/rows-are-capped", Net_Session_Checks::rowsAreCapped);
		check("session/clients-track-the-host", Net_Session_Checks::clientsTrackTheHost);
		check("lobby/codec-round-trip", Net_Lobby_Checks::codecRoundTrip);
		check("lobby/codec-refuses-and-never-mixes-with-game-packets", Net_Lobby_Checks::codecRefuses);
		check("lobby/another-lobby-version-is-told-which", Net_Lobby_Checks::outdatedIsAnswered);
		check("lobby/host-list-join-and-mirror", Net_Lobby_Checks::hostListJoinMirror);
		check("lobby/version-full-and-missing-are-refused", Net_Lobby_Checks::versionFullAndMissing);
		check("lobby/reap-close-and-the-old-code-back", Net_Lobby_Checks::reapCloseAndOldCode);
		check("lobby/idle-two-minutes-over-loss", Net_Lobby_Checks::idleTwoMinutes);
		check("lobby/answers-are-throttled", Net_Lobby_Checks::answersAreThrottled);
		check("lobby/game-packets-wait-for-the-session", Net_Lobby_Checks::gamePacketsWaitForTheSession);
		check("lobby/one-socket-for-lobby-and-game-over-udp", Net_Lobby_Checks::sharedSocketOverUdp);
		check("ice/stun-codec-reads-the-rfc-5769-vectors", Net_Ice_Checks::codecReadsTheRfcVectors);
		check("ice/stun-codec-refuses-and-never-mixes", Net_Ice_Checks::codecRefusesAndNeverMixes);
		check("ice/address-text-is-the-transports", Net_Ice_Checks::addressTextIsTheTransports);
		check("ice/probe-tells-the-mapping-and-names-a-fix", Net_Ice_Checks::probeTellsTheMapping);
		check("ice/every-router-against-every-router", Net_Ice_Checks::everyRouterAgainstEvery);
		check("ice/random-ports-are-honest", Net_Ice_Checks::randomPortsAreHonest);
		check("ice/ipv6-first", Net_Ice_Checks::ipv6First);
		check("ice/same-lan-goes-straight", Net_Ice_Checks::sameLanGoesStraight);
		check("ice/punches-race-over-a-bad-wire", Net_Ice_Checks::punchesRaceOverABadWire);
		check("ice/reconnecting-is-said", Net_Ice_Checks::reconnectingIsSaid);
		check("ice/strangers-get-no-answer", Net_Ice_Checks::strangersGetNoAnswer);
		check("relay/turn-codec-reads-the-rfc-5769-signed-vector", Net_Relay_Checks::codecReadsTheRfcSignedVector);
		check("relay/turn-codec-round-trips-and-refuses", Net_Relay_Checks::codecRoundTripsAndRefuses);
		check("relay/lobby-names-the-relay-with-a-minted-credential", Net_Relay_Checks::lobbyNamesTheRelay);
		check("relay/every-router-with-a-relay", Net_Relay_Checks::everyRouterWithARelay);
		check("relay/refused-credential-is-honest", Net_Relay_Checks::refusedCredentialIsHonest);
		check("relay/relayed-size-close-and-silence", Net_Relay_Checks::relayedSizeCloseAndSilence);
		check("relay/udp-takes-a-relayed-datagram", Net_Relay_Checks::udpTakesARelayedDatagram);

		System.out.println(failed == 0 ? "NET ok, " + checks + " checks" : "NET FAILED " + failed + " of " + checks + " check(s)");
		System.exit(failed == 0 ? 0 : 1);
	}

	// ---------------------------------------------------------------- UDP

	/** Every player binds port 0 : the mapping we use is the one our own outbound packet made. */
	static void udpEphemeralPort() throws Exception
	{
		try (Net_Transport a = Transport_Udp.open(); Net_Transport b = Transport_Udp.open())
		{
			is(a.localPort() > 0, "an ephemeral socket still has a port, got " + a.localPort());
			is(a.localPort() != b.localPort(), "two transports must not share a port");
		}
	}

	static void udpRoundTrip() throws Exception
	{
		try (Net_Transport host = Transport_Udp.open(); Net_Transport client = Transport_Udp.open())
		{
			Inbox atHost = new Inbox();
			Inbox atClient = new Inbox();

			client.send(client.resolve(local(host)), bytes("join"));
			await(host, atHost, () -> atHost.size() == 1, "the host never heard the client");
			eq("join", atHost.text(0), "the bytes the host read");

			// The host answers the address it learned from that packet - nobody told it in advance
			host.send(atHost.from(0), bytes("welcome"));
			await(client, atClient, () -> atClient.size() == 1, "the client never heard the answer");
			eq("welcome", atClient.text(0), "the bytes the client read");
			eq(local(host), atClient.from(0).address(), "the address the answer came from");
		}
	}

	/** A host with eight clients is the busiest session the plan sizes for (d5 -> A : one hero per machine). */
	static void udpHostLearnsPeers() throws Exception
	{
		Net_Transport host = Transport_Udp.open();
		List<Net_Transport> clients = new ArrayList<Net_Transport>();
		try
		{
			Inbox atHost = new Inbox();
			for (int i = 0; i < 8; i++)
			{
				Net_Transport client = Transport_Udp.open();
				clients.add(client);
				client.send(client.resolve(local(host)), bytes("hello " + i));
			}
			await(host, atHost, () -> atHost.size() == 8, "the host heard " + atHost.size() + " of 8 clients");

			Set<String> addresses = new LinkedHashSet<String>();
			for (int i = 0; i < atHost.size(); i++)
				addresses.add(atHost.from(i).address());
			eq(8, addresses.size(), "eight clients are eight distinct peers");

			// Broadcast : the same snapshot to everyone the host has heard from
			for (int i = 0; i < atHost.size(); i++)
				host.send(atHost.from(i), bytes("snapshot"));
			for (int i = 0; i < clients.size(); i++)
			{
				Inbox atClient = new Inbox();
				Net_Transport client = clients.get(i);
				await(client, atClient, () -> atClient.size() == 1, "client " + i + " missed the broadcast");
				eq("snapshot", atClient.text(0), "what client " + i + " got");
			}
		}
		finally
		{
			for (Net_Transport client : clients)
				client.close();
			host.close();
		}
	}

	/** Refused out loud : a payload past the MTU is a bug in the protocol, not something to discover in the wild. */
	static void udpPayloadCap() throws Exception
	{
		try (Net_Transport host = Transport_Udp.open(); Net_Transport client = Transport_Udp.open())
		{
			Net_Peer peer = client.resolve(local(host));
			client.send(peer, ByteBuffer.allocate(Net_Transport.MAX_PAYLOAD)); // legal, to the byte
			try
			{
				client.send(peer, ByteBuffer.allocate(Net_Transport.MAX_PAYLOAD + 1));
				is(false, "one byte past MAX_PAYLOAD was accepted");
			}
			catch (IllegalArgumentException expected)
			{
				is(expected.getMessage().contains("MAX_PAYLOAD"), "the refusal must name the limit");
			}
		}
	}

	/** Whole or not at all : 1200 bytes, every byte value, arriving identical. */
	static void udpWholeOrNothing() throws Exception
	{
		try (Net_Transport host = Transport_Udp.open(); Net_Transport client = Transport_Udp.open())
		{
			byte[] payload = new byte[Net_Transport.MAX_PAYLOAD];
			for (int i = 0; i < payload.length; i++)
				payload[i] = (byte) (i % 256);

			Inbox atHost = new Inbox();
			client.send(client.resolve(local(host)), ByteBuffer.wrap(payload));
			await(host, atHost, () -> atHost.size() == 1, "the biggest legal packet never arrived");

			byte[] got = atHost.bytes(0);
			eq(payload.length, got.length, "the length that arrived");
			for (int i = 0; i < payload.length; i++)
				if (got[i] != payload[i])
					is(false, "byte " + i + " changed on the wire");
		}
	}

	/** The loop pumps every frame : a pump that blocks is a frame that does not happen. */
	static void udpPumpNeverBlocks() throws Exception
	{
		try (Net_Transport host = Transport_Udp.open())
		{
			Inbox nothing = new Inbox();
			long start = System.nanoTime();
			for (int i = 0; i < 1000; i++)
				eq(0, host.pump(nothing), "an empty socket delivers nothing");
			long millis = (System.nanoTime() - start) / 1_000_000L;
			is(millis < 200, "1000 empty pumps took " + millis + " ms");
		}
	}

	/** A NAT mapping dies after about 30 s of silence, so a quiet peer is a gone peer - said once. */
	static void udpPeerTimeout() throws Exception
	{
		long[] clock = { 0 };
		try (Net_Transport host = Transport_Udp.open(0, () -> clock[0], 10_000);
				Net_Transport client = Transport_Udp.open())
		{
			Inbox atHost = new Inbox();
			client.send(client.resolve(local(host)), bytes("hello"));
			await(host, atHost, () -> atHost.size() == 1, "the host never heard the client");

			clock[0] = 9_999;
			host.pump(atHost);
			eq(0, atHost.lost.size(), "a peer quiet for less than the timeout is still here");

			// The keepalive a session sends every 5-15 s, and the whole reason it has to
			client.send(client.resolve(local(host)), bytes("still here"));
			await(host, atHost, () -> atHost.size() == 2, "the keepalive never arrived");

			clock[0] = 19_998;
			host.pump(atHost);
			eq(0, atHost.lost.size(), "a keepalive restarts the timeout, it does not shorten it");

			clock[0] = 20_000;
			host.pump(atHost);
			eq(1, atHost.lost.size(), "a peer quiet past the timeout is lost");

			clock[0] = 40_000;
			host.pump(atHost);
			eq(1, atHost.lost.size(), "a lost peer is reported once, then forgotten");
		}
	}

	/** IPv6 first is the connection order phase 2 will use : the transport has to be able to speak it at all. */
	static void udpIpv6() throws Exception
	{
		if (!hasIpv6())
		{
			System.out.println("NET skip udp/ipv6-loopback : no IPv6 on this machine");
			return;
		}
		try (Net_Transport host = Transport_Udp.open(); Net_Transport client = Transport_Udp.open())
		{
			Inbox atHost = new Inbox();
			client.send(client.resolve("[::1]:" + host.localPort()), bytes("v6"));
			await(host, atHost, () -> atHost.size() == 1, "nothing arrived over IPv6 loopback");
			eq("v6", atHost.text(0), "the bytes that arrived over IPv6");
			is(atHost.from(0).address().startsWith("["), "an IPv6 address is bracketed : " + atHost.from(0).address());
		}
	}

	// ---------------------------------------------------------------- the in-memory wire

	static void wireRoundTrip() throws Exception
	{
		Net_Loopback wire = new Net_Loopback();
		Net_Transport host = wire.open("host");
		Net_Transport client = wire.open("client");
		Inbox atHost = new Inbox(), atClient = new Inbox();

		client.send(client.resolve("host"), bytes("join"));
		eq(1, host.pump(atHost), "the wire delivered the packet");
		eq("join", atHost.text(0), "what the host read");

		host.send(atHost.from(0), bytes("welcome"));
		eq(1, client.pump(atClient), "the answer came back");
		eq("welcome", atClient.text(0), "what the client read");
		eq("host", atClient.from(0).address(), "who it says it is from");
	}

	/** Loss has to be reproducible, or a netcode bug that shows one run in five is unfixable. */
	static void wireLoss() throws Exception
	{
		int first = deliveredUnderLoss(42);
		int again = deliveredUnderLoss(42);
		int other = deliveredUnderLoss(7);

		eq(first, again, "the same seed drops the same packets");
		is(first > 400 && first < 600, "half of 1000 packets should get through, got " + first);
		is(first != other, "a different seed should drop a different set, both gave " + first);
	}

	static int deliveredUnderLoss(long seed)
	{
		Net_Loopback wire = new Net_Loopback(seed);
		wire.loss = 0.5f;
		Net_Transport host = wire.open("host");
		Net_Transport client = wire.open("client");
		Net_Peer peer = client.resolve("host");
		for (int i = 0; i < 1000; i++)
			client.send(peer, bytes("tick " + i));
		Inbox atHost = new Inbox();
		host.pump(atHost);
		return atHost.size();
	}

	/** UDP promises no order and no uniqueness : the protocol above must be tested against both. */
	static void wireDuplicatesAndReorder() throws Exception
	{
		Net_Loopback wire = new Net_Loopback(3);
		wire.duplicate = 1;
		Net_Transport host = wire.open("host");
		Net_Transport client = wire.open("client");
		Net_Peer peer = client.resolve("host");
		for (int i = 0; i < 10; i++)
			client.send(peer, bytes("tick " + i));
		Inbox atHost = new Inbox();
		host.pump(atHost);
		eq(20, atHost.size(), "every packet duplicated means twice as many arrive");

		Net_Loopback shuffled = new Net_Loopback(3);
		shuffled.reorder = 0.5f;
		Net_Transport far = shuffled.open("host");
		Net_Transport near = shuffled.open("client");
		Net_Peer there = near.resolve("host");
		for (int i = 0; i < 50; i++)
			near.send(there, bytes("" + i));
		Inbox jumbled = new Inbox();
		far.pump(jumbled);
		eq(50, jumbled.size(), "reordering must not lose anything");

		boolean outOfOrder = false;
		for (int i = 1; i < jumbled.size(); i++)
			if (Integer.parseInt(jumbled.text(i)) < Integer.parseInt(jumbled.text(i - 1)))
				outOfOrder = true;
		is(outOfOrder, "at 50% reorder, something should have arrived early");
	}

	static void wireSilentPeer() throws Exception
	{
		Net_Loopback wire = new Net_Loopback();
		Net_Transport host = wire.open("host");
		Net_Transport client = wire.open("client");
		Inbox atHost = new Inbox();

		client.send(client.resolve("host"), bytes("hello"));
		host.pump(atHost);
		eq(1, atHost.size(), "the host heard the client");

		wire.advance(5_000);
		host.pump(atHost);
		eq(0, atHost.lost.size(), "5 s of silence is a quiet player, not a gone one");

		wire.advance(6_000);
		host.pump(atHost);
		eq(1, atHost.lost.size(), "past the timeout the peer is lost");
		eq("client", atHost.lost.get(0).address(), "and it is named");

		wire.advance(11_000);
		host.pump(atHost);
		eq(1, atHost.lost.size(), "a lost peer is reported once, then forgotten");
	}

	/** Half a minute of a quiet game, kept alive by the packet every 5 s the session owes the peer. */
	static void wireKeepalive() throws Exception
	{
		Net_Loopback wire = new Net_Loopback();
		Net_Transport host = wire.open("host");
		Net_Transport client = wire.open("client");
		Net_Peer there = client.resolve("host");
		Inbox atHost = new Inbox();

		for (int i = 0; i < 6; i++)
		{
			client.send(there, bytes("keepalive"));
			host.pump(atHost);
			wire.advance(5_000);
		}
		host.pump(atHost);
		eq(6, atHost.size(), "every keepalive arrived");
		eq(0, atHost.lost.size(), "a peer that keeps talking is never lost");
	}

	/** The host quits : sending into the void is not an error, it is a peer that stops answering. */
	static void wireClosedEnd() throws Exception
	{
		Net_Loopback wire = new Net_Loopback();
		Net_Transport host = wire.open("host");
		Net_Transport client = wire.open("client");
		Inbox atClient = new Inbox();

		Net_Peer peer = client.resolve("host");
		client.send(peer, bytes("hello"));
		host.close();
		client.send(peer, bytes("anyone there"));

		eq(0, client.pump(atClient), "nothing comes back from a closed end");
		wire.advance(11_000);
		client.pump(atClient);
		eq(1, atClient.lost.size(), "the host that went away is reported lost");
	}

	// ---------------------------------------------------------------- plumbing

	/** Collects what a transport delivers, and is the listener at the same time. */
	static class Inbox implements Net_Listener
	{
		final List<Net_Peer> from = new ArrayList<Net_Peer>();
		final List<byte[]> payloads = new ArrayList<byte[]>();
		final List<Net_Peer> lost = new ArrayList<Net_Peer>();

		@Override
		public void received(Net_Peer peer, ByteBuffer payload)
		{
			byte[] copy = new byte[payload.remaining()];
			payload.get(copy);
			from.add(peer);
			payloads.add(copy);
		}

		@Override
		public void lost(Net_Peer peer)
		{
			lost.add(peer);
		}

		int size()
		{
			return payloads.size();
		}

		Net_Peer from(int i)
		{
			return from.get(i);
		}

		byte[] bytes(int i)
		{
			return payloads.get(i);
		}

		String text(int i)
		{
			return new String(payloads.get(i), StandardCharsets.UTF_8);
		}
	}

	/** Pumps until the condition holds or the deadline passes : loopback is fast, not instant. */
	static void await(Net_Transport transport, Net_Listener listener, BooleanSupplier until, String failure)
			throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + DEADLINE_MS;
		while (System.currentTimeMillis() < deadline)
		{
			transport.pump(listener);
			if (until.getAsBoolean())
				return;
			Thread.sleep(1);
		}
		is(false, failure + " (waited " + DEADLINE_MS + " ms)");
	}

	static ByteBuffer bytes(String text)
	{
		return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
	}

	static String local(Net_Transport transport)
	{
		return "127.0.0.1:" + transport.localPort();
	}

	static boolean hasIpv6()
	{
		try (DatagramSocket probe = new DatagramSocket(0, InetAddress.getByName("::1")))
		{
			return probe.getLocalAddress() instanceof Inet6Address;
		}
		catch (IOException e)
		{
			return false;
		}
	}

	interface Check
	{
		void run() throws Exception;
	}

	static void check(String name, Check check)
	{
		checks++;
		try
		{
			check.run();
			System.out.println("NET ok   " + name);
		}
		catch (Throwable t)
		{
			failed++;
			System.out.println("NET FAIL " + name);
			t.printStackTrace(System.out);
		}
	}

	static void is(boolean condition, String what)
	{
		if (!condition)
			throw new AssertionError(what);
	}

	static void eq(Object expected, Object actual, String what)
	{
		if (!expected.equals(actual))
			throw new AssertionError(what + " : expected " + expected + ", got " + actual);
	}

	static void eq(int expected, int actual, String what)
	{
		if (expected != actual)
			throw new AssertionError(what + " : expected " + expected + ", got " + actual);
	}
}
