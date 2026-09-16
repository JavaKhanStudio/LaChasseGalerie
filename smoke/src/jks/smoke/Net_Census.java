package jks.smoke;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.badlogic.gdx.controllers.Controller;

import jks.headless.Headless_Runner;
import jks.input.GVars_Controller;
import jks.input.Player_Inputs;
import jks.net.Net_Codec;
import jks.net.Net_Snapshot;
import jks.net.Net_Transport;
import jks.online.Snapshot_View;
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
 * The snapshot is the one a host sends : Snapshot_View's (phase 1.3, r38). Potions that fall off the
 * world are removed from the run (n7) — otherwise they would pile up without limit and no layout fits.
 */
public class Net_Census implements Headless_Runner.Session
{
	final int players, seconds;
	final long seed;

	int biggest, biggestFrame, peakHeroes, peakMonsters, peakPotions;
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
				+ " monsters=" + peakMonsters + " potions=" + peakPotions);
		System.out.println("CENSUS biggest snapshot " + biggest + " B of " + Net_Transport.MAX_PAYLOAD + " at t=" + biggestFrame / 60 + "s : " + biggestCounts);
	}

	void measure(int frame)
	{
		Net_Snapshot snapshot = Snapshot_View.read(frame);
		// Throws past the cap, which fails the run : that is the gate
		int size = Net_Codec.encode(snapshot).remaining();

		peakHeroes = Math.max(peakHeroes, snapshot.heroes.size());
		peakMonsters = Math.max(peakMonsters, snapshot.monsters.size());
		peakPotions = Math.max(peakPotions, snapshot.potions.size());
		if (size > biggest)
		{
			biggest = size;
			biggestFrame = frame;
			biggestCounts = "players=" + snapshot.scores.size() + " heroes=" + snapshot.heroes.size() + " monsters=" + snapshot.monsters.size() + " potions=" + snapshot.potions.size();
		}
	}
}
