package jks.debug;

public class GVars_Debug 
{
	public static boolean debugMode = false ; 
	
	public static boolean soundDebug = false ;
	public static boolean collisionDebug = false ;
	public static boolean coreInformationDebug = false ;
	
	public static void setInFullDebug(boolean b) 
	{
		debugMode = b ;
		collisionDebug = b ;
		soundDebug = b ; 
		coreInformationDebug = b ;
	}

}
