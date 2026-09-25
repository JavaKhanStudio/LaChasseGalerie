package jks.smoke;

import static jks.smoke.Net_Run.eq;
import static jks.smoke.Net_Run.is;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import jks.lobby.Lobby_Client;
import jks.lobby.Lobby_Service;
import jks.lobby.Lobby_Tab;
import jks.lobby.Transport_Ws;
import jks.net.Lobby_Chunks;
import jks.net.Lobby_Codec;
import jks.net.Lobby_Message;
import jks.net.Net_Listener;
import jks.net.Net_Peer;
import jks.net.Net_Rejected;
import jks.net.Net_Transport;
import jks.net.Transport_Udp;

/**
 * nettest's checks for a tab's signalling (r79) : the lobby service's WebSocket front, and a WebRTC
 * description carried whole to a host and back in lobby packets. The tab is java.net.http's WebSocket
 * against a real Transport_Ws on loopback, the host a Lobby_Client on a real UDP socket ; the loss checks
 * run on the in-memory wire with a front that is only two queues.
 */
class Net_Tab_Checks
{
	/** An SDP-looking description of about this many bytes, told apart by its tag. */
	static String sdp(String tag, int bytes)
	{
		StringBuilder sdp = new StringBuilder("v=0\r\no=- " + tag + " 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n");
		for (int i = 0; sdp.length() < bytes; i++)
			sdp.append("a=candidate:").append(i).append(" 1 udp 2122260223 192.168.1.").append(i % 250).append(" 5").append(1000 + i)
					.append(" typ host generation 0 network-id 1\r\n");
		return sdp.substring(0, bytes);
	}

	// ---------------------------------------------------------------- the codec

	static void codecCarriesDescriptions() throws Exception
	{
		String big = sdp("big", 3000);
		Lobby_Message.Offer offer = (Lobby_Message.Offer) Net_Lobby_Checks.roundTrip(new Lobby_Message.Offer("ABC234", big));
		eq(big, offer.sdp, "OFFER sdp");
		eq("ABC234", offer.code, "OFFER code");
		is(Lobby_Codec.sizeOf(offer) > Net_Transport.MAX_PAYLOAD, "a 3 kB OFFER is past a packet, and encodes : it only goes over the WebSocket front");
		eq(big, ((Lobby_Message.Answer) Net_Lobby_Checks.roundTrip(new Lobby_Message.Answer("ABC234", big))).sdp, "ANSWER sdp");
		String most = sdp("most", Lobby_Codec.MAX_SDP);
		eq(Lobby_Codec.MAX_MESSAGE, Lobby_Codec.sizeOf(new Lobby_Message.Offer("ABC234", most)), "the biggest OFFER is MAX_MESSAGE");

		refuses(() -> Lobby_Codec.encode(new Lobby_Message.Offer("ABC234", most + "x")), "a description past MAX_SDP");
		refuses(() -> Lobby_Codec.encode(new Lobby_Message.Offer("ABC234", "v=0\r\ns=é\r\n")), "a description that is not ASCII");
		refuses(() -> Lobby_Codec.encode(new Lobby_Message.Offer("ABC234", "")), "an empty description");

		// Every part of the biggest description fits a packet, even one relayed
		List<Lobby_Message.Part> parts = Lobby_Chunks.split(Lobby_Message.Type.OFFER_PART, "ABC234", 65535, most);
		eq(Lobby_Codec.MAX_PARTS, parts.size(), "the biggest description's parts");
		for (Lobby_Message.Part part : parts)
		{
			is(Lobby_Codec.sizeOf(part) <= jks.net.Turn_Codec.MAX_RELAYED, "a part is " + Lobby_Codec.sizeOf(part) + " B, past what a relay carries");
			Lobby_Message.Part back = (Lobby_Message.Part) Net_Lobby_Checks.roundTrip(part);
			eq(part.part, back.part, "PART index");
			eq(65535, back.call, "PART call");
			is(java.util.Arrays.equals(part.bytes, back.bytes), "PART bytes");
			eq(Lobby_Message.Type.OFFER_PART, back.type(), "PART kind");
		}

		// Every truncation and every bit flip of a part is refused whole
		ByteBuffer packet = Lobby_Codec.encode(parts.get(1));
		byte[] bytes = new byte[packet.remaining()];
		packet.get(bytes);
		for (int length = 0; length < bytes.length; length++)
			rejected(ByteBuffer.wrap(bytes, 0, length), "a part cut to " + length + " B");
		for (int bit = 0; bit < bytes.length * 8; bit++)
		{
			byte[] flipped = bytes.clone();
			flipped[bit / 8] ^= 1 << (bit % 8);
			if (flipped[0] == 'L')
				rejected(ByteBuffer.wrap(flipped), "a part with bit " + bit + " flipped");
		}

		// Cut and put back : in any order, twice over, the description comes back once and whole
		Lobby_Chunks chunks = new Lobby_Chunks();
		List<Lobby_Message.Part> shuffled = new ArrayList<Lobby_Message.Part>(parts);
		shuffled.addAll(parts);
		Collections.shuffle(shuffled, new Random(3));
		List<String> whole = new ArrayList<String>();
		for (Lobby_Message.Part part : shuffled)
		{
			String sdp = chunks.add("service", part, 0);
			if (sdp != null)
				whole.add(sdp);
		}
		eq(Collections.singletonList(most), whole, "a shuffled, doubled description, put back together");
	}

	/** A part missing fails its description whole ; parts that disagree, or from someone else, never complete one. */
	static void lostPartFailsWhole() throws Exception
	{
		String sdp = sdp("lost", 2500);
		List<Lobby_Message.Part> parts = Lobby_Chunks.split(Lobby_Message.Type.ANSWER_PART, "ABC234", 7, sdp);
		eq(3, parts.size(), "2.5 kB in parts");

		Lobby_Chunks chunks = new Lobby_Chunks();
		is(chunks.add("host", parts.get(0), 0) == null && chunks.add("host", parts.get(2), 10) == null, "two parts of three are not a description");
		eq(1, chunks.pending(), "one call waiting for its part");
		chunks.expire(Lobby_Chunks.TIMEOUT_MS);
		eq(0, chunks.pending(), "the call with a part missing is gone after TIMEOUT_MS");
		eq(1, chunks.failed, "and counted as failed");
		is(chunks.add("host", parts.get(1), Lobby_Chunks.TIMEOUT_MS + 1) == null, "the late part alone does not bring it back");

		// The same call number from another sender is another call : a stranger cannot finish mine
		Lobby_Chunks mixed = new Lobby_Chunks();
		mixed.add("host", parts.get(0), 0);
		mixed.add("host", parts.get(1), 0);
		is(mixed.add("stranger", parts.get(2), 0) == null, "a stranger's part completed someone else's description");

		// Parts that disagree on how many there are : the call is dropped, not guessed at
		Lobby_Chunks liar = new Lobby_Chunks();
		liar.add("host", parts.get(0), 0);
		Lobby_Message.Part odd = Lobby_Chunks.split(Lobby_Message.Type.ANSWER_PART, "ABC234", 7, sdp("lost", 1500)).get(1);
		is(liar.add("host", odd, 0) == null, "a part of 2 in a call of 3");
		eq(1, liar.failed, "parts that disagree fail their call");
		is(liar.add("host", parts.get(1), 0) == null && liar.add("host", parts.get(2), 0) == null, "a failed call stays failed");
	}

	// ---------------------------------------------------------------- over the in-memory wire

	/** The tabs' front as two queues : "ws/tab" is a tab, nothing is lost or cut between it and the service. */
	static final class Queue_Front implements Net_Transport
	{
		final Net_Peer tab = () -> "ws/tab";
		final Deque<byte[]> toService = new ArrayDeque<byte[]>();
		final List<Lobby_Message> toTab = new ArrayList<Lobby_Message>();

		void send(Lobby_Message message)
		{
			ByteBuffer packet = Lobby_Codec.encode(message);
			byte[] bytes = new byte[packet.remaining()];
			packet.get(bytes);
			toService.add(bytes);
		}

		@Override
		public Net_Peer resolve(String address)
		{
			return tab;
		}

		@Override
		public void send(Net_Peer peer, ByteBuffer payload)
		{
			try
			{
				toTab.add(Lobby_Codec.decode(payload));
			}
			catch (Net_Rejected e)
			{
				throw new AssertionError("the service sent a tab what it cannot read : " + e);
			}
		}

		@Override
		public int pump(Net_Listener listener)
		{
			int delivered = 0;
			while (!toService.isEmpty())
			{
				listener.received(tab, ByteBuffer.wrap(toService.poll()));
				delivered++;
			}
			return delivered;
		}

		@Override
		public int localPort()
		{
			return 0;
		}

		@Override
		public int dropped()
		{
			return 0;
		}

		@Override
		public void close()
		{
		}
	}

	/**
	 * Forty offers to a host over a wire that loses, doubles and reorders : every one the host takes is
	 * exactly one the tab made, none twice, and every one it does not is counted failed or never began.
	 * Then the host answers each, and each answer reaches the tab whole.
	 */
	static void offersOverABadWire() throws Exception
	{
		Net_Lobby_Checks.Rig rig = new Net_Lobby_Checks.Rig(79);
		Queue_Front front = new Queue_Front();
		rig.service.front(front);
		Lobby_Client host = rig.client("host");
		host.host(8);
		rig.until(() -> host.code() != null, 5000, "no code");
		rig.wire.loss = 0.15f;
		rig.wire.duplicate = 0.1f;
		rig.wire.reorder = 0.2f;

		Set<String> made = new HashSet<String>();
		List<Lobby_Client.Call> taken = new ArrayList<Lobby_Client.Call>();
		int offers = 40;
		for (int i = 0; i < offers; i++)
		{
			String sdp = sdp("offer" + i, 2200 + i * 7);
			made.add(sdp);
			front.send(new Lobby_Message.Offer(host.code(), sdp));
			// Three parts every 250 ms : well inside what the service answers one address in a second
			rig.step(250);
			taken.addAll(host.takeOffers());
		}
		rig.run(Lobby_Chunks.TIMEOUT_MS + 1000, 250);
		taken.addAll(host.takeOffers());

		Set<Integer> calls = new HashSet<Integer>();
		for (Lobby_Client.Call call : taken)
		{
			is(made.contains(call.sdp), "the host took a description no tab made : " + call.sdp.length() + " B");
			is(calls.add(call.call), "call " + call.call + " taken twice");
		}
		int failed = host.offerParts().failed;
		is(taken.size() > 0 && failed > 0, "15% loss on 3-part offers : " + taken.size() + " whole and " + failed + " failed");
		is(taken.size() + failed <= offers, taken.size() + " whole + " + failed + " failed of " + offers + " offers");
		eq(0, host.offerParts().pending(), "no offer is left half put together");
		eq(offers, rig.service.offers, "offers the service passed on");

		rig.wire.loss = 0;
		rig.wire.duplicate = 0;
		rig.wire.reorder = 0;
		front.toTab.clear();
		for (Lobby_Client.Call call : taken)
		{
			host.answer(call.call, sdp("answer" + call.call, 1500));
			rig.step(250);
		}
		rig.step(250);
		Set<String> answered = new HashSet<String>();
		for (Lobby_Message message : front.toTab)
			if (message instanceof Lobby_Message.Answer)
				answered.add(((Lobby_Message.Answer) message).sdp);
		eq(taken.size(), answered.size(), "answers the tab got, each whole");
		for (Lobby_Client.Call call : taken)
			is(answered.contains(sdp("answer" + call.call, 1500)), "no answer for call " + call.call);

		// An answer to a call that is over, or from somebody who is not the host, goes nowhere
		int before = front.toTab.size();
		Lobby_Client stranger = rig.client("stranger");
		stranger.host(8);
		rig.until(() -> stranger.code() != null, 5000, "no code for the stranger");
		stranger.answer(taken.get(0).call, sdp("forged", 900));
		host.answer(taken.get(0).call, sdp("again", 900));
		rig.step(250);
		eq(before, front.toTab.size(), "messages to the tab after a stranger's and a finished call's answers");
	}

	/**
	 * A host's tabs with no libwebrtc behind them (r80) : an offer containing "unparsable" fails, any other
	 * is answered at the next pump ; {@link #toHost} is what the tabs sent, delivered at the next pump, and
	 * {@link #sent} what the host sent them. Lets the in-memory wire prove Lobby_Client's routing.
	 */
	static final class Fake_Tabs implements jks.net.Net_Tabs
	{
		final class Fake implements jks.net.Net_Tabs.Tab
		{
			final Net_Peer peer;
			final String offer;
			String sdp, failure;

			Fake(String offer)
			{
				String address = "rtc/" + (calls.size() + 1);
				this.peer = () -> address;
				this.offer = offer;
			}

			@Override
			public Net_Peer peer()
			{
				return peer;
			}

			@Override
			public String sdp()
			{
				return sdp;
			}

			@Override
			public String failure()
			{
				return failure;
			}

			@Override
			public boolean open()
			{
				return sdp != null && failure == null;
			}
		}

		final List<Fake> calls = new ArrayList<Fake>();
		final Deque<Object[]> toHost = new ArrayDeque<Object[]>();
		final List<Object[]> sent = new ArrayList<Object[]>();
		boolean closed;

		@Override
		public Tab answer(String offer)
		{
			Fake call = new Fake(offer);
			calls.add(call);
			return call;
		}

		@Override
		public Net_Peer resolve(String address)
		{
			for (Fake call : calls)
				if (call.peer.address().equals(address))
					return call.peer;
			return () -> address;
		}

		@Override
		public void send(Net_Peer peer, ByteBuffer payload)
		{
			byte[] bytes = new byte[payload.remaining()];
			payload.get(bytes);
			sent.add(new Object[] { peer.address(), bytes });
		}

		@Override
		public int pump(Net_Listener listener)
		{
			for (Fake call : calls)
				if (call.sdp == null && call.failure == null)
				{
					if (call.offer.contains("unparsable"))
					{
						call.failure = "the offer : unparsable";
						toHost.add(new Object[] { call.peer, null });
					}
					else
						call.sdp = sdp("answer-" + call.peer.address(), 900);
				}
			int delivered = 0;
			while (!toHost.isEmpty())
			{
				Object[] event = toHost.poll();
				if (event[1] == null)
					listener.lost((Net_Peer) event[0]);
				else
				{
					listener.received((Net_Peer) event[0], ByteBuffer.wrap((byte[]) event[1]));
					delivered++;
				}
			}
			return delivered;
		}

		@Override
		public int localPort()
		{
			return 0;
		}

		@Override
		public int dropped()
		{
			return 0;
		}

		@Override
		public void close()
		{
			closed = true;
		}
	}

	/**
	 * A host with no tabs (its WebRTC did not load) keeps at most HELD_OFFERS offers and answers none, and
	 * its UDP players are as before. With tabs plugged, it answers each offer itself, once, sends nothing
	 * for one that fails, and its game view carries the tab's peer : held before a session pumps, delivered
	 * after, sent to by its name, and lost - row and all - when the tab goes.
	 */
	static void hostAnswersTabsOnlyWithTabs() throws Exception
	{
		Net_Lobby_Checks.Rig rig = new Net_Lobby_Checks.Rig(80);
		Queue_Front front = new Queue_Front();
		rig.service.front(front);
		Lobby_Client host = rig.client("host");
		host.host(8);
		rig.until(() -> host.code() != null, 5000, "no code");

		is(!host.takesTabs(), "a host takes tabs before any is plugged");
		for (int i = 0; i < Lobby_Client.HELD_OFFERS + 4; i++)
		{
			front.send(new Lobby_Message.Offer(host.code(), sdp("unheard" + i, 1500)));
			rig.step(250);
		}
		rig.step(250);
		eq(0, answers(front), "answers from a host with no tabs");
		eq(4, host.offersRefused, "offers past HELD_OFFERS, dropped");
		List<Lobby_Client.Call> kept = host.takeOffers();
		eq(Lobby_Client.HELD_OFFERS, kept.size(), "offers kept for takeOffers");
		eq(sdp("unheard4", 1500), kept.get(0).sdp, "the oldest went first");

		// A UDP joiner is let through as ever
		Lobby_Client joiner = rig.client("joiner");
		joiner.join(host.code());
		rig.until(() -> joiner.joined() != null, 5000, "a UDP joiner beside refused tabs was not joined");

		Fake_Tabs tabs = new Fake_Tabs();
		host.tabs(tabs);
		is(host.takesTabs(), "a host with tabs plugged takes them");
		front.toTab.clear();
		front.send(new Lobby_Message.Offer(host.code(), sdp("tab", 2500)));
		front.send(new Lobby_Message.Offer(host.code(), "v=0\r\nunparsable\r\n"));
		rig.step(250);
		rig.step(250);
		eq(2, tabs.calls.size(), "offers the host handed its tabs");
		eq(1, answers(front), "answers the tab got : the one that parsed, never the failed one");
		eq(sdp("answer-rtc/1", 900), ((Lobby_Message.Answer) front.toTab.get(front.toTab.size() - 1)).sdp, "the answer is the tabs' own description");
		rig.run(2000, 250);
		eq(1, answers(front), "an answer is sent once");
		eq(1, host.tabRows().size(), "rows : the tab that is connecting, not the one that failed");
		eq("rtc/1", host.tabRows().get(0).tab.peer().address(), "the row's peer");
		is(host.tabRows().get(0).answered(), "the row says its answer went");
		eq(4 + 1, host.offersRefused, "the failed offer counted refused");
		is(host.takeOffers().isEmpty(), "an offer answered by the tabs is not kept for takeOffers too");

		// The tab's packets : held while nobody pumps the game, then delivered from rtc/1
		Net_Peer tabPeer = tabs.calls.get(0).peer;
		tabs.toHost.add(new Object[] { tabPeer, new byte[] { 1, 2, 3 } });
		rig.step(16);
		Net_Run.Inbox game = new Net_Run.Inbox();
		host.game().pump(game);
		// The failed call's loss was held too, before the tab's packet
		eq(1, game.size(), "the tab's packet, held then delivered");
		eq("rtc/1", game.from(0).address(), "from the tab's peer");
		is(java.util.Arrays.equals(new byte[] { 1, 2, 3 }, game.payloads.get(0)), "whole");
		eq(Collections.singletonList("rtc/2"), addresses(game.lost), "the failed call's peer, told lost to the game");

		// Sent to by its name, through the tabs and never the socket
		host.game().send(host.game().resolve("rtc/1"), ByteBuffer.wrap(new byte[] { 9 }));
		eq(1, tabs.sent.size(), "a packet to rtc/1 went through the tabs");
		eq("rtc/1", tabs.sent.get(0)[0], "to the tab");

		// The tab goes : lost to the game, and its row with it
		tabs.toHost.add(new Object[] { tabPeer, null });
		game = new Net_Run.Inbox();
		host.game().pump(game);
		eq(Collections.singletonList("rtc/1"), addresses(game.lost), "the tab that went, told lost");
		is(host.tabRows().isEmpty(), "and its row gone");

		// Closing the lobby closes the tabs : they are the client's
		host.close();
		is(tabs.closed && !host.takesTabs(), "the tabs closed with the lobby");
	}

	static int answers(Queue_Front front)
	{
		int answers = 0;
		for (Lobby_Message message : front.toTab)
			if (message instanceof Lobby_Message.Answer)
				answers++;
		return answers;
	}

	static List<String> addresses(List<Net_Peer> peers)
	{
		List<String> addresses = new ArrayList<String>();
		for (Net_Peer peer : peers)
			addresses.add(peer.address());
		return addresses;
	}

	// ---------------------------------------------------------------- over a real WebSocket

	/** A browser tab, as java.net.http sees one : messages whole into a queue, closes as their status code. */
	static final class Tab implements WebSocket.Listener
	{
		final BlockingQueue<Object> in = new LinkedBlockingQueue<Object>();
		final ByteArrayOutputStream partial = new ByteArrayOutputStream();
		WebSocket socket;

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last)
		{
			byte[] bytes = new byte[data.remaining()];
			data.get(bytes);
			partial.write(bytes, 0, bytes.length);
			if (last)
			{
				in.add(partial.toByteArray());
				partial.reset();
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
		{
			in.add(Integer.valueOf(statusCode));
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error)
		{
			in.add(error);
		}

		void send(Lobby_Message message)
		{
			socket.sendBinary(Lobby_Codec.encode(message), true).join();
		}
	}

	/** A service on loopback with both fronts, a host on its own UDP socket, and the loop that pumps them. */
	static final class Live implements AutoCloseable
	{
		final Transport_Udp serviceEnd = Transport_Udp.open(0);
		final Transport_Ws front = Transport_Ws.open(0, () -> System.nanoTime() / 1_000_000L, 30_000);
		final Lobby_Service service = new Lobby_Service(serviceEnd, () -> System.nanoTime() / 1_000_000L, new Random(79));
		final Transport_Udp hostEnd = Transport_Udp.open();
		final Lobby_Client host = new Lobby_Client(hostEnd, "127.0.0.1:" + serviceEnd.localPort(), () -> System.nanoTime() / 1_000_000L);
		final List<Tab> tabs = new ArrayList<Tab>();

		Live()
		{
			service.front(front);
			service.relay("203.0.113.1:3478", "not-the-real-secret");
		}

		Tab tab() throws Exception
		{
			Tab tab = new Tab();
			java.util.concurrent.CompletableFuture<WebSocket> connecting = HttpClient.newHttpClient().newWebSocketBuilder()
					.buildAsync(URI.create("ws://127.0.0.1:" + front.localPort() + "/"), tab);
			// The upgrade is answered by the service's loop : pump it while the tab waits
			until(connecting::isDone, 5000, "the tab's upgrade was not answered");
			tab.socket = connecting.get();
			tabs.add(tab);
			return tab;
		}

		void pump()
		{
			service.pump();
			host.pump();
		}

		void until(BooleanSupplier condition, long millis, String failure) throws InterruptedException
		{
			long end = System.nanoTime() + millis * 1_000_000L;
			while (!condition.getAsBoolean() && System.nanoTime() < end)
			{
				pump();
				Thread.sleep(2);
			}
			is(condition.getAsBoolean(), failure);
		}

		/** The next lobby message the tab gets, pumping while it waits. */
		Lobby_Message next(Tab tab, long millis) throws Exception
		{
			long end = System.nanoTime() + millis * 1_000_000L;
			while (System.nanoTime() < end)
			{
				pump();
				Object got = tab.in.poll(2, TimeUnit.MILLISECONDS);
				if (got instanceof byte[])
					return Lobby_Codec.decode(ByteBuffer.wrap((byte[]) got));
				if (got != null)
					throw new AssertionError("the tab got " + got + " where a message was due");
			}
			throw new AssertionError("the tab heard nothing in " + millis + " ms");
		}

		@Override
		public void close()
		{
			for (Tab tab : tabs)
				tab.socket.abort();
			host.close();
			hostEnd.close();
			front.close();
			serviceEnd.close();
		}
	}

	/**
	 * A tab on the WebSocket front browses and joins like a UDP player, with the relay in its JOINED and
	 * no PEER for the host ; its 3 kB OFFER reaches the host whole over UDP, and the host's answer reaches
	 * the tab whole. A UDP joiner beside it still gets the punch.
	 */
	static void tabSignalsOverTheWebSocketFront() throws Exception
	{
		try (Live live = new Live())
		{
			live.host.host(8);
			live.until(() -> live.host.code() != null, 3000, "the host got no code");
			String code = live.host.code();

			Tab tab = live.tab();
			tab.send(new Lobby_Message.Browse());
			Lobby_Message.Listing listing = (Lobby_Message.Listing) live.next(tab, 3000);
			eq(Collections.singletonList(new Lobby_Message.Row(code, 0, 8)), listing.rows, "the tab's LISTING");

			Lobby_Message.Join join = new Lobby_Message.Join();
			join.code = code;
			tab.send(join);
			Lobby_Message.Joined joined = (Lobby_Message.Joined) live.next(tab, 3000);
			eq(code, joined.code, "JOINED code");
			is(joined.you.startsWith(Transport_Ws.PREFIX), "a tab is seen as a ws/ peer : " + joined.you);
			is(joined.relay != null && joined.relay.server.equals("203.0.113.1:3478"), "the tab's JOINED names the relay : its TURN server");
			live.until(() -> live.service.joins == 1, 1000, "the join was not counted");
			for (int i = 0; i < 20; i++)
				live.pump();
			is(live.host.takeJoiners().isEmpty(), "a tab's JOIN mirrored a PEER to the host : nobody can punch to a tab");

			String offer = sdp("tab", 3000);
			tab.send(new Lobby_Message.Offer(code, offer));
			List<Lobby_Client.Call> calls = new ArrayList<Lobby_Client.Call>();
			live.until(() -> calls.addAll(live.host.takeOffers()) || !calls.isEmpty(), 3000, "the host never got the 3 kB offer whole");
			eq(1, calls.size(), "offers the host took");
			eq(offer, calls.get(0).sdp, "the offer the host took");

			String answer = sdp("host", 1800);
			live.host.answer(calls.get(0).call, answer);
			Lobby_Message.Answer back = (Lobby_Message.Answer) live.next(tab, 3000);
			eq(code, back.code, "ANSWER code");
			eq(answer, back.sdp, "the answer the tab got");

			// An offer for a lobby that is not there is refused, like a join
			tab.send(new Lobby_Message.Offer("ZZZZZZ", offer));
			Lobby_Message.Refused no = (Lobby_Message.Refused) live.next(tab, 3000);
			eq(Lobby_Message.Refused.Reason.NO_SUCH_LOBBY, no.reason, "an offer to no lobby");

			// The UDP front is as it was : a UDP joiner is mirrored to the host
			try (Transport_Udp joinerEnd = Transport_Udp.open())
			{
				Lobby_Client joiner = new Lobby_Client(joinerEnd, "127.0.0.1:" + live.serviceEnd.localPort(), () -> System.nanoTime() / 1_000_000L);
				joiner.join(code);
				List<List<String>> mirrored = new ArrayList<List<String>>();
				live.until(() ->
				{
					joiner.pump();
					mirrored.addAll(live.host.takeJoiners());
					return joiner.joined() != null && !mirrored.isEmpty();
				}, 3000, "a UDP joiner beside the tab was not joined and mirrored");
				eq("127.0.0.1:" + joinerEnd.localPort(), mirrored.get(0).get(0), "the UDP joiner as the service saw it");
				joiner.close();
			}

			// The tab goes : its connection is closed and forgotten
			eq(1, live.front.connections(), "tabs connected");
			tab.socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
			live.until(() -> live.front.connections() == 0, 3000, "a tab that closed is still connected");
		}
	}

	/** java.net.http's WebSocket as a Net_Transport, as html's Transport_WebSocket is a browser's : one peer, the service. */
	static final class Ws_Transport implements Net_Transport
	{
		final Tab tab;
		final Net_Peer service = () -> "service";

		Ws_Transport(Tab tab)
		{
			this.tab = tab;
		}

		@Override
		public Net_Peer resolve(String address)
		{
			return service;
		}

		@Override
		public void send(Net_Peer peer, ByteBuffer payload)
		{
			tab.socket.sendBinary(payload, true).join();
		}

		@Override
		public int pump(Net_Listener listener)
		{
			int delivered = 0;
			for (Object got; (got = tab.in.poll()) != null;)
				if (got instanceof byte[])
				{
					listener.received(service, ByteBuffer.wrap((byte[]) got));
					delivered++;
				}
				else
					listener.lost(service);
			return delivered;
		}

		@Override
		public int localPort()
		{
			return 0;
		}

		@Override
		public int dropped()
		{
			return 0;
		}

		@Override
		public void close()
		{
			tab.socket.abort();
		}
	}

	/** A tab's end of a call with no libwebrtc : described at once, open once answered with a description. */
	static final class Fake_Offerer implements Lobby_Tab.Offerer
	{
		final List<Lobby_Message.Relay> relays = new ArrayList<Lobby_Message.Relay>();
		int calls;

		@Override
		public Lobby_Tab.Call offer(Lobby_Message.Relay relay)
		{
			relays.add(relay);
			String address = "rtc/" + ++calls;
			return new Lobby_Tab.Call()
			{
				String answer;

				@Override
				public Net_Peer peer()
				{
					return () -> address;
				}

				@Override
				public String sdp()
				{
					return Net_Tab_Checks.sdp("tab-" + address, 1100);
				}

				@Override
				public String failure()
				{
					return answer != null && !answer.startsWith("v=0") ? "the answer : unparsable" : null;
				}

				@Override
				public boolean open()
				{
					return answer != null && failure() == null;
				}

				@Override
				public void answered(String sdp)
				{
					answer = sdp;
				}
			};
		}

		@Override
		public Net_Peer resolve(String address)
		{
			return () -> address;
		}

		@Override
		public void send(Net_Peer peer, ByteBuffer payload)
		{
		}

		@Override
		public int pump(Net_Listener listener)
		{
			return 0;
		}

		@Override
		public int localPort()
		{
			return 0;
		}

		@Override
		public int dropped()
		{
			return 0;
		}

		@Override
		public void close()
		{
		}
	}

	/**
	 * A browser tab's lobby (r82), the Lobby_Tab html's Vue_Lobby plays on, over a real WebSocket front : it
	 * lists the host's lobby, is refused a code nobody hosts, joins, offers through the relay its JOINED
	 * named, and its channel opens with the host's answer ; a host that cannot take tabs never answers, and
	 * the tab gives up after ANSWER_MS and says so ; the service going is seen.
	 */
	static void tabLobbyJoinsAndOffers() throws Exception
	{
		try (Live live = new Live())
		{
			live.host.host(8);
			live.until(() -> live.host.code() != null, 3000, "the host got no code");
			String code = live.host.code();
			Fake_Tabs tabs = new Fake_Tabs();
			live.host.tabs(tabs);

			long[] skew = { 0 };
			Fake_Offerer offerer = new Fake_Offerer();
			Lobby_Tab lobby = new Lobby_Tab(new Ws_Transport(live.tab()), "127.0.0.1:" + live.front.localPort(), offerer,
					() -> System.nanoTime() / 1_000_000L + skew[0]);
			lobby.browse();
			live.until(() -> { lobby.pump(); return lobby.listing() != null; }, 3000, "the tab's lobby was never listed");
			eq(Collections.singletonList(new Lobby_Message.Row(code, 0, 8)), lobby.listing().rows, "the tab's listing");

			lobby.join("ZZZZZZ");
			live.until(() -> { lobby.pump(); return lobby.state() == Lobby_Tab.State.FAILED; }, 3000, "a code nobody hosts was not refused");
			eq(Lobby_Message.Refused.Reason.NO_SUCH_LOBBY, lobby.refused().reason, "the refusal");
			eq(0, offerer.calls, "calls made for a refused code");

			lobby.join(code);
			eq(Lobby_Tab.State.JOINING, lobby.state(), "a new join clears the refusal");
			is(lobby.refused() == null && lobby.failure() == null, "a new join still says the last one's no");
			live.until(() -> { lobby.pump(); return lobby.state() == Lobby_Tab.State.OPEN; }, 3000, "the tab's channel never opened : " + lobby.state() + " " + lobby.failure());
			eq(1, offerer.calls, "calls made");
			is(offerer.relays.get(0) != null && offerer.relays.get(0).server.equals("203.0.113.1:3478"), "the call's TURN server is the relay JOINED named");
			eq("rtc/1", lobby.peer().address(), "the peer the session plays on");
			eq(1, tabs.calls.size(), "offers the host answered");
			eq(sdp("tab-rtc/1", 1100), tabs.calls.get(0).offer, "the offer the host answered, whole");

			// A host with no WebRTC : its lobby holds the offer and answers nothing
			try (Live bare = new Live())
			{
				bare.host.host(8);
				bare.until(() -> bare.host.code() != null, 3000, "the second host got no code");
				Lobby_Tab unanswered = new Lobby_Tab(new Ws_Transport(bare.tab()), "127.0.0.1:" + bare.front.localPort(), new Fake_Offerer(),
						() -> System.nanoTime() / 1_000_000L + skew[0]);
				unanswered.join(bare.host.code());
				List<Lobby_Client.Call> held = new ArrayList<Lobby_Client.Call>();
				bare.until(() -> { unanswered.pump(); held.addAll(bare.host.takeOffers()); return !held.isEmpty(); }, 3000,
						"the offer never reached the host with no WebRTC : " + unanswered.state() + " " + unanswered.failure());
				for (int i = 0; i < 20; i++)
				{
					bare.pump();
					unanswered.pump();
				}
				eq(Lobby_Tab.State.OFFERING, unanswered.state(), "a tab waiting on a host with no WebRTC");
				skew[0] += Lobby_Tab.ANSWER_MS;
				unanswered.pump();
				eq(Lobby_Tab.State.FAILED, unanswered.state(), "a tab past ANSWER_MS");
				is(unanswered.failure().contains("cannot take browser players"), "the failure says why : " + unanswered.failure());
				unanswered.close();
			}

			// The service goes : the tab sees it
			live.front.close();
			long end = System.nanoTime() + 3_000_000_000L;
			while (!lobby.serviceLost() && System.nanoTime() < end)
			{
				lobby.pump();
				Thread.sleep(2);
			}
			is(lobby.serviceLost(), "a closed front was not seen by the tab");
			lobby.close();
		}
	}

	/** What is not a WebSocket upgrade gets an HTTP refusal, and a text message closes the connection 1003. */
	static void frontRefusesWhatIsNotATab() throws Exception
	{
		try (Live live = new Live())
		{
			try (Socket socket = new Socket("127.0.0.1", live.front.localPort()))
			{
				socket.setSoTimeout(5);
				OutputStream out = socket.getOutputStream();
				out.write("GET / HTTP/1.1\r\nHost: lobby\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
				out.flush();
				InputStream in = socket.getInputStream();
				StringBuilder reply = new StringBuilder();
				long end = System.nanoTime() + 3_000_000_000L;
				while (reply.indexOf("\r\n") < 0 && System.nanoTime() < end)
				{
					live.pump();
					try
					{
						int b = in.read();
						if (b < 0)
							break;
						reply.append((char) b);
					}
					catch (SocketTimeoutException e)
					{
						// Nothing yet
					}
				}
				is(reply.toString().startsWith("HTTP/1.1 426"), "a plain GET got : " + reply);
			}
			eq(1, live.front.refusedUpgrades, "upgrades refused");

			Tab tab = live.tab();
			tab.socket.sendText("hello", true).join();
			long end = System.nanoTime() + 3_000_000_000L;
			Object got = null;
			while (got == null && System.nanoTime() < end)
			{
				live.pump();
				got = tab.in.poll(2, TimeUnit.MILLISECONDS);
			}
			eq(Integer.valueOf(1003), got, "a text message's close status");
			live.until(() -> live.front.connections() == 0, 3000, "the tab that sent text is still connected");

			eq("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", Transport_Ws.accept("dGhlIHNhbXBsZSBub25jZQ=="), "RFC 6455's own handshake example");
		}
	}

	static void refuses(Runnable encode, String what)
	{
		try
		{
			encode.run();
		}
		catch (IllegalArgumentException e)
		{
			return;
		}
		throw new AssertionError("encoded " + what);
	}

	static void rejected(ByteBuffer packet, String what)
	{
		try
		{
			Lobby_Codec.decode(packet);
		}
		catch (Net_Rejected e)
		{
			return;
		}
		throw new AssertionError("decoded " + what);
	}
}
