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
import jks.story.GVars_Story;
import jks.vars.GVars_Game;

/**
 * The canoe's flight (r93) in a real window : one keyboard player joins, the story jumps to just before
 * the take-off, and the probe writes a frame every 2 s of story time from then, flight_<story s>.png,
 * logging the sky scroll and the canoe's height. -Dprobe.until=<story s> (default 90). Bottom-up (GL
 * origin) : magick in.png -flip out.png.
 *
 *   ./gradlew dist && javac -cp desktop/build/libs/LaChasseGalerie-1.0.jar -d /tmp/probe tools/probe/Flight_Probe.java
 *   cd desktop/assets && ../../tools/offscreen.sh java -cp ../build/libs/LaChasseGalerie-1.0.jar:/tmp/probe Flight_Probe /tmp/probe
 */
public class Flight_Probe implements ApplicationListener
{
	final Main_Game game = new Main_Game() ;
	final String out ;
	final float until = Float.parseFloat(System.getProperty("probe.until", "90")) ;
	int frame ;
	float nextGrab ;

	Flight_Probe(String out)
	{this.out = out ;}

	public static void main(String[] arg)
	{
		jks.sounds.GVars_Audio.muted = true ;
		jks.vars.GVars_Heart.startAtMenu = false ;
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration() ;
		config.setWindowedMode(1280, 720) ;
		config.setForegroundFPS(60) ;
		config.setTitle("flight probe") ;
		new Lwjgl3Application(new Flight_Probe(arg.length > 0 ? arg[0] : "."), config) ;
	}

	public void create()
	{game.create() ;}

	public void render()
	{
		game.render() ;
		frame++ ;
		try
		{
			if(frame == 30)
			{
				jks.vars.GVars_Random.seed(1) ;
				GVars_Game.addPlayer(GVars_Controller.identify(null)) ;
				Field time = GVars_Story.class.getDeclaredField("timming_currentStoryTime") ;
				time.setAccessible(true) ;
				Field takeOff = GVars_Story.class.getDeclaredField("timming_timeUntil_TakeOff") ;
				takeOff.setAccessible(true) ;
				time.setFloat(null, takeOff.getFloat(null) - 1f) ;
				nextGrab = takeOff.getFloat(null) - 1f ;
			}
			if(frame > 30 && GVars_Story.storyTime() >= nextGrab)
			{
				grab(String.format("flight_%03d.png", (int) nextGrab)) ;
				nextGrab += 2 ;
			}
			if(frame > 30 && GVars_Story.storyTime() > until)
				Gdx.app.exit() ;
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
		Gdx.app.log("probe", name + " : story " + GVars_Story.storyTime() + ", sky " + GVars_Story.skyScroll() + ", canoe y " + GVars_Game.canoe.body.getPosition().y + " angle " + GVars_Game.canoe.body.getAngle()) ;
	}

	public void resize(int width, int height) {game.resize(width, height) ;}
	public void pause() {game.pause() ;}
	public void resume() {game.resume() ;}
	public void dispose() {game.dispose() ;}
}
