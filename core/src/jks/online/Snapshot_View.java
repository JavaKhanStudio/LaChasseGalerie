package jks.online;

import java.util.Map.Entry;

import com.badlogic.gdx.physics.box2d.Body;

import jks.input.PlayerId;
import jks.net.Net_Snapshot;
import jks.personnage.PhysicSpriteEnnemy;
import jks.personnage.PhysicSpriteHeroes;
import jks.personnage.ScoreLabel;
import jks.personnage.index.Index_Sprite;
import jks.physic.objects.PhysicSpriteHp;
import jks.story.GVars_Story;
import jks.vars.GVars_Game;
import jks.vars.GVars_Heart;

/**
 * The host's side of phase 1.3 : the answer to "what does a second machine need to be told?"
 * (docs/online-multiplayer.md section 1.2). It reads the running world into a {@link Net_Snapshot}
 * and changes nothing, so a host can call it between any two ticks, as often as it sends.
 *
 * Every entity is named by the id it was given when it was made (GVars_Game.newEntityId). The
 * canoe has none : there is one per run and it only tilts, so it travels as canoeAngle. An axe
 * rides on its hero.
 *
 * What has left the world is left out, because a client has nothing to draw for it and the packet
 * has no room for it : a hero already queued to die (drowned or out of hearts, it is gone at the
 * start of the next update). A potion that fell below the world is already out of hpStack when this
 * reads it (GVars_Game.removeFallenPotions, n7). Monsters are always sent : one chasing a drowning hero dips under the water line
 * and climbs back, and leaving it out there would make it blink out and in on every client.
 */
public final class Snapshot_View
{
	private Snapshot_View()
	{
	}

	/** The run as it stands, stamped with the tick it is the state after. */
	public static Net_Snapshot read(int tick)
	{
		Net_Snapshot snapshot = new Net_Snapshot();
		snapshot.tick = tick;
		snapshot.storyTime = GVars_Story.storyTime();
		snapshot.skyScroll = GVars_Story.skyScroll();
		snapshot.canoeAngle = GVars_Game.canoe.body.getAngle();
		snapshot.run = GVars_Heart.runsStarted & 0xFFFF;
		// The song is over (d14) : the final score table, and nothing left to draw on the river
		snapshot.over = GVars_Story.runOver();

		// Sorted by PlayerId : every client gets the table in one order
		for (Entry<PlayerId, ScoreLabel> entry : GVars_Game.playerRegister.entrySet())
		{
			ScoreLabel label = entry.getValue();
			// The look of the player's last hero, alive or not : a client that never saw it colours the row with it (r69)
			int look = label.look == null ? Net_Snapshot.Score.NO_LOOK : Index_Sprite.persoModel.indexOf(label.look);
			snapshot.scores.add(new Net_Snapshot.Score(entry.getKey().number(), label.scoreNumber, label.deathNumber,
					look < 0 ? Net_Snapshot.Score.NO_LOOK : look));
		}
		if (snapshot.over)
			return snapshot;

		for (PhysicSpriteHeroes model : GVars_Game.heroes)
		{
			// A hero below the world has already been queued by its own act()
			if (GVars_Game.toDie.contains(model))
				continue;
			Net_Snapshot.Hero hero = new Net_Snapshot.Hero();
			hero.id = model.id;
			hero.player = model.player.number();
			hero.look = Index_Sprite.persoModel.indexOf(model.index);
			hero.x = model.body.getPosition().x;
			hero.y = model.body.getPosition().y;
			hero.vx = model.body.getLinearVelocity().x;
			hero.vy = model.body.getLinearVelocity().y;
			hero.hp = model.hp_left;
			hero.anim = model.currentAnimState == null ? 0 : model.currentAnimState.ordinal();
			hero.reverse = model.isReversed();
			hero.invulnerable = model.invulnerable;
			Body axe = model.axe.bodyAxe;
			hero.axeX = axe.getPosition().x;
			hero.axeY = axe.getPosition().y;
			hero.axeAngle = axe.getAngle();
			snapshot.heroes.add(hero);
		}
		for (PhysicSpriteEnnemy model : GVars_Game.ennemies)
		{
			Net_Snapshot.Monster monster = new Net_Snapshot.Monster();
			monster.id = model.id;
			monster.look = Index_Sprite.monsterModel.indexOf(model.index);
			monster.x = model.body.getPosition().x;
			monster.y = model.body.getPosition().y;
			monster.reverse = model.isReversed();
			snapshot.monsters.add(monster);
		}
		for (PhysicSpriteHp model : GVars_Game.hpStack)
		{
			Net_Snapshot.Potion potion = new Net_Snapshot.Potion();
			potion.id = model.id;
			potion.x = model.body.getPosition().x;
			potion.y = model.body.getPosition().y;
			snapshot.potions.add(potion);
		}
		return snapshot;
	}
}
