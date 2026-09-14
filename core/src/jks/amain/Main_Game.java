package jks.amain;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;

import jks.sounds.GVars_AudioManager;
import jks.vars.GVars_Heart;
import jks.vue.models.Vue_Game;

public class Main_Game extends ApplicationAdapter 
{


	@Override
	public void create () 
	{
		GVars_Heart.changeVue(new Vue_Game()) ; 
	}

	@Override
	public void render () 
	{
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    	float delta = Math.min(Gdx.graphics.getDeltaTime(), 1 / 30f);
    
    	if (delta > 0) 
    	{
    		GVars_Heart.vue.update(delta);
        	
    		GVars_Heart.vue.render();
    	}
	}
	
    @Override
	public void dispose() 
	{
    	GVars_AudioManager.StopAndDisposeMusic();
    }
}
