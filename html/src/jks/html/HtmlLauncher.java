package jks.html;

import com.google.gwt.user.client.Window;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.gwt.GwtApplication;
import com.badlogic.gdx.backends.gwt.GwtApplicationConfiguration;

import jks.amain.Main_Game;
import jks.debug.GVars_Debug;
import jks.sounds.GVars_Audio;
import jks.vars.GVars_Game;
import jks.vars.GVars_Heart;

/**
 * The browser's entry point, standing exactly where desktop/src/jks/launcher/Launcher_Game does.
 *
 * A page has no command line, so the launcher options become query parameters :
 * index.html?mute&debug&menu. That is the whole of what does not survive the crossing on the
 * launcher side.
 *
 * It also sets window.lcg, what tools/browser-gate/gate.mjs reads : lcg.frames() and lcg.heroes().
 */
public class HtmlLauncher extends GwtApplication
{
	@Override
	public GwtApplicationConfiguration getConfig()
	{
		// 1280x720, like the desktop window : spawns and the HUD are computed from it (#gameplay)
		return new GwtApplicationConfiguration(1280, 720);
	}

	@Override
	public ApplicationListener createApplicationListener()
	{
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

	private static native void exportProbe()
	/*-{
		$wnd.lcg = {
			frames: $entry(function() { return @jks.html.HtmlLauncher::frames()(); }),
			heroes: $entry(function() { return @jks.html.HtmlLauncher::heroes()(); })
		};
	}-*/;
}
