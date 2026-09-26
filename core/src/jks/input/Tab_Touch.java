package jks.input;

/**
 * What a browser tab knows about touch that the game cannot (r87) : html's HtmlLauncher sets
 * GVars_Heart.touch to one, and it is null on a desktop.
 *
 * A tap is already a pointer : libGDX's GWT backend hands touches to scene2d as touchDown and
 * touchUp, so a menu entry takes a tap like a click. What it does not give is a way to tell a phone
 * from a mouse, nor a soft keyboard (setOnscreenKeyboardVisible does nothing in a tab) : this is both.
 */
public interface Tab_Touch
{
	/**
	 * The page sits under a finger : a coarse pointer, or a touch since it opened. The touch
	 * controls show only then (Simon on r87 : 2 -> A), so a laptop with a touchscreen gets them after
	 * its first touch and a desktop browser never.
	 */
	boolean seen() ;

	/**
	 * Opens the phone's own keyboard on a hidden field of the page holding this text (r87 : 3 -> A).
	 * Must be called from inside the tap that asks for it, or a phone will not show a keyboard.
	 */
	void keyboard(String text, Typed typed) ;

	/** Puts the keyboard away, if it is out. */
	void closeKeyboard() ;

	/** What the soft keyboard types, as the whole of the hidden field : the game filters it. */
	interface Typed
	{
		void text(String all) ;

		/** The keyboard's Enter, or Go. */
		void enter() ;
	}
}
