package jks.vue.models;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;

import jks.input.IKM_Menu_Keyboard;
import jks.input.IKM_Menu_XBoxController;
import jks.parralax.Enum_ColdNight;
import jks.parralax.GVars_Parralax;
import jks.vars.GVars_Heart;
import jks.vinterface.GVars_Interface;
import jks.vinterface.Menu_Focus;
import jks.vue.AVue_Model;

/**
 * The start menu, as a sketch (r18) : the night river, the title, and the ways out of it.
 *
 * It is a view like any other — GVars_Heart.changeVue(new Vue_Game()) is what "Local play" does —
 * and it owns ITS OWN Stage. The game's Stage (GVars_Interface.mainInterface) already carries the
 * score table, which a menu must not inherit.
 *
 * Three ways in, not one (d8) : the pointer, the arrows and a pad all move the same Menu_Focus, so
 * the pad a player is already holding gets them into the game without reaching for a mouse. And by
 * d9 that same press joins the run : whoever picks Local play is player 1, and the others press to
 * join behind them.
 *
 * What it does NOT do yet : offer a way back here from a running game. Nothing stops one any more
 * — changeVue(new Vue_Menu()) tears the run down (Vue_Game.dispose, phase 0.4) and the next Local
 * play starts clean — but no key or button asks for it.
 */
public class Vue_Menu extends AVue_Model
{
	Stage stage ;
	Menu_Focus focus ;
	IKM_Menu_XBoxController padListener ;

	/** Slow enough to read a menu over : the river runs at 7.5 once the game starts. */
	private static final float menuScrollSpeed = 2f ;

	@Override
	public void init()
	{
		// The skin only : building the game's HUD here would put the score table behind the menu
		GVars_Interface.loadSkin();
		GVars_Parralax.init();
		GVars_Parralax.setPages(Enum_ColdNight.COLD_NIGHT, Enum_ColdNight.COLD_WATER) ;

		stage = new Stage() ;
		focus = new Menu_Focus() ;

		// The Stage first, so the pointer keeps its clicks ; the keys it does not want fall through
		padListener = new IKM_Menu_XBoxController(focus) ;
		Gdx.input.setInputProcessor(new InputMultiplexer(stage, new IKM_Menu_Keyboard(focus))) ;
		Controllers.clearListeners();
		Controllers.addListener(padListener) ;

		float width = Gdx.graphics.getWidth() ;
		float height = Gdx.graphics.getHeight() ;

		Table table = new Table() ;
		table.setFillParent(true);

		Label title = new Label("La chasse-galerie", GVars_Interface.baseSkin, "title") ;
		table.add(title).padBottom(height * 0.08f).row();

		// The hand that picks the run is already in it (d9) : it is handed to the run it opens
		TextButton local = playButton("Local play") ;
		focus.add(local, picker -> GVars_Heart.changeVue(new Vue_Game(picker))) ;
		table.add(local).width(width * 0.34f).height(height * 0.11f).padBottom(height * 0.03f).row();

		// One player per machine, over the network (d5 -> A) : the lobby screen, r43
		TextButton online = playButton("Online play") ;
		focus.add(online, picker -> GVars_Heart.changeVue(new Vue_Lobby())) ;
		table.add(online).width(width * 0.34f).height(height * 0.11f).padBottom(height * 0.03f).row();

		TextButton quit = playButton("Quit") ;
		focus.add(quit, picker -> Gdx.app.exit()) ;
		table.add(quit).width(width * 0.34f).height(height * 0.11f).row();

		stage.addActor(table);
	}

	private TextButton playButton(String text)
	{return new TextButton(text, GVars_Interface.baseSkin) ;}

	@Override
	public void update(float delta)
	{
		// The choice made since the last frame lands here. Once it has run this view may already be
		// the old one, and must not go on acting a Stage it has just disposed.
		if(focus.runPicked())
			return ;

		GVars_Parralax.scroll(delta, menuScrollSpeed, 0);
		GVars_Parralax.act(delta);
		stage.act(delta);
	}

	@Override
	public void render()
	{
		Gdx.gl.glClearColor(0, 0, 0, 1);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

		GVars_Parralax.background.render();
		GVars_Parralax.foreground.render();

		stage.draw();
	}

	@Override
	public void dispose()
	{
		Controllers.removeListener(padListener);
		stage.dispose();
	}
}
