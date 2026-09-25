package jks.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A WebRTC description in lobby packets and back (r79) : {@link #split} cuts one into
 * {@link Lobby_Message.Part}s, and a {@link Lobby_Chunks} puts the parts of each call back together.
 *
 * WHOLE OR NOTHING, like every packet : a description is handed on only when every part of it arrived
 * and they agree on how many there are. A call still missing a part {@link #TIMEOUT_MS} after its first
 * one is dropped, and counted in {@link #failed} : the tab offers again, under a new call. Parts arrive in
 * any order, twice, or never, and a copy of a part of a call already delivered is not a second delivery.
 *
 * A call is keyed on its sender as well as its number, so a stranger cannot slip a part into somebody
 * else's description. Pure and single-threaded, off an injected clock, like the codec.
 */
public final class Lobby_Chunks
{
	/** A description goes in a few packets back to back : five seconds without the rest is a lost part, not a slow one. */
	public static final long TIMEOUT_MS = 5_000;
	/** Calls being put together at once, from everyone : past it the oldest goes, so parts from strangers cannot fill memory. */
	public static final int MAX_PENDING = 256;

	/** The parts of one description, cut in order : at most {@link Lobby_Codec#CHUNK_BYTES} each. */
	public static List<Lobby_Message.Part> split(Lobby_Message.Type kind, String code, int call, String sdp)
	{
		if (!Lobby_Codec.isSdp(sdp))
			throw new IllegalArgumentException("not a description the lobby can carry");
		int parts = (sdp.length() + Lobby_Codec.CHUNK_BYTES - 1) / Lobby_Codec.CHUNK_BYTES;
		List<Lobby_Message.Part> all = new ArrayList<Lobby_Message.Part>(parts);
		for (int i = 0; i < parts; i++)
		{
			Lobby_Message.Part part = new Lobby_Message.Part(kind);
			part.code = code;
			part.call = call;
			part.part = i;
			part.parts = parts;
			int from = i * Lobby_Codec.CHUNK_BYTES, to = Math.min(sdp.length(), from + Lobby_Codec.CHUNK_BYTES);
			part.bytes = new byte[to - from];
			for (int c = from; c < to; c++)
				part.bytes[c - from] = (byte) sdp.charAt(c);
			all.add(part);
		}
		return all;
	}

	private static final class Pending
	{
		final long started;
		final byte[][] parts;
		int arrived;
		/** Delivered : the key stays until its time is up, so a late copy is known for one. */
		boolean done;

		Pending(long started, int parts)
		{
			this.started = started;
			this.parts = new byte[parts][];
		}
	}

	private final Map<String, Pending> pending = new LinkedHashMap<String, Pending>();
	/** Calls dropped with a part missing, or whose parts disagreed. */
	public int failed;

	/**
	 * Takes one part that {@code from} sent. Returns the whole description when this part completes it,
	 * null otherwise : still missing parts, a copy, or a part that disagrees with its call (then the whole
	 * call is dropped).
	 */
	public String add(String from, Lobby_Message.Part part, long now)
	{
		expire(now);
		String key = from + " " + part.type() + " " + part.code + " " + part.call;
		Pending call = pending.get(key);
		if (call == null)
		{
			while (pending.size() >= MAX_PENDING)
			{
				Iterator<Pending> oldest = pending.values().iterator();
				if (!oldest.next().done)
					failed++;
				oldest.remove();
			}
			pending.put(key, call = new Pending(now, part.parts));
		}
		if (call.done)
			return null;
		if (call.parts.length != part.parts)
		{
			// Two parts of one call that do not agree how many there are : neither can be believed
			failed++;
			call.done = true;
			return null;
		}
		if (call.parts[part.part] != null)
			return null;
		// Arrays.copyOf, not clone() : GWT cannot clone an array, and the browser build compiles this (html/)
		call.parts[part.part] = Arrays.copyOf(part.bytes, part.bytes.length);
		if (++call.arrived < call.parts.length)
			return null;

		call.done = true;
		int length = 0;
		for (byte[] bytes : call.parts)
			length += bytes.length;
		if (length > Lobby_Codec.MAX_SDP)
		{
			failed++;
			return null;
		}
		char[] sdp = new char[length];
		int at = 0;
		for (byte[] bytes : call.parts)
			for (byte b : bytes)
				sdp[at++] = (char) (b & 0xFF);
		return new String(sdp);
	}

	/** Calls still being put together, for a test and a log. */
	public int pending()
	{
		int count = 0;
		for (Pending call : pending.values())
			if (!call.done)
				count++;
		return count;
	}

	/** Drops the calls whose time is up : {@link #add} does it too, a caller with nothing arriving does it here. */
	public void expire(long now)
	{
		for (Iterator<Pending> it = pending.values().iterator(); it.hasNext();)
		{
			Pending call = it.next();
			if (now - call.started < TIMEOUT_MS)
				continue;
			if (!call.done)
				failed++;
			it.remove();
		}
	}
}
