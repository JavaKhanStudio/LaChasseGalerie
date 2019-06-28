package jks.sounds;

import static jks.sounds.GVars_Audio.*;

import java.util.ArrayList;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.MathUtils;

import jks.debug.GVars_Debug;

public class GVars_AudioManager 
{	
	private static ArrayList<Sound> jumpingSounds;
	private static ArrayList<Sound> heroLandingSounds;
	private static ArrayList<Sound> heroDeathSounds;

	private static Sound runningSound;
	private static Sound itemPickupSound;
	private static Sound touchingground;

	private static FileHandle musicFile = Gdx.files.internal("musics/pagayez.mp3");
	private static FileHandle ambianceFile = Gdx.files.internal("ambiance/courant1.mp3");

	public static Music currentlyRunningMusic;
	private static Music currentlyRunningMusicSecondary;
	public static Music currentlyRunningAmbiance;

	public static void init() 
	{
		
	}

	private static void PreLoadAllSounds()
	{
		jumpingSounds = new ArrayList<>();
		jumpingSounds.add(Gdx.audio.newSound(Gdx.files.internal("sounds/Player_Jump_01.wav")));
		jumpingSounds.add(Gdx.audio.newSound(Gdx.files.internal("sounds/Player_Jump_02.wav")));
		jumpingSounds.add(Gdx.audio.newSound(Gdx.files.internal("sounds/Player_Jump_03.wav")));

		runningSound = Gdx.audio.newSound(Gdx.files.internal("sounds/Player_Run_Loop.wav"));

		itemPickupSound = Gdx.audio.newSound(Gdx.files.internal("sounds/Item_Pickup.wav"));

		heroLandingSounds = new ArrayList<>();
		heroLandingSounds.add(Gdx.audio.newSound(Gdx.files.internal("sounds/Player_Land_01.wav")));
		heroLandingSounds.add(Gdx.audio.newSound(Gdx.files.internal("sounds/Player_Land_02.wav")));
		heroLandingSounds.add(Gdx.audio.newSound(Gdx.files.internal("sounds/Player_Land_03.wav")));

		heroDeathSounds = new ArrayList<>();
		heroDeathSounds.add(Gdx.audio.newSound(Gdx.files.internal("sounds/Player_FallDeath_01.wav")));
		heroDeathSounds.add(Gdx.audio.newSound(Gdx.files.internal("sounds/Player_FallDeath_02.wav")));
		heroDeathSounds.add(Gdx.audio.newSound(Gdx.files.internal("sounds/Player_FallDeath_03.wav")));
	}

	public static void PlaySound(Enum_Sounds whichOne) 
	{
		if(GVars_Audio.muted)
			return ;
		
		if(GVars_Debug.soundDebug)
			System.out.println("Trying to play sound : " + whichOne);
		
		switch (whichOne) 
		{
			case Jumping:
				int randomizedIndex = MathUtils.random.nextInt(jumpingSounds.size());
				runningSound.stop();
				jumpingSounds.get(randomizedIndex).play(volume);
				break;
	
			case Running:
				runningSound.stop();
				runningSound.loop(volume * volumeFootStep, 1.1f, 0) ;
				break;
	
			case Idlling:
				runningSound.stop();
				break;
				
			case itemPickup:
				itemPickupSound.play(volume);
				break;
				
			case heroLanding:
				int randomizedIndex2 = MathUtils.random.nextInt(heroLandingSounds.size());
				if(touchingground != null)
					touchingground.stop();
				touchingground = heroLandingSounds.get(randomizedIndex2);
				touchingground.play(volume) ;
				break;
				
			case HeroDeath:
				int randomizedIndex3 = MathUtils.random.nextInt(heroDeathSounds.size());
				heroDeathSounds.get(randomizedIndex3).play(volume);
				break;
				
			default:
				System.out.println("Unknown Sound requested in PlaySound : " + whichOne);
				break;
		}
	}

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
					currentlyRunningMusic = Gdx.audio.newMusic(musicFile);
					currentlyRunningMusic.setLooping(false);
					currentlyRunningMusic.play();
				}
				break;
			
			default:
				System.out.println("Unknown Music requested in PlayMusic : " + whichOne);
				break;
		}
	}
	
	public static void PlayAmbiance(Enum_Ambiance whichOne) 
	{
		if(GVars_Audio.muted)
			return ;

		if(GVars_Debug.soundDebug)
			System.out.println("Trying to play Music : " + whichOne);
		
		switch (whichOne) 
		{
			case WATER:
				if (currentlyRunningAmbiance == null) 
				{
					currentlyRunningAmbiance = Gdx.audio.newMusic(ambianceFile);
					currentlyRunningAmbiance.setLooping(false);
					currentlyRunningAmbiance.setVolume(0.25f);
					currentlyRunningAmbiance.play();
				}
				break;
			
			default:
				System.out.println("Unknown Music requested in PlayMusic : " + whichOne);
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
		if(currentlyRunningMusicSecondary != null)
		{
			currentlyRunningMusicSecondary.stop() ;
			currentlyRunningMusicSecondary.dispose() ;
			currentlyRunningMusicSecondary = null ;
		}
		if(currentlyRunningAmbiance != null)
		{
			currentlyRunningAmbiance.stop() ;
			currentlyRunningAmbiance.dispose() ;
			currentlyRunningAmbiance = null ;
		}
	}
}
