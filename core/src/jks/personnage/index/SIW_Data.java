package jks.personnage.index;

import java.util.HashMap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

public class SIW_Data 
{
	public HashMap<Enum_AnimState,Animation<TextureRegion>> animationList ;
	public float scale ;
	public Color color ;
	
	public SIW_Data(TextureAtlas textureAtlas, HashMap<Enum_AnimState,String> animationName, float animationSpeed, float Scale, Color color)
	{
		animationList = new HashMap<Enum_AnimState,Animation<TextureRegion>>() ;
		
		for(Enum_AnimState finalName : animationName.keySet())
		{
			animationList.put(finalName, new Animation<TextureRegion>(animationSpeed, textureAtlas.findRegions(animationName.get(finalName)))) ;
		}
	
		this.color = color ;
		scale = Scale ;
	}
	
	public static SIW_Data getHeroData(float scale,String type,Color color)
	{
		TextureAtlas textureAtlas = new TextureAtlas(Gdx.files.internal("perso/Hero" + type + ".atlas"));
		HashMap<Enum_AnimState,String> animationList = new HashMap<Enum_AnimState,String>() ; 
		animationList.put(Enum_AnimState.RUN, "r_run") ; 
		animationList.put(Enum_AnimState.DEATH, "Dead") ; 
		animationList.put(Enum_AnimState.JUMP, "r_jump_up") ; 
		animationList.put(Enum_AnimState.HURT, "r_hit") ; 
		animationList.put(Enum_AnimState.IDLE, "r_idle") ; 
		animationList.put(Enum_AnimState.WALK, "Walk") ; 
		
		return new SIW_Data(textureAtlas,animationList,0.03f,scale,color) ; 
	}

	
	public static SIW_Data getMonsterData(float scale,int monsterNumber)
	{
		TextureAtlas textureAtlas = new TextureAtlas(Gdx.files.internal("ennemy/monster" + monsterNumber + ".atlas"));
		HashMap<Enum_AnimState,String> animationList = new HashMap<Enum_AnimState,String>() ; 
		animationList.put(Enum_AnimState.IDLE, "IDLE") ; 

		return new SIW_Data(textureAtlas,animationList,0.08f,scale,null) ; 
	}
	
}
