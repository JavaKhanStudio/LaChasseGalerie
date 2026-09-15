package jks.amain;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;

import jks.sounds.GVars_AudioManager;
import jks.vars.FVars_Heart;
import jks.vars.GVars_Heart;
import jks.vue.models.Vue_Game;

public class Main_Game extends ApplicationAdapter 
{

	/**
	 * Real time waiting to be simulated. The world advances in whole steps of FVars_Heart.step and
	 * never by the length of a frame, so a machine that draws 30 or 144 pictures a second plays the
	 * same run at the same speed — it just sees it more or less often.
	 */
	private float accumulator ;
	
	/**
	 * The most real time one frame may catch up on. Past this the world is simulated slowly rather
	 * than the step count spiralling: a stall, a breakpoint or a dragged window is time we give up
	 * on, not time we try to replay in one frame.
	 */
	private static final float maxCatchUp = 5 * FVars_Heart.step ;

	@Override
	public void create () 
	{
		GVars_Heart.changeVue(new Vue_Game()) ; 
	}

	@Override
	public void render () 
	{
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
		
		accumulator += Gdx.graphics.getDeltaTime() ;
		
		if(accumulator > maxCatchUp)
			accumulator = maxCatchUp ; 
		
		while(accumulator >= FVars_Heart.step)
		{
			GVars_Heart.vue.update(FVars_Heart.step);
			accumulator -= FVars_Heart.step ; 
		}
		
		GVars_Heart.vue.render();
	}
	
    @Override
	public void dispose() 
	{
    	GVars_AudioManager.StopAndDisposeMusic();
    }
}
