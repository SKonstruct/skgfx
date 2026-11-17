package com.sirdank.skgfx;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_SAMPLES;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * The offscreen render target the game is steered into, and the full-screen pass that puts it on screen each frame:
 * bilinear scale plus optional contrast-adaptive sharpening (CAS). Written for the game's own context: GL 2.1
 * compatibility (GLSL 1.20, ARB-style framebuffer objects through the GL30 entry points the game itself uses).
 * <p>
 * Order per frame: the game draws into {@link #framebuffer()} (via the bind redirect), {@link #present(long)}
 * composites it onto the window just before the real swap, and {@link #afterSwap(long)} rebinds the offscreen target
 * so the next frame lands there too. Known limits, documented rather than handled: the game's own MSAA setting does
 * the HUD is scaled with the scene (the game's UI-scale option compensates).
 * <p>
 * Antialiasing: the game's MSAA is a window setting, so it would be lost on the offscreen target. The target is created
 * with the window's own sample count instead; the game draws into a multisampled framebuffer that is resolved into the
 * single-sample texture before presenting and before the game reads pixels back (screenshots, framebuffer copies).
 */
final class SkGfxPipeline
{
  private final IntBuffer widthBuf = BufferUtils.createIntBuffer(1);
  private final IntBuffer heightBuf = BufferUtils.createIntBuffer(1);

  private int realWidth, realHeight;
  private int framebuffer, colorTexture, depthStencil, program;
  /** Scene depth as a texture (frame generation only), for camera reprojection; 0 when unavailable. */
  private int depthTexture;
  private int reprojProgram;
  private final FloatBuffer matrixA = BufferUtils.createFloatBuffer(16), matrixB = BufferUtils.createFloatBuffer(16);
  /** Multisampled draw target; all zero when the window has no MSAA. */
  private int samples, msaaFramebuffer, msaaColor, msaaDepth;
  private int scaledWidth, scaledHeight;
  private int texelUniform, sharpenUniform, blendUniform;
  private boolean hasFrame;
  /** Previous resolved frame (single-sample), for frame generation. */
  private int historyTexture, historyFramebuffer;
  private boolean historyValid;
  private static Boolean blitAvailable;
  /** HUD target: a transparent real-resolution layer the game's 2D/HUD pass is redirected into (see beginUi). */
  private int uiFramebuffer, uiTexture, uiDepth, uiProgram;
  private boolean uiActive, inUi;
  /** Multisampled HUD target (when the game has MSAA), resolved into uiTexture before presenting. */
  private int uiSamples, uiMsaaFramebuffer, uiMsaaColor, uiMsaaDepth;
  /** Full-window intermediate for the sharpened generated frame. */
  private int genFramebuffer, genTexture;

  /**
   * Whether the framebuffer blit and multisample entry points exist. The game runs with LWJGL's NoChecks, where a
   * missing entry point is a JVM crash rather than an exception, so anything built on them is gated on this.
   */
  static boolean blitAvailable ()
  {
    if (blitAvailable == null) {
      try {
        GLCapabilities caps = GL.getCapabilities();
        blitAvailable = caps.glBlitFramebuffer != 0L && caps.glRenderbufferStorageMultisample != 0L;
      } catch (Throwable t) {
        blitAvailable = false;
      }
      System.err.println("[skgfx] framebuffer blit/multisample " + (blitAvailable ? "available" : "NOT available"));
    }
    return blitAvailable;
  }

  /** The framebuffer the game should draw into. */
  int framebuffer ()
  {
    if (inUi) return uiSamples > 0 ? uiMsaaFramebuffer : uiFramebuffer;
    return samples > 0 ? msaaFramebuffer : framebuffer;
  }

  /** Whether the game is currently drawing into the HUD layer. */
  boolean inUi ()
  {
    return inUi;
  }

  /** Whether the window size matches an orthographic projection of that size (a full-window 2D pass). */
  boolean matchesWindow (double width, double height)
  {
    return realWidth > 0 && Math.abs(width - realWidth) < 1.5 && Math.abs(height - realHeight) < 1.5;
  }

  /**
   * The 3D scene is complete: resolve it, then redirect the game into a cleared transparent HUD layer at the real window
   * resolution. The HUD is then composited over the scene (or over a generated frame) at present time, so it is never
   * warped or blended, and stays crisp when the scene is rendered at a lower scale.
   */
  void beginUi ()
  {
    if (framebuffer == 0 || realWidth == 0) return;
    resolve();
    ensureUi();
    uiActive = true;
    inUi = true;
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer());
    glPushAttrib(GL_COLOR_BUFFER_BIT | GL_SCISSOR_BIT | GL_ENABLE_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
    try {
      glDisable(GL_SCISSOR_TEST);
      glColorMask(true, true, true, true);
      glDepthMask(true);
      glStencilMask(0xFF);
      glClearColor(0f, 0f, 0f, 0f);
      glClearDepth(1.0);
      glClearStencil(0);
      glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
    } finally {
      glPopAttrib();
    }
    // The game's viewport state is the scene's (scaled); the HUD layer is real-sized.
    glViewport(0, 0, realWidth, realHeight);
  }

  /** The frame is over: the game goes back to drawing into the scene target next frame. */
  void endUi ()
  {
    uiActive = false;
    inUi = false;
  }

  private void ensureUi ()
  {
    if (uiFramebuffer != 0) return;
    int savedTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
    uiTexture = glGenTextures();
    glBindTexture(GL_TEXTURE_2D, uiTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, realWidth, realHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
    glBindTexture(GL_TEXTURE_2D, savedTexture);
    uiDepth = glGenRenderbuffers();
    glBindRenderbuffer(GL_RENDERBUFFER, uiDepth);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, realWidth, realHeight);
    glBindRenderbuffer(GL_RENDERBUFFER, 0);
    uiFramebuffer = glGenFramebuffers();
    glBindFramebuffer(GL_FRAMEBUFFER, uiFramebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, uiTexture, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, uiDepth);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
      throw new IllegalStateException("HUD framebuffer incomplete");
    }
    createUiMultisample();
    if (uiProgram == 0) {
      int vertex = compile(GL_VERTEX_SHADER, VERTEX_SOURCE);
      int fragment = compile(GL_FRAGMENT_SHADER, """
        #version 120
        uniform sampler2D uTex;
        void main() { gl_FragColor = texture2D(uTex, gl_TexCoord[0].st); }
        """);
      uiProgram = link(vertex, fragment);
      int savedProgram = glGetInteger(GL_CURRENT_PROGRAM);
      glUseProgram(uiProgram);
      glUniform1i(glGetUniformLocation(uiProgram, "uTex"), 0);
      glUseProgram(savedProgram);
    }
  }

  /** Resolves the multisampled HUD layer into its texture; leaves the game drawing into the multisampled one. */
  void resolveUi ()
  {
    if (!uiActive || uiSamples == 0) return;
    blit(uiMsaaFramebuffer, uiFramebuffer, false, realWidth, realHeight);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, inUi ? uiMsaaFramebuffer : uiFramebuffer);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, uiFramebuffer);
  }

  private void createUiMultisample ()
  {
    uiSamples = 0;
    if (samples < 2 || !blitAvailable()) return;
    try {
      uiMsaaColor = glGenRenderbuffers();
      glBindRenderbuffer(GL_RENDERBUFFER, uiMsaaColor);
      glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, realWidth, realHeight);
      uiMsaaDepth = glGenRenderbuffers();
      glBindRenderbuffer(GL_RENDERBUFFER, uiMsaaDepth);
      glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH24_STENCIL8, realWidth, realHeight);
      glBindRenderbuffer(GL_RENDERBUFFER, 0);
      uiMsaaFramebuffer = glGenFramebuffers();
      glBindFramebuffer(GL_FRAMEBUFFER, uiMsaaFramebuffer);
      glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, uiMsaaColor);
      glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, uiMsaaDepth);
      if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        throw new IllegalStateException("multisampled HUD framebuffer incomplete");
      }
      uiSamples = samples;
    } catch (Throwable t) {
      System.err.println("[skgfx] no MSAA on the HUD layer: " + t);
      disposeUiMultisample();
      while (glGetError() != GL_NO_ERROR) { }
    }
  }

  private void ensureGen ()
  {
    if (genFramebuffer != 0) return;
    int savedTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
    genTexture = glGenTextures();
    glBindTexture(GL_TEXTURE_2D, genTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, realWidth, realHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
    glBindTexture(GL_TEXTURE_2D, savedTexture);
    genFramebuffer = glGenFramebuffers();
    glBindFramebuffer(GL_FRAMEBUFFER, genFramebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, genTexture, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
      throw new IllegalStateException("generated-frame framebuffer incomplete");
    }
  }

  private void disposeUiMultisample ()
  {
    if (uiMsaaFramebuffer != 0) { glDeleteFramebuffers(uiMsaaFramebuffer); uiMsaaFramebuffer = 0; }
    if (uiMsaaColor != 0) { glDeleteRenderbuffers(uiMsaaColor); uiMsaaColor = 0; }
    if (uiMsaaDepth != 0) { glDeleteRenderbuffers(uiMsaaDepth); uiMsaaDepth = 0; }
    uiSamples = 0;
  }

  private void disposeUi ()
  {
    disposeUiMultisample();
    if (genFramebuffer != 0) { glDeleteFramebuffers(genFramebuffer); genFramebuffer = 0; }
    if (genTexture != 0) { glDeleteTextures(genTexture); genTexture = 0; }
    if (uiFramebuffer != 0) { glDeleteFramebuffers(uiFramebuffer); uiFramebuffer = 0; }
    if (uiTexture != 0) { glDeleteTextures(uiTexture); uiTexture = 0; }
    if (uiDepth != 0) { glDeleteRenderbuffers(uiDepth); uiDepth = 0; }
    uiActive = false;
    inUi = false;
  }

  private void drawFullScreenQuad ()
  {
    glBegin(GL_QUADS);
    glTexCoord2f(0f, 0f); glVertex2f(-1f, -1f);
    glTexCoord2f(1f, 0f); glVertex2f(1f, -1f);
    glTexCoord2f(1f, 1f); glVertex2f(1f, 1f);
    glTexCoord2f(0f, 1f); glVertex2f(-1f, 1f);
    glEnd();
  }

  /**
   * A read-back (screenshot) is about to happen while the game is drawing the HUD: lay the HUD over the scene so the game
   * reads the finished picture.
   */
  void compositeUiForRead ()
  {
    if (!inUi || uiFramebuffer == 0) return;
    resolveUi();
    int savedProgram = glGetInteger(GL_CURRENT_PROGRAM);
    glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_TEXTURE_BIT | GL_CURRENT_BIT
      | GL_VIEWPORT_BIT | GL_SCISSOR_BIT | GL_STENCIL_BUFFER_BIT);
    try {
      glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
      glDisable(GL_DEPTH_TEST);
      glDisable(GL_STENCIL_TEST);
      glDisable(GL_SCISSOR_TEST);
      glEnable(GL_BLEND);
      glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
      glViewport(0, 0, scaledWidth, scaledHeight);
      glUseProgram(uiProgram);
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_2D, uiTexture);
      drawFullScreenQuad();
    } finally {
      glUseProgram(savedProgram);
      glPopAttrib();
    }
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer());
    glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
  }

  /**
   * Resolves the multisampled frame into the single-sample target. Leaves reads pointed at the resolved copy, so a
   * game read-back right after sees valid single-sample pixels, and draws pointed at the multisampled target.
   */
  void resolve ()
  {
    if (samples == 0 || msaaFramebuffer == 0) return;
    blit(msaaFramebuffer, framebuffer, true);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, msaaFramebuffer);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
  }

  /** Copies the colour of one framebuffer into another of the same (scaled) size; the blit ignores scissor only when it is off. */
  private void blit (int source, int target, boolean depth)
  {
    blit(source, target, depth, scaledWidth, scaledHeight);
  }

  private void blit (int source, int target, boolean depth, int width, int height)
  {
    boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
    if (scissor) glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, source);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target);
    glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
      GL_COLOR_BUFFER_BIT | (depth && depthTexture != 0 ? GL_DEPTH_BUFFER_BIT : 0), GL_NEAREST);
    if (scissor) glEnable(GL_SCISSOR_TEST);
  }

  /**
   * Readies the frame the game just drew for presenting (target created or resized, MSAA resolved). @return false when
   * there is nothing of ours to show this frame and the window should be left as the game drew it.
   */
  boolean prepare (long window)
  {
    boolean fresh = ensure(window);
    if (fresh) historyValid = false;
    if (fresh || !hasFrame) return false;
    resolve();
    resolveUi();
    return true;
  }

  /**
   * Binds the reprojection pass: the in-between frame is the scene as a camera halfway between the two real frames
   * would see it. Each pixel is lifted into 3D using the current frame's depth, moved to where the previous and the
   * current camera would see it, and both frames are sampled there; where they disagree (things that moved on their
   * own) the current frame is used.
   */
  private void useReprojection (double[] toCur, double[] toPrev, double[] frustum, float phase)
  {
    if (reprojProgram == 0) {
      int vertex = compile(GL_VERTEX_SHADER, VERTEX_SOURCE);
      int fragment = compile(GL_FRAGMENT_SHADER, REPROJECT_SOURCE);
      reprojProgram = link(vertex, fragment);
      int saved = glGetInteger(GL_CURRENT_PROGRAM);
      glUseProgram(reprojProgram);
      glUniform1i(glGetUniformLocation(reprojProgram, "uCur"), 0);
      glUniform1i(glGetUniformLocation(reprojProgram, "uPrev"), 1);
      glUniform1i(glGetUniformLocation(reprojProgram, "uDepth"), 2);
      glUseProgram(saved);
    }
    glUseProgram(reprojProgram);
    matrixA.clear();
    matrixB.clear();
    for (int i = 0; i < 16; i++) {
      matrixA.put((float) toCur[i]);
      matrixB.put((float) toPrev[i]);
    }
    matrixA.flip();
    matrixB.flip();
    glUniformMatrix4fv(glGetUniformLocation(reprojProgram, "uToCur"), false, matrixA);
    glUniformMatrix4fv(glGetUniformLocation(reprojProgram, "uToPrev"), false, matrixB);
    glUniform4f(glGetUniformLocation(reprojProgram, "uFrustum"), (float) frustum[0], (float) frustum[1],
      (float) frustum[2], (float) frustum[3]);
    glUniform1f(glGetUniformLocation(reprojProgram, "uPhase"), phase);
    glUniform2f(glGetUniformLocation(reprojProgram, "uNearFar"), (float) frustum[4], (float) frustum[5]);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, depthTexture);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, historyTexture);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, colorTexture);
  }

  private static final String REPROJECT_SOURCE = """
    #version 120
    uniform sampler2D uCur;
    uniform sampler2D uPrev;
    uniform sampler2D uDepth;
    uniform mat4 uToCur;   // intermediate-camera eye space -> current-frame eye space
    uniform mat4 uToPrev;  // intermediate-camera eye space -> previous-frame eye space
    uniform vec4 uFrustum; // left right bottom top at the near plane
    uniform vec2 uNearFar;
    uniform float uPhase;  // how far from the previous frame to the current one this frame is
    vec3 unproject(vec2 uv, float depth) {
      float n = uNearFar.x, f = uNearFar.y;
      float ze = 2.0 * f * n / ((f - n) * (depth * 2.0 - 1.0) - (f + n));
      vec2 ndc = uv * 2.0 - 1.0;
      float x = (ndc.x * (uFrustum.y - uFrustum.x) * 0.5 + (uFrustum.y + uFrustum.x) * 0.5) / n * -ze;
      float y = (ndc.y * (uFrustum.w - uFrustum.z) * 0.5 + (uFrustum.w + uFrustum.z) * 0.5) / n * -ze;
      return vec3(x, y, ze);
    }
    vec2 project(vec3 p) {
      float n = uNearFar.x;
      float xn = (2.0 * n / (uFrustum.y - uFrustum.x) * p.x + (uFrustum.y + uFrustum.x) / (uFrustum.y - uFrustum.x) * p.z) / -p.z;
      float yn = (2.0 * n / (uFrustum.w - uFrustum.z) * p.y + (uFrustum.w + uFrustum.z) / (uFrustum.w - uFrustum.z) * p.z) / -p.z;
      return vec2(xn, yn) * 0.5 + 0.5;
    }
    bool inside(vec2 uv) { return uv.x >= 0.0 && uv.y >= 0.0 && uv.x <= 1.0 && uv.y <= 1.0; }
    void main() {
      vec2 uv = gl_TexCoord[0].st;
      vec3 pc = unproject(uv, texture2D(uDepth, uv).r);
      vec2 uvCur = project((uToCur * vec4(pc, 1.0)).xyz);
      vec2 uvPrev = project((uToPrev * vec4(pc, 1.0)).xyz);
      // Two readings of what this pixel shows: a static part of the world, which the moving camera shifted (sample both
      // frames where the camera says it is), or something that stays where it is on screen (the knight the camera
      // follows, the HUD-like effects): sample both frames at this pixel. The reading whose two samples agree better
      // wins, so the camera-locked knight is not dragged half a camera step on every generated frame (a wobble).
      vec3 curR = texture2D(uCur, inside(uvCur) ? uvCur : uv).rgb;
      vec3 prevR = texture2D(uPrev, inside(uvPrev) ? uvPrev : uv).rgb;
      vec3 curS = texture2D(uCur, uv).rgb;
      vec3 prevS = texture2D(uPrev, uv).rgb;
      vec3 dR = abs(curR - prevR);
      vec3 dS = abs(curS - prevS);
      float errR = max(dR.r, max(dR.g, dR.b));
      float errS = max(dS.r, max(dS.g, dS.b));
      vec3 color;
      if (errR <= 0.05) color = mix(prevR, curR, uPhase);        // static world: the reprojection is right
      else if (errS < errR - 0.02) color = mix(prevS, curS, uPhase); // fixed on screen: no camera shift
      else color = curS;                                         // moved on its own: show the current frame as it is
      gl_FragColor = vec4(color, 1.0);
    }
    """;

  private String overlayText;
  private boolean overlayGenerating;

  /** The fps counter text to draw over the next presents (null for none) and whether frames are being generated. */
  void setOverlay (String text, boolean generating)
  {
    overlayText = text;
    overlayGenerating = generating;
  }

  /** Whether the previous frame is available to blend with. */
  boolean canBlend ()
  {
    return historyValid;
  }

  /** Keeps the current resolved frame as the next frame's "previous". Needs the blit entry point. */
  void storeHistory ()
  {
    if (!blitAvailable()) return;
    if (historyTexture == 0) {
      int savedTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
      historyTexture = glGenTextures();
      glBindTexture(GL_TEXTURE_2D, historyTexture);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, scaledWidth, scaledHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
      glBindTexture(GL_TEXTURE_2D, savedTexture);
      historyFramebuffer = glGenFramebuffers();
      glBindFramebuffer(GL_FRAMEBUFFER, historyFramebuffer);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, historyTexture, 0);
      if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        throw new IllegalStateException("history framebuffer incomplete");
      }
    }
    blit(framebuffer, historyFramebuffer, false);
    historyValid = true;
  }

  /**
   * Composites the offscreen frame onto the window. {@code blend} is the weight of the current frame: 1 shows it as is,
   * less mixes in the stored previous frame (the generated in-between frame).
   */
  void draw (float blend)
  {
    draw(blend, null);
  }

  /** As {@link #draw(float)}; with a valid camera estimate the in-between frame is reprojected instead of mixed. */
  void draw (float blend, SkGfxCamera.Estimate camera)
  {
    // The camera as it was at this phase between the two real frames: where the scene's points are seen from there,
    // relative to the previous frame's camera (toPrev) and the current one (toCur).
    double[] toCur = null, toPrev = null;
    if (blend < 1f && camera != null && camera.valid() && depthTexture != 0 && historyValid) {
      double[] before = SkGfxCamera.step(camera.delta(), blend);
      double[] after = SkGfxCamera.step(camera.delta(), 1.0 - blend);
      if (before != null && after != null) {
        toPrev = SkGfxCamera.affineInverse(before);
        toCur = after;
      }
    }
    int savedProgram = glGetInteger(GL_CURRENT_PROGRAM);
    glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_TEXTURE_BIT
      | GL_CURRENT_BIT | GL_VIEWPORT_BIT | GL_SCISSOR_BIT | GL_STENCIL_BUFFER_BIT);
    try {
      glBindFramebuffer(GL_FRAMEBUFFER, 0);
      glDisable(GL_DEPTH_TEST);
      glDisable(GL_STENCIL_TEST);
      glDisable(GL_SCISSOR_TEST);
      glDisable(GL_BLEND);
      glDisable(GL_ALPHA_TEST);
      glColorMask(true, true, true, true);
      glViewport(0, 0, realWidth, realHeight);

      if (toCur != null && toPrev != null) {
        boolean sharpen = SkGfxHook.SHARPEN > 0f;
        if (sharpen) {
          ensureGen();
          glBindFramebuffer(GL_FRAMEBUFFER, genFramebuffer);
        }
        useReprojection(toCur, toPrev, camera.projection(), blend);
        drawFullScreenQuad();
        if (sharpen) {
          // The reprojected frame is sharpened like a real one: second pass over it, at window resolution.
          glBindFramebuffer(GL_FRAMEBUFFER, 0);
          glUseProgram(program);
          glUniform2f(texelUniform, 1f / realWidth, 1f / realHeight);
          glUniform1f(sharpenUniform, SkGfxHook.SHARPEN);
          glUniform1f(blendUniform, 1f);
          glActiveTexture(GL_TEXTURE0);
          glBindTexture(GL_TEXTURE_2D, genTexture);
          drawFullScreenQuad();
        }
      } else {
        glUseProgram(program);
        glUniform2f(texelUniform, 1f / SkGfxHook.scaled(realWidth), 1f / SkGfxHook.scaled(realHeight));
        glUniform1f(sharpenUniform, SkGfxHook.SHARPEN);
        glUniform1f(blendUniform, blend);
        if (blend < 1f) {
          glActiveTexture(GL_TEXTURE1);
          glBindTexture(GL_TEXTURE_2D, historyTexture);
        }
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, colorTexture);
        drawFullScreenQuad();
      }

      if (uiActive) {
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(uiProgram);
        glBindTexture(GL_TEXTURE_2D, uiTexture);
        drawFullScreenQuad();
      }

      if (overlayText != null) {
        try {
          SkGfxOverlay.draw(overlayText, realWidth, realHeight, overlayGenerating);
        } catch (Throwable t) {
          overlayText = null;
          System.err.println("[skgfx] counter disabled after error: " + t);
        }
      }

      if (SkGfxHook.DEBUG_TINT) {
        glEnable(GL_SCISSOR_TEST);
        glScissor(10, 10, 60, 60);
        glClearColor(0.67f, 0.29f, 0.51f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
      }
    } finally {
      glUseProgram(savedProgram);
      glPopAttrib();
    }
  }

  /** The swap left the window bound; point the game back at the offscreen target for its next frame. */
  void afterSwap (long window)
  {
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer());
    hasFrame = true;
  }

  /**
   * Creates or resizes the offscreen target to match the window. @return true when (re)created this call, meaning the
   * game hasn't rendered into it yet.
   */
  private boolean ensure (long window)
  {
    GLFW.glfwGetFramebufferSize(window, widthBuf, heightBuf);
    int width = Math.max(1, widthBuf.get(0));
    int height = Math.max(1, heightBuf.get(0));
    if (framebuffer != 0 && width == realWidth && height == realHeight) {
      return false;
    }
    dispose();
    realWidth = width;
    realHeight = height;
    scaledWidth = SkGfxHook.scaled(width);
    scaledHeight = SkGfxHook.scaled(height);
    boolean multisampled = createMultisampleTarget();

    // The game caches GL state; put the texture binding back when done (renderbuffer is rebound to 0 below).
    int savedTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
    colorTexture = glGenTextures();
    glBindTexture(GL_TEXTURE_2D, colorTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, scaledWidth, scaledHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
    glBindTexture(GL_TEXTURE_2D, savedTexture);

    framebuffer = glGenFramebuffers();
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
    if (SkGfxHook.FRAME_GEN && attachDepthTexture(savedTexture)) {
      // The scene's depth is a texture: the reprojection pass reads it.
    } else if (!multisampled) {
      // Without MSAA the game draws here directly and needs depth/stencil; with it, only the resolve target is kept.
      depthStencil = glGenRenderbuffers();
      glBindRenderbuffer(GL_RENDERBUFFER, depthStencil);
      glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, scaledWidth, scaledHeight);
      glBindRenderbuffer(GL_RENDERBUFFER, 0);
      glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthStencil);
    }
    int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE && depthTexture != 0) {
      // Depth as a texture isn't supported here: fall back to a plain depth renderbuffer, and no reprojection.
      System.err.println("[skgfx] depth texture attachment unsupported (0x" + Integer.toHexString(status) + ")");
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, 0, 0);
      glDeleteTextures(depthTexture);
      depthTexture = 0;
      if (!multisampled) {
        depthStencil = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthStencil);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, scaledWidth, scaledHeight);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthStencil);
      }
      status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    }
    if (status != GL_FRAMEBUFFER_COMPLETE) {
      throw new IllegalStateException("offscreen framebuffer incomplete: 0x" + Integer.toHexString(status));
    }

    if (program == 0) {
      program = buildProgram();
      texelUniform = glGetUniformLocation(program, "uTexel");
      sharpenUniform = glGetUniformLocation(program, "uSharp");
      blendUniform = glGetUniformLocation(program, "uBlend");
      int savedProgram = glGetInteger(GL_CURRENT_PROGRAM);
      glUseProgram(program);
      glUniform1i(glGetUniformLocation(program, "uTex"), 0);
      glUniform1i(glGetUniformLocation(program, "uPrev"), 1);
      glUseProgram(savedProgram);
    }

    hasFrame = false;
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer());
    System.err.println("[skgfx] target " + scaledWidth + "x" + scaledHeight + " for window " + width + "x" + height
      + (samples > 0 ? ", " + samples + "x MSAA" : ""));
    return true;
  }

  /** Creates the scene depth texture and attaches it to the (bound) scene framebuffer. */
  private boolean attachDepthTexture (int savedTexture)
  {
    depthTexture = glGenTextures();
    glBindTexture(GL_TEXTURE_2D, depthTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH24_STENCIL8, scaledWidth, scaledHeight, 0, GL_DEPTH_STENCIL,
      GL_UNSIGNED_INT_24_8, (java.nio.ByteBuffer) null);
    glBindTexture(GL_TEXTURE_2D, savedTexture);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, depthTexture, 0);
    return true;
  }

  /**
   * Builds a multisampled framebuffer with the window's own sample count, so the game's antialiasing setting carries
   * over. Any failure leaves {@code samples} at 0 and the plain single-sample target in use.
   */
  private boolean createMultisampleTarget ()
  {
    samples = 0;
    try {
      if (!blitAvailable()) return false;
      glBindFramebuffer(GL_FRAMEBUFFER, 0);
      int wanted = Math.min(glGetInteger(GL_SAMPLES), glGetInteger(GL_MAX_SAMPLES));
      if (wanted < 2) return false;
      msaaColor = glGenRenderbuffers();
      glBindRenderbuffer(GL_RENDERBUFFER, msaaColor);
      glRenderbufferStorageMultisample(GL_RENDERBUFFER, wanted, GL_RGBA8, scaledWidth, scaledHeight);
      msaaDepth = glGenRenderbuffers();
      glBindRenderbuffer(GL_RENDERBUFFER, msaaDepth);
      glRenderbufferStorageMultisample(GL_RENDERBUFFER, wanted, GL_DEPTH24_STENCIL8, scaledWidth, scaledHeight);
      glBindRenderbuffer(GL_RENDERBUFFER, 0);
      msaaFramebuffer = glGenFramebuffers();
      glBindFramebuffer(GL_FRAMEBUFFER, msaaFramebuffer);
      glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msaaColor);
      glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, msaaDepth);
      if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        throw new IllegalStateException("multisampled framebuffer incomplete");
      }
      samples = wanted;
      return true;
    } catch (Throwable t) {
      System.err.println("[skgfx] no MSAA on the offscreen target: " + t);
      disposeMultisample();
      while (glGetError() != GL_NO_ERROR) { }
      return false;
    }
  }

  private void disposeMultisample ()
  {
    if (msaaFramebuffer != 0) { glDeleteFramebuffers(msaaFramebuffer); msaaFramebuffer = 0; }
    if (msaaColor != 0) { glDeleteRenderbuffers(msaaColor); msaaColor = 0; }
    if (msaaDepth != 0) { glDeleteRenderbuffers(msaaDepth); msaaDepth = 0; }
    samples = 0;
  }

  void dispose ()
  {
    if (framebuffer != 0) { glDeleteFramebuffers(framebuffer); framebuffer = 0; }
    if (colorTexture != 0) { glDeleteTextures(colorTexture); colorTexture = 0; }
    if (depthStencil != 0) { glDeleteRenderbuffers(depthStencil); depthStencil = 0; }
    if (depthTexture != 0) { glDeleteTextures(depthTexture); depthTexture = 0; }
    disposeMultisample();
    disposeUi();
    if (historyFramebuffer != 0) { glDeleteFramebuffers(historyFramebuffer); historyFramebuffer = 0; }
    if (historyTexture != 0) { glDeleteTextures(historyTexture); historyTexture = 0; }
    historyValid = false;
    hasFrame = false;
  }

  private static int buildProgram ()
  {
    int vertex = compile(GL_VERTEX_SHADER, VERTEX_SOURCE);
    // The sharpening below follows the algorithm of AMD FidelityFX CAS (MIT license, Copyright (c) 2020 Advanced
    // Micro Devices, Inc.); the notice is reproduced in THIRD-PARTY-NOTICES.md at the repository root.
    // uSharp 0 is a plain bilinear fetch.
    int fragment = compile(GL_FRAGMENT_SHADER, """
      #version 120
      uniform sampler2D uTex;
      uniform sampler2D uPrev;
      uniform vec2 uTexel;
      uniform float uSharp;
      uniform float uBlend;
      // uBlend < 1 mixes in the previous frame: the generated in-between frame of the frame-generation prototype.
      vec3 fetch(vec2 p) {
        vec3 c = texture2D(uTex, p).rgb;
        return uBlend < 1.0 ? mix(texture2D(uPrev, p).rgb, c, uBlend) : c;
      }
      void main() {
        vec2 tc = gl_TexCoord[0].st;
        vec3 e = fetch(tc);
        if (uSharp <= 0.0) {
          gl_FragColor = vec4(e, 1.0);
          return;
        }
        vec3 b = fetch(tc + vec2(0.0, -uTexel.y));
        vec3 d = fetch(tc + vec2(-uTexel.x, 0.0));
        vec3 f = fetch(tc + vec2(uTexel.x, 0.0));
        vec3 h = fetch(tc + vec2(0.0, uTexel.y));
        vec3 mn = min(min(min(d, e), min(f, b)), h);
        vec3 mx = max(max(max(d, e), max(f, b)), h);
        vec3 amp = clamp(min(mn, 2.0 - mx) / (mx + 1e-5), 0.0, 1.0);
        amp = sqrt(amp);
        float peak = -1.0 / mix(8.0, 5.0, uSharp);
        vec3 w = amp * peak;
        gl_FragColor = vec4(((b + d + f + h) * w + e) / (4.0 * w + 1.0), 1.0);
      }
      """);
    return link(vertex, fragment);
  }

  private static final String VERTEX_SOURCE = """
    #version 120
    void main() {
      gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);
      gl_TexCoord[0] = gl_MultiTexCoord0;
    }
    """;

  private static int link (int vertex, int fragment)
  {
    int program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
      throw new IllegalStateException("shader link failed: " + glGetProgramInfoLog(program));
    }
    return program;
  }

  private static int compile (int type, String source)
  {
    int shader = glCreateShader(type);
    glShaderSource(shader, source);
    glCompileShader(shader);
    if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
      throw new IllegalStateException("shader compile failed: " + glGetShaderInfoLog(shader));
    }
    return shader;
  }
}
