import java.lang.reflect.Field;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import jks.amain.Main_Game;
import jks.input.GVars_Controller;
import jks.input.Menu_Picker;
import jks.personnage.ScoreLabel;
import jks.story.GVars_Story;
import jks.vars.GVars_Game;
import jks.vars.GVars_Heart;
import jks.vue.models.Vue_Game;

/**
 * Renders the end of a LOCAL run (d15) in a real window : two players join, the story jumps to just
 * before the descent, and the probe writes the frame after the landing, the score screen, and the menu
 * that Menu opens. PNGs come out bottom-up (GL origin) : magick in.png -flip out.png.
 *
 *   ./gradlew dist && javac -cp desktop/build/libs/LaChasseGalerie-1.0.jar -d /tmp/probe tools/probe/Score_Screen_Probe.java
 *   cd assets && ../tools/offscreen.sh java -cp ../desktop/build/libs/LaChasseGalerie-1.0.jar:/tmp/probe Score_Screen_Probe /tmp/probe
 */
public class Score_Screen_Probe implements ApplicationListener
{
	final Main_Game game = new Main_Game() ;
	final String out ;
	int frame ;
	Vue_Game run ;
	int shown = -1 ;

	Score_Screen_Probe(String out)
	{this.out = out ;}

	public static void main(String[] arg)
	{
		GVars_Heart.startAtMenu = false ;
		jks.sounds.GVars_Audio.muted = true ;
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration() ;
		config.setWindowedMode(1280, 720) ;
		config.setTitle("score screen probe") ;
		new Lwjgl3Application(new Score_Screen_Probe(arg.length > 0 ? arg[0] : "."), config) ;
	}

	public void create()
	{
		game.create() ;
		Gdx.app.log("probe", "GL " + Gdx.gl.glGetString(com.badlogic.gdx.graphics.GL20.GL_RENDERER)) ;
	}

	public void render()
	{
		game.render() ;
		frame++ ;
		try
		{
			if(frame == 30)
			{
				run = (Vue_Game) GVars_Heart.vue ;
				GVars_Game.addPlayer(GVars_Controller.identify(null)) ;
				GVars_Game.addPlayer(GVars_Controller.identify(null)) ;
				int i = 0 ;
				for(ScoreLabel label : GVars_Game.playerRegister.values())
				{
					label.scoreNumber = i == 0 ? 37 : 52 ;
					label.deathNumber = i == 0 ? 3 : 5 ;
					i++ ;
				}
				Field time = GVars_Story.class.getDeclaredField("timming_currentStoryTime") ;
				time.setAccessible(true) ;
				Field landing = GVars_Story.class.getDeclaredField("timming_timeUntil_Landing") ;
				landing.setAccessible(true) ;
				// Jump to just before the descent, so the canoe comes down as it would
				time.setFloat(null, landing.getFloat(null) - 11f) ;
			}
			if(run != null && shown < 0 && run.scoreChoices() != null)
				shown = frame ;
			if(shown > 0 && frame == shown + 60)
			{
				grab("score_screen.png") ;
				run.scoreChoices().move(1) ;
				run.scoreChoices().pick(Menu_Picker.POINTER) ;
			}
			if(shown > 0 && frame == shown + 120)
			{
				Gdx.app.log("probe", "after Menu : " + GVars_Heart.vue.getClass().getSimpleName()) ;
				grab("after_menu.png") ;
				Gdx.app.exit() ;
			}
		}
		catch(Exception e)
		{throw new RuntimeException(e) ;}
	}

	void grab(String name)
	{
		Graphics g = Gdx.graphics ;
		Pixmap pixmap = Pixmap.createFromFrameBuffer(0, 0, g.getBackBufferWidth(), g.getBackBufferHeight()) ;
		PixmapIO.writePNG(Gdx.files.absolute(out + "/" + name), pixmap) ;
		pixmap.dispose() ;
		Gdx.app.log("probe", "wrote " + name + " at frame " + frame) ;
	}

	public void resize(int width, int height) {game.resize(width, height) ;}
	public void pause() {game.pause() ;}
	public void resume() {game.resume() ;}
	public void dispose() {game.dispose() ;}
}
