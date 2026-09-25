package jks.smoke;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import jks.lobby.Lobby_Client;
import jks.net.Lobby_Codec;
import jks.net.Lobby_Message;
import jks.net.Net_Transport;
import jks.net.Transport_Udp;

/**
 * `./gradlew netwsonline -Plobby=ip:port -Pws=ip:port` : a tab's signalling against the REAL lobby service
 * (r79). A host on a UDP socket opens a lobby ; a tab - java.net.http's WebSocket, on the service's front -
 * lists it, joins it (its JOINED must name the relay), and offers a 3 kB description, which must reach the
 * host whole over UDP ; the host's 1.8 kB answer must reach the tab whole. Needs the internet ; the
 * defaults are VPS_1's service. It proves the deployed service speaks lobby version {@link Lobby_Codec#VERSION}
 * and that its TCP port is open from outside.
 */
public class Net_Tab_Online
{
	public static void main(String[] args) throws Exception
	{
		String service = args.length > 0 ? args[0] : "141.94.115.201:7770";
		String ws = args.length > 1 ? args[1] : "141.94.115.201:7771";
		long clock0 = System.nanoTime();
		java.util.function.LongSupplier clock = () -> (System.nanoTime() - clock0) / 1_000_000L;
		try (Net_Transport hostSocket = Transport_Udp.open())
		{
			Lobby_Client host = new Lobby_Client(hostSocket, service, clock);
			Runnable pump = host::pump;
			host.answerTabsByHand();
			host.host(4);
			say(until(pump, () -> host.code() != null, 5_000), "the service at " + service + " opened a lobby : " + host.code()
					+ (host.serviceOutdated() >= 0 ? " (the service speaks lobby version " + host.serviceOutdated() + ", this game " + Lobby_Codec.VERSION + ")" : ""));
			String code = host.code();

			Net_Tab_Checks.Tab tab = new Net_Tab_Checks.Tab();
			CompletableFuture<WebSocket> connecting = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(java.time.Duration.ofSeconds(5))
					.buildAsync(URI.create("ws://" + ws + "/"), tab);
			say(until(pump, connecting::isDone, 6_000) && !connecting.isCompletedExceptionally(), "the tab connected to ws://" + ws
					+ (connecting.isCompletedExceptionally() ? " : " + connecting.handle((s, e) -> e).get() : ""));
			tab.socket = connecting.get();

			tab.send(new Lobby_Message.Browse());
			Lobby_Message listing = next(pump, tab);
			say(listing instanceof Lobby_Message.Listing && ((Lobby_Message.Listing) listing).rows.stream().anyMatch(row -> row.code.equals(code)),
					"the tab's LISTING shows " + code + " : " + listing);

			Lobby_Message.Join join = new Lobby_Message.Join();
			join.code = code;
			tab.send(join);
			Lobby_Message joined = next(pump, tab);
			say(joined instanceof Lobby_Message.Joined && ((Lobby_Message.Joined) joined).relay != null, "the tab's JOINED names the relay "
					+ (joined instanceof Lobby_Message.Joined && ((Lobby_Message.Joined) joined).relay != null ? ((Lobby_Message.Joined) joined).relay.server
							+ ", and sees the tab as " + ((Lobby_Message.Joined) joined).you : "- got " + joined));

			String offer = Net_Tab_Checks.sdp("online", 3000);
			long offered = clock.getAsLong();
			tab.send(new Lobby_Message.Offer(code, offer));
			List<Lobby_Client.Call> calls = new ArrayList<Lobby_Client.Call>();
			say(until(pump, () -> calls.addAll(host.takeOffers()) || !calls.isEmpty(), 5_000) && calls.get(0).sdp.equals(offer),
					"the 3 kB offer reached the host whole over UDP, in " + (clock.getAsLong() - offered) + " ms");

			String answer = Net_Tab_Checks.sdp("online-host", 1800);
			long answered = clock.getAsLong();
			host.answer(calls.get(0).call, answer);
			Lobby_Message back = next(pump, tab);
			say(back instanceof Lobby_Message.Answer && ((Lobby_Message.Answer) back).sdp.equals(answer),
					"the 1.8 kB answer reached the tab whole, in " + (clock.getAsLong() - answered) + " ms");

			tab.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
			host.close();
			for (int i = 0; i < 5; i++)
				host.pump();
		}
		System.out.println("TAB ONLINE ok");
		System.exit(0);
	}

	static Lobby_Message next(Runnable pump, Net_Tab_Checks.Tab tab) throws Exception
	{
		long deadline = System.currentTimeMillis() + 5_000;
		while (System.currentTimeMillis() < deadline)
		{
			pump.run();
			Object got = tab.in.poll(2, TimeUnit.MILLISECONDS);
			if (got instanceof byte[])
				return Lobby_Codec.decode(ByteBuffer.wrap((byte[]) got));
			if (got != null)
				return null;
		}
		return null;
	}

	static boolean until(Runnable pump, BooleanSupplier done, long ms) throws InterruptedException
	{
		return Net_Online.until(pump, done, ms);
	}

	static void say(boolean ok, String what)
	{
		System.out.println((ok ? "TAB ONLINE ok   " : "TAB ONLINE FAIL ") + what);
		if (!ok)
			System.exit(1);
	}
}
