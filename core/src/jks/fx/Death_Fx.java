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
	/** The five ways the lab compares. */
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
		RING("Ring", 0.55f) ;

		public final String label ;
		public final float length ;

		Style(String label, float length)
		{
			this.label = label ;
			this.length = length ;
		}
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

	/** A shard, a puff, a spark : whatever a style throws. */
	private static final class Bit
	{
		TextureRegion region ;
		float x, y, vx, vy, angle, spin, size, sizeTo, life, born ;
		float width, height ;
		float phase ;
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
				return wisps(16) ;
			case DISSOLVE :
				return embers(12) ;
			case RING :
				return burst(9, Kit.spark(), 700, 1100, 110, 160, 0.03f, 0.45f, GLOW) ;
		}
		return new Bit[0] ;
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

	private Bit[] wisps(int count)
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
			bit.born = MathUtils.random(0f, 0.3f) ;
			bit.life = MathUtils.random(0.6f, 0.9f) ;
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
		drawBits(batch, true, 0) ;
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
		drawBits(batch, true, 35) ;
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
		drawBits(batch, true, 0) ;
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
		drawBits(batch, true, 0) ;
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

	/** Puffs, sparks, wisps and embers : glowing, so added to what is under them. sway : a sideways wave, in world units. */
	private void drawBits(Batch batch, boolean additive, float sway)
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
			float travel = sway > 0 ? t : bit.life * Interpolation.pow2Out.apply(k) ;
			float px = bit.x + bit.vx * travel + (sway > 0 ? MathUtils.sin(bit.phase + t * 6) * sway : 0) ;
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
		 * SpriteBatch's own shader, plus u_flash (mixes toward white) and u_burn (0 whole, 1 gone : the
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
						+ "uniform float u_burn;\n"
						+ "uniform vec3 u_edge;\n"
						+ "uniform vec2 u_noiseScale;\n"
						+ "void main()\n{\n"
						+ "  vec4 c = v_color * texture2D(u_texture, v_texCoords);\n"
						+ "  c.rgb = mix(c.rgb, vec3(1.0), u_flash);\n"
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
