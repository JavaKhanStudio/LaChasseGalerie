package jks.html;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gwt.core.client.JavaScriptObject;

import jks.lobby.Lobby_Tab;
import jks.net.Lobby_Message;
import jks.net.Net_Listener;
import jks.net.Net_Peer;
import jks.net.Net_Tabs;

/**
 * A browser tab's game transport (r82, d7) : one RTCDataChannel per call to a host, behind the same
 * Net_Transport a desktop's UDP socket is behind, so ClientSession and Snapshot_Mirror play on it unchanged.
 * The tab's end of what jks.rtc.Transport_Rtc is on the host : its calls are named "rtc/N" like that one's.
 *
 * The channel is {ordered:false, maxRetransmits:0} : lossy and unordered, the contract the protocol above
 * already survives, and what the host's end expects. The offer is WHOLE : {@link Call#sdp()} stays null
 * until ICE gathering is complete, so one OFFER and one ANSWER are the whole signalling (r79), never a
 * trickle. A gathering that has not completed after {@link #GATHER_MS} (a TURN server that does not answer
 * holds Chrome's for tens of seconds) is described with what it gathered by then, still whole.
 *
 * The relay the service named in JOINED is the call's TURN server (its REST credential, 24 h) : a tab
 * cannot be punched to, and the relay is its way to a host behind a hard NAT.
 *
 * The browser's callbacks only fill each call's inbox and flags ; {@link #pump} delivers on the loop's
 * thread. A call is reported {@link Net_Listener#lost} once, when its channel closes or fails, or after
 * {@link #TIMEOUT_MS} with nothing heard, as Transport_Rtc does.
 */
public final class Transport_Channel implements Lobby_Tab.Offerer
{
	/** How long a gathering may take before the offer is described with what it has. */
	public static final int GATHER_MS = 5_000 ;
	/** As Transport_Rtc's channel, which the host opens its end with. */
	public static final String LABEL = "game" ;

	private final Map<String, Call> calls = new LinkedHashMap<String, Call>() ;
	/** Net_PeerTable's and Transport_Rtc's : a peer silent this long is lost. */
	public static final long TIMEOUT_MS = 10_000 ;
	private int made, dropped ;

	private static long now()
	{return System.currentTimeMillis() ;}

	/** One call to a host : its peer, then its channel. */
	final class Call implements Lobby_Tab.Call
	{
		final Net_Peer peer ;
		final JavaScriptObject end ;
		long lastHeard = now() ;

		Call(Net_Peer peer, JavaScriptObject end)
		{
			this.peer = peer ;
			this.end = end ;
		}

		@Override
		public Net_Peer peer()
		{return peer ;}

		@Override
		public String sdp()
		{return sdpOf(end) ;}

		@Override
		public String failure()
		{return failureOf(end) ;}

		@Override
		public boolean open()
		{return isOpen(end) ;}

		@Override
		public void answered(String sdp)
		{answer(end, sdp) ;}
	}

	@Override
	public Lobby_Tab.Call offer(Lobby_Message.Relay relay)
	{
		String name = Net_Tabs.PREFIX + ++made ;
		Net_Peer peer = peer(name) ;
		JavaScriptObject end = relay == null
				? call(LABEL, null, null, null, GATHER_MS)
				: call(LABEL, "turn:" + relay.server + "?transport=udp", relay.username, relay.password, GATHER_MS) ;
		Call call = new Call(peer, end) ;
		calls.put(name, call) ;
		return call ;
	}

	private static Net_Peer peer(String name)
	{
		return new Net_Peer()
		{
			@Override
			public String address()
			{return name ;}

			@Override
			public boolean equals(Object other)
			{return other instanceof Net_Peer && ((Net_Peer) other).address().equals(name) ;}

			@Override
			public int hashCode()
			{return name.hashCode() ;}

			@Override
			public String toString()
			{return name ;}
		} ;
	}

	@Override
	public Net_Peer resolve(String address)
	{
		Call call = calls.get(address) ;
		// Nobody called it : a peer nothing reaches. The session that holds it times out on its own
		return call != null ? call.peer : peer(address) ;
	}

	@Override
	public void send(Net_Peer peer, ByteBuffer payload)
	{
		if(payload.remaining() > MAX_PAYLOAD)
			throw new IllegalArgumentException(payload.remaining() + " B is more than the " + MAX_PAYLOAD + " B a packet may carry") ;
		byte[] bytes = new byte[payload.remaining()] ;
		payload.get(bytes) ;
		Call call = calls.get(peer.address()) ;
		if(call == null || !send(call.end, bytes))
			dropped++ ;
	}

	@Override
	public int pump(Net_Listener listener)
	{
		int delivered = 0 ;
		long now = now() ;
		List<Call> gone = new ArrayList<Call>() ;
		for(Call call : calls.values())
		{
			for(int length ; (length = nextLength(call.end)) >= 0 ; )
			{
				byte[] bytes = new byte[length] ;
				take(call.end, bytes) ;
				call.lastHeard = now ;
				listener.received(call.peer, ByteBuffer.wrap(bytes)) ;
				delivered++ ;
			}
			if(closed(call.end) || now - call.lastHeard >= TIMEOUT_MS)
				gone.add(call) ;
		}
		for(Call call : gone)
		{
			calls.remove(call.peer.address()) ;
			hangUp(call.end) ;
			listener.lost(call.peer) ;
		}
		return delivered ;
	}

	@Override
	public int localPort()
	{return 0 ;}

	@Override
	public int dropped()
	{return dropped ;}

	@Override
	public void close()
	{
		for(Iterator<Call> it = calls.values().iterator() ; it.hasNext() ; )
		{
			hangUp(it.next().end) ;
			it.remove() ;
		}
	}

	// ---------------------------------------------------------------- the page's side

	/**
	 * A peer connection with one data channel, offered : end.sdp is set once gathering is complete (or
	 * after gatherMs), end.failure when anything refuses, end.open while the channel is open, end.closed
	 * once it closed after opening or the connection failed.
	 */
	private static native JavaScriptObject call(String label, String turn, String username, String password, int gatherMs)
	/*-{
		var end = {inbox: [], sdp: null, failure: null, open: false, closed: false};
		try {
			var servers = [];
			if (turn != null)
				servers.push({urls: turn, username: username, credential: password});
			var pc = new RTCPeerConnection({iceServers: servers});
			end.pc = pc;
			var channel = pc.createDataChannel(label, {ordered: false, maxRetransmits: 0});
			channel.binaryType = 'arraybuffer';
			end.channel = channel;
			channel.onopen = function() { end.open = true; };
			channel.onclose = function() { end.open = false; end.closed = true; };
			channel.onmessage = function(event) {
				if (event.data instanceof ArrayBuffer)
					end.inbox.push(new Uint8Array(event.data));
			};
			var described = function() {
				if (end.sdp == null && pc.localDescription && pc.localDescription.sdp)
					end.sdp = pc.localDescription.sdp;
			};
			pc.onicegatheringstatechange = function() {
				if (pc.iceGatheringState == 'complete')
					described();
			};
			pc.onconnectionstatechange = function() {
				if (pc.connectionState == 'failed') {
					if (end.failure == null)
						end.failure = 'the WebRTC connection failed';
					end.closed = true;
				}
			};
			pc.createOffer().then(function(offer) {
				return pc.setLocalDescription(offer);
			}).then(function() {
				if (pc.iceGatheringState == 'complete')
					described();
				setTimeout(described, gatherMs);
			})['catch'](function(e) {
				end.failure = 'the offer : ' + e;
			});
		} catch (e) {
			end.failure = 'WebRTC : ' + e;
		}
		return end;
	}-*/;

	private static native void answer(JavaScriptObject end, String sdp)
	/*-{
		if (!end.pc)
			return;
		try {
			end.pc.setRemoteDescription({type: 'answer', sdp: sdp})['catch'](function(e) {
				end.failure = 'the answer : ' + e;
			});
		} catch (e) {
			end.failure = 'the answer : ' + e;
		}
	}-*/;

	private static native String sdpOf(JavaScriptObject end)
	/*-{
		return end.sdp;
	}-*/;

	private static native String failureOf(JavaScriptObject end)
	/*-{
		return end.failure;
	}-*/;

	private static native boolean isOpen(JavaScriptObject end)
	/*-{
		return end.open;
	}-*/;

	private static native boolean closed(JavaScriptObject end)
	/*-{
		return end.closed;
	}-*/;

	/** False when the channel is not open or refused the message (its buffer is full) : dropped. */
	private static native boolean send(JavaScriptObject end, byte[] bytes)
	/*-{
		if (!end.open)
			return false;
		var message = new Uint8Array(bytes.length);
		for (var i = 0; i < bytes.length; i++)
			message[i] = bytes[i];
		try {
			end.channel.send(message);
			return true;
		} catch (e) {
			return false;
		}
	}-*/;

	private static native int nextLength(JavaScriptObject end)
	/*-{
		return end.inbox.length ? end.inbox[0].length : -1;
	}-*/;

	private static native void take(JavaScriptObject end, byte[] bytes)
	/*-{
		var message = end.inbox.shift();
		for (var i = 0; i < message.length; i++)
			bytes[i] = (message[i] << 24) >> 24;
	}-*/;

	private static native void hangUp(JavaScriptObject end)
	/*-{
		end.open = false;
		end.closed = true;
		try { if (end.channel) end.channel.close(); } catch (e) {}
		try { if (end.pc) end.pc.close(); } catch (e) {}
	}-*/;
}
