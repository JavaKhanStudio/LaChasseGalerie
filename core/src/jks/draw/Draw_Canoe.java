package jks.draw;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.math.Vector2;

import jks.camera.GVars_Camera;

/**
 * The canoe as it is drawn (phase 1.5) : its back, drawn behind the heroes, and its front, drawn over
 * them, tilted by an angle. PhysicSpriteCanoe extends it and adds the body the heroes stand on ; a
 * client has no body and tilts it by the snapshot's canoeAngle.
 */
public class Draw_Canoe
{
	/** In world units : where the canoe sits in the fixed-size world, and how big it is. */
	protected final Vector2 size, position ; 
	private final Sprite sprite_Front ;
	private final Sprite sprite_Back ; 
	
	public Draw_Canoe()
	{
		size = new Vector2(GVars_Camera.viewWidth/1.6f * GVars_Camera.worldMutiplier,GVars_Camera.viewHeight/7.5f * GVars_Camera.worldMutiplier); 
		position = new Vector2(GVars_Camera.viewWidth/2/2 * GVars_Camera.worldMutiplier - size.x/8,GVars_Camera.viewHeight/5.4f * GVars_Camera.worldMutiplier) ; 
		
		sprite_Front = new Sprite(new Texture("tools/Canoe_Front.png")) ;
        sprite_Front.setPosition(position.x,position.y);
        sprite_Front.setSize(size.x,size.y);
        sprite_Back = new Sprite(new Texture("tools/Canoe_Back.png")) ;
        sprite_Back.setPosition(position.x,position.y);
        sprite_Back.setSize(size.x,size.y);
	}
	
	/** The tilt, in radians. */
	public void showAngle(float angle)
	{
		sprite_Front.setRotation((float)Math.toDegrees(angle));
		sprite_Back.setRotation((float)Math.toDegrees(angle));
	}
	
	protected float spriteWidth()
	{return sprite_Front.getWidth() ;}
	
	protected float spriteHeight()
	{return sprite_Front.getHeight() ;}
	
	public void drawBack(Batch batch)
	{
		sprite_Back.draw(batch);
	}
	
	public void drawFront(Batch batch)
	{
		sprite_Front.draw(batch);
	}
	
	/** The two textures. */
	public void dispose()
	{
		sprite_Front.getTexture().dispose();
		sprite_Back.getTexture().dispose();
	}
}
