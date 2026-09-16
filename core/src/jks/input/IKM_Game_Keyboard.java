package jks.input;

import com.badlogic.gdx.Input.Keys;

import static jks.input.GVars_Controller.getLocalPlayer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;

import jks.sounds.GVars_AudioManager;
import jks.vars.GVars_Game;

public class IKM_Game_Keyboard extends InputAdapter 
{
		// PC input
		@Override
		public boolean keyDown (int keycode) 
		{
			
			if(keycode == Keys.ESCAPE)
			{
				Gdx.app.exit();
				return true ;
			}
			
			Player_Inputs inputing = getLocalPlayer(null) ; 
			if(inputing == null)
			{
				GVars_Game.addPlayer(GVars_Controller.identify(null));
				return false; 
			}
			
			switch (keycode) 
			{
				case Keys.SPACE :
				case Keys.UP :
					inputing.jumpPressed = true ; 
					return true ;
				case Keys.D :
					inputing.powerLeft = true ; 
					return true ;
				case Keys.Q :
					inputing.powerRight = true ; 
					return true ;
				case Keys.LEFT :
					inputing.leftPressed = true ; 
					inputing.rightPressed = false ;
					return true ;
				case Keys.RIGHT :
					inputing.rightPressed = true ; 
					inputing.leftPressed = false ;
					return true ;
				case Keys.E : 
					if(GVars_AudioManager.currentlyRunningAmbiance != null)
						GVars_AudioManager.currentlyRunningAmbiance.setVolume(Math.max(0, GVars_AudioManager.currentlyRunningAmbiance.getVolume() - 0.1f));
					return true ;
			}

			return false ; 
		}
		
		@Override
		public boolean keyUp (int keycode) 
		{
			if(GVars_Game.inCinematic)
				return true; 
			
			Player_Inputs inputing = getLocalPlayer(null) ;  
			if(inputing == null)
				return false ; 
			
			switch (keycode) 
			{
			case Keys.LEFT :
				inputing.leftPressed = false ; 
				return true ;
			case Keys.RIGHT :
				inputing.rightPressed = false ; 
				return true ;
			}
			
			return false;
		}
		
}
