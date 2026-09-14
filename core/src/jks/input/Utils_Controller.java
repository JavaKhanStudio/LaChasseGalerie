package jks.input;

import com.badlogic.gdx.controllers.ControllerMapping;

import jks.vars.GVars_Game;

public class Utils_Controller 
{
	static float minForceMoveX = 0.3f;
	static float minForceMoveY = 0.2f;
	
	
	
	public static boolean axisController(ControllerMapping mapping, int axisCode, float value, Player_Inputs player)
	{
		if(GVars_Game.inCinematic)
		{
			return true; 
		}
		
		if(axisCode == mapping.axisLeftX)
		{
			if(value > minForceMoveX)
			{
				player.leftPressed = false;
				player.rightPressed = true;
			}
			else if(value < -minForceMoveX)
			{
				player.leftPressed = true;
				player.rightPressed = false;
			}
			else
			{
				player.leftPressed = false;
				player.rightPressed = false;
			}
		}
		
		if(axisCode == mapping.axisLeftY)
		{
			if(value > minForceMoveY)
			{
				player.upPressed = false;
				player.downPressed = true;
			}
			else if(value < -minForceMoveY)
			{
				player.upPressed = true;
				player.downPressed = false;
			}
			else
			{
				player.upPressed = false;
				player.downPressed = false;
			}
		}
	
		return false ; 
	}
	
}