package jks.physic;

import static jks.physic.FVars_Physic.gravity;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Box2D;
import com.badlogic.gdx.physics.box2d.World;


public class Gvars_Physic 
{

	public static World world;
	public static MyContactListener contractListener;
	
	public static void init() 
	{
		Box2D.init();
		world = new World(new Vector2(0, -gravity),true);
		contractListener = new MyContactListener() ; 
		world.setContactListener(contractListener) ; 
	}
	
	public static void act() 
	{
		world.step(1f/60f, 6, 2);
	}

}
