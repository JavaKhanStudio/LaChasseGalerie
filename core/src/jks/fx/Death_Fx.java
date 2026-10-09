package jks.fx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;

/**
 * What a monster does as it is destroyed (r98, a lab : Vue_Lab_Death shows every Style side by side).
 * One Death_Fx is one death : the frame the monster showed last, where, how big and which way it faced,
 * then update and draw until done(). It owns no body and reads no GVars_Game, so a client can play one
 * from a snapshot's destroyed monster as well as the host from its own.
 *
 * Every roll here is MathUtils.random, never GVars_Random : a picture must not take a die from the
 * simulation's one seeded stream, or a run with effects is another run than the smoke's.
 *
 * The textures (a soft dot, a ring, a four-point spark, a value noise) are made from code, not files,
 * and the one shader is GLSL ES 1.0, so it runs on a desktop and in a browser tab alike.
 */
public class Death_Fx
{
	/** The ways the lab compares, five a page : page 1 the first round, page 2 the souls of Simon's pick F. */
	public enum Style
	{
		/** Animation only : a white flash, a squash, and the skull pops to nothing with a few puffs. */
		POP("Pop", 0.5f),
		/** The frame cut into shards that fly apart, spin and fall. */
		SHATTER("Shatter", 1.1f),
		/** The skull's ghost rises and fades, and soft wisps float up with it. */
		WISP("Wisp", 1.2f),
		/** A shader burns the frame away through noise, with a glowing edge and embers. */
		DISSOLVE("Dissolve", 1f),
		/** A flash, a ring that opens, and sparks thrown outward. */
		RING("Ring", 0.55f),

		// Page 2 (F, r98) : Pop's flash and squash, and out of it the monster's soul, its silhouette lit lilac

		/** The plain F : the soul rises from the pop, swaying a little, wisps around it. */
		SOUL("Soul", 1.2f, 2),
		/** The soul thins and stretches like a candle flame, sways, and leaves a trail of wisps. */
		FLAME("Flame", 1.3f, 2),
		/** The soul shrinks into a glowing orb that spirals up, dropping sparkles. */
		ORB("Orb", 1.3f, 2),
		/** The soul rises with three afterimages lagging behind, cyan turning lilac. */
		ECHO("Echo", 1.2f, 2),
		/** The soul hovers a moment, then is called away : it streaks up and off, stretched along its flight. */
		CALLED("Called", 1.1f, 2) ;

		public final String label ;
		public final float length ;
		/** The lab page it is on, from 1. */
		public final int page ;

		Style(String label, float length)
		{this(label, length, 1) ;}

		Style(String label, float length, int page)
		{
			this.label = label ;
			this.length = length ;
			this.page = page ;
		}

		/** The styles of a page, in order. */
		public static Style[] page(int page)
		{
			java.util.List<Style> out = new java.util.ArrayList<>() ;
			for(Style style : values())
				if(style.page == page)
					out.add(style) ;
			return out.toArray(new Style[0]) ;
		}

		public static int pages()
		{return values()[values().length - 1].page ;}
	}

	/** Lilac, the monsters' wing purple lit up : the colour every style glows in. */
	public static final Color GLOW = new Color(0.82f, 0.62f, 1f, 1f) ;
	/** The night's cyan, for the wisps that are not lilac. */
	public static final Color COLD = new Color(0.62f, 0.9f, 1f, 1f) ;

	public final Style style ;
	private final TextureRegion frame ;
	/** Where the sprite's corner was, its size in world units, and which way it faced (SpriteModel's reverse). */
	private final float x, y, width, height ;
	private final boolean reverse ;
	private float time ;
	private final Bit[] bits ;
	/** A soul's own wave : where its sway starts, and which way it is called (+1 right, -1 left). */
	private final float soulPhase = MathUtils.random(MathUtils.PI2), soulSide = MathUtils.randomSign() ;

	/** A shard, a puff, a spark : whatever a style throws. */
	private static final class Bit
	{
		TextureRegion region ;
		float x, y, vx, vy, angle, spin, size, sizeTo, life, born ;
		float width, height ;
		float phase ;
		/** A sideways wave in world units : a bit that has one drifts at its own pace, one without slows down. */
		float sway ;
		final Color color = new Color(Color.WHITE) ;
	}

	public Death_Fx(Style style, TextureRegion frame, float x, float y, float width, float height, boolean reverse)
	{
		this.style = style ;
		this.frame = frame ;
		this.x = x ;
		this.y = y ;
		this.width = width ;
		this.height = height ;
		this.reverse = reverse ;
		// Made here, outside any batch : a texture made between begin and end rebinds unit 0 behind the batch's back
		Kit.prepare() ;
		this.bits = throwBits() ;
	}

	public void update(float delta)
	{time += delta ;}

	public boolean done()
	{return time >= style.length ;}

	private float centreX()
	{return x + width / 2 ;}

	private float centreY()
	{return y + height / 2 ;}

	// ---------------------------------------------------------------- what each style throws

	private Bit[] throwBits()
	{
		switch(style)
		{
			case POP :
				return burst(8, Kit.dot(), 350, 650, 40, 70, 0.07f, 0.38f, Color.WHITE) ;
			case SHATTER :
				return shards(4, 3) ;
			case WISP :
				return wisps(16, 0) ;
			case DISSOLVE :
				return embers(12) ;
			case RING :
				return burst(9, Kit.spark(), 700, 1100, 110, 160, 0.03f, 0.45f, GLOW) ;
			case SOUL :
				return join(puffs(), wisps(10, 0.15f)) ;
			case FLAME :
			case CALLED :
				return join(puffs(), trail(14, Kit.dot(), 0.2f, 0.9f, 30, 60)) ;
			case ORB :
				return join(puffs(), trail(18, Kit.spark(), 0.3f, 1.1f, 22, 40)) ;
			case ECHO :
				return join(puffs(), wisps(6, 0.2f)) ;
		}
		return new Bit[0] ;
	}

	/** Pop's puffs, fewer and smaller : under a soul they are the pop, not the show. */
	private Bit[] puffs()
	{return burst(6, Kit.dot(), 300, 520, 30, 55, 0.07f, 0.32f, Color.WHITE) ;}

	private static Bit[] join(Bit[] a, Bit[] b)
	{
		Bit[] out = java.util.Arrays.copyOf(a, a.length + b.length) ;
		System.arraycopy(b, 0, out, a.length, b.length) ;
		return out ;
	}

	/** Bits the soul drops on its way, each born where the soul is at that moment, lingering and fading. */
	private Bit[] trail(int count, TextureRegion region, float from, float to, float sizeMin, float sizeMax)
	{
		Bit[] out = new Bit[count] ;
		float[] at = new float[2] ;
		for(int i = 0 ; i < count ; i++)
		{
			Bit bit = new Bit() ;
			bit.region = region ;
			bit.born = from + (to - from) * i / count ;
			soulAt(bit.born, at) ;
			bit.x = at[0] + MathUtils.random(-0.1f, 0.1f) * width ;
			bit.y = at[1] + MathUtils.random(-0.1f, 0.1f) * height ;
			bit.vx = MathUtils.random(-25f, 25f) ;
			bit.vy = MathUtils.random(-10f, 50f) ;
			bit.sway = 10 ;
			bit.phase = MathUtils.random(MathUtils.PI2) ;
			bit.angle = MathUtils.random(360f) ;
			bit.spin = MathUtils.random(-180f, 180f) ;
			bit.size = MathUtils.random(sizeMin, sizeMax) ;
			bit.sizeTo = bit.size * 0.25f ;
			bit.life = MathUtils.random(0.35f, 0.55f) ;
			bit.color.set(i % 3 == 0 ? COLD : GLOW) ;
			out[i] = bit ;
		}
		return out ;
	}

	private Bit[] burst(int count, TextureRegion region, float speedMin, float speedMax, float sizeMin, float sizeMax, float born, float life, Color color)
	{
		Bit[] out = new Bit[count] ;
		float turn = MathUtils.random(360f) ;
		for(int i = 0 ; i < count ; i++)
		{
			Bit bit = new Bit() ;
			float angle = turn + i * 360f / count + MathUtils.random(-12f, 12f) ;
			float speed = MathUtils.random(speedMin, speedMax) ;
			bit.region = region ;
			bit.x = centreX() ;
			bit.y = centreY() ;
			bit.vx = MathUtils.cosDeg(angle) * speed ;
			bit.vy = MathUtils.sinDeg(angle) * speed ;
			bit.angle = MathUtils.random(360f) ;
			bit.spin = MathUtils.random(-360f, 360f) ;
			bit.size = MathUtils.random(sizeMin, sizeMax) ;
			bit.sizeTo = bit.size * 0.3f ;
			bit.born = born ;
			bit.life = life * MathUtils.random(0.8f, 1.1f) ;
			bit.color.set(color) ;
			out[i] = bit ;
		}
		return out ;
	}

	/** The frame in columns x rows, each piece thrown away from the centre, with gravity. */
	private Bit[] shards(int columns, int rows)
	{
		Bit[] out = new Bit[columns * rows] ;
		int regionWidth = frame.getRegionWidth() / columns, regionHeight = frame.getRegionHeight() / rows ;
		float pieceWidth = width / columns, pieceHeight = height / rows ;
		for(int column = 0 ; column < columns ; column++)
			for(int row = 0 ; row < rows ; row++)
			{
				Bit bit = new Bit() ;
				// Rows count down the texture and up the world ; a sprite that does not face right is drawn mirrored
				bit.region = new TextureRegion(frame, column * regionWidth, (rows - 1 - row) * regionHeight, regionWidth, regionHeight) ;
				int drawnColumn = reverse ? column : columns - 1 - column ;
				bit.x = x + (drawnColumn + 0.5f) * pieceWidth ;
				bit.y = y + (row + 0.5f) * pieceHeight ;
				float away = MathUtils.atan2(bit.y - centreY(), bit.x - centreX()) ;
				float speed = MathUtils.random(250f, 650f) ;
				bit.vx = MathUtils.cos(away) * speed ;
				bit.vy = MathUtils.sin(away) * speed + 350 ;
				bit.spin = MathUtils.random(-540f, 540f) ;
				bit.width = pieceWidth ;
				bit.height = pieceHeight ;
				bit.born = 0.05f ;
				bit.life = style.length - 0.05f ;
				out[column * rows + row] = bit ;
			}
		return out ;
	}

	/** Soft lights rising from the body, born from `from` on. */
	private Bit[] wisps(int count, float from)
	{
		Bit[] out = new Bit[count] ;
		for(int i = 0 ; i < count ; i++)
		{
			Bit bit = new Bit() ;
			bit.region = Kit.dot() ;
			bit.x = centreX() + MathUtils.random(-0.35f, 0.35f) * width ;
			bit.y = centreY() + MathUtils.random(-0.3f, 0.2f) * height ;
			bit.vx = MathUtils.random(-40f, 40f) ;
			bit.vy = MathUtils.random(160f, 380f) ;
			bit.phase = MathUtils.random(MathUtils.PI2) ;
			bit.size = MathUtils.random(45f, 100f) ;
			bit.sizeTo = bit.size * 0.2f ;
			bit.born = from + MathUtils.random(0f, 0.3f) ;
			bit.life = MathUtils.random(0.6f, 0.9f) ;
			bit.sway = 35 ;
			bit.color.set(i % 3 == 0 ? COLD : GLOW) ;
			out[i] = bit ;
		}
		return out ;
	}

	/** Sparks that leave the burning edge as it passes, rising. */
	private Bit[] embers(int count)
	{
		Bit[] out = new Bit[count] ;
		for(int i = 0 ; i < count ; i++)
		{
			Bit bit = new Bit() ;
			bit.region = Kit.dot() ;
			bit.x = centreX() + MathUtils.random(-0.4f, 0.4f) * width ;
			bit.y = centreY() + MathUtils.random(-0.35f, 0.35f) * height ;
			bit.vx = MathUtils.random(-60f, 60f) ;
			bit.vy = MathUtils.random(120f, 300f) ;
			bit.size = MathUtils.random(18f, 34f) ;
			bit.sizeTo = 4 ;
			bit.born = i * 0.75f / count ;
			bit.life = MathUtils.random(0.3f, 0.5f) ;
			bit.color.set(GLOW) ;
			out[i] = bit ;
		}
		return out ;
	}

	// ---------------------------------------------------------------- drawing

	/** Draws this death ; the batch is begun, with the default shader and alpha blending, and is left so. */
	public void draw(Batch batch)
	{
		switch(style)
		{
			case POP :
				drawPop(batch) ;
				break ;
			case SHATTER :
				if(time < 0.05f)
					drawSkull(batch, 1, 1, 0, 1, 1f) ;
				else
					drawShards(batch) ;
				break ;
			case WISP :
				drawWisp(batch) ;
				break ;
			case DISSOLVE :
				drawDissolve(batch) ;
				break ;
			case RING :
				drawRing(batch) ;
				break ;
			default :
				drawSoul(batch) ;
		}
	}

	private void drawPop(Batch batch)
	{
		if(time < 0.07f)
			drawSkull(batch, 1.25f, 0.8f, 0, 1, 1f) ;
		else if(time < 0.3f)
		{
			float k = (time - 0.07f) / 0.23f ;
			float scale = 1.15f * (1 - Interpolation.pow2In.apply(k)) ;
			drawSkull(batch, scale, scale * (1 + 0.35f * k), 0, 1 - k, 1 - k) ;
		}
		drawBits(batch, true) ;
	}

	// ---------------------------------------------------------------- page 2 : the soul (F)

	/** When the soul leaves the body : Pop's squash is still shrinking under it. */
	private static final float soulBorn = 0.12f ;

	/** The soul's own clock, 0 to 1 over what is left of the style once it is born. */
	private float soulK(float t)
	{return MathUtils.clamp((t - soulBorn) / (style.length - soulBorn), 0, 1) ;}

	/** Where the soul's centre is at time t (it starts on the body's), into at[0], at[1]. */
	private void soulAt(float t, float[] at)
	{
		float k = soulK(t) ;
		float dx = 0, dy = 0 ;
		switch(style)
		{
			case SOUL :
				dy = 240 * Interpolation.pow2Out.apply(k) ;
				dx = MathUtils.sin(soulPhase + k * 5) * 18 * k ;
				break ;
			case FLAME :
				// Slow at first, then drawn up : a soul pulled, not thrown
				dy = 260 * Interpolation.pow2In.apply(k) + 40 * k ;
				dx = MathUtils.sin(soulPhase + k * 9) * 28 * k ;
				break ;
			case ORB :
				float radius = 45 * (1 - k) + 10 ;
				dy = 240 * Interpolation.sineOut.apply(k) ;
				dx = MathUtils.sin(soulPhase + k * 14) * radius ;
				break ;
			case ECHO :
				dy = 260 * Interpolation.pow2Out.apply(k) ;
				break ;
			case CALLED :
				// Hovers up a little, then streaks away up and to one side
				float hover = MathUtils.clamp(k / 0.35f, 0, 1), flight = MathUtils.clamp((k - 0.35f) / 0.65f, 0, 1) ;
				dy = 50 * Interpolation.pow2Out.apply(hover) + 520 * Interpolation.pow3In.apply(flight) ;
				dx = soulSide * 420 * Interpolation.pow3In.apply(flight) + MathUtils.sin(soulPhase + k * 12) * 6 * (1 - flight) ;
				break ;
			default :
		}
		at[0] = centreX() + dx ;
		at[1] = centreY() + dy ;
	}

	private final float[] soul = new float[2] ;
	private final Color tint = new Color() ;

	private void drawSoul(Batch batch)
	{
		// Pop's beat, quicker : flash and squash, then the body shrinks away white as the soul leaves it
		if(time < 0.06f)
			drawSkull(batch, 1.25f, 0.8f, 0, 1, 1f) ;
		else if(time < 0.24f)
		{
			float k = (time - 0.06f) / 0.18f ;
			float scale = 1.1f * (1 - Interpolation.pow2In.apply(k)) ;
			drawSkull(batch, scale, scale * (1 + 0.3f * k), 0, 1 - k, 1) ;
		}
		if(time >= soulBorn)
		{
			float k = soulK(time) ;
			// It comes in quickly, and fades over its last half
			float alpha = MathUtils.clamp((time - soulBorn) / 0.08f, 0, 1) * (1 - Interpolation.pow2In.apply(MathUtils.clamp((k - 0.45f) / 0.55f, 0, 1))) ;
			soulAt(time, soul) ;
			switch(style)
			{
				case SOUL :
					halo(batch, soul[0], soul[1], 1.3f, GLOW, 0.18f * alpha) ;
					drawGhost(batch, soul[0], soul[1], 0.85f + 0.2f * k, 0.85f + 0.2f * k, 0, GLOW, 0.55f * alpha) ;
					break ;
				case FLAME :
					float sway = MathUtils.sin(soulPhase + k * 9) ;
					halo(batch, soul[0], soul[1], 1.1f, GLOW, 0.18f * alpha) ;
					drawGhost(batch, soul[0], soul[1], MathUtils.lerp(0.9f, 0.45f, k), MathUtils.lerp(0.9f, 1.6f, k), -sway * 10 * k, GLOW, 0.6f * alpha) ;
					break ;
				case ORB :
					// The ghost shrinks into the orb in its first third ; the orb holds a white core
					float into = MathUtils.clamp(k / 0.3f, 0, 1) ;
					float orb = MathUtils.lerp(0.9f, 0.45f, Interpolation.pow2Out.apply(into)) ;
					if(into < 1)
						drawGhost(batch, soul[0], soul[1], 0.9f * (1 - into) + 0.2f, 0.9f * (1 - into) + 0.2f, 0, GLOW, 0.55f * alpha * (1 - into)) ;
					halo(batch, soul[0], soul[1], orb, GLOW, 0.9f * alpha) ;
					halo(batch, soul[0], soul[1], orb * 0.4f, Color.WHITE, alpha) ;
					break ;
				case ECHO :
					// The afterimages are where the soul was a little before, fainter and colder
					for(int echo = 3 ; echo >= 0 ; echo--)
					{
						float lag = echo * 0.1f ;
						if(time - lag < soulBorn)
							continue ;
						soulAt(time - lag, soul) ;
						float scale = 0.85f + 0.2f * soulK(time - lag) ;
						tint.set(COLD).lerp(GLOW, MathUtils.clamp(k * 1.6f - echo * 0.25f, 0, 1)) ;
						drawGhost(batch, soul[0], soul[1], scale, scale, 0, tint, (echo == 0 ? 0.6f : 0.3f / echo) * alpha) ;
					}
					break ;
				case CALLED :
					float flight = MathUtils.clamp((k - 0.35f) / 0.65f, 0, 1) ;
					float stretch = Interpolation.pow2In.apply(flight) ;
					// Stretched along its flight : up and to its side, so it leans that way
					float lean = -soulSide * MathUtils.radiansToDegrees * MathUtils.atan2(420, 520) * Math.min(1, stretch * 3) ;
					halo(batch, soul[0], soul[1], 1.2f - 0.5f * stretch, GLOW, 0.18f * alpha) ;
					drawGhost(batch, soul[0], soul[1], 0.9f - 0.4f * stretch, 0.9f + 1.1f * stretch, lean, GLOW, 0.6f * alpha) ;
					break ;
				default :
			}
		}
		drawBits(batch, true) ;
	}

	/** The frame's silhouette, lit `tint` and added to what is under it : the monster's soul. */
	private void drawGhost(Batch batch, float cx, float cy, float scaleX, float scaleY, float rotation, Color tint, float alpha)
	{
		if(alpha <= 0)
			return ;
		ShaderProgram shader = Kit.shader() ;
		batch.setShader(shader) ;
		// Not all the way : 0.8 of the tint keeps the eyes and the mouth a shade darker, so it is still this monster
		shader.setUniformf("u_flash", 0.8f) ;
		shader.setUniformf("u_flashColor", tint.r, tint.g, tint.b) ;
		shader.setUniformf("u_burn", 0f) ;
		batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE) ;
		batch.setColor(1, 1, 1, alpha) ;
		drawRegion(batch, frame, cx, cy, scaleX, scaleY, rotation) ;
		batch.setColor(Color.WHITE) ;
		batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA) ;
		// Flushed by the blend change above, so the white goes back for the next flash
		shader.setUniformf("u_flashColor", 1f, 1f, 1f) ;
		batch.setShader(null) ;
	}

	/** A soft glow, `size` times the frame's width, added at cx, cy. */
	private void halo(Batch batch, float cx, float cy, float size, Color tint, float alpha)
	{
		if(alpha <= 0)
			return ;
		batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE) ;
		batch.setColor(tint.r, tint.g, tint.b, alpha) ;
		float s = width * size ;
		batch.draw(Kit.dot(), cx - s / 2, cy - s / 2, s, s) ;
		batch.setColor(Color.WHITE) ;
		batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA) ;
	}

	private void drawShards(Batch batch)
	{
		for(Bit bit : bits)
		{
			float t = time - bit.born ;
			float fade = 1 - MathUtils.clamp((t - bit.life * 0.55f) / (bit.life * 0.45f), 0, 1) ;
			float px = bit.x + bit.vx * t ;
			float py = bit.y + bit.vy * t - 0.5f * 1800 * t * t ;
			float shrink = 1 - 0.3f * (t / bit.life) ;
			batch.setColor(1, 1, 1, fade) ;
			float w = bit.width * shrink * (reverse ? 1 : -1), h = bit.height * shrink ;
			batch.draw(bit.region, px - w / 2, py - h / 2, w / 2, h / 2, w, h, 1, 1, bit.spin * t) ;
		}
		batch.setColor(Color.WHITE) ;
	}

	private void drawWisp(Batch batch)
	{
		float k = MathUtils.clamp(time / 0.7f, 0, 1) ;
		// The ghost of the skull : lilac, rising, fading, a little bigger
		batch.setColor(GLOW.r, GLOW.g, GLOW.b, 0.85f * (1 - Interpolation.pow2Out.apply(k))) ;
		drawRegion(batch, frame, centreX(), centreY() + 140 * Interpolation.pow2Out.apply(k), 1 + 0.15f * k, 1 + 0.15f * k, 0) ;
		batch.setColor(Color.WHITE) ;
		drawBits(batch, true) ;
	}

	private void drawDissolve(Batch batch)
	{
		float burn = MathUtils.clamp(time / 0.85f, 0, 1) ;
		ShaderProgram shader = Kit.shader() ;
		batch.setShader(shader) ;
		Kit.noise().bind(1) ;
		Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0) ;
		shader.setUniformi("u_noise", 1) ;
		shader.setUniformf("u_burn", burn) ;
		shader.setUniformf("u_flash", Math.max(0, 0.4f - time * 4)) ;
		shader.setUniformf("u_edge", GLOW.r, GLOW.g, GLOW.b) ;
		shader.setUniformf("u_noiseScale", 2.2f, 2.2f) ;
		drawSkull(batch, 1, 1, 0, 1, -1) ;
		batch.setShader(null) ;
		drawBits(batch, true) ;
	}

	private void drawRing(Batch batch)
	{
		if(time < 0.04f)
			drawSkull(batch, 1.1f, 1.1f, 0, 1, 1f) ;
		batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE) ;
		float k = MathUtils.clamp(time / 0.35f, 0, 1) ;
		float ring = Math.max(width, height) * (0.25f + 1.4f * Interpolation.pow3Out.apply(k)) ;
		batch.setColor(GLOW.r, GLOW.g, GLOW.b, 1 - k) ;
		batch.draw(Kit.ring(), centreX() - ring / 2, centreY() - ring / 2, ring, ring) ;
		float glow = MathUtils.clamp(1 - time / 0.15f, 0, 1) ;
		if(glow > 0)
		{
			batch.setColor(1, 1, 1, glow) ;
			float size = width * 1.1f ;
			batch.draw(Kit.dot(), centreX() - size / 2, centreY() - size / 2, size, size) ;
		}
		batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA) ;
		batch.setColor(Color.WHITE) ;
		drawBits(batch, true) ;
	}

	/**
	 * The last frame, centred where it was : scaled, turned, faded, and flashed white (0 none, 1 all,
	 * -1 : the batch's shader is not this class's, leave it be).
	 */
	private void drawSkull(Batch batch, float scaleX, float scaleY, float rotation, float alpha, float flash)
	{
		if(flash > 0)
		{
			ShaderProgram shader = Kit.shader() ;
			batch.setShader(shader) ;
			shader.setUniformf("u_flash", flash) ;
			shader.setUniformf("u_burn", 0f) ;
		}
		batch.setColor(1, 1, 1, alpha) ;
		drawRegion(batch, frame, centreX(), centreY(), scaleX, scaleY, rotation) ;
		batch.setColor(Color.WHITE) ;
		if(flash > 0)
			batch.setShader(null) ;
	}

	/** SpriteModel's mirroring : a sprite that does not face right is drawn with a negative width. */
	private void drawRegion(Batch batch, TextureRegion region, float cx, float cy, float scaleX, float scaleY, float rotation)
	{
		float w = width * (reverse ? 1 : -1) ;
		batch.draw(region, cx - w / 2, cy - height / 2, w / 2, height / 2, w, height, scaleX, scaleY, rotation) ;
	}

	/** Puffs, sparks, wisps and embers : glowing, so added to what is under them. */
	private void drawBits(Batch batch, boolean additive)
	{
		if(additive)
			batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE) ;
		for(Bit bit : bits)
		{
			float t = time - bit.born ;
			if(t < 0 || t > bit.life)
				continue ;
			float k = t / bit.life ;
			// A burst slows down ; a wisp drifts at its own pace
			float travel = bit.sway > 0 ? t : bit.life * Interpolation.pow2Out.apply(k) ;
			float px = bit.x + bit.vx * travel + (bit.sway > 0 ? MathUtils.sin(bit.phase + t * 6) * bit.sway : 0) ;
			float py = bit.y + bit.vy * travel ;
			float size = MathUtils.lerp(bit.size, bit.sizeTo, k) ;
			batch.setColor(bit.color.r, bit.color.g, bit.color.b, 1 - Interpolation.pow2In.apply(k)) ;
			batch.draw(bit.region, px - size / 2, py - size / 2, size / 2, size / 2, size, size, 1, 1, bit.angle + bit.spin * t) ;
		}
		batch.setColor(Color.WHITE) ;
		if(additive)
			batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA) ;
	}

	// ---------------------------------------------------------------- the kit

	/** What every Death_Fx draws with, made on first use. dispose() lets go of it : the next use makes it again. */
	public static final class Kit
	{
		private static TextureRegion dot, ring, spark ;
		private static Texture noise ;
		private static ShaderProgram shader ;

		static TextureRegion dot()
		{
			if(dot == null)
				dot = region(64, (dx, dy, r) -> smooth(1 - r)) ;
			return dot ;
		}

		static TextureRegion ring()
		{
			if(ring == null)
				ring = region(256, (dx, dy, r) -> (float) Math.exp(-sq((r - 0.85f) / 0.07f))) ;
			return ring ;
		}

		/** Four points : the dot's glow, pinched along both axes. */
		static TextureRegion spark()
		{
			if(spark == null)
				spark = region(64, (dx, dy, r) -> Math.min(1, smooth(1 - r) * ((float) Math.exp(-Math.abs(dx) * 9) + (float) Math.exp(-Math.abs(dy) * 9)))) ;
			return spark ;
		}

		/** Tileable value noise, two octaves, for the dissolve. */
		static Texture noise()
		{
			if(noise == null)
			{
				int size = 64 ;
				float[] coarse = grid(8), fine = grid(16) ;
				Pixmap pixmap = new Pixmap(size, size, Pixmap.Format.RGBA8888) ;
				for(int px = 0 ; px < size ; px++)
					for(int py = 0 ; py < size ; py++)
					{
						float v = 0.68f * sample(coarse, 8, px * 8f / size, py * 8f / size) + 0.32f * sample(fine, 16, px * 16f / size, py * 16f / size) ;
						pixmap.drawPixel(px, py, Color.rgba8888(v, v, v, 1)) ;
					}
				noise = new Texture(pixmap) ;
				pixmap.dispose() ;
				noise.setFilter(TextureFilter.Linear, TextureFilter.Linear) ;
				noise.setWrap(TextureWrap.Repeat, TextureWrap.Repeat) ;
			}
			return noise ;
		}

		/**
		 * SpriteBatch's own shader, plus u_flash (mixes toward u_flashColor, white unless a soul sets it) and u_burn (0 whole, 1 gone : the
		 * noise below it is cut away, and what is just above it glows u_edge).
		 */
		static ShaderProgram shader()
		{
			if(shader == null)
			{
				String vertex = "attribute vec4 " + ShaderProgram.POSITION_ATTRIBUTE + ";\n"
						+ "attribute vec4 " + ShaderProgram.COLOR_ATTRIBUTE + ";\n"
						+ "attribute vec2 " + ShaderProgram.TEXCOORD_ATTRIBUTE + "0;\n"
						+ "uniform mat4 u_projTrans;\n"
						+ "varying vec4 v_color;\n"
						+ "varying vec2 v_texCoords;\n"
						+ "void main()\n{\n"
						+ "  v_color = " + ShaderProgram.COLOR_ATTRIBUTE + ";\n"
						+ "  v_color.a = v_color.a * (255.0/254.0);\n"
						+ "  v_texCoords = " + ShaderProgram.TEXCOORD_ATTRIBUTE + "0;\n"
						+ "  gl_Position = u_projTrans * " + ShaderProgram.POSITION_ATTRIBUTE + ";\n"
						+ "}\n" ;
				String fragment = "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
						+ "varying vec4 v_color;\n"
						+ "varying vec2 v_texCoords;\n"
						+ "uniform sampler2D u_texture;\n"
						+ "uniform sampler2D u_noise;\n"
						+ "uniform float u_flash;\n"
						+ "uniform vec3 u_flashColor;\n"
						+ "uniform float u_burn;\n"
						+ "uniform vec3 u_edge;\n"
						+ "uniform vec2 u_noiseScale;\n"
						+ "void main()\n{\n"
						+ "  vec4 c = v_color * texture2D(u_texture, v_texCoords);\n"
						+ "  c.rgb = mix(c.rgb, u_flashColor, u_flash);\n"
						+ "  if(u_burn > 0.0)\n  {\n"
						+ "    float n = texture2D(u_noise, v_texCoords * u_noiseScale).r;\n"
						+ "    float cut = u_burn * 1.15 - 0.075;\n"
						+ "    if(n < cut) discard;\n"
						+ "    float edge = 1.0 - smoothstep(0.0, 0.12, n - cut);\n"
						+ "    c.rgb = mix(c.rgb, u_edge * 1.4, edge);\n"
						+ "  }\n"
						+ "  gl_FragColor = c;\n"
						+ "}\n" ;
				ShaderProgram.pedantic = false ;
				shader = new ShaderProgram(vertex, fragment) ;
				if(!shader.isCompiled())
					throw new IllegalStateException("Death_Fx's shader : " + shader.getLog()) ;
				shader.bind() ;
				shader.setUniformf("u_flashColor", 1f, 1f, 1f) ;
			}
			return shader ;
		}

		/** Makes everything now : call it outside a batch's begin and end. */
		public static void prepare()
		{
			dot() ;
			ring() ;
			spark() ;
			noise() ;
			shader() ;
		}

		public static void dispose()
		{
			for(TextureRegion region : new TextureRegion[] {dot, ring, spark})
				if(region != null)
					region.getTexture().dispose() ;
			if(noise != null)
				noise.dispose() ;
			if(shader != null)
				shader.dispose() ;
			dot = ring = spark = null ;
			noise = null ;
			shader = null ;
		}

		private interface Shape
		{
			/** Alpha at dx, dy in -1..1 from the centre, r their length. */
			float alpha(float dx, float dy, float r) ;
		}

		private static TextureRegion region(int size, Shape shape)
		{
			Pixmap pixmap = new Pixmap(size, size, Pixmap.Format.RGBA8888) ;
			for(int px = 0 ; px < size ; px++)
				for(int py = 0 ; py < size ; py++)
				{
					float dx = (px + 0.5f) / size * 2 - 1, dy = (py + 0.5f) / size * 2 - 1 ;
					float r = (float) Math.sqrt(dx * dx + dy * dy) ;
					float a = r >= 1 ? 0 : MathUtils.clamp(shape.alpha(dx, dy, r), 0, 1) ;
					pixmap.drawPixel(px, py, Color.rgba8888(1, 1, 1, a)) ;
				}
			Texture texture = new Texture(pixmap) ;
			pixmap.dispose() ;
			texture.setFilter(TextureFilter.Linear, TextureFilter.Linear) ;
			return new TextureRegion(texture) ;
		}

		private static float smooth(float v)
		{
			v = MathUtils.clamp(v, 0, 1) ;
			return v * v * (3 - 2 * v) ;
		}

		private static float sq(float v)
		{return v * v ;}

		private static float[] grid(int cells)
		{
			float[] grid = new float[cells * cells] ;
			for(int i = 0 ; i < grid.length ; i++)
				grid[i] = MathUtils.random() ;
			return grid ;
		}

		/** The grid at gx, gy, smoothly between its cells, wrapping so the texture tiles. */
		private static float sample(float[] grid, int cells, float gx, float gy)
		{
			int x0 = (int) gx, y0 = (int) gy ;
			float fx = smooth(gx - x0), fy = smooth(gy - y0) ;
			float a = grid[(y0 % cells) * cells + x0 % cells], b = grid[(y0 % cells) * cells + (x0 + 1) % cells] ;
			float c = grid[((y0 + 1) % cells) * cells + x0 % cells], d = grid[((y0 + 1) % cells) * cells + (x0 + 1) % cells] ;
			return MathUtils.lerp(MathUtils.lerp(a, b, fx), MathUtils.lerp(c, d, fx), fy) ;
		}
	}
}
