package jks.input;

/**
 * Who a player is. Not what they hold (phase 0.3 of docs/online-multiplayer.md).
 *
 * Until now a player WAS a Controller object, with null standing for the keyboard, and a remote
 * player has no Controller and never will. Everything that is about a PLAYER — their hero, their
 * score label, their spawn, their death — is keyed on one of these instead. A Controller only
 * lives at the local input edge now (IKM_Game_Keyboard, IKM_Game_XBoxController), as the way this
 * machine's devices reach a hero.
 *
 * It is a number and nothing else, because d5 -> A says online is ONE PLAYER PER MACHINE : an id
 * is a peer, not a peer plus a device slot. Online it will be the peer's number, handed out by the
 * host ; offline GVars_Controller mints them in join order, and a machine with a keyboard and
 * three pads simply holds four of them — local co-op is the offline mode, not a second dimension.
 *
 * The number is also what the score label says, so Player 2 is Player 2 everywhere.
 *
 * Comparable so the roster can be walked in a settled order : a run replayed from a seed, and
 * later a snapshot sent to every client, must not depend on a hash.
 */
public final class PlayerId implements Comparable<PlayerId>
{
	private final int number ;

	private PlayerId(int number)
	{this.number = number ;}

	/** The player known by this number — the host's numbering, once there is a host. */
	public static PlayerId of(int number)
	{return new PlayerId(number) ;}

	/** 1 for the first player to join, and what their label reads. */
	public int number()
	{return number ;}

	@Override
	public boolean equals(Object other)
	{return other instanceof PlayerId && ((PlayerId) other).number == number ;}

	@Override
	public int hashCode()
	{return number ;}

	@Override
	public int compareTo(PlayerId other)
	{return Integer.compare(number, other.number) ;}

	@Override
	public String toString()
	{return "Player " + number ;}
}
