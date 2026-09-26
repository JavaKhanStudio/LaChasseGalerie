package jks.smoke;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.joints.RevoluteJoint;

import jks.headless.Headless_Runner;
import jks.input.GVars_Controller;
import jks.input.Player_Inputs;
import jks.personnage.PhysicSpriteHeroes;
import jks.vars.GVars_Game;
import jks.vars.GVars_Random;

/**
 * r104 : what a swing from rest does. The axe has no gravity and hangs on a joint limited to the upper
 * half circle, -pi (right of the hero) to 0 (left). powerLeft (D, pad B) drives it over the top to the
 * RIGHT side, powerRight (Q, pad X) to the left : a swing toward the side the axe already rests on has
 * nowhere to go and does not stir it, which is what r87's tab saw after a walk right.
 * One keyboard hero joins, walks each way, stops, lets the axe settle, then swings each way from rest.
 * Fails when a swing that has room to travel does not turn the axe at least 2 rad in 0.8 s.
 * ./gradlew axeprobe
 */
public class Axe_Probe implements Headless_Runner.Session
{
	Headless_Runner runner;

	public static void main(String[] args)
	{
		Headless_Runner.launch(1280, 720, new Axe_Probe());
	}

	@Override
	public void run(Headless_Runner runner) throws Exception
	{
		this.runner = runner;
		GVars_Random.seed(1);
		runner.boot();
		// Before the monsters (15 s) : nothing but the hero and its axe moves
		GVars_Game.addPlayer(GVars_Controller.identify(null));
		steps(60);

		int still = 0;
		for (boolean walkRight : new boolean[] { true, false })
			for (boolean swingLeft : new boolean[] { true, false })
				if (!swing(walkRight, swingLeft))
					still++;
		if (still > 0)
			throw new IllegalStateException(still + " swing(s) from rest with room to travel did not swing the axe");
		System.out.println("AXE every swing from rest with room to travel swung the axe over");
	}

	/** Walks, stops, settles, swings once : false when the swing had room and the axe did not travel. */
	boolean swing(boolean walkRight, boolean swingLeft)
	{
		PhysicSpriteHeroes hero = GVars_Game.heroes.get(0);
		Player_Inputs input = GVars_Controller.getLocalPlayer(null);
		Body axe = hero.axe.bodyAxe;
		RevoluteJoint joint = (RevoluteJoint) hero.joint;

		// Short, from wherever it is : a long walk leaves the canoe
		if (walkRight) input.rightPressed = true; else input.leftPressed = true;
		steps(20);
		input.rightPressed = input.leftPressed = false;
		steps(120);

		float rest = joint.getJointAngle();
		float from = axe.getAngle();
		if (swingLeft) input.powerLeft = true; else input.powerRight = true;
		float most = 0;
		for (int i = 0; i < 48; i++)
		{
			runner.step();
			most = Math.max(most, Math.abs(joint.getJointAngle() - rest));
		}
		System.out.printf("AXE walked %-5s swung %-5s : at rest joint %6.2f rad (limits %.2f..%.2f), hero x %.1f, turned at most %.2f rad (axe body %.2f)%n",
				walkRight ? "right" : "left", swingLeft ? "left" : "right", rest, joint.getLowerLimit(), joint.getUpperLimit(),
				hero.body.getPosition().x, most, Math.abs(axe.getAngle() - from));
		steps(120);
		// Left goes toward the lower limit (right side), right toward the upper one
		float room = swingLeft ? rest - joint.getLowerLimit() : joint.getUpperLimit() - rest;
		return room < 1 || most > 2;
	}

	void steps(int n)
	{
		for (int i = 0; i < n; i++)
			runner.step();
	}
}
