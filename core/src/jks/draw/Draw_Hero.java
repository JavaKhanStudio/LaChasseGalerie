package jks.draw;

import static jks.physic.FVars_Physic.PPM;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;

import jks.personnage.index.Enum_AnimState;
import jks.personnage.index.SIW_Data;
import jks.personnage.model.SpriteModel;

/**
 * A hero as it is DRAWN, and nothing it needs to be simulated (phase 1.5) : the animation, the blink
 * after a hit, the axe and the hearts, all from drawing fields. No body is read in here.
 *
 * Two things write those fields. PhysicSpriteHeroes extends this and copies them off its bodies at
 * the end of every act(), so the host draws exactly what it drew before the split. Vue_Client, which
 * owns no world, copies them off a snapshot. The clocks (animation, blink) are advanced by the
 * simulation's time in both cases : the host's own step, or the host's story clock on a client.
 */
public class Draw_Hero extends SpriteModel
{
	/** Hearts a hero starts with : also how the heart row is cut. */
	public int hp_max = 4 ;
	
	/** The body's centre, in metres. */
	protected float bodyX, bodyY ; 
	/** The axe body's centre in metres, and its angle in radians. */
	protected float axeX, axeY, axeAngle ; 
	protected int shownHp ; 
	protected boolean shownInvulnerable ; 
	
	public static Texture heartTexture; 
	// Shared by every hero : one per join would leak a GPU texture each time
	public static Texture axeTexture ; 
	private final Sprite spriteAxe ; 
	public Color painColor ; 
	
	protected static float baseWidth ; 
	protected static float baseHeight ; 
	private static float fractions ; 
	
	public Draw_Hero(SIW_Data index)
	{
		super(index) ; 
		currentFrame = currentState.getKeyFrame(0,false) ;
		
		baseWidth = getFrameWidth(currentFrame) ; 
		baseHeight = getFrameHeight(currentFrame) ;
		fractions = baseWidth/(hp_max-1) ; 
		
		if(axeTexture == null)
			axeTexture = new Texture("tools/double_axe.png") ; 
		spriteAxe = new Sprite(axeTexture) ;
		
		invulnerable_CurrentColor = colors[0] ; 
		painColor = index.color.cpy().sub(0, 0, 0, 0.4f) ; 
		
		if(heartTexture == null)
			heartTexture = new Texture("tools/heart.png") ; 
	}
	
	/**
	 * The clocks only a picture needs, moved on by one step of the simulation. A heart lost is seen HERE,
	 * from the hearts going down between two steps : the host and a client both pass theirs, so the
	 * lost heart's flash needs nothing in the snapshot but the hearts it already carries.
	 */
	public void advanceLook(float delta, boolean invulnerable, int hp)
	{
		update(delta) ; 
		if(invulnerable)
			invulnerable_ColorTimmmer += delta ; 
		if(hp == 1)
			lastHp_ColorTimmmer += delta ; 
		
		if(lostHeart_Timer >= 0)
		{
			lostHeart_Timer += delta ; 
			if(lostHeart_Timer >= lostHeart_blink * lostHeart_blinks)
				lostHeart_Timer = -1 ; 
		}
		// The first hearts this picture is shown are where it starts, not a loss : a client joining mid-run
		if(heartsBefore >= 0 && hp < heartsBefore)
		{
			lostHeart_Top = lostHeart_Timer >= 0 ? Math.max(lostHeart_Top, heartsBefore) : heartsBefore ; 
			lostHeart_Timer = 0 ; 
		}
		heartsBefore = hp ; 
	}
	
	/** What the next draw shows : the bodies' centres in metres, the hearts and the blink. */
	public void show(float bodyX, float bodyY, int hp, boolean invulnerable, float axeX, float axeY, float axeAngle)
	{
		this.bodyX = bodyX ; 
		this.bodyY = bodyY ; 
		this.shownHp = hp ; 
		this.shownInvulnerable = invulnerable ; 
		this.axeX = axeX ; 
		this.axeY = axeY ; 
		this.axeAngle = axeAngle ; 
	}
	
	/** The sprite's corner, from the body's centre and the frame drawn last. */
	public void placeAtBody()
	{
		position.x = bodyX * PPM - getFrameWidth(currentFrame)/ 2; 
		position.y = bodyY * PPM - getFrameHeight(currentFrame)/ 2; 
	}
	
	/** A state as the host picks it : the jump and the hit play once, standing and running loop. */
	public void showState(Enum_AnimState state)
	{
		changeAnimationState(state, state != Enum_AnimState.JUMP && state != Enum_AnimState.HURT) ; 
	}
	
	@Override
	public void draw(Batch batch) 
	{
		if(shownInvulnerable)
			colorChanging_Invul(batch) ; 
		
		super.draw(batch);
		
		batch.setColor(Color.WHITE);
		drawAxe(batch);
	}
	
	private void drawAxe(Batch batch)
	{
		 spriteAxe.setPosition(
				 (axeX * PPM) - spriteAxe.getWidth()/2 ,
				 (axeY * PPM) -spriteAxe.getHeight()/2 );

		 spriteAxe.setRotation((float)Math.toDegrees(axeAngle) + 90);
		 batch.draw(spriteAxe, 
				 	spriteAxe.getX(), spriteAxe.getY(),
				 	spriteAxe.getOriginX(),spriteAxe.getOriginY(),
	                spriteAxe.getWidth(),spriteAxe.getHeight(),
	                spriteAxe.getScaleX(),spriteAxe.getScaleY(),
	                spriteAxe.getRotation());
	}
	
	float invulnerable_ColorTimmmer ; 
	float invulnerable_timeBetween = 0.1f; 
	Color invulnerable_CurrentColor ; 
	int colorCounter = 0 ; 
	private static final float trans = 0.70f ;
	private static final float trans2 = 0.90f ;
	private static final float step1 = 0.70f ;
	private static final float step2 = 0.60f ;
	private static final float step3 = 0.50f ;
	private static final float step4 = 0.40f ;
	
	private static Color[] colors = new Color[] {
			new Color(step1,step1,step1,trans),
			new Color(step2,step2,step2,trans2),
			new Color(step3,step3,step3,trans),
			new Color(step4,step4,step4,trans2),
			new Color(step3,step3,step3,trans),
			new Color(step2,step2,step2,trans2)
	} ; 
	
	private void colorChanging_Invul(Batch batch) 
	{
		if(invulnerable_timeBetween < invulnerable_ColorTimmmer)
		{
			colorCounter ++ ; 
			if(colorCounter == colors.length)
				colorCounter = 0 ;
			
			invulnerable_CurrentColor = colors[colorCounter] ; 
			invulnerable_ColorTimmmer = 0 ; 
		}
		batch.setColor(invulnerable_CurrentColor);
	}
	
	/** A heart lost (r91) blinks this many times where it stood, each blink this long, fainter each time. */
	private static final int lostHeart_blinks = 3 ; 
	private static final float lostHeart_blink = 0.3f ; 
	/** How far into its blinks the lost heart is, -1 when none is. */
	private float lostHeart_Timer = -1 ; 
	/** The highest heart still blinking : every heart above the ones left, up to it, is lost. */
	private int lostHeart_Top ; 
	/** The hearts of the step before, -1 before the first. */
	private int heartsBefore = -1 ; 
	
	float lastHp_ColorTimmmer ; 
	float lastHp_timeBetween = 0.4f; 
	boolean drawClassic ; 
	
	public void drawHp(Batch batch)
	{
		if(shownHp > 1)
			batch.setColor(index.color);
		else
		{
			if(lastHp_ColorTimmmer > lastHp_timeBetween)
			{
				lastHp_ColorTimmmer = 0 ; 
				drawClassic = !drawClassic ; 
			}
			
			batch.setColor(drawClassic ? painColor : Color.LIGHT_GRAY);
		}
		for(int x = 1 ; x <= shownHp; x++)
			drawHeart(batch, x) ; 
		
		// The lost one, lit half of each blink, lighter than the others so it reads as a flash, and a step
		// fainter each blink (1, 0.7, 0.4) : the last one still shows over the river
		if(lostHeart_Timer >= 0 && lostHeart_Timer % lostHeart_blink < lostHeart_blink / 2)
		{
			int blink = (int) (lostHeart_Timer / lostHeart_blink) ; 
			Color fading = index.color.cpy().lerp(Color.WHITE, 0.45f) ; 
			// Clamped : an alpha past 1 packs to a transparent byte in SpriteBatch
			fading.a = MathUtils.clamp(1 - 0.3f * blink, 0, 1) ; 
			batch.setColor(fading) ; 
			for(int x = shownHp + 1 ; x <= lostHeart_Top ; x++)
				drawHeart(batch, x) ; 
		}
	}
	
	private void drawHeart(Batch batch, int x)
	{
		batch.draw(heartTexture, 
				bodyX * PPM - baseWidth + fractions * x , 
				bodyY * PPM - baseHeight/2 - fractions,
				fractions,fractions);
	}
	
	@Override
	public float getFrameWidth(TextureRegion frame)
	{return (frame.getRegionWidth() * index.scale) ;}
	
	@Override
	public float getFrameHeight(TextureRegion frame)
	{return (frame.getRegionHeight() * index.scale);}
}
