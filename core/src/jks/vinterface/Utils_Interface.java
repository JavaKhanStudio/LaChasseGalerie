package jks.vinterface;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;

public class Utils_Interface
{

	public static TextureRegionDrawable buildDrawingRegionTexture(String texturePath)
	{
		//Texture texture  = GVars_Heart.assets.get(texturePath) ; 
		// No mipmaps : the Linear filter never samples them, and WebGL 1 refuses to build them for a
		// texture that is not a power of two (score.png is 617 px), which the browser console said twice
		Texture texture  = new Texture(Gdx.files.internal(texturePath));
		texture.setFilter(TextureFilter.Linear, TextureFilter.Linear) ;
	    TextureRegion TextureRegion = new TextureRegion(texture);
	    return new TextureRegionDrawable(TextureRegion);
	}
	
	public static TextureRegion buildTextureRegion(String texturePath)
	{
		Texture texture  = new Texture(Gdx.files.internal(texturePath));
	    return new TextureRegion(texture);
	}
	
}