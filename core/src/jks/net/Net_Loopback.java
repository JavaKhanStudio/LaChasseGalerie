package jks.net;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A wire with no operating system on it : transports opened from the same Net_Loopback deliver to
 * each other in memory.
 *
 * It exists for two reasons. A test of the protocol should fail because the protocol is wrong,
 * not because a port was busy or a packet took 3 ms ; and the nasty part of the internet - loss,
 * reordering, duplicates - is something you have to be able to TURN ON to find out whether the
 * code above survives it. Everything here is seeded, so a failure replays.
 *
 * The clock is virtual : nothing times out until {@link #advance} is called, and then it happens
 * at once. A test watching a peer die does not have to wait ten seconds for it.
 *
 * Latency is modelled off the same clock (phase 1.4) : a packet is due {@link #latencyMs} after it
 * was sent, plus up to {@link #jitterMs}, and pump delivers only what is due. Jitter reorders on its
 * own, the way the internet does : a packet that drew less of it overtakes one sent before it.
 */
public class Net_Loopback
{
	/** 0 to 1 : the share of packets thrown away, as the internet does between a host and a friend. */
	public float loss;
	/** 0 to 1 : the share of packets that jump ahead of one already queued. UDP does not promise order. */
	public float reorder;
	/** 0 to 1 : the share of packets delivered twice. Rare in the wild, fatal to code that assumes it cannot happen. */
	public float duplicate;
	/** Milliseconds between a send and the pump that may deliver it. 0 : the next pump. */
	public int latencyMs;
	/** Up to this many milliseconds more, drawn per packet. */
	public int jitterMs;

	final Map<String, Wired> wires = new LinkedHashMap<String, Wired>();
	final Random random;
	long now;
	public int sent, lost, delivered;

	public Net_Loopback()
	{
		this(1);
	}

	public Net_Loopback(long seed)
	{
		this.random = new Random(seed);
	}

	/** A transport on this wire. The name is its address : peers resolve each other by it. */
	public Net_Transport open(String name)
	{
		if (wires.containsKey(name))
			throw new IllegalStateException("already a transport named " + name + " on this wire");
		Wired wire = new Wired(name, Net_PeerTable.DEFAULT_TIMEOUT_MS);
		wires.put(name, wire);
		return wire;
	}

	/** Moves the virtual clock, which is the only thing that makes a silent peer time out. */
	public void advance(long millis)
	{
		now += millis;
	}

	/** Draws from the seeded stream only when jitter is on, so a wire without it replays as it did before. */
	long due()
	{
		return now + latencyMs + (jitterMs > 0 ? random.nextInt(jitterMs + 1) : 0);
	}

	boolean roll(float chance)
	{
		return chance > 0 && random.nextFloat() < chance;
	}

	class Wired implements Net_Transport
	{
		final String name;
		final Net_PeerTable table;
		final Deque<Packet> inbox = new ArrayDeque<Packet>();
		boolean open = true;
		int dropped;

		Wired(String name, long timeoutMs)
		{
			this.name = name;
			this.table = new Net_PeerTable(() -> now, timeoutMs);
		}

		@Override
		public Net_Peer resolve(String address)
		{
			return table.peer(address, Wire_Peer::new);
		}

		@Override
		public void send(Net_Peer peer, ByteBuffer payload)
		{
			int size = payload.remaining();
			if (size > MAX_PAYLOAD)
				throw new IllegalArgumentException(size + " bytes is past MAX_PAYLOAD " + MAX_PAYLOAD);

			byte[] bytes = new byte[size];
			payload.get(bytes);
			sent++;

			Wired target = wires.get(peer.address());
			// A closed or unknown end is not an error : the packet goes nowhere and the peer times out
			if (target == null || !target.open || roll(loss))
			{
				lost++;
				return;
			}

			target.accept(new Packet(name, bytes, due()));
			if (roll(duplicate))
				target.accept(new Packet(name, bytes, due()));
		}

		void accept(Packet packet)
		{
			if (!inbox.isEmpty() && roll(reorder))
				inbox.addFirst(packet); // ahead of one that was sent before it
			else
				inbox.addLast(packet);
		}

		@Override
		public int pump(Net_Listener listener)
		{
			int count = 0;
			// Only what is due, in queue order ; what is not due yet keeps its place
			List<Packet> due = new ArrayList<Packet>();
			for (Iterator<Packet> it = inbox.iterator(); it.hasNext();)
			{
				Packet packet = it.next();
				if (packet.due <= now)
				{
					due.add(packet);
					it.remove();
				}
			}
			for (Packet packet : due)
			{
				// The listener may close this end while it reads
				if (!open)
					break;
				Net_Peer peer = table.peer(packet.from, Wire_Peer::new);
				table.heard(packet.from);
				count++;
				delivered++;
				listener.received(peer, ByteBuffer.wrap(packet.bytes));
			}

			List<Net_Peer> gone = table.takeLost();
			for (int i = 0; i < gone.size(); i++)
				listener.lost(gone.get(i));
			return count;
		}

		@Override
		public int localPort()
		{
			return 0;
		}

		@Override
		public int dropped()
		{
			return dropped;
		}

		@Override
		public void close()
		{
			open = false;
			inbox.clear();
			wires.remove(name);
		}

		@Override
		public String toString()
		{
			return "loopback " + name;
		}
	}

	static class Packet
	{
		final String from;
		final byte[] bytes;
		final long due;

		Packet(String from, byte[] bytes, long due)
		{
			this.from = from;
			this.bytes = bytes;
			this.due = due;
		}
	}

	static class Wire_Peer implements Net_Peer
	{
		final String address;

		Wire_Peer(String address)
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
			return other instanceof Wire_Peer && ((Wire_Peer) other).address.equals(address);
		}

		@Override
		public int hashCode()
		{
			return address.hashCode();
		}

		@Override
		public String toString()
		{
			return "loopback " + address;
		}
	}
}
