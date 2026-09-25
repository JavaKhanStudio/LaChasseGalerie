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
	
	/** The skin on its own : a view that wants widgets but not the game's HUD calls this. */
	public static void loadSkin()
	{
		if(baseSkin == null)
		{
			baseSkin = new Skin(Gdx.files.internal("skin/freezing-ui.json"));
			// font-export.fnt says lineHeight=16 but its descenders reach 19 px down : a wrapped label drew
			// its lines into each other (r75). One line is laid out on its cap height, so only wraps move
			baseSkin.getFont("font").getData().setLineHeight(21);
		}
	}

	public static void init() 
	{
		loadSkin();
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
	
	/** The game's HUD : the Stage, the score table and its two icons. The skin is shared with the menu and stays. */
	public static void dispose()
	{
		if(mainInterface != null)
			mainInterface.dispose();
		if(scoreRegion != null)
			scoreRegion.getRegion().getTexture().dispose();
		if(deathRegion != null)
			deathRegion.getRegion().getTexture().dispose();
		mainInterface = null ;
		bottomScore = null ;
		scoreRegion = null ;
		deathRegion = null ;
	}

}
