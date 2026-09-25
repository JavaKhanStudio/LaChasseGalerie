package jks.smoke;

import static jks.smoke.Net_Run.eq;
import static jks.smoke.Net_Run.is;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import jks.net.Lobby_Message;
import jks.net.Net_Codec;
import jks.net.Net_Listener;
import jks.net.Net_Message;
import jks.net.Net_Peer;
import jks.net.Net_Transport;
import jks.rtc.Transport_Rtc;

/**
 * The desktop end of a browser tab's transport (r46) : two {@link Transport_Rtc} in this JVM, one playing
 * the tab (it offers) and one the host (it answers), over this machine's own interfaces. No STUN server :
 * host candidates are enough here, and a gate must not need the internet. Real time, in milliseconds :
 * libwebrtc runs on its own threads.
 */
final class Net_Rtc_Checks
{
	private Net_Rtc_Checks()
	{
	}

	/** Pumps both ends until the condition holds, for at most that long. */
	static void until(BooleanSupplier condition, long millis, String failure, Net_Transport... ends) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + millis;
		Net_Run.Inbox ignored = new Net_Run.Inbox();
		while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline)
		{
			for (Net_Transport end : ends)
				end.pump(ignored);
			Thread.sleep(5);
		}
		is(condition.getAsBoolean(), failure);
	}

	/** The whole signalling : the tab's description to the host, the host's back. Returns {tab's call, host's call}. */
	static Transport_Rtc.Call[] connect(Transport_Rtc tab, Transport_Rtc host) throws InterruptedException
	{
		Transport_Rtc.Call offer = tab.offer();
		until(() -> offer.sdp() != null, 5000, "the tab never finished describing itself");
		is(offer.sdp().contains("webrtc-datachannel"), "the offer is a data channel's");
		Transport_Rtc.Call answer = host.answer(offer.sdp());
		until(() -> answer.sdp() != null || answer.failure() != null, 5000, "the host never answered");
		is(answer.failure() == null, "the host refused the offer : " + answer.failure());
		offer.answered(answer.sdp());
		until(() -> offer.open() && answer.open(), 5000, "the channel never opened both ways");
		return new Transport_Rtc.Call[] {offer, answer};
	}

	/** Open, then the biggest packet both ways, delivered whole, on the pumping thread, from the call's peer. */
	static void openAndCarryBothWays() throws Exception
	{
		try (Transport_Rtc tab = new Transport_Rtc(Collections.emptyList()); Transport_Rtc host = new Transport_Rtc(Collections.emptyList()))
		{
			long started = System.currentTimeMillis();
			Transport_Rtc.Call[] calls = connect(tab, host);
			System.out.println("NET      a data channel opened in " + (System.currentTimeMillis() - started) + " ms ; offer "
					+ calls[0].sdp().length() + " B, answer " + calls[1].sdp().length() + " B");
			is(calls[1].peer().address().startsWith(Transport_Rtc.PREFIX), "a tab is named rtc/N, got " + calls[1].peer().address());
			eq(calls[1].peer(), host.resolve(calls[1].peer().address()), "resolving a call's name gives its peer");

			byte[] big = new byte[Net_Transport.MAX_PAYLOAD];
			for (int i = 0; i < big.length; i++)
				big[i] = (byte) (i * 7);
			Thread pumping = Thread.currentThread();
			boolean[] offThread = {false};
			byte[][] got = new byte[2][];
			Net_Peer[] from = new Net_Peer[2];
			Net_Listener atHost = new Net_Listener()
			{
				@Override
				public void received(Net_Peer peer, ByteBuffer payload)
				{
					offThread[0] |= Thread.currentThread() != pumping;
					got[0] = new byte[payload.remaining()];
					payload.get(got[0]);
					from[0] = peer;
				}
			};
			Net_Listener atTab = (peer, payload) ->
			{
				got[1] = new byte[payload.remaining()];
				payload.get(got[1]);
				from[1] = peer;
			};
			tab.send(calls[0].peer(), ByteBuffer.wrap(big));
			host.send(calls[1].peer(), ByteBuffer.wrap(big));
			long deadline = System.currentTimeMillis() + 3000;
			while ((got[0] == null || got[1] == null) && System.currentTimeMillis() < deadline)
			{
				host.pump(atHost);
				tab.pump(atTab);
				Thread.sleep(2);
			}
			is(got[0] != null && got[1] != null, "1200 B did not cross both ways");
			is(java.util.Arrays.equals(big, got[0]) && java.util.Arrays.equals(big, got[1]), "delivered whole and unchanged");
			eq(calls[1].peer(), from[0], "the host hears the tab as its call's peer");
			eq(calls[0].peer(), from[1], "and the tab the host as its own");
			is(!offThread[0], "delivered on the thread that pumps, never libwebrtc's");
			eq(0, host.dropped() + tab.dropped(), "nothing dropped");
		}
	}

	/** The channel is the one the contract describes : unordered, never retransmitted, capped at 1200 B. */
	static void unreliableUnorderedAndCapped() throws Exception
	{
		try (Transport_Rtc tab = new Transport_Rtc(Collections.emptyList()); Transport_Rtc host = new Transport_Rtc(Collections.emptyList()))
		{
			Transport_Rtc.Call[] calls = connect(tab, host);
			for (Transport_Rtc.Call call : calls)
				is(!call.ordered() && call.maxRetransmits() == 0, call.peer() + " : ordered " + call.ordered() + ", " + call.maxRetransmits() + " retransmits");
			boolean refused = false;
			try
			{
				tab.send(calls[0].peer(), ByteBuffer.allocate(Net_Transport.MAX_PAYLOAD + 1));
			}
			catch (IllegalArgumentException e)
			{
				refused = true;
			}
			is(refused, "a payload past MAX_PAYLOAD is refused out loud");
			// A name nobody called reaches nothing, and says so in dropped()
			tab.send(tab.resolve("rtc/99"), ByteBuffer.allocate(10));
			eq(1, tab.dropped(), "a packet to a peer with no channel is dropped and counted");
		}
	}

	/** A tab that closes is reported lost once ; a call that never opens is lost after the timeout ; a bad offer fails. */
	static void closedSilentAndRefusedAreLostOnce() throws Exception
	{
		try (Transport_Rtc host = new Transport_Rtc(Collections.emptyList()))
		{
			Transport_Rtc tab = new Transport_Rtc(Collections.emptyList());
			Transport_Rtc.Call[] calls = connect(tab, host);
			tab.close();
			Net_Run.Inbox inbox = new Net_Run.Inbox();
			long deadline = System.currentTimeMillis() + 8000;
			while (inbox.lost.isEmpty() && System.currentTimeMillis() < deadline)
			{
				host.pump(inbox);
				Thread.sleep(5);
			}
			eq(1, inbox.lost.size(), "the host hears a closed tab is gone");
			eq(calls[1].peer(), inbox.lost.get(0), "and which");
			host.pump(inbox);
			eq(1, inbox.lost.size(), "once");
			is(host.call(calls[1].peer()) == null, "and forgets its call");

			Transport_Rtc.Call bad = host.answer("v=0\r\nnot a description\r\n");
			inbox.lost.clear();
			deadline = System.currentTimeMillis() + 3000;
			while (inbox.lost.isEmpty() && System.currentTimeMillis() < deadline)
			{
				host.pump(inbox);
				Thread.sleep(5);
			}
			is(bad.failure() != null, "an offer that is not one fails");
			eq(bad.peer(), inbox.lost.isEmpty() ? null : inbox.lost.get(0), "and its peer is reported lost");
		}

		AtomicLong now = new AtomicLong();
		try (Transport_Rtc host = new Transport_Rtc(Collections.emptyList(), now::get, 10_000))
		{
			Transport_Rtc tab = new Transport_Rtc(Collections.emptyList());
			Transport_Rtc.Call offer = tab.offer();
			until(() -> offer.sdp() != null, 5000, "no offer");
			// The answer never goes back : the host's call waits for a tab that will not come
			Transport_Rtc.Call waiting = host.answer(offer.sdp());
			until(() -> waiting.sdp() != null, 5000, "no answer");
			Net_Run.Inbox inbox = new Net_Run.Inbox();
			now.set(9_999);
			host.pump(inbox);
			eq(0, inbox.lost.size(), "not lost inside the timeout");
			now.set(10_000);
			host.pump(inbox);
			eq(1, inbox.lost.size(), "a call that never opened is lost at the timeout");
			host.pump(inbox);
			eq(1, inbox.lost.size(), "once");
			tab.close();
		}
	}

	/**
	 * r80 : a tab joins a desktop host the way a browser will. The tab is a Transport_Rtc that offers, and
	 * java.net.http's WebSocket on a local service's front ; the host a Lobby_Client on a real UDP socket with
	 * a Transport_Rtc plugged in. The offer goes through the service, the host answers it by itself, the
	 * channel opens, and game packets cross both ways through the host's game view as from "rtc/N".
	 */
	static void aTabJoinsAHostThroughTheLobby() throws Exception
	{
		try (Net_Tab_Checks.Live live = new Net_Tab_Checks.Live(); Transport_Rtc tabEnd = new Transport_Rtc(Collections.emptyList()))
		{
			Transport_Rtc hostTabs = new Transport_Rtc(Collections.emptyList());
			live.host.host(8);
			live.host.tabs(hostTabs);
			live.until(() -> live.host.code() != null, 3000, "the host got no code");
			String code = live.host.code();

			Net_Tab_Checks.Tab tab = live.tab();
			Lobby_Message.Join join = new Lobby_Message.Join();
			join.code = code;
			tab.send(join);
			is(live.next(tab, 3000) instanceof Lobby_Message.Joined, "the tab was not joined");

			long started = System.currentTimeMillis();
			Transport_Rtc.Call offer = tabEnd.offer();
			live.until(() -> offer.sdp() != null, 5000, "the tab never finished describing itself");
			tab.send(new Lobby_Message.Offer(code, offer.sdp()));
			Lobby_Message answer = live.next(tab, 5000);
			is(answer instanceof Lobby_Message.Answer, "the tab got " + answer + ", not the host's answer");
			eq(1, live.host.tabRows().size(), "the host's rows : the tab it answered");
			is(live.host.tabRows().get(0).answered(), "its row says the answer went");
			offer.answered(((Lobby_Message.Answer) answer).sdp);
			live.until(() -> offer.open() && live.host.tabRows().get(0).tab.open(), 5000, "the channel never opened both ways");
			System.out.println("NET      a tab joined through the lobby in " + (System.currentTimeMillis() - started) + " ms");

			// A game packet from the tab : held while the lobby pumps, delivered from rtc/N once the game does
			Net_Message.Hello hello = new Net_Message.Hello(80);
			tabEnd.send(offer.peer(), Net_Codec.encode(hello));
			Net_Run.Inbox game = new Net_Run.Inbox();
			long deadline = System.currentTimeMillis() + 3000;
			while (game.size() == 0 && System.currentTimeMillis() < deadline)
			{
				live.service.pump();
				live.host.pump();
				Thread.sleep(20);
				live.host.game().pump(game);
			}
			eq(1, game.size(), "the tab's HELLO reached the host's game");
			Net_Peer tabPeer = game.from(0);
			is(tabPeer.address().startsWith(Transport_Rtc.PREFIX), "the host's game hears the tab as rtc/N : " + tabPeer.address());
			is(Net_Codec.decode(ByteBuffer.wrap(game.payloads.get(0))) instanceof Net_Message.Hello, "whole, a HELLO");

			// The host answers through the same view, by the tab's name
			live.host.game().send(live.host.game().resolve(tabPeer.address()), ByteBuffer.wrap(new byte[Net_Transport.MAX_PAYLOAD]));
			Net_Run.Inbox atTab = new Net_Run.Inbox();
			deadline = System.currentTimeMillis() + 3000;
			while (atTab.size() == 0 && System.currentTimeMillis() < deadline)
			{
				tabEnd.pump(atTab);
				Thread.sleep(5);
			}
			eq(1, atTab.size(), "the host's packet reached the tab");
			eq(Net_Transport.MAX_PAYLOAD, atTab.payloads.get(0).length, "whole");

			// The tab goes : the host's game hears it lost, and the row goes
			tabEnd.close();
			Net_Run.Inbox after = new Net_Run.Inbox();
			deadline = System.currentTimeMillis() + 8000;
			while (after.lost.isEmpty() && System.currentTimeMillis() < deadline)
			{
				live.host.game().pump(after);
				Thread.sleep(5);
			}
			eq(Collections.singletonList(tabPeer), after.lost, "the host's game hears the tab went");
			is(live.host.tabRows().isEmpty(), "and its row is gone");
		}
	}
}
