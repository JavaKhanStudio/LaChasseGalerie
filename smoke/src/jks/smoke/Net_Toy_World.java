package jks.smoke;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jks.input.PlayerId;
import jks.net.Net_Input;
import jks.net.Net_Snapshot;
import jks.online.Host_Simulation;

/**
 * A world small enough to reason about, behind the same interface the real game sits behind : so
 * nettest can hold HostSession and ClientSession to their rules in milliseconds, with no Box2D, no
 * assets and no GVars_*.
 *
 * A hero walks 0.05 m a tick while LEFT or RIGHT is held and jumps on JUMP (a parabola that lands on
 * y = 0) ; one monster circles, so interpolation is tested on a curve ; a potion lives 90 ticks out of
 * every 120, so entities come and go. Every press is logged with the tick it landed on.
 */
class Net_Toy_World implements Host_Simulation
{
	static final float WALK = 0.05f, JUMP_SPEED = 0.2f, GRAVITY = 0.01f;

	static final class Hero
	{
		final int id;
		final PlayerId player;
		float x = 2, y, vy;
		int held, pressed;

		Hero(int id, PlayerId player)
		{
			this.id = id;
			this.player = player;
		}
	}

	int tick, nextId = 1, nextPlayer = 1;
	final Map<PlayerId, Hero> heroes = new LinkedHashMap<PlayerId, Hero>();
	final Map<PlayerId, int[]> scores = new TreeMap<PlayerId, int[]>();
	/** Per player, "tick:bits" for every press that fired, in order. */
	final Map<PlayerId, List<String>> presses = new LinkedHashMap<PlayerId, List<String>>();
	final List<PlayerId> removed = new ArrayList<PlayerId>();
	final List<PlayerId> forgotten = new ArrayList<PlayerId>();
	int monsterId = nextId++, potionId;

	@Override
	public PlayerId newPlayer()
	{
		return PlayerId.of(nextPlayer++);
	}

	@Override
	public boolean hasHero(PlayerId player)
	{
		return heroes.containsKey(player);
	}

	@Override
	public void spawn(PlayerId player)
	{
		heroes.put(player, new Hero(nextId++, player));
		scores.putIfAbsent(player, new int[2]);
	}

	@Override
	public void press(PlayerId player, int held, int pressed)
	{
		Hero hero = heroes.get(player);
		if (hero == null)
			return;
		hero.held = held;
		hero.pressed |= pressed;
		if (pressed != 0)
			presses.computeIfAbsent(player, p -> new ArrayList<String>()).add(tick + ":" + pressed);
	}

	@Override
	public void remove(PlayerId player)
	{
		heroes.remove(player);
		removed.add(player);
	}

	@Override
	public void forget(PlayerId player)
	{
		scores.remove(player);
		forgotten.add(player);
	}

	/** A death : the hero goes, the score row stays, like the game. */
	void kill(PlayerId player)
	{
		heroes.remove(player);
		scores.get(player)[1]++;
	}

	void step()
	{
		for (Hero hero : heroes.values())
		{
			if ((hero.held & Net_Input.RIGHT) != 0)
				hero.x += WALK;
			else if ((hero.held & Net_Input.LEFT) != 0)
				hero.x -= WALK;
			if ((hero.pressed & Net_Input.JUMP) != 0 && hero.y == 0)
				hero.vy = JUMP_SPEED;
			hero.pressed = 0;
			hero.y += hero.vy;
			hero.vy -= GRAVITY;
			if (hero.y <= 0)
			{
				hero.y = 0;
				hero.vy = 0;
			}
		}
		tick++;
		if (tick % 120 == 0)
			potionId = nextId++;
		else if (tick % 120 == 90)
			potionId = 0;
	}

	@Override
	public Net_Snapshot read(int tick)
	{
		Net_Snapshot snapshot = new Net_Snapshot();
		snapshot.tick = tick;
		snapshot.storyTime = tick / 60f;
		for (Map.Entry<PlayerId, int[]> score : scores.entrySet())
			snapshot.scores.add(new Net_Snapshot.Score(score.getKey().number(), score.getValue()[0], score.getValue()[1]));
		for (Hero from : heroes.values())
		{
			Net_Snapshot.Hero hero = new Net_Snapshot.Hero();
			hero.id = from.id;
			hero.player = from.player.number();
			hero.x = from.x;
			hero.y = from.y;
			hero.vy = from.vy;
			hero.hp = 3;
			hero.axeX = from.x + 0.5f;
			hero.axeY = from.y;
			hero.axeAngle = (float) Math.IEEEremainder(tick * 0.1, 2 * Math.PI);
			snapshot.heroes.add(hero);
		}
		Net_Snapshot.Monster monster = new Net_Snapshot.Monster();
		monster.id = monsterId;
		monster.x = 5 + 3 * (float) Math.cos(tick * 0.03);
		monster.y = 5 + 3 * (float) Math.sin(tick * 0.03);
		snapshot.monsters.add(monster);
		if (potionId != 0)
		{
			Net_Snapshot.Potion potion = new Net_Snapshot.Potion();
			potion.id = potionId;
			potion.x = 7;
			potion.y = 9 - (tick % 120) * 0.05f;
			snapshot.potions.add(potion);
		}
		return snapshot;
	}
}
