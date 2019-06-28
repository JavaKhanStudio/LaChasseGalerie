package jks.debug;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

public class GVars_Debug 
{
	public static boolean debugMode = false ; 
	
	public static boolean soundDebug = false ;
	public static boolean collisionDebug = false ;
	public static boolean coreInformationDebug = true ;
	
	public static ShapeRenderer shapeDebugRenderer;
	
	public static void activatedDebug()
	{
		collisionDebug = true ;
		soundDebug = true ; 
		coreInformationDebug = true ;
	}
	
	public static void desactivateDebug()
	{
		collisionDebug = false ;
		soundDebug = false ; 
		coreInformationDebug = false ;
	}
	
	public static void setInFullDebug(boolean b) 
	{
		debugMode = b ;
		
		if(debugMode)
			activatedDebug() ; 
		else
			desactivateDebug() ;
		
	}

}