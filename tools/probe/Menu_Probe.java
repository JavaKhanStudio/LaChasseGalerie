import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import jks.amain.Main_Game;
import jks.vars.GVars_Heart;

/**
 * Draws the start menu in a real window and writes it as a PNG (r71, the logo above the title), bottom-up
 * (GL origin) : magick menu.png -flip menu.png.
 *
 *   ./gradlew dist
 *   javac -cp desktop/build/libs/LaChasseGalerie-1.0.jar -d /tmp/probe tools/probe/Menu_Probe.java
 *   cd /tmp && .../tools/offscreen.sh java -cp <repo>/desktop/build/libs/LaChasseGalerie-1.0.jar:/tmp/probe Menu_Probe /tmp/probe
 *
 * Run from another directory on purpose : the logo must load from the jar, as the dist does.
 * -Dprobe.size=1920x1080 needs gamescope's headless backend (see Lobby_Screen_Probe).
 */
public class Menu_Probe implements ApplicationListener
{
	final Main_Game game = new Main_Game() ;
	final String out ;
	final long started = System.currentTimeMillis() ;

	Menu_Probe(String out)
	{this.out = out ;}

	public static void main(String[] arg)
	{
		GVars_Heart.startAtMenu = true ;
		jks.sounds.GVars_Audio.muted = true ;
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration() ;
		String[] size = System.getProperty("probe.size", "1280x720").split("x") ;
		config.setWindowedMode(Integer.parseInt(size[0]), Integer.parseInt(size[1])) ;
		config.setForegroundFPS(60) ;
		config.setTitle("menu probe") ;
		new Lwjgl3Application(new Menu_Probe(arg.length > 0 ? arg[0] : "."), config) ;
	}

	public void create()
	{game.create() ;}

	public void render()
	{
		game.render() ;
		if(System.currentTimeMillis() - started < 1_500)
			return ;
		Graphics g = Gdx.graphics ;
		Pixmap pixmap = Pixmap.createFromFrameBuffer(0, 0, g.getBackBufferWidth(), g.getBackBufferHeight()) ;
		String name = "menu_" + g.getBackBufferWidth() + "x" + g.getBackBufferHeight() + ".png" ;
		PixmapIO.writePNG(Gdx.files.absolute(out + "/" + name), pixmap) ;
		pixmap.dispose() ;
		System.out.println("PROBE wrote " + out + "/" + name) ;
		Gdx.app.exit() ;
	}

	public void resize(int width, int height) {game.resize(width, height) ;}
	public void pause() {game.pause() ;}
	public void resume() {game.resume() ;}
	public void dispose() {game.dispose() ;}
}
