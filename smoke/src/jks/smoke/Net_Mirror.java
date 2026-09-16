package jks.smoke;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.physics.box2d.Body;

import jks.headless.Headless_Runner;
import jks.input.GVars_Controller;
import jks.input.Player_Inputs;
import jks.net.Net_Codec;
import jks.net.Net_Snapshot;
import jks.online.Snapshot_Mirror;
import jks.online.Snapshot_View;
import jks.personnage.PhysicSpriteEnnemy;
import jks.personnage.PhysicSpriteHeroes;
import jks.personnage.index.Index_Sprite;
import jks.physic.objects.PhysicSpriteHp;
import jks.vars.GVars_Game;
import jks.vars.GVars_Random;

/**
 * `./gradlew netmirror` : phase 1.3's gate (r38). A host plays the real game headless ; every tick
 * Snapshot_View reads the world, and two world-less clients apply what it read. One takes the
 * snapshot as it is, the other takes it through the real codec, quantized, the way a socket would
 * hand it over. Every tick both must agree with the WORLD, entity by entity and by id, and the first
 * disagreement exits 1.
 *
 * Players press at random, die and rejoin, so heroes, monsters and potions are made and destroyed all
 * run long. Half way through the ids are driven past 0xFFFF, so the second half plays on wrapped ids.
 * A third client joins late and must catch up from one snapshot.
 */
public class Net_Mirror implements Headless_Runner.Session
{
	/** What quantizing to 1/256 m can move a value by, and a little float slack on top. */
	static final float POSITION_TOLERANCE = 0.5f / 256 + 1e-4f;
	static final float ANGLE_TOLERANCE = 0.5f / 8192 + 1e-4f;

	final int players, seconds;
	final long seed;
	int frame;

	int created, destroyed, rejoins, wrapsForced;
	final Set<Integer> heroIdsSeen = new HashSet<Integer>();
	/** Every hero id each player has worn : a rejoin must be a new one. */
	final Map<Integer, Set<Integer>> idsByPlayer = new HashMap<Integer, Set<Integer>>();

	public static void main(String[] args)
	{
		int players = args.length > 0 ? Integer.parseInt(args[0]) : 8;
		int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 90;
		long seed = args.length > 2 ? Long.parseLong(args[2]) : 1;
		Headless_Runner.launch(1280, 720, new Net_Mirror(players, seconds, seed));
	}

	Net_Mirror(int players, int seconds, long seed)
	{
		this.players = players;
		this.seconds = seconds;
		this.seed = seed;
	}

	@Override
	public void failed(Throwable t)
	{
		System.out.println("MIRROR FAILED at frame " + frame + " (t=" + frame / 60f + "s, seed " + seed + ")");
		t.printStackTrace(System.out);
	}

	@Override
	public void run(Headless_Runner runner) throws Exception
	{
		GVars_Random.seed(seed);
		Random random = new Random(seed);
		runner.boot();

		Snapshot_Mirror.Listener counter = new Snapshot_Mirror.Listener()
		{
			@Override public void created(Net_Snapshot.Hero hero) { created++; }
			@Override public void destroyed(Net_Snapshot.Hero hero) { destroyed++; }
			@Override public void created(Net_Snapshot.Monster monster) { created++; }
			@Override public void destroyed(Net_Snapshot.Monster monster) { destroyed++; }
			@Override public void created(Net_Snapshot.Potion potion) { created++; }
			@Override public void destroyed(Net_Snapshot.Potion potion) { destroyed++; }
		};
		Snapshot_Mirror exact = new Snapshot_Mirror(counter);
		Snapshot_Mirror wire = new Snapshot_Mirror();
		Snapshot_Mirror late = null;

		List<Controller> devices = new ArrayList<Controller>();
		devices.add(null);
		for (int i = 1; i < players; i++)
			devices.add(Smoke_Run.fakeController("pad" + i));

		Net_Snapshot previous = null;
		for (frame = 0; frame < seconds * 60; frame++)
		{
			if (frame % 60 == 0)
				for (Controller device : devices)
					if (GVars_Controller.getLocalPlayer(device) == null)
						GVars_Game.addPlayer(GVars_Controller.identify(device));
			if (frame % 20 == 0)
				for (Player_Inputs player : GVars_Controller.playerList.values())
				{
					int move = random.nextInt(3);
					player.leftPressed = move == 0;
					player.rightPressed = move == 1;
					player.jumpPressed = random.nextInt(3) == 0;
					player.powerLeft = random.nextInt(3) == 0;
					player.powerRight = random.nextInt(3) == 0;
				}
			if (frame == seconds * 30)
				forceWrap();

			runner.step();

			checkIdsUnique();
			Net_Snapshot snapshot = Snapshot_View.read(frame);
			is(exact.apply(snapshot), "a new tick's snapshot was refused");
			ByteBuffer packet = Net_Codec.encode(snapshot);
			is(wire.apply((Net_Snapshot) Net_Codec.decode(packet)), "a new tick's decoded snapshot was refused");

			if (!snapshot.equals(exact.toSnapshot()))
				throw new IllegalStateException("the exact mirror differs from the snapshot it applied:\n  " + snapshot + "\n  " + exact.toSnapshot());
			agreesWithTheWorld(exact, 0, 0, "exact");
			agreesWithTheWorld(wire, POSITION_TOLERANCE, ANGLE_TOLERANCE, "wire");
			trackHeroIds(snapshot);

			if (previous != null && exact.apply(previous))
				throw new IllegalStateException("the snapshot of tick " + previous.tick + " applied again after tick " + frame);

			if (frame == seconds * 45)
			{
				late = new Snapshot_Mirror();
				is(late.apply(snapshot), "a late client refused its first snapshot");
			}
			else if (late != null)
				is(late.apply(snapshot), "the late client refused a new tick");
			if (late != null && !late.toSnapshot().equals(exact.toSnapshot()))
				throw new IllegalStateException("the late client does not agree with the one that saw every tick");

			previous = snapshot;
		}

		int living = exact.heroes().size() + exact.monsters().size() + exact.potions().size();
		if (created - destroyed != living)
			throw new IllegalStateException(created + " created - " + destroyed + " destroyed, but the mirror holds " + living);
		if (rejoins == 0)
			throw new IllegalStateException("nobody died and rejoined : the run proves nothing about new ids");
		if (wrapsForced == 0 || destroyed == 0)
			throw new IllegalStateException("no wrap or no destroy : the run proves nothing");

		System.out.println("MIRROR ok : " + players + " players, seed " + seed + ", " + seconds + "s, every tick applied to 2 world-less clients and a late one : "
				+ created + " entities created and " + destroyed + " destroyed on the client, " + heroIdsSeen.size() + " hero ids, " + rejoins
				+ " rejoins under a new id, ids wrapped past 0xFFFF at t=" + seconds / 2 + "s");
	}

	/** Uses up the rest of the 16-bit ids, so everything made after this wears a wrapped one. */
	void forceWrap()
	{
		int before = GVars_Game.newEntityId();
		int id = before;
		while (id >= before)
			id = GVars_Game.newEntityId();
		wrapsForced++;
	}

	/** One namespace, whatever the kind, including the potions that fell off the world. */
	void checkIdsUnique()
	{
		Set<Integer> ids = new HashSet<Integer>();
		for (PhysicSpriteHeroes hero : GVars_Game.heroes)
			if (!ids.add(hero.id) || hero.id <= 0 || hero.id > 0xFFFF)
				throw new IllegalStateException("hero id " + hero.id + " is taken or out of range");
		for (PhysicSpriteEnnemy monster : GVars_Game.ennemies)
			if (!ids.add(monster.id) || monster.id <= 0 || monster.id > 0xFFFF)
				throw new IllegalStateException("monster id " + monster.id + " is taken or out of range");
		for (PhysicSpriteHp potion : GVars_Game.hpStack)
			if (!ids.add(potion.id) || potion.id <= 0 || potion.id > 0xFFFF)
				throw new IllegalStateException("potion id " + potion.id + " is taken or out of range");
	}

	/**
	 * The client's picture against the host's world, read straight off the bodies and not through the
	 * view : the same ids, and each entity where its body is. A hero queued to die and a potion that fell
	 * off the world are not on the client ; everything else is, in the host's drawing order.
	 */
	void agreesWithTheWorld(Snapshot_Mirror mirror, float position, float angle, String which)
	{
		int heroes = 0;
		for (PhysicSpriteHeroes model : GVars_Game.heroes)
		{
			Net_Snapshot.Hero hero = mirror.hero(model.id);
			boolean gone = GVars_Game.toDie.contains(model) || model.body.getPosition().y < 0;
			if (gone)
			{
				is(hero == null, which + ": hero " + model.id + " left the world but the client still has it");
				continue;
			}
			heroes++;
			is(hero != null, which + ": hero " + model.id + " of " + model.player + " is not on the client");
			near(model.body, hero.x, hero.y, position, which + " hero " + model.id);
			near(model.body.getLinearVelocity().x, hero.vx, position, which + " hero " + model.id + " vx");
			near(model.body.getLinearVelocity().y, hero.vy, position, which + " hero " + model.id + " vy");
			near(model.axe.bodyAxe, hero.axeX, hero.axeY, position, which + " axe of hero " + model.id);
			nearAngle(model.axe.bodyAxe.getAngle(), hero.axeAngle, angle, which + " axe angle of hero " + model.id);
			eq(model.player.number(), hero.player, which + " hero " + model.id + " player");
			eq(Index_Sprite.persoModel.indexOf(model.index), hero.look, which + " hero " + model.id + " look");
			eq(model.hp_left, hero.hp, which + " hero " + model.id + " hp");
			eq(model.currentAnimState.ordinal(), hero.anim, which + " hero " + model.id + " anim");
			is(model.isReversed() == hero.reverse, which + " hero " + model.id + " facing");
			is(model.invulnerable == hero.invulnerable, which + " hero " + model.id + " invulnerable");
		}
		eq(heroes, mirror.heroes().size(), which + ": heroes on the client");

		int monsters = 0;
		for (PhysicSpriteEnnemy model : GVars_Game.ennemies)
		{
			Net_Snapshot.Monster monster = mirror.monster(model.id);
			monsters++;
			is(monster != null, which + ": monster " + model.id + " is not on the client");
			near(model.body, monster.x, monster.y, position, which + " monster " + model.id);
			eq(Index_Sprite.monsterModel.indexOf(model.index), monster.look, which + " monster " + model.id + " look");
			is(model.isReversed() == monster.reverse, which + " monster " + model.id + " facing");
		}
		eq(monsters, mirror.monsters().size(), which + ": monsters on the client");

		int potions = 0;
		for (PhysicSpriteHp model : GVars_Game.hpStack)
		{
			Net_Snapshot.Potion potion = mirror.potion(model.id);
			if (model.body.getPosition().y < 0)
			{
				is(potion == null, which + ": potion " + model.id + " fell off the world but is on the client");
				continue;
			}
			potions++;
			is(potion != null, which + ": potion " + model.id + " is not on the client");
			near(model.body, potion.x, potion.y, position, which + " potion " + model.id);
		}
		eq(potions, mirror.potions().size(), which + ": potions on the client");

		nearAngle(GVars_Game.canoe.body.getAngle(), mirror.canoeAngle, angle, which + " canoe angle");
		eq(GVars_Game.playerRegister.size(), mirror.scores().size(), which + ": score rows");
		for (Net_Snapshot.Score score : mirror.scores())
		{
			var label = GVars_Game.playerRegister.get(jks.input.PlayerId.of(score.player));
			is(label != null, which + ": a score row for player " + score.player + " who never joined");
			eq(label.scoreNumber, score.score, which + " P" + score.player + " score");
			eq(label.deathNumber, score.deaths, which + " P" + score.player + " deaths");
		}
	}

	void trackHeroIds(Net_Snapshot snapshot)
	{
		for (Net_Snapshot.Hero hero : snapshot.heroes)
		{
			if (!heroIdsSeen.add(hero.id))
				continue;
			Set<Integer> worn = idsByPlayer.computeIfAbsent(hero.player, p -> new HashSet<Integer>());
			if (!worn.isEmpty())
				rejoins++;
			worn.add(hero.id);
		}
	}

	static void near(Body body, float x, float y, float tolerance, String what)
	{
		near(body.getPosition().x, x, tolerance, what + " x");
		near(body.getPosition().y, y, tolerance, what + " y");
	}

	static void near(float expected, float actual, float tolerance, String what)
	{
		if (!(Math.abs(expected - actual) <= tolerance))
			throw new IllegalStateException(what + " : the world says " + expected + ", the client " + actual);
	}

	static void nearAngle(float expected, float actual, float tolerance, String what)
	{
		double turn = 2 * Math.PI;
		double difference = Math.abs(((expected - actual) % turn + turn * 1.5) % turn - turn / 2);
		if (!(difference <= tolerance))
			throw new IllegalStateException(what + " : the world says " + expected + ", the client " + actual);
	}

	static void is(boolean condition, String what)
	{
		if (!condition)
			throw new IllegalStateException(what);
	}

	static void eq(int expected, int actual, String what)
	{
		if (expected != actual)
			throw new IllegalStateException(what + " : the world says " + expected + ", the client " + actual);
	}
}
