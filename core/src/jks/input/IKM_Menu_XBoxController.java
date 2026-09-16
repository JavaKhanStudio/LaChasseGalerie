package jks.input;

import java.util.HashMap;

import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerAdapter;
import com.badlogic.gdx.controllers.ControllerMapping;

import jks.vinterface.Menu_Focus;

/**
 * The menu under a pad (d8) : the left stick or the d-pad moves the focus, A takes the choice.
 *
 * Every pad drives the same focus. There is one menu on the screen, so whoever reaches for it is
 * the one steering — and by d9, the one who picks a run is player 1 of it.
 */
public class IKM_Menu_XBoxController extends ControllerAdapter
{
	/** How far the stick must be pushed to count as a move... */
	private static final float pushed = 0.6f ;
	/** ...and how far back to the middle before it may move again. */
	private static final float released = 0.3f ;

	private final Menu_Focus focus ;
	/** Which way each pad is already holding its stick : a stick is a position, a menu wants a step. */
	private final HashMap<Controller,Integer> held = new HashMap<Controller,Integer>() ;

	public IKM_Menu_XBoxController(Menu_Focus focus)
	{this.focus = focus ;}

	@Override
	public boolean buttonDown(Controller controller, int buttonCode)
	{
		ControllerMapping mapping = controller.getMapping() ;

		if(buttonCode == mapping.buttonDpadUp)
			focus.move(-1) ;
		else if(buttonCode == mapping.buttonDpadDown)
			focus.move(1) ;
		else if(buttonCode == mapping.buttonA)
			// This pad picking a run is this pad sitting down to play it (d9)
			focus.pick(Menu_Picker.pad(controller)) ;
		else
			return false ;

		return true ;
	}

	@Override
	public boolean axisMoved(Controller controller, int axisCode, float value)
	{
		if(axisCode != controller.getMapping().axisLeftY)
			return false ;

		// The stick reads negative upwards, the same way Utils_Controller reads it in a run
		int direction = 0 ;
		if(value < -pushed)
			direction = -1 ;
		else if(value > pushed)
			direction = 1 ;

		Integer already = held.get(controller) ;
		if(already == null)
			already = 0 ;

		if(direction == 0)
		{
			if(Math.abs(value) < released)
				held.put(controller, 0) ;
			return false ;
		}

		if(direction == already)
			return true ;

		held.put(controller, direction) ;
		focus.move(direction) ;
		return true ;
	}

	@Override
	public void disconnected(Controller controller)
	{held.remove(controller) ;}
}
