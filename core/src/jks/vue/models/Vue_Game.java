package jks.vue.models;

import static jks.camera.GVars_Camera.camera;
import static jks.camera.GVars_Camera.screenMovementSpeed;
import static jks.camera.GVars_Camera.staticBatch;
import static jks.physic.FVars_Physic.PPM;
import static jks.physic.Gvars_Physic.world;
import static jks.vars.GVars_Game.canoe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import jks.input.PlayerId;
import jks.parralax.Enum_ColdNight;
import jks.parralax.GVars_Parralax;
import jks.personnage.PhysicSpriteEnnemy;
import jks.personnage.PhysicSpriteHeroes;
import jks.personnage.ScoreLabel;
import jks.physic.Gvars_Physic;
import jks.physic.objects.PhysicSpriteCanoe;
import jks.physic.objects.PhysicSpriteHp;
import jks.sounds.Enum_Ambiance;
import jks.sounds.GVars_AudioManager;
import jks.story.GVars_Story;
import jks.vars.GVars_Game;
import jks.vars.GVars_Heart;
import jks.vinterface.GVars_Interface;
import jks.vinterface.Menu_Focus;
import jks.vinterface.Score_Screen;
import jks.vinterface.ToRender;
import jks.vue.AVue_Model;

public class Vue_Game extends AVue_Model
{
    
    Box2DDebugRenderer debugRenderer ;
    public static Sprite star1 ; 
    
    /** The hand that started this run, from the menu. Never null : POINTER when nobody's did. */
    private final Menu_Picker starter ;
    
    /** A run whose song is over : the final scores, and what this window's player picks next (d14, d15). Null until then. */
    private Score_Screen scoreScreen ;
    /** The score screen was opened by a host, so it offers Close the server rather than Menu. */
    private boolean hostedEnding ;
    
    /** A run nobody in particular asked for : --menu off, or a mouse click. Everyone joins by hand. */
    public Vue_Game()
    {this(Menu_Picker.POINTER) ;}
    
    public Vue_Game(Menu_Picker starter)
    {this.starter = starter ;}
    
    @Override
    public void init() 
    {
    	GVars_Heart.init();
    	GVars_Heart.runsStarted++ ;
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
    	
    	// Over the score screen the run is over and the canoe empty, as its clients are sent it (Snapshot_View)
    	boolean playing = scoreScreen == null ; 
    	
    	staticBatch.begin();
    	star1.draw(staticBatch);
    	canoe.drawBack(staticBatch);
    	if(playing)
    	{
	    	for(PhysicSpriteHeroes model : GVars_Game.heroes)
	    		model.draw(staticBatch);
	    	for(PhysicSpriteEnnemy model : GVars_Game.ennemies)
	    		model.draw(staticBatch);
	    	for(PhysicSpriteHp model : GVars_Game.hpStack)
	    		model.draw(staticBatch);
    	}
    	canoe.drawFront(staticBatch);
    	staticBatch.end();
    	
    	GVars_Parralax.foreground.render();
    	
    	if(playing)
    	{
	    	staticBatch.begin();
	    	for(PhysicSpriteHeroes model : GVars_Game.heroes)
	    		model.drawHp(staticBatch);
	    	staticBatch.end();
    	}
    	
    	staticBatch.setColor(Color.WHITE);
    	
    	if(GVars_Debug.collisionDebug)
    		debugRenderer.render(world, camera.combined.cpy().scale(PPM, PPM, 1));
    	
    	// The HUD is laid out on the window, not in the world. The score screen takes its place
    	if(playing)
    	{
	    	GVars_Interface.mainInterface.getViewport().apply();
	    	GVars_Interface.mainInterface.draw();
    	}
    	else
    		scoreScreen.draw();
    	
    	for (ToRender rende : toRender) 
			rende.render();
    }

	@Override
	public void update(float delta) 
	{
		// The song is over and the canoe is back on the river (d12). Before anything of this run is
		// touched, since a pick disposes it : the run stays on the score screen until one (d14, d15)
		if(GVars_Story.runOver())
		{
			waitForTheNextRun(delta) ; 
			return ; 
		}
		
		cleanUp() ; 
		Gvars_Physic.act(delta);
    	GVars_Story.act(delta);
    	canoe.act(delta);
    	
    	for(PhysicSpriteHeroes model : GVars_Game.heroes)
    		model.act(delta);
    	
    	for(PhysicSpriteEnnemy model : GVars_Game.ennemies)
    		model.act(delta);
    	
    	GVars_Game.removeFallenPotions();
    	
    	GVars_Controller.act(delta);
    	
    	GVars_Parralax.scroll(delta, screenMovementSpeed, 0);
    	GVars_Parralax.act(delta);	
	}
	
	/**
	 * The song is over (d14 hosted, d15 local). The world stops : nobody moves, spawns or scores any more,
	 * and a JOIN gets no hero (Game_Simulation.spawn). The river flows on under the final scores until
	 * this window's player picks :
	 * <ul>
	 * <li>New run : a fresh Vue_Game. The hand that picked is in it, as from the start menu (d9) ;
	 *     everyone else presses to join. Hosted, it runs under the same HostSession : the seats stay,
	 *     with their PlayerIds (GVars_Controller numbers on), and every client sees the run number
	 *     change and starts over.</li>
	 * <li>Menu, on a local run : back to the start menu.</li>
	 * <li>Close the server, on a hosted one : GVars_Heart.hosting goes false. Main_Game then closes the
	 *     session while this world still exists - every client gets HOST_ENDED - and the next update
	 *     goes to the menu.</li>
	 * </ul>
	 * Hosted, this is called inside HostSession.tick, which reads the world after it : a pick never
	 * tears the run down without putting another in its place.
	 */
	void waitForTheNextRun(float delta)
	{
		// The server is closed (Main_Game told every peer) : this run ends into the menu
		if(scoreScreen != null && hostedEnding && !GVars_Heart.hosting)
		{
			GVars_Heart.changeVue(new Vue_Menu());
			return ; 
		}
		
		if(scoreScreen == null)
			openScoreScreen() ; 
		
		// The pick made since the last update. Once it ran, this view may be the old one
		if(scoreScreen.focus.runPicked())
			return ; 
		
		GVars_Parralax.scroll(delta, screenMovementSpeed, 0);
		GVars_Parralax.act(delta);
		scoreScreen.act(delta);
	}
	
	void openScoreScreen()
	{
		List<Score_Screen.Row> rows = new ArrayList<Score_Screen.Row>() ; 
		for(Map.Entry<PlayerId, ScoreLabel> entry : GVars_Game.playerRegister.entrySet())
			rows.add(new Score_Screen.Row(entry.getKey().number(), entry.getValue().scoreNumber, entry.getValue().deathNumber, entry.getValue().score.getColor())) ; 
		
		hostedEnding = GVars_Heart.hosting ; 
		scoreScreen = new Score_Screen(rows, null) ; 
		scoreScreen.choice("New run", picker -> GVars_Heart.changeVue(new Vue_Game(picker))) ; 
		if(hostedEnding)
			scoreScreen.choice("Close the server", picker -> 
			{
				scoreScreen.say("Closing the server...") ; 
				GVars_Heart.hosting = false ; 
			}) ; 
		else
			scoreScreen.choice("Menu", picker -> GVars_Heart.changeVue(new Vue_Menu())) ; 
		scoreScreen.listen() ; 
	}
	
	/** The score screen's choices, while it is up : what a headless gate picks with. Null otherwise. */
	public Menu_Focus scoreChoices()
	{return scoreScreen == null ? null : scoreScreen.focus ;}
	
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
		
		if(scoreScreen != null)
			scoreScreen.dispose();
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
