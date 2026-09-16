package jks.input;

import com.badlogic.gdx.controllers.Controller;

import jks.vars.GVars_Game;

/**
 * Whose hand took a choice in a menu : a pad, the keyboard, or a pointer that is nobody's hand.
 *
 * It exists because of d9 : the gesture that starts a run IS the join gesture. Whoever picked
 * Local play is player 1 and never presses a second time, so the choice has to carry who made it
 * all the way from the listener that heard it to the run it opens.
 *
 * The game already knows the keyboard player as the one with no controller (GVars_Controller keys
 * its map by Controller, null for the PC). A pointer is null too, which is why the two cannot be
 * told apart by the controller alone : a mouse is not a player, and joins nobody.
 */
public class Menu_Picker
{
	/** A mouse or a finger : it can choose a run, but it cannot paddle one. */
	public static final Menu_Picker POINTER = new Menu_Picker(null, false) ;
	/** The keyboard, which the game already knows as the player with no controller. */
	public static final Menu_Picker KEYBOARD = new Menu_Picker(null, true) ;

	public static Menu_Picker pad(Controller controller)
	{return new Menu_Picker(controller, true) ;}

	/** The pad that picked, or null for the keyboard and for a pointer. */
	public final Controller pad ;
	private final boolean player ;

	private Menu_Picker(Controller pad, boolean player)
	{
		this.pad = pad ;
		this.player = player ;
	}

	/**
	 * Sits this picker down in the run they have just started (d9). A pointer sits nobody down,
	 * and everyone else still joins the usual way : any key, any button.
	 *
	 * Must be called once the run exists — GVars_Game.init for the map, GVars_Interface.init for
	 * the score table a new player writes a label into, and the canoe for the hero to stand in.
	 */
	public void joinTheRun()
	{
		if(!player)
			return ;

		if(pad == null)
			GVars_Game.addPlayer() ;
		else
			GVars_Game.addPlayer(pad) ;
	}
}
