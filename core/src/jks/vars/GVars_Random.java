package jks.vars;

import java.util.Random;

/** The game's only source of randomness: every roll draws from this one stream, so a seed replays a run
 *  and a host can hand its seed to clients at start. Under host authority only the host rolls. */
public class GVars_Random 
{
	private static long seed = System.nanoTime() ; 
	public static final Random random = new Random(seed) ; 
	
	public static void seed(long newSeed)
	{
		seed = newSeed ; 
		random.setSeed(newSeed) ; 
	}
	
	public static long seed()
	{
		return seed ; 
	}
}
