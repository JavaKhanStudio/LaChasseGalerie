package jks.online;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jks.net.Net_Snapshot;

/**
 * The client's side of phase 1.3 : what a machine that is not simulating knows of the run, built
 * from snapshots alone. A CLIENT OWNS NO BOX2D WORLD (docs/online-multiplayer.md section 2), and
 * this class is the proof : it imports no body, no GVars_* and no asset, so it runs where the game
 * cannot, in `./gradlew nettest` and in a browser build.
 *
 * {@link #apply} walks a snapshot by entity id : an id it has not seen is created, an id it holds
 * is moved, and an id the snapshot no longer names is destroyed. Each entity keeps its object
 * across snapshots, so whatever a {@link Listener} hangs on it at creation — a sprite, its
 * animation clock — lives exactly as long as the entity does. Ids are one namespace across kinds on
 * the host (GVars_Game.newEntityId), and a snapshot that names one twice is refused whole.
 *
 * UDP reorders and duplicates : a snapshot no newer than the last one applied changes nothing.
 * Interpolating between two snapshots is phase 1.4's and 1.5's, not this class's.
 */
public final class Snapshot_Mirror
{
	/** Told as entities come and go, so the renderer (phase 1.5) creates and drops their sprites. */
	public interface Listener
	{
		default void created(Net_Snapshot.Hero hero) {}
		default void destroyed(Net_Snapshot.Hero hero) {}
		default void created(Net_Snapshot.Monster monster) {}
		default void destroyed(Net_Snapshot.Monster monster) {}
		default void created(Net_Snapshot.Potion potion) {}
		default void destroyed(Net_Snapshot.Potion potion) {}
	}

	private static final Listener NOBODY = new Listener() {};

	private final Listener listener;

	/** The tick of the last snapshot applied, -1 before the first. */
	public int tick = -1;
	public float storyTime, skyScroll, canoeAngle;

	private final List<Net_Snapshot.Score> scores = new ArrayList<Net_Snapshot.Score>();
	/** By id, in the order the last snapshot listed them, which is the order the host draws them in. */
	private final Map<Integer, Net_Snapshot.Hero> heroes = new LinkedHashMap<Integer, Net_Snapshot.Hero>();
	private final Map<Integer, Net_Snapshot.Monster> monsters = new LinkedHashMap<Integer, Net_Snapshot.Monster>();
	private final Map<Integer, Net_Snapshot.Potion> potions = new LinkedHashMap<Integer, Net_Snapshot.Potion>();

	public Snapshot_Mirror()
	{
		this(NOBODY);
	}

	public Snapshot_Mirror(Listener listener)
	{
		this.listener = listener;
	}

	/**
	 * Makes this mirror what the snapshot says.
	 *
	 * @return false, having changed nothing, if the snapshot is stale (its tick is not newer than the
	 *         last one applied) or names an id twice
	 */
	public boolean apply(Net_Snapshot snapshot)
	{
		if (snapshot.tick <= tick || !idsAreUnique(snapshot))
			return false;

		tick = snapshot.tick;
		storyTime = snapshot.storyTime;
		skyScroll = snapshot.skyScroll;
		canoeAngle = snapshot.canoeAngle;

		scores.clear();
		for (Net_Snapshot.Score score : snapshot.scores)
			scores.add(new Net_Snapshot.Score(score.player, score.score, score.deaths));

		// Rebuilt in the snapshot's order, which is the host's drawing order : an entity keeps its
		// object across snapshots, not its place in the list
		Map<Integer, Net_Snapshot.Hero> oldHeroes = new LinkedHashMap<Integer, Net_Snapshot.Hero>(heroes);
		Map<Integer, Net_Snapshot.Monster> oldMonsters = new LinkedHashMap<Integer, Net_Snapshot.Monster>(monsters);
		Map<Integer, Net_Snapshot.Potion> oldPotions = new LinkedHashMap<Integer, Net_Snapshot.Potion>(potions);
		heroes.clear();
		monsters.clear();
		potions.clear();
		List<Object> created = new ArrayList<Object>();

		for (Net_Snapshot.Hero from : snapshot.heroes)
		{
			Net_Snapshot.Hero hero = oldHeroes.remove(from.id);
			if (hero == null)
				created.add(hero = new Net_Snapshot.Hero());
			copy(from, hero);
			heroes.put(from.id, hero);
		}
		for (Net_Snapshot.Monster from : snapshot.monsters)
		{
			Net_Snapshot.Monster monster = oldMonsters.remove(from.id);
			if (monster == null)
				created.add(monster = new Net_Snapshot.Monster());
			monster.id = from.id;
			monster.look = from.look;
			monster.x = from.x;
			monster.y = from.y;
			monster.reverse = from.reverse;
			monsters.put(from.id, monster);
		}
		for (Net_Snapshot.Potion from : snapshot.potions)
		{
			Net_Snapshot.Potion potion = oldPotions.remove(from.id);
			if (potion == null)
				created.add(potion = new Net_Snapshot.Potion());
			potion.id = from.id;
			potion.x = from.x;
			potion.y = from.y;
			potions.put(from.id, potion);
		}

		// What is left over vanished. Destroyed before anything is created : after a wrap the host may
		// hand a dead entity's id to one of another kind, and a renderer keyed on ids must see it go first
		for (Net_Snapshot.Hero hero : oldHeroes.values())
			listener.destroyed(hero);
		for (Net_Snapshot.Monster monster : oldMonsters.values())
			listener.destroyed(monster);
		for (Net_Snapshot.Potion potion : oldPotions.values())
			listener.destroyed(potion);
		for (Object entity : created)
		{
			if (entity instanceof Net_Snapshot.Hero)
				listener.created((Net_Snapshot.Hero) entity);
			else if (entity instanceof Net_Snapshot.Monster)
				listener.created((Net_Snapshot.Monster) entity);
			else
				listener.created((Net_Snapshot.Potion) entity);
		}
		return true;
	}

	private static boolean idsAreUnique(Net_Snapshot snapshot)
	{
		Set<Integer> seen = new HashSet<Integer>();
		for (Net_Snapshot.Hero hero : snapshot.heroes)
			if (!seen.add(hero.id))
				return false;
		for (Net_Snapshot.Monster monster : snapshot.monsters)
			if (!seen.add(monster.id))
				return false;
		for (Net_Snapshot.Potion potion : snapshot.potions)
			if (!seen.add(potion.id))
				return false;
		return true;
	}

	private static void copy(Net_Snapshot.Hero from, Net_Snapshot.Hero to)
	{
		to.id = from.id;
		to.player = from.player;
		to.look = from.look;
		to.x = from.x;
		to.y = from.y;
		to.vx = from.vx;
		to.vy = from.vy;
		to.hp = from.hp;
		to.anim = from.anim;
		to.reverse = from.reverse;
		to.invulnerable = from.invulnerable;
		to.axeX = from.axeX;
		to.axeY = from.axeY;
		to.axeAngle = from.axeAngle;
	}

	public Net_Snapshot.Hero hero(int id)
	{
		return heroes.get(id);
	}

	public Net_Snapshot.Monster monster(int id)
	{
		return monsters.get(id);
	}

	public Net_Snapshot.Potion potion(int id)
	{
		return potions.get(id);
	}

	/** Read-only views, in the order the host sent them. */
	public Collection<Net_Snapshot.Hero> heroes()
	{
		return Collections.unmodifiableCollection(heroes.values());
	}

	public Collection<Net_Snapshot.Monster> monsters()
	{
		return Collections.unmodifiableCollection(monsters.values());
	}

	public Collection<Net_Snapshot.Potion> potions()
	{
		return Collections.unmodifiableCollection(potions.values());
	}

	public List<Net_Snapshot.Score> scores()
	{
		return Collections.unmodifiableList(scores);
	}

	/** Everything this mirror holds, as the snapshot that would make a fresh mirror the same. */
	public Net_Snapshot toSnapshot()
	{
		Net_Snapshot snapshot = new Net_Snapshot();
		snapshot.tick = tick;
		snapshot.storyTime = storyTime;
		snapshot.skyScroll = skyScroll;
		snapshot.canoeAngle = canoeAngle;
		snapshot.scores.addAll(scores);
		snapshot.heroes.addAll(heroes.values());
		snapshot.monsters.addAll(monsters.values());
		snapshot.potions.addAll(potions.values());
		return snapshot;
	}
}
