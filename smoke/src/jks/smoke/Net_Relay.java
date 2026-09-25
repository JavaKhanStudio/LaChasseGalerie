package jks.smoke;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import jks.net.Net_Listener;
import jks.net.Net_Peer;
import jks.net.Net_Transport;
import jks.net.Transport_Udp;
import jks.net.Turn_Client;
import jks.net.Turn_Codec;

/**
 * `./gradlew netrelay` : the game's relay client against a REAL relay, over the internet (r45). Two sockets
 * on this machine play joiner and host : the joiner allocates on the relay and lets this machine's public ip
 * through ; the host sends to the relayed address, the joiner answers through the relay, and the biggest
 * packets go both ways - a full 1200 B game payload comes back as a Data indication past MAX_PAYLOAD, which
 * is what Transport_Udp must take whole.
 *
 * The relay and a credential come from the environment - RELAY=ip:port RELAY_USER RELAY_PASS - so no
 * secret is ever in a command line : deploy/relay/netrelay.sh mints one on VPS_1 and runs this. Needs the
 * internet, so it is not in nettest.
 */
public class Net_Relay
{
	static final long WAIT_MS = 6_000;

	public static void main(String[] args) throws Exception
	{
		String server = System.getenv("RELAY"), user = System.getenv("RELAY_USER"), pass = System.getenv("RELAY_PASS");
		if (server == null || user == null || pass == null)
		{
			System.out.println("RELAY needs RELAY=ip:port, RELAY_USER and RELAY_PASS : deploy/relay/netrelay.sh mints them");
			System.exit(2);
		}
		boolean ok = true;
		try (Net_Transport joiner = Transport_Udp.open(); Net_Transport host = Transport_Udp.open())
		{
			Turn_Client turn = new Turn_Client(joiner, server, user, pass, () -> System.nanoTime() / 1_000_000L, new SecureRandom());
			List<Net_Peer> atJoinerFrom = new ArrayList<Net_Peer>();
			List<Integer> atJoiner = new ArrayList<Integer>();
			List<String> atHostFrom = new ArrayList<String>();
			List<Integer> atHost = new ArrayList<Integer>();
			Net_Listener relayed = (from, payload) ->
			{
				atJoinerFrom.add(from);
				atJoiner.add(payload.remaining());
			};
			Runnable pump = () ->
			{
				joiner.pump((from, payload) ->
				{
					if (turn.fromServer(from) && Turn_Codec.isTurnPacket(payload))
						turn.received(payload, relayed);
				});
				host.pump((from, payload) ->
				{
					atHostFrom.add(from.address());
					atHost.add(payload.remaining());
				});
				turn.update();
			};

			long started = System.nanoTime();
			turn.start();
			if (!until(pump, () -> turn.state() != Turn_Client.State.ALLOCATING, WAIT_MS) || turn.state() != Turn_Client.State.ALLOCATED)
			{
				System.out.println("RELAY FAIL no allocation from " + server + " : " + turn.state() + " " + turn.why());
				System.exit(1);
			}
			System.out.println("RELAY allocated " + turn.relayed() + " in " + ms(started) + " ms ; the relay sees this machine at " + turn.mapped());

			turn.permit(turn.mapped());
			until(pump, () -> false, 500); // the permission's answer

			// Host -> relayed address -> joiner, as the host's checks and snapshots go
			long sent = System.nanoTime();
			host.send(host.resolve(turn.relayed()), ByteBuffer.wrap("hello through the relay".getBytes()));
			ok &= check(until(pump, () -> !atJoiner.isEmpty(), WAIT_MS), "host -> relay -> joiner, " + (atJoiner.isEmpty() ? "nothing" : "from " + atJoinerFrom.get(0)));
			if (atJoiner.isEmpty())
				System.exit(1);
			Net_Peer hostThroughRelay = atJoinerFrom.get(0);

			// Joiner -> relay -> host, the biggest a relayed packet may be
			byte[] biggest = new byte[Turn_Codec.MAX_RELAYED];
			turn.send(hostThroughRelay, ByteBuffer.wrap(biggest));
			ok &= check(until(pump, () -> !atHost.isEmpty(), WAIT_MS) && atHost.get(0) == Turn_Codec.MAX_RELAYED && atHostFrom.get(0).equals(turn.relayed()),
					"joiner -> relay -> host, " + Turn_Codec.MAX_RELAYED + " B arrived as " + atHost + " from " + atHostFrom + " (round trip " + ms(sent) + " ms)");

			// A full game payload host -> joiner : on the last hop it is 1200 B plus the relay's framing
			atJoiner.clear();
			host.send(host.resolve(turn.relayed()), ByteBuffer.wrap(new byte[Net_Transport.MAX_PAYLOAD]));
			ok &= check(until(pump, () -> !atJoiner.isEmpty(), WAIT_MS) && atJoiner.get(0) == Net_Transport.MAX_PAYLOAD,
					"a full " + Net_Transport.MAX_PAYLOAD + " B payload reached the joiner whole through the relay : " + atJoiner);

			// Round trips, for the row's "a little more lag"
			long worst = 0, total = 0;
			for (int i = 0; i < 10; i++)
			{
				atHost.clear();
				long t = System.nanoTime();
				turn.send(hostThroughRelay, ByteBuffer.wrap(new byte[64]));
				until(pump, () -> !atHost.isEmpty(), 2_000);
				long rtt = ms(t);
				worst = Math.max(worst, rtt);
				total += rtt;
			}
			System.out.println("RELAY joiner -> relay -> host : " + total / 10 + " ms on average, " + worst + " ms at worst, over 10");
			turn.close();
		}
		System.out.println(ok ? "RELAY ok" : "RELAY FAILED");
		System.exit(ok ? 0 : 1);
	}

	static boolean until(Runnable pump, BooleanSupplier done, long ms) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + ms;
		while (!done.getAsBoolean() && System.currentTimeMillis() < deadline)
		{
			pump.run();
			Thread.sleep(1);
		}
		return done.getAsBoolean();
	}

	static boolean check(boolean ok, String what)
	{
		System.out.println((ok ? "RELAY ok   " : "RELAY FAIL ") + what);
		return ok;
	}

	static long ms(long since)
	{
		return (System.nanoTime() - since) / 1_000_000;
	}
}
