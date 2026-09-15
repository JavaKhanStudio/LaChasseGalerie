package jks.personnage;

import static jks.physic.FVars_Physic.PPM;
import static jks.physic.Gvars_Physic.world;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.FixtureDef;
import com.badlogic.gdx.physics.box2d.Joint;
import com.badlogic.gdx.physics.box2d.PolygonShape;

import jks.camera.GVars_Camera;
import jks.input.GVars_Controller;
import jks.personnage.index.Enum_AnimState;
import jks.personnage.index.Index_Sprite;
import jks.personnage.index.SIW_Data;
import jks.personnage.model.SpriteModel;
import jks.vars.GVars_Game;
import jks.physic.tools.BoxBodyBuilder;
import jks.physic.tools.RevoluteJoint; 

public class PhysicSpriteHeroes extends SpriteModel
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
	
	public int hp_max = 4 ;
	public int hp_left = hp_max ; 
//	public int hp_left = 1 ; 
	
	public Controller controller ; 
	public boolean invulnerable ; 
	public float invulnerable_Timmer ;
	public final float invulnerable_Base = 3.0f ;
	
	public static Texture heartTexture; 
	public Color painColor ; 
	
	private static float baseWidth ; 
	private static float baseHeight ; 
	private static float fractions ; 
	
	public ScoreLabel score ; 
	
	public PhysicSpriteHeroes(SIW_Data index, Controller controller, ScoreLabel scoreRegister) 
	{
		super(index);
		score = scoreRegister ;
		score.setColor(index.color);
		this.controller = controller ; 
		this.position.add(Gdx.graphics.getWidth()/2 * GVars_Camera.worldMutiplier,Gdx.graphics.getHeight()/2 * GVars_Camera.worldMutiplier) ; 
		currentFrame = currentState.getKeyFrame(0,false) ;
		
		BodyDef bodyDef = new BodyDef();
        bodyDef.type = BodyDef.BodyType.DynamicBody;
        bodyDef.position.set(this.position.x/PPM,this.position.y/PPM);
        bodyDef.fixedRotation = true ; 
        bodyDef.gravityScale = 2 ; 
        
        body = world.createBody(bodyDef);
       
        baseWidth = getFrameWidth(currentFrame) ; 
        baseHeight = getFrameHeight(currentFrame) ;
        fractions = baseWidth/(hp_max-1) ; 
       
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
        invulnerable_CurrentColor = colors[0] ; 
        invulnerable = true ; 
        
        painColor = index.color.cpy().sub(0, 0, 0, 0.4f) ; 
        
        if(heartTexture == null)
        	heartTexture = new Texture("tools/heart.png") ; 
             
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
		update(delta) ; 
		
		if(checkForGround && Math.abs(body.getLinearVelocity().y) < groundedThreshold)
		{
			resetJump() ; 
		}
		else if(checkForGround)
		{checkForGround = false ;}
		
		if(invulnerable)
		{
			invulnerable_Timmer += delta ;
			invulnerable_ColorTimmmer += delta ; 
			if(invulnerable_Timmer > invulnerable_Base)
			{
				invulnerable = false ;
				invulnerable_Timmer = 0 ; 
			}
		}
		
		if(hp_left == 1)
			lastHp_ColorTimmmer += delta ; 
			
		
		position.x = body.getPosition().x * PPM - getFrameWidth(currentFrame)/ 2; 
		position.y = body.getPosition().y * PPM - getFrameHeight(currentFrame)/ 2; 
		
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
	
	int power = 50 ; 
	public void pushAxe(boolean left)
	{
		axe.bodyAxe.applyForce(new Vector2(left ? -power : power, left ? power : -power), axe.bodyAxe.getLocalCenter(), true);
	}
	
	@Override
	public void draw(Batch batch) 
	{
		if(invulnerable)
			colorChanging_Invul(batch) ; 
		
		super.draw(batch);
		
		batch.setColor(Color.WHITE);
		axe.draw(batch);
		
	}

	
	float invulnerable_ColorTimmmer ; 
	float invulnerable_timeBetween = 0.1f; 
	Color invulnerable_CurrentColor ; 
	int colorCounter = 0 ; 
	private static final float trans = 0.70f ;
	private static final float trans2 = 0.90f ;
	private static final float step1 = 0.70f ;
	private static final float step2 = 0.60f ;
	private static final float step3 = 0.50f ;
	private static final float step4 = 0.40f ;
	
//	private static final float bonus = 0.20f ;
	
	private static Color[] colors = new Color[] {
			new Color(step1,step1,step1,trans),
			new Color(step2,step2,step2,trans2),
			new Color(step3,step3,step3,trans),
			new Color(step4,step4,step4,trans2),
			new Color(step3,step3,step3,trans),
			new Color(step2,step2,step2,trans2)
	} ; 
	
	private void colorChanging_Invul(Batch batch) 
	{
		if(invulnerable_timeBetween < invulnerable_ColorTimmmer)
		{
			colorCounter ++ ; 
			if(colorCounter == colors.length)
				colorCounter = 0 ;
			
			invulnerable_CurrentColor = colors[colorCounter] ; 
			invulnerable_ColorTimmmer = 0 ; 
		}
		batch.setColor(invulnerable_CurrentColor);
		
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
	
	@Override
	public float getFrameWidth(TextureRegion frame)
	{return (frame.getRegionWidth() * index.scale) ;}
	
	@Override
	public float getFrameHeight(TextureRegion frame)
	{return (frame.getRegionHeight() * index.scale);}

	
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
		GVars_Controller.playerList.remove(controller) ;
	}
	
	float lastHp_ColorTimmmer ; 
	float lastHp_timeBetween = 0.4f; 
	boolean drawClassic ; 
	
	public void drawHp(Batch batch)
	{
		if(hp_left > 1)
			batch.setColor(index.color);
		else
		{
			if(lastHp_ColorTimmmer > lastHp_timeBetween)
			{
				lastHp_ColorTimmmer = 0 ; 
				drawClassic = !drawClassic ; 
			}
			
			batch.setColor(drawClassic ? painColor : Color.LIGHT_GRAY);
		
		}
		for(int x = 1 ; x <= hp_left; x++)
		{
			batch.draw(heartTexture, 
					body.getPosition().x * PPM - baseWidth + fractions * x , 
					body.getPosition().y* PPM - baseHeight/2 - fractions,
					fractions,fractions);
		}
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
