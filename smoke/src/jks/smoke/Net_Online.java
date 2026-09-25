package jks.smoke;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import jks.lobby.Lobby_Client;
import jks.lobby.Lobby_Ice;
import jks.net.Net_Listener;
import jks.net.Net_Peer;
import jks.net.Net_Transport;
import jks.net.Transport_Udp;
import jks.net.Turn_Client;

/**
 * `./gradlew netonline -Plobby=ip:port` : the online path against the REAL lobby service and relay (r45).
 * A host and a joiner on this machine, each on its own socket, find each other through the service by
 * code ; both are held to the relay ({@link Lobby_Ice#relayOnly}), because on one machine they would
 * otherwise meet on the LAN. It proves the service speaks lobby version 2, mints a credential the relay
 * takes, that the relayed address reaches the host through the service's PEER, and that both rows say
 * RELAYED with game packets crossing each way.
 *
 * What it does not prove : two households, and a phone hotspot's carrier NAT. That is r74, and a person.
 */
public class Net_Online
{
	public static void main(String[] args) throws Exception
	{
		String service = args.length > 0 ? args[0] : "141.94.115.201:7770";
		long clock0 = System.nanoTime();
		java.util.function.LongSupplier clock = () -> (System.nanoTime() - clock0) / 1_000_000L;
		try (Net_Transport hostSocket = Transport_Udp.open(); Net_Transport joinerSocket = Transport_Udp.open())
		{
			Lobby_Client host = new Lobby_Client(hostSocket, service, clock);
			Lobby_Client joiner = new Lobby_Client(joinerSocket, service, clock);
			// Both ends : a host that found the joiner on the LAN or over IPv6 stops checking, and never tries the relayed address
			joiner.ice().relayOnly = true;
			host.ice().relayOnly = true;
			List<String> atHost = new ArrayList<String>(), atJoiner = new ArrayList<String>();
			Runnable pump = () ->
			{
				host.game().pump(inbox(atHost));
				joiner.game().pump(inbox(atJoiner));
			};

			host.host(4);
			say(until(pump, () -> host.code() != null, 5_000), "the service at " + service + " opened a lobby : " + host.code()
					+ (host.serviceOutdated() >= 0 ? " (the service speaks lobby version " + host.serviceOutdated() + ")" : ""));
			joiner.join(host.code());
			say(until(pump, () -> joiner.joined() != null, 5_000), "JOINED, the host at " + (joiner.joined() == null ? null : joiner.joined().host));
			say(joiner.joined().relay != null, "JOINED names the relay " + (joiner.joined().relay == null ? null : joiner.joined().relay.server)
					+ " with a credential for " + (joiner.joined().relay == null ? null : joiner.joined().relay.username));
			Turn_Client relay = joiner.relay();
			say(until(pump, () -> relay.state() != Turn_Client.State.ALLOCATING, 5_000) && relay.state() == Turn_Client.State.ALLOCATED,
					"the relay took the credential : " + relay.state() + " " + relay.relayed() + (relay.why() == null ? "" : " " + relay.why()));

			long asked = clock.getAsLong();
			Lobby_Ice.Link row = joiner.ice().link(joiner.joined().host.get(0));
			say(until(pump, () -> row.usable() && host.ice().link(joiner.publicAddress()) != null && host.ice().link(joiner.publicAddress()).usable(), 10_000),
					"the joiner's row " + row + " ; the host's row " + host.ice().link(joiner.publicAddress()) + ", " + (clock.getAsLong() - asked) + " ms");
			say(row.route() == Lobby_Ice.Route.RELAYED && host.ice().link(joiner.publicAddress()).route() == Lobby_Ice.Route.RELAYED, "both rows say RELAYED");

			joiner.game().send(joiner.game().resolve(row.address()), ByteBuffer.wrap("hello host".getBytes(StandardCharsets.UTF_8)));
			say(until(pump, () -> !atHost.isEmpty(), 3_000), "a game packet joiner -> host through the relay : " + atHost);
			String from = atHost.get(0).substring(0, atHost.get(0).indexOf(' '));
			host.game().send(host.game().resolve(from), ByteBuffer.wrap("welcome".getBytes(StandardCharsets.UTF_8)));
			say(until(pump, () -> !atJoiner.isEmpty(), 3_000), "and host -> joiner : " + atJoiner);
			host.close();
			joiner.close();
		}
		System.out.println("ONLINE ok");
		System.exit(0);
	}

	static Net_Listener inbox(List<String> into)
	{
		return new Net_Listener()
		{
			@Override
			public void received(Net_Peer from, ByteBuffer payload)
			{
				into.add(from.address() + " " + StandardCharsets.UTF_8.decode(payload));
			}
		};
	}

	static boolean until(Runnable pump, BooleanSupplier done, long ms) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + ms;
		while (!done.getAsBoolean() && System.currentTimeMillis() < deadline)
		{
			pump.run();
			Thread.sleep(2);
		}
		return done.getAsBoolean();
	}

	static void say(boolean ok, String what)
	{
		System.out.println((ok ? "ONLINE ok   " : "ONLINE FAIL ") + what);
		if (!ok)
			System.exit(1);
	}
}
