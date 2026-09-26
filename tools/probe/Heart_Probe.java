import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import jks.amain.Main_Game;
import jks.input.GVars_Controller;
import jks.personnage.PhysicSpriteHeroes;
import jks.vars.GVars_Game;

/**
 * A heart lost (r91) in a real window : one keyboard player joins, is hurt once (4 hearts to 3), and
 * the probe writes a frame every 3 frames (0.05 s) from the hit on, heart_00.png to heart_19.png, and
 * logs where the hero's hearts are on the window. Bottom-up (GL origin) : magick in.png -flip out.png.
 *
 *   ./gradlew dist && javac -cp desktop/build/libs/LaChasseGalerie-1.0.jar -d /tmp/probe tools/probe/Heart_Probe.java
 *   cd desktop/assets && ../../tools/offscreen.sh java -cp ../build/libs/LaChasseGalerie-1.0.jar:/tmp/probe Heart_Probe /tmp/probe
 */
public class Heart_Probe implements ApplicationListener
{
	final Main_Game game = new Main_Game() ;
	final String out ;
	int frame ;
	PhysicSpriteHeroes hero ;

	Heart_Probe(String out)
	{this.out = out ;}

	public static void main(String[] arg)
	{
		jks.sounds.GVars_Audio.muted = true ;
		jks.vars.GVars_Heart.startAtMenu = false ;
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration() ;
		config.setWindowedMode(1280, 720) ;
		// A frame a step, so a frame count is a time
		config.setForegroundFPS(60) ;
		config.setTitle("heart probe") ;
		new Lwjgl3Application(new Heart_Probe(arg.length > 0 ? arg[0] : "."), config) ;
	}

	public void create()
	{game.create() ;}

	public void render()
	{
		game.render() ;
		frame++ ;
		if(frame == 30)
		{
			jks.vars.GVars_Random.seed(1) ;
			GVars_Game.addPlayer(GVars_Controller.identify(null)) ;
			hero = GVars_Game.heroes.get(0) ;
		}
		if(frame == 90)
		{
			// A hero joins invulnerable : the hit has to land
			hero.invulnerable = false ;
			hero.getHurt(null) ;
		}
		if(frame >= 90 && (frame - 90) % 3 == 0 && frame < 90 + 3 * 20)
		{
			com.badlogic.gdx.math.Vector3 at = jks.camera.GVars_Camera.camera.project(new com.badlogic.gdx.math.Vector3(hero.body.getPosition().x * jks.physic.FVars_Physic.PPM, hero.body.getPosition().y * jks.physic.FVars_Physic.PPM, 0)) ;
			grab(String.format("heart_%02d.png", (frame - 90) / 3), "story " + jks.story.GVars_Story.storyTime() + ", hp " + hero.hp_left + ", hero centre on the window at " + (int) at.x + "," + (int) at.y) ;
		}
		if(frame == 90 + 3 * 20)
			Gdx.app.exit() ;
	}

	void grab(String name, String seen)
	{
		Graphics g = Gdx.graphics ;
		Pixmap pixmap = Pixmap.createFromFrameBuffer(0, 0, g.getBackBufferWidth(), g.getBackBufferHeight()) ;
		PixmapIO.writePNG(Gdx.files.absolute(out + "/" + name), pixmap) ;
		pixmap.dispose() ;
		Gdx.app.log("probe", "wrote " + name + " at frame " + frame + " : " + seen) ;
	}

	public void resize(int width, int height) {game.resize(width, height) ;}
	public void pause() {game.pause() ;}
	public void resume() {game.resume() ;}
	public void dispose() {game.dispose() ;}
}
