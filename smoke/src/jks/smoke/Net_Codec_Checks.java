package jks.smoke;

import static jks.smoke.Net_Run.eq;
import static jks.smoke.Net_Run.is;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.zip.CRC32;

import jks.net.Net_Codec;
import jks.net.Net_Input;
import jks.net.Net_Listener;
import jks.net.Net_Loopback;
import jks.net.Net_Message;
import jks.net.Net_Peer;
import jks.net.Net_Rejected;
import jks.net.Net_Snapshot;
import jks.net.Net_Transport;
import jks.online.HostSession;

/**
 * nettest's checks for the game protocol (phase 1.2, r37) : Net_Codec in and out, no socket needed
 * except for the one check that sends inputs over the lossy in-memory wire.
 *
 * What they hold it to : every field survives the round trip ; a truncated, corrupted or lying packet
 * is refused and never half-read ; a packet past 1200 B is refused out loud when it is encoded ; and
 * the biggest snapshot a real 8-player run made (`./gradlew netcensus`) fits.
 */
class Net_Codec_Checks
{
	/**
	 * The worst of each count `./gradlew netcensus` measured, 8 players, seeds 1-5 over 120 s and seed 2
	 * over 600 s (r37), taken together although no single tick had them all : the biggest real snapshot
	 * was 442 B. Measure again and raise these if the game spawns more : the check below is
	 * only as honest as they are.
	 */
	static final int MEASURED_PLAYERS = 8, MEASURED_HEROES = 8, MEASURED_MONSTERS = 26, MEASURED_POTIONS = 8;

	// ---------------------------------------------------------------- round trips

	static void controlRoundTrip() throws Exception
	{
		roundTrip(new Net_Message.Hello(1), 14);
		roundTrip(new Net_Message.Hello(-1), 14);
		roundTrip(new Net_Message.Hello(Long.MIN_VALUE), 14);
		roundTrip(new Net_Message.Join(), 6);
		roundTrip(new Net_Message.Keepalive(), 6);
		roundTrip(new Net_Message.Welcome(1, 0), 12);
		roundTrip(new Net_Message.Welcome(65535, -1), 12);
		for (Net_Message.Leave.Reason reason : Net_Message.Leave.Reason.values())
			roundTrip(new Net_Message.Leave(7, reason), 9);
	}

	static void inputRoundTrip() throws Exception
	{
		for (int buttons = 0; buttons < 32; buttons++)
		{
			Net_Input input = new Net_Input();
			for (int count = 1; count <= Net_Input.FRAMES; count++)
			{
				input.push(Integer.MAX_VALUE - 10 + count, (buttons + count) % 32);
				roundTrip(input, 11 + count);
			}
		}
		Net_Input input = new Net_Input();
		for (int tick = 0; tick < 10; tick++)
			input.push(tick, tick % 32);
		eq(9, input.buttonsAt(9), "the newest frame");
		eq(6, input.buttonsAt(6), "the oldest frame kept");
		eq(-1, input.buttonsAt(5), "a frame already dropped");
		eq(-1, input.buttonsAt(10), "a frame not pressed yet");
	}

	/** Every field of every entity, each set to something no other field holds, on the quantization grid. */
	static void snapshotRoundTrip() throws Exception
	{
		Net_Snapshot snapshot = fullSnapshot();
		Net_Message decoded = roundTrip(snapshot, Net_Codec.snapshotSize(2, 2, 2, 2));

		// equals is the whole proof, so prove it looks at every field : change each one, and it must see it
		List<Consumer<Net_Snapshot>> changes = new ArrayList<Consumer<Net_Snapshot>>();
		changes.add(s -> s.tick = s.tick + 1);
		changes.add(s -> s.storyTime += 1);
		changes.add(s -> s.skyScroll += 1);
		changes.add(s -> s.canoeAngle += 1 / 8192f);
		changes.add(s -> s.run = other(s.run, 65535));
		changes.add(s -> s.over ^= true);
		changes.add(s -> s.scores.get(1).player = other(s.scores.get(1).player, 65535));
		changes.add(s -> s.scores.get(1).score = other(s.scores.get(1).score, 65535));
		changes.add(s -> s.scores.get(1).deaths = other(s.scores.get(1).deaths, 65535));
		changes.add(s -> s.heroes.get(1).id = other(s.heroes.get(1).id, 65535));
		changes.add(s -> s.heroes.get(1).player = other(s.heroes.get(1).player, 65535));
		changes.add(s -> s.heroes.get(1).look = other(s.heroes.get(1).look, 255));
		changes.add(s -> s.heroes.get(1).x += 1 / 256f);
		changes.add(s -> s.heroes.get(1).y += 1 / 256f);
		changes.add(s -> s.heroes.get(1).vx += 1 / 256f);
		changes.add(s -> s.heroes.get(1).vy += 1 / 256f);
		changes.add(s -> s.heroes.get(1).hp = other(s.heroes.get(1).hp, 255));
		changes.add(s -> s.heroes.get(1).anim = other(s.heroes.get(1).anim, 15));
		changes.add(s -> s.heroes.get(1).reverse ^= true);
		changes.add(s -> s.heroes.get(1).invulnerable ^= true);
		changes.add(s -> s.heroes.get(1).axeX += 1 / 256f);
		changes.add(s -> s.heroes.get(1).axeY += 1 / 256f);
		changes.add(s -> s.heroes.get(1).axeAngle += 1 / 8192f);
		changes.add(s -> s.monsters.get(1).id = other(s.monsters.get(1).id, 65535));
		changes.add(s -> s.monsters.get(1).look = other(s.monsters.get(1).look, 127));
		changes.add(s -> s.monsters.get(1).x += 1 / 256f);
		changes.add(s -> s.monsters.get(1).y += 1 / 256f);
		changes.add(s -> s.monsters.get(1).reverse ^= true);
		changes.add(s -> s.potions.get(1).id = other(s.potions.get(1).id, 65535));
		changes.add(s -> s.potions.get(1).x += 1 / 256f);
		changes.add(s -> s.potions.get(1).y += 1 / 256f);
		for (int i = 0; i < changes.size(); i++)
		{
			Net_Snapshot changed = fullSnapshot();
			changes.get(i).accept(changed);
			Net_Message back = Net_Codec.decode(Net_Codec.encode(changed));
			is(!back.equals(decoded), "field change " + i + " did not survive the wire, or equals does not look at it");
			eq(changed, back, "field change " + i + " round trip");
		}
	}

	/** Quantized to a grid, clamped at the edge, angles wrapped : close enough to draw, never refused for being far. */
	static void quantizing() throws Exception
	{
		Random random = new Random(37);
		for (int i = 0; i < 10000; i++)
		{
			Net_Snapshot snapshot = new Net_Snapshot();
			Net_Snapshot.Hero hero = hero(1, 1);
			hero.x = (random.nextFloat() - 0.5f) * 250;
			hero.vy = (random.nextFloat() - 0.5f) * 250;
			hero.axeAngle = (random.nextFloat() - 0.5f) * 100;
			snapshot.heroes.add(hero);
			Net_Snapshot.Hero back = ((Net_Snapshot) Net_Codec.decode(Net_Codec.encode(snapshot))).heroes.get(0);
			is(Math.abs(back.x - hero.x) <= 1 / 512f + 1e-4f, "x " + hero.x + " came back " + back.x);
			is(Math.abs(back.vy - hero.vy) <= 1 / 512f + 1e-4f, "vy " + hero.vy + " came back " + back.vy);
			double turn = Math.IEEEremainder(back.axeAngle - hero.axeAngle, 2 * Math.PI);
			is(Math.abs(turn) <= 1 / 16384f + 1e-4f, "angle " + hero.axeAngle + " came back " + back.axeAngle);
			is(back.axeAngle >= -Math.PI - 1e-3 && back.axeAngle <= Math.PI + 1e-3, "angle " + back.axeAngle + " is not wrapped");
		}

		Net_Snapshot far = new Net_Snapshot();
		Net_Snapshot.Monster flung = new Net_Snapshot.Monster();
		flung.x = 5000;
		flung.y = -33000;
		far.monsters.add(flung);
		Net_Snapshot.Monster back = ((Net_Snapshot) Net_Codec.decode(Net_Codec.encode(far))).monsters.get(0);
		eq(32767 / 256f, back.x, "far right clamps to the edge");
		eq(-128f, back.y, "far below clamps to the edge");

		refusedToEncode(s -> s.heroes.get(0).x = Float.NaN, "a NaN position");
		refusedToEncode(s -> s.storyTime = Float.POSITIVE_INFINITY, "an infinite clock");
		refusedToEncode(s -> s.heroes.get(0).player = 0, "player 0");
		refusedToEncode(s -> s.heroes.get(0).id = 65536, "an id past 16 bits");
		refusedToEncode(s -> s.heroes.get(0).anim = 16, "an animation past 4 bits");
		refusedToEncode(s -> s.monsters.get(0).look = 128, "a monster look past 7 bits");
		refusedToEncode(s -> s.scores.get(0).score = -1, "a negative score");
	}

	// ---------------------------------------------------------------- refusals

	/** A packet cut anywhere is refused : the transport promises whole packets, the internet does not. */
	static void truncatedIsRejected() throws Exception
	{
		for (Net_Message message : everyKind())
		{
			ByteBuffer packet = Net_Codec.encode(message);
			for (int length = 0; length < packet.remaining(); length++)
				rejected(slice(packet, 0, length), message.type() + " cut to " + length + " B");
			// And the other way : bytes the sender never wrote, after a whole packet
			ByteBuffer longer = ByteBuffer.allocate(packet.remaining() + 1).put(packet.duplicate());
			longer.position(longer.capacity());
			longer.flip();
			rejected(longer, message.type() + " with a byte after it");
		}
	}

	/** Every single bit of every kind of packet, flipped in turn, and not one is misread. */
	static void corruptedIsRejected() throws Exception
	{
		int flips = 0;
		for (Net_Message message : everyKind())
		{
			ByteBuffer packet = Net_Codec.encode(message);
			for (int bit = 0; bit < packet.remaining() * 8; bit++)
			{
				ByteBuffer corrupted = copy(packet);
				corrupted.put(bit / 8, (byte) (corrupted.get(bit / 8) ^ (1 << (bit % 8))));
				rejected(corrupted, message.type() + " with bit " + bit + " flipped");
				flips++;
			}
		}
		is(flips > 1000, "only " + flips + " flips tried");

		// Noise with our own version byte in front, so it gets past the first gate
		Random random = new Random(1200);
		for (int i = 0; i < 100000; i++)
		{
			byte[] noise = new byte[random.nextInt(64)];
			random.nextBytes(noise);
			if (noise.length > 0)
				noise[0] = (byte) Net_Codec.VERSION;
			rejected(ByteBuffer.wrap(noise), "noise " + i);
		}
	}

	/** A packet whose checksum is right and whose content is not : what a hostile or buggy peer sends. */
	static void liesAreRejected() throws Exception
	{
		eq(Net_Rejected.Reason.VERSION, reason(signed(Net_Codec.VERSION + 1, 0)), "another version");
		eq(Net_Codec.VERSION + 1, rejectedBy(signed(Net_Codec.VERSION + 1, 0)).version, "the version it says it is");
		eq(Net_Codec.VERSION + 1, Net_Codec.versionOf(signed(Net_Codec.VERSION + 1, 0)), "versionOf reads the first byte");
		eq(Net_Rejected.Reason.UNKNOWN_TYPE, reason(signed(Net_Codec.VERSION, 200)), "an unknown type");
		eq(Net_Rejected.Reason.LENGTH, reason(signed(Net_Codec.VERSION, Net_Message.Type.HELLO.ordinal(), 0)), "a HELLO with a one-byte key");
		eq(Net_Rejected.Reason.LENGTH, reason(signed(Net_Codec.VERSION, Net_Message.Type.HELLO.ordinal())), "a HELLO with no key");
		eq(Net_Rejected.Reason.BAD_VALUE, reason(signed(Net_Codec.VERSION, Net_Message.Type.HELLO.ordinal(), 0, 0, 0, 0, 0, 0, 0, 0)), "rejoin key 0");
		eq(Net_Rejected.Reason.BAD_VALUE, reason(signed(Net_Codec.VERSION, Net_Message.Type.LEAVE.ordinal(), 0, 1, 99)), "an unknown leave reason");
		eq(Net_Rejected.Reason.BAD_VALUE, reason(signed(Net_Codec.VERSION, Net_Message.Type.WELCOME.ordinal(), 0, 0, 0, 0, 0, 1)), "player 0");
		eq(Net_Rejected.Reason.BAD_VALUE, reason(signed(Net_Codec.VERSION, Net_Message.Type.INPUT.ordinal(), 0, 0, 0, 1, 5, 0, 0, 0, 0, 0)), "five input frames");
		eq(Net_Rejected.Reason.BAD_VALUE, reason(signed(Net_Codec.VERSION, Net_Message.Type.INPUT.ordinal(), 0, 0, 0, 1, 1, 64)), "an unknown button");
		eq(Net_Rejected.Reason.TOO_SHORT, reason(ByteBuffer.wrap(new byte[] { (byte) Net_Codec.VERSION, 0 })), "no checksum");

		// A snapshot header claiming 255 of everything and carrying none of it : refused before allocating
		int[] header = new int[2 + 4 + 4 + 4 + 2 + 4 + 2 + 1];
		header[0] = Net_Codec.VERSION;
		header[1] = Net_Message.Type.SNAPSHOT.ordinal();
		for (int i = 16; i < 20; i++)
			header[i] = 255;
		eq(Net_Rejected.Reason.LENGTH, reason(signed(header)), "a snapshot that claims 1020 entities in 23 B");
		// The score screen is on or off : a third state is a lie (d14)
		header[16] = header[17] = header[18] = header[19] = 0;
		header[22] = 2;
		eq(Net_Rejected.Reason.BAD_VALUE, reason(signed(header)), "a snapshot that is over 2");

		ByteBuffer hero = copy(Net_Codec.encode(fullSnapshot()));
		// version, type, the fixed snapshot fields, both scores, then the first hero's id, player, look, 4 x s16, hp
		int flags = 2 + 21 + 2 * Net_Codec.SCORE_BYTES + 2 + 2 + 1 + 8 + 1;
		hero.put(flags, (byte) 0x40);
		eq(Net_Rejected.Reason.BAD_VALUE, reason(resign(hero)), "a hero flag nobody defined");
	}

	/** Out loud, and before the transport sees it : one byte past the cap is an exception naming the counts. */
	static void oversizedIsRefused() throws Exception
	{
		int potions = (Net_Transport.MAX_PAYLOAD - Net_Codec.snapshotSize(8, 8, 0, 0)) / Net_Codec.POTION_BYTES;
		Net_Snapshot biggest = crowd(8, 8, 0, potions);
		ByteBuffer packet = Net_Codec.encode(biggest);
		is(packet.remaining() <= Net_Transport.MAX_PAYLOAD, "the biggest legal snapshot is " + packet.remaining() + " B");
		eq(biggest, Net_Codec.decode(packet), "the biggest legal snapshot round trip");
		// And the transport takes it
		Net_Loopback wire = new Net_Loopback();
		Net_Transport host = wire.open("host");
		host.send(host.resolve("client"), packet);

		Net_Snapshot tooBig = crowd(8, 8, 0, potions + 1);
		try
		{
			Net_Codec.encode(tooBig);
			is(false, "a " + Net_Codec.sizeOf(tooBig) + " B snapshot was encoded");
		}
		catch (IllegalArgumentException expected)
		{
			is(expected.getMessage().contains("1200") && expected.getMessage().contains((potions + 1) + " potions"),
					"the refusal should name the cap and the counts : " + expected.getMessage());
		}
		try
		{
			Net_Codec.encode(new Net_Message.Hello(1), ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN));
			is(false, "encoded into a little-endian buffer");
		}
		catch (IllegalArgumentException expected)
		{
		}
	}

	/** docs/online-multiplayer.md risk 4 : sized from a real 8-player run, not from the estimate. */
	static void measuredPeakFits() throws Exception
	{
		int size = Net_Codec.snapshotSize(MEASURED_PLAYERS, MEASURED_HEROES, MEASURED_MONSTERS, MEASURED_POTIONS);
		is(size <= Net_Transport.MAX_PAYLOAD, "the measured peak is " + size + " B");
		ByteBuffer packet = Net_Codec.encode(crowd(MEASURED_PLAYERS, MEASURED_HEROES, MEASURED_MONSTERS, MEASURED_POTIONS));
		eq(size, packet.remaining(), "snapshotSize agrees with the encoder");
		System.out.println("NET      measured 8-player peak : " + size + " B of " + Net_Transport.MAX_PAYLOAD
				+ ", " + (Net_Transport.MAX_PAYLOAD - size) + " B to spare");

		// Rows outlive their players (d13 -> C) : the table a host keeps at its cap, around the same crowd
		int kept = Net_Codec.snapshotSize(HostSession.MAX_ROWS, MEASURED_HEROES, MEASURED_MONSTERS, MEASURED_POTIONS);
		is(kept <= Net_Transport.MAX_PAYLOAD, "the measured peak with " + HostSession.MAX_ROWS + " score rows is " + kept + " B");
		eq(kept, Net_Codec.encode(crowd(HostSession.MAX_ROWS, MEASURED_HEROES, MEASURED_MONSTERS, MEASURED_POTIONS)).remaining(),
				"a snapshot with every row kept encodes");
	}

	/**
	 * The redundancy is the reliability : over a wire that loses one packet in ten, duplicates and
	 * reorders, every tick's buttons still reach the host, and each tick is taken once. A tick is only
	 * gone if the four packets carrying it are all lost, about one in ten thousand : on this seed, none.
	 */
	static void inputSurvivesLoss() throws Exception
	{
		Net_Loopback wire = new Net_Loopback(12);
		wire.loss = 0.1f;
		wire.duplicate = 0.1f;
		wire.reorder = 0.2f;
		Net_Transport host = wire.open("host");
		Net_Transport client = wire.open("client");
		Net_Peer toHost = client.resolve("host");

		int ticks = 600;
		int[] pressed = new int[ticks];
		int[] received = new int[ticks];
		Arrays.fill(received, -1);
		int[] copiesIgnored = { 0 };
		Random random = new Random(5);
		Net_Listener atHost = new Net_Listener()
		{
			@Override
			public void received(Net_Peer from, ByteBuffer payload)
			{
				try
				{
					Net_Input got = (Net_Input) Net_Codec.decode(payload);
					for (int tick = got.tick - got.count + 1; tick <= got.tick; tick++)
					{
						// The host takes a tick once, by its number : a copy must not jump twice
						if (received[tick] >= 0)
						{
							is(received[tick] == got.buttonsAt(tick), "two copies of tick " + tick + " disagree");
							copiesIgnored[0]++;
						}
						else
							received[tick] = got.buttonsAt(tick);
					}
				}
				catch (Net_Rejected e)
				{
					throw new AssertionError("a packet off a wire that never corrupts was rejected", e);
				}
			}
		};

		Net_Input input = new Net_Input();
		for (int tick = 0; tick < ticks; tick++)
		{
			pressed[tick] = random.nextInt(32);
			input.push(tick, pressed[tick]);
			client.send(toHost, Net_Codec.encode(input));
			host.pump(atHost);
		}
		host.pump(atHost);

		is(wire.lost > ticks / 20, "the wire should have lost about a tenth, lost " + wire.lost);
		is(copiesIgnored[0] > ticks, "most ticks arrive several times, only " + copiesIgnored[0] + " copies");
		for (int tick = 0; tick < ticks; tick++)
			eq(pressed[tick], received[tick], "the buttons of tick " + tick);
	}

	// ---------------------------------------------------------------- helpers

	static Net_Message roundTrip(Net_Message message, int size) throws Exception
	{
		ByteBuffer packet = Net_Codec.encode(message);
		eq(size, packet.remaining(), message.type() + " size");
		eq(size, Net_Codec.sizeOf(message), message.type() + " sizeOf");
		eq(Net_Codec.VERSION, packet.get(0) & 0xFF, message.type() + " starts with the version");
		CRC32 crc = new CRC32();
		crc.update(packet.array(), 0, size - 4);
		eq((int) crc.getValue(), packet.getInt(size - 4), message.type() + " ends with the IEEE CRC-32 of the rest");

		ByteBuffer shared = copy(packet);
		Net_Message decoded = Net_Codec.decode(shared);
		eq(message, decoded, message.type() + " round trip");
		// The transport reuses its buffer : the decoded message must not be reading it
		for (int i = 0; i < shared.capacity(); i++)
			shared.put(i, (byte) 0x5A);
		eq(message, decoded, message.type() + " after its buffer was overwritten");
		return decoded;
	}

	static Net_Snapshot fullSnapshot()
	{
		Net_Snapshot snapshot = new Net_Snapshot();
		snapshot.tick = 123456789;
		snapshot.storyTime = 37.25f;
		snapshot.skyScroll = -4096.5f;
		snapshot.canoeAngle = 2105 / 8192f;
		snapshot.run = 65535;
		snapshot.over = true;
		snapshot.scores.add(new Net_Snapshot.Score(1, 65535, 0));
		snapshot.scores.add(new Net_Snapshot.Score(65535, 12, 34));
		Net_Snapshot.Hero first = hero(40000, 1);
		first.x = -128f;
		first.y = 32767 / 256f;
		first.vx = -3.5f;
		first.vy = 100.25f;
		first.hp = 255;
		first.anim = 15;
		first.reverse = true;
		first.invulnerable = false;
		first.axeX = 12.75f;
		first.axeY = 1 / 256f;
		first.axeAngle = -25000 / 8192f;
		snapshot.heroes.add(first);
		Net_Snapshot.Hero second = hero(7, 65535);
		second.look = 255;
		second.x = 25.6015625f;
		second.hp = 0;
		second.anim = 3;
		second.invulnerable = true;
		second.axeAngle = 20000 / 8192f;
		snapshot.heroes.add(second);
		snapshot.monsters.add(monster(0, 0, false, 14.40625f, -1.5f));
		snapshot.monsters.add(monster(65535, 127, true, 0.5f, 3.0078125f));
		snapshot.potions.add(potion(9, 1.25f, 13.5f));
		snapshot.potions.add(potion(65535, -0.25f, 0));
		return snapshot;
	}

	/** A different value that is still legal : one up, or one down from the top. */
	static int other(int value, int max)
	{
		return value == max ? value - 1 : value + 1;
	}

	static Net_Snapshot crowd(int players, int heroes, int monsters, int potions)
	{
		Net_Snapshot snapshot = new Net_Snapshot();
		for (int i = 0; i < players; i++)
			snapshot.scores.add(new Net_Snapshot.Score(i + 1, i, i));
		for (int i = 0; i < heroes; i++)
			snapshot.heroes.add(hero(i, i + 1));
		for (int i = 0; i < monsters; i++)
			snapshot.monsters.add(monster(100 + i, i % 4, i % 2 == 0, i, 7));
		for (int i = 0; i < potions; i++)
			snapshot.potions.add(potion(1000 + i, i / 8f, 3));
		return snapshot;
	}

	static Net_Snapshot.Hero hero(int id, int player)
	{
		Net_Snapshot.Hero hero = new Net_Snapshot.Hero();
		hero.id = id;
		hero.player = player;
		hero.look = player % 6;
		hero.x = 12.75f;
		hero.y = 7.25f;
		hero.hp = 4;
		return hero;
	}

	static Net_Snapshot.Monster monster(int id, int look, boolean reverse, float x, float y)
	{
		Net_Snapshot.Monster monster = new Net_Snapshot.Monster();
		monster.id = id;
		monster.look = look;
		monster.reverse = reverse;
		monster.x = x;
		monster.y = y;
		return monster;
	}

	static Net_Snapshot.Potion potion(int id, float x, float y)
	{
		Net_Snapshot.Potion potion = new Net_Snapshot.Potion();
		potion.id = id;
		potion.x = x;
		potion.y = y;
		return potion;
	}

	static List<Net_Message> everyKind()
	{
		List<Net_Message> all = new ArrayList<Net_Message>();
		all.add(new Net_Message.Hello(0x0123456789ABCDEFL));
		all.add(new Net_Message.Join());
		all.add(new Net_Message.Keepalive());
		all.add(new Net_Message.Welcome(3, 77));
		all.add(new Net_Message.Leave(3, Net_Message.Leave.Reason.TIMEOUT));
		Net_Input input = new Net_Input();
		for (int tick = 0; tick < 6; tick++)
			input.push(tick, tick * 5 % 32);
		all.add(input);
		all.add(fullSnapshot());
		return all;
	}

	static void refusedToEncode(Consumer<Net_Snapshot> change, String what)
	{
		Net_Snapshot snapshot = crowd(1, 1, 1, 0);
		change.accept(snapshot);
		try
		{
			Net_Codec.encode(snapshot);
			is(false, what + " was encoded");
		}
		catch (IllegalArgumentException expected)
		{
		}
	}

	static void rejected(ByteBuffer packet, String what)
	{
		rejectedBy(packet);
	}

	static Net_Rejected rejectedBy(ByteBuffer packet)
	{
		int position = packet.position(), limit = packet.limit();
		try
		{
			Net_Message misread = Net_Codec.decode(packet);
			throw new AssertionError("misread as " + misread);
		}
		catch (Net_Rejected expected)
		{
			eq(position, packet.position(), "decode moved the buffer");
			eq(limit, packet.limit(), "decode changed the limit");
			return expected;
		}
	}

	static Net_Rejected.Reason reason(ByteBuffer packet)
	{
		return rejectedBy(packet).reason;
	}

	/** These bytes with a correct checksum after them. */
	static ByteBuffer signed(int... bytes)
	{
		ByteBuffer packet = ByteBuffer.allocate(bytes.length + 4);
		for (int b : bytes)
			packet.put((byte) b);
		packet.putInt(0);
		packet.flip();
		return resign(packet);
	}

	static ByteBuffer resign(ByteBuffer packet)
	{
		CRC32 crc = new CRC32();
		crc.update(packet.array(), 0, packet.limit() - 4);
		packet.putInt(packet.limit() - 4, (int) crc.getValue());
		return packet;
	}

	static ByteBuffer copy(ByteBuffer packet)
	{
		ByteBuffer copy = ByteBuffer.allocate(packet.remaining());
		copy.put(packet.duplicate());
		copy.flip();
		return copy;
	}

	static ByteBuffer slice(ByteBuffer packet, int from, int length)
	{
		ByteBuffer copy = ByteBuffer.allocate(length);
		ByteBuffer source = packet.duplicate();
		source.limit(source.position() + from + length);
		source.position(source.position() + from);
		copy.put(source);
		copy.flip();
		return copy;
	}
}
