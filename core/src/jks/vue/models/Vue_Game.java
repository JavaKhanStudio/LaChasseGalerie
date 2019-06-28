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
import jks.input.GVars_Controller;
import jks.input.IKM_Game_Keyboard;
import jks.input.IKM_Game_XBoxController;
import jks.parralax.Enum_ColdNight;
import jks.personnage.PhysicSpriteEnnemy;
import jks.personnage.PhysicSpriteHeroes;
import jks.physic.Gvars_Physic;
import jks.physic.objects.PhysicSpriteCanoe;
import jks.physic.objects.PhysicSpriteHp;
import jks.sounds.Enum_Ambiance;
import jks.sounds.Enum_Music;
import jks.sounds.GVars_AudioManager;
import jks.story.GVars_Story;
import jks.tools2d.parallax.heart.Parallax_Heart;
import jks.tools2d.parallax.heart.Parallax_Utils_Page;
import jks.vars.GVars_Game;
import jks.vars.GVars_Heart;
import jks.vinterface.GVars_Interface;
import jks.vinterface.ToRender;
import jks.vue.AVue_Model;

public class Vue_Game extends AVue_Model
{
    
   
    boolean inDebug = true ; 
    Box2DDebugRenderer debugRenderer ;
    public static Sprite star1 ; 
    
    @Override
    public void init() 
    {
    	GVars_Heart.init();
    	GVars_Game.init();
    	Parallax_Utils_Page.setPage(Enum_ColdNight.COLD_NIGHT.wholePage) ;
    	Parallax_Utils_Page.setSecondPage(Enum_ColdNight.COLD_WATER.wholePage) ;
//    	buildGround() ; 
    	canoe = new PhysicSpriteCanoe() ; 
    	
    	if(GVars_Debug.collisionDebug)
    		debugRenderer = new Box2DDebugRenderer() ; 
    	
    	Gdx.input.setInputProcessor(new InputMultiplexer(GVars_Interface.mainInterface, new IKM_Game_Keyboard()));
		Controllers.clearListeners();
		Controllers.addListener(new IKM_Game_XBoxController()) ; 
		GVars_AudioManager.PlayAmbiance(Enum_Ambiance.WATER);
		
//		if(GVars_Debug.collisionDebug)
//		{this.toRender.add(new ShowFPS());}
//		for(int a = 0 ; a < 10 ; a++)
//			GVars_Game.addEnnemy() ; 
//		testBall() ; 
		star1 = new Sprite(new Texture("stars/Stars Small_1.png")) ; 
		star1.setPosition(0, Gdx.graphics.getHeight()/1.1f * GVars_Camera.worldMutiplier);
    }

    @Override
    public void render() 
    {
    	camera.update();
    	Gdx.gl.glClearColor(1, 1, 1, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        staticBatch.setProjectionMatrix(camera.combined);
    	
    	Parallax_Heart.worldCamera.position.add(screenMovementSpeed, 0, 0);
    	Parallax_Heart.renderMainPage();
		
    	
    	staticBatch.begin();
    	
    	star1.draw(staticBatch);
    	
    	canoe.drawBack(staticBatch);
    	staticBatch.setProjectionMatrix(camera.combined);
    	for(PhysicSpriteHeroes model : GVars_Game.heroes)
    		model.draw(staticBatch);
    	for(PhysicSpriteEnnemy model : GVars_Game.ennemies)
    		model.draw(staticBatch);
    	for(PhysicSpriteHp model : GVars_Game.hpStack)
    		model.draw(staticBatch);
    	canoe.drawFront(staticBatch);
    	staticBatch.end();
    	
    	Parallax_Heart.renderSecondePage();
    	
    	staticBatch.begin();
    	for(PhysicSpriteHeroes model : GVars_Game.heroes)
    		model.drawHp(staticBatch);
    	staticBatch.end();
    	
    	staticBatch.setColor(Color.WHITE);
    	
    	if(GVars_Debug.collisionDebug)
    		debugRenderer.render(world, camera.combined.cpy().scale(PPM, PPM, 1));
    	
    	GVars_Interface.mainInterface.draw();
    	
    	for (ToRender rende : toRender) 
			rende.render();
    }

	@Override
	public void destroy() 
	{
		// TODO Auto-generated method stub
		
	}

	@Override
	public void restart() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void update(float delta) 
	{
		
		cleanUp() ; 
		Gvars_Physic.act();
    	GVars_Story.act(delta);
    	canoe.act(delta);
    	
    	for(PhysicSpriteHeroes model : GVars_Game.heroes)
    		model.act(delta);
    	
    	for(PhysicSpriteEnnemy model : GVars_Game.ennemies)
    		model.act(delta);
    	
    	GVars_Controller.act(delta);
    	
    	Parallax_Heart.act(delta);	
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

    /*
    public void buildGround()
    {
    	// Now the physics body of the bottom edge of the screen
        BodyDef bodyDef3 = new BodyDef();
        bodyDef3.type = BodyDef.BodyType.StaticBody;
        float w = Gdx.graphics.getWidth()/PPM/1.5f;
        float h = 50/PPM;

        bodyDef3.position.set(16,Gdx.graphics.getHeight()/2/PPM);
        FixtureDef fixtureDef3 = new FixtureDef();

        PolygonShape shape = new PolygonShape();
        shape.setAsBox(w,h);
        fixtureDef3.shape = shape;

        bodyEdgeScreen = world.createBody(bodyDef3);
        bodyEdgeScreen.createFixture(fixtureDef3);
        shape.dispose();
    }
    */
    
   
   
}