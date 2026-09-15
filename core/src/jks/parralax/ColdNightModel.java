package jks.parralax;

import java.util.ArrayList;

import jks.tools2d.parallax.heart.Gvars_Parallax;
import jks.tools2d.parallax.pages.Parallax_Model;

public class ColdNightModel
{
	/**
	 * One layer, in the values this scene was tuned with on the old library, converted to parallax-background 2.x.
	 * 
	 * ratioX/Y   : how much of the scroll the layer follows (parallaxScalingSpeed)
	 * speed      : world units per second the layer drifts ON SCREEN on its own (+ = right). The 2.x speed at rest is
	 *              scaled by the ratio, so it is divided by it here.
	 * padX/YRatio: start offset, in world HEIGHTS, from the left and from the drawing height
	 * 
	 * The world size is read when the enum is first used, after GVars_Parralax.init.
	 */
	private static Parallax_Model layer(String regionName, int regionPosition, float sizeRatio, float ratioX, float ratioY, float speed, float padXRatio, float padYRatio)
	{
		Parallax_Model model = new Parallax_Model() ; 
		model.regionName = regionName ; 
		model.regionPosition = regionPosition ; 
		model.sizeRatio = sizeRatio ; 
		model.parallaxScalingSpeedX = ratioX ; 
		model.parallaxScalingSpeedY = ratioY ; 
		model.speedXAtRest = ratioX == 0 ? 0 : -speed / ratioX ; 
		model.decal_X_Ratio = padXRatio * 100 * Gvars_Parallax.getWorldHeight() / Gvars_Parallax.getWorldWidth() ; 
		model.decal_Y_Ratio = padYRatio * 100 ; 
		return model ; 
	}
	

	public static ArrayList<Parallax_Model> buildPages() 
	{
		ArrayList<Parallax_Model> returningList = new ArrayList<Parallax_Model>() ; 
		
		Parallax_Model cloudsTop0 = layer("clouds", 0, 0.5f, .01f, .01f, 0.5f, .1f, 1.8f) ;
		Parallax_Model cloudsTop1 = layer("clouds", 1, 0.5f, .01f, .01f, 0.5f, .50f, 1.4f) ;
		Parallax_Model cloudsTop2 = layer("clouds", 0, 0.5f, .01f, .01f, 0.5f, .20f, 1.2f) ;
		Parallax_Model clouds0 = layer("clouds", 0, 0.5f, .01f, .01f, 0.5f, 0, .70f) ;
		Parallax_Model clouds1 = layer("clouds", 1, 0.5f, .01f, .01f, 0.5f, 0, .70f) ;
		Parallax_Model rocks = layer("rocks", 0, 0.5f, .005f, .006f, 0, 0, .40f) ;
		Parallax_Model ground0 = layer("ground", 1, 0.27f, .008f, .006f, 0, 0, .38f) ;
		Parallax_Model ground1 = layer("ground", 2, 0.32f, .0105f, .006f, 0, 0, .35f) ;
		Parallax_Model ground2 = layer("ground", 1, 0.40f, .0135f, .006f, 0, 0, .30f) ;
		Parallax_Model ground3 = layer("ground", 0, 0.45f, .015f, .006f, 0, 0, .25f) ;
		Parallax_Model ground4 = layer("ground", 1, 0.6f, .020f, .006f, 0, 0, .20f) ;
		Parallax_Model ground5 = layer("ground", 2, 0.6f, .024f, .006f, 0, 0, .20f) ;
		Parallax_Model water = layer("waterDark", 0, 0.7f, .04f, .006f, -0.4f, 0, .08f) ;
		
		returningList.add(cloudsTop0) ; 
		returningList.add(cloudsTop1) ; 
		returningList.add(cloudsTop2) ; 
		returningList.add(clouds0) ; 
		returningList.add(clouds1) ; 
		returningList.add(rocks) ;
		returningList.add(ground0) ; 
		returningList.add(ground1) ; 
		returningList.add(ground2) ; 
		returningList.add(ground3) ; 
		returningList.add(ground4) ; 
		returningList.add(ground5) ; 
		returningList.add(water) ;
	
		return returningList ; 
	}
	
	public static ArrayList<Parallax_Model> buildSecondPages() 
	{
		ArrayList<Parallax_Model> returningList = new ArrayList<Parallax_Model>() ; 
		
		Parallax_Model water1 = layer("waterDark", 0, 0.7f, .044f, .006f, -.8f, .20f, .10f) ;
		Parallax_Model water2 = layer("waterDark", 0, 0.7f, .048f, .006f, -0.4f, .30f, .00f) ;
		Parallax_Model cloudsTop0 = layer("clouds", 0, 0.5f, .01f, .01f, 0.5f, .70f, 1.6f) ;
		Parallax_Model cloudsTop1 = layer("clouds", 0, 0.5f, .01f, .01f, 0.5f, .50f, 1.3f) ;
		Parallax_Model cloudsTop2 = layer("clouds", 0, 0.5f, .01f, .01f, 0.5f, .20f, 1.1f) ; 
		
		returningList.add(cloudsTop0) ; 
		returningList.add(cloudsTop1) ; 
		returningList.add(cloudsTop2) ; 
		returningList.add(water1) ;
		returningList.add(water2) ;
		
		return returningList ; 
	}

}
