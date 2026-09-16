package jks.physic.objects;

import static jks.physic.FVars_Physic.PPM;
import static jks.physic.Gvars_Physic.world;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.FixtureDef;
import com.badlogic.gdx.physics.box2d.PolygonShape;

import jks.draw.Draw_Canoe;

/** The canoe's body, which the heroes stand on and the take-off tilts. Drawn by Draw_Canoe. */
public class PhysicSpriteCanoe extends Draw_Canoe
{
	
	public Body body;
	
	float width ; 
	float height ;
	
	public PhysicSpriteCanoe()
    {
    	// Now the physics body of the bottom edge of the screen
        
        BodyDef bodyDef = new BodyDef();
        bodyDef.type = BodyDef.BodyType.StaticBody;
        width = spriteWidth()/PPM/2;
        height = spriteHeight()/PPM/2/3;

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
		showAngle(body.getAngle());
	}
}
