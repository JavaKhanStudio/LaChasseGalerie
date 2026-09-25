package jks.lobby;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

import jks.net.Lobby_Codec;
import jks.net.Net_Listener;
import jks.net.Net_Peer;
import jks.net.Net_Transport;

/**
 * The lobby service's second front (r79) : a WebSocket server, for the player a UDP socket cannot reach
 * - a browser tab (d7). Hand-rolled RFC 6455, JDK only, because the lobby module is core's classes and a
 * JRE and nothing else : the HTTP upgrade, binary frames (fragmented or not), ping, pong and close.
 *
 * It is a {@link Net_Transport}, so {@link Lobby_Service} holds a tab like any player : each binary
 * message is one lobby packet, the SAME {@link Lobby_Codec} bytes, and a tab's address is
 * {@code "ws/<ip:port>"} (the TCP connection's far end), which no UDP address can be mistaken for.
 *
 * WHERE IT BENDS THE CONTRACT : a message here is not a datagram, nothing fragments it and nothing loses
 * it, so {@link #send} takes up to {@link Lobby_Codec#MAX_MESSAGE} - a whole WebRTC description, the
 * reason this front exists - where a packet stops at {@link #MAX_PAYLOAD}. Everything else holds : send and
 * pump never block, a message is delivered whole or not at all, and a peer is lost once, when its
 * connection closes or has been silent for the timeout. A tab that is only waiting still answers the
 * pings sent every {@link #PING_MS} : browsers do that by themselves.
 *
 * No TLS : a page served over https needs wss, and where the page is served is a later phase's
 * question. Not thread safe : pump it from the service's loop.
 */
public final class Transport_Ws implements Net_Transport
{
	/** What every address this transport names starts with. */
	public static final String PREFIX = "ws/";
	/** The upgrade request : a browser's is well under 1 kB, cookies included. */
	static final int MAX_REQUEST = 8192;
	public static final int MAX_CONNECTIONS = 1024;
	/** Time to finish the upgrade after connecting. */
	static final long HANDSHAKE_MS = 5_000;
	public static final long PING_MS = 10_000;
	/** Unsent bytes one connection may hold : a reader that does not read is closed, not buffered for. */
	static final int MAX_BACKLOG = 256 * 1024;
	static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

	private final ServerSocketChannel server;
	private final Selector selector;
	private final LongSupplier clock;
	private final long timeoutMs;
	private final Map<String, Connection> connections = new HashMap<String, Connection>();
	private final List<Net_Peer> lost = new ArrayList<Net_Peer>();
	private int dropped;
	public int refusedUpgrades;

	/** A WebSocket server on this TCP port, 0 for any ; a peer is lost after timeoutMs of silence. */
	public static Transport_Ws open(int port, LongSupplier clock, long timeoutMs)
	{
		try
		{
			ServerSocketChannel server = ServerSocketChannel.open();
			server.setOption(StandardSocketOptions.SO_REUSEADDR, Boolean.TRUE);
			server.bind(new InetSocketAddress(port));
			server.configureBlocking(false);
			return new Transport_Ws(server, clock, timeoutMs);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("could not open a WebSocket server on TCP port " + port, e);
		}
	}

	private Transport_Ws(ServerSocketChannel server, LongSupplier clock, long timeoutMs) throws IOException
	{
		this.server = server;
		this.selector = Selector.open();
		this.clock = clock;
		this.timeoutMs = timeoutMs;
		server.register(selector, SelectionKey.OP_ACCEPT);
	}

	/** One tab's connection, from the TCP accept to the close. */
	final class Connection implements Net_Peer
	{
		final String address;
		final SocketChannel channel;
		final long opened;
		/** Bytes read and not yet understood : the request, then frames. Big enough for the biggest frame. */
		final ByteBuffer in = ByteBuffer.allocate(Lobby_Codec.MAX_MESSAGE + 14);
		final Deque<ByteBuffer> out = new ArrayDeque<ByteBuffer>();
		int backlog;
		boolean upgraded, closed;
		long lastHeard, lastPing;
		/** A message arriving in several frames, until its last one. */
		java.io.ByteArrayOutputStream fragments;

		Connection(String address, SocketChannel channel, long now)
		{
			this.address = address;
			this.channel = channel;
			this.opened = now;
			this.lastHeard = now;
			this.lastPing = now;
		}

		@Override
		public String address()
		{
			return address;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Net_Peer && ((Net_Peer) other).address().equals(address);
		}

		@Override
		public int hashCode()
		{
			return address.hashCode();
		}

		@Override
		public String toString()
		{
			return address;
		}
	}

	/** A peer this transport has no connection for : sending to it is a dropped message. */
	static final class Absent implements Net_Peer
	{
		final String address;

		Absent(String address)
		{
			this.address = address;
		}

		@Override
		public String address()
		{
			return address;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Net_Peer && ((Net_Peer) other).address().equals(address);
		}

		@Override
		public int hashCode()
		{
			return address.hashCode();
		}
	}

	/** True for an address this kind of transport names. */
	public static boolean isWs(String address)
	{
		return address != null && address.startsWith(PREFIX);
	}

	@Override
	public Net_Peer resolve(String address)
	{
		Connection connection = connections.get(address);
		return connection != null ? connection : new Absent(address);
	}

	@Override
	public void send(Net_Peer peer, ByteBuffer payload)
	{
		int size = payload.remaining();
		if (size > Lobby_Codec.MAX_MESSAGE)
			throw new IllegalArgumentException(size + " bytes is past the " + Lobby_Codec.MAX_MESSAGE + " B a lobby message may be");
		Connection connection = connections.get(peer.address());
		if (connection == null || !connection.upgraded || connection.closed)
		{
			dropped++;
			payload.position(payload.limit());
			return;
		}
		queue(connection, frame(0x2, payload));
		flush(connection);
	}

	@Override
	public int pump(Net_Listener listener)
	{
		int delivered = 0;
		long now = clock.getAsLong();
		try
		{
			selector.selectNow();
		}
		catch (IOException e)
		{
			dropped++;
		}
		for (SelectionKey key : new ArrayList<SelectionKey>(selector.selectedKeys()))
		{
			if (!key.isValid())
				continue;
			if (key.isAcceptable())
				accept(now);
			else if (key.isReadable())
				delivered += read((Connection) key.attachment(), listener, now);
		}
		selector.selectedKeys().clear();

		for (Connection connection : new ArrayList<Connection>(connections.values()))
		{
			if (connection.closed)
				continue;
			if (!connection.upgraded && now - connection.opened >= HANDSHAKE_MS)
				drop(connection);
			else if (connection.upgraded && now - connection.lastHeard >= timeoutMs)
				fail(connection, 1001);
			else if (connection.upgraded && now - connection.lastPing >= PING_MS)
			{
				connection.lastPing = now;
				queue(connection, frame(0x9, ByteBuffer.allocate(0)));
			}
			if (!connection.closed && !connection.out.isEmpty())
				flush(connection);
		}

		for (Net_Peer peer : new ArrayList<Net_Peer>(lost))
			listener.lost(peer);
		lost.clear();
		return delivered;
	}

	// ---------------------------------------------------------------- arriving

	void accept(long now)
	{
		while (true)
		{
			SocketChannel channel;
			try
			{
				channel = server.accept();
				if (channel == null)
					return;
				if (connections.size() >= MAX_CONNECTIONS)
				{
					dropped++;
					channel.close();
					continue;
				}
				channel.configureBlocking(false);
				channel.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
				Connection connection = new Connection(PREFIX + textOf((InetSocketAddress) channel.getRemoteAddress()), channel, now);
				// A connection from the same ip:port as one not yet reaped : the old one is over
				Connection old = connections.put(connection.address, connection);
				if (old != null)
					close(old, true);
				channel.register(selector, SelectionKey.OP_READ, connection);
			}
			catch (IOException e)
			{
				dropped++;
				return;
			}
		}
	}

	int read(Connection connection, Net_Listener listener, long now)
	{
		if (connection.closed)
			return 0;
		int delivered = 0;
		while (true)
		{
			int read;
			try
			{
				read = connection.channel.read(connection.in);
			}
			catch (IOException e)
			{
				read = -1;
			}
			if (read < 0)
			{
				close(connection, true);
				return delivered;
			}
			if (!connection.upgraded)
			{
				if (!upgrade(connection))
					return delivered;
			}
			else
			{
				delivered += frames(connection, listener, now);
				if (connection.closed)
					return delivered;
			}
			if (read == 0)
				return delivered;
		}
	}

	/** Reads the HTTP request once it is whole, and answers it. False while it is not, or when it was refused. */
	boolean upgrade(Connection connection)
	{
		ByteBuffer in = connection.in;
		int end = -1;
		for (int i = 3; i < in.position(); i++)
			if (in.get(i - 3) == '\r' && in.get(i - 2) == '\n' && in.get(i - 1) == '\r' && in.get(i) == '\n')
			{
				end = i + 1;
				break;
			}
		if (end < 0)
		{
			if (in.position() >= MAX_REQUEST)
				refuse(connection, "431 Request Header Fields Too Large", "");
			return false;
		}

		String request = new String(in.array(), 0, end, StandardCharsets.ISO_8859_1);
		String[] lines = request.split("\r\n");
		Map<String, String> headers = new HashMap<String, String>();
		for (int i = 1; i < lines.length; i++)
		{
			int colon = lines[i].indexOf(':');
			if (colon > 0)
				headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
		}
		String key = headers.get("sec-websocket-key");
		if (!lines[0].startsWith("GET ") || !has(headers.get("upgrade"), "websocket") || !has(headers.get("connection"), "upgrade") || key == null)
		{
			refuse(connection, "426 Upgrade Required", "Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\n");
			return false;
		}
		if (!"13".equals(headers.get("sec-websocket-version")))
		{
			refuse(connection, "426 Upgrade Required", "Sec-WebSocket-Version: 13\r\n");
			return false;
		}
		try
		{
			if (Base64.getDecoder().decode(key).length != 16)
				throw new IllegalArgumentException("a key is 16 bytes");
		}
		catch (IllegalArgumentException e)
		{
			refuse(connection, "400 Bad Request", "");
			return false;
		}

		queue(connection, ByteBuffer.wrap(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
				+ accept(key) + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)));
		connection.upgraded = true;
		// A client may send its first frame right behind the request
		in.flip();
		in.position(end);
		in.compact();
		flush(connection);
		return !connection.closed;
	}

	/** The Sec-WebSocket-Accept for a key : RFC 6455 section 4.2.2. */
	public static String accept(String key)
	{
		try
		{
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			return Base64.getEncoder().encodeToString(sha1.digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("every JDK has SHA-1", e);
		}
	}

	static boolean has(String header, String token)
	{
		if (header == null)
			return false;
		for (String part : header.split(","))
			if (part.trim().equalsIgnoreCase(token))
				return true;
		return false;
	}

	void refuse(Connection connection, String status, String headers)
	{
		refusedUpgrades++;
		queue(connection, ByteBuffer.wrap(("HTTP/1.1 " + status + "\r\n" + headers + "Content-Length: 0\r\nConnection: close\r\n\r\n")
				.getBytes(StandardCharsets.ISO_8859_1)));
		flush(connection);
		close(connection, false);
	}

	/** Every whole frame in the buffer, delivered or answered. Returns the messages delivered. */
	int frames(Connection connection, Net_Listener listener, long now)
	{
		ByteBuffer in = connection.in;
		int delivered = 0;
		while (!connection.closed)
		{
			int available = in.position();
			if (available < 2)
				break;
			int b0 = in.get(0) & 0xFF, b1 = in.get(1) & 0xFF;
			boolean fin = (b0 & 0x80) != 0;
			int opcode = b0 & 0x0F;
			long length = b1 & 0x7F;
			int header = 2;
			if ((b0 & 0x70) != 0 || (b1 & 0x80) == 0)
			{
				// Extension bits nobody agreed on, or a client frame without its mask : RFC 6455 says fail
				fail(connection, 1002);
				break;
			}
			if (length == 126)
			{
				if (available < 4)
					break;
				length = in.getShort(2) & 0xFFFF;
				header = 4;
			}
			else if (length == 127)
			{
				if (available < 10)
					break;
				length = in.getLong(2);
				header = 10;
			}
			if (length < 0 || length > Lobby_Codec.MAX_MESSAGE || (connection.fragments != null ? connection.fragments.size() + length > Lobby_Codec.MAX_MESSAGE : false))
			{
				fail(connection, 1009);
				break;
			}
			if (available < header + 4 + length)
				break;

			byte[] payload = new byte[(int) length];
			for (int i = 0; i < length; i++)
				payload[i] = (byte) (in.get(header + 4 + i) ^ in.get(header + (i & 3)));
			in.flip();
			in.position((int) (header + 4 + length));
			in.compact();
			connection.lastHeard = now;

			if (opcode >= 0x8 && (!fin || length > 125))
			{
				fail(connection, 1002);
				break;
			}
			switch (opcode)
			{
				case 0x2:
					if (connection.fragments != null)
					{
						fail(connection, 1002);
						break;
					}
					if (fin)
						delivered += deliver(connection, payload, listener);
					else
						(connection.fragments = new java.io.ByteArrayOutputStream()).write(payload, 0, payload.length);
					break;
				case 0x0:
					if (connection.fragments == null)
					{
						fail(connection, 1002);
						break;
					}
					connection.fragments.write(payload, 0, payload.length);
					if (fin)
					{
						byte[] whole = connection.fragments.toByteArray();
						connection.fragments = null;
						delivered += deliver(connection, whole, listener);
					}
					break;
				case 0x1:
					// The lobby speaks bytes : a text message is somebody else's protocol
					fail(connection, 1003);
					break;
				case 0x8:
					queue(connection, frame(0x8, ByteBuffer.wrap(payload, 0, Math.min(2, payload.length))));
					flush(connection);
					close(connection, true);
					break;
				case 0x9:
					queue(connection, frame(0xA, ByteBuffer.wrap(payload)));
					flush(connection);
					break;
				case 0xA:
					break;
				default:
					fail(connection, 1002);
					break;
			}
		}
		return delivered;
	}

	int deliver(Connection connection, byte[] message, Net_Listener listener)
	{
		listener.received(connection, ByteBuffer.wrap(message));
		return 1;
	}

	// ---------------------------------------------------------------- sending

	/** One unmasked frame, FIN set : a server never masks, and never needs to cut a message. */
	static ByteBuffer frame(int opcode, ByteBuffer payload)
	{
		int length = payload.remaining();
		ByteBuffer frame = ByteBuffer.allocate(length + (length < 126 ? 2 : length < 65536 ? 4 : 10));
		frame.put((byte) (0x80 | opcode));
		if (length < 126)
			frame.put((byte) length);
		else if (length < 65536)
		{
			frame.put((byte) 126);
			frame.putShort((short) length);
		}
		else
		{
			frame.put((byte) 127);
			frame.putLong(length);
		}
		frame.put(payload);
		frame.flip();
		return frame;
	}

	void queue(Connection connection, ByteBuffer bytes)
	{
		connection.out.addLast(bytes);
		connection.backlog += bytes.remaining();
		if (connection.backlog > MAX_BACKLOG)
		{
			dropped++;
			close(connection, true);
		}
	}

	void flush(Connection connection)
	{
		try
		{
			while (!connection.out.isEmpty())
			{
				ByteBuffer next = connection.out.peekFirst();
				int wrote = connection.channel.write(next);
				connection.backlog -= wrote;
				if (next.hasRemaining())
					return;
				connection.out.pollFirst();
			}
		}
		catch (IOException e)
		{
			close(connection, true);
		}
	}

	// ---------------------------------------------------------------- leaving

	/** A protocol error : say why in a close frame, then go. */
	void fail(Connection connection, int status)
	{
		ByteBuffer code = ByteBuffer.allocate(2);
		code.putShort((short) status);
		code.flip();
		queue(connection, frame(0x8, code));
		flush(connection);
		close(connection, true);
	}

	/** A connection that never finished its upgrade : nobody knew it as a peer. */
	void drop(Connection connection)
	{
		close(connection, false);
	}

	void close(Connection connection, boolean report)
	{
		if (connection.closed)
			return;
		connection.closed = true;
		try
		{
			connection.channel.close();
		}
		catch (IOException e)
		{
			// Gone either way
		}
		if (connections.get(connection.address) == connection)
			connections.remove(connection.address);
		if (report && connection.upgraded)
			lost.add(connection);
	}

	@Override
	public int localPort()
	{
		try
		{
			return ((InetSocketAddress) server.getLocalAddress()).getPort();
		}
		catch (IOException e)
		{
			return 0;
		}
	}

	/** Upgraded connections open now. */
	public int connections()
	{
		int count = 0;
		for (Connection connection : connections.values())
			if (connection.upgraded && !connection.closed)
				count++;
		return count;
	}

	@Override
	public int dropped()
	{
		return dropped;
	}

	@Override
	public void close()
	{
		for (Connection connection : new ArrayList<Connection>(connections.values()))
			close(connection, false);
		try
		{
			selector.close();
			server.close();
		}
		catch (IOException e)
		{
			// Closing what is already gone is not a problem anyone can act on
		}
	}

	/** "ip:port", an IPv6 literal in brackets : the same text Transport_Udp's addresses take. */
	static String textOf(InetSocketAddress endpoint)
	{
		String host = endpoint.getAddress() != null ? endpoint.getAddress().getHostAddress() : endpoint.getHostString();
		int scope = host.indexOf('%');
		if (scope >= 0)
			host = host.substring(0, scope);
		if (host.indexOf(':') >= 0)
			host = "[" + host + "]";
		return host + ":" + endpoint.getPort();
	}
}
