package jks.vue.models;

import static jks.camera.GVars_Camera.camera;
import static jks.camera.GVars_Camera.staticBatch;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerAdapter;
import com.badlogic.gdx.controllers.ControllerMapping;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.scenes.scene2d.ui.Label;

import jks.camera.GVars_Camera;
import jks.draw.Draw_Canoe;
import jks.draw.Draw_Hero;
import jks.draw.Draw_Monster;
import jks.draw.Draw_Potion;
import jks.input.Player_Inputs;
import jks.input.Utils_Controller;
import jks.net.Net_Input;
import jks.net.Net_Snapshot;
import jks.net.Transport_Udp;
import jks.online.ClientSession;
import jks.online.Snapshot_Mirror;
import jks.parralax.Enum_ColdNight;
import jks.parralax.GVars_Parralax;
import jks.personnage.ScoreLabel;
import jks.personnage.index.Enum_AnimState;
import jks.personnage.index.Index_Sprite;
import jks.personnage.index.SIW_Data;
import jks.sounds.Enum_Ambiance;
import jks.sounds.GVars_AudioManager;
import jks.story.GVars_Story;
import jks.vars.GVars_Heart;
import jks.vinterface.GVars_Interface;
import jks.vinterface.ToRender;
import jks.vue.AVue_Model;

/**
 * A run this machine does not simulate (phase 1.5, docs/online-multiplayer.md section 2) : it draws
 * {@link ClientSession#view()} and sends this machine's buttons, and OWNS NO WORLD. Gvars_Physic is
 * never initialised here, nor GVars_Game or GVars_Controller : there is no body to read, so every
 * sprite is a Draw_* written from the snapshot, the same classes the host draws through.
 *
 * What moves the picture is the HOST's clock, not this one. The animation and blink clocks, the
 * river, the sky and the music cue all advance by how far the snapshot's story time moved since the
 * last tick (GVars_Story.followHost), so a picture that stalls waiting for snapshots stalls whole,
 * and a client that joins late sees the river where the host has it. What is interpolated and what is
 * not is ClientSession's : positions, axes and the clocks are ; hp, animation state, facing and the
 * blink are the older snapshot's.
 *
 * One machine is one player (d5 -> A) : the keyboard and every pad drive the same hero, and touching
 * any of them while that hero is not in the snapshots asks for one (the online first key press).
 * There is no prediction : your own hero answers about one round trip plus 100 ms later (section 4).
 */
public class Vue_Client extends AVue_Model
{
	private final String hostAddress ;
	private Transport_Udp transport ;
	private ClientSession client ;

	/** The buttons of this machine's one player, whatever device pressed them. No hero behind it. */
	private final Player_Inputs buttons = new Player_Inputs(null) ;

	// By the mirror's entity OBJECT, which lives as long as the entity : the snapshot types compare by value
	private final Map<Net_Snapshot.Hero, Draw_Hero> heroes = new IdentityHashMap<Net_Snapshot.Hero, Draw_Hero>() ;
	private final Map<Net_Snapshot.Monster, Draw_Monster> monsters = new IdentityHashMap<Net_Snapshot.Monster, Draw_Monster>() ;
	/** The score table, by player number, in the order the host sorts it. */
	private final TreeMap<Integer, ScoreLabel> scores = new TreeMap<Integer, ScoreLabel>() ;

	private Draw_Canoe canoe ;
	private Sprite star ;
	private Label status ;

	/** The host's story time and sky scroll the picture showed last tick. */
	private float shownStory, shownSky ;

	private final LifecycleListener leaveOnExit = new LifecycleListener()
	{
		@Override public void pause() {}
		@Override public void resume() {}

		@Override
		public void dispose()
		{closeSession() ;}
	} ;

	private final Snapshot_Mirror.Listener entities = new Snapshot_Mirror.Listener()
	{
		@Override
		public void created(Net_Snapshot.Hero hero)
		{heroes.put(hero, new Draw_Hero(model(Index_Sprite.persoModel, hero.look))) ;}

		@Override
		public void destroyed(Net_Snapshot.Hero hero)
		{heroes.remove(hero) ;}

		@Override
		public void created(Net_Snapshot.Monster monster)
		{monsters.put(monster, new Draw_Monster(model(Index_Sprite.monsterModel, monster.look))) ;}

		@Override
		public void destroyed(Net_Snapshot.Monster monster)
		{monsters.remove(monster) ;}
	} ;

	/** @param hostAddress "host:port", as Net_Transport.resolve reads it */
	public Vue_Client(String hostAddress)
	{this.hostAddress = hostAddress ;}

	@Override
	public void init()
	{
		// What a picture needs, and nothing that simulates : no Gvars_Physic, GVars_Game or GVars_Controller
		GVars_Heart.loadAssets() ;
		GVars_Parralax.init() ;
		GVars_Camera.init() ;
		GVars_Interface.init() ;
		GVars_Story.init() ;
		GVars_Parralax.setPages(Enum_ColdNight.COLD_NIGHT, Enum_ColdNight.COLD_WATER) ;

		canoe = new Draw_Canoe() ;
		star = new Sprite(new Texture("stars/Stars Small_1.png")) ;
		star.setPosition(0, GVars_Story.starStartY());

		status = new Label("", GVars_Interface.baseSkin) ;
		status.setFontScale(2);
		status.setPosition(Gdx.graphics.getWidth() * 0.03f, Gdx.graphics.getHeight() * 0.92f);
		GVars_Interface.mainInterface.addActor(status);

		Gdx.input.setInputProcessor(new InputMultiplexer(GVars_Interface.mainInterface, keyboard));
		Controllers.clearListeners();
		Controllers.addListener(pads) ;
		GVars_AudioManager.PlayAmbiance(Enum_Ambiance.WATER);

		transport = Transport_Udp.open() ;
		client = new ClientSession(transport, hostAddress, machineKey(), entities) ;
		Gdx.app.addLifecycleListener(leaveOnExit);
	}

	/**
	 * This machine's rejoin key, made once and kept in the game's preferences : a game that crashed,
	 * lost its connection or was restarted comes back to a run as the player it was (d13 -> C).
	 */
	static long machineKey()
	{
		Preferences online = Gdx.app.getPreferences("LaChasseGalerie-online") ;
		long key = online.getLong("rejoinKey", 0) ;
		if(key == 0)
		{
			key = ClientSession.newKey() ;
			online.putLong("rejoinKey", key) ;
			online.flush() ;
		}
		return key ;
	}

	@Override
	public void update(float delta)
	{
		client.tick(pressed()) ;

		Snapshot_Mirror view = client.view() ;
		float storyDelta = Math.max(0, view.storyTime - shownStory) ;
		GVars_Story.followHost(shownStory, view.storyTime, shownSky, view.skyScroll) ;
		shownStory = Math.max(shownStory, view.storyTime) ;
		shownSky = view.skyScroll ;

		star.setPosition(0, GVars_Story.starStartY() - view.skyScroll/3);
		canoe.showAngle(view.canoeAngle);

		for(Net_Snapshot.Hero hero : view.heroes())
		{
			Draw_Hero look = heroes.get(hero) ;
			look.advanceLook(storyDelta, hero.invulnerable, hero.hp) ;
			look.showState(state(hero.anim)) ;
			look.reverse(hero.reverse) ;
			look.show(hero.x, hero.y, hero.hp, hero.invulnerable, hero.axeX, hero.axeY, hero.axeAngle) ;
			look.placeAtBody() ;
		}
		for(Net_Snapshot.Monster monster : view.monsters())
		{
			Draw_Monster look = monsters.get(monster) ;
			look.update(storyDelta) ;
			look.reverse(monster.reverse) ;
			look.placeAt(monster.x, monster.y) ;
		}

		showScores(view) ;
		showStatus() ;
	}

	@Override
	public void render()
	{
		// As Vue_Game draws it, in the same order, from the snapshot instead of the lists
		Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
		Gdx.gl.glClearColor(0, 0, 0, 1);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
		GVars_Camera.viewport.apply();
		camera.update();

		staticBatch.setProjectionMatrix(camera.combined);

		GVars_Parralax.background.render();

		Snapshot_Mirror view = client.view() ;
		staticBatch.begin();
		star.draw(staticBatch);
		canoe.drawBack(staticBatch);
		for(Net_Snapshot.Hero hero : view.heroes())
			heroes.get(hero).draw(staticBatch);
		for(Net_Snapshot.Monster monster : view.monsters())
			monsters.get(monster).draw(staticBatch);
		for(Net_Snapshot.Potion potion : view.potions())
			Draw_Potion.draw(staticBatch, potion.x, potion.y, 0);
		canoe.drawFront(staticBatch);
		staticBatch.end();

		GVars_Parralax.foreground.render();

		staticBatch.begin();
		for(Net_Snapshot.Hero hero : view.heroes())
			heroes.get(hero).drawHp(staticBatch);
		staticBatch.end();

		staticBatch.setColor(Color.WHITE);

		GVars_Interface.mainInterface.getViewport().apply();
		GVars_Interface.mainInterface.draw();

		for (jks.vinterface.ToRender rende : toRender)
			rende.render();
	}

	@Override
	public void dispose()
	{
		Gdx.app.removeLifecycleListener(leaveOnExit);
		closeSession() ;
		Gdx.input.setInputProcessor(null);
		Controllers.clearListeners();

		heroes.clear() ;
		monsters.clear() ;
		scores.clear() ;
		GVars_Camera.dispose() ;
		GVars_Interface.dispose() ;
		canoe.dispose() ;
		star.getTexture().dispose() ;
	}

	/** Says LEAVE to the host, once, whether the view is replaced or the window closes. */
	private void closeSession()
	{
		if(client == null)
			return ;
		client.close() ;
		transport.close() ;
		client = null ;
	}

	// ---------------------------------------------------------------- the picture

	/** A model by the index the host sent ; a host with more models than this build loaded (--debug) wraps. */
	private static SIW_Data model(List<SIW_Data> models, int look)
	{return models.get(Math.floorMod(look, models.size())) ;}

	private static Enum_AnimState state(int ordinal)
	{
		Enum_AnimState[] states = Enum_AnimState.values() ;
		return ordinal >= 0 && ordinal < states.length ? states[ordinal] : Enum_AnimState.IDLE ;
	}

	/** The host's score table : a row per player who joined, in the colour of the hero they last had. */
	private void showScores(Snapshot_Mirror view)
	{
		List<Integer> gone = new ArrayList<Integer>(scores.keySet()) ;
		for(Net_Snapshot.Score score : view.scores())
		{
			gone.remove(Integer.valueOf(score.player)) ;
			ScoreLabel label = scores.get(score.player) ;
			if(label == null)
			{
				label = new ScoreLabel("   Player " + score.player + "   ") ;
				GVars_Interface.bottomScore.add(label) ;
				scores.put(score.player, label) ;
			}
			if(label.scoreNumber != score.score || label.deathNumber != score.deaths)
			{
				label.scoreNumber = score.score ;
				label.deathNumber = score.deaths ;
				label.update() ;
			}
		}
		for(Integer player : gone)
			scores.remove(player).remove() ;
		for(Net_Snapshot.Hero hero : view.heroes())
		{
			ScoreLabel label = scores.get(hero.player) ;
			if(label != null)
				label.setColor(heroes.get(hero).index.color) ;
		}
	}

	private void showStatus()
	{
		String text ;
		switch(client.state())
		{
			case CONNECTING :
				text = "Connecting to " + hostAddress + "..." ;
				break ;
			case IN :
				text = client.hasHeroInNewest() ? "" : "Player " + client.player() + " : press a key to join" ;
				break ;
			case ENDED :
				text = "The host ended the run (" + client.endedBecause() + ")" ;
				break ;
			default :
				text = "Lost the host" ;
				break ;
		}
		status.setText(text) ;
	}

	// ---------------------------------------------------------------- the buttons

	/** This tick's Net_Input bits : what is held, and the presses since the last tick, which are then spent. */
	private int pressed()
	{
		int bits = 0 ;
		if(buttons.leftPressed)
			bits |= Net_Input.LEFT ;
		if(buttons.rightPressed)
			bits |= Net_Input.RIGHT ;
		if(buttons.jumpPressed)
			bits |= Net_Input.JUMP ;
		if(buttons.powerLeft)
			bits |= Net_Input.SWING_LEFT ;
		if(buttons.powerRight)
			bits |= Net_Input.SWING_RIGHT ;
		buttons.jumpPressed = false ;
		buttons.powerLeft = false ;
		buttons.powerRight = false ;
		return bits ;
	}

	/** With no hero in the snapshots, a touch asks for one and does nothing else, as offline. */
	private boolean joinedOrAsked()
	{
		if(client != null && client.hasHeroInNewest())
			return true ;
		if(client != null)
			client.join() ;
		return false ;
	}

	/** IKM_Game_Keyboard's keys, onto this machine's one player. */
	private final InputAdapter keyboard = new InputAdapter()
	{
		@Override
		public boolean keyDown(int keycode)
		{
			if(keycode == Keys.ESCAPE)
			{
				Gdx.app.exit();
				return true ;
			}
			if(!joinedOrAsked())
				return false ;

			switch (keycode)
			{
				case Keys.SPACE :
				case Keys.UP :
					buttons.jumpPressed = true ;
					return true ;
				case Keys.D :
					buttons.powerLeft = true ;
					return true ;
				case Keys.Q :
					buttons.powerRight = true ;
					return true ;
				case Keys.LEFT :
					buttons.leftPressed = true ;
					buttons.rightPressed = false ;
					return true ;
				case Keys.RIGHT :
					buttons.rightPressed = true ;
					buttons.leftPressed = false ;
					return true ;
			}
			return false ;
		}

		@Override
		public boolean keyUp(int keycode)
		{
			switch (keycode)
			{
				case Keys.LEFT :
					buttons.leftPressed = false ;
					return true ;
				case Keys.RIGHT :
					buttons.rightPressed = false ;
					return true ;
			}
			return false;
		}
	} ;

	/** IKM_Game_XBoxController's buttons, onto the same player : a pad can be handed across the couch. */
	private final ControllerAdapter pads = new ControllerAdapter()
	{
		@Override
		public boolean buttonDown(Controller controller, int buttonCode)
		{
			if(!joinedOrAsked())
				return false ;

			ControllerMapping mapping = controller.getMapping() ;
			if(buttonCode == mapping.buttonA)
				buttons.jumpPressed = true ;
			else if(buttonCode == mapping.buttonB)
				buttons.powerLeft = true ;
			else if(buttonCode == mapping.buttonX)
				buttons.powerRight = true ;
			else
				return false ;
			return true ;
		}

		@Override
		public boolean axisMoved(Controller controller, int axisCode, float value)
		{
			return Utils_Controller.axisController(controller.getMapping(), axisCode, value, buttons) ;
		}
	} ;
}
