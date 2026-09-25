package jks.html;

import java.nio.ByteBuffer;

import com.google.gwt.core.client.JavaScriptObject;

import jks.net.Net_Listener;
import jks.net.Net_Peer;
import jks.net.Net_Transport;

/**
 * A browser tab's way to the lobby service (r82) : the page's WebSocket to the service's front
 * (jks.lobby.Transport_Ws, r79), behind Net_Transport, so jks.lobby.Lobby_Tab speaks Lobby_Codec over it as a
 * desktop's Lobby_Client does over UDP. Its one peer is the service ; each binary message is one whole lobby
 * message, and the front alone may send one past MAX_PAYLOAD (a whole OFFER or ANSWER), so neither end
 * holds this transport to the cap.
 *
 * The browser's callbacks only fill the end's inbox ; {@link #pump} delivers on the loop's thread, as the
 * interface promises. What is sent before the socket opened waits for it. A socket that closes, errs or
 * never opens is the service {@link Net_Listener#lost}, once. Pings are the browser's own business.
 */
public final class Transport_WebSocket implements Net_Transport
{
	private final JavaScriptObject end ;
	private final Net_Peer service ;
	private boolean lostSaid ;
	private int dropped ;

	/** @param address the front's "host:port" : TCP 7771 on VPS_1, ws:// (TLS is the hosting task's) */
	public Transport_WebSocket(String address)
	{
		String name = "ws/" + address ;
		service = new Net_Peer()
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
		} ;
		end = open("ws://" + address + "/") ;
	}

	@Override
	public Net_Peer resolve(String address)
	{return service ;}

	@Override
	public void send(Net_Peer peer, ByteBuffer payload)
	{
		byte[] bytes = new byte[payload.remaining()] ;
		payload.get(bytes) ;
		if(!send(end, bytes))
			dropped++ ;
	}

	@Override
	public int pump(Net_Listener listener)
	{
		int delivered = 0 ;
		for(int length ; (length = nextLength(end)) >= 0 ; )
		{
			byte[] bytes = new byte[length] ;
			take(end, bytes) ;
			listener.received(service, ByteBuffer.wrap(bytes)) ;
			delivered++ ;
		}
		if(!lostSaid && lost(end))
		{
			lostSaid = true ;
			listener.lost(service) ;
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
	{close(end) ;}

	// ---------------------------------------------------------------- the page's side

	private static native JavaScriptObject open(String url)
	/*-{
		var end = {inbox: [], outbox: [], lost: false};
		try {
			end.socket = new WebSocket(url);
		} catch (e) {
			end.lost = true;
			return end;
		}
		end.socket.binaryType = 'arraybuffer';
		end.socket.onopen = function() {
			for (var i = 0; i < end.outbox.length; i++)
				end.socket.send(end.outbox[i]);
			end.outbox = [];
		};
		end.socket.onmessage = function(event) {
			if (event.data instanceof ArrayBuffer)
				end.inbox.push(new Uint8Array(event.data));
		};
		end.socket.onclose = function() { end.lost = true; };
		end.socket.onerror = function() { end.lost = true; };
		return end;
	}-*/;

	/** False when the socket is closing or closed : the message is dropped. */
	private static native boolean send(JavaScriptObject end, byte[] bytes)
	/*-{
		if (end.lost || !end.socket)
			return false;
		var message = new Uint8Array(bytes.length);
		for (var i = 0; i < bytes.length; i++)
			message[i] = bytes[i];
		if (end.socket.readyState == 0) {
			end.outbox.push(message);
			return true;
		}
		if (end.socket.readyState != 1)
			return false;
		end.socket.send(message);
		return true;
	}-*/;

	private static native int nextLength(JavaScriptObject end)
	/*-{
		return end.inbox.length ? end.inbox[0].length : -1;
	}-*/;

	/** Moves the oldest message into bytes, which is exactly its length, and forgets it. */
	private static native void take(JavaScriptObject end, byte[] bytes)
	/*-{
		var message = end.inbox.shift();
		for (var i = 0; i < message.length; i++)
			bytes[i] = (message[i] << 24) >> 24;
	}-*/;

	private static native boolean lost(JavaScriptObject end)
	/*-{
		return end.lost;
	}-*/;

	private static native void close(JavaScriptObject end)
	/*-{
		end.lost = true;
		if (end.socket)
			try { end.socket.close(); } catch (e) {}
	}-*/;
}
