package jks.story;

import static jks.vars.GVars_Game.canoe;

import jks.camera.GVars_Camera;
import jks.parralax.GVars_Parralax;
import jks.sounds.Enum_Music;
import jks.sounds.GVars_AudioManager;
import jks.vars.FVars_Heart;
import jks.vars.GVars_Game;
import jks.vars.GVars_Random;
import jks.vue.models.Vue_Game;

public class GVars_Story 
{
	
	/**
	 * Back to the first second of a run : the clock, the spawn timers and the music cue. The
	 * timings themselves are tuning, not state, and stay. Vue_Game.init calls it, so a second run
	 * in the same JVM plays the same timeline as the first (phase 0.4).
	 */
	public static void init()
	{
		timming_currentStoryTime = 0 ; 
		skyScrolled = 0 ; 
		musicCueFired = false ; 
		screenSpeed = 0 ; 
		numberHp = 0 ; 
		currentTimmer_HpDrop = 0 ; 
		numberEnnemies = 0 ; 
		currentTimmer_TopEnnemy = 0 ; 
		currentTimmer_SideEnnemy = 0 ; 
	}
	
	public static void act(float delta)
	{
		calculateHpDrop(delta) ; 
		calculateBots(delta) ; 
		storyTelling(delta) ; 
	}
	
	static float timming_currentStoryTime ; 
	/** How far the take-off has scrolled the sky, in world units : what the star and the river's Y follow. */
	static float skyScrolled ; 
	
	/** Seconds since the run started : what the music, the take-off and the spawns are timed on. */
	public static float storyTime()
	{return timming_currentStoryTime ;}
	
	/** How far the sky has scrolled up since the run started, in world units (phase 1.3's snapshot). */
	public static float skyScroll()
	{return skyScrolled ;}
	
	static float timming_timeUntil_Music = 10 ; 
	static float timming_timeUntil_Monster = 5 ; 
	static float timming_timeUntil_TakeOff = 27 + timming_timeUntil_Music ; 
//	static float timming_timeUntil_TakeOff = 0 ;
	static float timming_maxAngle = 0.25701914f; 
	static float timming_angleSpeed = 9.0f; 
	
	static float flyingSpeed = 270 ; 
	static float faddingPower = 0.023f ; 
	static float accelerationGoingUp = 1.5f ;
	
	/** What the river speeds up by when the music starts. */
	static final float musicRiverBoost = 0.7f ; 
	
	static float screenSpeed ; 
	
	// The Music object stays null under --mute, so it cannot tell whether the cue already fired
	static boolean musicCueFired ; 
	
	static float timming_timeUntil_Stabilise = 8f + timming_timeUntil_TakeOff ; 
	
	private static void storyTelling(float delta) 
	{
		timming_currentStoryTime += delta ; 
		
		if(timming_currentStoryTime > timming_timeUntil_Music && !musicCueFired) 
		{
			musicCueFired = true ; 
			GVars_AudioManager.PlayMusic(Enum_Music.MUSIC);
			GVars_Camera.screenMovementSpeed += musicRiverBoost ; 
		}
		
		if(timming_currentStoryTime > timming_timeUntil_TakeOff && timming_currentStoryTime < timming_timeUntil_Stabilise)
		{
			if(canoe.body.getAngle() < timming_maxAngle) 
			{
				canoe.body.setTransform(canoe.body.getPosition(), canoe.body.getAngle() + ((float)Math.toRadians(delta) * timming_angleSpeed));
			}
				
			GVars_Parralax.scroll(delta, 0, delta * flyingSpeed) ; 
			skyScrolled += delta * flyingSpeed ; 
			GVars_Camera.screenMovementSpeed += accelerationGoingUp * delta ; 
			Vue_Game.star1.setPosition(Vue_Game.star1.getX(), Vue_Game.star1.getY() - delta * flyingSpeed/3);
			if(GVars_AudioManager.currentlyRunningAmbiance != null)
				GVars_AudioManager.currentlyRunningAmbiance.setVolume(Math.max(0, GVars_AudioManager.currentlyRunningAmbiance.getVolume() - faddingPower * delta));
		}
		else if(timming_currentStoryTime > timming_timeUntil_Stabilise && canoe.body.getAngle() >= 0)
		{
			if(canoe.body.getAngle() >= 0) 
			{
				canoe.body.setTransform(canoe.body.getPosition(), canoe.body.getAngle() + ((float)Math.toRadians(delta) * -timming_angleSpeed));
			}
			else 
			{canoe.body.setTransform(canoe.body.getPosition(), 0);}
			
			GVars_Parralax.scroll(delta, 0, delta * flyingSpeed) ; 
			skyScrolled += delta * flyingSpeed ; 
			Vue_Game.star1.setPosition(Vue_Game.star1.getX(), Vue_Game.star1.getY() - delta * flyingSpeed/3);
		}
		
	}


	/**
	 * The story as a CLIENT plays it (phase 1.5) : a client owns no world and runs no timers, so the
	 * beats follow the host's story clock, read off the snapshots, instead of its own. Everything
	 * between two story times that a picture or a speaker needs : the music cue, the river's scroll,
	 * the sky going up at take-off and the water fading. Spawns, the canoe's tilt and the star are
	 * the host's, and arrive in the snapshot.
	 */
	public static void followHost(float fromTime, float toTime, float fromSky, float toSky)
	{
		float delta = toTime - fromTime ; 
		if(delta <= 0)
			return ; 
		
		if(toTime > timming_timeUntil_Music && !musicCueFired) 
		{
			musicCueFired = true ; 
			GVars_AudioManager.PlayMusic(Enum_Music.MUSIC);
		}
		if(toTime > timming_timeUntil_TakeOff && toTime < timming_timeUntil_Stabilise && GVars_AudioManager.currentlyRunningAmbiance != null)
			GVars_AudioManager.currentlyRunningAmbiance.setVolume(Math.max(0, GVars_AudioManager.currentlyRunningAmbiance.getVolume() - faddingPower * delta));
		
		// The host scrolls the river by its speed once per step : that many steps went by
		GVars_Parralax.scroll(delta, riverSpeedAt(toTime) * delta / FVars_Heart.step, toSky - fromSky) ; 
		GVars_Parralax.act(delta) ; 
	}
	
	/**
	 * GVars_Camera.screenMovementSpeed as the host's story has made it by this story time : the base,
	 * the boost at the music cue, and the acceleration while taking off. Not sent in the snapshot,
	 * because it follows from the clock alone (the river is too faint to show the difference).
	 */
	public static float riverSpeedAt(float storyTime)
	{
		float speed = GVars_Camera.baseMovementSpeed ; 
		if(storyTime > timming_timeUntil_Music)
			speed += musicRiverBoost ; 
		float climbing = Math.min(storyTime, timming_timeUntil_Stabilise) - timming_timeUntil_TakeOff ; 
		if(climbing > 0)
			speed += accelerationGoingUp * climbing ; 
		return speed ; 
	}
	
	/** Where the star starts, in world units ; it goes down by a third of the sky's scroll. */
	public static float starStartY()
	{return GVars_Camera.viewHeight/1.1f * GVars_Camera.worldMutiplier ;}
	
	static int numberHp ; 
	static float currentTimmer_HpDrop ; 
//	static final float timming_HpDrop = 0 ; 
	static final float timming_HpDrop = 10.0f ; // Don't do it Charlie!
	
	
	private static void calculateHpDrop(float delta) 
	{
		currentTimmer_HpDrop += delta ; 
		if(currentTimmer_HpDrop > timming_HpDrop)
		{
			currentTimmer_HpDrop = 0 ; 
			numberHp = GVars_Random.random.nextInt(getBaseNumber() + 1) ; 
			while(numberHp > 0)
			{
				GVars_Game.dropHp() ; 
				--numberHp ; 
			}
		}	
	}


	static int numberEnnemies ; 
	static float currentTimmer_TopEnnemy ; 
	static float timming_TopEnnemy = 3; 
	static float currentTimmer_SideEnnemy ;
	static float timming_SideEnnemy = 4; 
	
	public static void calculateBots(float delta)
	{
		if(timming_currentStoryTime < timming_timeUntil_Music + timming_timeUntil_Monster)
			return ; 
		
		currentTimmer_TopEnnemy += delta ; 
		currentTimmer_SideEnnemy += delta ; 
		if(currentTimmer_TopEnnemy > timming_TopEnnemy)
		{topEnnemy();}
		
		if(currentTimmer_SideEnnemy > timming_SideEnnemy)
		{sideEnnemy();}
	}
	
	private static void topEnnemy()
	{
		numberEnnemies = getBaseNumber() ;
		int sendingX = GVars_Random.random.nextInt(numberEnnemies + 1) + numberEnnemies ; 
		while(sendingX > 0)
		{
			GVars_Game.addEnnemy_Top();
			sendingX-- ; 
		}
		
		currentTimmer_TopEnnemy = 0 ; 
	}
	
	private static void sideEnnemy()
	{
		numberEnnemies = getBaseNumber() ;
		int sendingX = GVars_Random.random.nextInt(numberEnnemies + 1) ; 
		while(sendingX > 0)
		{
			GVars_Game.addEnnemy_Side();
			sendingX-- ; 
		}
		
		currentTimmer_SideEnnemy = 0 ; 
	}
	
	private static int getBaseNumber()
	{return GVars_Game.heroes.size() ;}
	
	
}
