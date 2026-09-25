package java.security;

import java.util.Random;

/**
 * GWT emulates no SecureRandom. The game needs one for a rejoin key (ClientSession.newKey), so a tab
 * draws its bits from the browser's own crypto.getRandomValues, which is what SecureRandom is for.
 */
public class SecureRandom extends Random
{
	public SecureRandom()
	{}

	@Override
	protected int next(int bits)
	{return random32() >>> (32 - bits);}

	@Override
	public void nextBytes(byte[] bytes)
	{
		for (int i = 0; i < bytes.length; i++)
			bytes[i] = (byte) random32();
	}

	private static native int random32()
	/*-{
		var word = new $wnd.Int32Array(1);
		$wnd.crypto.getRandomValues(word);
		return word[0];
	}-*/;
}
