package jks.personnage;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;

import jks.vinterface.GVars_Interface;

public class ScoreLabel extends Table
{
	Label nameValue ;
	public Image score ; 
	public Image death ; 
	public Label scoreValue ; 
	public Label deathValue ; 
	
	public int scoreNumber = 0; 
	public int deathNumber = 0; 
	
	public static Float buttonSize ; 
	
	public ScoreLabel(String name)
	{
		nameValue = new Label(name, GVars_Interface.baseSkin) ; 
		nameValue.setFontScale(2);
		
		scoreValue = new Label("", GVars_Interface.baseSkin)
		{
			@Override
			public float getPrefWidth()
			{return buttonSize * 1.1f ;}
			
			@Override
			public float getPrefHeight()
			{return getPrefWidth() ; }
		} ;
		scoreValue.setFontScale(2);
		score = new Image(GVars_Interface.scoreRegion) 
		{
			@Override
			public float getPrefWidth()
			{return buttonSize ;}
			
			@Override
			public float getPrefHeight()
			{return getPrefWidth() ; }
		}; 
		
		deathValue = new Label("", GVars_Interface.baseSkin)
		{
			@Override
			public float getPrefWidth()
			{return buttonSize * 1.1f;}
			
			@Override
			public float getPrefHeight()
			{return getPrefWidth() ; }
		} ;
		deathValue.setFontScale(2);
		death = new Image(GVars_Interface.deathRegion) 
		{
			@Override
			public float getPrefWidth()
			{return buttonSize ;}
			
			@Override
			public float getPrefHeight()
			{return getPrefWidth() ; }
		} ;

		this.add(nameValue).colspan(2).row();
		this.add(score);
		this.add(scoreValue).align(Align.left);
		this.row() ; 
		
		this.add(death);
		this.add(deathValue).align(Align.left);
		update() ; 
	}
	
	public void setColor(Color color)
	{
		score.setColor(color);
		death.setColor(color);
	}
	
	public void update() 
	{
		scoreValue.setText(": " + scoreNumber);
		deathValue.setText(": " + deathNumber);
	}
}
