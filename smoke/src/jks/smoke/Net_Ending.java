package jks.smoke;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import jks.headless.Headless_Runner;
import jks.input.GVars_Controller;
import jks.input.Menu_Picker;
import jks.input.PlayerId;
import jks.net.Net_Input;
import jks.net.Net_Loopback;
import jks.net.Net_Message;
import jks.net.Net_Snapshot;
import jks.online.ClientSession;
import jks.online.Game_Simulation;
import jks.online.HostSession;
import jks.online.Snapshot_View;
import jks.story.GVars_Story;
import jks.vars.GVars_Game;
import jks.vars.GVars_Heart;
import jks.vars.GVars_Random;
import jks.vinterface.Menu_Focus;
import jks.vue.models.Vue_Game;
import jks.vue.models.Vue_Menu;

/**
 * `./gradlew netending` : what a hosted run does when its song is over (d14). The real game headless
 * behind a HostSession, two world-less clients over the lossy in-memory wire, and the host's score
 * screen driven through its own focus ring, the way a keyboard or a pad drives it :
 * <ol>
 * <li>the song ends : the host stops the world, every client is sent the score screen with the host's
 *     final table and no entity, a JOIN gets nobody a hero, and nobody times out while the host waits
 *     longer than the 10 s a silent peer is given ;</li>
 * <li>New run, picked by the keyboard : a fresh run under the same session, the clients see the run
 *     number change and keep their players, the keyboard that picked is in it (d9) under a number no
 *     client holds, and the clients join it ;</li>
 * <li>its song ends too, and Close the server : the session is closed while the run still exists,
 *     both clients hear HOST_ENDED, and the host's window is back on the menu.</li>
 * </ol>
 * The long middle of each run is played without the wire (its clock is virtual and stands still) :
 * the ending is what is under test, not the 340 s before it. The first disagreement exits 1.
 */
public class Net_Ending implements Headless_Runner.Session
{
	final long seed;
	final Random random;
	int frame;

	Headless_Runner runner;
	Net_Loopback wire;
	HostSession host;
	boolean hostOpen = true;
	final List<ClientSession> clients = new ArrayList<ClientSession>();
	final List<Net_Message.Leave.Reason> left = new ArrayList<Net_Message.Leave.Reason>();
	final int[] buttons = new int[2];

	public static void main(String[] args)
	{
		long seed = args.length > 0 ? Long.parseLong(args[0]) : 1;
		Headless_Runner.launch(1280, 720, new Net_Ending(seed));
	}

	Net_Ending(long seed)
	{
		this.seed = seed;
		this.random = new Random(seed);
	}

	@Override
	public void failed(Throwable t)
	{
		System.out.println("ENDING FAILED at frame " + frame + " (story " + GVars_Story.storyTime() + "s, seed " + seed + ")");
		t.printStackTrace(System.out);
	}

	@Override
	public void run(Headless_Runner runner) throws Exception
	{
		this.runner = runner;
		GVars_Random.seed(seed);
		runner.boot();

		wire = new Net_Loopback(seed);
		wire.latencyMs = 40;
		wire.jitterMs = 20;
		wire.loss = 0.03f;
		wire.duplicate = 0.01f;
		wire.reorder = 0.02f;

		host = new HostSession(wire.open("host"), new Game_Simulation(), new HostSession.Events()
		{
			@Override
			public void left(HostSession.Seat seat, Net_Message.Leave.Reason reason)
			{
				left.add(reason);
			}
		});
		is(GVars_Heart.hosting, "a Game_Simulation behind a HostSession did not mark the game hosted");
		clients.add(new ClientSession(wire.open("a"), "host"));
		clients.add(new ClientSession(wire.open("b"), "host"));

		// ---------------------------------------------------------------- 1. the song ends
		play(10, true);
		for (ClientSession client : clients)
			is(client.state() == ClientSession.State.IN, "a client never got in : " + client.state());
		int playerA = clients.get(0).player(), playerB = clients.get(1).player();
		Vue_Game firstRun = (Vue_Game) GVars_Heart.vue;
		int firstRunNumber = clients.get(0).newest().run;

		toTheEnd(playerB);
		Net_Snapshot finalTable = Snapshot_View.read(host.tick());
		is(finalTable.over, "the host's snapshot does not say the run is over");
		is(finalTable.heroes.isEmpty() && finalTable.monsters.isEmpty() && finalTable.potions.isEmpty(), "an over snapshot still carries entities : " + finalTable);
		is(finalTable.scores.size() >= 2, "the final table lost the players : " + finalTable.scores);
		float storyAtEnd = GVars_Story.storyTime();
		int heroesAtEnd = GVars_Game.heroes.size();

		// Longer than a peer may be silent : the host must keep its clients while it waits
		play(12, true);
		is(GVars_Heart.vue == firstRun, "the host left its run before anyone picked : " + GVars_Heart.vue);
		is(firstRun.scoreChoices() != null, "the host shows no score screen");
		eq(storyAtEnd, GVars_Story.storyTime(), "the story went on past the score screen");
		eq(heroesAtEnd, GVars_Game.heroes.size(), "a hero joined a run that is over");
		for (ClientSession client : clients)
		{
			is(client.state() == ClientSession.State.IN, "a client was dropped while the host waited : " + client.state() + " " + client.endedBecause());
			Net_Snapshot newest = client.newest();
			is(newest.over, "a client was not sent the score screen");
			eq(finalTable.scores, newest.scores, "the table a client was sent");
			is(client.view().over && client.view().heroes().isEmpty() && client.view().monsters().isEmpty(), "a client still draws the run under its score screen");
			is(!client.hasHeroInNewest(), "a client got a hero after the song ended");
		}

		// ---------------------------------------------------------------- 2. New run, by the keyboard
		Menu_Focus choices = firstRun.scoreChoices();
		choices.pick(Menu_Picker.KEYBOARD);
		play(3, false);
		is(GVars_Heart.vue instanceof Vue_Game && GVars_Heart.vue != firstRun, "New run did not start a run : " + GVars_Heart.vue);
		Vue_Game secondRun = (Vue_Game) GVars_Heart.vue;
		is(secondRun.scoreChoices() == null, "the new run opened on the score screen");
		is(GVars_Story.storyTime() < 4, "the new run did not start from its first second : " + GVars_Story.storyTime());
		eq(2, host.seats().size(), "seats after the new run");
		PlayerId keyboard = GVars_Controller.identify(null);
		is(GVars_Controller.getPlayer(keyboard) != null, "the keyboard that picked New run is not in it (d9)");
		is(keyboard.number() != playerA && keyboard.number() != playerB, "the host's keyboard took a remote player's number " + keyboard);
		for (ClientSession client : clients)
		{
			is(client.state() == ClientSession.State.IN, "a client was dropped by the new run : " + client.state());
			is(client.newest().run != firstRunNumber && !client.newest().over, "a client was not sent the new run : " + client.newest().run + (client.newest().over ? " over" : ""));
		}
		eq(playerA, clients.get(0).player(), "client a's player across runs");
		eq(playerB, clients.get(1).player(), "client b's player across runs");

		play(5, true);
		for (ClientSession client : clients)
			is(GVars_Game.playerRegister.containsKey(PlayerId.of(client.player())), "a client could not join the new run");

		// ---------------------------------------------------------------- 3. Close the server
		toTheEnd(0);
		play(2, true);
		choices = secondRun.scoreChoices();
		is(choices != null, "the second run shows no score screen");
		choices.move(1);
		choices.pick(Menu_Picker.POINTER);
		play(2, false);
		is(!hostOpen, "Close the server did not close the session");
		is(!GVars_Heart.hosting, "the game still thinks it is hosted");
		is(GVars_Heart.vue instanceof Vue_Menu, "the host's window did not go back to the menu : " + GVars_Heart.vue);
		for (ClientSession client : clients)
			eq(Net_Message.Leave.Reason.HOST_ENDED, client.endedBecause(), "why a client's session ended (" + client.state() + ")");
		eq(List.of(Net_Message.Leave.Reason.HOST_ENDED, Net_Message.Leave.Reason.HOST_ENDED), left, "who left the host, and why");

		System.out.println("ENDING ok : seed " + seed + ", a headless host and 2 clients over 40+-20 ms, 3% loss : the song ended at "
				+ storyAtEnd + "s on a score screen of " + finalTable.scores + ", the clients waited 12 s, a new run kept players "
				+ playerA + " and " + playerB + " and seated the keyboard as " + keyboard + ", and Close the server sent both HOST_ENDED and the host to the menu");
	}

	/**
	 * The run's long middle without the wire, then the descent and the landing with it, to the score screen.
	 * @param heroless a player whose hero is taken out before the descent and who does not rejoin before
	 *        the end, so the score screen has someone asking to join it (0 : nobody)
	 */
	void toTheEnd(int heroless)
	{
		while (GVars_Story.storyTime() < 335)
			runner.step();
		for (var hero : GVars_Game.heroes)
			if (hero.player.number() == heroless)
				GVars_Game.toDie.add(hero);
		for (int guard = 0; !GVars_Story.runOver(); guard++)
		{
			is(guard < 60 * 30, "the song never ended : story " + GVars_Story.storyTime());
			tick(heroless == 0);
		}
		is(heroless == 0 || GVars_Controller.getPlayer(PlayerId.of(heroless)) == null, "player " + heroless + " still has a hero at the end");
		// The update that finds the run over opens the score screen
		tick(heroless == 0);
	}

	void play(int seconds, boolean joining)
	{
		for (int i = 0; i < seconds * 60; i++)
			tick(joining);
	}

	/** One frame as Main_Game runs it for a host, and every client's. */
	void tick(boolean joining)
	{
		wire.advance((frame + 1) * 1000L / 60 - frame * 1000L / 60);
		frame++;

		if (hostOpen && !GVars_Heart.hosting)
		{
			host.close();
			hostOpen = false;
		}
		if (hostOpen && GVars_Heart.vue instanceof Vue_Game)
			host.tick(runner::step);
		else
			runner.step();

		for (int i = 0; i < clients.size(); i++)
		{
			ClientSession client = clients.get(i);
			if (frame % 20 == 0)
				buttons[i] = new int[] { 0, Net_Input.LEFT, Net_Input.RIGHT }[random.nextInt(3)];
			int pressed = random.nextInt(12) == 0 ? new int[] { Net_Input.JUMP, Net_Input.SWING_LEFT, Net_Input.SWING_RIGHT }[random.nextInt(3)] : 0;
			if (joining && !client.hasHeroInNewest())
				client.join();
			client.tick(buttons[i] | pressed);
		}
	}

	static void is(boolean condition, String what)
	{
		if (!condition)
			throw new IllegalStateException(what);
	}

	static void eq(Object expected, Object actual, String what)
	{
		if (!expected.equals(actual))
			throw new IllegalStateException(what + " : expected " + expected + ", got " + actual);
	}
}
