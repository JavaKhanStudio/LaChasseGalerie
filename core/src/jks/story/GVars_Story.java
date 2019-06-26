package jks.story;

import static jks.vars.GVars_Game.canoe;

import java.util.Random;

import jks.tools2d.parallax.heart.Parallax_Heart;
import jks.vars.GVars_Game;
import jks.vue.models.Vue_Game;

public class GVars_Story 
{

	public void init()
	{
		
	}
	
	
	static Random random = new Random();
	
	
	public static void act(float delta)
	{
		calculateHpDrop(delta) ; 
		calculateBots(delta) ; 
		canoeMovement(delta) ; 
	}
	
	static float timming_currentStoryTime ; 
	
	static float timming_timeUntil_TakeOff = 15 ; 
//	static float timming_timeUntil_TakeOff = 0 ;
	static float timming_maxAngle = 0.25701914f; 
	static float timming_angleSpeed = 9.0f; 
	
	static float flyingSpeed = 270 ; 
	
	static float timming_timeUntil_Stabilise = 8f + timming_timeUntil_TakeOff ; 
	
	private static void canoeMovement(float delta) 
	{
		timming_currentStoryTime += delta ; 
		if(timming_currentStoryTime > timming_timeUntil_TakeOff && timming_currentStoryTime < timming_timeUntil_Stabilise)
		{
			if(canoe.body.getAngle() < timming_maxAngle) 
			{
				canoe.body.setTransform(canoe.body.getPosition(), canoe.body.getAngle() + ((float)Math.toRadians(delta) * timming_angleSpeed));
			}
				
			Parallax_Heart.worldCamera.position.add(0, delta * flyingSpeed, 0) ; 
			Vue_Game.star1.setPosition(Vue_Game.star1.getX(), Vue_Game.star1.getY() - delta * flyingSpeed/3);
		}
		else if(timming_currentStoryTime > timming_timeUntil_Stabilise && canoe.body.getAngle() >= 0)
		{
			if(canoe.body.getAngle() >= 0) 
			{
				canoe.body.setTransform(canoe.body.getPosition(), canoe.body.getAngle() + ((float)Math.toRadians(delta) * -timming_angleSpeed));
			}
			else 
			{canoe.body.setTransform(canoe.body.getPosition(), 0);}
			
			Parallax_Heart.worldCamera.position.add(0, delta * flyingSpeed, 0) ; 
			Vue_Game.star1.setPosition(Vue_Game.star1.getX(), Vue_Game.star1.getY() - delta * flyingSpeed/3);
		}
		
	}


	static int numberHp ; 
	static float currentTimmer_HpDrop ; 
//	static final float timming_HpDrop = 12 ; 
	static final float timming_HpDrop = 10.0f ; // Don't do it Charlie!
	
	
	private static void calculateHpDrop(float delta) 
	{
		currentTimmer_HpDrop += delta ; 
		if(currentTimmer_HpDrop > timming_HpDrop)
		{
			currentTimmer_HpDrop = 0 ; 
			numberHp = random.nextInt(getBaseNumber() + 1) ; 
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
		int sendingX = random.nextInt(numberEnnemies + 1) + numberEnnemies ; 
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
		int sendingX = random.nextInt(numberEnnemies + 1) ; 
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
