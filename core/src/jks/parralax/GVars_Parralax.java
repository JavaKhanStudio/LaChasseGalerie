package jks.parralax;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import jks.camera.GVars_Camera;
import jks.tools2d.parallax.ParallaxLayer;
import jks.tools2d.parallax.Utils_Parallax;
import jks.tools2d.parallax.heart.Parallax_Heart;

public class GVars_Parralax 
{
	static final float worldWidth = 40 ; 
	
	// Drawn before the sprites : the sky gradient and COLD_NIGHT
	public static Parallax_Heart background ; 
	// Drawn after the sprites : COLD_WATER passes in front of the heroes
	public static Parallax_Heart foreground ; 
	
	public static void init()
	{
		// Idempotent : the start menu brings the river up before the game view asks for it again,
		// and a second pair of hearts would leak the first pair's batch
		if(background != null)
			return ;
		
		// The shape of the world, not of the window : the game's viewport letterboxes the rest
		float worldHeight = Utils_Parallax.calculateOtherDimension(true, worldWidth, GVars_Camera.viewWidth, GVars_Camera.viewHeight) ; 
		OrthographicCamera worldCamera = new OrthographicCamera() ; 
		worldCamera.setToOrtho(false, worldWidth, worldHeight);
		SpriteBatch batch = new SpriteBatch() ; 
		
		background = new Parallax_Heart(worldCamera, batch, worldWidth, worldHeight) ; 
		background.parallaxReader.setDrawingHeight(2.2f);
		foreground = new Parallax_Heart(worldCamera, batch, worldWidth, worldHeight) ; 
	}
	
	/** Opens a view on these pages, at the start of the river : see rewind. */
	public static void setPages(Enum_ColdNight back, Enum_ColdNight front)
	{
		background.setPage(back.wholePage);
		foreground.setPage(front.wholePage);
		rewind() ; 
	}
	
	/**
	 * Back to where the river starts. The layers belong to the Enum_ColdNight pages, which live as
	 * long as the JVM, so their scroll does too : without this a second run starts where the last
	 * one's take-off left the sky, and so does the menu it goes back to (phase 0.4).
	 */
	private static void rewind()
	{
		for(Parallax_Heart heart : new Parallax_Heart[]{background, foreground})
		{
			heart.screenSpeedConsumableX = 0 ; 
			heart.screenSpeedConsumableY = 0 ; 
			for(ParallaxLayer layer : heart.parallaxReader.layers)
			{
				layer.setScrollX(0) ; 
				layer.setScrollY(0) ; 
			}
		}
	}
	
	/**
	 * The layers are anchored to the screen : the scroll is a speed, not a camera move.
	 * Both values are world units for THIS frame, as the camera used to be moved.
	 */
	public static void scroll(float delta, float moveX, float moveY)
	{
		if(delta <= 0)
			return ; 
		
		for(Parallax_Heart heart : new Parallax_Heart[]{background, foreground})
		{
			heart.screenSpeedConsumableX += moveX / delta ; 
			heart.screenSpeedConsumableY += moveY / delta ; 
		}
	}
	
	public static void act(float delta)
	{
		background.act(delta);
		foreground.act(delta);
	}
}
