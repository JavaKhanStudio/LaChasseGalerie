package jks.input;

import static jks.input.GVars_Controller.getLocalPlayer;

import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerAdapter;
import com.badlogic.gdx.controllers.ControllerMapping;

import jks.debug.GVars_Debug;
import jks.vars.GVars_Game;

public class IKM_Game_XBoxController extends ControllerAdapter
{
	
	@Override
	public void connected(Controller controller) 
	{
		if(GVars_Debug.coreInformationDebug)
			System.out.println("Connected controller : " + controller.getName());
	}

	@Override
	public void disconnected(Controller controller) 
	{
		if(GVars_Debug.coreInformationDebug)
			System.out.println("Disconnected controller : " + controller.getName());
	}

	@Override
	public boolean buttonDown(Controller controller, int buttonCode) 
	{
		Player_Inputs inputing = getLocalPlayer(controller) ; 
		if(inputing == null)
		{
			GVars_Game.addPlayer(GVars_Controller.identify(controller));
			return false; 
		}

		ControllerMapping mapping = controller.getMapping() ; 
		
		if(buttonCode == mapping.buttonA)
			inputing.jumpPressed = true ; 
		else if(buttonCode == mapping.buttonB)
			inputing.powerLeft = true ; 
		else if(buttonCode == mapping.buttonX)
			inputing.powerRight = true ; 
		else
			return false ; 
		
		return true ;
	}

	@Override
	public boolean axisMoved(Controller controller, int axisCode, float value) 
	{
		Player_Inputs inputing = getLocalPlayer(controller) ; 
		
		if(inputing == null)
		{return false;}
		
		return Utils_Controller.axisController(controller.getMapping(), axisCode, value, inputing) ;
	}

}
