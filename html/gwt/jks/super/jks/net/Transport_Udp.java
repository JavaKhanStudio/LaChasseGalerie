package jks.net;

import java.nio.ByteBuffer;
import java.util.function.LongSupplier;

/**
 * The browser's Transport_Udp : a GWT super-source stand-in for core/src/jks/net/Transport_Udp.java,
 * which is a DatagramChannel and cannot be translated (html/gwt/jks/GdxDefinition.gwt.xml).
 *
 * It keeps the signatures the game names, so core compiles to JavaScript unchanged, and it refuses
 * to open : a tab has no UDP socket. Its transport is an RTCDataChannel, the next phase (r82) ;
 * until then, hosting, joining and the online lobby fail with this message in a tab.
 */
public class Transport_Udp implements Net_Transport
{
	public static final int MAX_RELAYED_DATAGRAM = MAX_PAYLOAD + Turn_Codec.OVERHEAD + 8;

	public static Transport_Udp open()
	{return open(0);}

	public static Transport_Udp open(int port)
	{return open(port, Net_PeerTable.MONOTONIC, Net_PeerTable.DEFAULT_TIMEOUT_MS);}

	public static Transport_Udp open(int port, LongSupplier clock, long timeoutMs)
	{throw new UnsupportedOperationException("a browser tab has no UDP socket (port " + port + ")");}

	private Transport_Udp()
	{}

	@Override
	public Net_Peer resolve(String address)
	{throw new UnsupportedOperationException();}

	@Override
	public void send(Net_Peer peer, ByteBuffer payload)
	{throw new UnsupportedOperationException();}

	@Override
	public int pump(Net_Listener listener)
	{return 0;}

	@Override
	public int localPort()
	{return -1;}

	@Override
	public int dropped()
	{return 0;}

	@Override
	public void close()
	{}
}
