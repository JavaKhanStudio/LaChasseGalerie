package jks.vue;

import java.util.ArrayList;

import jks.vinterface.ToRender;

public abstract class AVue_Model 
{

	public ArrayList<ToRender> toRender = new ArrayList<ToRender>(); 
	
	public abstract void init() ;
	
	public abstract void update (float delta) ;
	public abstract void render () ;
	
	/** The window changed size : a view with a Stage of its own updates its viewport here. */
	public void resize (int width, int height) {}
	
	/** Called by GVars_Heart.changeVue before this view is replaced : let go of what it owns. */
	public void dispose () {}
	
}
