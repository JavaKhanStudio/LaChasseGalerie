package jks.camera;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

public class GVars_Camera 
{
	public static OrthographicCamera camera;
	/** The window is only a view onto the world: it decides the black bars, never where anything is. */
	public static Viewport viewport;
	public static Batch staticBatch ;
	
	/** Where the river starts, before the story speeds it up. */
	public static final float baseMovementSpeed = 7.5f ;
	public static float screenMovementSpeed = baseMovementSpeed;
	
	public static float worldMutiplier = 2f ;
	
	/**
	 * The size of the world, before worldMutiplier : the 1280x720 window the game was tuned in.
	 * Every world position reads these, never Gdx.graphics, so a host and a client with different
	 * windows share one world. The world itself is viewWidth * worldMutiplier wide.
	 */
	public static final int viewWidth = 1280, viewHeight = 720 ;
	
	public static void init()
	{
		screenMovementSpeed = baseMovementSpeed ;
		camera = new OrthographicCamera();
		staticBatch = new SpriteBatch();
		viewport = new FitViewport(viewWidth * worldMutiplier, viewHeight * worldMutiplier, camera);
		viewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
	}
	
	public static void dispose()
	{
		if(staticBatch != null)
			staticBatch.dispose();
		staticBatch = null ;
		viewport = null ;
		camera = null ;
	}
	
	public static void resize(int width, int height)
	{
		if(viewport != null)
			viewport.update(width, height, true);
	}
	
}
