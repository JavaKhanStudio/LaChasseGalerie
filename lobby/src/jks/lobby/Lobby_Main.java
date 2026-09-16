package jks.lobby;

import java.security.SecureRandom;

import jks.net.Net_Peer;
import jks.net.Transport_Udp;

/**
 * The lobby service as a process : `lobby [port]`, UDP, default {@link #DEFAULT_PORT}. It is the one
 * thing in this project that binds a fixed port, because players must know where to find it
 * (Transport_Udp : "only a dedicated server passes a fixed port").
 *
 * It prints a line per lobby opened, closed and joined, and a count every minute. It runs until killed.
 */
public class Lobby_Main
{
	public static final int DEFAULT_PORT = 7770;
	/** Longer than the reap : the reap is what closes a quiet lobby, the transport only forgets a quiet peer. */
	static final long PEER_TIMEOUT_MS = Lobby_Service.REAP_MS + 5_000;

	public static void main(String[] args) throws Exception
	{
		int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
		try (Transport_Udp transport = Transport_Udp.open(port, () -> System.nanoTime() / 1_000_000L, PEER_TIMEOUT_MS))
		{
			Lobby_Service service = new Lobby_Service(transport, () -> System.nanoTime() / 1_000_000L, new SecureRandom());
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
							+ service.rejected + " rejected, " + service.throttled + " throttled");
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
