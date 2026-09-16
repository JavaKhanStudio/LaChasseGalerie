package jks.personnage;

import static jks.physic.FVars_Physic.PPM;
import static jks.physic.Gvars_Physic.world;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef.BodyType;
import com.badlogic.gdx.physics.box2d.Fixture;

import jks.draw.Draw_Monster;
import jks.personnage.index.Enum_AnimState;
import jks.personnage.index.SIW_Data;
import jks.vars.GVars_Game;
import jks.physic.tools.BoxBodyBuilder; 

/** A monster in the simulation : its body and its chase. Drawn by Draw_Monster, placed on the body in act(). */
public class PhysicSpriteEnnemy extends Draw_Monster
{

	/** This monster's entity id, for snapshots. */
	public final int id = GVars_Game.newEntityId() ; 
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
		
		placeAt(body.getPosition().x, body.getPosition().y) ; 
		
		checkForState() ; 
	}
	

	public void checkForState()
	{
		if(currentAnimState != Enum_AnimState.IDLE)
			changeAnimationState(Enum_AnimState.IDLE,true) ;
	}
	
	public void getHurt() 
	{
		GVars_Game.ennemies.remove(this) ; 
		GVars_Game.toBeDestroy_Body.add(body) ; 
	}

}
