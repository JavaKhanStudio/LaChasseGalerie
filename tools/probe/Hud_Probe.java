import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import jks.amain.Main_Game;
import jks.input.GVars_Controller;
import jks.vars.GVars_Game;

/**
 * Plays the game in a real window, joins one keyboard player, and writes the frame as a PNG
 * (r84 : the HUD's score and death icons under "Player 1"), bottom-up (GL origin) :
 * magick hud.png -flip hud.png.
 *
 *   ./gradlew dist
 *   javac -cp desktop/build/libs/LaChasseGalerie-1.0.jar -d /tmp/probe tools/probe/Hud_Probe.java
 *   cd /tmp && .../tools/offscreen.sh java -cp <repo>/desktop/build/libs/LaChasseGalerie-1.0.jar:/tmp/probe Hud_Probe /tmp/probe
 */
public class Hud_Probe implements ApplicationListener
{
	final Main_Game game = new Main_Game() ;
	final String out ;
	final long started = System.currentTimeMillis() ;
	boolean joined ;

	Hud_Probe(String out)
	{this.out = out ;}

	public static void main(String[] arg)
	{
		jks.sounds.GVars_Audio.muted = true ;
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration() ;
		config.setWindowedMode(1280, 720) ;
		config.setForegroundFPS(60) ;
		config.setTitle("hud probe") ;
		new Lwjgl3Application(new Hud_Probe(arg.length > 0 ? arg[0] : "."), config) ;
	}

	public void create()
	{game.create() ;}

	public void render()
	{
		game.render() ;
		long ms = System.currentTimeMillis() - started ;
		if(!joined && ms > 1_000 && GVars_Game.heroes != null)
		{
			jks.vars.GVars_Random.seed(1) ; // the hero, and so the icons, always get the same colour
			GVars_Game.addPlayer(GVars_Controller.identify(null)) ;
			joined = true ;
		}
		if(ms < 3_000)
			return ;
		Graphics g = Gdx.graphics ;
		Pixmap pixmap = Pixmap.createFromFrameBuffer(0, 0, g.getBackBufferWidth(), g.getBackBufferHeight()) ;
		PixmapIO.writePNG(Gdx.files.absolute(out + "/hud.png"), pixmap) ;
		pixmap.dispose() ;
		System.out.println("PROBE wrote " + out + "/hud.png") ;
		Gdx.app.exit() ;
	}

	public void resize(int width, int height) {game.resize(width, height) ;}
	public void pause() {game.pause() ;}
	public void resume() {game.resume() ;}
	public void dispose() {game.dispose() ;}
}
