package jks.launcher;

import java.util.Arrays;
import java.util.List;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import jks.amain.Main_Game;
import jks.debug.GVars_Debug;
import jks.lobby.Lobby_Ice;
import jks.launcher.settings.Utils_Launcher;
import jks.rtc.Transport_Rtc;
import jks.sounds.GVars_Audio;
import jks.vars.GVars_Heart;

/**
 * Desktop entry point.
 * 
 * Arguments :
 *   --fullscreen  play fullscreen on the primary monitor (default is a 1280x720 window)
 *   --mute        start without music
 *   --debug       draw Box2D collision shapes and print debug information
 *   --menu        open on the start menu instead of starting a run right away
 *   --host [port] host the run for clients on this UDP port (default 7777), and play in it too
 *   --join host:port  be a client of that host : draw its run, send it this machine's buttons
 *   --lobby host:port the lobby service Online play talks to (default VPS_1's, 141.94.115.201:7770)
 */
public class Launcher_Game 
{
	/** Where --host listens when no port is given. Phase 2's lobby replaces fixed ports for players. */
	static final int defaultPort = 7777 ;

	public static void main (String[] arg) 
	{
		List<String> args = Arrays.asList(arg) ; 
		
		GVars_Audio.muted = args.contains("--mute") ;
		GVars_Heart.startAtMenu = args.contains("--menu") ;
		GVars_Debug.setInFullDebug(args.contains("--debug"));
		
		int host = args.indexOf("--host") ;
		if(host >= 0)
			GVars_Heart.hostPort = host + 1 < args.size() && args.get(host + 1).matches("\\d+") ? Integer.parseInt(args.get(host + 1)) : defaultPort ;
		int lobby = args.indexOf("--lobby") ;
		if(lobby >= 0)
		{
			if(lobby + 1 >= args.size())
				throw new IllegalArgumentException("--lobby needs the service's address, as host:port") ;
			GVars_Heart.lobbyService = args.get(lobby + 1) ;
		}
		int join = args.indexOf("--join") ;
		if(join >= 0)
		{
			if(join + 1 >= args.size())
				throw new IllegalArgumentException("--join needs the host's address, as host:port") ;
			GVars_Heart.joinAddress = args.get(join + 1) ;
		}
		
		// A lobby this window hosts takes browser tabs too (r80), when libwebrtc loads here
		GVars_Heart.tabs = () -> Transport_Rtc.open(Transport_Rtc.stun(Lobby_Ice.STUN_SERVERS)) ;
		
		Utils_Launcher.preferX11OnLinux();
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
		Utils_Launcher.basicConfig(config) ; 
		
		if(args.contains("--fullscreen"))
			Utils_Launcher.setFullScreen(config);
		else
			Utils_Launcher.setWindowed(config);
		
		if(GVars_Heart.joinAddress != null)
			config.setTitle("La chasse galerie - client of " + GVars_Heart.joinAddress);
		else if(GVars_Heart.hostPort >= 0)
			config.setTitle("La chasse galerie - host on port " + GVars_Heart.hostPort);
		
		new Lwjgl3Application(new Main_Game(), config);
	}
	
}
