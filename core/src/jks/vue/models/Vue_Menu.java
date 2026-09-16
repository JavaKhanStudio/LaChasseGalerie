package jks.vue.models;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;

import jks.parralax.Enum_ColdNight;
import jks.parralax.GVars_Parralax;
import jks.vars.GVars_Heart;
import jks.vinterface.GVars_Interface;
import jks.vue.AVue_Model;

/**
 * The start menu, as a sketch (r18) : the night river, the title, and the ways out of it.
 *
 * It is a view like any other — GVars_Heart.changeVue(new Vue_Game()) is what "Local play" does —
 * and it owns ITS OWN Stage. The game's Stage (GVars_Interface.mainInterface) already carries the
 * score table, which a menu must not inherit.
 *
 * What it does NOT do, on purpose : there is no way back here from a running game. The run's timers
 * are static and never reset (#gameplay), so returning to the menu is a restart path, not a screen.
 */
public class Vue_Menu extends AVue_Model
{
	Stage stage ;

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
		Gdx.input.setInputProcessor(stage);

		float width = Gdx.graphics.getWidth() ;
		float height = Gdx.graphics.getHeight() ;

		Table table = new Table() ;
		table.setFillParent(true);

		Label title = new Label("La chasse-galerie", GVars_Interface.baseSkin, "title") ;
		table.add(title).padBottom(height * 0.08f).row();

		table.add(playButton("Local play", () -> GVars_Heart.changeVue(new Vue_Game())))
			.width(width * 0.34f).height(height * 0.11f).padBottom(height * 0.03f).row();

		TextButton online = playButton("Online play", null) ;
		// Nothing behind it yet : the plan is docs/online-multiplayer.md
		online.setDisabled(true);
		online.getColor().a = 0.7f ;
		table.add(online).width(width * 0.34f).height(height * 0.11f).row();

		// ASCII ONLY : the skin's bitmap fonts carry 98 glyphs, no accents and no dashes but '-'
		Label soon = new Label("not yet - one player per machine, over the network", GVars_Interface.baseSkin, "default") ;
		soon.getColor().a = 0.75f ;
		table.add(soon).padTop(height * 0.01f).padBottom(height * 0.04f).row();

		table.add(playButton("Quit", () -> Gdx.app.exit()))
			.width(width * 0.34f).height(height * 0.11f).row();

		stage.addActor(table);
	}

	private TextButton playButton(String text, Runnable onClick)
	{
		TextButton button = new TextButton(text, GVars_Interface.baseSkin) ;
		if(onClick != null)
		{
			button.addListener(new ChangeListener()
			{
				@Override
				public void changed(ChangeEvent event, Actor actor)
				{onClick.run();}
			});
		}
		return button ;
	}

	@Override
	public void update(float delta)
	{
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
		stage.dispose();
	}
}
