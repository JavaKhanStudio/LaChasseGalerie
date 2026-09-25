package jks.vars;

import jks.camera.GVars_Camera;
import jks.input.GVars_Controller;
import jks.lobby.Lobby_Client;
import jks.net.Net_Transport;
import jks.parralax.GVars_Parralax;
import jks.personnage.index.Index_Sprite;
import jks.physic.Gvars_Physic;
import jks.sounds.GVars_AudioManager;
import jks.tools.Enum_Timming;
import jks.tools.GlobalTimmer;
import jks.vinterface.GVars_Interface;
import jks.vue.AVue_Model;

public class GVars_Heart 
{
	
	public static AVue_Model vue;
	/** --menu : open on the start menu instead of dropping straight into a run. */
	public static boolean startAtMenu;
	/** --host [port] : this window hosts, and lets clients in on this UDP port. -1 : not hosting. */
	public static int hostPort = -1;
	/** --join host:port : this window is a client of that host, and simulates nothing. */
	public static String joinAddress;
	/**
	 * A HostSession has the game behind it (Game_Simulation sets it). Its runs follow each other : a
	 * run whose song is over waits on the score screen for this window's player to start the next one
	 * or close the server (d14), which sets it back to false. Nothing else of hosting is decided here.
	 */
	public static boolean hosting;
	/**
	 * --lobby host:port : where the lobby service answers (phase 2.1). Nobody runs one for players yet (r45
	 * deploys it) : until then this is a service on this machine, as `./gradlew :lobby:run` starts it.
	 */
	public static String lobbyService = "127.0.0.1:7770";
	/**
	 * A lobby this window opened to host from Online play (r43), handed to Main_Game when its host picks
	 * Start : Main_Game builds its HostSession on the lobby's game view, keeps the lobby open through the
	 * runs so a latecomer can still find it, and closes both with the server. Null otherwise.
	 */
	public static Lobby_Host lobbyHost;

	/** A hosted lobby and the one socket it and the game share. */
	public static final class Lobby_Host
	{
		public final Lobby_Client lobby;
		public final Net_Transport socket;

		public Lobby_Host(Lobby_Client lobby, Net_Transport socket)
		{
			this.lobby = lobby;
			this.socket = socket;
		}
	}

	/** How many runs this JVM has started : a snapshot's run number, so a client knows a new one began (d14). */
	public static int runsStarted;

	public static void init() 
	{
		loadAssets() ; 
		GVars_Parralax.init() ; 
		Gvars_Physic.init(); 
		GVars_Camera.init();
		GVars_Interface.init();
		GVars_Controller.init();
		
		GlobalTimmer.purge() ;
	}
	
	/**
	 * Undoes init for everything a run owns, so the next init starts from nothing (phase 0.4). What
	 * outlives a run on purpose : the sprite atlases, the skin and the river, which the menu shows too.
	 */
	public static void dispose()
	{
		Gvars_Physic.dispose();
		GVars_Camera.dispose();
		GVars_Interface.dispose();
		GVars_Controller.dispose();
	}
 
	
	public static void loadAssets()
	{
		GlobalTimmer.registerTime(Enum_Timming.ASSETS);
		
		Index_Sprite.init();
		GlobalTimmer.getElapse(Enum_Timming.ASSETS, "Sprite", true);
	}
	
	public static void changeVue(AVue_Model View) 
	{
		GVars_AudioManager.StopAndDisposeMusic();
		if(vue != null)
			vue.dispose();
		vue = View;
		vue.init();
	}

}
