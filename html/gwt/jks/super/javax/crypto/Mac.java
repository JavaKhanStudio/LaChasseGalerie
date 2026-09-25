package javax.crypto;

import java.security.Key;
import java.security.NoSuchAlgorithmException;

/**
 * Only so core's Turn_Codec translates : a tab never speaks TURN itself, its RTCPeerConnection does
 * (docs/browser-target.md section 11). A browser build that reaches this has a bug, and says so.
 */
public final class Mac
{
	public static Mac getInstance(String algorithm) throws NoSuchAlgorithmException
	{throw new NoSuchAlgorithmException(algorithm + " in a browser tab : TURN is the RTCPeerConnection's job");}

	private Mac()
	{}

	public void init(Key key)
	{}

	public void update(byte[] input, int offset, int length)
	{}

	public byte[] doFinal()
	{return null;}
}
