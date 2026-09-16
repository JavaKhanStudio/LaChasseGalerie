package jks.net;

import java.nio.ByteBuffer;

/** What a {@link Net_Transport} tells its owner, during {@link Net_Transport#pump}. */
public interface Net_Listener
{
	/**
	 * A packet arrived. The buffer is the transport's own and is REUSED as soon as this returns :
	 * read it now, or copy it. Its bytes are exactly what the sender passed to send, or the
	 * packet is not delivered at all - there is no partial delivery and no reassembly.
	 *
	 * The peer may be one never seen before : that is how a host learns a client exists, and it
	 * is the same packet that opened the NAT mapping we are allowed to answer on.
	 */
	void received(Net_Peer from, ByteBuffer payload);

	/**
	 * Nothing has been heard from this peer for the transport's timeout, or its end closed.
	 * The transport has forgotten it ; a later packet from the same address is a new peer.
	 */
	default void lost(Net_Peer peer) {}
}
