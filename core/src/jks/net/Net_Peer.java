package jks.net;

/**
 * The other end of a connection, as the game sees it : a name to send bytes to, nothing more.
 *
 * It is deliberately NOT an address. A desktop peer is an ip:port, a browser peer (d7) is a
 * WebRTC data channel the lobby put us in touch with, and session code must be able to hold
 * either without knowing which. {@link #address()} is transport-specific text, for logs and for
 * the lobby to mirror : read it, print it, hand it back to the same transport, never parse it
 * anywhere else.
 *
 * Implementations MUST define equals and hashCode on the address, because a host keeps its
 * players in a map keyed on the peer.
 */
public interface Net_Peer
{
	/** Transport-specific text, unique within one transport : "127.0.0.1:41234", a channel id. */
	String address();
}
