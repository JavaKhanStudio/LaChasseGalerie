package jks.vinterface;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Button.ButtonStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;

import jks.input.Player_Inputs;

/**
 * The buttons a phone plays a run with (r87, Simon's layout A) : Left and Right bottom-left, a big Jump
 * bottom-right with the two swings above it, Swing left on the left. They press what the keyboard presses,
 * into the same Player_Inputs, so what goes to the host is the same Net_Input mask.
 *
 * Left and Right are ONE zone, split down its middle : a thumb that slides from one to the other turns the
 * hero round without lifting, and it keeps steering if it slides off the zone. The others press on touch.
 *
 * Icons only, drawn here : the skin's fonts carry ASCII and nothing like an arrow, and a word does not fit
 * a thumb's button. The Stage is the HUD's, first in the multiplexer, so a touch on a button never reaches
 * the keyboard listener behind it ; a touch anywhere else falls through as before.
 */
public class Touch_Pad
{
	/** What the first touch does with no hero on screen : ask for one, and nothing else (the online first key press). */
	public interface Joined
	{boolean joinedOrAsked() ;}

	/** In the HUD's 1280x720 world : big enough for a thumb on a phone held sideways, where 720 is ~400 px. */
	private static final float MARGIN = 24, ARROW = 150, JUMP = 180, SWING = 110, GAP = 16 ;
	/** The ice shows the game through it : the pad sits over the river. */
	private static final float ALPHA = 0.8f ;
	/**
	 * A button under a thumb, darkened : this skin's pressed ice differs from its resting ice by a few pixels'
	 * shift, which reads on a menu entry with its text but not on a button with a thumb on it.
	 */
	private static final Color PRESSED = new Color(0.62f, 0.78f, 0.9f, 1f) ;

	private final Player_Inputs buttons ;
	private final Joined joined ;
	private final Texture icons ;
	private final Texture axe ;
	private final Group pad = new Group() ;
	private final Button left, right ;
	/** The pointer steering, -1 while no thumb is on Left or Right. */
	private int steering = -1 ;

	public Touch_Pad(Stage stage, Player_Inputs buttons, Joined joined)
	{
		this.buttons = buttons ;
		this.joined = joined ;

		icons = arrows() ;
		axe = new Texture(Gdx.files.internal("tools/double_axe.png")) ;
		axe.setFilter(TextureFilter.Linear, TextureFilter.Linear) ;
		TextureRegion rightArrow = new TextureRegion(icons, 0, 0, 128, 128) ;
		TextureRegion leftArrow = new TextureRegion(rightArrow) ;
		leftArrow.flip(true, false) ;
		TextureRegion upArrow = new TextureRegion(icons, 128, 0, 128, 128) ;

		ButtonStyle ice = GVars_Interface.baseSkin.get(ButtonStyle.class) ;
		left = button(ice, leftArrow, ARROW * 0.5f) ;
		right = button(ice, rightArrow, ARROW * 0.5f) ;
		left.setBounds(MARGIN, MARGIN, ARROW, ARROW) ;
		right.setBounds(MARGIN + ARROW + GAP, MARGIN, ARROW, ARROW) ;
		// Named for the phone gate, tools/browser-gate/tabtouch.mjs, which taps them where they are drawn
		left.setName("touch-left") ;
		right.setName("touch-right") ;
		left.setTouchable(Touchable.disabled) ;
		right.setTouchable(Touchable.disabled) ;
		Group steer = new Group() ;
		steer.setBounds(0, 0, MARGIN * 2 + ARROW * 2 + GAP, MARGIN * 2 + ARROW) ;
		steer.addActor(left) ;
		steer.addActor(right) ;
		steer.addListener(steering()) ;
		pad.addActor(steer) ;

		float width = stage.getWidth() ;
		Button jump = button(ice, upArrow, JUMP * 0.5f) ;
		jump.setName("touch-jump") ;
		jump.setBounds(width - MARGIN - JUMP, MARGIN, JUMP, JUMP) ;
		jump.addListener(press(() -> buttons.jumpPressed = true)) ;
		pad.addActor(jump) ;

		Button swingRight = swing(ice, rightArrow, false) ;
		swingRight.setName("touch-swing-right") ;
		swingRight.setBounds(width - MARGIN - SWING, MARGIN + JUMP + GAP, SWING, SWING) ;
		swingRight.addListener(press(() -> buttons.powerRight = true)) ;
		pad.addActor(swingRight) ;

		Button swingLeft = swing(ice, leftArrow, true) ;
		swingLeft.setName("touch-swing-left") ;
		swingLeft.setBounds(width - MARGIN - SWING * 2 - GAP, MARGIN + JUMP + GAP, SWING, SWING) ;
		swingLeft.addListener(press(() -> buttons.powerLeft = true)) ;
		pad.addActor(swingLeft) ;

		pad.getColor().a = ALPHA ;
		pad.setVisible(false) ;
		stage.addActor(pad) ;
	}

	/** Shows the pad while the page is under a finger ; hiding it lets go of Left and Right. */
	public void show(boolean shown)
	{
		if(!shown && pad.isVisible())
			letGo() ;
		pad.setVisible(shown) ;
	}

	public void dispose()
	{
		icons.dispose() ;
		axe.dispose() ;
	}

	private InputListener steering()
	{
		return new InputListener()
		{
			@Override
			public boolean touchDown(InputEvent event, float x, float y, int pointer, int button)
			{
				if(steering >= 0 || !joined.joinedOrAsked())
					return false ;
				steering = pointer ;
				steer(x) ;
				return true ;
			}

			@Override
			public void touchDragged(InputEvent event, float x, float y, int pointer)
			{
				if(pointer == steering)
					steer(x) ;
			}

			@Override
			public void touchUp(InputEvent event, float x, float y, int pointer, int button)
			{
				if(pointer == steering)
					letGo() ;
			}
		} ;
	}

	/** Left of the gap between the two is Left, the rest Right. */
	private void steer(float x)
	{
		boolean toRight = x >= MARGIN + ARROW + GAP / 2 ;
		buttons.rightPressed = toRight ;
		buttons.leftPressed = !toRight ;
		pressed(left, !toRight) ;
		pressed(right, toRight) ;
	}

	private void letGo()
	{
		steering = -1 ;
		buttons.leftPressed = false ;
		buttons.rightPressed = false ;
		pressed(left, false) ;
		pressed(right, false) ;
	}

	/** A one-shot button : its flag is spent by the next tick, as a key's is. */
	private InputListener press(Runnable pressed)
	{
		return new InputListener()
		{
			@Override
			public boolean touchDown(InputEvent event, float x, float y, int pointer, int button)
			{
				if(joined.joinedOrAsked())
					pressed.run() ;
				Touch_Pad.pressed(event.getListenerActor(), true) ;
				return true ;
			}

			@Override
			public void touchUp(InputEvent event, float x, float y, int pointer, int button)
			{Touch_Pad.pressed(event.getListenerActor(), false) ;}
		} ;
	}

	private static void pressed(Actor button, boolean pressed)
	{button.setColor(pressed ? PRESSED : Color.WHITE) ;}

	private static Button button(ButtonStyle style, TextureRegion icon, float iconSize)
	{
		Button button = new Button(style) ;
		button.add(new Image(new TextureRegionDrawable(icon))).size(iconSize) ;
		return button ;
	}

	/** The axe, with a small arrow on the side it swings to. */
	private Button swing(ButtonStyle style, TextureRegion arrow, boolean toLeft)
	{
		Button button = new Button(style) ;
		Image head = new Image(new TextureRegionDrawable(new TextureRegion(axe))) ;
		Image side = new Image(new TextureRegionDrawable(arrow)) ;
		float icon = SWING * 0.42f, small = SWING * 0.22f ;
		if(toLeft)
		{
			button.add(side).size(small) ;
			button.add(head).size(icon) ;
		}
		else
		{
			button.add(head).size(icon) ;
			button.add(side).size(small) ;
		}
		return button ;
	}

	/** A right-pointing and an up-pointing triangle, side by side, in the colour of the skin's button text. */
	private static Texture arrows()
	{
		Pixmap pixmap = new Pixmap(256, 128, Pixmap.Format.RGBA8888) ;
		pixmap.setColor(GVars_Interface.baseSkin.getColor("button")) ;
		pixmap.fillTriangle(24, 12, 24, 116, 112, 64) ;
		pixmap.fillTriangle(128 + 12, 104, 128 + 116, 104, 128 + 64, 16) ;
		Texture texture = new Texture(pixmap) ;
		texture.setFilter(TextureFilter.Linear, TextureFilter.Linear) ;
		pixmap.dispose() ;
		return texture ;
	}
}
