package com.sirdank.skgfx;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13.GL_TEXTURE2;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Per-pixel motion between the previous and the current real frame, for frame generation: what moves on its own
 * (knights, monsters, projectiles) is followed, not blended. Fragment shaders only (GLSL 1.20, no compute), so it runs on
 * the game's GL 2.1 compatibility context on every desktop platform.
 * <p>
 * Each real frame the scene is reduced to a luma pyramid (1/4, 1/8, 1/16 of the scene size). From the coarsest level to
 * the finest, every pixel searches a few candidate displacements around the one inherited from the level above and keeps
 * the one whose 4x4 neighbourhood matches the previous frame best (plain coarse-to-fine block matching, the same principle
 * as AMD FidelityFX Optical Flow; written from scratch for GLSL 1.20, no code taken from it). The result is a backward
 * flow: the content at a pixel of the current frame was at {@code pixel + flow} in the previous one, in pixels of the
 * finest level, plus the match cost.
 * Generated frames then sample both real frames along that flow. Render thread only; any failure is the caller's cue to
 * stop using flow (the camera reprojection and the plain blend still work).
 */
final class SkGfxFlow
{
  /** {@code -Dskgfx.fg.flow=false} keeps frame generation on camera reprojection only. */
  static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("skgfx.fg.flow"));

  static final int LEVELS = 4;
  /**
   * Candidate step sizes per level (index 0 is the finest), in that level's pixels. The first pass at the coarsest level
   * tries every displacement within {@link #WIDE} pixels (a blurred picture offers local minima to a descent); the other
   * passes only the eight neighbours of the inherited displacement.
   */
  private static final float[][] STEPS = {{1f, 0.5f, 0.5f}, {1f, 0.5f}, {1f, 0.5f}, {1f}};
  private static final float WIDE = 3f;

  /** A texture and the framebuffer that draws into it. */
  private static final class Target
  {
    int texture, framebuffer;

    Target (int width, int height, int internalFormat, int format, int type)
    {
      int saved = glGetInteger(GL_TEXTURE_BINDING_2D);
      texture = glGenTextures();
      glBindTexture(GL_TEXTURE_2D, texture);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
      glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, (java.nio.ByteBuffer) null);
      glBindTexture(GL_TEXTURE_2D, saved);
      framebuffer = glGenFramebuffers();
      glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
      int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
      if (status != GL_FRAMEBUFFER_COMPLETE) {
        delete();
        throw new IllegalStateException("flow target incomplete: 0x" + Integer.toHexString(status));
      }
    }

    void delete ()
    {
      if (framebuffer != 0) { glDeleteFramebuffers(framebuffer); framebuffer = 0; }
      if (texture != 0) { glDeleteTextures(texture); texture = 0; }
    }
  }

  private int sourceWidth, sourceHeight;
  private final int[] width = new int[LEVELS], height = new int[LEVELS];
  /** Luma pyramids, {@code luma[frame][level]}; {@code current} says which of the two is the newest frame's. */
  private final Target[][] luma = new Target[2][LEVELS];
  private int current;
  /** Flow per level, two textures each: the search refines from one into the other. */
  private final Target[][] flow = new Target[LEVELS][2];
  private int finalFlow;
  private boolean analyzed, hasPrevious, solved;

  private int downProgram, matchProgram, warpProgram;
  private int downTap, matchTexel, matchStep, matchScale, matchUse, matchRadius, matchCross;
  private int warpPhase, warpFlowTexel;

  /** Whether the newest frame was analysed and the previous one is there too, so a flow can be solved. */
  boolean canSolve ()
  {
    return analyzed && hasPrevious;
  }

  /** Whether the flow between the previous and the current frame has been solved this frame. */
  boolean solved ()
  {
    return solved;
  }

  /** Reduces the current frame ({@code colorTexture}, {@code w x h}) to its luma pyramid. */
  void analyze (int colorTexture, int w, int h)
  {
    solved = false;
    analyzed = false;
    State state = State.save();
    try {
      ensure(w, h);
      glUseProgram(downProgram);
      glActiveTexture(GL_TEXTURE0);
      for (int level = 0; level < LEVELS; level++) {
        Target target = luma[current][level];
        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
        glViewport(0, 0, width[level], height[level]);
        int srcW = level == 0 ? sourceWidth : width[level - 1];
        int srcH = level == 0 ? sourceHeight : height[level - 1];
        // Four bilinear taps one (level 0: 4 to 1) or half (2 to 1) source texel off centre average the whole block.
        float tap = level == 0 ? 1f : 0.5f;
        glUniform2f(downTap, tap / srcW, tap / srcH);
        glBindTexture(GL_TEXTURE_2D, level == 0 ? colorTexture : luma[current][level - 1].texture);
        SkGfxPipeline.drawFullScreenQuad();
      }
      analyzed = true;
    } finally {
      state.restore();
    }
  }

  /** Solves the flow from the previous frame to the current one (after {@link #analyze}). */
  void solve ()
  {
    solved = false;
    if (!canSolve()) return;
    State state = State.save();
    try {
      glUseProgram(matchProgram);
      int previous = current ^ 1;
      for (int level = LEVELS - 1; level >= 0; level--) {
        glViewport(0, 0, width[level], height[level]);
        glUniform2f(matchTexel, 1f / width[level], 1f / height[level]);
        float[] steps = STEPS[level];
        for (int pass = 0; pass < steps.length; pass++) {
          Target destination = flow[level][pass & 1];
          int source;
          float scale = 1f;
          boolean use = true;
          if (pass > 0) {
            source = flow[level][(pass - 1) & 1].texture;
          } else if (level < LEVELS - 1) {
            source = flow[level + 1][finalIndex(level + 1)].texture;
            scale = 2f; // the level above has half the pixels: its displacements double
          } else {
            source = flow[level][1].texture; // bound but unused: the coarsest search starts from zero
            use = false;
          }
          glBindFramebuffer(GL_FRAMEBUFFER, destination.framebuffer);
          glUniform1f(matchStep, steps[pass]);
          glUniform1f(matchScale, scale);
          glUniform1f(matchUse, use ? 1f : 0f);
          glUniform1f(matchRadius, level == LEVELS - 1 && pass == 0 ? WIDE : 1f);
          glUniform1f(matchCross, steps[pass] < 1f ? 1f : 0f); // the sub-pixel passes only probe the four axis neighbours
          glActiveTexture(GL_TEXTURE2);
          glBindTexture(GL_TEXTURE_2D, source);
          glActiveTexture(GL_TEXTURE1);
          glBindTexture(GL_TEXTURE_2D, luma[previous][level].texture);
          glActiveTexture(GL_TEXTURE0);
          glBindTexture(GL_TEXTURE_2D, luma[current][level].texture);
          SkGfxPipeline.drawFullScreenQuad();
        }
      }
      finalFlow = finalIndex(0);
      solved = true;
    } finally {
      state.restore();
    }
  }

  private static int finalIndex (int level)
  {
    return (STEPS[level].length - 1) & 1;
  }

  /** The finest flow (RG: pixels of the finest level, B: match cost); valid while {@link #solved()}. */
  int flowTexture ()
  {
    return flow[0][finalFlow].texture;
  }

  /** The newest frame becomes the previous one; call once per real frame, after its generated frames are drawn. */
  void swap ()
  {
    if (!analyzed) return;
    current ^= 1;
    hasPrevious = true;
    analyzed = false;
    solved = false;
  }

  /**
   * Binds the generated-frame pass: both real frames sampled along the flow, {@code phase} being the weight of the
   * current frame (0 is the previous frame, 1 the current one). Where the two samples disagree (something appeared from
   * behind something else, or the match was poor) the nearer real frame is shown as it is rather than a ghost of both.
   */
  void useWarp (float phase, int currentColor, int previousColor)
  {
    if (warpProgram == 0) {
      warpProgram = SkGfxPipeline.program(SkGfxPipeline.VERTEX_SOURCE, WARP_SOURCE);
      int saved = glGetInteger(GL_CURRENT_PROGRAM);
      glUseProgram(warpProgram);
      glUniform1i(glGetUniformLocation(warpProgram, "uCur"), 0);
      glUniform1i(glGetUniformLocation(warpProgram, "uPrev"), 1);
      glUniform1i(glGetUniformLocation(warpProgram, "uFlow"), 2);
      glUseProgram(saved);
      warpPhase = glGetUniformLocation(warpProgram, "uPhase");
      warpFlowTexel = glGetUniformLocation(warpProgram, "uFlowTexel");
    }
    glUseProgram(warpProgram);
    glUniform1f(warpPhase, phase);
    glUniform2f(warpFlowTexel, 1f / width[0], 1f / height[0]);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, flowTexture());
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, previousColor);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, currentColor);
  }

  /** (Re)creates the targets for a scene of this size, and the programs the first time. */
  private void ensure (int w, int h)
  {
    if (downProgram == 0) {
      downProgram = SkGfxPipeline.program(SkGfxPipeline.VERTEX_SOURCE, DOWN_SOURCE);
      matchProgram = SkGfxPipeline.program(SkGfxPipeline.VERTEX_SOURCE, MATCH_SOURCE);
      int saved = glGetInteger(GL_CURRENT_PROGRAM);
      glUseProgram(downProgram);
      glUniform1i(glGetUniformLocation(downProgram, "uTex"), 0);
      downTap = glGetUniformLocation(downProgram, "uTap");
      glUseProgram(matchProgram);
      glUniform1i(glGetUniformLocation(matchProgram, "uCur"), 0);
      glUniform1i(glGetUniformLocation(matchProgram, "uPrev"), 1);
      glUniform1i(glGetUniformLocation(matchProgram, "uPred"), 2);
      matchTexel = glGetUniformLocation(matchProgram, "uTexel");
      matchStep = glGetUniformLocation(matchProgram, "uStep");
      matchScale = glGetUniformLocation(matchProgram, "uPredScale");
      matchUse = glGetUniformLocation(matchProgram, "uUsePred");
      matchRadius = glGetUniformLocation(matchProgram, "uRadius");
      matchCross = glGetUniformLocation(matchProgram, "uCross");
      glUseProgram(saved);
    }
    if (luma[0][0] != null && w == sourceWidth && h == sourceHeight) return;
    deleteTargets();
    sourceWidth = w;
    sourceHeight = h;
    try {
      width[0] = Math.max(1, w / 4);
      height[0] = Math.max(1, h / 4);
      for (int level = 1; level < LEVELS; level++) {
        width[level] = Math.max(1, width[level - 1] / 2);
        height[level] = Math.max(1, height[level - 1] / 2);
      }
      for (int level = 0; level < LEVELS; level++) {
        for (int frame = 0; frame < 2; frame++) {
          luma[frame][level] = new Target(width[level], height[level], GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE);
        }
        for (int i = 0; i < 2; i++) {
          flow[level][i] = new Target(width[level], height[level], GL_RGBA16F, GL_RGBA, GL_FLOAT);
        }
      }
    } catch (RuntimeException e) {
      deleteTargets();
      throw e;
    }
    current = 0;
  }

  private void deleteTargets ()
  {
    for (int level = 0; level < LEVELS; level++) {
      for (int frame = 0; frame < 2; frame++) {
        if (luma[frame][level] != null) { luma[frame][level].delete(); luma[frame][level] = null; }
      }
      for (int i = 0; i < 2; i++) {
        if (flow[level][i] != null) { flow[level][i].delete(); flow[level][i] = null; }
      }
    }
    sourceWidth = sourceHeight = 0;
    hasPrevious = analyzed = solved = false;
  }

  /** The scene size changed or the pipeline restarted: the previous frame is gone. */
  void dispose ()
  {
    deleteTargets();
  }

  /** Everything, programs included, goes back to GL. */
  void release ()
  {
    deleteTargets();
    if (downProgram != 0) { glDeleteProgram(downProgram); downProgram = 0; }
    if (matchProgram != 0) { glDeleteProgram(matchProgram); matchProgram = 0; }
    if (warpProgram != 0) { glDeleteProgram(warpProgram); warpProgram = 0; }
  }

  /** The GL state the flow passes change, put back afterwards: the game caches its own and never expects ours. */
  private record State (int program, int framebuffer)
  {
    static State save ()
    {
      glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_TEXTURE_BIT | GL_CURRENT_BIT
        | GL_VIEWPORT_BIT | GL_SCISSOR_BIT | GL_STENCIL_BUFFER_BIT);
      State state = new State(glGetInteger(GL_CURRENT_PROGRAM), glGetInteger(GL_FRAMEBUFFER_BINDING));
      glDisable(GL_DEPTH_TEST);
      glDisable(GL_STENCIL_TEST);
      glDisable(GL_SCISSOR_TEST);
      glDisable(GL_BLEND);
      glDisable(GL_ALPHA_TEST);
      glDisable(GL_CULL_FACE);
      glColorMask(true, true, true, true);
      return state;
    }

    void restore ()
    {
      glActiveTexture(GL_TEXTURE0);
      glUseProgram(program);
      glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
      glPopAttrib();
    }
  }

  private static final String DOWN_SOURCE = """
    #version 120
    uniform sampler2D uTex;
    uniform vec2 uTap; // offset of the four taps, in source texels
    void main() {
      vec2 uv = gl_TexCoord[0].st;
      vec3 c = texture2D(uTex, uv + vec2(-uTap.x, -uTap.y)).rgb
             + texture2D(uTex, uv + vec2(uTap.x, -uTap.y)).rgb
             + texture2D(uTex, uv + vec2(-uTap.x, uTap.y)).rgb
             + texture2D(uTex, uv + vec2(uTap.x, uTap.y)).rgb;
      gl_FragColor = vec4(vec3(dot(c * 0.25, vec3(0.299, 0.587, 0.114))), 1.0);
    }
    """;

  /**
   * One refinement of the flow at one level: the inherited displacement and its eight neighbours {@code uStep} pixels
   * away compete on how well the 4x4 luma neighbourhood matches the previous frame there; ties go to the inherited one.
   * Output: RG displacement (this level's pixels), B the winning match cost.
   */
  private static final String MATCH_SOURCE = """
    #version 120
    uniform sampler2D uCur;
    uniform sampler2D uPrev;
    uniform sampler2D uPred;
    uniform vec2 uTexel;
    uniform float uStep;
    uniform float uPredScale;
    uniform float uUsePred;
    uniform float uRadius; // candidates within this many steps of the inherited displacement (at most 3)
    uniform float uCross;  // 1: only the four axis neighbours (sub-pixel refinement), 0: the whole square
    float curWindow[16];   // the current frame's 4x4 window, read once
    float cost(vec2 uv, vec2 cand) {
      float s = 0.0;
      for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
          vec2 o = (vec2(float(i), float(j)) - 1.5) * uTexel;
          s += abs(curWindow[j * 4 + i] - texture2D(uPrev, uv + o + cand * uTexel).r);
        }
      }
      return s * (1.0 / 16.0);
    }
    void main() {
      vec2 uv = gl_TexCoord[0].st;
      vec2 pred = uUsePred > 0.5 ? texture2D(uPred, uv).rg * uPredScale : vec2(0.0);
      for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
          curWindow[j * 4 + i] = texture2D(uCur, uv + (vec2(float(i), float(j)) - 1.5) * uTexel).r;
        }
      }
      vec2 best = pred;
      float bestCost = cost(uv, pred);
      // A pixel the inherited displacement already explains (most of a mostly static scene) is left alone.
      if (bestCost > 0.0025) {
        if (uUsePred > 0.5 && dot(pred, pred) > 0.0) {
          float c = cost(uv, vec2(0.0)) + 0.004;
          if (c < bestCost) { bestCost = c; best = vec2(0.0); }
        }
        for (int j = -3; j <= 3; j++) {
          for (int i = -3; i <= 3; i++) {
            if ((i == 0 && j == 0) || abs(float(i)) > uRadius || abs(float(j)) > uRadius) continue;
            if (uCross > 0.5 && i != 0 && j != 0) continue;
            vec2 cand = pred + vec2(float(i), float(j)) * uStep;
            float c = cost(uv, cand) + 0.003 * uStep * length(vec2(float(i), float(j)));
            if (c < bestCost) { bestCost = c; best = cand; }
          }
        }
      }
      gl_FragColor = vec4(best, bestCost, 1.0);
    }
    """;

  private static final String WARP_SOURCE = """
    #version 120
    uniform sampler2D uCur;
    uniform sampler2D uPrev;
    uniform sampler2D uFlow;
    uniform vec2 uFlowTexel; // one flow pixel, in texture coordinates
    uniform float uPhase;    // weight of the current frame
    void main() {
      vec2 uv = gl_TexCoord[0].st;
      // The flow lives on the current frame's grid; this pixel of the in-between frame sits (1 - phase) of the way back
      // along it, so read the flow there (one fixed-point step).
      vec3 f0 = texture2D(uFlow, uv).rgb;
      vec3 f = texture2D(uFlow, uv - (1.0 - uPhase) * f0.rg * uFlowTexel).rgb;
      vec2 b = f.rg * uFlowTexel;
      vec3 cur = texture2D(uCur, uv - (1.0 - uPhase) * b).rgb;
      vec3 prev = texture2D(uPrev, uv + uPhase * b).rgb;
      vec3 d = abs(cur - prev);
      float bad = max(max(d.r, max(d.g, d.b)) * 5.0, f.b * 8.0);
      float trust = 1.0 - smoothstep(0.6, 1.0, bad);
      vec3 warped = mix(prev, cur, uPhase);
      vec3 nearest = uPhase < 0.5 ? texture2D(uPrev, uv).rgb : texture2D(uCur, uv).rgb;
      gl_FragColor = vec4(mix(nearest, warped, trust), 1.0);
    }
    """;
}
