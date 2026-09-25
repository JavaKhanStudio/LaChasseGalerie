package jks.vinterface;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Array;

import jks.input.Menu_Picker;

/**
 * The choices of a menu as a ring, with one focus travelling around it (d8).
 *
 * This game is joined by pressing any key or any button, so a menu only a mouse can drive is a
 * different game from the one four people sit down to with pads. The ring is what lets the arrows,
 * the stick and the pad's A button reach the same choices the pointer does — jks.input's
 * IKM_Menu_Keyboard and IKM_Menu_XBoxController both talk to this, and nothing else.
 *
 * A choice is told WHO took it (d9) : the hand that starts a run also joins it, so the pad or the
 * keyboard that picked has to travel from the listener that heard it to the view it opens.
 *
 * A pick is NOT run where it is triggered : it waits for the view's next update. Both callers are
 * mid-iteration when they fire one — scene2d is walking its actors, and gdx-controllers is walking
 * a LinkedList of listeners that GVars_Heart.changeVue() then clears (Vue_Game.init).
 */
public class Menu_Focus
{
	/**
	 * How far an unfocused choice fades. Barely : the focused one is told apart by its background,
	 * and a choice dimmed any further reads as dead.
	 */
	private static final float dimmed = 0.85f ;

	/** What taking a choice does, told whose hand took it. */
	public interface Taken
	{void by(Menu_Picker picker) ;}

	private final Array<TextButton> choices = new Array<TextButton>() ;
	private final Array<Taken> picks = new Array<Taken>() ;
	private final Array<TextButtonStyle> resting = new Array<TextButtonStyle>() ;
	/** The same button drawn with its pressed background : this skin has no other 'chosen' look. */
	private final Array<TextButtonStyle> highlighted = new Array<TextButtonStyle>() ;

	private int focused = -1 ;
	private Taken picked ;
	private Menu_Picker pickedBy ;

	/** Adds a choice at the bottom of the ring. A choice nobody can take does not belong here. */
	public void add(TextButton button, Taken onPick)
	{
		final int index = choices.size ;

		choices.add(button) ;
		picks.add(onPick) ;

		TextButtonStyle style = button.getStyle() ;
		TextButtonStyle chosen = new TextButtonStyle(style) ;
		chosen.up = style.down ;
		resting.add(style) ;
		highlighted.add(chosen) ;

		// The pointer drives the same ring : a click picks, and hovering moves the focus, so the
		// mouse and the pad never disagree about which choice is the live one.
		button.addListener(new ChangeListener()
		{
			@Override
			public void changed(ChangeEvent event, Actor actor)
			{pick(index, Menu_Picker.POINTER) ;}
		});
		button.addListener(new InputListener()
		{
			@Override
			public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor)
			{
				if(pointer == -1)
					focus(index) ;
			}
		});

		if(focused < 0)
			focused = index ;

		paint() ;
	}

	/** Forgets every choice : a screen that rebuilds its buttons (the lobby's, r43) starts a new ring. */
	public void clear()
	{
		choices.clear() ;
		picks.clear() ;
		resting.clear() ;
		highlighted.clear() ;
		focused = -1 ;
	}

	/** Puts the focus on this choice, if it is one : a code typed in full moves it to Join. */
	public void focus(TextButton button)
	{
		int index = choices.indexOf(button, true) ;
		if(index >= 0)
			focus(index) ;
	}

	/** @param direction -1 for the choice above, 1 for the one below. The ring wraps around. */
	public void move(int direction)
	{
		if(choices.size == 0)
			return ;

		focus(((focused + direction) % choices.size + choices.size) % choices.size) ;
	}

	/** Takes the focused choice, in the name of the hand that took it. It runs on the next
	 *  update, not here. */
	public void pick(Menu_Picker picker)
	{
		if(focused >= 0)
			pick(focused, picker) ;
	}

	/**
	 * Runs the choice made since the last frame.
	 * @return true when one ran — the view holding this ring may no longer be the current one, and
	 *         must not touch its stage again this frame.
	 */
	public boolean runPicked()
	{
		if(picked == null)
			return false ;

		Taken running = picked ;
		Menu_Picker picker = pickedBy ;
		picked = null ;
		pickedBy = null ;
		running.by(picker);
		return true ;
	}

	private void pick(int index, Menu_Picker picker)
	{
		focus(index) ;
		picked = picks.get(index) ;
		pickedBy = picker ;
	}

	private void focus(int index)
	{
		if(index == focused)
			return ;

		focused = index ;
		paint() ;
	}

	private void paint()
	{
		for(int i = 0 ; i < choices.size ; i++)
		{
			boolean live = i == focused ;
			choices.get(i).setStyle(live ? highlighted.get(i) : resting.get(i)) ;
			choices.get(i).getColor().a = live ? 1f : dimmed ;
		}
	}
}
