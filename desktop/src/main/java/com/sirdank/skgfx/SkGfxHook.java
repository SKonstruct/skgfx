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
  /**
   * Splits the game's HUD from its 3D scene: the HUD pass is redirected into its own transparent layer at full
   * resolution and composited over the (scaled, possibly generated) scene at present time.
   */
  static final boolean HUD_SPLIT = FRAME_GEN || Boolean.getBoolean("skgfx.hudsplit");
  /** Depth-tested draws needed before an orthographic pass counts as the HUD (a 2D background comes first on menus). */
  private static final int MIN_WORLD_DRAWS = 20;
  /** Log real fps and swap spacing every few seconds (only when asked for with skgfx.stats, or frame generation is on). */
  static final boolean STATS = FRAME_GEN || Boolean.getBoolean("skgfx.stats");
  /** The fps counter in the window's top left corner; on unless {@code -Dskgfx.counter=false}. */
  static final boolean COUNTER = !"false".equalsIgnoreCase(System.getProperty("skgfx.counter"));

  /** Whether the offscreen pipeline is wanted at all; a failure turns this off for the rest of the run. */
  private static volatile boolean active = SCALE != 1f || SHARPEN > 0f || DEBUG_TINT || FRAME_GEN || HUD_SPLIT;

  private static SkGfxPipeline pipeline;
  private static final SkGfxPacer pacer = STATS || COUNTER ? new SkGfxPacer() : null;

  private static final double[] NO_PHASES = new double[0];
  private static boolean swapIntervalForced;
  private static volatile boolean overlayFailed;
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
      overlayFailed(t);
    }
  }

  /** The counter could not be drawn: it stays off for the rest of the run (and is not retried every frame). */
  static void overlayFailed (Throwable t)
  {
    overlayFailed = true;
    System.err.println("[skgfx] counter disabled after error: " + t);
  }

  static boolean overlayFailed ()
  {
    return overlayFailed;
  }

  /** Whether any of this real frame's outputs is a generated one (the counter shows green while it is). */
  static boolean generating (double[] phases)
  {
    for (double phase : phases) if (phase < 0.9999) return true;
    return false;
  }

  /** The HUD layer ended, or a resize tore it down while it was open: either way the scene's GL state is put back. */
  static boolean sceneNeedsRestoring (boolean hudBeforePrepare, boolean hudAfter)
  {
    return hudBeforePrepare || hudAfter;
  }

  /** A swap on a window other than the last one: the game recreated its window (and so its GL context). */
  static boolean isNewWindow (long last, long now)
  {
    return last != 0 && now != 0 && last != now;
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
    checkContext(window);
    SkGfxPacer p = pacer;
    boolean mayGenerate = p != null && p.beginFrame(window);
    String counter = COUNTER && !overlayFailed && p != null ? p.counterText() : null;
    SkGfxCamera.Estimate camera = null;
    if (cameraOn) {
      // Camera change between the previous frame and this one, from the world draws recorded this frame.
      try {
        trackCamera();
        camera = debugEstimate(CAMERA.estimate());
      } catch (Throwable t) {
        cameraFailed(t);
      }
    }
    // A resize while the HUD is open drops the HUD layer (and with it the answer to "was this a HUD frame"): ask first.
    boolean hudBefore = pipeline != null && pipeline.inUi();
    if (active) {
      try {
        if (pipeline == null) pipeline = new SkGfxPipeline();
        pipeline.setOverlay(counter, false);
        if (pipeline.prepare(window)) {
          double[] phases = FRAME_GEN && p != null && pipeline.canBlend() && SkGfxPipeline.blitAvailable()
            ? p.plan(mayGenerate) : NO_PHASES;
          pipeline.setOverlay(counter, generating(phases));
          pipeline.flowFrame(phases.length > 0);
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
        boolean hud = sceneNeedsRestoring(hudBefore, pipeline.inUi());
        pipeline.endUi();
        pipeline.afterSwap();
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

  private static long lastWindow;

  /**
   * The game destroyed its window and made another (a fullscreen or MSAA change): our GL objects belong to the dead
   * context. They are forgotten, not deleted (their names mean something else in the new context), and rebuilt.
   */
  private static void checkContext (long window)
  {
    if (isNewWindow(lastWindow, window) && pipeline != null) {
      System.err.println("[skgfx] the game's window was recreated: building the offscreen target again");
      pipeline = null;
      SkGfxPipeline.forgetContext();
      swapIntervalForced = false;
      blendSeparate = null;
      onDefaultFramebuffer = true;
      depthTest = false;
      worldDraws = 0;
      if (pacer != null) pacer.windowChanged();
    }
    if (window != 0) lastWindow = window;
  }

  /** Whether the game believes framebuffer 0 (the window) is bound. Only touched on the render thread. */
  private static boolean onDefaultFramebuffer = true;

  /** The game binds framebuffer 0 to draw to the screen: give it the offscreen framebuffer instead. */
  public static void bindFramebuffer (int target, int framebuffer)
  {
    boolean reads = target == GL_READ_FRAMEBUFFER;
    // Only a bind that sets the draw framebuffer says where the game draws.
    if (!reads) onDefaultFramebuffer = framebuffer == 0;
    if (active && framebuffer == 0 && pipeline != null) {
      checkContext(GLFW.glfwGetCurrentContext());
      if (pipeline != null) framebuffer = redirected(target, framebuffer, pipeline.framebuffer(), pipeline.readFramebuffer());
    }
    GL30.glBindFramebuffer(target, framebuffer);
    // The HUD layer's alpha rule belongs to the HUD layer only, not to a framebuffer of the game's own.
    if (!reads && inUi()) applyBlend();
  }

  static final int GL_READ_FRAMEBUFFER = 0x8CA8;

  /**
   * What the game's bind of {@code framebuffer} really binds: for the window (0) the offscreen target, which is the
   * multisampled one to draw into but the resolved single-sample one to read from (a multisampled framebuffer cannot be
   * read back).
   */
  static int redirected (int target, int framebuffer, int drawTarget, int readTarget)
  {
    if (framebuffer != 0) return framebuffer;
    return target == GL_READ_FRAMEBUFFER ? readTarget : drawTarget;
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
    haveViewport = true;
    if (active && onDefaultFramebuffer && !inUi()) {
      GL11.glViewport(scaledPos(x), scaledPos(y), scaledRect(width, SCALE), scaledRect(height, SCALE));
    } else {
      GL11.glViewport(x, y, width, height);
    }
  }

  public static void scissor (int x, int y, int width, int height)
  {
    sx = x; sy = y; sw = width; sh = height;
    haveScissor = true;
    if (active && onDefaultFramebuffer && !inUi()) {
      GL11.glScissor(scaledPos(x), scaledPos(y), scaledRect(width, SCALE), scaledRect(height, SCALE));
    } else {
      GL11.glScissor(x, y, width, height);
    }
  }

  // ---- HUD split: state the game believes it has, kept so it can be put back when the HUD layer ends.
  private static int vx, vy, vw, vh, sx, sy, sw, sh;
  private static boolean haveViewport, haveScissor;
  // What GL starts with: until the game says otherwise this is what it has.
  private static int blendSrc = GL11.GL_ONE, blendDst = GL11.GL_ZERO;
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
  /** Camera tracking is on until it fails once or the pipeline is disabled; the real GL calls always go through. */
  private static volatile boolean cameraOn = CAMERA != null;

  /** The camera replay threw: tracking stops (the game's own GL calls are unaffected), frames are still presented. */
  static void cameraFailed (Throwable t)
  {
    if (cameraOn) System.err.println("[skgfx] camera tracking stopped after error: " + t);
    cameraOn = false;
  }

  static boolean cameraTracking ()
  {
    return cameraOn;
  }

  public static void matrixMode (int mode)
  {
    if (cameraOn) {
      try { CAMERA.matrixMode(mode); } catch (Throwable t) { cameraFailed(t); }
    }
    GL11.glMatrixMode(mode);
  }

  public static void loadIdentity ()
  {
    if (cameraOn) {
      try { CAMERA.loadIdentity(); } catch (Throwable t) { cameraFailed(t); }
    }
    GL11.glLoadIdentity();
  }

  public static void pushMatrix ()
  {
    if (cameraOn) {
      try { CAMERA.push(); } catch (Throwable t) { cameraFailed(t); }
    }
    GL11.glPushMatrix();
  }

  public static void popMatrix ()
  {
    if (cameraOn) {
      try { CAMERA.pop(); } catch (Throwable t) { cameraFailed(t); }
    }
    GL11.glPopMatrix();
  }

  public static void translatef (float x, float y, float z)
  {
    if (cameraOn) {
      try { CAMERA.translate(x, y, z); } catch (Throwable t) { cameraFailed(t); }
    }
    GL11.glTranslatef(x, y, z);
  }

  public static void scalef (float x, float y, float z)
  {
    if (cameraOn) {
      try { CAMERA.scale(x, y, z); } catch (Throwable t) { cameraFailed(t); }
    }
    GL11.glScalef(x, y, z);
  }

  public static void rotatef (float angle, float x, float y, float z)
  {
    if (cameraOn) {
      try { CAMERA.rotate(angle, x, y, z); } catch (Throwable t) { cameraFailed(t); }
    }
    GL11.glRotatef(angle, x, y, z);
  }

  public static void loadMatrixf (java.nio.FloatBuffer m)
  {
    if (cameraOn) {
      try { CAMERA.load(m); } catch (Throwable t) { cameraFailed(t); }
    }
    GL11.glLoadMatrixf(m);
  }

  public static void multMatrixf (java.nio.FloatBuffer m)
  {
    if (cameraOn) {
      try { CAMERA.multiply(m); } catch (Throwable t) { cameraFailed(t); }
    }
    GL11.glMultMatrixf(m);
  }

  public static void frustum (double l, double r, double b, double t, double n, double f)
  {
    if (cameraOn) {
      try { CAMERA.perspective(l, r, b, t, n, f); } catch (Throwable e) { cameraFailed(e); }
    }
    GL11.glFrustum(l, r, b, t, n, f);
  }

  public static void bindTexture (int target, int texture)
  {
    if (cameraOn) {
      try { CAMERA.bindTexture(texture); } catch (Throwable t) { cameraFailed(t); }
    }
    GL11.glBindTexture(target, texture);
  }

  public static void bindBuffer (int target, int buffer)
  {
    if (cameraOn) {
      try {
        if (target == org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER) CAMERA.bindArray(buffer);
        else if (target == org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER) CAMERA.bindElement(buffer);
      } catch (Throwable t) {
        cameraFailed(t);
      }
    }
    org.lwjgl.opengl.GL15.glBindBuffer(target, buffer);
  }

  public static void drawRangeElements (int mode, int start, int end, int count, int type, long offset)
  {
    if (depthTest) worldDraws++;
    if (cameraOn) {
      try { CAMERA.draw(mode, start, end, count, type, offset); } catch (Throwable t) { cameraFailed(t); }
    }
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
    if (cameraOn) {
      try { CAMERA.orthographic(); } catch (Throwable t) { cameraFailed(t); }
    }
    if (active && onDefaultFramebuffer && worldDraws >= MIN_WORLD_DRAWS && pipeline != null && !pipeline.inUi()
      && pipeline.matchesWindow(right - left, top - bottom)) {
      try {
        pipeline.beginUi();
        if (cameraOn) {
          try { CAMERA.endWorld(); } catch (Throwable t) { cameraFailed(t); }
        }
        if (!hudAnnounced) {
          hudAnnounced = true;
          System.err.println("[skgfx] HUD layer starts after " + worldDraws + " depth-tested draws");
        }
        if (pipeline.inUi()) {
          // The scene's scaled scissor and the pipeline's real-sized viewport are not what the game believes it has.
          if (haveViewport) GL11.glViewport(vx, vy, vw, vh);
          if (haveScissor) GL11.glScissor(sx, sy, sw, sh);
          applyBlend();
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
    applyBlend();
  }

  /** Alpha blend factors for the HUD layer's coverage: it accumulates, except under additive blending (see below). */
  static int coverageSrcAlpha (int dst)
  {
    return dst == GL11.GL_ONE ? GL11.GL_ZERO : GL11.GL_ONE;
  }

  static int coverageDstAlpha (int dst)
  {
    // Additive light (dst = ONE) adds colour to what is below and covers nothing: alpha is left alone, or the
    // layer would darken the scene behind every glow by the glow's alpha.
    return dst == GL11.GL_ONE ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA;
  }

  /** The game's blend function, with coverage alpha while it is drawing the HUD layer (and only then). */
  private static void applyBlend ()
  {
    if (inUi() && onDefaultFramebuffer && blendSeparate()) {
      GL14.glBlendFuncSeparate(blendSrc, blendDst, coverageSrcAlpha(blendDst), coverageDstAlpha(blendDst));
    } else {
      GL11.glBlendFunc(blendSrc, blendDst);
    }
  }

  /** The frame's HUD layer is over: put GL back to what the scene pass expects (scaled viewport, plain blend). */
  private static void restoreSceneState ()
  {
    GL11.glBlendFunc(blendSrc, blendDst);
    if (haveViewport) GL11.glViewport(scaledPos(vx), scaledPos(vy), scaledRect(vw, SCALE), scaledRect(vh, SCALE));
    if (haveScissor) GL11.glScissor(scaledPos(sx), scaledPos(sy), scaledRect(sw, SCALE), scaledRect(sh, SCALE));
  }

  /** The size of a render target for a window dimension (at least 1: a framebuffer cannot be empty). */
  static int scaled (int size)
  {
    return Math.max(1, Math.round(size * SCALE));
  }

  /**
   * The size of a viewport or scissor rectangle for the game's. Unlike a target it may be empty: a zero-wide scissor is
   * how a fully clipped region discards everything, and rounding it up to 1 would let a pixel column through.
   */
  static int scaledRect (int size, float scale)
  {
    return size <= 0 ? size : Math.max(1, Math.round(size * scale));
  }

  private static int scaledPos (int position)
  {
    return Math.round(position * SCALE);
  }

  private static void disable (Throwable t)
  {
    boolean hud = inUi();
    active = false;
    cameraOn = false;
    System.err.println("[skgfx] disabled after error: " + t);
    t.printStackTrace();
    try {
      if (pipeline != null) pipeline.release();
    } catch (Throwable ignored) {
      // the original error is the one that matters
    }
    pipeline = null;
    // The game carries on drawing straight into the window: with what it believes it has set, not our scaled state.
    try {
      onDefaultFramebuffer = true;
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
      if (hud) GL11.glBlendFunc(blendSrc, blendDst);
      if (haveViewport) GL11.glViewport(vx, vy, vw, vh);
      if (haveScissor) GL11.glScissor(sx, sy, sw, sh);
    } catch (Throwable ignored) {
      // no usable context: nothing more to put back
    }
  }

  private static float floatProp (String key, float fallback)
  {
    return (float) SkGfxPacer.finiteOr(System.getProperty(key), fallback);
  }

  private static float clamp (float value, float min, float max)
  {
    return Math.min(max, Math.max(min, value));
  }

  private SkGfxHook () { }
}
