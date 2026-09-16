package jks.personnage;

import static jks.physic.FVars_Physic.PPM;
import static jks.physic.Gvars_Physic.world;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef.BodyType;

import jks.physic.tools.BoxBodyBuilder;

/** The axe's body, on its hero's joint. It is drawn by Draw_Hero, from where this body is. */
public class Weapon_AXE 
{
	
	public Body bodyAxe ; 
	PhysicSpriteHeroes ref ; 
	
	public Weapon_AXE(PhysicSpriteHeroes ref) 
	{
		this.ref = ref ; 
		bodyAxe = BoxBodyBuilder.CreateCircleBody(world, BodyType.DynamicBody, ref.position.x/PPM,ref.position.y/PPM + 30/PPM, 100);
		bodyAxe.getFixtureList().get(0).setUserData(this);
	}
	
	public void act(float delta)
	{
		
	}
	
	public void addOneKill() 
	{
		ref.addScore(1);
		
	}
	
}
