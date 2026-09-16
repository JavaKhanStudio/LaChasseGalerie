package jks.vue.models;

import static jks.camera.GVars_Camera.camera;
import static jks.camera.GVars_Camera.screenMovementSpeed;
import static jks.camera.GVars_Camera.staticBatch;
import static jks.physic.FVars_Physic.PPM;
import static jks.physic.Gvars_Physic.world;
import static jks.vars.GVars_Game.canoe;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.Box2DDebugRenderer;
import com.badlogic.gdx.physics.box2d.Joint;

import jks.camera.GVars_Camera;
import jks.debug.GVars_Debug;
import jks.debug.ShowFPS;
import jks.input.GVars_Controller;
import jks.input.IKM_Game_Keyboard;
import jks.input.IKM_Game_XBoxController;
import jks.input.Menu_Picker;
import jks.parralax.Enum_ColdNight;
import jks.parralax.GVars_Parralax;
import jks.personnage.PhysicSpriteEnnemy;
import jks.personnage.PhysicSpriteHeroes;
import jks.physic.Gvars_Physic;
import jks.physic.objects.PhysicSpriteCanoe;
import jks.physic.objects.PhysicSpriteHp;
import jks.sounds.Enum_Ambiance;
import jks.sounds.GVars_AudioManager;
import jks.story.GVars_Story;
import jks.vars.GVars_Game;
import jks.vars.GVars_Heart;
import jks.vinterface.GVars_Interface;
import jks.vinterface.ToRender;
import jks.vue.AVue_Model;

public class Vue_Game extends AVue_Model
{
    
    Box2DDebugRenderer debugRenderer ;
    public static Sprite star1 ; 
    
    /** The hand that started this run, from the menu. Never null : POINTER when nobody's did. */
    private final Menu_Picker starter ;
    
    /** A run nobody in particular asked for : --menu off, or a mouse click. Everyone joins by hand. */
    public Vue_Game()
    {this(Menu_Picker.POINTER) ;}
    
    public Vue_Game(Menu_Picker starter)
    {this.starter = starter ;}
    
    @Override
    public void init() 
    {
    	GVars_Heart.init();
    	GVars_Game.init();
    	GVars_Story.init();
    	GVars_Parralax.setPages(Enum_ColdNight.COLD_NIGHT, Enum_ColdNight.COLD_WATER) ;
    	canoe = new PhysicSpriteCanoe() ; 
    	
    	if(GVars_Debug.collisionDebug)
    		debugRenderer = new Box2DDebugRenderer() ; 
    	
    	if(GVars_Debug.coreInformationDebug)
    		toRender.add(new ShowFPS());
    	
    	Gdx.input.setInputProcessor(new InputMultiplexer(GVars_Interface.mainInterface, new IKM_Game_Keyboard()));
		Controllers.clearListeners();
		Controllers.addListener(new IKM_Game_XBoxController()) ; 
		GVars_AudioManager.PlayAmbiance(Enum_Ambiance.WATER);
		
		star1 = new Sprite(new Texture("stars/Stars Small_1.png")) ; 
		star1.setPosition(0, GVars_Story.starStartY());
		
		// Last, once the world, the score table and the canoe exist : the pad or the keyboard that
		// chose this run is already player 1 and does not press a second time to get in (d9)
		starter.joinTheRun();
    }

    @Override
    public void render() 
    {
    	// Black bars outside the world, then the world's view : everything below is drawn inside it
    	Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    	Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    	GVars_Camera.viewport.apply();
    	camera.update();

        staticBatch.setProjectionMatrix(camera.combined);
    	
    	GVars_Parralax.background.render();
    	
    	staticBatch.begin();
    	star1.draw(staticBatch);
    	canoe.drawBack(staticBatch);
    	for(PhysicSpriteHeroes model : GVars_Game.heroes)
    		model.draw(staticBatch);
    	for(PhysicSpriteEnnemy model : GVars_Game.ennemies)
    		model.draw(staticBatch);
    	for(PhysicSpriteHp model : GVars_Game.hpStack)
    		model.draw(staticBatch);
    	canoe.drawFront(staticBatch);
    	staticBatch.end();
    	
    	GVars_Parralax.foreground.render();
    	
    	staticBatch.begin();
    	for(PhysicSpriteHeroes model : GVars_Game.heroes)
    		model.drawHp(staticBatch);
    	staticBatch.end();
    	
    	staticBatch.setColor(Color.WHITE);
    	
    	if(GVars_Debug.collisionDebug)
    		debugRenderer.render(world, camera.combined.cpy().scale(PPM, PPM, 1));
    	
    	// The HUD is laid out on the window, not in the world
    	GVars_Interface.mainInterface.getViewport().apply();
    	GVars_Interface.mainInterface.draw();
    	
    	for (ToRender rende : toRender) 
			rende.render();
    }

	@Override
	public void update(float delta) 
	{
		cleanUp() ; 
		Gvars_Physic.act(delta);
    	GVars_Story.act(delta);
    	canoe.act(delta);
    	
    	for(PhysicSpriteHeroes model : GVars_Game.heroes)
    		model.act(delta);
    	
    	for(PhysicSpriteEnnemy model : GVars_Game.ennemies)
    		model.act(delta);
    	
    	GVars_Controller.act(delta);
    	
    	GVars_Parralax.scroll(delta, screenMovementSpeed, 0);
    	GVars_Parralax.act(delta);	
	}
	
	/**
	 * The run is over : a second one can start in this JVM (phase 0.4). GVars_Heart.changeVue calls
	 * this between two updates, never from inside world.step, so disposing the world is safe here.
	 * The world goes down whole rather than body by body, and the lists that pointed into it go
	 * with it : no monster is left chasing a hero, and nothing waits in the destroy queue.
	 */
	@Override
	public void dispose()
	{
		Gdx.input.setInputProcessor(null);
		Controllers.clearListeners();
		
		GVars_Game.dispose();
		GVars_Heart.dispose();
		
		star1.getTexture().dispose();
		star1 = null ;
		if(debugRenderer != null)
			debugRenderer.dispose();
	}
	
	public void cleanUp()
	{
		for(PhysicSpriteHeroes dying : GVars_Game.toDie)
		{dying.kill();}
		
		GVars_Game.toDie.clear();
		
		for(Joint join : GVars_Game.toBeDestroy_Jointure)
			world.destroyJoint(join);
		
		GVars_Game.toBeDestroy_Jointure.clear();
		
		for(Body body : GVars_Game.toBeDestroy_Body)
			world.destroyBody(body);
		
		GVars_Game.toBeDestroy_Body.clear();
	}
   
}
