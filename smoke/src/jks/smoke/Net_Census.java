package jks.smoke;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.physics.box2d.Body;

import jks.headless.Headless_Runner;
import jks.input.GVars_Controller;
import jks.input.PlayerId;
import jks.input.Player_Inputs;
import jks.net.Net_Codec;
import jks.net.Net_Snapshot;
import jks.net.Net_Transport;
import jks.personnage.PhysicSpriteEnnemy;
import jks.personnage.PhysicSpriteHeroes;
import jks.personnage.ScoreLabel;
import jks.personnage.index.Index_Sprite;
import jks.physic.objects.PhysicSpriteHp;
import jks.vars.GVars_Game;
import jks.vars.GVars_Random;

/**
 * `./gradlew netcensus` : how big a snapshot the real game makes, measured rather than estimated
 * (docs/online-multiplayer.md risk 4 — monster spawns scale with heroes.size()).
 *
 * Plays the headless loop with N players (default 8, the busiest session under d5 -> A) who press
 * at random and rejoin every second, so the population stays up. Every tick it builds the snapshot
 * a host would send and encodes it with the real codec : a snapshot past the payload cap fails the
 * run, and the biggest one is reported with its counts. Net_Run's measured-peak check carries those
 * counts, so the game-free nettest gate holds the layout to them.
 *
 * The snapshot here is a stand-in for phase 1.3's view (r38) : ids in order of first sight, and no
 * facing, which the models keep protected. It sizes the packet, it does not define what goes in it.
 * It already leaves out potions that fell off the world, which is n7 : otherwise they pile up
 * without limit and no layout fits.
 */
public class Net_Census implements Headless_Runner.Session
{
	final int players, seconds;
	final long seed;

	final Map<Object, Integer> ids = new IdentityHashMap<Object, Integer>();
	int biggest, biggestFrame, peakHeroes, peakMonsters, peakPotions, peakFallen;
	String biggestCounts = "";

	public static void main(String[] args)
	{
		int players = args.length > 0 ? Integer.parseInt(args[0]) : 8;
		int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 120;
		long seed = args.length > 2 ? Long.parseLong(args[2]) : 1;
		Headless_Runner.launch(1280, 720, new Net_Census(players, seconds, seed));
	}

	Net_Census(int players, int seconds, long seed)
	{
		this.players = players;
		this.seconds = seconds;
		this.seed = seed;
	}

	@Override
	public void run(Headless_Runner runner)
	{
		GVars_Random.seed(seed);
		Random random = new Random(seed);
		runner.boot();

		List<Controller> devices = new ArrayList<Controller>();
		devices.add(null);
		for (int i = 1; i < players; i++)
			devices.add(Smoke_Run.fakeController("pad" + i));

		for (int frame = 0; frame < seconds * 60; frame++)
		{
			if (frame % 60 == 0)
				for (Controller device : devices)
					if (GVars_Controller.getLocalPlayer(device) == null)
						GVars_Game.addPlayer(GVars_Controller.identify(device));
			if (frame % 20 == 0)
				for (Player_Inputs player : GVars_Controller.playerList.values())
				{
					int move = random.nextInt(3);
					player.leftPressed = move == 0;
					player.rightPressed = move == 1;
					player.jumpPressed = random.nextInt(3) == 0;
					player.powerLeft = random.nextInt(3) == 0;
					player.powerRight = random.nextInt(3) == 0;
				}

			runner.step();
			measure(frame);
		}

		System.out.println("CENSUS " + players + " players, seed " + seed + ", " + seconds + "s : peak heroes=" + peakHeroes
				+ " monsters=" + peakMonsters + " potions=" + peakPotions + " (and " + peakFallen + " fallen off the world, not sent)");
		System.out.println("CENSUS biggest snapshot " + biggest + " B of " + Net_Transport.MAX_PAYLOAD + " at t=" + biggestFrame / 60 + "s : " + biggestCounts);
	}

	void measure(int frame)
	{
		Net_Snapshot snapshot = snapshot(frame);
		// Throws past the cap, which fails the run : that is the gate
		int size = Net_Codec.encode(snapshot).remaining();

		peakHeroes = Math.max(peakHeroes, snapshot.heroes.size());
		peakMonsters = Math.max(peakMonsters, snapshot.monsters.size());
		peakPotions = Math.max(peakPotions, snapshot.potions.size());
		peakFallen = Math.max(peakFallen, GVars_Game.hpStack.size() - snapshot.potions.size());
		if (size > biggest)
		{
			biggest = size;
			biggestFrame = frame;
			biggestCounts = "players=" + snapshot.scores.size() + " heroes=" + snapshot.heroes.size() + " monsters=" + snapshot.monsters.size() + " potions=" + snapshot.potions.size();
		}
	}

	Net_Snapshot snapshot(int frame)
	{
		Net_Snapshot snapshot = new Net_Snapshot();
		snapshot.tick = frame;
		snapshot.canoeAngle = GVars_Game.canoe.body.getAngle();

		for (Entry<PlayerId, ScoreLabel> entry : GVars_Game.playerRegister.entrySet())
			snapshot.scores.add(new Net_Snapshot.Score(entry.getKey().number(), entry.getValue().scoreNumber, entry.getValue().deathNumber));

		for (PhysicSpriteHeroes model : GVars_Game.heroes)
		{
			Net_Snapshot.Hero hero = new Net_Snapshot.Hero();
			hero.id = id(model);
			hero.player = model.player.number();
			hero.look = Index_Sprite.persoModel.indexOf(model.index);
			hero.x = model.body.getPosition().x;
			hero.y = model.body.getPosition().y;
			hero.vx = model.body.getLinearVelocity().x;
			hero.vy = model.body.getLinearVelocity().y;
			hero.hp = model.hp_left;
			hero.anim = model.currentAnimState == null ? 0 : model.currentAnimState.ordinal();
			hero.invulnerable = model.invulnerable;
			Body axe = model.axe.bodyAxe;
			hero.axeX = axe.getPosition().x;
			hero.axeY = axe.getPosition().y;
			hero.axeAngle = axe.getAngle();
			snapshot.heroes.add(hero);
		}
		for (PhysicSpriteEnnemy model : GVars_Game.ennemies)
		{
			Net_Snapshot.Monster monster = new Net_Snapshot.Monster();
			monster.id = id(model);
			monster.look = Index_Sprite.monsterModel.indexOf(model.index);
			monster.x = model.body.getPosition().x;
			monster.y = model.body.getPosition().y;
			snapshot.monsters.add(monster);
		}
		for (PhysicSpriteHp model : GVars_Game.hpStack)
		{
			if (model.body.getPosition().y < 0)
				continue;
			Net_Snapshot.Potion potion = new Net_Snapshot.Potion();
			potion.id = id(model);
			potion.x = model.body.getPosition().x;
			potion.y = model.body.getPosition().y;
			snapshot.potions.add(potion);
		}
		return snapshot;
	}

	int id(Object entity)
	{
		Integer id = ids.get(entity);
		if (id == null)
		{
			id = Integer.valueOf(ids.size() % 0x10000);
			ids.put(entity, id);
		}
		return id.intValue();
	}
}
