package jks.online;

import jks.input.PlayerId;
import jks.net.Net_Snapshot;

/**
 * What a {@link HostSession} needs from the game it hosts, and nothing more : mint a player, give
 * one a hero, press its buttons, take it out, and read the world.
 *
 * It is an interface for two reasons. The session's rules - who is in, which input frame lands on
 * which tick, who gets told what - are checked in `./gradlew nettest` against a toy world, in
 * milliseconds and with no assets. And nothing here says the host has a screen or a hero of its
 * own : a dedicated server is this same interface on a headless loop (docs/online-multiplayer.md
 * section 6, "a door left open"). {@link Game_Simulation} is the real game behind it.
 *
 * Stepping the world is not here : the loop that owns the clock - the headless runner, or
 * Main_Game's accumulator - hands {@link HostSession#tick} the step to run.
 */
public interface Host_Simulation
{
	/** A player no one has been this run, from the same numbering local devices use. */
	PlayerId newPlayer();

	/** Whether this player has a living hero right now. A dead one waits for a JOIN. */
	boolean hasHero(PlayerId player);

	/** Gives the player a hero : the online form of the first key press. The score row is kept across deaths. */
	void spawn(PlayerId player);

	/**
	 * The buttons for the tick about to be simulated. {@code held} is what is down (only LEFT and RIGHT
	 * mean anything held), {@code pressed} the one-shot buttons pressed since the last call (JUMP,
	 * SWING_LEFT, SWING_RIGHT) : each fires once. Net_Input's bits. Ignored for a player with no hero.
	 */
	void press(PlayerId player, int held, int pressed);

	/** The player left the run : their hero goes, with no death counted, and so does their score row. */
	void remove(PlayerId player);

	/** The world as it stands after the given tick. Reads, changes nothing. */
	Net_Snapshot read(int tick);
}
