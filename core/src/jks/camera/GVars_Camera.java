package jks.camera;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

public class GVars_Camera 
{
	public static OrthographicCamera camera;
	public static Batch staticBatch ;
	
	public static float screenMovementSpeed = 7.5f;
	
	public static float worldMutiplier = 2f ;
	
	public static void init()
	{
		camera = new OrthographicCamera();
		staticBatch = new SpriteBatch();
		camera.setToOrtho(false, Gdx.graphics.getWidth() * GVars_Camera.worldMutiplier, Gdx.graphics.getHeight() * GVars_Camera.worldMutiplier);
	}
	
}
