package jks.sounds;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;

import jks.debug.GVars_Debug;

public class GVars_AudioManager 
{	
	private static final String musicFile = "musics/pagayez.mp3";
	private static final String ambianceFile = "ambiance/courant1.mp3";

	public static Music currentlyRunningMusic;
	public static Music currentlyRunningAmbiance;

	public static void PlayMusic(Enum_Music whichOne) 
	{
		if(GVars_Audio.muted)
			return ;

		if(GVars_Debug.soundDebug)
			System.out.println("Trying to play Music : " + whichOne);
		
		switch (whichOne) 
		{
			case MUSIC:
				if (currentlyRunningMusic == null) 
				{
					currentlyRunningMusic = Gdx.audio.newMusic(Gdx.files.internal(musicFile));
					currentlyRunningMusic.setLooping(false);
					currentlyRunningMusic.setVolume(GVars_Audio.volume);
					currentlyRunningMusic.play();
				}
				break;
		}
	}
	
	public static void PlayAmbiance(Enum_Ambiance whichOne) 
	{
		if(GVars_Audio.muted)
			return ;

		if(GVars_Debug.soundDebug)
			System.out.println("Trying to play Ambiance : " + whichOne);
		
		switch (whichOne) 
		{
			case WATER:
				if (currentlyRunningAmbiance == null) 
				{
					currentlyRunningAmbiance = Gdx.audio.newMusic(Gdx.files.internal(ambianceFile));
					currentlyRunningAmbiance.setLooping(false);
					currentlyRunningAmbiance.setVolume(0.25f * GVars_Audio.volume);
					currentlyRunningAmbiance.play();
				}
				break;
			
			default:
				System.out.println("Unknown Ambiance requested in PlayAmbiance : " + whichOne);
				break;
		}
	}

	public static void StopAndDisposeMusic() 
	{
		if(currentlyRunningMusic != null)
		{
			currentlyRunningMusic.stop() ;
			currentlyRunningMusic.dispose() ;
			currentlyRunningMusic = null ;
		}
		if(currentlyRunningAmbiance != null)
		{
			currentlyRunningAmbiance.stop() ;
			currentlyRunningAmbiance.dispose() ;
			currentlyRunningAmbiance = null ;
		}
	}
}
