package jks.net;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Who a transport is talking to, and when each of them was last heard from.
 *
 * A NAT mapping dies after about 30 s of silence, so a peer that has said nothing for a while is
 * either gone or about to become unreachable either way : both are "lost" to the game. The
 * session above is what keeps a live peer alive, by sending a keepalive well inside the timeout.
 *
 * The clock is injected because a test cannot wait ten seconds to watch a timeout happen.
 */
class Net_PeerTable
{
	/** Longer than a keepalive interval of 5-15 s would be useless : a quiet peer must still count as here. */
	static final long DEFAULT_TIMEOUT_MS = 10_000;

	static final LongSupplier MONOTONIC = () -> System.nanoTime() / 1_000_000L;

	final Map<String, Net_Peer> peers = new LinkedHashMap<String, Net_Peer>();
	final Map<String, Long> lastHeard = new LinkedHashMap<String, Long>();
	final LongSupplier clock;
	final long timeoutMs;

	Net_PeerTable(LongSupplier clock, long timeoutMs)
	{
		this.clock = clock;
		this.timeoutMs = timeoutMs;
	}

	/** The peer for this address, created by the transport's factory the first time it is seen. */
	Net_Peer peer(String address, Function<String, Net_Peer> factory)
	{
		Net_Peer peer = peers.get(address);
		if (peer == null)
		{
			peer = factory.apply(address);
			peers.put(address, peer);
			lastHeard.put(address, Long.valueOf(clock.getAsLong()));
		}
		return peer;
	}

	/** A packet came from this peer : it is alive, and the timeout starts again. */
	void heard(String address)
	{
		if (peers.containsKey(address))
			lastHeard.put(address, Long.valueOf(clock.getAsLong()));
	}

	boolean knows(String address)
	{
		return peers.containsKey(address);
	}

	/** Removes and returns every peer silent for longer than the timeout. Reported once : they are gone from the table. */
	List<Net_Peer> takeLost()
	{
		List<Net_Peer> lost = new ArrayList<Net_Peer>();
		long now = clock.getAsLong();
		Iterator<Map.Entry<String, Long>> it = lastHeard.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<String, Long> entry = it.next();
			if (now - entry.getValue().longValue() < timeoutMs)
				continue;
			lost.add(peers.remove(entry.getKey()));
			it.remove();
		}
		return lost;
	}

	/** Drops a peer without reporting it : its own end said it was leaving. */
	Net_Peer forget(String address)
	{
		lastHeard.remove(address);
		return peers.remove(address);
	}
}
