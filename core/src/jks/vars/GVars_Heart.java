package jks.vars;

import java.util.Random;

import com.badlogic.gdx.assets.AssetManager;

import jks.debug.GVars_Debug;
import jks.sounds.GVars_AudioManager;
import jks.tools.Enum_Timming;
import jks.tools.GlobalTimmer;
import jks.vinterface.GVars_Interface;
import jks.vue.AVue_Model;

public class GVars_Heart 
{
	
	public static boolean debug = true;
	public static boolean isPaused = false ; 
	public static AVue_Model vue;
	public static final Random random = new Random();
	public static AssetManager assetManager = new AssetManager();

	public static void init() 
	{
		if(GVars_Debug.coreInformationDebug)
			GlobalTimmer.registerTime(Enum_Timming.WHOLE);
		if(GVars_Debug.coreInformationDebug)
			GlobalTimmer.registerTime(Enum_Timming.INSTANCE);
		
		loadAssets() ; 
		if(GVars_Debug.coreInformationDebug)
			GlobalTimmer.getElapse(Enum_Timming.INSTANCE, "Load assets", true);
	
		if(GVars_Debug.debugMode)
			GVars_Debug.setInFullDebug(true) ;
		
		if(GVars_Debug.coreInformationDebug)
			GlobalTimmer.getElapse(Enum_Timming.WHOLE, "", true);
		
		GlobalTimmer.purge() ;
		GlobalTimmer.registerTime(Enum_Timming.CONTROLLER_MOVE);
	}
 
	
	public static void loadAssets()
	{
		GlobalTimmer.registerTime(Enum_Timming.ASSETS);
		GVars_Interface.init();
		GlobalTimmer.getElapse(Enum_Timming.ASSETS, "Interface", true);
		
//		Index_Sprite.init();
//		GlobalTimmer.getElapse(Enum_Timming.ASSETS, "Sprite", true);
		
		GVars_AudioManager.init();
		GlobalTimmer.getElapse(Enum_Timming.ASSETS, "Sounds", true);
	}
	
	public static void changeVue(AVue_Model View,boolean cleanAll) 
	{
		if(cleanAll) {
			GVars_AudioManager.StopAndDisposeMusic();
			GVars_Heart.isPaused = false ; 
		}
		if (View != null) {
			vue = View;
			vue.init();
		} else {
			System.out.println("Aucune view?");
		}
	}

	public static void togglePauseMenu() 
	{
		GVars_Heart.isPaused = !GVars_Heart.isPaused ; 
		GVars_Interface.setPause(GVars_Heart.isPaused) ; 
	}

}