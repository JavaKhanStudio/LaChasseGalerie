package jks.vinterface;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;

import jks.input.IKM_Menu_Keyboard;
import jks.input.IKM_Menu_XBoxController;
import jks.input.Menu_Picker;

/**
 * Escape in a run (r90) : a darkened river, a title, and the choices, Resume first. Escape again, or a
 * pad's Start, takes Resume.
 *
 * It only SHOWS and takes a pick : whether the run stops under it is the view's to decide. A local run
 * freezes ; an online one cannot, since the host's world is everyone's, so it goes on under this.
 *
 * Driven like the score screen (d8) : pointer, arrows and pads move one {@link Menu_Focus}, and a pick
 * runs on the view's next update. It owns its own Stage, and while it shows, this window's pointer,
 * keyboard and pads drive it and nothing of the run : the view hands them back on Resume.
 *
 * ASCII ONLY in every text : the skin's bitmap fonts carry 98 glyphs, no accents.
 */
public class Pause_Screen
{
	private final Stage stage = GVars_Interface.menuStage() ;
	public final Menu_Focus focus = new Menu_Focus() ;
	private final Table root = new Table() ;
	private TextButton resume ;
	private IKM_Menu_XBoxController pads ;

	/** @param line what the line under the title says, or null for none */
	public Pause_Screen(String title, String line)
	{
		root.setFillParent(true) ;
		root.setBackground(GVars_Interface.baseSkin.newDrawable("white", new Color(0, 0, 0, 0.55f))) ;
		root.add(new Label(title, GVars_Interface.baseSkin, "title")).padBottom(stage.getHeight() * 0.05f).row() ;
		if(line != null)
		{
			Label said = new Label(line, GVars_Interface.baseSkin) ;
			said.setFontScale(1.5f) ;
			root.add(said).padBottom(stage.getHeight() * 0.05f).row() ;
		}
		stage.addActor(root) ;
	}

	/** A choice, in the order they are added. The first one is what Escape and Start take. */
	public void choice(String text, Menu_Focus.Taken onPick)
	{
		TextButton button = new TextButton(text, GVars_Interface.baseSkin) ;
		focus.add(button, onPick) ;
		if(resume == null)
			resume = button ;
		root.add(button).width(stage.getWidth() * 0.34f).height(stage.getHeight() * 0.11f).padBottom(stage.getHeight() * 0.03f).row() ;
	}

	/** This window's pointer, keyboard and pads drive the choices from now on, and nothing of the run. */
	public void listen()
	{
		pads = new IKM_Menu_XBoxController(focus)
		{
			@Override
			public boolean buttonDown(Controller controller, int buttonCode)
			{
				if(buttonCode == controller.getMapping().buttonStart)
				{
					takeResume(Menu_Picker.pad(controller)) ;
					return true ;
				}
				return super.buttonDown(controller, buttonCode) ;
			}
		} ;
		InputAdapter escape = new InputAdapter()
		{
			@Override
			public boolean keyDown(int keycode)
			{
				if(keycode != Keys.ESCAPE)
					return false ;
				takeResume(Menu_Picker.KEYBOARD) ;
				return true ;
			}
		} ;
		Gdx.input.setInputProcessor(new InputMultiplexer(stage, escape, new IKM_Menu_Keyboard(focus))) ;
		Controllers.clearListeners() ;
		Controllers.addListener(pads) ;
	}

	private void takeResume(Menu_Picker picker)
	{
		focus.focus(resume) ;
		focus.pick(picker) ;
	}

	public void resize(int width, int height)
	{stage.getViewport().update(width, height, true) ;}

	public void act(float delta)
	{stage.act(delta) ;}

	public void draw()
	{
		stage.getViewport().apply() ;
		stage.draw() ;
	}

	/** The view that showed this hands its own input back : this only lets go of its pads. */
	public void dispose()
	{
		if(pads != null)
			Controllers.removeListener(pads) ;
		stage.dispose() ;
	}
}
