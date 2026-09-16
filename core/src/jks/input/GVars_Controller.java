package jks.input;

import java.util.HashMap;
import java.util.TreeMap;

import com.badlogic.gdx.controllers.Controller;

/**
 * The run's roster : who is playing, and what this machine's devices have to do with them.
 *
 * Two maps, and the split is the point (phase 0.3). playerList is about PLAYERS — a PlayerId to
 * the inputs driving their hero — and a remote player will sit in it exactly like a local one.
 * localDevices is about THIS MACHINE's hardware — a Controller (null for the keyboard) to the
 * player it drives — and it is the only place in the game where a device is a key.
 *
 * A device keeps its player across a death. Dying costs you your hero, not who you are : that is
 * what makes the score and the death count still follow the same pad after it rejoins.
 */
public class GVars_Controller 
{
	/** Sorted, not hashed : a seeded replay and a future snapshot must not depend on a hash. */
	public static TreeMap<PlayerId,Player_Inputs> playerList ;
	/** Local hardware only, null being the keyboard. Offline co-op lives here (d5 -> A). */
	private static HashMap<Controller,PlayerId> localDevices ;
	private static int nextNumber ;
	
	public static void init()
	{
		playerList = new TreeMap<PlayerId,Player_Inputs>() ;
		localDevices = new HashMap<Controller,PlayerId>() ;
		nextNumber = 1 ;
	}
	
	public static Player_Inputs getPlayer(PlayerId player)
	{return player == null ? null : playerList.get(player) ;}
	
	/**
	 * The player this machine's device is driving, or null when it has not joined yet — the one
	 * question the input edge asks. A device that joined and then died answers null again.
	 */
	public static Player_Inputs getLocalPlayer(Controller device)
	{return getPlayer(localDevices.get(device)) ;}
	
	/** The player this device is, minting one the first time it is touched. Null is the keyboard. */
	public static PlayerId identify(Controller device)
	{
		PlayerId player = localDevices.get(device) ;
		if(player == null)
		{
			player = PlayerId.of(nextNumber ++) ;
			localDevices.put(device, player) ;
		}
		return player ;
	}
	
	
	
	public static void act(float delta)
	{
		for(Player_Inputs player : playerList.values())
		{player.act(delta);}
	}
}
