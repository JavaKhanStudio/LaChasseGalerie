package jks.personnage;

import static jks.physic.FVars_Physic.PPM;
import static jks.physic.Gvars_Physic.world;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.FixtureDef;
import com.badlogic.gdx.physics.box2d.Joint;
import com.badlogic.gdx.physics.box2d.PolygonShape;

import jks.camera.GVars_Camera;
import jks.draw.Draw_Hero;
import jks.input.GVars_Controller;
import jks.input.PlayerId;
import jks.personnage.index.Enum_AnimState;
import jks.personnage.index.Index_Sprite;
import jks.personnage.index.SIW_Data;
import jks.vars.GVars_Game;
import jks.physic.tools.BoxBodyBuilder;
import jks.physic.tools.RevoluteJoint; 

/**
 * A hero in the simulation : its body, its axe on a joint, its moves, hearts and invulnerability.
 * How it is drawn is Draw_Hero's (phase 1.5) : act() copies what the bodies say into the drawing
 * fields, and nothing drawn reads a body, so a client with no world draws the same hero.
 */
public class PhysicSpriteHeroes extends Draw_Hero
{

	public Body body ;
	public Weapon_AXE axe ; 
	
	public Joint joint ; 
	public Fixture fixture_Main ;
	
	public float speed_current ; 
	public float speed_max = 420 ;
	public float speed_acceleration = 25; 
	public float speed_deceleration = 30; 
	public float speed_minSpeed_Run = 60 ; 
	public float speed_minSpeed_Idle = 10 ; 
	
	public float jump_strenght = 35; 
	public float maxUpSpeed = 2.0f ; 
	
	public boolean checkForGround = false ; 
	public int jump_max = 2; 
	public int jump_remaining = jump_max; 
	
	public int hp_left = hp_max ; 
//	public int hp_left = 1 ; 
	
	/** This hero's entity id, for snapshots : a new one each time the player rejoins. */
	public final int id = GVars_Game.newEntityId() ; 
	/** Who this hero is. The device driving it, if any, is GVars_Controller's business. */
	public final PlayerId player ; 
	public boolean invulnerable ; 
	public float invulnerable_Timmer ;
	public final float invulnerable_Base = 3.0f ;
	
	public ScoreLabel score ; 
	
	public PhysicSpriteHeroes(SIW_Data index, PlayerId player, ScoreLabel scoreRegister) 
	{
		super(index);
		score = scoreRegister ;
		score.wear(index);
		this.player = player ; 
		this.position.add(GVars_Camera.viewWidth/2 * GVars_Camera.worldMutiplier,GVars_Camera.viewHeight/2 * GVars_Camera.worldMutiplier) ; 
		
		BodyDef bodyDef = new BodyDef();
        bodyDef.type = BodyDef.BodyType.DynamicBody;
        bodyDef.position.set(this.position.x/PPM,this.position.y/PPM);
        bodyDef.fixedRotation = true ; 
        bodyDef.gravityScale = 2 ; 
        
        body = world.createBody(bodyDef);
       
        PolygonShape shape = new PolygonShape();
        shape.setAsBox(baseWidth/ 2 / PPM, baseHeight / 2 / PPM);
        
        FixtureDef fixtureDef = new FixtureDef();
        fixtureDef.shape = shape;
        fixtureDef.density = 1f;
        fixtureDef.friction = 0.2f ;
        fixtureDef.restitution = 0.0f;
        
        fixture_Main = body.createFixture(fixtureDef);
        fixture_Main.setUserData(this);
        shape.dispose();
        forgeWeapon() ;    
        invulnerable = true ; 
        // Drawn before its first act : where the bodies start. The sprite's corner waits for act
        showBodies() ; 
	}
	
	public void forgeWeapon()
	{
		axe = new Weapon_AXE(this) ; 
		joint = MakeJoint(body,axe.bodyAxe) ; 
	}
	
	Joint MakeJoint(Body joinA, Body joinB)
    {
		RevoluteJoint jointure = new RevoluteJoint(joinA,joinB,false);
		jointure.SetAnchorA(BoxBodyBuilder.ConvertToBox(0), BoxBodyBuilder.ConvertToBox(0));
		jointure.SetAnchorB(BoxBodyBuilder.ConvertToBox(150), BoxBodyBuilder.ConvertToBox(00));
		jointure.SetAngleLimit(-180, 0);
		Joint joint = jointure.CreateJoint(world);
		return joint ; 
	}

	float groundedThreshold = 0.1f ;
	
	public void act(float delta)
	{
		advanceLook(delta, invulnerable, hp_left) ; 
		
		if(checkForGround && Math.abs(body.getLinearVelocity().y) < groundedThreshold)
		{
			resetJump() ; 
		}
		else if(checkForGround)
		{checkForGround = false ;}
		
		if(invulnerable)
		{
			invulnerable_Timmer += delta ;
			if(invulnerable_Timmer > invulnerable_Base)
			{
				invulnerable = false ;
				invulnerable_Timmer = 0 ; 
			}
		}
		
		showBodies() ; 
		placeAtBody() ; 
		
		if(body.getPosition().y < 0)
			die(this) ; 
		
		if(speed_current < 0)
			reverse = false ;
		else if(speed_current > 0)
			reverse = true ;
		
		checkForState(false) ; 
	}
	
	public void resetJump()
	{
		checkForState(true);
		jump_remaining = jump_max; 
	}
	
	/** The drawing fields, off the bodies as they stand. */
	private void showBodies()
	{
		Body bodyAxe = axe.bodyAxe ; 
		show(body.getPosition().x, body.getPosition().y, hp_left, invulnerable, bodyAxe.getPosition().x, bodyAxe.getPosition().y, bodyAxe.getAngle()) ; 
	}
	
	int power = 50 ; 
	public void pushAxe(boolean left)
	{
		axe.bodyAxe.applyForce(new Vector2(left ? -power : power, left ? power : -power), axe.bodyAxe.getLocalCenter(), true);
	}
	
	public void checkForState(boolean grounding)
	{
		if((currentAnimState == Enum_AnimState.JUMP || (currentAnimState == Enum_AnimState.FALL)) && !grounding) {
			return ; 
		}
		
		
		if(currentAnimState == Enum_AnimState.HURT && !currentState.isAnimationFinished(stateTime))
		{return ;}
		
			
	
		Enum_AnimState shouldBe = null ; 
		if (Math.abs(speed_current) < speed_minSpeed_Idle) {
			shouldBe = Enum_AnimState.IDLE ;
		} 
		else
		{shouldBe = Enum_AnimState.RUN ;} 
//		else 
//		{shouldBe = Enum_AnimState.WALK;}
		
		
		if(shouldBe != null)
			changeAnimationState(shouldBe,true) ;
	}
	
	public void jump() 
	{
		if(jump_remaining == 0)
			return ; 
		
			if(body.getLinearVelocity().y < 0)
		{
			body.setLinearVelocity(body.getLinearVelocity().x,body.getLinearVelocity().y/3);
			body.applyForceToCenter(0, jump_strenght * PPM, true);
		}
		else if(body.getLinearVelocity().y < maxUpSpeed)
		{body.applyForceToCenter(0, jump_strenght * PPM, true);}
		else
		{body.applyForceToCenter(0, jump_strenght/1.5f * PPM, true);}
		
		changeAnimationState(Enum_AnimState.JUMP, false) ; 
		jump_remaining -- ; 
	}
	
	public void getHurt(PhysicSpriteHeroes player)
	{
		if(invulnerable)
			return ; 
		
		hp_left -- ; 
		changeAnimationState(Enum_AnimState.HURT, false) ; 
		
		if(hp_left == 0)
			die(player) ;

		invulnerable = true ; 
	}
	
	private void die(PhysicSpriteHeroes player)
	{
		addDeath() ;
		GVars_Game.toDie.add(this) ; 
	}
	
	public void kill()
	{
		for(int x = 0 ; x < Index_Sprite.persoModel.size() ; x++)
		{
			if(Index_Sprite.persoModel.get(x) == index)
			{Index_Sprite.colorUsers.set(x, Index_Sprite.colorUsers.get(x) - 1) ;}
		}
		
		GVars_Game.heroes.remove(this) ; 
		for(PhysicSpriteEnnemy ennemy : GVars_Game.ennemies)
		{
			if(ennemy.target == this)
				ennemy.target = null ; 
		}
		
		GVars_Game.toBeDestroy_Body.add(body) ;
		GVars_Game.toBeDestroy_Body.add(axe.bodyAxe) ;
		GVars_Game.toBeDestroy_Jointure.add(joint) ;
		GVars_Controller.playerList.remove(player) ;
	}
	
	public boolean tryHealing() 
	{
		if(hp_left < hp_max)
		{
			hp_left ++ ; 
//			addScore(1) ; 
			return true ; 
		}
		else
		{
			addScore(2) ;
			return true ; 
		}
		//return false;
	}
	
	public void addDeath()
	{
		score.deathNumber ++ ; 
		score.scoreNumber = score.scoreNumber/2 ; 
		score.update() ; 
	}
	
	public void addScore(int value)
	{
		score.scoreNumber += value ; 
		score.update(); 
	}
}
