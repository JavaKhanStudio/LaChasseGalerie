package jks.personnage.index;

import java.util.ArrayList;
import java.util.Random;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Sprite;

import jks.debug.GVars_Debug;

public class Index_Sprite 
{
	// How many living heroes wear each colour : past the last free one, colours are shared
	public static ArrayList<Integer> colorUsers ; 
	public static ArrayList<SIW_Data> persoModel ; 
	
	public static ArrayList<SIW_Data> monsterModel ; 
	public static Sprite hpBottle ; 
	private static float scale = 0.5f; 
	private static float scaleMonster = 0.3f; 
	private static Random random = new Random() ; 
	
	private static float bottleScaling = 3.6f ;
	
	public static void init()
	{
		colorUsers = new ArrayList<Integer>() ;
		persoModel = new ArrayList<SIW_Data>() ; 
		monsterModel = new ArrayList<SIW_Data>() ; 
		addColor("Red",Color.RED) ; 
		addColor("Blue",Color.BLUE) ;
		addMonster(1) ; 
		hpBottle = new Sprite(new Texture("tools/healing_1.png"));
		hpBottle.setSize(hpBottle.getWidth()/bottleScaling, hpBottle.getHeight()/bottleScaling) ; 

		if(!GVars_Debug.debugMode)
		{
			addMonster(2) ; 
			addMonster(4) ; 
			addMonster(5) ;
			
			addColor("Green",Color.GREEN) ; 
			addColor("Teal",Color.TEAL) ; 
			addColor("Yellow",Color.YELLOW) ; 
			addColor("Gris",Color.GRAY) ; 
		}

		
	}
	
	private static void addColor(String colorName,Color color)
	{
		persoModel.add(SIW_Data.getHeroData(scale,colorName,color)) ;
		colorUsers.add(0) ;
	}
	
	private static void addMonster(int number)
	{
		monsterModel.add(SIW_Data.getMonsterData(scaleMonster, number)) ; 
	}
	
	public static SIW_Data getRandomEnnemy()
	{
		return monsterModel.get(random.nextInt(monsterModel.size())) ;
	}

	
	public static SIW_Data getRandomHeroColor()
	{
		int start = random.nextInt(persoModel.size()) ; 
		int chosen = start ; 
		for(int step = 1 ; step < persoModel.size() ; step++)
		{
			int value = (start + step) % persoModel.size() ; 
			if(colorUsers.get(value) < colorUsers.get(chosen))
			{chosen = value ;}
		}
		colorUsers.set(chosen, colorUsers.get(chosen) + 1) ; 
		return persoModel.get(chosen) ; 
	}
	
}
