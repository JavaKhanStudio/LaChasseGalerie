package jks.personnage;

import static jks.physic.FVars_Physic.PPM;
import static jks.physic.Gvars_Physic.world;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef.BodyType;
import com.badlogic.gdx.physics.box2d.Fixture;

import jks.personnage.index.Enum_AnimState;
import jks.personnage.index.SIW_Data;
import jks.personnage.model.SpriteModel;
import jks.vars.GVars_Game;
import jks.physic.tools.BoxBodyBuilder; 

public class PhysicSpriteEnnemy extends SpriteModel
{

	public Body body ;

	public Fixture fixture_Main ;
	
	public float speed_current = 300 ; 
	public float speed_max = 350 ;
	public float speed_acceleration = 25; 
	public float speed_deceleration = 30; 
	public float speed_minSpeed_Run = 60 ; 
	public float speed_minSpeed_Idle = 10 ; 
	
	public PhysicSpriteHeroes target ; 
	
	
	public PhysicSpriteEnnemy(SIW_Data index,float x, float y) 
	{
		super(index);
		
		this.position.add(x,y) ; 
		currentFrame = currentState.getKeyFrame(0,false) ;
		body = BoxBodyBuilder.CreateCircleBody(world, BodyType.DynamicBody, this.position.x/PPM,this.position.y/PPM, 90);
		fixture_Main = body.getFixtureList().get(0); 
		fixture_Main.setUserData(this) ; 
	}
	

	public void act(float delta)
	{
		update(delta) ; 
		
		if(target != null)
		{
			Vector2 myPosition = body.getPosition() ;
			Vector2 myEnnemyPosition = target.body.getPosition() ;
			Vector2 normalised = myEnnemyPosition.sub(myPosition).nor() ; 
			normalised.scl(speed_current) ; 
			
			body.setLinearVelocity(normalised.x * delta,normalised.y * delta);
			
			if(normalised.x < 0)
				reverse = false ;
			else if(normalised.x > 0)
				reverse = true ;
		}
		else
		{
			target = GVars_Game.checkForTarget() ; 
		}
		
		position.x = body.getPosition().x * PPM - getFrameWidth(currentFrame)/ 2; 
		position.y = body.getPosition().y * PPM - getFrameHeight(currentFrame)/ 2; 
		
		checkForState() ; 
	}
	

	@Override
	public void draw(Batch batch) 
	{
		super.draw(batch);
	}

	public void checkForState()
	{
		if(currentAnimState != Enum_AnimState.IDLE)
			changeAnimationState(Enum_AnimState.IDLE,true) ;
	}
	
	@Override
	public float getFrameWidth(TextureRegion frame)
	{return (frame.getRegionWidth() * index.scale) ;}
	
	@Override
	public float getFrameHeight(TextureRegion frame)
	{return (frame.getRegionHeight() * index.scale);}

	

	public void getHurt() 
	{
		GVars_Game.ennemies.remove(this) ; 
		GVars_Game.toBeDestroy_Body.add(body) ; 
	}

}
