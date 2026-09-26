package jks.vars;

import jks.camera.GVars_Camera;
import jks.input.GVars_Controller;
import jks.lobby.Lobby_Client;
import jks.net.Net_Tabs;
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
	 * --lobby host:port : where the lobby service answers (phase 2.1). By default VPS_1's, where r45 deployed
	 * it beside the relay (Simon on r78 : A), so Online play works with no flag. Probes and tests pass a local
	 * service (`./gradlew :lobby:run` is 127.0.0.1:7770) and never rely on this default.
	 */
	public static String lobbyService = "141.94.115.201:7770";
	/**
	 * --report [name] (r89) : Online play sends what it sees - NAT verdict, each row's route - to the lobby
	 * service's log under this name (Lobby_Client.reportAs). The gate build's launchers pass it ; null, none sent.
	 */
	public static String reportAs;
	/**
	 * A lobby this window opened to host from Online play (r43), handed to Main_Game when its host picks
	 * Start : Main_Game builds its HostSession on the lobby's game view, keeps the lobby open through the
	 * runs so a latecomer can still find it, and closes both with the server. Null otherwise.
	 */
	public static Lobby_Host lobbyHost;
	/**
	 * How a hosting window takes browser tabs (r80) : the desktop launcher sets it to open jks.rtc's
	 * Transport_Rtc, which core cannot name. Null, or a supplier giving null (this machine's WebRTC did not
	 * load), and the lobby hosts desktop players only : a tab's offer goes unanswered.
	 */
	public static java.util.function.Supplier<Net_Tabs> tabs;
	/**
	 * Online play in a browser tab (r82) : html's launcher sets it to open a WebSocket to the service's front
	 * and an RTCDataChannel transport, which core cannot name. Set, and Vue_Lobby is a player's lobby only :
	 * Open games and a code, no Host a game (d7). Null on a desktop.
	 */
	public static java.util.function.Supplier<jks.lobby.Lobby_Tab> tabLobby;
	/**
	 * A browser tab's touch (r87) : html's launcher sets it. Whether the page is under a finger, which
	 * shows Vue_Client's Touch_Pad, and the phone's soft keyboard for Vue_Lobby's code. Null on a desktop.
	 */
	public static jks.input.Tab_Touch touch;

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
