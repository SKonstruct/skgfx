package com.sirdank.skgfx;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Manual check of the optical-flow shaders on a real GL 2.1 context (not a unit test: needs a display, and on macOS
 * {@code -XstartOnFirstThread}). A synthetic scene is shifted by a known amount between two "frames"; the flow the shaders
 * find, the in-between frame they warp and their cost are printed. Run it after changing SkGfxFlow:
 * {@code java [-XstartOnFirstThread] -cp target/classes:target/test-classes:<lwjgl jars and natives> com.sirdank.skgfx.SkGfxFlowHarness [width height]}
 */
public final class SkGfxFlowHarness
{
  private static double scene (double x, double y, int channel)
  {
    double c = channel * 1.7;
    double v = 0.5 + 0.12 * Math.sin(x * 0.011 + c + 0.8 * Math.sin(y * 0.014))
      + 0.10 * Math.sin(y * 0.017 + 0.7 * Math.sin(x * 0.013 + c))
      + 0.14 * Math.sin(x * 0.07 + c + 1.3 * Math.sin(y * 0.05))
      + 0.09 * Math.sin(y * 0.11 + 0.9 * Math.sin(x * 0.09 + c))
      + 0.06 * Math.sin((x + y) * 0.19 + c);
    return Math.max(0, Math.min(1, v));
  }

  private static int upload (int w, int h, double dx, double dy)
  {
    ByteBuffer data = BufferUtils.createByteBuffer(w * h * 4);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        for (int c = 0; c < 3; c++) {
          data.put((byte) Math.round(scene(x + dx, y + dy, c) * 255));
        }
        data.put((byte) 255);
      }
    }
    data.flip();
    int texture = glGenTextures();
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
    return texture;
  }

  private static void check (String stage)
  {
    int error = glGetError();
    if (error != GL_NO_ERROR) System.out.println("  GL error 0x" + Integer.toHexString(error) + " at " + stage);
  }

  private static void run (int w, int h, double dx, double dy)
  {
    check("start");
    int previous = upload(w, h, dx, dy); // prev(x) = scene(x + d), cur(x) = scene(x): content moved by -d
    int current = upload(w, h, 0, 0);
    check("upload");
    SkGfxFlow flow = new SkGfxFlow();
    flow.analyze(previous, w, h);
    check("analyze 1");
    flow.swap();
    flow.analyze(current, w, h);
    check("analyze 2");
    flow.solve();
    check("solve");

    int fw = Math.max(1, w / 4), fh = Math.max(1, h / 4);
    FloatBuffer pixels = BufferUtils.createFloatBuffer(fw * fh * 4);
    glBindTexture(GL_TEXTURE_2D, flow.flowTexture());
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_FLOAT, pixels);
    double ex = -dx / 4, ey = -dy / 4; // expected backward flow, in flow pixels
    int margin = 8;
    double[] errors = new double[(fw - 2 * margin) * (fh - 2 * margin)];
    int n = 0;
    double sx = 0, sy = 0;
    for (int y = margin; y < fh - margin; y++) {
      for (int x = margin; x < fw - margin; x++) {
        float fx = pixels.get((y * fw + x) * 4), fy = pixels.get((y * fw + x) * 4 + 1);
        sx += fx;
        sy += fy;
        errors[n++] = Math.hypot(fx - ex, fy - ey);
      }
    }
    Arrays.sort(errors, 0, n);
    long good = Arrays.stream(errors, 0, n).filter(e -> e < 0.3).count();
    System.out.printf("shift (%.1f, %.1f) px: expected flow (%.2f, %.2f), mean (%.2f, %.2f), median error %.3f, within 0.3 px: %.1f%%%n",
      dx, dy, ex, ey, sx / n, sy / n, errors[n / 2], 100.0 * good / n);

    // The in-between frame at phase 0.5 against the truth, and against a plain blend of the two real frames.
    int target = glGenTextures();
    glBindTexture(GL_TEXTURE_2D, target);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
    int fbo = glGenFramebuffers();
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
    glViewport(0, 0, w, h);
    float phase = 0.5f;
    flow.useWarp(phase, current, previous);
    SkGfxPipeline.drawFullScreenQuad();
    ByteBuffer out = BufferUtils.createByteBuffer(w * h * 4);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, out);
    double warpedError = 0, blendError = 0;
    int count = 0;
    for (int y = 32; y < h - 32; y++) {
      for (int x = 32; x < w - 32; x++) {
        double truth = scene(x + dx * (1 - phase), y + dy * (1 - phase), 0);
        double warped = (out.get((y * w + x) * 4) & 0xff) / 255.0;
        double blend = 0.5 * (scene(x + dx, y + dy, 0) + scene(x, y, 0));
        warpedError += Math.abs(warped - truth);
        blendError += Math.abs(blend - truth);
        count++;
      }
    }
    System.out.printf("  in-between frame: mean abs error %.4f warped vs %.4f plain blend%n", warpedError / count, blendError / count);

    // Cost of analyse + solve.
    for (int i = 0; i < 10; i++) { flow.analyze(current, w, h); flow.solve(); }
    glFinish();
    long start = System.nanoTime();
    int runs = 50;
    for (int i = 0; i < runs; i++) { flow.analyze(current, w, h); flow.solve(); }
    glFinish();
    System.out.printf("  analyse + solve at %dx%d: %.2f ms per frame (%s)%n", w, h, (System.nanoTime() - start) / 1e6 / runs, glGetString(GL_RENDERER));
    int error = glGetError();
    if (error != GL_NO_ERROR) System.out.println("  GL error 0x" + Integer.toHexString(error));

    glUseProgram(0); // the warp pass left its program current, and release() deletes it
    flow.release();
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    for (int unit = 2; unit >= 0; unit--) { // the warp pass left textures bound on all three; they are about to be deleted
      glActiveTexture(GL_TEXTURE0 + unit);
      glBindTexture(GL_TEXTURE_2D, 0);
    }
    int afterRelease = glGetError();
    glDeleteFramebuffers(fbo);
    glDeleteTextures(new int[] {target, previous, current});
    int afterDelete = glGetError();
    if (afterRelease != GL_NO_ERROR || afterDelete != GL_NO_ERROR) {
      System.out.println("  GL error after release 0x" + Integer.toHexString(afterRelease) + ", after delete 0x" + Integer.toHexString(afterDelete));
    }
  }

  public static void main (String[] args)
  {
    int w = args.length > 1 ? Integer.parseInt(args[0]) : 960;
    int h = args.length > 1 ? Integer.parseInt(args[1]) : 540;
    if (!GLFW.glfwInit()) throw new IllegalStateException("no GLFW");
    GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
    long window = GLFW.glfwCreateWindow(64, 64, "skgfx flow harness", 0, 0);
    if (window == 0) throw new IllegalStateException("no window");
    GLFW.glfwMakeContextCurrent(window);
    GL.createCapabilities();
    System.out.println("GL " + glGetString(GL_VERSION) + " / " + glGetString(GL_RENDERER));
    double[][] shifts = {{0, 0}, {4, 0}, {12, 7}, {-24, 14}, {3.5, -2.25}, {48, -30},
      {1000, 700}}; // unrelated frames: nothing matches, so no pixel can skip its search (the worst case for timing)
    for (double[] s : shifts) run(w, h, s[0], s[1]);
    GLFW.glfwDestroyWindow(window);
    GLFW.glfwTerminate();
  }
}
