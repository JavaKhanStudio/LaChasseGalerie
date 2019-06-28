package jks.vars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.Joint;

import jks.camera.GVars_Camera;
import jks.input.GVars_Controller;
import jks.input.Player_Inputs;
import jks.personnage.PhysicSpriteEnnemy;
import jks.personnage.PhysicSpriteHeroes;
import jks.personnage.ScoreLabel;
import jks.personnage.index.Index_Sprite;
import jks.physic.objects.PhysicSpriteCanoe;
import jks.physic.objects.PhysicSpriteHp;
import jks.vinterface.GVars_Interface;

public class GVars_Game 
{
	public static boolean inCinematic = false ;
	public static ArrayList<PhysicSpriteHeroes> heroes ; 
	
	public static PhysicSpriteCanoe canoe ;
	
	public static ArrayList<PhysicSpriteHp> hpStack ; 
	public static ArrayList<PhysicSpriteEnnemy> ennemies ; 
	public static ArrayList<PhysicSpriteHeroes> toDie ; 
	public static ArrayList<Body> toBeDestroy_Body ; 
	public static ArrayList<Joint> toBeDestroy_Jointure ;
	
	public static HashMap<Controller,ScoreLabel> playerRegister ; 
	public static int currentPlayerIndex = 1 ; 
	
	public static void init()
	{
		heroes = new ArrayList<PhysicSpriteHeroes>() ; 
		ennemies = new ArrayList<PhysicSpriteEnnemy>() ;
		toBeDestroy_Body = new ArrayList<Body>() ; 
		toBeDestroy_Jointure = new ArrayList<Joint>() ;
		toDie = new ArrayList<PhysicSpriteHeroes>() ;
		hpStack = new ArrayList<PhysicSpriteHp>() ; 
		playerRegister = new  HashMap<Controller,ScoreLabel>()  ;
	}


	public static void addPlayer(Controller controller)
	{
		GVars_Controller.playerList.put(controller, buildPlayer(controller)) ; 
	}
	
	public static void addPlayer()
	{
		GVars_Controller.pcPlayer = buildPlayer(null) ;
		GVars_Controller.playerList.put(null,GVars_Controller.pcPlayer) ; 
	}
	
	private static Player_Inputs buildPlayer(Controller controller)
	{
		PhysicSpriteHeroes physicSprite = new PhysicSpriteHeroes(Index_Sprite.getRandomHeroColor(),controller, getScoreLabel(controller)) ; 
		heroes.add(physicSprite) ; 
		
		String name = "player_" + heroes.size() ; 
		physicSprite.setPlayerName(name);

		return new Player_Inputs(physicSprite) ; 
	}
	
	private static ScoreLabel getScoreLabel(Controller controller) 
	{
		ScoreLabel label = playerRegister.get(controller) ; 
		if(label == null)
		{
			label = new ScoreLabel("   Player " + currentPlayerIndex + "   ") ;
			currentPlayerIndex ++ ; 
			GVars_Interface.bottomScore.add(label);
			System.out.println("adding label");
			playerRegister.put(controller, label) ; 
		}
		return label;
	}

	static Random random = new Random() ; 
	
	public static void addEnnemy_Top()
	{
		PhysicSpriteEnnemy physic = 
				new PhysicSpriteEnnemy(
						Index_Sprite.getRandomEnnemy(),
						Gdx.graphics.getWidth() * GVars_Camera.worldMutiplier * (random.nextInt(100) + 1)/100,
						Gdx.graphics.getHeight() * GVars_Camera.worldMutiplier
						) ;
		ennemies.add(physic) ; 
	}
	
	public static void addEnnemy_Side()
	{
		PhysicSpriteEnnemy physic = 
				new PhysicSpriteEnnemy(
						Index_Sprite.getRandomEnnemy(),
						random.nextBoolean() ? Gdx.graphics.getWidth() * 2 : 0,
						Gdx.graphics.getHeight() * GVars_Camera.worldMutiplier / (random.nextInt(2) + 2)
						) ;
		ennemies.add(physic) ; 
	}

	public static void dropHp() 
	{
		PhysicSpriteHp physic = 
				new PhysicSpriteHp(	
						random.nextInt(Gdx.graphics.getWidth()) * GVars_Camera.worldMutiplier ,
						Gdx.graphics.getHeight() * GVars_Camera.worldMutiplier
						) ;
		hpStack.add(physic) ; 
	}
	
	public static PhysicSpriteHeroes checkForTarget() 
	{
		if(heroes.size() == 0)
			return null ; 
		return heroes.get(random.nextInt(heroes.size())) ; 
	}
	
}