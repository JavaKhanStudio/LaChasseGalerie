package jks.physic.objects;

import static jks.personnage.index.Index_Sprite.hpBottle;
import static jks.physic.FVars_Physic.PPM;
import static jks.physic.Gvars_Physic.world;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.FixtureDef;
import com.badlogic.gdx.physics.box2d.PolygonShape;

import jks.vars.GVars_Game; 

public class PhysicSpriteHp
{

	
	/** This potion's entity id, for snapshots. */
	public final int id = GVars_Game.newEntityId() ; 
	public Body body;

	float width ; 
	float height ;
	
	Vector2 position ;
	
	public PhysicSpriteHp(float x, float y)
    {
		Vector2 size = new Vector2(hpBottle.getWidth(),hpBottle.getHeight()); 
		position = new Vector2(x,y) ; 
		
        BodyDef bodyDef = new BodyDef();
        bodyDef.type = BodyDef.BodyType.DynamicBody;
        width = size.x/PPM/2;
        height = size.y/PPM/2;
        bodyDef.gravityScale = 0.2f ; 
        bodyDef.fixedRotation = true ; 

        bodyDef.position.set(position.x/PPM + size.x/PPM/2,position.y/PPM + size.y/PPM/2/2);
        FixtureDef fixtureDef = new FixtureDef();
        fixtureDef.density = 1f;
        fixtureDef.friction = 0.2f ;
        fixtureDef.restitution = 0.4f;

        PolygonShape shape = new PolygonShape();
        shape.setAsBox(width,height);
        fixtureDef.shape = shape;

        body = world.createBody(bodyDef);
        body.createFixture(fixtureDef);
        body.getFixtureList().get(0).setUserData(this);
        shape.dispose();
    }
	
	public void act(float delta)
	{

	}
	
	public void draw(Batch batch)
	{
		batch.draw(
				hpBottle, 
			 	body.getPosition().x * PPM - hpBottle.getWidth()/2, body.getPosition().y * PPM - hpBottle.getHeight()/2,
			 	hpBottle.getOriginX(),hpBottle.getOriginY(),
			 	hpBottle.getWidth(),hpBottle.getHeight(),
			 	hpBottle.getScaleX(),hpBottle.getScaleY(),
			 	(float)Math.toDegrees(body.getAngle()));
                
	}

}
