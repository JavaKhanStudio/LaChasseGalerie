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

import jks.camera.GVars_Camera;
import jks.headless.Headless_Runner;
import jks.input.GVars_Controller;
import jks.input.Player_Inputs;
import jks.personnage.PhysicSpriteEnnemy;
import jks.personnage.PhysicSpriteHeroes;
import jks.personnage.ScoreLabel;
import jks.personnage.index.Index_Sprite;
import jks.physic.Gvars_Physic;
import jks.vars.GVars_Game;
import jks.vars.GVars_Heart;
import jks.story.GVars_Story;
import jks.vars.GVars_Random;
import jks.vinterface.GVars_Interface;
import jks.vue.models.Vue_Menu;

/**
 * Plays the real game loop headless through Headless_Runner, for a minute of game time. A keyboard player and two fake gamepads join,
 * move, jump and swing at random, rejoin once a second after dying, and are killed on purpose
 * at fixed times (by hearts, by the river, all at once).
 *
 * A native Box2D crash does not reliably happen when the rules are broken, so every frame checks
 * the invariants that prevent one instead. The first broken one exits 1.
 *
 * Then it goes back to the start menu and plays the same seed again, in the same JVM (phase 0.4). A
 * run that leaves anything behind — a timer, a colour, a player number, a body — plays differently,
 * so every run must print the same report as the first.
 */
public class Smoke_Run implements Headless_Runner.Session
{
	static final int WIDTH = 1280, HEIGHT = 720;

	final long seed;
	final int seconds, runs;
	Random random;

	final List<Controller> pads = List.of(fakeController("pad1"), fakeController("pad2"));
	int frame, joins, forcedDeaths, monstersKilled, mostMonsters;

	public static void main(String[] args)
	{
		long seed = args.length > 0 ? Long.parseLong(args[0]) : 1;
		int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 60;
		int runs = args.length > 2 ? Integer.parseInt(args[2]) : 2;
		Headless_Runner.launch(WIDTH, HEIGHT, new Smoke_Run(seed, seconds, runs));
	}

	Smoke_Run(long seed, int seconds, int runs)
	{
		this.seed = seed;
		this.seconds = seconds;
		this.runs = runs;
	}

	/** What a run printed, without the wall-clock times : two runs of one seed must print the same. */
	List<String> reports = new ArrayList<>();
	int run;

	@Override
	public void failed(Throwable t)
	{
		System.out.println("SMOKE FAILED at frame " + frame + " (t=" + frame / 60f + "s, seed " + seed + ")");
		t.printStackTrace(System.out);
	}

	@Override
	public void run(Headless_Runner runner) throws Exception
	{
		List<String> first = null;
		for (run = 1; run <= runs; run++)
		{
			if (run > 1)
				backToTheMenu(runner);

			reports = new ArrayList<>();
			playOnce(runner);

			if (first == null)
				first = reports;
			else if (!first.equals(reports))
				throw new IllegalStateException("run " + run + " played differently from run 1:\n  " + String.join("\n  ", first) + "\nagainst\n  " + String.join("\n  ", reports));
		}
		if (runs > 1)
			System.out.println("SMOKE " + runs + " runs in one JVM, every report the same");
	}

	/** The way a player would leave: the run ends into the start menu, which idles, then Local play again. */
	void backToTheMenu(Headless_Runner runner)
	{
		GVars_Heart.changeVue(new Vue_Menu());
		checkTornDown();
		for (int i = 0; i < 60; i++)
			runner.step();
	}

	/** Nothing of the last run may survive into the menu: a Body kept past its world is a native crash. */
	void checkTornDown()
	{
		if (Gvars_Physic.world != null)
			throw new IllegalStateException("the world outlived its run");
		if (GVars_Game.heroes != null || GVars_Game.ennemies != null || GVars_Game.hpStack != null || GVars_Game.toBeDestroy_Body != null || GVars_Game.canoe != null)
			throw new IllegalStateException("the run's lists still point into a disposed world");
		if (GVars_Controller.playerList != null)
			throw new IllegalStateException("players outlived their run");
		if (GVars_Interface.mainInterface != null || GVars_Camera.staticBatch != null)
			throw new IllegalStateException("the run's HUD or batch was not disposed");
	}

	void playOnce(Headless_Runner runner) throws Exception
	{
		frame = joins = forcedDeaths = monstersKilled = mostMonsters = 0;
		random = new Random(seed);
		GVars_Random.seed(seed);

		long start = System.currentTimeMillis();
		runner.boot();
		System.out.println("SMOKE run " + run + ", seed " + seed + ", " + seconds + "s of game time, init in " + (System.currentTimeMillis() - start) + " ms");

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

			if (GVars_Story.runOver())
			{
				// The song is over (d12): this step hands the window back to the menu, and the run is gone
				checkLanded();
				report("t=" + frame / 60 + "s ended:");
				checkItProvedSomething();
				runner.step();
				if (!(GVars_Heart.vue instanceof Vue_Menu))
					throw new IllegalStateException("the run is over but did not go back to the menu");
				checkTornDown();
				return;
			}

			runner.step();

			monstersBefore.removeAll(GVars_Game.ennemies);
			monstersKilled += monstersBefore.size();
			mostMonsters = Math.max(mostMonsters, GVars_Game.ennemies.size());
			checkInvariants();

			if (frame % 600 == 0)
				report("t=" + frame / 60 + "s");
		}

		report("done in " + (System.currentTimeMillis() - start) + " ms:");
		checkItProvedSomething();
	}

	/** A run that spawned nothing or killed nobody proves nothing. */
	void checkItProvedSomething()
	{
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

	/** The descent (d12) put everything back where the run started: the sky, the canoe's nose, the river. */
	void checkLanded()
	{
		if (!GVars_Story.hasLanded())
			throw new IllegalStateException("the run ended before the canoe landed");
		if (GVars_Story.skyScroll() != 0)
			throw new IllegalStateException("landed with the sky still scrolled by " + GVars_Story.skyScroll());
		if (GVars_Game.canoe.body.getAngle() != 0)
			throw new IllegalStateException("landed with the canoe tilted by " + GVars_Game.canoe.body.getAngle());
		if (GVars_Camera.screenMovementSpeed != GVars_Story.riverSpeedAt(GVars_Story.storyTime()))
			throw new IllegalStateException("landed with the river at " + GVars_Camera.screenMovementSpeed);
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
		String counts = " heroes=" + GVars_Game.heroes.size()
				+ " monsters=" + GVars_Game.ennemies.size()
				+ " potions=" + GVars_Game.hpStack.size()
				+ " bodies=" + Gvars_Physic.world.getBodyCount()
				+ " joins=" + joins
				+ " deaths=" + deaths() + " (" + forcedDeaths + " forced)"
				+ " monstersKilled=" + monstersKilled;
		reports.add(frame + counts + " playing=" + whoPlays());
		System.out.println("SMOKE " + when + counts);
	}

	/** Player numbers and the colour each wears: what a run gets wrong when it inherits the last one's roster. */
	String whoPlays()
	{
		StringBuilder who = new StringBuilder();
		for (PhysicSpriteHeroes hero : GVars_Game.heroes)
			who.append(" P").append(hero.player.number()).append(':').append(Index_Sprite.persoModel.indexOf(hero.index));
		return who.toString();
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
