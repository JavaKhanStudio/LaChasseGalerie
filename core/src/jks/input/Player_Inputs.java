package jks.input;

import jks.personnage.PhysicSpriteHeroes;

public class Player_Inputs 
{
	public boolean 
		touched, 
		jumpPressed,
		upPressed, downPressed,
		leftPressed, rightPressed,
		powerLeft, powerRight
		;

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
