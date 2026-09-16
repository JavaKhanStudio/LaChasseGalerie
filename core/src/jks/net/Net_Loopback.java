package jks.net;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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
 *
 * Routers are modelled too (phase 2.2, r42), because hole punching is only proven against the NATs
 * that break it : {@link #nat} puts a router with a public ip on the wire, and a transport opened
 * behind it gets its source rewritten and its arrivals filtered the way that kind of NAT does ;
 * {@link #ipv6} gives a transport a second, un-NATed address behind a stateful firewall. Name such
 * transports "ip:port" so the addresses read like the internet's.
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
	/** Milliseconds more for a packet to an IPv6 address : a longer route, so an IPv4 answer can come back first. */
	public int ipv6LatencyMs;

	final Map<String, Wired> wires = new LinkedHashMap<String, Wired>();
	final Map<String, Nat> nats = new HashMap<String, Nat>();
	final Map<String, Wired> ipv6Ends = new HashMap<String, Wired>();
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

	/** A transport behind a router : what it sends leaves from the router's ip, what arrives is filtered by it. */
	public Net_Transport open(String name, Nat nat)
	{
		Wired wire = (Wired) open(name);
		wire.nat = nat;
		return wire;
	}

	/** A router on this wire, owning every "publicIp:port". */
	public Nat nat(String publicIp, Nat.Kind kind)
	{
		Nat nat = new Nat(publicIp, kind);
		nats.put(publicIp, nat);
		return nat;
	}

	/**
	 * Gives a transport of this wire a global IPv6 address too : packets to an IPv6 address leave from it
	 * with no translation. A firewalled one lets in only what answers something it sent, as home routers do.
	 */
	public void ipv6(Net_Transport end, String address, boolean firewalled)
	{
		Wired wire = (Wired) end;
		wire.ipv6 = address;
		wire.ipv6Firewalled = firewalled;
		ipv6Ends.put(address, wire);
	}

	static String ipOf(String address)
	{
		int colon = address.lastIndexOf(':');
		return colon < 0 ? address : address.substring(0, colon);
	}

	/**
	 * A home or carrier router, as far as punching cares (RFC 4787) : how it MAPS an inside address to a
	 * public port, and what it FILTERS on the way in.
	 */
	public static final class Nat
	{
		public enum Kind
		{
			/** One port per inside address ; anybody may send to it. */
			FULL_CONE,
			/** One port per inside address ; only an ip it sent to may answer. */
			RESTRICTED,
			/** One port per inside address ; only the ip:port it sent to may answer. */
			PORT_RESTRICTED,
			/** A new port for every destination ; only that ip:port may answer. Mobile data and carrier NAT behave like this. */
			SYMMETRIC
		}

		public final String publicIp;
		public final Kind kind;
		/** Ports handed out one after the other, as most routers do, unless this is set : then at random. */
		public boolean randomPorts;
		Random random = new Random(7);
		int nextPort = 40000;
		final Map<String, Integer> mappings = new HashMap<String, Integer>();
		final Map<Integer, String> insides = new HashMap<Integer, String>();
		final Map<Integer, Set<String>> allowed = new HashMap<Integer, Set<String>>();

		Nat(String publicIp, Kind kind)
		{
			this.publicIp = publicIp;
			this.kind = kind;
		}

		/** The public address a packet from inside to this destination leaves from, and the permission it opens. */
		String outbound(String inside, String destination)
		{
			String key = kind == Kind.SYMMETRIC ? inside + ">" + destination : inside;
			Integer port = mappings.get(key);
			if (port == null)
			{
				do
					port = Integer.valueOf(randomPorts ? 1024 + random.nextInt(64000) : nextPort++);
				while (insides.containsKey(port));
				mappings.put(key, port);
				insides.put(port, inside);
				allowed.put(port, new HashSet<String>());
			}
			allowed.get(port).add(kind == Kind.RESTRICTED ? ipOf(destination) : destination);
			return publicIp + ":" + port;
		}

		/** The inside address a packet to this public address goes to, or null when the router drops it. */
		String inbound(String publicAddress, String from)
		{
			int colon = publicAddress.lastIndexOf(':');
			Integer port;
			try
			{
				port = Integer.valueOf(publicAddress.substring(colon + 1));
			}
			catch (NumberFormatException e)
			{
				return null;
			}
			String inside = insides.get(port);
			if (inside == null)
				return null;
			if (kind == Kind.FULL_CONE)
				return inside;
			return allowed.get(port).contains(kind == Kind.RESTRICTED ? ipOf(from) : from) ? inside : null;
		}

		/** Every mapping forgotten at once : they expired, or the router restarted, or the player moved networks. */
		public void reset()
		{
			mappings.clear();
			insides.clear();
			allowed.clear();
		}

		/** How many public ports are open now. */
		public int mappings()
		{
			return insides.size();
		}
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
		Nat nat;
		String ipv6;
		boolean ipv6Firewalled;
		final Set<String> ipv6Allowed = new HashSet<String>();

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

			String to = peer.address();
			String from = name;
			Wired neighbour = wires.get(to);
			if (ipv6 != null && to.startsWith("["))
			{
				from = ipv6;
				ipv6Allowed.add(to);
			}
			else if (nat != null && (neighbour == null || neighbour.nat != nat))
				from = nat.outbound(name, to);

			Wired target = arrival(to, from);
			// A closed or unknown end, or a router's no, is not an error : the packet goes nowhere and the peer times out
			if (target == null || !target.open || roll(loss))
			{
				lost++;
				return;
			}

			int extra = to.startsWith("[") ? ipv6LatencyMs : 0;
			target.accept(new Packet(from, bytes, due() + extra));
			if (roll(duplicate))
				target.accept(new Packet(from, bytes, due() + extra));
		}

		/** Who gets a packet sent to this address from that one, past any router ; null for nobody. */
		Wired arrival(String to, String from)
		{
			Wired direct = wires.get(to);
			if (direct != null)
				return direct.nat == null || direct.nat == nat ? direct : null; // an inside address is reached from its own LAN only
			Wired v6 = ipv6Ends.get(to);
			if (v6 != null)
				return !v6.ipv6Firewalled || v6.ipv6Allowed.contains(from) ? v6 : null;
			Nat router = nats.get(ipOf(to));
			String inside = router == null ? null : router.inbound(to, from);
			return inside == null ? null : wires.get(inside);
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

		/** Its IPv6 address, then its own name when that reads as an ip:port : the LAN address, behind a router. */
		@Override
		public List<String> localAddresses()
		{
			List<String> addresses = new ArrayList<String>();
			if (ipv6 != null)
				addresses.add(ipv6);
			if (Stun_Codec.ipOf(name) != null)
				addresses.add(name);
			return Collections.unmodifiableList(addresses);
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
			if (ipv6 != null)
				ipv6Ends.remove(ipv6);
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
