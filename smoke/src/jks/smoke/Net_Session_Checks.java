package jks.smoke;

import static jks.smoke.Net_Run.eq;
import static jks.smoke.Net_Run.is;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import jks.input.PlayerId;
import jks.net.Net_Codec;
import jks.net.Net_Input;
import jks.net.Net_Loopback;
import jks.net.Net_Message;
import jks.net.Net_Peer;
import jks.net.Net_Transport;
import jks.online.ClientSession;
import jks.online.HostSession;

/**
 * nettest's checks for phase 1.4 (r39) : HostSession and ClientSession over the in-memory wire, with a
 * toy world behind the host, so every rule runs in milliseconds and a failure replays from its seed.
 * `./gradlew netsession` holds the same two classes against the real game.
 */
class Net_Session_Checks
{
	/** A host, its toy world and its clients on one wire, stepped together at 60 Hz of virtual time. */
	static final class Rig
	{
		final Net_Loopback wire;
		final Net_Toy_World world = new Net_Toy_World();
		final HostSession host;
		final List<ClientSession> clients = new ArrayList<ClientSession>();
		final Net_Tracking tracking;
		int frame;

		Rig(long seed, float tolerance)
		{
			wire = new Net_Loopback(seed);
			host = new HostSession(wire.open("host"), world);
			tracking = new Net_Tracking(tolerance);
		}

		ClientSession client(String name)
		{
			ClientSession client = new ClientSession(wire.open(name), "host");
			clients.add(client);
			return client;
		}

		/** One 60 Hz frame of virtual time : the wire's clock, the host, then each client with its buttons. */
		void frame(int... buttons)
		{
			wire.advance((frame + 1) * 1000L / 60 - frame * 1000L / 60);
			frame++;
			host.tick(world::step);
			tracking.hostTicked(world.read(host.tick()));
			for (int i = 0; i < clients.size(); i++)
			{
				ClientSession client = clients.get(i);
				client.tick(i < buttons.length ? buttons[i] : 0);
				if (client.state() == ClientSession.State.IN)
					tracking.compare(client, host.tick(), "client " + (i + 1));
			}
		}

		void frames(int count)
		{
			for (int i = 0; i < count; i++)
				frame();
		}
	}

	/** Nothing arrives before its latency ; jitter reorders on its own ; the same seed delivers the same order. */
	static void latencyAndJitter() throws Exception
	{
		eq(arrivalOrder(3), arrivalOrder(3), "one seed, one order");
		List<Integer> order = arrivalOrder(3);
		eq(50, order.size(), "every packet arrives, late or not");
		is(!order.equals(new ArrayList<Integer>(new java.util.TreeSet<Integer>(order))), "40 ms of jitter on packets 5 ms apart reorders some : " + order);
	}

	static List<Integer> arrivalOrder(long seed)
	{
		Net_Loopback wire = new Net_Loopback(seed);
		wire.latencyMs = 100;
		wire.jitterMs = 40;
		Net_Transport host = wire.open("host");
		Net_Transport client = wire.open("client");
		Net_Peer toHost = client.resolve("host");
		Net_Run.Inbox inbox = new Net_Run.Inbox();
		for (int i = 0; i < 50; i++)
		{
			client.send(toHost, ByteBuffer.wrap(new byte[] { (byte) i }));
			wire.advance(5);
			host.pump(inbox);
			// Sent at 5 ms a packet : by now only what left 100 ms ago can have arrived
			is(inbox.size() <= Math.max(0, i - 19), "packet " + inbox.size() + " arrived before its 100 ms, at " + (i * 5 + 5) + " ms");
		}
		wire.advance(200);
		host.pump(inbox);
		List<Integer> order = new ArrayList<Integer>();
		for (int i = 0; i < inbox.size(); i++)
			order.add(Integer.valueOf(inbox.bytes(i)[0]));
		return order;
	}

	/** Two peers, two players, two heroes - and a host with no hero of its own, the legal ordinary case. */
	static void welcomeAndJoin() throws Exception
	{
		Rig rig = new Rig(1, 0.001f);
		ClientSession a = rig.client("a"), b = rig.client("b");
		rig.frames(2);
		eq(ClientSession.State.IN, a.state(), "a after its HELLO");
		eq(ClientSession.State.IN, b.state(), "b after its HELLO");
		is(a.player() != b.player() && a.player() > 0 && b.player() > 0, "two peers are two players : " + a.player() + ", " + b.player());
		eq(0, rig.world.heroes.size(), "nobody has a hero before asking, and the host never had one");

		a.join();
		b.join();
		rig.frames(12);
		eq(2, rig.world.heroes.size(), "a JOIN each is a hero each");
		is(a.hasHeroInNewest() && b.hasHeroInNewest(), "each client sees its own hero");
		eq(2, a.view().heroes().size(), "a draws both heroes");

		// A JOIN while the hero lives changes nothing
		int id = rig.world.heroes.get(PlayerId.of(a.player())).id;
		a.join();
		rig.frames(40);
		eq(id, rig.world.heroes.get(PlayerId.of(a.player())).id, "a second JOIN left the living hero alone");
	}

	/** The WELCOME was lost : the client says HELLO again and is the same player, in one seat. */
	static void helloAgainIsTheSamePlayer() throws Exception
	{
		Rig rig = new Rig(3, 0.001f);
		rig.wire.loss = 0.6f;
		ClientSession a = rig.client("a");
		for (int i = 0; i < 600 && a.state() != ClientSession.State.IN; i++)
			rig.frame();
		eq(ClientSession.State.IN, a.state(), "a client over a lossy wire gets in eventually");
		rig.wire.loss = 0;
		rig.frames(60);
		eq(1, rig.host.seats().size(), "HELLOs that crossed a lost WELCOME are one seat");
		eq(a.player(), rig.host.seats().get(0).player.number(), "and the client is the player that seat holds");
	}

	/**
	 * The heart of it : over a wire that loses, duplicates, reorders and delays, every press lands once,
	 * in the order pressed, and what is held is held.
	 */
	static void inputLandsOnceInOrder() throws Exception
	{
		Rig rig = new Rig(7, 0.001f);
		rig.wire.loss = 0.1f;
		rig.wire.duplicate = 0.1f;
		rig.wire.reorder = 0.2f;
		rig.wire.latencyMs = 40;
		rig.wire.jitterMs = 30;
		ClientSession a = rig.client("a");
		rig.frames(10);
		a.join();
		for (int i = 0; i < 300 && !a.hasHeroInNewest(); i++)
			rig.frame();
		is(a.hasHeroInNewest(), "a has a hero");
		PlayerId player = PlayerId.of(a.player());

		Random random = new Random(11);
		List<Integer> pressedOrder = new ArrayList<Integer>();
		int held = 0;
		for (int i = 0; i < 1200; i++)
		{
			if (i % 40 == 0)
				held = new int[] { 0, Net_Input.LEFT, Net_Input.RIGHT }[random.nextInt(3)];
			int buttons = held;
			// Presses a few ticks apart : far enough that a coalesced burst is still distinguishable in order
			if (i < 1100 && i % 9 == 0)
			{
				int press = new int[] { Net_Input.JUMP, Net_Input.SWING_LEFT, Net_Input.SWING_RIGHT }[pressedOrder.size() % 3];
				buttons |= press;
				pressedOrder.add(press);
			}
			rig.frame(buttons);
		}
		rig.wire.loss = 0;
		for (int i = 0; i < 60; i++)
			rig.frame(held);

		List<String> landed = rig.world.presses.get(player);
		// Four frames a packet : only four packets lost in a row lose a press, and this seed has none
		eq(pressedOrder.size(), landed.size(), "presses sent against presses that landed");
		int last = -1;
		for (int i = 0; i < landed.size(); i++)
		{
			String[] parts = landed.get(i).split(":");
			int at = Integer.parseInt(parts[0]), bits = Integer.parseInt(parts[1]);
			eq(pressedOrder.get(i).intValue(), bits, "press " + i + " landed as");
			is(at > last, "press " + i + " landed at tick " + at + ", not after the one before it at " + last);
			last = at;
		}
		HostSession.Seat seat = rig.host.seats().get(0);
		is(seat.framesIgnored > 0, "the wire duplicated and repeated frames, and the host ignored the copies");
		eq(held, rig.world.heroes.get(player).held, "what the client holds last is what the hero holds");
	}

	/** A frame claiming a tick to come waits for it ; one claiming a tick absurdly far ahead is a broken clock, applied now. */
	static void futureFramesWaitForTheirTick() throws Exception
	{
		Net_Loopback wire = new Net_Loopback(1);
		Net_Toy_World world = new Net_Toy_World();
		HostSession host = new HostSession(wire.open("host"), world);
		Net_Transport raw = wire.open("raw");
		Net_Peer toHost = raw.resolve("host");
		raw.send(toHost, Net_Codec.encode(new Net_Message.Hello()));
		host.tick(world::step);
		raw.send(toHost, Net_Codec.encode(new Net_Message.Join()));
		host.tick(world::step);
		PlayerId player = host.seats().get(0).player;

		Net_Input input = new Net_Input();
		input.push(host.tick() + 5, Net_Input.JUMP);
		raw.send(toHost, Net_Codec.encode(input));
		int sentAt = host.tick();
		for (int i = 0; i < 8; i++)
			host.tick(world::step);
		eq(List.of((sentAt + 5) + ":" + Net_Input.JUMP), world.presses.get(player), "a frame for 5 ticks ahead landed on its tick");

		Net_Input broken = new Net_Input();
		broken.push(host.tick() + 100_000, Net_Input.SWING_LEFT);
		raw.send(toHost, Net_Codec.encode(broken));
		int now = host.tick();
		host.tick(world::step);
		eq(now + ":" + Net_Input.SWING_LEFT, world.presses.get(player).get(1), "a frame from a clock 100 000 ticks ahead lands now");
	}

	/** Bytes from someone who never said HELLO are nobody's : no seat, no hero, no press. */
	static void strangersAreIgnored() throws Exception
	{
		Net_Loopback wire = new Net_Loopback(1);
		Net_Toy_World world = new Net_Toy_World();
		HostSession host = new HostSession(wire.open("host"), world);
		Net_Transport stranger = wire.open("stranger");
		Net_Peer toHost = stranger.resolve("host");
		stranger.send(toHost, Net_Codec.encode(new Net_Message.Join()));
		Net_Input input = new Net_Input();
		input.push(0, Net_Input.JUMP);
		stranger.send(toHost, Net_Codec.encode(input));
		stranger.send(toHost, ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }));
		host.tick(world::step);
		eq(0, host.seats().size(), "a stranger has no seat");
		eq(0, world.heroes.size(), "a stranger's JOIN made no hero");
		eq(1, host.rejected, "the garbage was rejected, and counted");
	}

	/** A hero dies : the client sees it gone, asks again, and comes back as a new entity for the same player. */
	static void rejoinAfterDeath() throws Exception
	{
		Rig rig = new Rig(5, 0.001f);
		rig.wire.latencyMs = 30;
		ClientSession a = rig.client("a");
		rig.frames(5);
		a.join();
		rig.frames(30);
		PlayerId player = PlayerId.of(a.player());
		int first = rig.world.heroes.get(player).id;

		rig.world.kill(player);
		rig.frames(30);
		is(!a.hasHeroInNewest(), "the dead hero is gone from the client");
		eq(0, a.view().heroes().size(), "and from what it draws");
		a.join();
		rig.frames(30);
		is(rig.world.hasHero(player), "the JOIN brought the hero back");
		is(rig.world.heroes.get(player).id != first, "as a new entity");
		eq(1, a.view().heroes().size(), "drawn once");
		eq(1, rig.host.seats().size(), "for the same seat");
		eq(1, a.newest().scores.get(0).deaths, "and the death counted against the same player");
	}

	/** Leaving is an event, and so is going silent : both free the seat and take the hero out. */
	static void leaveAndTimeout() throws Exception
	{
		Rig rig = new Rig(9, 0.001f);
		ClientSession a = rig.client("a"), b = rig.client("b");
		rig.frames(3);
		a.join();
		b.join();
		rig.frames(20);
		eq(2, rig.world.heroes.size(), "two heroes");

		List<Net_Message.Leave.Reason> reasons = new ArrayList<Net_Message.Leave.Reason>();
		rig.host.events = new HostSession.Events()
		{
			@Override
			public void left(HostSession.Seat seat, Net_Message.Leave.Reason reason)
			{
				reasons.add(reason);
			}
		};

		a.close();
		rig.frames(3);
		eq(1, rig.host.seats().size(), "a LEAVE frees a's seat");
		is(!rig.world.hasHero(PlayerId.of(a.player())), "and takes a's hero out");
		eq(1, rig.world.removed.size(), "removed, not killed");
		eq(List.of(Net_Message.Leave.Reason.QUIT), reasons, "why a left");

		// b stops ticking : its transport says nothing more, and the host's timeout finds out
		rig.clients.clear();
		for (int i = 0; i < 11 * 60; i++)
			rig.frame();
		eq(0, rig.host.seats().size(), "a silent peer loses its seat after the timeout");
		is(!rig.world.hasHero(PlayerId.of(b.player())), "and its hero");
		eq(List.of(Net_Message.Leave.Reason.QUIT, Net_Message.Leave.Reason.TIMEOUT), reasons, "why b left");
	}

	/** Eight seats : the ninth is told FULL, and a host that ends the run tells everyone. */
	static void fullAndHostEnded() throws Exception
	{
		Rig rig = new Rig(2, 0.001f);
		for (int i = 0; i < HostSession.MAX_PLAYERS + 1; i++)
			rig.client("c" + i);
		rig.frames(3);
		ClientSession ninth = rig.clients.get(HostSession.MAX_PLAYERS);
		eq(ClientSession.State.ENDED, ninth.state(), "the ninth client");
		eq(Net_Message.Leave.Reason.FULL, ninth.endedBecause(), "why the ninth was refused");
		eq(HostSession.MAX_PLAYERS, rig.host.seats().size(), "seats");

		rig.host.close();
		rig.frames(2);
		for (int i = 0; i < HostSession.MAX_PLAYERS; i++)
		{
			eq(ClientSession.State.ENDED, rig.clients.get(i).state(), "client " + i + " after the host ended");
			eq(Net_Message.Leave.Reason.HOST_ENDED, rig.clients.get(i).endedBecause(), "why client " + i + " ended");
		}
	}

	/** Another protocol version cannot play : the host says which version it speaks, and a client can read that. */
	static void versionIsRefused() throws Exception
	{
		Net_Loopback wire = new Net_Loopback(1);
		Net_Toy_World world = new Net_Toy_World();
		HostSession host = new HostSession(wire.open("host"), world);
		ClientSession client = new ClientSession(wire.open("future"), "host");
		// The HELLO is already on the wire : what a host of another version sends back is simulated below instead
		host.tick(world::step);
		client.tick(0);
		eq(ClientSession.State.IN, client.state(), "same version plays");

		Net_Transport future = wire.open("future-client");
		ByteBuffer hello = Net_Codec.encode(new Net_Message.Hello());
		hello.put(0, (byte) (Net_Codec.VERSION + 1));
		future.send(future.resolve("host"), hello);
		host.tick(world::step);
		eq(1, host.seats().size(), "another version gets no seat");
		Net_Run.Inbox inbox = new Net_Run.Inbox();
		future.pump(inbox);
		is(inbox.size() > 0, "the host answered the other version");
		Net_Message.Leave leave = (Net_Message.Leave) Net_Codec.decode(ByteBuffer.wrap(inbox.bytes(0)));
		eq(Net_Message.Leave.Reason.VERSION, leave.reason, "and said why");

		// A client meeting a host of another version ends, and knows the version
		Net_Transport oldHost = wire.open("old-host");
		ClientSession stranded = new ClientSession(wire.open("stranded"), "old-host");
		oldHost.pump(new Net_Run.Inbox());
		ByteBuffer refusal = Net_Codec.encode(new Net_Message.Leave(HostSession.NOBODY, Net_Message.Leave.Reason.VERSION));
		refusal.put(0, (byte) 9);
		oldHost.send(oldHost.resolve("stranded"), refusal);
		stranded.tick(0);
		eq(ClientSession.State.ENDED, stranded.state(), "a client refused by another version");
		eq(Net_Message.Leave.Reason.VERSION, stranded.endedBecause(), "why");
		eq(9, stranded.hostVersion(), "the version the host speaks");
	}

	/**
	 * The gate's promise on the toy world : two clients behind 50 ms of latency, jitter, loss, copies and
	 * reordering draw every entity where the host had it at the tick they draw, a little over 100 ms
	 * behind - through deaths, rejoins, and entities coming and going.
	 *
	 * Each position is held to what a straight line between its two snapshots can honestly promise
	 * (Net_Tracking), plus 1 mm ; the mean, which a wrong tick or a wrong pair of snapshots would blow up
	 * at once, to 1 cm.
	 */
	static void clientsTrackTheHost() throws Exception
	{
		Rig rig = new Rig(21, 0.001f);
		rig.wire.latencyMs = 50;
		rig.wire.jitterMs = 20;
		rig.wire.loss = 0.05f;
		rig.wire.duplicate = 0.02f;
		rig.wire.reorder = 0.05f;
		ClientSession a = rig.client("a"), b = rig.client("b");
		Random random = new Random(4);
		int[] held = new int[2];
		Set<Integer> heroIds = new HashSet<Integer>();
		for (int i = 0; i < 60 * 60; i++)
		{
			for (int c = 0; c < 2; c++)
			{
				ClientSession client = rig.clients.get(c);
				if (!client.hasHeroInNewest())
					client.join();
				if (i % 30 == 0)
					held[c] = new int[] { 0, Net_Input.LEFT, Net_Input.RIGHT }[random.nextInt(3)];
			}
			if (i % 900 == 450)
				rig.world.kill(PlayerId.of(a.player()));
			rig.frame(held[0] | (random.nextInt(20) == 0 ? Net_Input.JUMP : 0), held[1] | (random.nextInt(20) == 0 ? Net_Input.JUMP : 0));
			a.view().heroes().forEach(hero -> heroIds.add(hero.id));
		}
		is(rig.tracking.compared > 10_000, "the check compared almost nothing : " + rig.tracking.report());
		is(rig.tracking.errorSum / rig.tracking.compared < 0.01, "the mean error is past 1 cm : " + rig.tracking.report());
		is(rig.tracking.worstLag <= 20, "the picture fell too far behind : " + rig.tracking.report());
		is(heroIds.size() >= 5, "a died and rejoined, so a drew at least 5 hero entities, got " + heroIds.size());
		System.out.println("NET      toy tracking : " + rig.tracking.report());
	}
}
