import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import jks.amain.Main_Game;
import jks.input.Menu_Picker;
import jks.net.Net_Message;
import jks.net.Transport_Udp;
import jks.online.ClientSession;
import jks.online.Game_Simulation;
import jks.online.HostSession;
import jks.sounds.GVars_Audio;
import jks.story.GVars_Story;
import jks.vars.FVars_Heart;
import jks.vars.GVars_Heart;
import jks.vue.models.Vue_Client;
import jks.vue.models.Vue_Game;

/**
 * Escape on an ONLINE CLIENT (r90, proven by r106) in two real windows : a host and a --join client,
 * each its own JVM, talking over UDP on loopback, coordinated through files in <out>.
 *
 *   host  a zero-hero run behind its own HostSession on a port the OS picks (out/port.txt) ; writes what
 *         its seats did to out/host_status.txt twice a second ; on out/end it plays the long middle of
 *         the song without the wire, a few thousand steps a frame, to the score screen ; on out/newrun it
 *         picks New run there ; on out/done it closes.
 *   join  Main_Game as --join makes it, driven by keys pressed through the window's own input processor :
 *         joins, jumps (the host counts the press), Escape shows the pause over snapshots that keep coming,
 *         Escape again gives the keys back (a jump reaches the host), a pause up when the host's song ends
 *         gives way to the score screen, and on the host's new run Escape, Down, Enter takes Leave : the
 *         host hears LEAVE (QUIT) and this window is on Vue_Menu.
 *
 * Every check prints PASS or FAIL ; frames are client_*.png and host_*.png, bottom-up (GL origin) :
 * magick in.png -flip out.png. tools/probe/pause_online.sh runs both and fails on any FAIL.
 */
public class Pause_Online_Probe implements ApplicationListener
{
	final Main_Game game = new Main_Game() ;
	final boolean hosting ;
	final Path out ;
	int frame ;

	Pause_Online_Probe(boolean hosting, String out)
	{
		this.hosting = hosting ;
		this.out = Paths.get(out) ;
	}

	public static void main(String[] arg) throws Exception
	{
		boolean hosting = arg[0].equals("host") ;
		String out = arg.length > 1 ? arg[1] : "." ;
		GVars_Audio.muted = true ;
		GVars_Heart.startAtMenu = false ;
		if(!hosting)
			GVars_Heart.joinAddress = "127.0.0.1:" + waitFor(Paths.get(out, "port.txt"), 60).trim() ;
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration() ;
		config.setWindowedMode(1280, 720) ;
		config.setForegroundFPS(60) ;
		config.setTitle("pause online probe " + arg[0]) ;
		new Lwjgl3Application(new Pause_Online_Probe(hosting, out), config) ;
	}

	public void create()
	{
		game.create() ;
		if(hosting)
			openHost() ;
	}

	public void render()
	{
		frame++ ;
		try
		{
			if(hosting)
				hostFrame() ;
			else
			{
				game.render() ;
				clientFrame() ;
			}
		}
		catch(Exception e)
		{throw new RuntimeException(e) ;}
	}

	// ---------------------------------------------------------------- the host

	HostSession host ;
	final List<String> left = new ArrayList<String>() ;
	float accumulator ;
	boolean pickedNewRun ;
	final Runnable step = () -> GVars_Heart.vue.update(FVars_Heart.step) ;

	void openHost()
	{
		Transport_Udp socket = Transport_Udp.open() ;
		host = new HostSession(socket, new Game_Simulation(), new HostSession.Events()
		{
			@Override
			public void left(HostSession.Seat seat, Net_Message.Leave.Reason reason)
			{
				left.add(seat.player.number() + ":" + reason) ;
				Gdx.app.log("probe", "host : player " + seat.player.number() + " left, " + reason) ;
			}
		}) ;
		write("port.txt", "" + socket.localPort()) ;
		Gdx.app.log("probe", "host : letting clients in on UDP port " + socket.localPort()) ;
	}

	/** Main_Game's loop, with this probe's HostSession, and the long middle skipped on out/end. */
	void hostFrame() throws Exception
	{
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT) ;
		accumulator = Math.min(accumulator + Gdx.graphics.getDeltaTime(), 0.25f) ;
		while(accumulator >= FVars_Heart.step)
		{
			if(GVars_Heart.vue instanceof Vue_Game)
				host.tick(step) ;
			else
				step.run() ;
			accumulator -= FVars_Heart.step ;
		}
		// About 12 s of game a frame without the wire : the client hears a snapshot every frame all the same
		if(Files.exists(out.resolve("end")))
			for(int i = 0 ; i < 720 && GVars_Story.storyTime() < 335 ; i++)
				step.run() ;
		Vue_Game run = (Vue_Game) GVars_Heart.vue ;
		// Once : the new run is played at its own pace
		if(run.scoreChoices() != null)
			Files.deleteIfExists(out.resolve("end")) ;
		if(Files.exists(out.resolve("newrun")) && !pickedNewRun && run.scoreChoices() != null)
		{
			pickedNewRun = true ;
			GVars_Heart.vue.render() ;
			grab("host_score.png") ;
			run.scoreChoices().pick(Menu_Picker.KEYBOARD) ;
		}
		else
			GVars_Heart.vue.render() ;

		if(frame % 30 == 0)
		{
			int presses = 0 ;
			for(HostSession.Seat seat : host.seats())
				presses += seat.pressesApplied ;
			write("host_status.txt", "presses=" + presses + " seats=" + host.seats().size() + " left=" + left
					+ " story=" + GVars_Story.storyTime() + " over=" + (run.scoreChoices() != null)) ;
		}
		if(Files.exists(out.resolve("done")))
		{
			host.close() ;
			Gdx.app.exit() ;
		}
	}

	// ---------------------------------------------------------------- the client

	int phase ;
	int since ;
	int presses, tickAtPause, runAtEnd ;
	boolean failed ;

	void clientFrame() throws Exception
	{
		Vue_Client view = GVars_Heart.vue instanceof Vue_Client ? (Vue_Client) GVars_Heart.vue : null ;
		ClientSession client = view == null ? null : view.session() ;
		int waited = frame - since ;
		if(waited > 60 * 30)
			finish("FAIL phase " + phase + " waited 30 s") ;

		if(phase == 1 && runProcessor == null)
			runProcessor = processor() ;
		switch(phase)
		{
			case 0 :	// in, then the first key only asks for a hero
				if(client != null && client.state() == ClientSession.State.IN)
				{
					key(Keys.SPACE) ;
					next() ;
				}
				break ;
			case 1 :
				if(client.hasHeroInNewest() && waited > 60)
				{
					presses = hostPresses() ;
					key(Keys.SPACE) ;
					next() ;
				}
				break ;
			case 2 :	// the jump reached the host
				if(hostPresses() > presses)
				{
					check("a jump before the pause reaches the host", true, "presses " + presses + " -> " + hostPresses()) ;
					tickAtPause = client.newest().tick ;
					key(Keys.ESCAPE) ;
					next() ;
				}
				break ;
			case 3 :
				if(waited == 60)
					key(Keys.D) ;	// a swing, pressed under the pause : the menu has it, never the run
				if(waited == 120)
				{
					check("Escape shows the pause screen", pause(view) != null, "pause " + pause(view)) ;
					check("the input processor is the pause screen's, not the run's", !processor().equals(runProcessor), "processor " + processor()) ;
					int tick = client.newest().tick ;
					check("snapshots keep coming under the pause (the run goes on)", tick > tickAtPause + 30 && client.state() == ClientSession.State.IN, "tick " + tickAtPause + " -> " + tick + ", " + client.state()) ;
					grab("client_paused.png") ;
					presses = hostPresses() ;
					key(Keys.ESCAPE) ;
					next() ;
				}
				break ;
			case 4 :
				if(waited == 30)
				{
					check("Escape again takes the pause away", pause(view) == null, "pause " + pause(view)) ;
					check("the run's keys are back", processor().equals(runProcessor), "processor " + processor()) ;
					check("presses while paused never reached the host", hostPresses() == presses, "presses " + presses + " -> " + hostPresses()) ;
					presses = hostPresses() ;
					key(Keys.SPACE) ;
					next() ;
				}
				break ;
			case 5 :
				if(hostPresses() > presses)
				{
					check("after Resume, a jump reaches the host", true, "presses " + presses + " -> " + hostPresses()) ;
					grab("client_resumed.png") ;
					key(Keys.ESCAPE) ;
					runAtEnd = client.view().run ;
					write("end", "") ;
					next() ;
				}
				break ;
			case 6 :	// the host's song ends under this client's pause
				if(waited == 1)
					check("the pause is up when the host's song ends", pause(view) != null, "pause " + pause(view)) ;
				if(field(view, "scoreScreen") != null)
				{
					check("a pause up when the song ends gives way to the score screen", pause(view) == null, "pause " + pause(view)) ;
					next() ;
				}
				break ;
			case 7 :
				if(waited == 60)
				{
					grab("client_score.png") ;
					write("newrun", "") ;
					next() ;
				}
				break ;
			case 8 :	// the host's new run : this client pauses again and leaves
				if(client.view().run != runAtEnd && field(view, "scoreScreen") == null && waited > 60)
				{
					key(Keys.ESCAPE) ;
					next() ;
				}
				break ;
			case 9 :
				if(waited == 30)
				{
					check("Escape pauses the new run", pause(view) != null, "pause " + pause(view)) ;
					grab("client_leave.png") ;
					key(Keys.DOWN) ;
					key(Keys.ENTER) ;
					next() ;
				}
				break ;
			case 10 :
				if(waited == 90)
				{
					String vue = GVars_Heart.vue.getClass().getSimpleName() ;
					check("Leave lands on the menu", vue.equals("Vue_Menu"), "view " + vue) ;
					String status = hostStatus() ;
					check("the host heard LEAVE (QUIT)", status.contains(":QUIT"), "host " + status) ;
					grab("client_menu.png") ;
					finish("done") ;
				}
				break ;
		}
	}

	InputProcessor runProcessor ;

	void next()
	{
		phase++ ;
		since = frame ;
		Gdx.app.log("probe", "client : phase " + phase + " at frame " + frame + " ; host " + hostStatus()) ;
	}

	void finish(String why)
	{
		Gdx.app.log("probe", "client : " + why + (failed ? " (with FAILs)" : "")) ;
		write("done", "") ;
		Gdx.app.exit() ;
		phase = 99 ;
	}

	InputProcessor processor()
	{
		InputProcessor p = Gdx.input.getInputProcessor() ;
		// Each listen() makes a new multiplexer : compare what it holds, not the object
		return p instanceof InputMultiplexer ? new Holding(((InputMultiplexer) p).getProcessors().toString()) : p ;
	}

	record Holding(String what) implements InputProcessor
	{
		public boolean keyDown(int k) {return false ;}
		public boolean keyUp(int k) {return false ;}
		public boolean keyTyped(char c) {return false ;}
		public boolean touchDown(int x, int y, int p, int b) {return false ;}
		public boolean touchUp(int x, int y, int p, int b) {return false ;}
		public boolean touchCancelled(int x, int y, int p, int b) {return false ;}
		public boolean touchDragged(int x, int y, int p) {return false ;}
		public boolean mouseMoved(int x, int y) {return false ;}
		public boolean scrolled(float x, float y) {return false ;}
	}

	static Object pause(Vue_Client view) throws Exception
	{return field(view, "pauseScreen") ;}

	static Object field(Object on, String name) throws Exception
	{
		Field f = on.getClass().getDeclaredField(name) ;
		f.setAccessible(true) ;
		return f.get(on) ;
	}

	String hostStatus()
	{
		try
		{return Files.readString(out.resolve("host_status.txt")) ;}
		catch(Exception e)
		{return "" ;}
	}

	int hostPresses()
	{
		String status = hostStatus() ;
		int at = status.indexOf("presses=") ;
		return at < 0 ? 0 : Integer.parseInt(status.substring(at + 8, status.indexOf(' ', at))) ;
	}

	// ---------------------------------------------------------------- both

	void key(int keycode)
	{
		Gdx.input.getInputProcessor().keyDown(keycode) ;
		Gdx.input.getInputProcessor().keyUp(keycode) ;
	}

	void check(String what, boolean ok, String seen)
	{
		failed |= !ok ;
		Gdx.app.log("probe", (ok ? "PASS " : "FAIL ") + what + " (" + seen + ")") ;
	}

	void write(String name, String text)
	{
		try
		{
			Path tmp = out.resolve(name + ".tmp") ;
			Files.writeString(tmp, text) ;
			Files.move(tmp, out.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) ;
		}
		catch(Exception e)
		{throw new RuntimeException(e) ;}
	}

	static String waitFor(Path file, int seconds) throws Exception
	{
		for(int i = 0 ; i < seconds * 10 ; i++)
		{
			if(Files.exists(file))
				return Files.readString(file) ;
			Thread.sleep(100) ;
		}
		throw new IllegalStateException("no " + file + " after " + seconds + " s") ;
	}

	void grab(String name)
	{
		Graphics g = Gdx.graphics ;
		Pixmap pixmap = Pixmap.createFromFrameBuffer(0, 0, g.getBackBufferWidth(), g.getBackBufferHeight()) ;
		PixmapIO.writePNG(Gdx.files.absolute(out.resolve(name).toString()), pixmap) ;
		pixmap.dispose() ;
		Gdx.app.log("probe", "wrote " + name + " at frame " + frame) ;
	}

	public void resize(int width, int height) {game.resize(width, height) ;}
	public void pause() {game.pause() ;}
	public void resume() {game.resume() ;}
	public void dispose()
	{
		Gdx.app.log("probe", (hosting ? "host" : "client") + " : window closing at frame " + frame) ;
		game.dispose() ;
	}
}
