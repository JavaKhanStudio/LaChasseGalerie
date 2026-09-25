package jks.html;

import com.google.gwt.user.client.Window;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.gwt.GwtApplication;
import com.badlogic.gdx.backends.gwt.GwtApplicationConfiguration;

import jks.amain.Main_Game;
import jks.debug.GVars_Debug;
import jks.lobby.Lobby_Tab;
import jks.net.Net_Snapshot;
import jks.online.ClientSession;
import jks.sounds.GVars_Audio;
import jks.vars.GVars_Game;
import jks.vars.GVars_Heart;
import jks.vue.models.Vue_Client;

/**
 * The browser's entry point, standing exactly where desktop/src/jks/launcher/Launcher_Game does.
 *
 * A page has no command line, so the launcher options become query parameters :
 * index.html?mute&debug&menu. That is the whole of what does not survive the crossing on the
 * launcher side. lobby=host:port is --lobby's, for the service's WebSocket front (r82), by default VPS_1's.
 *
 * Online play in a tab (r82) : GVars_Heart.tabLobby opens a Lobby_Tab on a Transport_WebSocket to the front,
 * with a Transport_Channel for the game, so Vue_Lobby is a player's lobby and the session plays on an
 * RTCDataChannel.
 *
 * It also sets window.lcg, what tools/browser-gate/ reads : lcg.frames() and lcg.heroes() (gate.mjs), and
 * lcg.vue() and lcg.online() (tabjoin.mjs).
 */
public class HtmlLauncher extends GwtApplication
{
	@Override
	public GwtApplicationConfiguration getConfig()
	{
		// 1280x720, like the desktop window : spawns and the HUD are computed from it (#gameplay)
		return new GwtApplicationConfiguration(1280, 720);
	}

	/** The service's WebSocket front on VPS_1 (r79), ws:// : where Online play goes with no lobby= parameter. */
	public static final String LOBBY_FRONT = "141.94.115.201:7771" ;

	@Override
	public ApplicationListener createApplicationListener()
	{
		String front = Window.Location.getParameter("lobby") ;
		String service = front == null || front.isEmpty() ? LOBBY_FRONT : front ;
		GVars_Heart.tabLobby = () -> new Lobby_Tab(new Transport_WebSocket(service), service, new Transport_Channel(), System::currentTimeMillis) ;
		GVars_Audio.muted = Window.Location.getParameter("mute") != null;
		GVars_Heart.startAtMenu = Window.Location.getParameter("menu") != null;
		GVars_Debug.setInFullDebug(Window.Location.getParameter("debug") != null);
		exportProbe();
		return new Main_Game();
	}

	/** Frames rendered so far : a page that is really running counts up at 60 a second. */
	static int frames()
	{return Gdx.graphics == null ? 0 : (int) Gdx.graphics.getFrameId();}

	/** Heroes in the game : 0 until a key or a pad joins a player. */
	static int heroes()
	{return GVars_Game.heroes == null ? 0 : GVars_Game.heroes.size();}

	/** The view on screen, by its class : Vue_Menu, Vue_Lobby, Vue_Client. */
	static String vue()
	{return GVars_Heart.vue == null ? "" : GVars_Heart.vue.getClass().getName() ;}

	/**
	 * A tab's session, once it plays : "state player snapshots x" with x its own hero's, in metres, or "-"
	 * while it has none ; "" off a Vue_Client.
	 */
	static String online()
	{
		if(!(GVars_Heart.vue instanceof Vue_Client))
			return "" ;
		ClientSession client = ((Vue_Client) GVars_Heart.vue).session() ;
		if(client == null)
			return "closed" ;
		String x = "-" ;
		for(Net_Snapshot.Hero hero : client.view().heroes())
			if(hero.player == client.player())
				x = String.valueOf(hero.x) ;
		return client.state() + " " + client.player() + " " + client.snapshotsReceived + " " + x ;
	}

	private static native void exportProbe()
	/*-{
		$wnd.lcg = {
			frames: $entry(function() { return @jks.html.HtmlLauncher::frames()(); }),
			heroes: $entry(function() { return @jks.html.HtmlLauncher::heroes()(); }),
			vue: $entry(function() { return @jks.html.HtmlLauncher::vue()(); }),
			online: $entry(function() { return @jks.html.HtmlLauncher::online()(); })
		};
	}-*/;
}
