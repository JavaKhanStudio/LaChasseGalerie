package jks.online;

import jks.input.GVars_Controller;
import jks.input.PlayerId;
import jks.input.Player_Inputs;
import jks.net.Net_Input;
import jks.net.Net_Snapshot;
import jks.personnage.PhysicSpriteHeroes;
import jks.personnage.ScoreLabel;
import jks.story.GVars_Story;
import jks.vars.GVars_Game;
import jks.vars.GVars_Heart;

/**
 * The real game behind a {@link HostSession} : GVars_Game's run, as it is. A remote player sits in
 * GVars_Controller.playerList exactly like a local one (phase 0.3), so pressing its buttons is
 * writing the same Player_Inputs fields the keyboard writes, and the game cannot tell them apart.
 *
 * It holds no state of its own : a host that also plays locally keeps its keyboard and pads on
 * IKM_Game_*, beside this, and a host with no local player is one where nothing else ever calls
 * GVars_Game.addPlayer.
 */
public class Game_Simulation implements Host_Simulation
{
	/** The game is hosted from now on : its runs end on the score screen, not in the menu (d14). */
	public Game_Simulation()
	{
		GVars_Heart.hosting = true;
	}

	@Override
	public PlayerId newPlayer()
	{
		return GVars_Controller.newPlayer();
	}

	@Override
	public boolean hasHero(PlayerId player)
	{
		return GVars_Controller.getPlayer(player) != null;
	}

	@Override
	public void spawn(PlayerId player)
	{
		// The song is over : the score screen is up and nobody joins a run that has ended (d14)
		if (!hasHero(player) && !GVars_Story.runOver())
			GVars_Game.addPlayer(player);
	}

	@Override
	public void press(PlayerId player, int held, int pressed)
	{
		Player_Inputs inputs = GVars_Controller.getPlayer(player);
		if (inputs == null)
			return;
		inputs.leftPressed = (held & Net_Input.LEFT) != 0;
		inputs.rightPressed = (held & Net_Input.RIGHT) != 0;
		// One-shots are cleared by Player_Inputs.act once they fired : only ever set them here
		if ((pressed & Net_Input.JUMP) != 0)
			inputs.jumpPressed = true;
		if ((pressed & Net_Input.SWING_LEFT) != 0)
			inputs.powerLeft = true;
		if ((pressed & Net_Input.SWING_RIGHT) != 0)
			inputs.powerRight = true;
	}

	@Override
	public void remove(PlayerId player)
	{
		for (PhysicSpriteHeroes hero : GVars_Game.heroes)
			if (hero.player.equals(player))
				GVars_Game.toDie.add(hero); // kill() at the next update : not a death, nobody hit it
	}

	@Override
	public void forget(PlayerId player)
	{
		ScoreLabel label = GVars_Game.playerRegister.remove(player);
		if (label != null)
			label.remove();
	}

	@Override
	public Net_Snapshot read(int tick)
	{
		return Snapshot_View.read(tick);
	}
}
