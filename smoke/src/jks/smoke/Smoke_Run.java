package jks.smoke;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.utils.Array;

import jks.headless.Headless_Runner;
import jks.input.GVars_Controller;
import jks.input.Player_Inputs;
import jks.personnage.PhysicSpriteEnnemy;
import jks.personnage.PhysicSpriteHeroes;
import jks.personnage.ScoreLabel;
import jks.physic.Gvars_Physic;
import jks.vars.GVars_Game;
import jks.vars.GVars_Random;

/**
 * Plays the real game loop headless through Headless_Runner, for a minute of game time. A keyboard player and two fake gamepads join,
 * move, jump and swing at random, rejoin once a second after dying, and are killed on purpose
 * at fixed times (by hearts, by the river, all at once).
 *
 * A native Box2D crash does not reliably happen when the rules are broken, so every frame checks
 * the invariants that prevent one instead. The first broken one exits 1.
 *
 * Static game state is never reset, so this is one run per JVM.
 */
public class Smoke_Run implements Headless_Runner.Session
{
	static final int WIDTH = 1280, HEIGHT = 720;

	final long seed;
	final int seconds;
	final Random random;

	final List<Controller> pads = List.of(fakeController("pad1"), fakeController("pad2"));
	int frame, joins, forcedDeaths, monstersKilled, mostMonsters;

	public static void main(String[] args)
	{
		long seed = args.length > 0 ? Long.parseLong(args[0]) : 1;
		int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 60;
		Headless_Runner.launch(WIDTH, HEIGHT, new Smoke_Run(seed, seconds));
	}

	Smoke_Run(long seed, int seconds)
	{
		this.seed = seed;
		this.seconds = seconds;
		this.random = new Random(seed);
	}

	@Override
	public void failed(Throwable t)
	{
		System.out.println("SMOKE FAILED at frame " + frame + " (t=" + frame / 60f + "s, seed " + seed + ")");
		t.printStackTrace(System.out);
	}

	@Override
	public void run(Headless_Runner runner) throws Exception
	{
		GVars_Random.seed(seed);

		long start = System.currentTimeMillis();
		runner.boot();
		System.out.println("SMOKE seed " + seed + ", " + seconds + "s of game time, init in " + (System.currentTimeMillis() - start) + " ms");

		Set<PhysicSpriteEnnemy> monstersBefore = Collections.newSetFromMap(new IdentityHashMap<>());
		for (frame = 0; frame < seconds * 60; frame++)
		{
			if (frame % 60 == 0)
				joinEveryone();
			if (frame % 20 == 0)
				pressButtons();
			forceDeaths();

			checkDestroyQueue();
			monstersBefore.clear();
			monstersBefore.addAll(GVars_Game.ennemies);

			runner.step();

			monstersBefore.removeAll(GVars_Game.ennemies);
			monstersKilled += monstersBefore.size();
			mostMonsters = Math.max(mostMonsters, GVars_Game.ennemies.size());
			checkInvariants();

			if (frame % 600 == 0)
				report("t=" + frame / 60 + "s");
		}

		report("done in " + (System.currentTimeMillis() - start) + " ms:");
		// A run that spawned nothing or killed nobody proves nothing
		if (seconds >= 30 && mostMonsters == 0)
			throw new IllegalStateException("no monster ever spawned");
		if (deaths() < forcedDeaths)
			throw new IllegalStateException(forcedDeaths + " deaths forced but only " + deaths() + " counted");
	}

	/** Joins the way the input edge does: the keyboard (null) first, then each pad, by device. */
	void joinEveryone()
	{
		List<Controller> devices = new ArrayList<>(pads);
		devices.add(0, null);
		for (Controller device : devices)
		{
			if (GVars_Controller.getLocalPlayer(device) == null)
			{
				GVars_Game.addPlayer(GVars_Controller.identify(device));
				joins++;
			}
		}
	}

	void pressButtons()
	{
		// playerList is sorted by PlayerId, so walking it replays from a seed as it is
		for (Player_Inputs player : GVars_Controller.playerList.values())
		{
			int move = random.nextInt(3);
			player.leftPressed = move == 0;
			player.rightPressed = move == 1;
			player.jumpPressed = random.nextInt(3) == 0;
			player.powerLeft = random.nextInt(3) == 0;
			player.powerRight = random.nextInt(3) == 0;
		}
	}

	/** Monsters from 15s, take-off 37s to 45s: one death of each kind lands in a different phase. */
	void forceDeaths()
	{
		if (GVars_Game.heroes.isEmpty())
			return;

		if (frame == 20 * 60)
		{
			loseEveryHeart(GVars_Game.heroes.get(0));
		}
		else if (frame == 40 * 60)
		{
			PhysicSpriteHeroes hero = GVars_Game.heroes.get(GVars_Game.heroes.size() - 1);
			hero.body.setTransform(hero.body.getPosition().x, -1, 0);
			forcedDeaths++;
		}
		else if (frame == 50 * 60)
		{
			for (PhysicSpriteHeroes hero : new ArrayList<>(GVars_Game.heroes))
				loseEveryHeart(hero);
		}
	}

	void loseEveryHeart(PhysicSpriteHeroes hero)
	{
		while (hero.hp_left > 0)
		{
			hero.invulnerable = false;
			hero.getHurt(hero);
		}
		forcedDeaths++;
	}

	/** Before cleanUp runs: destroying a body the world no longer holds is the double-destroy crash. */
	void checkDestroyQueue()
	{
		Array<Body> bodies = new Array<>();
		Gvars_Physic.world.getBodies(bodies);
		for (Body queued : GVars_Game.toBeDestroy_Body)
			if (!bodies.contains(queued, true))
				throw new IllegalStateException("a body queued for destruction is already gone from the world");
	}

	void checkInvariants()
	{
		for (PhysicSpriteEnnemy monster : GVars_Game.ennemies)
		{
			if (monster.target != null && !GVars_Game.heroes.contains(monster.target))
				throw new IllegalStateException("a monster chases a hero who was removed");
			if (!Float.isFinite(monster.body.getPosition().x) || !Float.isFinite(monster.body.getPosition().y))
				throw new IllegalStateException("a monster position is not finite");
		}

		for (PhysicSpriteHeroes hero : GVars_Game.heroes)
		{
			if (hero.hp_left < 0 || hero.hp_left > hero.hp_max)
				throw new IllegalStateException("a hero has " + hero.hp_left + " hearts");
			if (!Float.isFinite(hero.body.getPosition().x) || !Float.isFinite(hero.body.getPosition().y))
				throw new IllegalStateException("a hero position is not finite");
		}

		// Canoe, a body and an axe per hero, monsters, potions, and whatever waits in the queue
		int expectedBodies = 1 + GVars_Game.heroes.size() * 2 + GVars_Game.ennemies.size() + GVars_Game.hpStack.size() + GVars_Game.toBeDestroy_Body.size();
		if (Gvars_Physic.world.getBodyCount() != expectedBodies)
			throw new IllegalStateException("world holds " + Gvars_Physic.world.getBodyCount() + " bodies, the game tracks " + expectedBodies);

		int expectedJoints = GVars_Game.heroes.size() + GVars_Game.toBeDestroy_Jointure.size();
		if (Gvars_Physic.world.getJointCount() != expectedJoints)
			throw new IllegalStateException("world holds " + Gvars_Physic.world.getJointCount() + " joints, the game tracks " + expectedJoints);

		// A dying hero keeps its player until cleanUp kills it, at the start of the next update
		if (GVars_Controller.playerList.size() != GVars_Game.heroes.size())
			throw new IllegalStateException(GVars_Controller.playerList.size() + " players for " + GVars_Game.heroes.size() + " heroes");
		for (PhysicSpriteHeroes hero : GVars_Game.heroes)
			if (!GVars_Controller.playerList.containsKey(hero.player))
				throw new IllegalStateException(hero.player + " has a hero but no inputs");
			else if (GVars_Game.playerRegister.get(hero.player) != hero.score)
				throw new IllegalStateException(hero.player + " writes into someone else's score label");

		// One label per player ever joined, and three devices never make a fourth player
		if (GVars_Game.playerRegister.size() > devices())
			throw new IllegalStateException(GVars_Game.playerRegister.size() + " score labels for " + devices() + " devices");
	}

	int devices()
	{return pads.size() + 1;}

	int deaths()
	{
		int deaths = 0;
		for (ScoreLabel label : GVars_Game.playerRegister.values())
			deaths += label.deathNumber;
		return deaths;
	}

	void report(String when)
	{
		System.out.println("SMOKE " + when
				+ " heroes=" + GVars_Game.heroes.size()
				+ " monsters=" + GVars_Game.ennemies.size()
				+ " potions=" + GVars_Game.hpStack.size()
				+ " bodies=" + Gvars_Physic.world.getBodyCount()
				+ " joins=" + joins
				+ " deaths=" + deaths() + " (" + forcedDeaths + " forced)"
				+ " monstersKilled=" + monstersKilled);
	}

	static Controller fakeController(String name)
	{
		return (Controller) Proxy.newProxyInstance(Smoke_Run.class.getClassLoader(), new Class<?>[] { Controller.class }, (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				case "toString": case "getName": return name;
				default: break;
			}
			// No button held, no stick moved
			Class<?> type = method.getReturnType();
			return type == int.class ? 0 : type == boolean.class ? false : type == float.class ? 0f : null;
		});
	}
}
