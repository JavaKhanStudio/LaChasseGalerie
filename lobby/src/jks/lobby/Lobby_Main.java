package jks.lobby;

import java.security.SecureRandom;

import jks.net.Net_Peer;
import jks.net.Transport_Udp;

/**
 * The lobby service as a process : `lobby [port [wsPort]]`, UDP on port (default {@link #DEFAULT_PORT}) and
 * the tabs' WebSocket front (r79) on TCP wsPort (default {@link #DEFAULT_WS_PORT}, or any free port when the
 * UDP port is 0, as the process gate asks). It is the one
 * thing in this project that binds a fixed port, because players must know where to find it
 * (Transport_Udp : "only a dedicated server passes a fixed port").
 *
 * The relay (r45) : with LOBBY_RELAY=ip:port and LOBBY_RELAY_SECRET in its environment, every HOSTED
 * and JOINED names that relay with a credential minted from the secret (coturn's static-auth-secret).
 * From the environment, not the command line, so the secret is not in `ps` for every user of the box.
 *
 * It prints a line per lobby opened, closed and joined, and a count every minute. It runs until killed.
 */
public class Lobby_Main
{
	public static final int DEFAULT_PORT = 7770;
	public static final int DEFAULT_WS_PORT = 7771;
	/** A tab's browser answers the front's pings by itself : three of them unanswered is a tab gone. */
	static final long WS_TIMEOUT_MS = 3 * Transport_Ws.PING_MS;
	/** Longer than the reap : the reap is what closes a quiet lobby, the transport only forgets a quiet peer. */
	static final long PEER_TIMEOUT_MS = Lobby_Service.REAP_MS + 5_000;

	public static void main(String[] args) throws Exception
	{
		int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
		int wsPort = args.length > 1 ? Integer.parseInt(args[1]) : port == 0 ? 0 : DEFAULT_WS_PORT;
		try (Transport_Udp transport = Transport_Udp.open(port, () -> System.nanoTime() / 1_000_000L, PEER_TIMEOUT_MS);
				Transport_Ws front = Transport_Ws.open(wsPort, () -> System.nanoTime() / 1_000_000L, WS_TIMEOUT_MS))
		{
			Lobby_Service service = new Lobby_Service(transport, () -> System.nanoTime() / 1_000_000L, new SecureRandom());
			service.front(front);
			service.events = new Lobby_Service.Events()
			{
				@Override
				public void opened(Lobby_Service.Lobby lobby)
				{
					log("OPENED " + lobby.code + " by " + lobby.host.address() + ", game v" + lobby.game + ", " + lobby.players + "/" + lobby.seats);
				}

				@Override
				public void closed(Lobby_Service.Lobby lobby, String why)
				{
					log("CLOSED " + lobby.code + " : " + why);
				}

				@Override
				public void joining(Lobby_Service.Lobby lobby, Net_Peer joiner)
				{
					log("JOINING " + lobby.code + " from " + joiner.address());
				}
			};
			String relay = System.getenv("LOBBY_RELAY"), secret = System.getenv("LOBBY_RELAY_SECRET");
			if (relay != null && !relay.isEmpty() && secret != null && !secret.isEmpty())
			{
				service.relay(relay, secret);
				log("RELAY " + relay);
			}
			else
				log("RELAY none : a pair no check gets through cannot play");
			log("WS " + front.localPort());
			log("PORT " + transport.localPort());

			long nextCount = System.currentTimeMillis() + 60_000;
			while (true)
			{
				// Nothing to read : a short sleep. A lobby answer 5 ms late is not something a person sees
				if (service.pump() == 0)
					Thread.sleep(5);
				if (System.currentTimeMillis() >= nextCount)
				{
					nextCount += 60_000;
					log("COUNT " + service.lobbies().size() + " lobbies, " + service.joins + " joins, " + service.refusals + " refused, "
							+ service.rejected + " rejected, " + service.throttled + " throttled, " + front.connections() + " tabs, "
							+ service.offers + " offers, " + service.answers + " answers");
				}
			}
		}
	}

	static void log(String line)
	{
		System.out.println(line);
		System.out.flush();
	}
}
