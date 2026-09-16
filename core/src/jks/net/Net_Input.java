package jks.net;

import java.util.Arrays;

/**
 * Client to host, every tick : what this machine's player is pressing.
 *
 * Every packet carries the newest frame AND the three before it, newest first. One lost packet in
 * four costs nothing, so input needs no acknowledgement and no resend (docs/online-multiplayer.md
 * section 4). The host applies each tick once, by its number, and ignores the copies — which is
 * also what keeps the one-shot buttons (jump, swing) from firing twice.
 *
 * The bits are what Player_Inputs holds, and nothing about which device pressed them : online a
 * machine is one player (d5 -> A), whatever local device is touched.
 */
public final class Net_Input extends Net_Message
{
	public static final int LEFT = 1, RIGHT = 2, JUMP = 4, SWING_LEFT = 8, SWING_RIGHT = 16;
	static final int ALL_BUTTONS = LEFT | RIGHT | JUMP | SWING_LEFT | SWING_RIGHT;

	/** The newest frame and the three before it. */
	public static final int FRAMES = 4;

	/** The tick of frames[0]. frames[i] is tick - i. */
	public int tick;
	/** Newest first. Fewer than {@link #FRAMES} only in the first ticks of a session. */
	public int count;
	public final int[] frames = new int[FRAMES];

	@Override
	public Type type()
	{
		return Type.INPUT;
	}

	/** Pushes this tick's buttons in front, dropping the oldest frame. */
	public void push(int tick, int buttons)
	{
		System.arraycopy(frames, 0, frames, 1, FRAMES - 1);
		frames[0] = buttons;
		this.tick = tick;
		count = Math.min(count + 1, FRAMES);
	}

	/** The buttons held at this tick, or -1 when the packet does not carry it. */
	public int buttonsAt(int tick)
	{
		int age = this.tick - tick;
		return age >= 0 && age < count ? frames[age] : -1;
	}

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof Net_Input))
			return false;
		Net_Input that = (Net_Input) other;
		return that.tick == tick && that.count == count
				&& Arrays.equals(Arrays.copyOf(frames, count), Arrays.copyOf(that.frames, that.count));
	}

	@Override
	public int hashCode()
	{
		return tick * 31 + count;
	}

	@Override
	public String toString()
	{
		return "INPUT tick " + tick + " " + Arrays.toString(Arrays.copyOf(frames, count));
	}
}
