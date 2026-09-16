package jks.vars;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.TreeMap;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.Joint;

import jks.camera.GVars_Camera;
import jks.input.GVars_Controller;
import jks.input.PlayerId;
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
	// Sets : two contacts in the same physics step can queue the same object twice,
	// and destroying a Box2D body twice crashes the native library.
	public static LinkedHashSet<PhysicSpriteHeroes> toDie ; 
	public static LinkedHashSet<Body> toBeDestroy_Body ; 
	public static LinkedHashSet<Joint> toBeDestroy_Jointure ;
	
	/** Sorted by PlayerId, like GVars_Controller.playerList. */
	public static TreeMap<PlayerId,ScoreLabel> playerRegister ; 
	
	/**
	 * The last entity id handed out this run (phase 1.3). A hero, a monster and a potion each get one
	 * when they are made, from this one counter, so no two living entities share an id whatever
	 * their kind : a snapshot names them by it, and a client creates, moves and destroys by it.
	 * 16 bits on the wire, so it wraps past 0xFFFF ; 0 is never handed out.
	 */
	private static int lastEntityId ; 
	private static boolean entityIdsWrapped ; 
	
	public static void init()
	{
		inCinematic = false ; 
		lastEntityId = 0 ; 
		entityIdsWrapped = false ; 
		heroes = new ArrayList<PhysicSpriteHeroes>() ; 
		ennemies = new ArrayList<PhysicSpriteEnnemy>() ;
		toBeDestroy_Body = new LinkedHashSet<Body>() ; 
		toBeDestroy_Jointure = new LinkedHashSet<Joint>() ;
		toDie = new LinkedHashSet<PhysicSpriteHeroes>() ;
		hpStack = new ArrayList<PhysicSpriteHp>() ; 
		playerRegister = new TreeMap<PlayerId,ScoreLabel>() ;
	}
	
	/**
	 * Lets go of the run. Nothing here destroys a body : Gvars_Physic.dispose takes the whole world
	 * down at once, so the destroy queues are dropped, not drained, and no monster is left holding
	 * a target. Call it with Gvars_Physic.dispose, never on its own.
	 */
	public static void dispose()
	{
		if(canoe != null)
			canoe.dispose() ; 
		canoe = null ; 
		heroes = null ; 
		ennemies = null ; 
		hpStack = null ; 
		toDie = null ; 
		toBeDestroy_Body = null ; 
		toBeDestroy_Jointure = null ; 
		playerRegister = null ; 
	}


	/**
	 * A fresh id for an entity being made. A hero that dies and rejoins is a new entity, so a new id
	 * for the same player. After a wrap an id still worn by a living entity is skipped : a potion that
	 * fell off the world keeps its body, and its id, forever (n7).
	 */
	public static int newEntityId()
	{
		while(true)
		{
			lastEntityId++ ; 
			if(lastEntityId > 0xFFFF)
			{
				lastEntityId = 1 ; 
				entityIdsWrapped = true ; 
			}
			if(!entityIdsWrapped || !entityIdInUse(lastEntityId))
				return lastEntityId ; 
		}
	}
	
	private static boolean entityIdInUse(int id)
	{
		for(PhysicSpriteHeroes hero : heroes)
			if(hero.id == id)
				return true ; 
		for(PhysicSpriteEnnemy ennemy : ennemies)
			if(ennemy.id == id)
				return true ; 
		for(PhysicSpriteHp hp : hpStack)
			if(hp.id == id)
				return true ; 
		return false ; 
	}

	/**
	 * Gives this player a hero. Local devices get their PlayerId from GVars_Controller.identify ;
	 * a remote player will come with one. The label stays theirs across deaths.
	 */
	public static void addPlayer(PlayerId player)
	{
		PhysicSpriteHeroes physicSprite = new PhysicSpriteHeroes(Index_Sprite.getRandomHeroColor(), player, getScoreLabel(player)) ; 
		heroes.add(physicSprite) ; 
		GVars_Controller.playerList.put(player, new Player_Inputs(physicSprite)) ; 
	}
	
	private static ScoreLabel getScoreLabel(PlayerId player) 
	{
		ScoreLabel label = playerRegister.get(player) ; 
		if(label == null)
		{
			label = new ScoreLabel("   Player " + player.number() + "   ") ;
			GVars_Interface.bottomScore.add(label);
			playerRegister.put(player, label) ; 
		}
		return label;
	}

	public static void addEnnemy_Top()
	{
		PhysicSpriteEnnemy physic = 
				new PhysicSpriteEnnemy(
						Index_Sprite.getRandomEnnemy(),
						GVars_Camera.viewWidth * GVars_Camera.worldMutiplier * (GVars_Random.random.nextInt(100) + 1)/100,
						GVars_Camera.viewHeight * GVars_Camera.worldMutiplier
						) ;
		ennemies.add(physic) ; 
	}
	
	public static void addEnnemy_Side()
	{
		PhysicSpriteEnnemy physic = 
				new PhysicSpriteEnnemy(
						Index_Sprite.getRandomEnnemy(),
						GVars_Random.random.nextBoolean() ? GVars_Camera.viewWidth * 2 : 0,
						GVars_Camera.viewHeight * GVars_Camera.worldMutiplier / (GVars_Random.random.nextInt(2) + 2)
						) ;
		ennemies.add(physic) ; 
	}

	public static void dropHp() 
	{
		PhysicSpriteHp physic = 
				new PhysicSpriteHp(	
						GVars_Random.random.nextInt(GVars_Camera.viewWidth) * GVars_Camera.worldMutiplier ,
						GVars_Camera.viewHeight * GVars_Camera.worldMutiplier
						) ;
		hpStack.add(physic) ; 
	}
	
	public static PhysicSpriteHeroes checkForTarget() 
	{
		if(heroes.size() == 0)
			return null ; 
		return heroes.get(GVars_Random.random.nextInt(heroes.size())) ; 
	}
	
}