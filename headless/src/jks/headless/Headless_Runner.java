package jks.headless;

import java.lang.reflect.Proxy;
import java.nio.IntBuffer;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.backends.headless.mock.graphics.MockGraphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;

import jks.amain.Main_Game;
import jks.sounds.GVars_Audio;
import jks.vars.GVars_Heart;

/**
 * Runs the real game with no window and no sound: Main_Game.create, then one fixed 1/60 tick at a time
 * with a stubbed GL, as fast as the caller asks. This is the loop a host with no screen runs.
 *
 * The backend's own loop never runs: the session drives every tick from inside create, on the
 * application thread, and the process exits when it returns (0) or throws (1).
 *
 * Every texture is still loaded, so a headless JVM weighs what a windowed one does (r25).
 * Static game state is never reset, so this is one session per JVM (phase 0.4).
 */
public class Headless_Runner
{
	public static final float DELTA = 1 / 60f;

	/** What runs once the game can boot. Throwing fails the process. */
	public interface Session
	{
		void run(Headless_Runner runner) throws Exception;

		/** Called before the process exits 1. */
		default void failed(Throwable t)
		{
			t.printStackTrace(System.out);
		}
	}

	public final int width, height;

	Headless_Runner(int width, int height)
	{
		this.width = width;
		this.height = height;
	}

	/** Spawn points, the canoe and the HUD come from the window size: at 0 everyone spawns at 0 and drowns. */
	public static void launch(int width, int height, Session session)
	{
		GVars_Audio.muted = true;
		Headless_Runner runner = new Headless_Runner(width, height);

		HeadlessApplicationConfiguration config = new HeadlessApplicationConfiguration();
		config.updatesPerSecond = -1;
		new HeadlessApplication(new ApplicationAdapter()
		{
			@Override
			public void create()
			{
				// An exception on the HeadlessApplication thread would not fail the process
				try
				{
					runner.stubDisplay();
					session.run(runner);
					System.exit(0);
				}
				catch (Throwable t)
				{
					session.failed(t);
					System.exit(1);
				}
			}
		}, config);
	}

	void stubDisplay()
	{
		Gdx.gl = Gdx.gl20 = stubGl();
		Gdx.graphics = new MockGraphics()
		{
			@Override public int getWidth() { return width; }
			@Override public int getHeight() { return height; }
			@Override public int getBackBufferWidth() { return width; }
			@Override public int getBackBufferHeight() { return height; }
		};
	}

	/** Loads the game and opens its first view. */
	public void boot()
	{
		new Main_Game().create();
	}

	/** One tick of game time, and a render nobody sees. */
	public void step()
	{
		GVars_Heart.vue.update(DELTA);
		GVars_Heart.vue.render();
	}

	/** Shaders compile, programs link with no attributes or uniforms, everything else is a no-op. */
	static GL20 stubGl()
	{
		return (GL20) Proxy.newProxyInstance(Headless_Runner.class.getClassLoader(), new Class<?>[] { GL20.class, GL30.class }, (proxy, method, args) ->
		{
			String name = method.getName();
			if ((name.equals("glGetShaderiv") || name.equals("glGetProgramiv")) && args[2] instanceof IntBuffer buffer)
			{
				int query = (Integer) args[1];
				boolean isCount = query == GL20.GL_ACTIVE_ATTRIBUTES || query == GL20.GL_ACTIVE_UNIFORMS;
				buffer.put(buffer.position(), isCount ? 0 : 1);
				return null;
			}
			if (name.equals("glGetIntegerv") && args[1] instanceof IntBuffer buffer)
			{
				buffer.put(buffer.position(), 8192);
				return null;
			}
			if (name.equals("glGetShaderInfoLog") || name.equals("glGetProgramInfoLog") || name.equals("glGetString"))
				return "";
			if (name.equals("glGetAttribLocation") || name.equals("glGetUniformLocation"))
				return 0;
			return defaultValue(method.getReturnType());
		});
	}

	static Object defaultValue(Class<?> type)
	{
		if (type == int.class) return 1;
		if (type == boolean.class) return true;
		if (type == float.class) return 0f;
		if (type == long.class) return 0L;
		return null;
	}
}
