package jks.vinterface;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;

import jks.personnage.ScoreLabel;

public class GVars_Interface 
{

	public static Skin baseSkin;
	public static Stage mainInterface;
	
	public static Table bottomScore ; 
	
	public static TextureRegionDrawable scoreRegion ;
	public static TextureRegionDrawable deathRegion ;

	private static float width = 0.7f; 
	private static float height = 0.12f ; 
	
	public static void init() 
	{
		baseSkin = new Skin(Gdx.files.internal("skin/freezing-ui.json"));
		mainInterface = new Stage();
		
		scoreRegion = Utils_Interface.buildDrawingRegionTexture("ui/score.png") ;		
		deathRegion = Utils_Interface.buildDrawingRegionTexture("ui/death.png") ;
		
		bottomScore = new Table() ; 
		bottomScore.setWidth(Gdx.graphics.getWidth() * width);
		bottomScore.setHeight(Gdx.graphics.getHeight() * height);
		bottomScore.setPosition(
				(Gdx.graphics.getWidth() - bottomScore.getWidth())/2, 
				bottomScore.getHeight()/8);
		ScoreLabel.buttonSize = bottomScore.getHeight()/3f ; 
		
		mainInterface.addActor(bottomScore);
	}

}
