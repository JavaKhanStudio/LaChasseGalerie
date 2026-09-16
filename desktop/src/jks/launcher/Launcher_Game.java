package jks.launcher;

import java.util.Arrays;
import java.util.List;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import jks.amain.Main_Game;
import jks.debug.GVars_Debug;
import jks.launcher.settings.Utils_Launcher;
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
 */
public class Launcher_Game 
{

	public static void main (String[] arg) 
	{
		List<String> args = Arrays.asList(arg) ; 
		
		GVars_Audio.muted = args.contains("--mute") ;
		GVars_Heart.startAtMenu = args.contains("--menu") ;
		GVars_Debug.setInFullDebug(args.contains("--debug"));
		
		Utils_Launcher.preferX11OnLinux();
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
		Utils_Launcher.basicConfig(config) ; 
		
		if(args.contains("--fullscreen"))
			Utils_Launcher.setFullScreen(config);
		else
			Utils_Launcher.setWindowed(config);
		
		new Lwjgl3Application(new Main_Game(), config);
	}
	
}
