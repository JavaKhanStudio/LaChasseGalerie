package jks.input;

import java.util.HashMap;
import java.util.TreeMap;

import com.badlogic.gdx.controllers.Controller;

import jks.vars.GVars_Heart;

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
	private static int nextNumber = 1 ;
	
	public static void init()
	{
		playerList = new TreeMap<PlayerId,Player_Inputs>() ;
		localDevices = new HashMap<Controller,PlayerId>() ;
		numberFromOne() ;
	}
	
	/** Nobody is playing, and the next run numbers its players from 1 again. */
	public static void dispose()
	{
		playerList = null ;
		localDevices = null ;
		numberFromOne() ;
	}
	
	/**
	 * Except behind a host (d14) : its seats keep their PlayerIds from one run to the next, so a run
	 * after the score screen goes on numbering where the last one stopped. From 1, this window's
	 * keyboard would take the number of a remote player still seated.
	 */
	private static void numberFromOne()
	{
		if(!GVars_Heart.hosting)
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
			player = newPlayer() ;
			localDevices.put(device, player) ;
		}
		return player ;
	}
	
	/**
	 * A player nobody has been yet, with no device behind it : what a host hands a peer (phase 1.4).
	 * Local devices mint theirs here too, so a host that also plays and the peers it lets in share one
	 * numbering, and a host with no local player at all is just a host that never called identify.
	 */
	public static PlayerId newPlayer()
	{return PlayerId.of(nextNumber ++) ;}
	
	
	
	public static void act(float delta)
	{
		for(Player_Inputs player : playerList.values())
		{player.act(delta);}
	}
}
