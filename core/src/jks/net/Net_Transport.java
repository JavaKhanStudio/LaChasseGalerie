package jks.net;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

/**
 * Moves opaque bytes between this machine and its peers, unreliably.
 *
 * THIS INTERFACE IS THE POINT. docs/online-multiplayer.md phase 1 : everything above it - the
 * protocol, HostSession, ClientSession - talks to this and never to a DatagramChannel, because a
 * browser tab cannot open a UDP socket and d7 says a tab is a player. {@link Transport_Udp} is
 * behind it for desktop peers, {@link Net_Loopback} for tests, and a browser tab's WebRTC data channel
 * (jks.rtc.Transport_Rtc in the rtc module, r46) without a line of session code changing. Spreading socket calls across the session code
 * is the one mistake here that costs a phase to undo.
 *
 * The contract, and the protocol above it must survive all of it :
 * <ul>
 * <li>a packet may be lost, duplicated or arrive out of order - never truncated ;</li>
 * <li>a payload is at most {@link #MAX_PAYLOAD} bytes, or send refuses it - the one exception is the
 *     lobby service's WebSocket front (jks.lobby.Transport_Ws, r79), where a message is not a datagram
 *     and a whole WebRTC description may pass ;</li>
 * <li>nothing blocks : send returns when the packet is handed to the OS, pump when the arrived
 *     packets have been delivered ;</li>
 * <li>a peer that stops answering is reported lost, once, and forgotten.</li>
 * </ul>
 *
 * One transport owns ONE socket, and it is the socket the lobby is talked to as well : the public
 * ip:port the lobby service sees is then free STUN and is exactly the mapping game traffic will
 * arrive on. A second socket for lobby chatter advertises an endpoint that does not work, and it
 * fails only for other people.
 *
 * Not thread safe. Pump it from the thread that runs the loop.
 */
public interface Net_Transport extends AutoCloseable
{
	/**
	 * The biggest payload that survives the internet in one piece. A UDP datagram larger than the
	 * path MTU is fragmented by IP, and one lost fragment loses the whole packet ; 1200 leaves
	 * room for the IPv6, UDP and any tunnel headers under the usual 1280-1500 byte path.
	 */
	int MAX_PAYLOAD = 1200;

	/**
	 * The peer that this transport's text address names - "203.0.113.7:7777" for UDP. Resolving
	 * does not send anything and does not prove the peer exists : it starts the timeout, so a
	 * host that never answers is reported {@link Net_Listener#lost} like any other.
	 */
	Net_Peer resolve(String address);

	/**
	 * Sends the buffer's remaining bytes to the peer, best effort. Consumes the buffer.
	 *
	 * @throws IllegalArgumentException if more than {@link #MAX_PAYLOAD} bytes remain
	 */
	void send(Net_Peer peer, ByteBuffer payload);

	/**
	 * Delivers everything that arrived since the last call, then reports the peers that timed out.
	 * Returns the number of packets delivered, so a caller can tell silence from work.
	 */
	int pump(Net_Listener listener);

	/** The local port this transport is bound to, or 0 when the transport has no port (loopback). */
	int localPort();

	/**
	 * The addresses this transport can be reached at WITHOUT anybody's help, in its own text, best
	 * first : a global IPv6 address and a LAN IPv4 address, with the bound port. What ICE (r42) offers
	 * another player besides the mapping the lobby service sees. Never the loopback, never a name
	 * lookup. Empty when there is nothing to offer, or nothing to learn it from (a tab : WebRTC gathers
	 * its own).
	 */
	default List<String> localAddresses()
	{
		return Collections.emptyList();
	}

	/** How many packets this transport itself threw away : oversized, malformed, or the OS refused. */
	int dropped();

	@Override
	void close();
}
