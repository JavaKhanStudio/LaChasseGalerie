package jks.net;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The game protocol's bytes : {@link Net_Message} in, a packet out, and back. Pure : no sockets,
 * no game, no allocation beyond the decoded message.
 *
 * Every packet is
 * <pre>
 *   version u8 | type u8 | body | CRC-32 u32
 * </pre>
 * big-endian, with the version FIRST so a lobby can gate on it before reading anything else. The
 * checksum is over everything before it. UDP has its own, but a 16-bit sum lets a lot through, and
 * the socket takes bytes from anyone : a packet is read whole and checked, or not at all.
 *
 * Bodies :
 * <pre>
 *   JOIN, KEEPALIVE   (empty)
 *   HELLO      key u64, never 0
 *   WELCOME    player u16 | tick u32
 *   LEAVE      player u16 | reason u8
 *   INPUT      tick u32 | count u8 (1-4) | count x buttons u8, newest first
 *   SNAPSHOT   tick u32 | storyTime f32 | skyScroll f32 | canoeAngle s16
 *              | scores u8 | heroes u8 | monsters u8 | potions u8 | run u16 | over u8 (0 or 1)
 *              | scores   x (player u16 | score u16 | deaths u16)                               6 B
 *              | heroes   x (id u16 | player u16 | look u8 | x y vx vy s16 | hp u8
 *                            | flags u8 : anim 4 bits, reverse, invulnerable
 *                            | axeX axeY s16 | axeAngle s16)                                    21 B
 *              | monsters x (id u16 | look u8 : 7 bits, reverse | x y s16)                      7 B
 *              | potions  x (id u16 | x y s16)                                                   6 B
 * </pre>
 * Positions and velocities are s16 in 1/256 m (and m/s), clamped at +-128. Angles are wrapped to
 * one turn and sent as s16 in 1/8192 rad.
 *
 * An encoder never writes a packet past {@link Net_Transport#MAX_PAYLOAD} : it throws, naming the
 * counts, rather than hand the transport something IP would fragment. At 8 players the biggest
 * snapshot measured was 442 B (r37, `./gradlew netcensus`).
 */
public final class Net_Codec
{
	/** Bump on ANY change to a layout above. Peers of different versions do not play together. */
	public static final int VERSION = 3;

	static final int HEADER = 2, CHECKSUM = 4;
	static final int SNAPSHOT_FIXED = 4 + 4 + 4 + 2 + 4 + 2 + 1;
	public static final int SCORE_BYTES = 6, HERO_BYTES = 21, MONSTER_BYTES = 7, POTION_BYTES = 6;

	static final float POSITION_SCALE = 256f;
	static final float ANGLE_SCALE = 8192f;
	static final int ANIM_MASK = 0x0F, REVERSE = 0x10, INVULNERABLE = 0x20;
	static final int MONSTER_LOOK_MASK = 0x7F, MONSTER_REVERSE = 0x80;

	private Net_Codec()
	{
	}

	/** The bytes a message takes on the wire, header and checksum included. */
	public static int sizeOf(Net_Message message)
	{
		return HEADER + bodySize(message) + CHECKSUM;
	}

	/** The bytes a snapshot of these counts takes : what a host checks before it has built one. */
	public static int snapshotSize(int scores, int heroes, int monsters, int potions)
	{
		return HEADER + SNAPSHOT_FIXED + scores * SCORE_BYTES + heroes * HERO_BYTES + monsters * MONSTER_BYTES
				+ potions * POTION_BYTES + CHECKSUM;
	}

	/** The version byte of a packet, or -1 if it has none : for a lobby that only needs to know that. */
	public static int versionOf(ByteBuffer packet)
	{
		return packet.remaining() > 0 ? packet.get(packet.position()) & 0xFF : -1;
	}

	/** A new buffer holding the packet, ready to hand to {@link Net_Transport#send}. */
	public static ByteBuffer encode(Net_Message message)
	{
		ByteBuffer out = ByteBuffer.allocate(sizeOf(message));
		encode(message, out);
		out.flip();
		return out;
	}

	/**
	 * Writes the packet at the buffer's position and leaves the position after it.
	 *
	 * @throws IllegalArgumentException if the packet would pass {@link Net_Transport#MAX_PAYLOAD},
	 *         or a field cannot be represented : a player 0, an id past 16 bits, a NaN
	 */
	public static void encode(Net_Message message, ByteBuffer out)
	{
		int size = sizeOf(message);
		if (size > Net_Transport.MAX_PAYLOAD)
			throw new IllegalArgumentException(tooBig(message, size));
		if (out.order() != ByteOrder.BIG_ENDIAN)
			throw new IllegalArgumentException("the protocol is big-endian, the buffer is " + out.order());
		if (out.remaining() < size)
			throw new IllegalArgumentException("a " + message.type() + " takes " + size + " B, the buffer has " + out.remaining());

		int start = out.position();
		out.put((byte) VERSION);
		out.put((byte) message.type().ordinal());
		switch (message.type())
		{
			case JOIN:
			case KEEPALIVE:
				break;
			case HELLO:
				long key = ((Net_Message.Hello) message).key;
				if (key == 0)
					throw new IllegalArgumentException("a HELLO needs a rejoin key, and 0 is none");
				out.putLong(key);
				break;
			case WELCOME:
				Net_Message.Welcome welcome = (Net_Message.Welcome) message;
				putPlayer(out, welcome.player);
				out.putInt(welcome.tick);
				break;
			case LEAVE:
				Net_Message.Leave leave = (Net_Message.Leave) message;
				if (leave.reason == null)
					throw new IllegalArgumentException("a LEAVE needs a reason");
				putPlayer(out, leave.player);
				out.put((byte) leave.reason.ordinal());
				break;
			case INPUT:
				putInput(out, (Net_Input) message);
				break;
			case SNAPSHOT:
				putSnapshot(out, (Net_Snapshot) message);
				break;
		}
		out.putInt(crc32(out, start, out.position()));
	}

	/**
	 * Reads one whole packet : the buffer's remaining bytes, all of them. Consumes nothing and keeps
	 * no reference, so the transport may reuse the buffer as soon as this returns.
	 */
	public static Net_Message decode(ByteBuffer packet) throws Net_Rejected
	{
		int start = packet.position(), end = packet.limit(), length = end - start;
		// A lobby packet on the shared socket is not another version of this game : nobody to tell
		if (Lobby_Codec.isLobbyPacket(packet))
			throw new Net_Rejected(Net_Rejected.Reason.NOT_OURS, -1, "a lobby packet");
		// Nor is a connectivity check (r42) : a STUN packet starts with 0 or 1, which reads as a very old version
		if (Stun_Codec.isStunPacket(packet))
			throw new Net_Rejected(Net_Rejected.Reason.NOT_OURS, -1, "a STUN packet");
		int version = versionOf(packet);
		if (length > 0 && version != VERSION)
			throw new Net_Rejected(Net_Rejected.Reason.VERSION, version, "version " + version + ", this game speaks " + VERSION);
		if (length < HEADER + CHECKSUM)
			throw new Net_Rejected(Net_Rejected.Reason.TOO_SHORT, version, length + " B");

		ByteBuffer in = packet.duplicate();
		in.order(ByteOrder.BIG_ENDIAN);
		int bodyEnd = end - CHECKSUM;
		if (in.getInt(bodyEnd) != crc32(in, start, bodyEnd))
			throw new Net_Rejected(Net_Rejected.Reason.CHECKSUM, version, length + " B");

		in.position(start + 1);
		int code = in.get() & 0xFF;
		Net_Message.Type type = Net_Message.Type.of(code);
		if (type == null)
			throw new Net_Rejected(Net_Rejected.Reason.UNKNOWN_TYPE, version, "type " + code);
		in.limit(bodyEnd);

		Net_Message message;
		switch (type)
		{
			case HELLO:
				expect(in, 8, type, version);
				long key = in.getLong();
				if (key == 0)
					throw bad(version, "rejoin key 0");
				message = new Net_Message.Hello(key);
				break;
			case JOIN:
				message = new Net_Message.Join();
				break;
			case KEEPALIVE:
				message = new Net_Message.Keepalive();
				break;
			case WELCOME:
				expect(in, 2 + 4, type, version);
				message = new Net_Message.Welcome(getPlayer(in, version), in.getInt());
				break;
			case LEAVE:
				expect(in, 2 + 1, type, version);
				int player = getPlayer(in, version);
				int reason = in.get() & 0xFF;
				if (Net_Message.Leave.Reason.of(reason) == null)
					throw bad(version, "leave reason " + reason);
				message = new Net_Message.Leave(player, Net_Message.Leave.Reason.of(reason));
				break;
			case INPUT:
				message = getInput(in, version);
				break;
			default:
				message = getSnapshot(in, version);
				break;
		}
		if (in.hasRemaining())
			throw new Net_Rejected(Net_Rejected.Reason.LENGTH, version, "a " + type + " with " + in.remaining() + " B left over");
		return message;
	}

	// ---------------------------------------------------------------- INPUT

	static void putInput(ByteBuffer out, Net_Input input)
	{
		if (input.count < 1 || input.count > Net_Input.FRAMES)
			throw new IllegalArgumentException("an INPUT carries 1 to " + Net_Input.FRAMES + " frames, not " + input.count);
		out.putInt(input.tick);
		out.put((byte) input.count);
		for (int i = 0; i < input.count; i++)
		{
			if ((input.frames[i] & ~Net_Input.ALL_BUTTONS) != 0)
				throw new IllegalArgumentException("unknown buttons " + Integer.toBinaryString(input.frames[i]));
			out.put((byte) input.frames[i]);
		}
	}

	static Net_Input getInput(ByteBuffer in, int version) throws Net_Rejected
	{
		expectAtLeast(in, 4 + 1, Net_Message.Type.INPUT, version);
		Net_Input input = new Net_Input();
		input.tick = in.getInt();
		input.count = in.get() & 0xFF;
		if (input.count < 1 || input.count > Net_Input.FRAMES)
			throw bad(version, input.count + " input frames");
		expect(in, input.count, Net_Message.Type.INPUT, version);
		for (int i = 0; i < input.count; i++)
		{
			int buttons = in.get() & 0xFF;
			if ((buttons & ~Net_Input.ALL_BUTTONS) != 0)
				throw bad(version, "unknown buttons " + Integer.toBinaryString(buttons));
			input.frames[i] = buttons;
		}
		return input;
	}

	// ---------------------------------------------------------------- SNAPSHOT

	static void putSnapshot(ByteBuffer out, Net_Snapshot snapshot)
	{
		out.putInt(snapshot.tick);
		out.putFloat(finite(snapshot.storyTime, "storyTime"));
		out.putFloat(finite(snapshot.skyScroll, "skyScroll"));
		out.putShort(angle(snapshot.canoeAngle, "canoeAngle"));
		out.put((byte) snapshot.scores.size());
		out.put((byte) snapshot.heroes.size());
		out.put((byte) snapshot.monsters.size());
		out.put((byte) snapshot.potions.size());
		out.putShort(u16(snapshot.run, "run"));
		out.put((byte) (snapshot.over ? 1 : 0));

		for (Net_Snapshot.Score score : snapshot.scores)
		{
			putPlayer(out, score.player);
			out.putShort(u16(score.score, "score"));
			out.putShort(u16(score.deaths, "deaths"));
		}
		for (Net_Snapshot.Hero hero : snapshot.heroes)
		{
			out.putShort(u16(hero.id, "hero id"));
			putPlayer(out, hero.player);
			out.put(u8(hero.look, "hero look"));
			out.putShort(position(hero.x, "hero x"));
			out.putShort(position(hero.y, "hero y"));
			out.putShort(position(hero.vx, "hero vx"));
			out.putShort(position(hero.vy, "hero vy"));
			out.put(u8(hero.hp, "hero hp"));
			if (hero.anim < 0 || hero.anim > ANIM_MASK)
				throw new IllegalArgumentException("hero anim " + hero.anim + " does not fit 4 bits");
			out.put((byte) (hero.anim | (hero.reverse ? REVERSE : 0) | (hero.invulnerable ? INVULNERABLE : 0)));
			out.putShort(position(hero.axeX, "axe x"));
			out.putShort(position(hero.axeY, "axe y"));
			out.putShort(angle(hero.axeAngle, "axe angle"));
		}
		for (Net_Snapshot.Monster monster : snapshot.monsters)
		{
			out.putShort(u16(monster.id, "monster id"));
			if (monster.look < 0 || monster.look > MONSTER_LOOK_MASK)
				throw new IllegalArgumentException("monster look " + monster.look + " does not fit 7 bits");
			out.put((byte) (monster.look | (monster.reverse ? MONSTER_REVERSE : 0)));
			out.putShort(position(monster.x, "monster x"));
			out.putShort(position(monster.y, "monster y"));
		}
		for (Net_Snapshot.Potion potion : snapshot.potions)
		{
			out.putShort(u16(potion.id, "potion id"));
			out.putShort(position(potion.x, "potion x"));
			out.putShort(position(potion.y, "potion y"));
		}
	}

	static Net_Snapshot getSnapshot(ByteBuffer in, int version) throws Net_Rejected
	{
		expectAtLeast(in, SNAPSHOT_FIXED, Net_Message.Type.SNAPSHOT, version);
		Net_Snapshot snapshot = new Net_Snapshot();
		snapshot.tick = in.getInt();
		snapshot.storyTime = getFinite(in, version, "storyTime");
		snapshot.skyScroll = getFinite(in, version, "skyScroll");
		snapshot.canoeAngle = in.getShort() / ANGLE_SCALE;
		int scores = in.get() & 0xFF, heroes = in.get() & 0xFF, monsters = in.get() & 0xFF, potions = in.get() & 0xFF;
		snapshot.run = in.getShort() & 0xFFFF;
		int over = in.get() & 0xFF;
		if (over > 1)
			throw bad(version, "over " + over);
		snapshot.over = over == 1;
		// The counts are checked against the bytes before a single entity is allocated
		expect(in, scores * SCORE_BYTES + heroes * HERO_BYTES + monsters * MONSTER_BYTES + potions * POTION_BYTES,
				Net_Message.Type.SNAPSHOT, version);

		for (int i = 0; i < scores; i++)
			snapshot.scores.add(new Net_Snapshot.Score(getPlayer(in, version), in.getShort() & 0xFFFF, in.getShort() & 0xFFFF));
		for (int i = 0; i < heroes; i++)
		{
			Net_Snapshot.Hero hero = new Net_Snapshot.Hero();
			hero.id = in.getShort() & 0xFFFF;
			hero.player = getPlayer(in, version);
			hero.look = in.get() & 0xFF;
			hero.x = in.getShort() / POSITION_SCALE;
			hero.y = in.getShort() / POSITION_SCALE;
			hero.vx = in.getShort() / POSITION_SCALE;
			hero.vy = in.getShort() / POSITION_SCALE;
			hero.hp = in.get() & 0xFF;
			int flags = in.get() & 0xFF;
			if ((flags & ~(ANIM_MASK | REVERSE | INVULNERABLE)) != 0)
				throw bad(version, "hero flags " + Integer.toBinaryString(flags));
			hero.anim = flags & ANIM_MASK;
			hero.reverse = (flags & REVERSE) != 0;
			hero.invulnerable = (flags & INVULNERABLE) != 0;
			hero.axeX = in.getShort() / POSITION_SCALE;
			hero.axeY = in.getShort() / POSITION_SCALE;
			hero.axeAngle = in.getShort() / ANGLE_SCALE;
			snapshot.heroes.add(hero);
		}
		for (int i = 0; i < monsters; i++)
		{
			Net_Snapshot.Monster monster = new Net_Snapshot.Monster();
			monster.id = in.getShort() & 0xFFFF;
			int look = in.get() & 0xFF;
			monster.look = look & MONSTER_LOOK_MASK;
			monster.reverse = (look & MONSTER_REVERSE) != 0;
			monster.x = in.getShort() / POSITION_SCALE;
			monster.y = in.getShort() / POSITION_SCALE;
			snapshot.monsters.add(monster);
		}
		for (int i = 0; i < potions; i++)
		{
			Net_Snapshot.Potion potion = new Net_Snapshot.Potion();
			potion.id = in.getShort() & 0xFFFF;
			potion.x = in.getShort() / POSITION_SCALE;
			potion.y = in.getShort() / POSITION_SCALE;
			snapshot.potions.add(potion);
		}
		return snapshot;
	}

	// ---------------------------------------------------------------- fields

	static int bodySize(Net_Message message)
	{
		switch (message.type())
		{
			case HELLO:
				return 8;
			case WELCOME:
				return 2 + 4;
			case LEAVE:
				return 2 + 1;
			case INPUT:
				return 4 + 1 + Math.max(0, ((Net_Input) message).count);
			case SNAPSHOT:
				Net_Snapshot snapshot = (Net_Snapshot) message;
				return snapshotSize(snapshot.scores.size(), snapshot.heroes.size(), snapshot.monsters.size(), snapshot.potions.size())
						- HEADER - CHECKSUM;
			default:
				return 0;
		}
	}

	static String tooBig(Net_Message message, int size)
	{
		String what = message.type().toString();
		if (message instanceof Net_Snapshot)
		{
			Net_Snapshot snapshot = (Net_Snapshot) message;
			what = "snapshot of " + snapshot.scores.size() + " players, " + snapshot.heroes.size() + " heroes, "
					+ snapshot.monsters.size() + " monsters and " + snapshot.potions.size() + " potions";
		}
		return "a " + what + " is " + size + " B, past the " + Net_Transport.MAX_PAYLOAD + " B a packet may be before IP fragments it";
	}

	/** Out of range is clamped, not refused : something flung off the world is still drawn at its edge. */
	static short position(float value, String what)
	{
		long q = Math.round((double) finite(value, what) * POSITION_SCALE);
		return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, q));
	}

	/** Box2D angles run past one turn : wrap to [-pi, pi) first, which then always fits. */
	static short angle(float value, String what)
	{
		double radians = finite(value, what);
		radians -= 2 * Math.PI * Math.floor((radians + Math.PI) / (2 * Math.PI));
		long q = Math.round(radians * ANGLE_SCALE);
		return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, q));
	}

	static float finite(float value, String what)
	{
		if (Float.isNaN(value) || Float.isInfinite(value))
			throw new IllegalArgumentException(what + " is " + value);
		return value;
	}

	static short u16(int value, String what)
	{
		if (value < 0 || value > 0xFFFF)
			throw new IllegalArgumentException(what + " " + value + " does not fit 16 bits");
		return (short) value;
	}

	static byte u8(int value, String what)
	{
		if (value < 0 || value > 0xFF)
			throw new IllegalArgumentException(what + " " + value + " does not fit 8 bits");
		return (byte) value;
	}

	/** Player 0 is nobody : it is what an unset field reads, so it is never a real player. */
	static void putPlayer(ByteBuffer out, int player)
	{
		if (player < 1 || player > 0xFFFF)
			throw new IllegalArgumentException("player " + player + " is not a PlayerId");
		out.putShort((short) player);
	}

	static int getPlayer(ByteBuffer in, int version) throws Net_Rejected
	{
		int player = in.getShort() & 0xFFFF;
		if (player == 0)
			throw bad(version, "player 0");
		return player;
	}

	static float getFinite(ByteBuffer in, int version, String what) throws Net_Rejected
	{
		float value = in.getFloat();
		if (Float.isNaN(value) || Float.isInfinite(value))
			throw bad(version, what + " is " + value);
		return value;
	}

	static void expect(ByteBuffer in, int bytes, Net_Message.Type type, int version) throws Net_Rejected
	{
		if (in.remaining() != bytes)
			throw new Net_Rejected(Net_Rejected.Reason.LENGTH, version, "a " + type + " body needs " + bytes + " B here, got " + in.remaining());
	}

	static void expectAtLeast(ByteBuffer in, int bytes, Net_Message.Type type, int version) throws Net_Rejected
	{
		if (in.remaining() < bytes)
			throw new Net_Rejected(Net_Rejected.Reason.LENGTH, version, "a " + type + " body needs at least " + bytes + " B, got " + in.remaining());
	}

	static Net_Rejected bad(int version, String detail)
	{
		return new Net_Rejected(Net_Rejected.Reason.BAD_VALUE, version, detail);
	}

	// ---------------------------------------------------------------- CRC-32

	/** IEEE CRC-32, as java.util.zip computes it. Written out because a browser build has no java.util.zip. */
	static int crc32(ByteBuffer bytes, int from, int to)
	{
		int crc = 0xFFFFFFFF;
		for (int i = from; i < to; i++)
			crc = CRC_TABLE[(crc ^ bytes.get(i)) & 0xFF] ^ (crc >>> 8);
		return ~crc;
	}

	private static final int[] CRC_TABLE = new int[256];
	static
	{
		for (int n = 0; n < 256; n++)
		{
			int c = n;
			for (int k = 0; k < 8; k++)
				c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
			CRC_TABLE[n] = c;
		}
	}
}
