package jks.html;

import com.google.gwt.user.client.Window;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.gwt.GwtApplication;
import com.badlogic.gdx.backends.gwt.GwtApplicationConfiguration;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;

import jks.amain.Main_Game;
import jks.debug.GVars_Debug;
import jks.input.Tab_Touch;
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
 * launcher side. lobby=host:port is --lobby's, for the service's WebSocket front (r82), by default VPS_1's ;
 * relay makes a tab's calls relay-only (r83's gate).
 *
 * Online play in a tab (r82) : GVars_Heart.tabLobby opens a Lobby_Tab on a Transport_WebSocket to the front,
 * with a Transport_Channel for the game, so Vue_Lobby is a player's lobby and the session plays on an
 * RTCDataChannel.
 *
 * A phone (r87) : GVars_Heart.touch is {@link Touch}, what the page knows of touch and its soft keyboard.
 * index.html fits the 1280x720 canvas to the screen ; libGDX already scales a touch back into the canvas.
 *
 * It also sets window.lcg, what tools/browser-gate/ reads : lcg.frames() and lcg.heroes() (gate.mjs),
 * lcg.vue() and lcg.online() (tabjoin.mjs), and lcg.touch() and lcg.find() (tabtouch.mjs).
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
		// ?relay (r83) : a tab's calls go through the relay only, the path a phone on mobile data takes
		boolean relayOnly = Window.Location.getParameter("relay") != null ;
		GVars_Heart.tabLobby = () -> new Lobby_Tab(new Transport_WebSocket(service), service, new Transport_Channel(relayOnly), System::currentTimeMillis) ;
		GVars_Audio.muted = Window.Location.getParameter("mute") != null;
		GVars_Heart.startAtMenu = Window.Location.getParameter("menu") != null;
		GVars_Debug.setInFullDebug(Window.Location.getParameter("debug") != null);
		GVars_Heart.touch = new Touch() ;
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
	 * A tab's session, once it plays : "state player snapshots x axe" with x its own hero's, in metres, and axe
	 * its axe's angle in radians (tabtouch.mjs sees a swing by it), or "-" "-" while it has none ; "" off a Vue_Client.
	 */
	static String online()
	{
		if(!(GVars_Heart.vue instanceof Vue_Client))
			return "" ;
		ClientSession client = ((Vue_Client) GVars_Heart.vue).session() ;
		if(client == null)
			return "closed" ;
		String x = "-", axe = "-" ;
		for(Net_Snapshot.Hero hero : client.view().heroes())
			if(hero.player == client.player())
			{
				x = String.valueOf(hero.x) ;
				axe = String.valueOf(hero.axeAngle) ;
			}
		return client.state() + " " + client.player() + " " + client.snapshotsReceived + " " + x + " " + axe ;
	}

	/**
	 * The page under a finger, and a hidden input for the phone's keyboard (r87). The input's key events stop
	 * there : libGDX listens on the document and would type every letter a second time.
	 */
	static final class Touch implements Tab_Touch
	{
		Touch()
		{watch() ;}

		@Override
		public native boolean seen()
		/*-{
			return $wnd.lcgTouch.seen ;
		}-*/;

		@Override
		public native void keyboard(String text, Tab_Touch.Typed typed)
		/*-{
			var input = $wnd.lcgTouch.input ;
			input.value = text ;
			input.oninput = $entry(function() { typed.@jks.input.Tab_Touch$Typed::text(Ljava/lang/String;)(input.value) ; }) ;
			input.onkeydown = $entry(function(e) {
				e.stopPropagation() ;
				if(e.key === 'Enter')
				{
					e.preventDefault() ;
					typed.@jks.input.Tab_Touch$Typed::enter()() ;
				}
			}) ;
			input.focus() ;
		}-*/;

		@Override
		public native void closeKeyboard()
		/*-{
			var input = $wnd.lcgTouch.input ;
			input.oninput = null ;
			input.onkeydown = function(e) { e.stopPropagation() ; } ;
			input.blur() ;
		}-*/;

		/** Seen : a coarse pointer (a phone, a tablet), or any touch since, on a laptop with a touchscreen. */
		private static native void watch()
		/*-{
			var touch = { seen: !!($wnd.matchMedia && $wnd.matchMedia('(pointer: coarse)').matches) } ;
			$doc.addEventListener('touchstart', function() { touch.seen = true ; }, { capture: true, passive: true }) ;
			// 16 px or iOS zooms the page onto it ; on the screen but invisible, or iOS will not focus it
			var input = $doc.createElement('input') ;
			input.type = 'text' ;
			input.setAttribute('autocomplete', 'off') ;
			input.setAttribute('autocorrect', 'off') ;
			input.setAttribute('autocapitalize', 'characters') ;
			input.setAttribute('spellcheck', 'false') ;
			input.setAttribute('enterkeyhint', 'go') ;
			input.setAttribute('aria-label', 'Game code') ;
			input.style.cssText = 'position:fixed;left:0;top:0;width:1px;height:1px;opacity:0;border:0;padding:0;font-size:16px' ;
			input.onkeydown = function(e) { e.stopPropagation() ; } ;
			input.onkeypress = function(e) { e.stopPropagation() ; } ;
			input.onkeyup = function(e) { e.stopPropagation() ; } ;
			$doc.body.appendChild(input) ;
			touch.input = input ;
			$wnd.lcgTouch = touch ;
		}-*/;
	}

	/** "seen keyboard" : whether the page is under a finger (the touch pad shows), and whether the soft keyboard's input has the focus. */
	static String touch()
	{
		boolean pad = GVars_Heart.touch != null && GVars_Heart.touch.seen() ;
		return pad + " " + keyboardOpen() ;
	}

	/**
	 * Where an actor of the screen's Stage is drawn, "x y width height text" in the canvas's 1280x720 pixels, y
	 * down : the first visible one whose name is this, or a button whose text holds it. "" when none is. What
	 * the phone gate taps (tabtouch.mjs), so a tap lands where the player's thumb would.
	 */
	static String find(String key)
	{
		Stage stage = null ;
		InputProcessor processor = Gdx.input.getInputProcessor() ;
		if(processor instanceof Stage)
			stage = (Stage) processor ;
		else if(processor instanceof InputMultiplexer)
			for(InputProcessor each : ((InputMultiplexer) processor).getProcessors())
				if(each instanceof Stage)
				{
					stage = (Stage) each ;
					break ;
				}
		Actor actor = stage == null ? null : find(stage.getRoot(), key) ;
		if(actor == null)
			return "" ;
		Vector2 low = stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(0, 0))) ;
		Vector2 high = stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(actor.getWidth(), actor.getHeight()))) ;
		String text = actor instanceof TextButton ? ((TextButton) actor).getText().toString() : actor instanceof TextField ? ((TextField) actor).getText() : "" ;
		return Math.round(low.x) + " " + Math.round(high.y) + " " + Math.round(high.x - low.x) + " " + Math.round(low.y - high.y) + " " + text ;
	}

	private static Actor find(Group group, String key)
	{
		for(Actor actor : group.getChildren())
		{
			if(!actor.isVisible())
				continue ;
			if(key.equals(actor.getName()) || actor instanceof TextButton && ((TextButton) actor).getText().toString().contains(key))
				return actor ;
			if(actor instanceof Group)
			{
				Actor found = find((Group) actor, key) ;
				if(found != null)
					return found ;
			}
		}
		return null ;
	}

	private static native boolean keyboardOpen()
	/*-{
		return $doc.activeElement === $wnd.lcgTouch.input ;
	}-*/;

	private static native void exportProbe()
	/*-{
		$wnd.lcg = {
			frames: $entry(function() { return @jks.html.HtmlLauncher::frames()(); }),
			heroes: $entry(function() { return @jks.html.HtmlLauncher::heroes()(); }),
			vue: $entry(function() { return @jks.html.HtmlLauncher::vue()(); }),
			online: $entry(function() { return @jks.html.HtmlLauncher::online()(); }),
			touch: $entry(function() { return @jks.html.HtmlLauncher::touch()(); }),
			find: $entry(function(key) { return @jks.html.HtmlLauncher::find(Ljava/lang/String;)(key); })
		};
	}-*/;
}
