package jks.net;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The desktop transport : one unconnected, non-blocking {@link DatagramChannel}.
 *
 * libGDX gives us nothing here - com.badlogic.gdx.Net.Protocol has exactly one constant, TCP -
 * and that is fine, the JDK's channel is all a host-authoritative game needs.
 *
 * TWO THINGS ARE DELIBERATE AND SHOULD NOT BE "FIXED" :
 *
 * ONE SOCKET, EPHEMERAL BY DEFAULT. {@link #open()} binds port 0 and never asks a router or a
 * firewall for anything. Every mapping we use is one our OWN outbound packet created, which is
 * what makes hole punching work and what keeps Windows Defender quiet - an outbound packet never
 * raises the "wants to accept connections" prompt that people click No on. Only a dedicated
 * server passes a fixed port.
 *
 * THE CHANNEL IS NEVER connect()ed. A host must be able to answer an address it has never seen,
 * because that unsolicited packet IS the punch ; and a connected channel would refuse it. The
 * price is that we accept bytes from anyone, so the protocol above has to not trust them.
 */
public class Transport_Udp implements Net_Transport
{
	final DatagramChannel channel;
	final Net_PeerTable table;
	/** One byte more than a legal payload, so a packet too big to be ours is seen, not silently truncated. */
	final ByteBuffer in = ByteBuffer.allocateDirect(MAX_PAYLOAD + 1);
	int dropped;

	/** An ephemeral port : what every player uses. */
	public static Transport_Udp open()
	{
		return open(0, Net_PeerTable.MONOTONIC, Net_PeerTable.DEFAULT_TIMEOUT_MS);
	}

	/** A fixed port : a dedicated server, or a test that needs to know where it is. */
	public static Transport_Udp open(int port)
	{
		return open(port, Net_PeerTable.MONOTONIC, Net_PeerTable.DEFAULT_TIMEOUT_MS);
	}

	public static Transport_Udp open(int port, LongSupplier clock, long timeoutMs)
	{
		try
		{
			DatagramChannel channel = DatagramChannel.open();
			channel.configureBlocking(false);
			// A 20 Hz snapshot to eight clients arrives in bursts : a default receive buffer can eat one
			channel.setOption(StandardSocketOptions.SO_RCVBUF, Integer.valueOf(512 * 1024));
			channel.setOption(StandardSocketOptions.SO_SNDBUF, Integer.valueOf(512 * 1024));
			channel.bind(new InetSocketAddress(port));
			return new Transport_Udp(channel, clock, timeoutMs);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("could not open a UDP socket on port " + port, e);
		}
	}

	Transport_Udp(DatagramChannel channel, LongSupplier clock, long timeoutMs)
	{
		this.channel = channel;
		this.table = new Net_PeerTable(clock, timeoutMs);
	}

	/** "host:port", with an IPv6 literal in brackets : "[::1]:7777". */
	@Override
	public Net_Peer resolve(String address)
	{
		return table.peer(address, Udp_Peer::new);
	}

	@Override
	public void send(Net_Peer peer, ByteBuffer payload)
	{
		int size = payload.remaining();
		if (size > MAX_PAYLOAD)
			throw new IllegalArgumentException(size + " bytes is past MAX_PAYLOAD " + MAX_PAYLOAD
					+ " : IP would fragment it and one lost fragment loses the packet");
		try
		{
			// 0 means the send buffer is full : the packet is gone, which is what unreliable means
			if (channel.send(payload, ((Udp_Peer) peer).endpoint) == 0)
				dropped++;
		}
		catch (IOException e)
		{
			// An unreachable host or a dead route is a dropped packet, not a crash : the peer times out
			dropped++;
			payload.position(payload.limit());
		}
	}

	@Override
	public int pump(Net_Listener listener)
	{
		int delivered = 0;
		while (true)
		{
			SocketAddress from;
			in.clear();
			try
			{
				from = channel.receive(in);
			}
			catch (IOException e)
			{
				dropped++;
				continue;
			}
			if (from == null)
				break;

			in.flip();
			if (in.remaining() > MAX_PAYLOAD)
			{
				// Not ours, or a fragmented monster : reading it would be reading somebody else's mail
				dropped++;
				continue;
			}

			String address = textOf((InetSocketAddress) from);
			Net_Peer peer = table.peer(address, Udp_Peer::new);
			table.heard(address);
			delivered++;
			listener.received(peer, in);
		}

		List<Net_Peer> lost = table.takeLost();
		for (int i = 0; i < lost.size(); i++)
			listener.lost(lost.get(i));
		return delivered;
	}

	@Override
	public int localPort()
	{
		try
		{
			return ((InetSocketAddress) channel.getLocalAddress()).getPort();
		}
		catch (IOException e)
		{
			return 0;
		}
	}

	@Override
	public int dropped()
	{
		return dropped;
	}

	@Override
	public void close()
	{
		try
		{
			channel.close();
		}
		catch (IOException e)
		{
			// Closing a socket that is already gone is not a problem anyone can act on
		}
	}

	/** The text form this transport's addresses take, and the only place that shape is decided. */
	static String textOf(InetSocketAddress endpoint)
	{
		String host = endpoint.getAddress() != null
				? endpoint.getAddress().getHostAddress()
				: endpoint.getHostString();
		if (host.indexOf(':') >= 0)
			host = "[" + host + "]"; // an IPv6 literal, or the port cannot be told from the address
		return host + ":" + endpoint.getPort();
	}

	/** An ip:port, compared by text so the same endpoint is the same key in a host's player map. */
	static class Udp_Peer implements Net_Peer
	{
		final String address;
		final InetSocketAddress endpoint;

		Udp_Peer(String address)
		{
			this.address = address;
			int colon = address.lastIndexOf(':');
			if (colon < 0)
				throw new IllegalArgumentException("not a host:port address : " + address);
			String host = address.substring(0, colon);
			if (host.startsWith("[") && host.endsWith("]"))
				host = host.substring(1, host.length() - 1);
			this.endpoint = new InetSocketAddress(host, Integer.parseInt(address.substring(colon + 1)));
		}

		@Override
		public String address()
		{
			return address;
		}

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Udp_Peer && ((Udp_Peer) other).address.equals(address);
		}

		@Override
		public int hashCode()
		{
			return address.hashCode();
		}

		@Override
		public String toString()
		{
			return "udp " + address;
		}
	}
}
