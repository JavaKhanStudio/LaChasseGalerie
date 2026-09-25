package jks.vinterface;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;

import jks.input.IKM_Menu_Keyboard;
import jks.input.IKM_Menu_XBoxController;

/**
 * The end of a run : the song is over, and the final score table shows over the river until this
 * window's player picks what comes next. A local run offers a new run or the menu (d15) ; a hosted
 * one a new run or closing the server, and every client sees the same table (d14).
 *
 * The deciding window gets the choices, driven like the start menu (d8) : pointer, arrows and pads all
 * move one {@link Menu_Focus}, and a pick runs on the view's next update. A client gets no choice, only
 * a line saying what it waits for : the decision is the host's.
 *
 * It owns ITS OWN Stage, like Vue_Menu : the game's HUD Stage carries the live score table, which this
 * one replaces while it shows. The skin and the two score icons are GVars_Interface's and stay.
 *
 * ASCII ONLY in every text : the skin's bitmap fonts carry 98 glyphs, no accents.
 */
public class Score_Screen
{
	/** One player's line : who, how they did, and the colour of the hero they had last. */
	public static final class Row
	{
		public final int player, score, deaths ;
		public final Color color ;

		public Row(int player, int score, int deaths, Color color)
		{
			this.player = player ;
			this.score = score ;
			this.deaths = deaths ;
			this.color = new Color(color) ;
		}
	}

	private final Stage stage = new Stage() ;
	/** The host's choices. Empty on a client. */
	public final Menu_Focus focus = new Menu_Focus() ;
	private final Table panel = new Table() ;
	private final Label line ;
	private IKM_Menu_XBoxController pads ;

	/**
	 * @param rows every player of the run, in any order : the best score comes first, fewer deaths
	 *        break a tie, then the lower player number
	 * @param waiting what the line under the table says, or null for none
	 */
	public Score_Screen(List<Row> rows, String waiting)
	{
		float width = Gdx.graphics.getWidth() ;
		float height = Gdx.graphics.getHeight() ;

		Table root = new Table() ;
		root.setFillParent(true) ;
		// The river goes on behind, darker, so the table reads over it
		root.setBackground(GVars_Interface.baseSkin.newDrawable("white", new Color(0, 0, 0, 0.55f))) ;

		root.add(new Label("The song is over", GVars_Interface.baseSkin, "title")).padBottom(height * 0.05f).row() ;

		List<Row> sorted = new ArrayList<Row>(rows) ;
		sorted.sort((a, b) -> a.score != b.score ? b.score - a.score : a.deaths != b.deaths ? a.deaths - b.deaths : a.player - b.player) ;

		float icon = height * 0.045f ;
		if(sorted.isEmpty())
			panel.add(label("Nobody joined this run", 2)).row() ;
		for(Row row : sorted)
		{
			panel.add(label("Player " + row.player, 2)).align(Align.left).padRight(width * 0.04f) ;
			panel.add(icon(GVars_Interface.scoreRegion, row.color)).size(icon).padRight(width * 0.008f) ;
			panel.add(label(String.valueOf(row.score), 2)).align(Align.left).minWidth(width * 0.06f).padRight(width * 0.03f) ;
			panel.add(icon(GVars_Interface.deathRegion, row.color)).size(icon).padRight(width * 0.008f) ;
			panel.add(label(String.valueOf(row.deaths), 2)).align(Align.left).minWidth(width * 0.06f) ;
			panel.row().padTop(height * 0.01f) ;
		}
		root.add(panel).padBottom(height * 0.05f).row() ;

		line = label(waiting == null ? "" : waiting, 1.5f) ;
		root.add(line).padBottom(height * 0.03f).row() ;

		stage.addActor(root) ;
	}

	/** A choice for the window that decides, under the table, in the order they are added. */
	public void choice(String text, Menu_Focus.Taken onPick)
	{
		TextButton button = new TextButton(text, GVars_Interface.baseSkin) ;
		focus.add(button, onPick) ;
		Table root = (Table) stage.getActors().first() ;
		root.add(button).width(Gdx.graphics.getWidth() * 0.34f).height(Gdx.graphics.getHeight() * 0.11f).padBottom(Gdx.graphics.getHeight() * 0.03f).row() ;
	}

	/**
	 * This window's pointer, keyboard and pads drive the choices from now on, and nothing of the run.
	 * The view that shows this screen still clears them in its own dispose.
	 */
	public void listen()
	{
		pads = new IKM_Menu_XBoxController(focus) ;
		Gdx.input.setInputProcessor(new InputMultiplexer(stage, new IKM_Menu_Keyboard(focus))) ;
		Controllers.clearListeners() ;
		Controllers.addListener(pads) ;
	}

	/** Changes the line under the table. */
	public void say(String text)
	{line.setText(text) ;}

	public void act(float delta)
	{stage.act(delta) ;}

	public void draw()
	{
		stage.getViewport().apply() ;
		stage.draw() ;
	}

	public void dispose()
	{
		if(pads != null)
			Controllers.removeListener(pads) ;
		stage.dispose() ;
	}

	private static Label label(String text, float scale)
	{
		Label label = new Label(text, GVars_Interface.baseSkin) ;
		label.setFontScale(scale) ;
		return label ;
	}

	private static Image icon(TextureRegionDrawable region, Color color)
	{
		Image image = new Image(region) ;
		image.setColor(color) ;
		return image ;
	}
}
