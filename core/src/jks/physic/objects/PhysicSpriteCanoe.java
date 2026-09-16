package jks.physic.objects;

import static jks.physic.FVars_Physic.PPM;
import static jks.physic.Gvars_Physic.world;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.FixtureDef;
import com.badlogic.gdx.physics.box2d.PolygonShape;

import jks.camera.GVars_Camera;

public class PhysicSpriteCanoe 
{
	
	public Body body;
	Sprite sprite_Front ;
	Sprite sprite_Back ; 
	
	float width ; 
	float height ;
	
	
	
	public PhysicSpriteCanoe()
    {
		Vector2 size = new Vector2(GVars_Camera.viewWidth/1.6f * GVars_Camera.worldMutiplier,GVars_Camera.viewHeight/7.5f * GVars_Camera.worldMutiplier); 
		Vector2 position = new Vector2(GVars_Camera.viewWidth/2/2 * GVars_Camera.worldMutiplier - size.x/8,GVars_Camera.viewHeight/5.4f * GVars_Camera.worldMutiplier) ; 
		
		sprite_Front = new Sprite(new Texture("tools/Canoe_Front.png")) ;
        sprite_Front.setPosition(position.x,position.y);
        sprite_Front.setSize(size.x,size.y);
        sprite_Back = new Sprite(new Texture("tools/Canoe_Back.png")) ;
        sprite_Back.setPosition(position.x,position.y);
        sprite_Back.setSize(size.x,size.y);
    	// Now the physics body of the bottom edge of the screen
        
        BodyDef bodyDef = new BodyDef();
        bodyDef.type = BodyDef.BodyType.StaticBody;
        width = sprite_Front.getWidth()/PPM/2;
        height = sprite_Front.getHeight()/PPM/2/3;

        bodyDef.position.set(position.x/PPM + size.x/PPM/2,position.y/PPM + size.y/PPM/2/2);
        FixtureDef fixtureDef = new FixtureDef();

        PolygonShape shape = new PolygonShape();
        shape.setAsBox(width,height);
        fixtureDef.shape = shape;

        body = world.createBody(bodyDef);
        body.createFixture(fixtureDef);
        body.getFixtureList().get(0).setUserData("CANOE");
        shape.dispose();
//        body.setTransform(body.getWorldCenter(), body.getAngle() + (float)Math.toRadians(30));
    }
	
	static int shakingStrenght = 1 ;
	
	public void act(float delta)
	{
		sprite_Front.setRotation((float)Math.toDegrees(body.getAngle()));
		sprite_Back.setRotation((float)Math.toDegrees(body.getAngle()));
	}
	
	public void drawBack(Batch batch)
	{
		sprite_Back.draw(batch);
	}
	
	public void drawFront(Batch batch)
	{
		sprite_Front.draw(batch);
	}
	
	/** The two textures. The body goes with the world. */
	public void dispose()
	{
		sprite_Front.getTexture().dispose();
		sprite_Back.getTexture().dispose();
	}
}
