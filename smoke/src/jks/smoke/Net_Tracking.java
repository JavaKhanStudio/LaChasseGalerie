package jks.smoke;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import jks.net.Net_Snapshot;
import jks.online.ClientSession;

/**
 * "The clients' entity positions track the host's within tolerance" (r39's gate), as one class the
 * toy check and the real-game gate share.
 *
 * The host's world is read after every tick, not only on the ticks it sends. A client draws the run as
 * it was at {@link ClientSession#drawTick()} ; every entity it draws is compared with where that same
 * entity was on the host at that tick. An entity the client does not draw yet, or still draws, is not
 * an error : entities appear and vanish on snapshot ticks. How far behind the host the picture runs is
 * measured too, because tracking a second-old world perfectly is not tracking.
 *
 * The tolerance is not one number, because a straight line drawn between two snapshots cuts the corner
 * wherever the entity turned, started or landed in between. An entity that moves at most d per tick on
 * the host, drawn between snapshots s ticks apart, can be off its path by up to d x s. That bound is
 * taken from the host's own history, entity by entity, plus the codec's quantizing, plus a fixed slack :
 * so a hero standing still must be drawn exactly where it stands, and one flung across the canoe is
 * allowed what its speed makes honest, and no more.
 */
class Net_Tracking
{
	static final int KEPT = 600;

	final TreeMap<Integer, Net_Snapshot> history = new TreeMap<Integer, Net_Snapshot>();
	/** What is allowed beyond the bound : float noise, and the whole error of a wrong but close picture. */
	final float slack;
	static final float QUANTIZING = 1 / 256f;

	float worst;
	double errorSum;
	long compared;
	/** By kind - "axe", "hero", "monster", "potion" : worst, sum, count. */
	final Map<String, double[]> byKind = new TreeMap<String, double[]>();
	int worstLag;
	long lagSum, lagSamples;

	Net_Tracking(float slack)
	{
		this.slack = slack;
	}

	/** After every host tick : the state after that many ticks. */
	void hostTicked(Net_Snapshot state)
	{
		history.put(state.tick, state);
		while (history.size() > KEPT)
			history.pollFirstEntry();
	}

	void compare(ClientSession client, int hostTick, String who)
	{
		float drawn = client.drawTick();
		if (drawn < 0)
			return;
		int lag = Math.round(hostTick - drawn);
		worstLag = Math.max(worstLag, lag);
		lagSum += lag;
		lagSamples++;

		if (drawn != (int) drawn)
			throw new IllegalStateException(who + " draws tick " + drawn + " : the client's clock ticks whole ticks");
		Net_Snapshot truth = history.get((int) drawn);
		if (truth == null)
			throw new IllegalStateException(who + " draws tick " + drawn + ", " + lag + " behind the host : older than the history kept");

		Map<Integer, Net_Snapshot.Hero> heroes = new HashMap<Integer, Net_Snapshot.Hero>();
		for (Net_Snapshot.Hero hero : truth.heroes)
			heroes.put(hero.id, hero);
		Map<Integer, Net_Snapshot.Monster> monsters = new HashMap<Integer, Net_Snapshot.Monster>();
		for (Net_Snapshot.Monster monster : truth.monsters)
			monsters.put(monster.id, monster);
		Map<Integer, Net_Snapshot.Potion> potions = new HashMap<Integer, Net_Snapshot.Potion>();
		for (Net_Snapshot.Potion potion : truth.potions)
			potions.put(potion.id, potion);

		for (Net_Snapshot.Hero hero : client.view().heroes())
		{
			Net_Snapshot.Hero host = heroes.get(hero.id);
			if (host == null)
				continue;
			if (host.player != hero.player)
				throw new IllegalStateException(who + " : hero " + hero.id + " is P" + hero.player + " on the client and P" + host.player + " on the host");
			near("hero", host.x, host.y, hero.x, hero.y, bound(client, h -> find(h.heroes, hero.id, e -> e.id, e -> new float[] { e.x, e.y })), who + " hero " + hero.id, drawn, hostTick);
			near("axe", host.axeX, host.axeY, hero.axeX, hero.axeY, bound(client, h -> find(h.heroes, hero.id, e -> e.id, e -> new float[] { e.axeX, e.axeY })), who + " axe of hero " + hero.id, drawn, hostTick);
		}
		for (Net_Snapshot.Monster monster : client.view().monsters())
		{
			Net_Snapshot.Monster host = monsters.get(monster.id);
			if (host != null)
				near("monster", host.x, host.y, monster.x, monster.y, bound(client, h -> find(h.monsters, monster.id, e -> e.id, e -> new float[] { e.x, e.y })), who + " monster " + monster.id, drawn, hostTick);
		}
		for (Net_Snapshot.Potion potion : client.view().potions())
		{
			Net_Snapshot.Potion host = potions.get(potion.id);
			if (host != null)
				near("potion", host.x, host.y, potion.x, potion.y, bound(client, h -> find(h.potions, potion.id, e -> e.id, e -> new float[] { e.x, e.y })), who + " potion " + potion.id, drawn, hostTick);
		}
	}

	/**
	 * How far off a straight line between the two snapshots this entity may honestly be drawn : its
	 * fastest step on the host between them, times the ticks between them, plus quantizing and slack.
	 */
	float bound(ClientSession client, Function<Net_Snapshot, float[]> position)
	{
		float fastest = 0;
		float[] previous = null;
		for (int tick = client.drawnFrom(); tick <= client.drawnTo(); tick++)
		{
			Net_Snapshot state = history.get(tick);
			float[] now = state == null ? null : position.apply(state);
			if (now == null)
				return Float.POSITIVE_INFINITY; // not there the whole way : nothing honest to hold it to
			if (previous != null)
				fastest = Math.max(fastest, Math.max(Math.abs(now[0] - previous[0]), Math.abs(now[1] - previous[1])));
			previous = now;
		}
		return fastest * (client.drawnTo() - client.drawnFrom()) + 2 * QUANTIZING + slack;
	}

	static <T> float[] find(List<T> entities, int id, ToIntFunction<T> idOf, Function<T, float[]> position)
	{
		for (T entity : entities)
			if (idOf.applyAsInt(entity) == id)
				return position.apply(entity);
		return null;
	}

	void near(String kind, float hostX, float hostY, float x, float y, float tolerance, String what, float drawn, int hostTick)
	{
		float error = Math.max(Math.abs(hostX - x), Math.abs(hostY - y));
		if (!(error <= tolerance))
			throw new IllegalStateException(what + " at tick " + drawn + " (host at " + hostTick + ") : the host had " + hostX + "," + hostY
					+ ", the client draws " + x + "," + y + " - " + error + " m off, allowed " + tolerance);
		worst = Math.max(worst, error);
		errorSum += error;
		compared++;
		double[] tally = byKind.computeIfAbsent(kind, k -> new double[3]);
		tally[0] = Math.max(tally[0], error);
		tally[1] += error;
		tally[2]++;
	}

	String kinds()
	{
		StringBuilder text = new StringBuilder("(");
		for (Map.Entry<String, double[]> kind : byKind.entrySet())
			text.append(text.length() > 1 ? ", " : "").append(kind.getKey()).append(String.format(" worst %.3f mean %.4f", kind.getValue()[0], kind.getValue()[1] / kind.getValue()[2]));
		return text.append(")").toString();
	}

	String report()
	{
		return compared + " positions compared, worst " + String.format("%.3f", worst) + " m, mean " + String.format("%.4f", compared == 0 ? 0 : errorSum / compared)
				+ " m " + kinds() + " ; the picture ran " + String.format("%.1f", lagSamples == 0 ? 0 : (double) lagSum / lagSamples) + " ticks behind the host, " + worstLag + " at worst";
	}
}
