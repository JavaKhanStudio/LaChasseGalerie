package jks.vue.models;

import static jks.camera.GVars_Camera.camera;
import static jks.camera.GVars_Camera.staticBatch;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;

import jks.camera.GVars_Camera;
import jks.draw.Draw_Monster;
import jks.fx.Death_Fx;
import jks.parralax.Enum_ColdNight;
import jks.parralax.GVars_Parralax;
import jks.personnage.index.Index_Sprite;
import jks.vars.GVars_Heart;
import jks.vinterface.GVars_Interface;
import jks.vue.AVue_Model;

/**
 * The death lab (r98) : a page of Death_Fx.Style side by side over the night river, a monster in each
 * column, dying at the same moment and coming back, over and over. `--lab death` opens it. Page 1 is
 * the first round (Pop, Shatter, Wisp, Dissolve, Ring) ; page 2 is Simon's pick F, Pop's flash and
 * squash and then the monster's soul, five ways.
 *
 *   Space  every column dies now          1-5  that column dies now
 *   S      slow motion (x0.25), again off M    the next monster model
 *   Tab    the next page                  Escape the start menu
 *
 * Nothing here simulates : no world, no body, no GVars_Game. Draw_Monster is the sprite the client draws.
 */
public class Vue_Lab_Death extends AVue_Model
{
	/** How long a monster idles before it dies, and how long its column stays empty after. */
	public static final float alive = 1.4f, empty = 0.6f ;

	/** Every page has this many columns. */
	private static final int columns = 5 ;
	private int page = 1 ;
	private Death_Fx.Style[] styles = Death_Fx.Style.page(page) ;
	private final Draw_Monster[] monsters = new Draw_Monster[columns] ;
	private final Death_Fx[] deaths = new Death_Fx[columns] ;
	/** Each column's clock : alive while below `alive`, dying while its Death_Fx plays, then empty. */
	private final float[] clock = new float[columns] ;
	private final Label[] names = new Label[columns] ;
	/** Lab seconds since every column last died at once (killNow(-1)), slow motion counted : the probe's clock. */
	private float sinceKill = -1 ;
	private int model ;
	private boolean slow ;
	private Stage stage ;
	private Label help ;

	@Override
	public void init()
	{
		GVars_Heart.loadAssets() ;
		GVars_Interface.loadSkin() ;
		GVars_Parralax.init() ;
		GVars_Camera.init() ;
		GVars_Parralax.setPages(Enum_ColdNight.COLD_NIGHT, Enum_ColdNight.COLD_WATER) ;

		stage = GVars_Interface.menuStage() ;
		float width = stage.getWidth(), height = stage.getHeight() ;
		for(int i = 0 ; i < columns ; i++)
		{
			names[i] = new Label("", GVars_Interface.baseSkin, "title") ;
			names[i].setFontScale(0.6f) ;
			stage.addActor(names[i]) ;
		}
		page(1) ;
		help = new Label("", GVars_Interface.baseSkin) ;
		help.setFontScale(1.3f) ;
		help.setPosition(width * 0.02f, height * 0.94f) ;
		stage.addActor(help) ;

		newMonsters() ;
		Death_Fx.Kit.prepare() ;
		Gdx.input.setInputProcessor(keys) ;
	}

	private void newMonsters()
	{
		for(int i = 0 ; i < styles.length ; i++)
			monsters[i] = new Draw_Monster(Index_Sprite.monsterModel.get((model + i) % Index_Sprite.monsterModel.size())) ;
	}

	/** Shows page `page` (from 1) : its styles in the columns, every column alive again. */
	public void page(int page)
	{
		this.page = page ;
		styles = Death_Fx.Style.page(page) ;
		float width = stage.getWidth(), height = stage.getHeight() ;
		for(int i = 0 ; i < columns ; i++)
		{
			names[i].setText((i + 1) + "  " + styles[i].label) ;
			names[i].pack() ;
			names[i].setPosition(width * (i + 0.5f) / columns - names[i].getWidth() / 2, height * 0.30f) ;
			deaths[i] = null ;
			clock[i] = 0 ;
		}
		sinceKill = -1 ;
	}

	/** Column i's monster dies now ; -1 : every column's. What the probe and Space call. */
	public void killNow(int column)
	{
		for(int i = 0 ; i < styles.length ; i++)
			if(column < 0 || column == i)
			{
				deaths[i] = null ;
				clock[i] = alive ;
			}
		if(column < 0)
			sinceKill = 0 ;
	}

	public float sinceKill()
	{return sinceKill ;}

	public void slow(boolean slow)
	{this.slow = slow ;}

	@Override
	public void update(float delta)
	{
		if(slow)
			delta *= 0.25f ;
		if(sinceKill >= 0)
			sinceKill += delta ;
		GVars_Parralax.scroll(delta, 2f, 0) ;
		GVars_Parralax.act(delta) ;
		stage.act(delta) ;
		help.setText("Death lab, page " + page + "/" + Death_Fx.Style.pages() + " : Space all die, 1-5 one, S slow motion" + (slow ? " (ON)" : "")
				+ ", M next monster, Tab next page, Esc menu") ;

		float worldWidth = GVars_Camera.viewWidth * GVars_Camera.worldMutiplier ;
		float worldHeight = GVars_Camera.viewHeight * GVars_Camera.worldMutiplier ;
		for(int i = 0 ; i < styles.length ; i++)
		{
			Draw_Monster monster = monsters[i] ;
			float before = clock[i] ;
			clock[i] += delta ;
			if(clock[i] < alive)
			{
				// Idling where the column is, bobbing as a flying skull does
				monster.update(delta) ;
				monster.reverse(i % 2 == 0) ;
				float bob = (float) Math.sin(clock[i] * 3 + i) * 12 ;
				monster.position.set(worldWidth * (i + 0.5f) / styles.length - monster.getFrameWidth(monster.currentFrame) / 2,
						worldHeight * 0.55f + bob) ;
				continue ;
			}
			if(before < alive || deaths[i] == null)
				deaths[i] = new Death_Fx(styles[i], monster.currentFrame, monster.position.x, monster.position.y,
						monster.getFrameWidth(monster.currentFrame), monster.getFrameHeight(monster.currentFrame), monster.isReversed()) ;
			deaths[i].update(delta) ;
			if(clock[i] >= alive + styles[i].length + empty)
				clock[i] = 0 ;
		}
	}

	@Override
	public void render()
	{
		Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight()) ;
		Gdx.gl.glClearColor(0, 0, 0, 1) ;
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT) ;
		GVars_Camera.viewport.apply() ;
		camera.update() ;
		staticBatch.setProjectionMatrix(camera.combined) ;

		GVars_Parralax.background.render() ;
		staticBatch.begin() ;
		for(int i = 0 ; i < styles.length ; i++)
		{
			if(clock[i] < alive)
				monsters[i].draw(staticBatch) ;
			else if(deaths[i] != null && !deaths[i].done())
				deaths[i].draw(staticBatch) ;
		}
		staticBatch.end() ;
		GVars_Parralax.foreground.render() ;

		stage.getViewport().apply() ;
		stage.draw() ;
	}

	@Override
	public void resize(int width, int height)
	{
		GVars_Camera.resize(width, height) ;
		stage.getViewport().update(width, height, true) ;
	}

	@Override
	public void dispose()
	{
		Gdx.input.setInputProcessor(null) ;
		stage.dispose() ;
		GVars_Camera.dispose() ;
		Death_Fx.Kit.dispose() ;
	}

	private final InputAdapter keys = new InputAdapter()
	{
		@Override
		public boolean keyDown(int keycode)
		{
			if(keycode == Keys.SPACE)
				killNow(-1) ;
			else if(keycode >= Keys.NUM_1 && keycode < Keys.NUM_1 + styles.length)
				killNow(keycode - Keys.NUM_1) ;
			else if(keycode == Keys.S)
				slow = !slow ;
			else if(keycode == Keys.M)
			{
				model++ ;
				newMonsters() ;
			}
			else if(keycode == Keys.TAB)
				page(page % Death_Fx.Style.pages() + 1) ;
			else if(keycode == Keys.ESCAPE)
				GVars_Heart.changeVue(new Vue_Menu()) ;
			else
				return false ;
			return true ;
		}
	} ;
}
