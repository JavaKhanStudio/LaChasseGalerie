package jks.input;

import jks.personnage.PhysicSpriteHeroes;
import jks.vinterface.GVars_Interface;
import jks.vinterface.controlling.Utils_Controllable; 

public class Player_Inputs 
{
	public boolean 
		touched, 
		jumpPressed,
		upPressed, downPressed,
		leftPressed, rightPressed,
		powerLeft, powerRight,
		triggerPowerLeft, triggerPowerRight,
		jumpSupression
		;

	public boolean blockActionForClick ; // Considere s'il faut annuler toute autre action de click

	PhysicSpriteHeroes ref ; 
	
	public Player_Inputs(PhysicSpriteHeroes ref)
	{
		this.ref = ref ; 
	}
	
	public void act(float delta) 
	{
		
		if (rightPressed)
		{
			ref.speed_current += ref.speed_acceleration ; 
			if(ref.speed_current > ref.speed_max)
				ref.speed_current = ref.speed_max ; 
		} 
		else if (leftPressed)
		{
			ref.speed_current -= ref.speed_acceleration ; 
			if(ref.speed_current < -ref.speed_max)
				ref.speed_current = -ref.speed_max ; 
		} 
		else 
		{
			if(ref.speed_current > 0)
			{
				ref.speed_current -= ref.speed_deceleration ; 
				if(ref.speed_current < 0)
					ref.speed_current = 0 ; 
			}
			else if(ref.speed_current < 0)
			{
				ref.speed_current += ref.speed_deceleration ; 
				if(ref.speed_current > 0)
					ref.speed_current = 0 ; 
			}
		}
		
		ref.body.setLinearVelocity(ref.speed_current * delta,ref.body.getLinearVelocity().y);
		
		if(jumpPressed)
		{
			ref.jump();
			jumpPressed = false ; 
		}
		
		if(jumpSupression)
		{

		}
		
		/*
		if(triggerPowerLeft)
		{
			ref.moveAxe(true) ; 
			triggerPowerLeft = false ; 
		}
		if(triggerPowerRight)
		{
			ref.moveAxe(false) ; 
			triggerPowerRight = false ; 
		}
		*/
		if(powerLeft)
		{
			ref.pushAxe(true) ; 
			powerLeft = false ; 
		}
		if(powerRight)
		{
			ref.pushAxe(false) ; 
			powerRight = false ; 
		}
		
		
	}
	
	public static void updateInput_ControllingInterface()
	{
		if(GVars_Interface.currentControllable != null)
		{Utils_Controllable.decodeInterfaceController();}
	}

	public void resetInputs()
	{
		touched = false ;
		jumpPressed = false ;
		upPressed = false ;
		downPressed = false ;
		leftPressed  = false ;
		rightPressed = false ;
	}
}
