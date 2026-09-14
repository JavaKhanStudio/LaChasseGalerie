package jks.personnage;

import static jks.physic.FVars_Physic.PPM;
import static jks.physic.Gvars_Physic.world;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef.BodyType;

import jks.physic.tools.BoxBodyBuilder;

public class Weapon_AXE 
{
	
	public Body bodyAxe ; 
	public Sprite spriteAxe ; 
	PhysicSpriteHeroes ref ; 
	
	public Weapon_AXE(PhysicSpriteHeroes ref) 
	{
		this.ref = ref ; 
		bodyAxe = BoxBodyBuilder.CreateCircleBody(world, BodyType.DynamicBody, ref.position.x/PPM,ref.position.y/PPM + 30/PPM, 100);
		bodyAxe.getFixtureList().get(0).setUserData(this);
		Texture img = new Texture("tools/double_axe.png");
        spriteAxe = new Sprite(img) ;
	}
	
	public void act(float delta)
	{
		
	}
	
	public void draw(Batch batch)
	{
		 spriteAxe.setPosition(
				 (bodyAxe.getPosition().x * PPM) - spriteAxe.getWidth()/2 ,
				 (bodyAxe.getPosition().y * PPM) -spriteAxe.getHeight()/2 );


		 spriteAxe.setRotation((float)Math.toDegrees(bodyAxe.getAngle()) + 90);
		 batch.draw(spriteAxe, 
				 	spriteAxe.getX(), spriteAxe.getY(),
				 	spriteAxe.getOriginX(),spriteAxe.getOriginY(),
	                spriteAxe.getWidth(),spriteAxe.getHeight(),
	                spriteAxe.getScaleX(),spriteAxe.getScaleY(),
	                spriteAxe.getRotation());
	}

	public void addOneKill() 
	{
		ref.addScore(1);
		
	}
	
}
