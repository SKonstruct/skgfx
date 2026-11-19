package com.sirdank.skgfx;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Developer tool: records every GL/GLFW call the game makes during one or two frames, with arguments, so the render
 * pipeline's real structure (draw order, matrix and depth state, framebuffers, where the HUD starts) can be read off
 * instead of assumed. Enabled with {@code -Dskgfx.trace=true}; the call sites are rewritten generically, so it works on
 * any game version.
 * <p>
 * A capture starts when {@code skgfx-trace.trigger} appears in the working directory (checked every 30 frames, the
 * file is deleted when picked up) or at {@code -Dskgfx.trace.frame=N}, and covers {@code -Dskgfx.trace.frames} frames
 * (default 2). Output goes to {@code skgfx-trace-<n>.txt}; consecutive identical calls are collapsed.
 */
public final class SkGfxTrace
{
  static final boolean ENABLED = Boolean.getBoolean("skgfx.trace");

  private static final int AUTO_FRAME = Integer.getInteger("skgfx.trace.frame", -1);
  private static final int FRAMES = Integer.getInteger("skgfx.trace.frames", 2);
  private static final int MAX_LINES = 400_000;
  private static final File TRIGGER = new File("skgfx-trace.trigger");

  private static volatile boolean capturing;
  private static volatile Thread renderThread;
  private static final List<String> lines = new ArrayList<>();
  private static long frame;
  private static int remaining;
  private static int fileNumber;

  /** Called from every rewritten GL/GLFW call site, before the real call. */
  public static void call (String name, Object[] args)
  {
    if (!capturing || Thread.currentThread() != renderThread || lines.size() >= MAX_LINES) return;
    lines.add(format(name, args));
  }

  /** Called on the render thread right before the game's buffer swap. */
  static void onSwap ()
  {
    if (renderThread == null) renderThread = Thread.currentThread();
    if (capturing) {
      lines.add("=== swap (end of frame " + frame + ")");
      frame++;
      if (--remaining <= 0) {
        capturing = false;
        write();
      }
      return;
    }
    frame++; // frames count from 1: -Dskgfx.trace.frame=0 (like the default, -1) never matches
    if (frame == AUTO_FRAME || (frame % 30 == 0 && takeTrigger(TRIGGER))) {
      lines.clear();
      lines.add("# capture start at frame " + frame);
      remaining = FRAMES;
      capturing = true;
    }
  }

  /** Set once a trigger file could not be deleted: it would otherwise start a capture every 30 frames, for ever. */
  private static boolean triggerStuck;

  /** Whether the trigger file is there; picking it up deletes it, and one that cannot be deleted counts only once. */
  static boolean takeTrigger (File trigger)
  {
    if (triggerStuck || !trigger.exists()) return false;
    if (!trigger.delete()) {
      triggerStuck = true;
      System.err.println("[skgfx] couldn't delete " + trigger + ": it starts one capture only");
    }
    return true;
  }

  static void resetTriggerForTest ()
  {
    triggerStuck = false;
  }

  private static void write ()
  {
    File out = new File("skgfx-trace-" + (++fileNumber) + ".txt");
    try (PrintWriter w = new PrintWriter(out, StandardCharsets.UTF_8)) {
      String previous = null;
      int repeat = 0;
      for (String line : lines) {
        if (line.equals(previous)) {
          repeat++;
          continue;
        }
        flushRepeat(w, repeat);
        repeat = 0;
        w.println(line);
        previous = line;
      }
      flushRepeat(w, repeat);
      System.err.println("[skgfx] trace written: " + out.getAbsolutePath() + " (" + lines.size() + " calls)");
    } catch (IOException e) {
      System.err.println("[skgfx] couldn't write trace: " + e);
    } finally {
      lines.clear(); // a failed write must not keep up to 400,000 lines alive
    }
  }

  private static void flushRepeat (PrintWriter w, int repeat)
  {
    if (repeat > 0) w.println("    x" + (repeat + 1) + " total");
  }

  static String format (String name, Object[] args)
  {
    StringBuilder sb = new StringBuilder(name).append('(');
    for (int i = 0; i < args.length; i++) {
      if (i > 0) sb.append(", ");
      Object a = args[i];
      if (a instanceof FloatBuffer fb) {
        sb.append('[');
        int n = Math.min(16, fb.remaining());
        for (int j = 0; j < n; j++) {
          if (j > 0) sb.append(' ');
          sb.append(String.format("%.4g", fb.get(fb.position() + j)));
        }
        sb.append(']');
      } else if (a != null && a.getClass().isArray() && a.getClass().getComponentType().isPrimitive()) {
        int length = java.lang.reflect.Array.getLength(a);
        sb.append(a.getClass().getComponentType().getSimpleName()).append("[").append(length).append("]{");
        for (int j = 0; j < Math.min(16, length); j++) sb.append(j > 0 ? " " : "").append(java.lang.reflect.Array.get(a, j));
        sb.append(length > 16 ? " ...}" : "}");
      } else if (a instanceof Buffer b) {
        sb.append(b.getClass().getSimpleName()).append('#').append(b.remaining());
      } else if (a instanceof CharSequence s) {
        sb.append('"').append(s.length() > 60 ? s.subSequence(0, 60) + "..." : s).append('"');
      } else {
        sb.append(a);
      }
    }
    return sb.append(')').toString();
  }

  private SkGfxTrace () { }
}
