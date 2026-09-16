package jks.smoke;

import static jks.smoke.Net_Run.eq;
import static jks.smoke.Net_Run.is;

import java.util.ArrayList;
import java.util.List;

import jks.net.Net_Snapshot;
import jks.online.Snapshot_Mirror;

/**
 * nettest's checks for the client's mirror (phase 1.3, r38) : Snapshot_Mirror alone, no game, no world.
 * `./gradlew netmirror` holds it against a real run ; these hold it to its rules one at a time.
 */
class Net_Mirror_Checks
{
	/** Records every create and destroy, in order, as "+hero 3" and "-monster 9". */
	static final class Log implements Snapshot_Mirror.Listener
	{
		final List<String> events = new ArrayList<String>();

		@Override public void created(Net_Snapshot.Hero hero) { events.add("+hero " + hero.id); }
		@Override public void destroyed(Net_Snapshot.Hero hero) { events.add("-hero " + hero.id); }
		@Override public void created(Net_Snapshot.Monster monster) { events.add("+monster " + monster.id); }
		@Override public void destroyed(Net_Snapshot.Monster monster) { events.add("-monster " + monster.id); }
		@Override public void created(Net_Snapshot.Potion potion) { events.add("+potion " + potion.id); }
		@Override public void destroyed(Net_Snapshot.Potion potion) { events.add("-potion " + potion.id); }

		List<String> take()
		{
			List<String> taken = new ArrayList<String>(events);
			events.clear();
			return taken;
		}
	}

	/** Create what is new, move what exists, destroy what vanished — and a moved entity is the same object. */
	static void createMoveDestroy() throws Exception
	{
		Log log = new Log();
		Snapshot_Mirror mirror = new Snapshot_Mirror(log);

		Net_Snapshot first = snapshot(1);
		first.heroes.add(hero(1, 1, 2f));
		first.monsters.add(monster(2, 5f));
		first.potions.add(potion(3, 7f));
		is(mirror.apply(first), "a first snapshot applies");
		eq(List.of("+hero 1", "+monster 2", "+potion 3"), log.take(), "the first snapshot creates everything");
		eq(first, mirror.toSnapshot(), "the mirror after the first snapshot");
		Net_Snapshot.Hero heroObject = mirror.hero(1);

		Net_Snapshot second = snapshot(2);
		second.heroes.add(hero(1, 1, 2.5f));
		second.heroes.add(hero(4, 2, 3f));
		second.potions.add(potion(3, 6.5f));
		is(mirror.apply(second), "a newer snapshot applies");
		eq(List.of("-monster 2", "+hero 4"), log.take(), "only what vanished is destroyed and only what is new created");
		eq(second, mirror.toSnapshot(), "the mirror after the second snapshot");
		is(mirror.hero(1) == heroObject, "a hero that moved is still the object its sprite was hung on");
		eq(2.5f, mirror.hero(1).x, "the hero moved");
		is(mirror.monster(2) == null, "the monster is gone");

		// The host sent it : leaving a snapshot's objects alone is the mirror's job
		second.heroes.get(0).x = 99f;
		eq(2.5f, mirror.hero(1).x, "the mirror keeps its own copy, not the snapshot's objects");

		is(mirror.apply(snapshot(3)), "an empty snapshot applies");
		eq(List.of("-hero 1", "-hero 4", "-potion 3"), log.take(), "an empty snapshot destroys everything");
	}

	/** UDP reorders and duplicates : an old or repeated snapshot changes nothing, not even the clock. */
	static void staleIsIgnored() throws Exception
	{
		Log log = new Log();
		Snapshot_Mirror mirror = new Snapshot_Mirror(log);
		Net_Snapshot newer = snapshot(10);
		newer.heroes.add(hero(1, 1, 2f));
		is(mirror.apply(newer), "tick 10 applies");
		log.take();

		Net_Snapshot older = snapshot(9);
		older.monsters.add(monster(5, 1f));
		is(!mirror.apply(older), "tick 9 after tick 10 is refused");
		is(!mirror.apply(newer), "tick 10 twice is refused");
		eq(newer, mirror.toSnapshot(), "a refused snapshot changed nothing");
		eq(List.of(), log.take(), "a refused snapshot created and destroyed nothing");
	}

	/** Ids are one namespace across kinds on the host : a snapshot naming one twice is refused whole. */
	static void duplicateIdsAreRefused() throws Exception
	{
		Log log = new Log();
		Snapshot_Mirror mirror = new Snapshot_Mirror(log);
		Net_Snapshot twice = snapshot(1);
		twice.heroes.add(hero(1, 1, 2f));
		twice.heroes.add(hero(1, 2, 3f));
		is(!mirror.apply(twice), "a hero id twice is refused");

		Net_Snapshot acrossKinds = snapshot(1);
		acrossKinds.heroes.add(hero(1, 1, 2f));
		acrossKinds.potions.add(potion(1, 3f));
		is(!mirror.apply(acrossKinds), "one id on a hero and a potion is refused");
		eq(-1, mirror.tick, "nothing was applied");
		eq(List.of(), log.take(), "nothing was created");
	}

	/** After a wrap the host may hand a dead potion's id to a monster : the potion goes before the monster comes. */
	static void idChangingKind() throws Exception
	{
		Log log = new Log();
		Snapshot_Mirror mirror = new Snapshot_Mirror(log);
		Net_Snapshot first = snapshot(1);
		first.potions.add(potion(7, 3f));
		mirror.apply(first);
		log.take();

		Net_Snapshot second = snapshot(2);
		second.monsters.add(monster(7, 4f));
		is(mirror.apply(second), "the snapshot applies");
		eq(List.of("-potion 7", "+monster 7"), log.take(), "destroyed as a potion, then created as a monster");
		eq(second, mirror.toSnapshot(), "the mirror");
	}

	/** A client that starts listening mid-run catches up from any one snapshot, and a rejoined hero is a new entity. */
	static void lateJoinerAndRejoin() throws Exception
	{
		Log early = new Log();
		Snapshot_Mirror since1 = new Snapshot_Mirror(early);
		Net_Snapshot a = snapshot(1);
		a.heroes.add(hero(1, 1, 2f));
		since1.apply(a);

		// Player 1 died ; they rejoined as hero 6, player 2 joined as hero 5
		Net_Snapshot b = snapshot(40);
		b.scores.add(new Net_Snapshot.Score(1, 3, 1));
		b.scores.add(new Net_Snapshot.Score(2, 0, 0));
		b.heroes.add(hero(5, 2, 1f));
		b.heroes.add(hero(6, 1, 4f));
		since1.apply(b);
		early.take();

		Snapshot_Mirror late = new Snapshot_Mirror();
		is(late.apply(b), "a late client applies the snapshot in hand");
		eq(since1.toSnapshot(), late.toSnapshot(), "a late client agrees with one that saw every snapshot");
		is(since1.hero(1) == null && since1.hero(6).player == 1, "player 1 came back as a new hero, id 6");
		eq(2, late.scores().size(), "the score table came over");
	}

	static Net_Snapshot snapshot(int tick)
	{
		Net_Snapshot snapshot = new Net_Snapshot();
		snapshot.tick = tick;
		snapshot.storyTime = tick / 60f;
		snapshot.skyScroll = tick;
		snapshot.canoeAngle = 0.1f;
		return snapshot;
	}

	static Net_Snapshot.Hero hero(int id, int player, float x)
	{
		Net_Snapshot.Hero hero = new Net_Snapshot.Hero();
		hero.id = id;
		hero.player = player;
		hero.look = player % 6;
		hero.x = x;
		hero.y = 5f;
		hero.hp = 4;
		hero.anim = 1;
		hero.reverse = true;
		hero.axeX = x + 1.5f;
		hero.axeY = 5f;
		return hero;
	}

	static Net_Snapshot.Monster monster(int id, float x)
	{
		Net_Snapshot.Monster monster = new Net_Snapshot.Monster();
		monster.id = id;
		monster.look = 2;
		monster.x = x;
		monster.y = 9f;
		return monster;
	}

	static Net_Snapshot.Potion potion(int id, float x)
	{
		Net_Snapshot.Potion potion = new Net_Snapshot.Potion();
		potion.id = id;
		potion.x = x;
		potion.y = 12f;
		return potion;
	}
}
