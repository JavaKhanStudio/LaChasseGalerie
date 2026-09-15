package jks.parralax;

import com.badlogic.gdx.graphics.Color;

import jks.tools2d.parallax.pages.WholePage_Model;

public enum Enum_ColdNight 
{
	COLD_NIGHT("parralax/models/OneNight.atlas",Color.valueOf("0B4B6F"),Color.valueOf("0C77AD"),Color.valueOf("030205")),
	COLD_WATER("parralax/models/OneNight.atlas"),
	
	;
	
	public WholePage_Model wholePage ; 
	
	Enum_ColdNight(String atlasPath, Color top, Color bottom,Color bottomHalf)
	{
		wholePage = new WholePage_Model(atlasPath,top,bottom,bottomHalf,bottomHalf) ;
		// The sky gradient covers the whole screen, the bottom half nothing
		wholePage.topHalfSize = 0 ; 
		wholePage.bottomHalfSize = 1 ; 
		wholePage.pageModel.pageList = ColdNightModel.buildPages() ; 
	}
	
	Enum_ColdNight(String atlasPath)
	{
		wholePage = new WholePage_Model(atlasPath) ;
		// Drawn over the sprites : no background at all
		wholePage.topHalfSize = 1 ; 
		wholePage.bottomHalfSize = 1 ; 
		wholePage.pageModel.pageList = ColdNightModel.buildSecondPages() ; 
	}

}
