import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import jks.amain.Main_Game;
import jks.sounds.GVars_Audio;
import jks.vars.GVars_Heart;
import jks.vue.models.Vue_Lab_Death;

/**
 * The death lab (r98) in a real window : every column dies on the same frame, and the frames after it
 * are written as sheet_NN.png (at the lab times in `sheet`) and gif/fNNN.png (every 1/30 s of lab time
 * for 1.25 s, in slow motion), bottom-up (GL origin). tools/probe/death_lab.sh turns them into
 * death_sheet.png and death.gif.
 */
public class Death_Lab_Probe implements ApplicationListener
{
	final Main_Game game = new Main_Game() ;
	final String out ;
	int frame ;
	static final int kill = 90 ;
	/** Lab seconds after the kill : the sheet's rows, and the gif's step (real time at 30 frames a second). */
	static final float[] sheet = {0.03f, 0.1f, 0.2f, 0.35f, 0.55f, 0.8f} ;
	static final float gifStep = 1 / 30f, gifLength = 1.25f ;
	int sheetDone, gifDone ;

	Death_Lab_Probe(String out)
	{this.out = out ;}

	public static void main(String[] arg)
	{
		GVars_Audio.muted = true ;
		GVars_Heart.lab = "death" ;
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration() ;
		config.setWindowedMode(1280, 720) ;
		config.setForegroundFPS(60) ;
		config.setTitle("death lab probe") ;
		new Lwjgl3Application(new Death_Lab_Probe(arg.length > 0 ? arg[0] : "."), config) ;
	}

	public void create()
	{game.create() ;}

	public void render()
	{
		Vue_Lab_Death lab = (Vue_Lab_Death) GVars_Heart.vue ;
		if(frame == kill)
		{
			// Slow motion : a render frame is a quarter of the lab time, so the gif misses less between steps
			lab.slow(true) ;
			lab.killNow(-1) ;
		}
		game.render() ;
		if(frame == kill - 30)
			grab("alive.png") ;
		float since = lab.sinceKill() ;
		if(since < 0)
		{
			frame++ ;
			return ;
		}
		if(sheetDone < sheet.length && since >= sheet[sheetDone])
			grab(String.format("sheet_%02d.png", sheetDone++)) ;
		if(since <= gifLength && since >= gifDone * gifStep)
		{
			grab(String.format("gif/f%03d.png", gifDone)) ;
			while(gifDone * gifStep <= since)
				gifDone++ ;
		}
		if(since > gifLength && sheetDone == sheet.length)
			Gdx.app.exit() ;
		frame++ ;
	}

	void grab(String name)
	{
		Graphics g = Gdx.graphics ;
		Pixmap pixmap = Pixmap.createFromFrameBuffer(0, 0, g.getBackBufferWidth(), g.getBackBufferHeight()) ;
		PixmapIO.writePNG(Gdx.files.absolute(out + "/" + name), pixmap) ;
		pixmap.dispose() ;
	}

	public void resize(int width, int height) {game.resize(width, height) ;}
	public void pause() {game.pause() ;}
	public void resume() {game.resume() ;}
	public void dispose() {game.dispose() ;}
}
