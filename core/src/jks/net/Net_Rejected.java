package jks.net;

/**
 * A packet {@link Net_Codec} refused to read. Nothing of it was used.
 *
 * The transport accepts bytes from anyone (the channel is never connect()ed, because an unknown
 * address is how a punch arrives), so a rejected packet is an ordinary event : drop it and go on.
 * {@link Reason#VERSION} is the one worth telling a person about.
 */
public class Net_Rejected extends Exception
{
	private static final long serialVersionUID = 1L;

	public enum Reason
	{
		/** Not even a header and a checksum. */
		TOO_SHORT,
		/** Another protocol version : {@link Net_Rejected#version} says which. Checked before anything else is read. */
		VERSION,
		/** A type byte this version does not know. */
		UNKNOWN_TYPE,
		/** The bytes are not the ones that were sent : corrupted, truncated, or not ours. */
		CHECKSUM,
		/** The body is not the length its type and counts say it must be. */
		LENGTH,
		/** A field holds something no encoder writes : an unknown bit, a player 0, a NaN. */
		BAD_VALUE
	}

	public final Reason reason;
	/** The version byte the packet carried, or -1 when it was too short to have one. */
	public final int version;

	Net_Rejected(Reason reason, int version, String detail)
	{
		super(reason + " : " + detail);
		this.reason = reason;
		this.version = version;
	}
}
