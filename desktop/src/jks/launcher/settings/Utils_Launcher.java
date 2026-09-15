package jks.launcher.settings;

import org.lwjgl.glfw.GLFW;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import jks.vars.FVars_Heart;

public class Utils_Launcher 
{
	public static final int windowScale = 80 ; 
	public static final int samples = 4; 

	/**
	 * GLFW picks native Wayland when it can. With GNOME and the NVIDIA driver that path loses the
	 * window decorations and can stall rendering, so use XWayland whenever an X display exists.
	 * Must run before the application initialises GLFW.
	 */
	public static void preferX11OnLinux()
	{
		if(System.getProperty("os.name").toLowerCase().contains("linux") && System.getenv("DISPLAY") != null)
			GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
	}

	public static void basicConfig(Lwjgl3ApplicationConfiguration config)
	{
		config.setTitle("La chasse galerie");
		config.setBackBufferConfig(8, 8, 8, 8, 16, 0, samples);
		config.useVsync(true);
		// The world advances in fixed steps whatever the frame rate (Main_Game), so this is only
		// about how often the game is drawn: no reason to burn a GPU on more than the screen shows.
		config.setForegroundFPS(FVars_Heart.fps);
		config.setResizable(false);
	}
	
	public static void setFullScreen(Lwjgl3ApplicationConfiguration config)
	{
		config.setFullscreenMode(Lwjgl3ApplicationConfiguration.getDisplayMode());
	}
	
	public static void setWindowed(Lwjgl3ApplicationConfiguration config)
	{
		config.setWindowedMode(FVars_Heart.screenXModel * windowScale, FVars_Heart.screenYModel * windowScale);
	}
}
