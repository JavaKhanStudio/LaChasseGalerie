package jks.physic.tools;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.BodyDef.BodyType;
import com.badlogic.gdx.physics.box2d.CircleShape;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.FixtureDef;
import com.badlogic.gdx.physics.box2d.World;

public class BoxBodyBuilder {
	
	public static float WORLD_TO_BOX=0.01f;
	public static float BOX_TO_WORLD=100f;
	
	private BoxBodyBuilder() {}
	
	public static float ConvertToBox(float x){
		return x*WORLD_TO_BOX;
	}
	
	static float ConvertToWorld(float x){
		return x*BOX_TO_WORLD;
	}
	
	public static Body CreateCircleBody(World world,BodyType bodyType,float posx,float posy,float radius)
	{
		BodyDef bodyDef = new BodyDef();
		bodyDef.type = bodyType;
		bodyDef.position.set(posx,posy);
		bodyDef.angle = 0;
		bodyDef.gravityScale = 0 ; 

		
		Body body = world.createBody(bodyDef);
		MakeCircleBody(body,radius,bodyType,0.1f,0,0,0);
		return body;
	}
	
	static Fixture MakeCircleBody(Body body,float radius,BodyDef.BodyType bodyType,
			float density,float restitution,float angle,float friction)
	{
		FixtureDef fixtureDef=new FixtureDef();
 		fixtureDef.density=density;
 		fixtureDef.restitution=restitution;
 		fixtureDef.friction=friction;
 		fixtureDef.shape=new CircleShape();
 		fixtureDef.shape.setRadius(ConvertToBox(radius));
 		
 		
 		Fixture fix = body.createFixture(fixtureDef);	
		fixtureDef.shape.dispose();
		return fix ; 
	
	}
}
