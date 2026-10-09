package jks.html;

import com.google.gwt.dom.client.CanvasElement;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.Touch;

import com.badlogic.gdx.backends.gwt.DefaultGwtInput;
import com.badlogic.gdx.backends.gwt.GwtApplicationConfiguration;

/**
 * libGDX's input, with a touch mapped through the quarter turn index.html gives the page on a phone held
 * upright (r103) : the game is forced sideways, its top along the screen's right edge.
 *
 * DefaultGwtInput places a touch with the canvas's untransformed offsets, which a CSS rotate does not move :
 * turned, a tap would land a quarter turn off. Here the turn is read off the canvas's box on screen, which is
 * taller than wide only when turned (the canvas is 16:9), so the page's CSS stays the one place it is decided.
 */
class Turned_Input extends DefaultGwtInput
{
	Turned_Input(CanvasElement canvas, GwtApplicationConfiguration config)
	{super(canvas, config) ;}

	@Override
	protected int getRelativeX(NativeEvent e, CanvasElement target)
	{return turned(target) ? Math.round(alongX(target, e.getClientY())) : super.getRelativeX(e, target) ;}

	@Override
	protected int getRelativeY(NativeEvent e, CanvasElement target)
	{return turned(target) ? Math.round(alongY(target, e.getClientX())) : super.getRelativeY(e, target) ;}

	@Override
	protected int getRelativeX(Touch touch, CanvasElement target)
	{return turned(target) ? Math.round(alongX(target, touch.getClientY())) : super.getRelativeX(touch, target) ;}

	@Override
	protected int getRelativeY(Touch touch, CanvasElement target)
	{return turned(target) ? Math.round(alongY(target, touch.getClientX())) : super.getRelativeY(touch, target) ;}

	private static native boolean turned(CanvasElement canvas)
	/*-{
		var box = canvas.getBoundingClientRect() ;
		return box.height > box.width ;
	}-*/;

	/** The canvas's x runs down the screen : from the box's top, in canvas pixels. */
	private static native float alongX(CanvasElement canvas, int clientY)
	/*-{
		var box = canvas.getBoundingClientRect() ;
		return (clientY - box.top) * canvas.width / box.height ;
	}-*/;

	/** The canvas's y runs right to left : from the box's right edge, in canvas pixels. */
	private static native float alongY(CanvasElement canvas, int clientX)
	/*-{
		var box = canvas.getBoundingClientRect() ;
		return (box.right - clientX) * canvas.height / box.width ;
	}-*/;
}
