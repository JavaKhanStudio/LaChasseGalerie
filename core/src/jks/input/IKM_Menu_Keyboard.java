package jks.input;

import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;

import jks.vinterface.Menu_Focus;

/**
 * The menu under a keyboard (d8) : the arrows move the focus, Space or Enter takes the choice.
 *
 * It sits BEHIND the menu's Stage in the multiplexer, so the pointer keeps its clicks and only the
 * keys scene2d does not want arrive here.
 */
public class IKM_Menu_Keyboard extends InputAdapter
{
	private final Menu_Focus focus ;

	public IKM_Menu_Keyboard(Menu_Focus focus)
	{this.focus = focus ;}

	@Override
	public boolean keyDown(int keycode)
	{
		switch(keycode)
		{
			case Keys.UP :
				focus.move(-1) ;
				return true ;
			case Keys.DOWN :
				focus.move(1) ;
				return true ;
			case Keys.SPACE :
			case Keys.ENTER :
			case Keys.NUMPAD_ENTER :
				// The keyboard picking a run is the keyboard sitting down to play it (d9)
				focus.pick(Menu_Picker.KEYBOARD) ;
				return true ;
		}

		return false ;
	}
}
