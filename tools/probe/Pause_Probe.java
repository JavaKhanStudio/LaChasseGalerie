import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import jks.amain.Main_Game;
import jks.sounds.GVars_AudioManager;
import jks.story.GVars_Story;
import jks.vars.GVars_Heart;

/**
 * Escape on a LOCAL run (r90) in a real window, pressed through the window's own input processor as a
 * key would be : the keyboard joins, Escape pauses (the story clock must not move and the river sound
 * must stop), Escape again resumes (the clock moves on), then Escape, Down, Enter takes Menu. Every
 * check prints PASS or FAIL ; the frames are paused.png, resumed.png and after_menu.png, bottom-up
 * (GL origin) : magick in.png -flip out.png.
 *
 *   ./gradlew dist && javac -cp desktop/build/libs/LaChasseGalerie-1.0.jar -d /tmp/probe tools/probe/Pause_Probe.java
 *   cd desktop/assets && ../../tools/offscreen.sh java -cp ../build/libs/LaChasseGalerie-1.0.jar:/tmp/probe Pause_Probe /tmp/probe
 */
public class Pause_Probe implements ApplicationListener
{
	final Main_Game game = new Main_Game() ;
	final String out ;
	int frame ;
	float pausedAt ;

	Pause_Probe(String out)
	{this.out = out ;}

	public static void main(String[] arg)
	{
		GVars_Heart.startAtMenu = false ;
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration() ;
		config.setWindowedMode(1280, 720) ;
		// A frame a step : uncapped, cage draws hundreds of frames a second and a pick can wait out the probe
		config.setForegroundFPS(60) ;
		config.setTitle("pause probe") ;
		new Lwjgl3Application(new Pause_Probe(arg.length > 0 ? arg[0] : "."), config) ;
	}

	public void create()
	{game.create() ;}

	public void render()
	{
		game.render() ;
		frame++ ;
		if(frame == 30)
			key(Keys.SPACE) ;		// joins
		if(frame == 90)
		{
			pausedAt = GVars_Story.storyTime() ;
			key(Keys.ESCAPE) ;
		}
		if(frame == 180)
		{
			check("the clock stands still while paused", GVars_Story.storyTime() == pausedAt, "story " + pausedAt + " -> " + GVars_Story.storyTime()) ;
			check("the river sound is paused", !playing(), "ambiance playing : " + playing()) ;
			grab("paused.png") ;
			key(Keys.ESCAPE) ;
		}
		if(frame == 240)
		{
			check("the clock moves on after Escape again", GVars_Story.storyTime() > pausedAt, "story " + pausedAt + " -> " + GVars_Story.storyTime()) ;
			check("the river sound plays again", playing(), "ambiance playing : " + playing()) ;
			grab("resumed.png") ;
			key(Keys.ESCAPE) ;
		}
		if(frame == 250)
		{
			key(Keys.DOWN) ;
			key(Keys.ENTER) ;
		}
		if(frame == 300)
		{
			String vue = GVars_Heart.vue.getClass().getSimpleName() ;
			check("Menu goes back to the start menu", vue.equals("Vue_Menu"), "view " + vue) ;
			grab("after_menu.png") ;
			Gdx.app.exit() ;
		}
	}

	static boolean playing()
	{return GVars_AudioManager.currentlyRunningAmbiance != null && GVars_AudioManager.currentlyRunningAmbiance.isPlaying() ;}

	void key(int keycode)
	{
		Gdx.input.getInputProcessor().keyDown(keycode) ;
		Gdx.input.getInputProcessor().keyUp(keycode) ;
	}

	void check(String what, boolean ok, String seen)
	{Gdx.app.log("probe", (ok ? "PASS " : "FAIL ") + what + " (" + seen + ")") ;}

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
	public void dispose()
	{
		Gdx.app.log("probe", "window closing at frame " + frame) ;
		game.dispose() ;
	}
}
