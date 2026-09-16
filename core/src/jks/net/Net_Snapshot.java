package jks.net;

import java.util.ArrayList;
import java.util.List;

/**
 * Host to clients, 20 times a second : everything a client needs to draw the run, and nothing it
 * would need to simulate it. A client owns no physics world (docs/online-multiplayer.md section 2).
 *
 * Values are in the game's own units - Box2D metres, radians, seconds - so the view that fills this
 * (phase 1.3, r38) copies them straight off the bodies. {@link Net_Codec} quantizes them : positions
 * and velocities to 1/256, which is under half a world pixel, and positions past +-128 m are clamped.
 * Nothing in the world is ever that far out except what should not be sent at all : an entity that
 * has left the world is the view's to leave out, not the codec's to carry (n7).
 *
 * Sizes on the wire, measured against an 8-player headless run (r37, `./gradlew netcensus`) :
 * a 24 B header, 6 B per player, 21 B per hero with its axe, 7 B per monster and 6 B per potion.
 */
public final class Net_Snapshot extends Net_Message
{
	/** The simulation tick this is the state after. */
	public int tick;
	/** GVars_Story's clock : what the music, the take-off and the spawns are timed on. */
	public float storyTime;
	/** How far the sky has scrolled since the run started, in world units. */
	public float skyScroll;
	/** The canoe is static except for its tilt at take-off. */
	public float canoeAngle;

	/** Every player who has joined this run, alive or waiting to rejoin : the score table. */
	public final List<Score> scores = new ArrayList<Score>();
	public final List<Hero> heroes = new ArrayList<Hero>();
	public final List<Monster> monsters = new ArrayList<Monster>();
	public final List<Potion> potions = new ArrayList<Potion>();

	@Override
	public Type type()
	{
		return Type.SNAPSHOT;
	}

	/** One row of the score table. A player keeps it across deaths, like the ScoreLabel it mirrors. */
	public static final class Score
	{
		public int player, score, deaths;

		public Score()
		{
		}

		public Score(int player, int score, int deaths)
		{
			this.player = player;
			this.score = score;
			this.deaths = deaths;
		}

		@Override
		public boolean equals(Object other)
		{
			if (!(other instanceof Score))
				return false;
			Score that = (Score) other;
			return that.player == player && that.score == score && that.deaths == deaths;
		}

		@Override
		public int hashCode()
		{
			return player;
		}

		@Override
		public String toString()
		{
			return "P" + player + " score " + score + " deaths " + deaths;
		}
	}

	/** A living hero and the axe on its joint. */
	public static final class Hero
	{
		/** The entity : a hero that dies and rejoins is a new one, for the same player. */
		public int id;
		public int player;
		/** Which hero model it wears : its colour. */
		public int look;
		public float x, y, vx, vy;
		public int hp;
		/** An animation state ordinal, 0 to 15. */
		public int anim;
		/** Facing right. */
		public boolean reverse;
		/** The blink after a hit or a spawn. */
		public boolean invulnerable;
		public float axeX, axeY, axeAngle;

		@Override
		public boolean equals(Object other)
		{
			if (!(other instanceof Hero))
				return false;
			Hero that = (Hero) other;
			return that.id == id && that.player == player && that.look == look
					&& that.x == x && that.y == y && that.vx == vx && that.vy == vy
					&& that.hp == hp && that.anim == anim && that.reverse == reverse && that.invulnerable == invulnerable
					&& that.axeX == axeX && that.axeY == axeY && that.axeAngle == axeAngle;
		}

		@Override
		public int hashCode()
		{
			return id;
		}

		@Override
		public String toString()
		{
			return "hero " + id + " P" + player + " look " + look + " at " + x + "," + y + " vel " + vx + "," + vy
					+ " hp " + hp + " anim " + anim + (reverse ? " reversed" : "") + (invulnerable ? " invulnerable" : "")
					+ " axe " + axeX + "," + axeY + " @" + axeAngle;
		}
	}

	public static final class Monster
	{
		public int id;
		/** Which monster model, 0 to 127. */
		public int look;
		public float x, y;
		public boolean reverse;

		@Override
		public boolean equals(Object other)
		{
			if (!(other instanceof Monster))
				return false;
			Monster that = (Monster) other;
			return that.id == id && that.look == look && that.x == x && that.y == y && that.reverse == reverse;
		}

		@Override
		public int hashCode()
		{
			return id;
		}

		@Override
		public String toString()
		{
			return "monster " + id + " look " + look + " at " + x + "," + y + (reverse ? " reversed" : "");
		}
	}

	public static final class Potion
	{
		public int id;
		public float x, y;

		@Override
		public boolean equals(Object other)
		{
			if (!(other instanceof Potion))
				return false;
			Potion that = (Potion) other;
			return that.id == id && that.x == x && that.y == y;
		}

		@Override
		public int hashCode()
		{
			return id;
		}

		@Override
		public String toString()
		{
			return "potion " + id + " at " + x + "," + y;
		}
	}

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof Net_Snapshot))
			return false;
		Net_Snapshot that = (Net_Snapshot) other;
		return that.tick == tick && that.storyTime == storyTime && that.skyScroll == skyScroll && that.canoeAngle == canoeAngle
				&& that.scores.equals(scores) && that.heroes.equals(heroes) && that.monsters.equals(monsters) && that.potions.equals(potions);
	}

	@Override
	public int hashCode()
	{
		return tick;
	}

	@Override
	public String toString()
	{
		return "SNAPSHOT tick " + tick + " story " + storyTime + " sky " + skyScroll + " canoe " + canoeAngle
				+ " " + scores + " " + heroes + " " + monsters + " " + potions;
	}
}
