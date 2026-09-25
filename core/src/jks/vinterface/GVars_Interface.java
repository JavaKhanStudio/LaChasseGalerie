package jks.vinterface;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.viewport.ExtendViewport;

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

	/**
	 * The Stage of a screen outside the run (the menus, the lobby, the score screen) : laid out on a
	 * 1280x720 world, wider or taller with the window's shape, and scaled to the window. At 1920x1080
	 * the text grows with the buttons instead of staying 16 px (r77). Lay it out on stage.getWidth() and
	 * getHeight(), NEVER on Gdx.graphics, and apply its viewport before drawing it.
	 */
	public static Stage menuStage()
	{
		Stage stage = new Stage(new ExtendViewport(1280, 720)) ;
		stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true) ;
		return stage ;
	}

	public static void init() 
	{
		loadSkin();
		// On the menus' 1280x720 world too, so the score table's text grows with the window (r77)
		mainInterface = menuStage();
		
		scoreRegion = Utils_Interface.buildDrawingRegionTexture("ui/score.png") ;		
		deathRegion = Utils_Interface.buildDrawingRegionTexture("ui/death.png") ;
		
		bottomScore = new Table() ; 
		bottomScore.setWidth(mainInterface.getWidth() * width);
		bottomScore.setHeight(mainInterface.getHeight() * height);
		bottomScore.setPosition(
				(mainInterface.getWidth() - bottomScore.getWidth())/2, 
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
