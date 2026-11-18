package com.sirdank.skgfx;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/**
 * The static methods the rewritten LWJGL call sites land on. Everything runs on the game's render thread with its GL
 * context current. Any failure permanently disables the pipeline and falls back to the real calls: the game must
 * always keep running, just without enhancements.
 */
public final class SkGfxHook
{
  static final float SCALE = clamp(floatProp("skgfx.scale", 1f), 0.25f, 4f);
  static final float SHARPEN = clamp(floatProp("skgfx.sharpen", 0f), 0f, 1f);
  static final boolean DEBUG_TINT = Boolean.getBoolean("skgfx.debugTint");
  /** Frame-generation prototype: show a blend of the previous and current frame between real frames. */
  static final boolean FRAME_GEN = Boolean.getBoolean("skgfx.fg");
  /** Log real fps and swap spacing every few seconds (the baseline for judging frame generation). */
  /**
   * Splits the game's HUD from its 3D scene: the HUD pass is redirected into its own transparent layer at full
   * resolution and composited over the (scaled, possibly generated) scene at present time.
   */
  static final boolean HUD_SPLIT = FRAME_GEN || Boolean.getBoolean("skgfx.hudsplit");
  /** Depth-tested draws needed before an orthographic pass counts as the HUD (a 2D background comes first on menus). */
  private static final int MIN_WORLD_DRAWS = 20;
  static final boolean STATS = FRAME_GEN || Boolean.getBoolean("skgfx.stats");
  /** Print the timing report every few seconds (only when asked for with skgfx.stats, or frame generation is on). */
  static final boolean STATS_LOG = STATS;
  /** The fps counter in the window's top left corner; on unless {@code -Dskgfx.counter=false}. */
  static final boolean COUNTER = !"false".equalsIgnoreCase(System.getProperty("skgfx.counter"));

  /** Whether the offscreen pipeline is wanted at all; a failure turns this off for the rest of the run. */
  private static volatile boolean active = SCALE != 1f || SHARPEN > 0f || DEBUG_TINT || FRAME_GEN || HUD_SPLIT;

  private static SkGfxPipeline pipeline;
  private static final SkGfxPacer pacer = STATS || COUNTER ? new SkGfxPacer() : null;

  private static final double[] NO_PHASES = new double[0];
  private static boolean swapIntervalForced;
  private static boolean overlayFailed;
  // Plain direct buffers: the class initializer runs during the agent's start-up, before the game's LWJGL is
  // necessarily visible, so it must not need LWJGL classes.
  private static final java.nio.IntBuffer sizeWidth = java.nio.ByteBuffer.allocateDirect(4)
    .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
  private static final java.nio.IntBuffer sizeHeight = java.nio.ByteBuffer.allocateDirect(4)
    .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();

  /** The counter over the window the game drew straight into (offscreen pipeline off). */
  private static void drawCounterOnWindow (long window, String text)
  {
    try {
      GLFW.glfwGetFramebufferSize(window, sizeWidth, sizeHeight);
      SkGfxOverlay.draw(text, Math.max(1, sizeWidth.get(0)), Math.max(1, sizeHeight.get(0)), false);
    } catch (Throwable t) {
      overlayFailed = true;
      System.err.println("[skgfx] counter disabled after error: " + t);
    }
  }
  /** Test aid: {@code -Dskgfx.gpuTime=true} measures the GPU time of our passes (stalls the pipeline; for measuring only). */
  private static final boolean GPU_TIME = Boolean.getBoolean("skgfx.gpuTime");

  private static void timed (SkGfxPacer p, int index, Runnable pass)
  {
    if (!GPU_TIME) {
      pass.run();
      return;
    }
    GL11.glFinish();
    long start = System.nanoTime();
    pass.run();
    GL11.glFinish();
    p.gpu(index, (System.nanoTime() - start) / 1e6);
  }

  public static void swapBuffers (long window)
  {
    if (SkGfxTrace.ENABLED) SkGfxTrace.onSwap();
    SkGfxPacer p = pacer;
    boolean mayGenerate = p != null && p.beginFrame(window);
    String counter = COUNTER && !overlayFailed && p != null ? p.counterText() : null;
    SkGfxCamera.Estimate camera = null;
    if (CAMERA != null) {
      // Camera change between the previous frame and this one, from the world draws recorded this frame.
      trackCamera();
      camera = debugEstimate(CAMERA.estimate());
    }
    if (active) {
      try {
        if (pipeline == null) pipeline = new SkGfxPipeline();
        pipeline.setOverlay(counter, FRAME_GEN);
        if (pipeline.prepare(window)) {
          double[] phases = FRAME_GEN && p != null && pipeline.canBlend() && SkGfxPipeline.blitAvailable()
            ? p.plan(mayGenerate) : NO_PHASES;
          if (phases.length > 0) {
            // The frames to show for this real frame, each the scene at its own phase between the previous real frame
            // and this one (1.0 is this one itself), a gap apart. The last is shown by the swap at the end of this
            // method; the previous real frame stays in the history until every generated frame has been drawn.
            if (SkGfxPacer.SWAP_INTERVAL_0 && !swapIntervalForced) {
              swapIntervalForced = true;
              GLFW.glfwSwapInterval(0);
            }
            for (int i = 0; i < phases.length; i++) {
              final float phase = (float) phases[i];
              final boolean realFrame = phase >= 0.9999f;
              if (i > 0) {
                p.segment(2);
                p.waitSpacing();
                p.segment(3);
              }
              final SkGfxCamera.Estimate estimate = camera;
              if (realFrame) {
                // Test aid: -Dskgfx.fg.debugHold shows a generated frame in place of the real one.
                if (DEBUG_HOLD) pipeline.draw(0.5f, estimate);
                else timed(p, 1, () -> pipeline.draw(1f));
              } else {
                timed(p, 0, () -> pipeline.draw(phase, estimate));
                p.generatedFrame();
              }
              if (i < phases.length - 1) {
                p.segment(0);
                GLFW.glfwSwapBuffers(window);
                p.segment(1);
                p.swapped();
              }
            }
            pipeline.storeHistory();
            p.segment(4);
          } else {
            pipeline.draw(1f);
            if (FRAME_GEN) pipeline.storeHistory();
            if (p != null) p.segment(0);
          }
        }
      } catch (Throwable t) {
        disable(t);
      }
    }
    if (p != null) p.segment(0);
    // Without the offscreen pipeline the game drew into the window itself: the counter goes on top of that.
    if (!active && counter != null) drawCounterOnWindow(window, counter);
    GLFW.glfwSwapBuffers(window);
    if (p != null) {
      p.segment(5);
      p.swapped();
    }
    if (active) {
      try {
        boolean hud = pipeline.inUi();
        pipeline.endUi();
        pipeline.afterSwap(window);
        if (hud) restoreSceneState();
      } catch (Throwable t) {
        disable(t);
      }
    }
    worldDraws = 0;
    if (p != null) {
      p.segment(6);
      p.endFrame();
    }
  }

  /** Whether the game believes framebuffer 0 (the window) is bound. Only touched on the render thread. */
  private static boolean onDefaultFramebuffer = true;

  /** The game binds framebuffer 0 to draw to the screen: give it the offscreen framebuffer instead. */
  public static void bindFramebuffer (int target, int framebuffer)
  {
    onDefaultFramebuffer = framebuffer == 0;
    if (active && framebuffer == 0 && pipeline != null) {
      framebuffer = pipeline.framebuffer();
    }
    GL30.glBindFramebuffer(target, framebuffer);
  }

  /**
   * Called right before the game reads pixels back (screenshots, framebuffer copies): with MSAA the multisampled
   * target can't be read directly, so it is resolved first and reads are pointed at the resolved copy.
   */
  public static void beforeRead ()
  {
    if (active && onDefaultFramebuffer && pipeline != null) {
      try {
        if (pipeline.inUi()) pipeline.compositeUiForRead();
        else pipeline.resolve();
      } catch (Throwable t) {
        disable(t);
      }
    }
  }

  /**
   * The game sizes its viewport for the window; rasterize into the scaled target instead. Its own offscreen passes
   * (bound to its own framebuffers) keep their real sizes.
   */
  public static void viewport (int x, int y, int width, int height)
  {
    vx = x; vy = y; vw = width; vh = height;
    if (active && onDefaultFramebuffer && !inUi()) {
      GL11.glViewport(scaledPos(x), scaledPos(y), scaled(width), scaled(height));
    } else {
      GL11.glViewport(x, y, width, height);
    }
  }

  public static void scissor (int x, int y, int width, int height)
  {
    sx = x; sy = y; sw = width; sh = height;
    if (active && onDefaultFramebuffer && !inUi()) {
      GL11.glScissor(scaledPos(x), scaledPos(y), scaled(width), scaled(height));
    } else {
      GL11.glScissor(x, y, width, height);
    }
  }

  // ---- HUD split: state the game believes it has, kept so it can be put back when the HUD layer ends.
  private static int vx, vy, vw, vh, sx, sy, sw, sh;
  private static int blendSrc = GL11.GL_SRC_ALPHA, blendDst = GL11.GL_ONE_MINUS_SRC_ALPHA;
  private static boolean depthTest;
  private static int worldDraws;
  private static boolean hudAnnounced;
  private static int cameraFrames, cameraValid;

  /** Test aid: {@code -Dskgfx.fg.debugShift=dx,dy,dz} replaces the camera delta with a fixed eye-space translation. */
  private static final boolean DEBUG_HOLD = Boolean.getBoolean("skgfx.fg.debugHold");
  private static final double[] DEBUG_SHIFT = parseShift(System.getProperty("skgfx.fg.debugShift"));

  /** A malformed test property is ignored with a warning: it must never stop the game from starting. */
  static double[] parseShift (String value)
  {
    if (value == null) return null;
    String[] parts = value.split(",");
    if (parts.length != 3) {
      System.err.println("[skgfx] ignoring skgfx.fg.debugShift: expected dx,dy,dz but got \"" + value + "\"");
      return null;
    }
    try {
      return SkGfxCamera.translation(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()),
        Double.parseDouble(parts[2].trim()));
    } catch (NumberFormatException e) {
      System.err.println("[skgfx] ignoring skgfx.fg.debugShift: \"" + value + "\" is not three numbers");
      return null;
    }
  }

  private static SkGfxCamera.Estimate debugEstimate (SkGfxCamera.Estimate real)
  {
    if (DEBUG_SHIFT == null || real.projection() == null) return real;
    return new SkGfxCamera.Estimate(DEBUG_SHIFT, real.pairs(), real.agreeing(), real.projection());
  }

  private static void trackCamera ()
  {
    CAMERA.finishFrame();
    SkGfxCamera.Estimate e = CAMERA.estimate();
    cameraFrames++;
    if (e.valid()) cameraValid++;
    if (cameraFrames % 300 == 0) {
      String move = "";
      if (e.valid()) {
        double[] d = e.delta();
        move = String.format(", last move %.4f %.4f %.4f", d[12], d[13], d[14]);
      }
      System.err.println("[skgfx] camera: valid " + cameraValid + "/" + cameraFrames + " frames, last pairs "
        + e.pairs() + " agreeing " + e.agreeing() + move);
      cameraFrames = cameraValid = 0;
    }
  }

  // ---- camera tracking (frame generation): replays the game's matrix, bind and draw calls.
  static final SkGfxCamera CAMERA = FRAME_GEN ? new SkGfxCamera() : null;

  public static void matrixMode (int mode)
  {
    CAMERA.matrixMode(mode);
    GL11.glMatrixMode(mode);
  }

  public static void loadIdentity ()
  {
    CAMERA.loadIdentity();
    GL11.glLoadIdentity();
  }

  public static void pushMatrix ()
  {
    CAMERA.push();
    GL11.glPushMatrix();
  }

  public static void popMatrix ()
  {
    CAMERA.pop();
    GL11.glPopMatrix();
  }

  public static void translatef (float x, float y, float z)
  {
    CAMERA.translate(x, y, z);
    GL11.glTranslatef(x, y, z);
  }

  public static void scalef (float x, float y, float z)
  {
    CAMERA.scale(x, y, z);
    GL11.glScalef(x, y, z);
  }

  public static void rotatef (float angle, float x, float y, float z)
  {
    CAMERA.rotate(angle, x, y, z);
    GL11.glRotatef(angle, x, y, z);
  }

  public static void loadMatrixf (java.nio.FloatBuffer m)
  {
    CAMERA.load(m);
    GL11.glLoadMatrixf(m);
  }

  public static void multMatrixf (java.nio.FloatBuffer m)
  {
    CAMERA.multiply(m);
    GL11.glMultMatrixf(m);
  }

  public static void frustum (double l, double r, double b, double t, double n, double f)
  {
    CAMERA.perspective(l, r, b, t, n, f);
    GL11.glFrustum(l, r, b, t, n, f);
  }

  public static void bindTexture (int target, int texture)
  {
    CAMERA.bindTexture(texture);
    GL11.glBindTexture(target, texture);
  }

  public static void bindBuffer (int target, int buffer)
  {
    if (target == org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER) CAMERA.bindArray(buffer);
    else if (target == org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER) CAMERA.bindElement(buffer);
    org.lwjgl.opengl.GL15.glBindBuffer(target, buffer);
  }

  public static void drawRangeElements (int mode, int start, int end, int count, int type, long offset)
  {
    if (depthTest) worldDraws++;
    CAMERA.draw(mode, start, end, count, type, offset);
    org.lwjgl.opengl.GL12.glDrawRangeElements(mode, start, end, count, type, offset);
  }

  private static Boolean blendSeparate;

  private static boolean blendSeparate ()
  {
    if (blendSeparate == null) blendSeparate = GL.getCapabilities().glBlendFuncSeparate != 0L;
    return blendSeparate;
  }

  private static boolean inUi ()
  {
    return pipeline != null && pipeline.inUi();
  }

  public static void enable (int cap)
  {
    if (cap == GL11.GL_DEPTH_TEST) depthTest = true;
    GL11.glEnable(cap);
  }

  public static void disable (int cap)
  {
    if (cap == GL11.GL_DEPTH_TEST) depthTest = false;
    GL11.glDisable(cap);
  }

  /** Called before every game draw call: counts depth-tested draws, i.e. 3D world geometry. */
  public static void beforeDraw ()
  {
    if (depthTest) worldDraws++;
  }

  /**
   * The first full-window orthographic projection after the 3D world has been drawn is where the game starts its HUD
   * (and any later 2D passes): from there it draws into the HUD layer instead of the scene.
   */
  public static void ortho (double left, double right, double bottom, double top, double near, double far)
  {
    GL11.glOrtho(left, right, bottom, top, near, far);
    if (CAMERA != null) CAMERA.orthographic();
    if (active && onDefaultFramebuffer && worldDraws >= MIN_WORLD_DRAWS && pipeline != null && !pipeline.inUi()
      && pipeline.matchesWindow(right - left, top - bottom)) {
      try {
        pipeline.beginUi();
        if (CAMERA != null) CAMERA.endWorld();
        if (!hudAnnounced) {
          hudAnnounced = true;
          System.err.println("[skgfx] HUD layer starts after " + worldDraws + " depth-tested draws");
        }
        if (pipeline.inUi() && blendSeparate()) {
          GL14.glBlendFuncSeparate(blendSrc, blendDst, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
      } catch (Throwable t) {
        disable(t);
      }
    }
  }

  /** In the HUD layer, colour blends as the game asked but alpha accumulates as coverage, so the layer composites. */
  public static void blendFunc (int src, int dst)
  {
    blendSrc = src;
    blendDst = dst;
    if (inUi() && blendSeparate()) {
      GL14.glBlendFuncSeparate(src, dst, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
    } else {
      GL11.glBlendFunc(src, dst);
    }
  }

  /** The frame's HUD layer is over: put GL back to what the scene pass expects (scaled viewport, plain blend). */
  private static void restoreSceneState ()
  {
    GL11.glBlendFunc(blendSrc, blendDst);
    if (vw > 0) GL11.glViewport(scaledPos(vx), scaledPos(vy), scaled(vw), scaled(vh));
    if (sw > 0) GL11.glScissor(scaledPos(sx), scaledPos(sy), scaled(sw), scaled(sh));
  }

  static int scaled (int size)
  {
    return Math.max(1, Math.round(size * SCALE));
  }

  private static int scaledPos (int position)
  {
    return Math.round(position * SCALE);
  }

  private static void disable (Throwable t)
  {
    active = false;
    System.err.println("[skgfx] disabled after error: " + t);
    t.printStackTrace();
    if (pipeline != null) {
      pipeline.dispose();
      pipeline = null;
    }
  }

  private static float floatProp (String key, float fallback)
  {
    try {
      String value = System.getProperty(key);
      return value == null ? fallback : Float.parseFloat(value);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static float clamp (float value, float min, float max)
  {
    return Math.min(max, Math.max(min, value));
  }

  private SkGfxHook () { }
}
