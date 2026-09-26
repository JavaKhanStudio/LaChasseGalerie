import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import jks.amain.Main_Game;
import jks.physic.FVars_Physic;
import jks.physic.objects.PhysicSpriteHp;
import jks.vars.GVars_Game;

/**
 * Where potions land (r94) in a real window : nobody joins, so nobody drinks them, and the probe drops
 * twelve at once through GVars_Game.dropHp, then writes potions_falling.png and potions_landed.png and
 * logs every potion's x against the canoe's deck. Bottom-up (GL origin) : magick in.png -flip out.png.
 *
 *   ./gradlew dist && javac -cp desktop/build/libs/LaChasseGalerie-1.0.jar -d /tmp/probe tools/probe/Potion_Probe.java
 *   cd desktop/assets && ../../tools/offscreen.sh java -cp ../build/libs/LaChasseGalerie-1.0.jar:/tmp/probe Potion_Probe /tmp/probe
 */
public class Potion_Probe implements ApplicationListener
{
	final Main_Game game = new Main_Game() ;
	final String out ;
	int frame ;

	Potion_Probe(String out)
	{this.out = out ;}

	public static void main(String[] arg)
	{
		jks.sounds.GVars_Audio.muted = true ;
		jks.vars.GVars_Heart.startAtMenu = false ;
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration() ;
		config.setWindowedMode(1280, 720) ;
		config.setForegroundFPS(60) ;
		config.setTitle("potion probe") ;
		new Lwjgl3Application(new Potion_Probe(arg.length > 0 ? arg[0] : "."), config) ;
	}

	public void create()
	{game.create() ;}

	public void render()
	{
		game.render() ;
		frame++ ;
		if(frame == 30)
			for(int i = 0 ; i < 12 ; i++)
				GVars_Game.dropHp() ;
		if(frame == 90)
			grab("potions_falling.png") ;
		if(frame == 420)
		{
			grab("potions_landed.png") ;
			int on = 0 ;
			for(PhysicSpriteHp potion : GVars_Game.hpStack)
			{
				float x = potion.body.getPosition().x * FVars_Physic.PPM ;
				if(x >= GVars_Game.canoe.deckLeft() && x <= GVars_Game.canoe.deckRight())
					on++ ;
			}
			Gdx.app.log("probe", (on == 12 ? "PASS " : "FAIL ") + on + " of 12 potions on the deck " + GVars_Game.canoe.deckLeft() + ".." + GVars_Game.canoe.deckRight() + ", " + GVars_Game.hpStack.size() + " still in the run") ;
			Gdx.app.exit() ;
		}
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
