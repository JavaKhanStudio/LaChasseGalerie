package jks.smoke;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import jks.headless.Headless_Runner;
import jks.input.GVars_Controller;
import jks.input.PlayerId;
import jks.net.Net_Input;
import jks.net.Net_Loopback;
import jks.net.Net_Message;
import jks.online.ClientSession;
import jks.online.Game_Simulation;
import jks.online.HostSession;
import jks.online.Snapshot_View;
import jks.vars.GVars_Game;
import jks.vars.GVars_Random;

/**
 * `./gradlew netsession` : phase 1.4's in-process gate (r39). The real game runs headless behind a
 * HostSession with NO hero of its own ; two ClientSessions, which own no world, join it over the
 * in-memory wire with latency, jitter, loss, copies and reordering, press at random, die, rejoin, and
 * every tick every entity they draw is held to where the host had it at the tick they draw
 * (Net_Tracking). Half way a third client joins and one leaves ; then the host player picks up a
 * keyboard, which must not take a remote player's number. The first disagreement exits 1.
 */
public class Net_Session implements Headless_Runner.Session
{
	final int seconds;
	final long seed;
	int frame;

	final Net_Tracking tracking = new Net_Tracking(0.01f);
	final List<Net_Message.Leave.Reason> left = new ArrayList<Net_Message.Leave.Reason>();
	int joins;

	public static void main(String[] args)
	{
		int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 60;
		long seed = args.length > 1 ? Long.parseLong(args[1]) : 1;
		Headless_Runner.launch(1280, 720, new Net_Session(seconds, seed));
	}

	Net_Session(int seconds, long seed)
	{
		this.seconds = seconds;
		this.seed = seed;
	}

	@Override
	public void failed(Throwable t)
	{
		System.out.println("SESSION FAILED at frame " + frame + " (t=" + frame / 60f + "s, seed " + seed + ")");
		t.printStackTrace(System.out);
	}

	@Override
	public void run(Headless_Runner runner) throws Exception
	{
		GVars_Random.seed(seed);
		Random random = new Random(seed);
		runner.boot();

		Net_Loopback wire = new Net_Loopback(seed);
		wire.latencyMs = 40;
		wire.jitterMs = 20;
		wire.loss = 0.03f;
		wire.duplicate = 0.01f;
		wire.reorder = 0.02f;

		HostSession host = new HostSession(wire.open("host"), new Game_Simulation(), new HostSession.Events()
		{
			@Override
			public void joined(HostSession.Seat seat)
			{
				joins++;
			}

			@Override
			public void left(HostSession.Seat seat, Net_Message.Leave.Reason reason)
			{
				left.add(reason);
			}
		});

		List<ClientSession> clients = new ArrayList<ClientSession>();
		List<String> names = new ArrayList<String>();
		clients.add(new ClientSession(wire.open("a"), "host"));
		names.add("client a");
		clients.add(new ClientSession(wire.open("b"), "host"));
		names.add("client b");
		int[] buttons = new int[3];
		Set<Integer> heroIdsDrawnByA = new HashSet<Integer>();
		PlayerId local = null;

		int total = seconds * 60;
		for (frame = 0; frame < total; frame++)
		{
			wire.advance((frame + 1) * 1000L / 60 - frame * 1000L / 60);

			if (frame == total / 2)
			{
				clients.get(1).close();
				clients.add(new ClientSession(wire.open("c"), "host"));
				names.add("client c");
			}
			if (frame == total * 3 / 4)
			{
				// The host player joins on the keyboard, the way IKM_Game_Keyboard does
				local = GVars_Controller.identify(null);
				for (ClientSession client : clients)
					if (client.player() == local.number())
						throw new IllegalStateException("the host's keyboard took the number of " + client.player() + ", a remote player");
				GVars_Game.addPlayer(local);
			}

			host.tick(runner::step);
			tracking.hostTicked(Snapshot_View.read(host.tick()));

			for (int i = 0; i < clients.size(); i++)
			{
				ClientSession client = clients.get(i);
				if (client.state() == ClientSession.State.ENDED)
					continue;
				if (frame % 20 == 0)
					buttons[i] = new int[] { 0, Net_Input.LEFT, Net_Input.RIGHT }[random.nextInt(3)];
				int pressed = random.nextInt(12) == 0 ? new int[] { Net_Input.JUMP, Net_Input.SWING_LEFT, Net_Input.SWING_RIGHT }[random.nextInt(3)] : 0;
				// Offline, a dead player's next key press brings the hero back : online, the same
				if (!client.hasHeroInNewest())
					client.join();
				client.tick(buttons[i] | pressed);
				if (client.state() == ClientSession.State.IN)
					tracking.compare(client, host.tick(), names.get(i));
			}
			clients.get(0).view().heroes().forEach(hero -> heroIdsDrawnByA.add(hero.id));
		}

		// What the host saw
		if (host.seats().size() != 2)
			throw new IllegalStateException("the host should end with a and c seated, has " + host.seats().size());
		if (!left.equals(List.of(Net_Message.Leave.Reason.QUIT)))
			throw new IllegalStateException("only b should have left, and by quitting : " + left);
		for (HostSession.Seat seat : host.seats())
			if (seat.pressesApplied == 0 || seat.framesApplied < 60)
				throw new IllegalStateException(seat.player + " sent input that never landed : " + seat.framesApplied + " frames, " + seat.pressesApplied + " presses");
		if (joins < 4)
			throw new IllegalStateException("only " + joins + " joins : nobody died and came back, the run proves nothing about rejoining");
		if (local == null || GVars_Controller.getPlayer(local) == null && GVars_Game.playerRegister.get(local) == null)
			throw new IllegalStateException("the host's own player never made it into the run");

		// What the clients drew
		if (tracking.compared < 20_000)
			throw new IllegalStateException("too little was compared : " + tracking.report());
		if (tracking.worstLag > 20)
			throw new IllegalStateException("the picture fell too far behind the host : " + tracking.report());
		if (tracking.errorSum / tracking.compared > 0.02)
			throw new IllegalStateException("the mean error is past 2 cm : " + tracking.report());
		boolean hostSeen = false;
		for (var hero : clients.get(0).newest().heroes)
			hostSeen |= hero.player == local.number();
		if (!hostSeen && GVars_Controller.getPlayer(local) != null)
			throw new IllegalStateException("the host's own hero is alive but not in what a client is sent");

		host.close();
		for (int i = 0; i < 10; i++)
		{
			wire.advance(17);
			for (ClientSession client : clients)
				client.tick(0);
		}
		for (int i = 0; i < clients.size(); i++)
		{
			ClientSession client = clients.get(i);
			Net_Message.Leave.Reason expected = i == 1 ? Net_Message.Leave.Reason.QUIT : Net_Message.Leave.Reason.HOST_ENDED;
			if (client.endedBecause() != expected)
				throw new IllegalStateException(names.get(i) + " ended " + client.state() + " " + client.endedBecause() + ", expected " + expected);
		}

		System.out.println("SESSION ok : seed " + seed + ", " + seconds + "s, a headless host with no hero, 3 clients over 40+-20 ms, 3% loss : "
				+ joins + " joins, " + heroIdsDrawnByA.size() + " hero entities drawn by client a, b quit, the host's keyboard joined as " + local + " ; "
				+ tracking.report());
	}
}
