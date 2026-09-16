package jks.story;

import static jks.vars.GVars_Game.canoe;

import jks.camera.GVars_Camera;
import jks.parralax.GVars_Parralax;
import jks.sounds.Enum_Music;
import jks.sounds.GVars_AudioManager;
import jks.vars.GVars_Game;
import jks.vars.GVars_Random;
import jks.vue.models.Vue_Game;

public class GVars_Story 
{
	
	public static void act(float delta)
	{
		calculateHpDrop(delta) ; 
		calculateBots(delta) ; 
		storyTelling(delta) ; 
	}
	
	static float timming_currentStoryTime ; 
	
	static float timming_timeUntil_Music = 10 ; 
	static float timming_timeUntil_Monster = 5 ; 
	static float timming_timeUntil_TakeOff = 27 + timming_timeUntil_Music ; 
//	static float timming_timeUntil_TakeOff = 0 ;
	static float timming_maxAngle = 0.25701914f; 
	static float timming_angleSpeed = 9.0f; 
	
	static float flyingSpeed = 270 ; 
	static float faddingPower = 0.023f ; 
	static float accelerationGoingUp = 1.5f ;
	
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
			GVars_Camera.screenMovementSpeed += 0.7f ; 
		}
		
		if(timming_currentStoryTime > timming_timeUntil_TakeOff && timming_currentStoryTime < timming_timeUntil_Stabilise)
		{
			if(canoe.body.getAngle() < timming_maxAngle) 
			{
				canoe.body.setTransform(canoe.body.getPosition(), canoe.body.getAngle() + ((float)Math.toRadians(delta) * timming_angleSpeed));
			}
				
			GVars_Parralax.scroll(delta, 0, delta * flyingSpeed) ; 
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
			Vue_Game.star1.setPosition(Vue_Game.star1.getX(), Vue_Game.star1.getY() - delta * flyingSpeed/3);
		}
		
	}


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
