package jks.draw;

import static jks.physic.FVars_Physic.PPM;

import jks.personnage.index.SIW_Data;
import jks.personnage.model.SpriteModel;

/**
 * A monster as it is drawn (phase 1.5) : its idle loop, facing, placed on a centre in metres.
 * PhysicSpriteEnnemy extends it and places it on its body ; Vue_Client places it from a snapshot.
 */
public class Draw_Monster extends SpriteModel
{
	public Draw_Monster(SIW_Data index)
	{
		super(index) ; 
		currentFrame = currentState.getKeyFrame(0,false) ;
	}
	
	/** The sprite's corner, from the body's centre in metres and the frame drawn last. */
	public void placeAt(float bodyX, float bodyY)
	{
		position.x = bodyX * PPM - getFrameWidth(currentFrame)/ 2; 
		position.y = bodyY * PPM - getFrameHeight(currentFrame)/ 2; 
	}
}
