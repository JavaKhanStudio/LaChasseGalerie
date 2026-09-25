import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import jks.amain.Main_Game;
import jks.input.Menu_Picker;
import jks.lobby.Lobby_Client;
import jks.lobby.Lobby_Ice;
import jks.net.Lobby_Message;
import jks.net.Net_Listener;
import jks.net.Net_Peer;
import jks.net.Net_Transport;
import jks.vars.GVars_Heart;
import jks.vinterface.Menu_Focus;
import jks.vue.models.Vue_Client;
import jks.vue.models.Vue_Game;
import jks.vue.models.Vue_Lobby;
import jks.vue.models.Vue_Menu;

/**
 * Plays Online play through its screens in a real window (r43), as a host or as a joiner, driving the
 * menus the way the keyboard does : Menu_Focus moves and picks, and the code is TYPED into the lobby
 * screen's input processor. The host writes its code to <out>/code.txt ; the joiner waits for it.
 * Every screen is written as a PNG, bottom-up (GL origin) : magick in.png -flip out.png.
 *
 *   ./gradlew dist :lobby:run &          (a lobby service on 127.0.0.1:7770)
 *   javac -cp desktop/build/libs/LaChasseGalerie-1.0.jar -d /tmp/probe tools/probe/Lobby_Screen_Probe.java
 *   cd desktop/assets
 *   ../../tools/offscreen.sh java -cp ../build/libs/LaChasseGalerie-1.0.jar:/tmp/probe Lobby_Screen_Probe host /tmp/probe &
 *   ../../tools/offscreen.sh java -cp ../build/libs/LaChasseGalerie-1.0.jar:/tmp/probe Lobby_Screen_Probe join /tmp/probe
 *
 * Exits 0 once the host saw a joiner's row say direct and started, and the joiner was let in and drew the
 * run ; it prints PROBE FAIL and exits 1 on a deadline.
 *
 * -Dprobe.size=1920x1080 needs a display that big : cage clamps a window to its 1280x720 output. Run it in
 * gamescope's headless backend instead of tools/offscreen.sh (r75) :
 *   gamescope --backend headless -W 1920 -H 1080 -w 1920 -h 1080 -- env ALSOFT_DRIVERS=null java -Dprobe.size=1920x1080 ...
 *
 * -Dprobe.block=true on BOTH (r75) : the joiner's socket drops every packet to and from anyone but the
 * lobby service and the STUN servers, as two symmetric NATs would, so each side's row goes to cannot
 * connect and draws its fix. Each writes that screen (host_cannot.png, join_cannot.png) and exits 0.
 *
 * -Dprobe.private=true on the host (r76) : it makes its lobby private before host_2_open.png, so the
 * joiner's join_1_listed.png shows Open games without it, and the joiner still gets in by the code.
 */
public class Lobby_Screen_Probe implements ApplicationListener
{
	final Main_Game game = new Main_Game() ;
	final boolean host ;
	final String out ;
	/** Frames, for the log ; every wait is in milliseconds, since an offscreen window draws far more than 60 a second. */
	int frame, step ;
	final long started = System.currentTimeMillis() ;
	static final boolean block = Boolean.getBoolean("probe.block") ;
	static final boolean unlisted = Boolean.getBoolean("probe.private") ;
	long stepStarted = started ;

	Lobby_Screen_Probe(boolean host, String out)
	{
		this.host = host ;
		this.out = out ;
	}

	public static void main(String[] arg)
	{
		GVars_Heart.startAtMenu = true ;
		jks.sounds.GVars_Audio.muted = true ;
		if(arg.length > 2)
			GVars_Heart.lobbyService = arg[2] ;
		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration() ;
		// -Dprobe.size=1920x1080 : the fullscreen layout, in a window of that size
		String[] size = System.getProperty("probe.size", "1280x720").split("x") ;
		config.setWindowedMode(Integer.parseInt(size[0]), Integer.parseInt(size[1])) ;
		// Uncapped, two offscreen windows draw thousands of frames a second each and starve each other
		config.setForegroundFPS(60) ;
		config.setIdleFPS(60) ;
		config.setTitle("lobby probe " + arg[0]) ;
		new Lwjgl3Application(new Lobby_Screen_Probe(arg[0].equals("host"), arg.length > 1 ? arg[1] : "."), config) ;
	}

	public void create()
	{game.create() ;}

	public void render()
	{
		game.render() ;
		frame++ ;
		if(GVars_Heart.vue != shownVue)
		{
			shownVue = GVars_Heart.vue ;
			log("now on " + shownVue.getClass().getSimpleName()) ;
		}
		try
		{
			if(System.currentTimeMillis() - started > 90_000)
				fail("deadline at step " + step + " on " + GVars_Heart.vue.getClass().getSimpleName()) ;
			if(host)
				host() ;
			else
				join() ;
		}
		catch(Exception e)
		{throw new RuntimeException(e) ;}
	}

	void host() throws Exception
	{
		switch(step)
		{
			case 0 :
				if(since() < 1_000) return ;
				grab("host_0_menu.png") ;
				Menu_Focus menu = (Menu_Focus) field(Vue_Menu.class, "focus").get(GVars_Heart.vue) ;
				menu.move(1) ;
				menu.pick(Menu_Picker.KEYBOARD) ;
				next() ;
				return ;
			case 1 :
				if(since() < 2_000) return ;
				grab("host_1_online.png") ;
				focus().pick(Menu_Picker.KEYBOARD) ; // Host a game : the first choice
				next() ;
				return ;
			case 2 :
				if(lobby().code() == null) return ;
				Files.write(Paths.get(out, "code.txt"), lobby().code().getBytes()) ;
				log("hosting " + lobby().code() + " as " + lobby().publicAddress()) ;
				next() ;
				return ;
			case 3 :
				// The toggle is the choice after Start ; the focus goes back to Start for step 4
				if(unlisted && at(300))
				{
					focus().move(1) ;
					focus().pick(Menu_Picker.KEYBOARD) ;
				}
				if(unlisted && at(600))
				{
					focus().move(-1) ;
					log("private : " + lobby().unlisted()) ;
				}
				if(at(1_000))
					grab("host_2_open.png") ;
				if(block)
				{
					for(Lobby_Ice.Link link : lobby().ice().links())
						if(link.route() == Lobby_Ice.Route.CANNOT_CONNECT && cannotSince < 0)
							cannotSince = System.currentTimeMillis() ;
					// A second after, so the fix line was laid out and drawn
					if(cannotSince > 0 && System.currentTimeMillis() - cannotSince > 1_000)
					{
						grab("host_cannot.png") ;
						Gdx.app.exit() ;
						step = 99 ;
					}
					return ;
				}
				for(Lobby_Ice.Link link : lobby().ice().links())
					if(link.route() == Lobby_Ice.Route.DIRECT)
					{
						log("a joiner is direct : " + link) ;
						next() ;
					}
				return ;
			case 4 :
				if(since() < 4_000) return ; // long enough for the joiner's screen to be seen waiting
				grab("host_3_joiner_direct.png") ;
				focus().pick(Menu_Picker.KEYBOARD) ; // Start : the first choice
				next() ;
				return ;
			case 5 :
				if(!(GVars_Heart.vue instanceof Vue_Game)) fail("Start opened " + GVars_Heart.vue.getClass().getSimpleName()) ;
				if(since() / 1_000 != lastSecond)
				{
					lastSecond = since() / 1_000 ;
					StringBuilder heroes = new StringBuilder() ;
					for(jks.personnage.PhysicSpriteHeroes hero : jks.vars.GVars_Game.heroes)
						heroes.append(hero.player).append(" at ").append(hero.body.getPosition()).append("  ") ;
					log("second " + lastSecond + " : " + heroes) ;
				}
				if(at(8_000))
					grab("host_4_run.png") ;

				// Up until the joiner has drawn its run : leaving closes the server, and sends it to its menu
				if(since() < 25_000) return ;
				log("run hosted, " + GVars_Heart.vue.getClass().getSimpleName()) ;
				Gdx.app.exit() ;
				next() ;
				return ;
		}
	}

	void join() throws Exception
	{
		switch(step)
		{
			case 0 :
				if(since() < 1_000) return ;
				Menu_Focus menu = (Menu_Focus) field(Vue_Menu.class, "focus").get(GVars_Heart.vue) ;
				menu.move(1) ;
				menu.pick(Menu_Picker.KEYBOARD) ;
				next() ;
				return ;
			case 1 :
				if(block && !(field(Lobby_Client.class, "shared").get(lobby()) instanceof Blocking))
				{
					Net_Transport real = (Net_Transport) field(Lobby_Client.class, "shared").get(lobby()) ;
					Blocking blocking = new Blocking(real, real.resolve(GVars_Heart.lobbyService)) ;
					field(Lobby_Client.class, "shared").set(lobby(), blocking) ;
					field(Lobby_Ice.class, "shared").set(lobby().ice(), blocking) ;
					log("blocking everyone but the lobby service and STUN") ;
				}
				Path code = Paths.get(out, "code.txt") ;
				// Let the list of open games come round with the host's in it : it is asked for every 3 s
				if(!Files.exists(code) || Files.getLastModifiedTime(code).toMillis() > System.currentTimeMillis() - 4_000) return ;
				grab("join_1_listed.png") ;
				// Typed as a person would : lower case, with a dash in the middle
				String typed = new String(Files.readAllBytes(code)).toLowerCase() ;
				typed = typed.substring(0, 3) + "-" + typed.substring(3) ;
				for(char c : typed.toCharArray())
					Gdx.input.getInputProcessor().keyTyped(c) ;
				next() ;
				return ;
			case 2 :
				if(since() < 500) return ;
				grab("join_2_typed.png") ;
				focus().pick(Menu_Picker.KEYBOARD) ; // the focus went to Join code when the code was whole
				next() ;
				return ;
			case 3 :
				if(block)
				{
					Lobby_Message.Joined joined = lobby().joined() ;
					Lobby_Ice.Link link = joined == null ? null : lobby().ice().link(joined.host.get(0)) ;
					if(link != null && link.route() == Lobby_Ice.Route.CANNOT_CONNECT && cannotSince < 0)
						cannotSince = System.currentTimeMillis() ;
					if(cannotSince > 0 && System.currentTimeMillis() - cannotSince > 1_000)
					{
						grab("join_cannot.png") ;
						Gdx.app.exit() ;
						step = 99 ;
					}
					return ;
				}
				if(GVars_Heart.vue instanceof Vue_Client)
				{
					log("let in") ;
					next() ;
					return ;
				}
				if(at(2_000))
					grab("join_3_waiting.png") ;
				return ;
			case 4 :
				// Any key asks for a hero, as a person sitting down would
				if(at(1_000))
				{
					Gdx.input.getInputProcessor().keyDown(com.badlogic.gdx.Input.Keys.SPACE) ;
					Gdx.input.getInputProcessor().keyUp(com.badlogic.gdx.Input.Keys.SPACE) ;
				}
				// Then a step to the right : both heroes land on the same spot, one hiding the other
				if(since() >= 2_500 && walked == 0)
				{
					walked = 1 ;
					Gdx.input.getInputProcessor().keyDown(com.badlogic.gdx.Input.Keys.RIGHT) ;
				}
				if(since() >= 3_300 && walked == 1)
				{
					walked = 2 ;
					Gdx.input.getInputProcessor().keyUp(com.badlogic.gdx.Input.Keys.RIGHT) ;
				}
				if(at(5_000))
					grab("join_4_run.png") ;
				// In until the host has drawn it : quitting says LEAVE, and the host takes the hero out
				if(since() < 12_000) return ;
				Gdx.app.exit() ;
				next() ;
				return ;
		}
	}

	Menu_Focus focus() throws Exception
	{
		if(!(GVars_Heart.vue instanceof Vue_Lobby))
			fail("expected the lobby screen, on " + GVars_Heart.vue.getClass().getSimpleName()) ;
		return (Menu_Focus) field(Vue_Lobby.class, "focus").get(GVars_Heart.vue) ;
	}

	Lobby_Client lobby() throws Exception
	{return (Lobby_Client) field(Vue_Lobby.class, "lobby").get(GVars_Heart.vue) ;}

	static Field field(Class<?> type, String name) throws Exception
	{
		Field field = type.getDeclaredField(name) ;
		field.setAccessible(true) ;
		return field ;
	}

	void next()
	{
		step++ ;
		stepStarted = System.currentTimeMillis() ;
	}

	/** Milliseconds since this step began. */
	long since()
	{return System.currentTimeMillis() - stepStarted ;}

	/** Once, when this step has lasted this long. */
	boolean at(long ms)
	{
		long key = step * 1_000_000L + ms ;
		if(since() < ms || taken.contains(key))
			return false ;
		taken.add(key) ;
		return true ;
	}
	final java.util.Set<Long> taken = new java.util.HashSet<Long>() ;
	int walked ;
	long cannotSince = -1 ;
	long lastSecond = -1 ;
	Object shownVue ;

	void log(String line)
	{
		System.out.println("PROBE " + (host ? "host" : "join") + " " + (System.currentTimeMillis() % 1_000_000) + " : " + line) ;
		System.out.flush() ;
	}

	void fail(String why)
	{
		log("FAIL " + why) ;
		grab((host ? "host" : "join") + "_fail.png") ;
		Runtime.getRuntime().halt(1) ;
	}

	void grab(String name)
	{
		Graphics g = Gdx.graphics ;
		Pixmap pixmap = Pixmap.createFromFrameBuffer(0, 0, g.getBackBufferWidth(), g.getBackBufferHeight()) ;
		PixmapIO.writePNG(Gdx.files.absolute(out + "/" + name), pixmap) ;
		pixmap.dispose() ;
		log("wrote " + name + " at frame " + frame) ;
	}

	/** The joiner's socket as two symmetric NATs leave it : only the lobby service and STUN get through. */
	static final class Blocking implements Net_Transport
	{
		final Net_Transport real ;
		final Net_Peer service ;

		Blocking(Net_Transport real, Net_Peer service)
		{
			this.real = real ;
			this.service = service ;
		}

		boolean through(Net_Peer peer)
		{return peer.equals(service) || peer.toString().endsWith(":19302") || peer.toString().endsWith(":3478") ;}

		public Net_Peer resolve(String address) {return real.resolve(address) ;}
		public void send(Net_Peer peer, ByteBuffer payload)
		{
			if(through(peer))
				real.send(peer, payload) ;
		}
		public int pump(Net_Listener listener)
		{
			return real.pump(new Net_Listener()
			{
				public void received(Net_Peer from, ByteBuffer payload)
				{
					if(through(from))
						listener.received(from, payload) ;
				}
				public void lost(Net_Peer peer) {listener.lost(peer) ;}
			}) ;
		}
		public int localPort() {return real.localPort() ;}
		public java.util.List<String> localAddresses() {return real.localAddresses() ;}
		public int dropped() {return real.dropped() ;}
		public void close() {real.close() ;}
	}

	public void resize(int width, int height) {game.resize(width, height) ;}
	public void pause() {game.pause() ;}
	public void resume() {game.resume() ;}
	public void dispose() {game.dispose() ;}
}
