package jks.draw;

import static jks.personnage.index.Index_Sprite.hpBottle;
import static jks.physic.FVars_Physic.PPM;

import com.badlogic.gdx.graphics.g2d.Batch;

/** A potion as it is drawn (phase 1.5) : the bottle on a centre in metres. Nothing of it is per potion. */
public class Draw_Potion
{
	private Draw_Potion() {}
	
	public static void draw(Batch batch, float bodyX, float bodyY, float angle)
	{
		batch.draw(
				hpBottle, 
			 	bodyX * PPM - hpBottle.getWidth()/2, bodyY * PPM - hpBottle.getHeight()/2,
			 	hpBottle.getOriginX(),hpBottle.getOriginY(),
			 	hpBottle.getWidth(),hpBottle.getHeight(),
			 	hpBottle.getScaleX(),hpBottle.getScaleY(),
			 	(float)Math.toDegrees(angle));
	}
}
