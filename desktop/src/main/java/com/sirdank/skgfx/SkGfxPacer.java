package com.sirdank.skgfx;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

import java.util.concurrent.locks.LockSupport;

/**
 * Frame timing for the frame-generation prototype: measures the game's own frame interval, decides per frame whether a
 * generated frame can be shown without halving the game's real frame rate, and waits for the midpoint between two real
 * frames. Also logs real fps and swap-to-swap spacing every few seconds ({@code -Dskgfx.stats=true}, works without
 * frame generation as the baseline measurement). Render thread only.
 */
final class SkGfxPacer
{
  private static final long HITCH_NANOS = 100_000_000L;
  private static final long SLOW_FRAME_NANOS = 40_000_000L;
  /** Longest gap between two frames of one real frame's burst, whatever the interval says. */
  private static final long MAX_SPACING_NANOS = 14_000_000L;
  private static final double MAX_MULTIPLIER = 6.0;

  /** Fixed mode: how many frames are shown per real frame (1.1 to 3 is the usual range, up to 6 by property). */
  static final double MULTIPLIER = clamp(doubleProperty("skgfx.fg.multiplier", 2.0), 1.1, MAX_MULTIPLIER);
  /** Adaptive mode (the default; fixed only when a multiplier is given): the frame rate to reach, 0 meaning the screen's refresh rate. */
  static final boolean ADAPTIVE = System.getProperty("skgfx.fg.multiplier") == null;
  static final int TARGET = (int) Math.max(0, doubleProperty("skgfx.fg.target", 0));

  private static double doubleProperty (String key, double fallback)
  {
    return finiteOr(System.getProperty(key), fallback);
  }

  /** The number in a property value, or {@code fallback} when it is missing, not a number, NaN or infinite. */
  static double finiteOr (String value, double fallback)
  {
    if (value == null) return fallback;
    try {
      double parsed = Double.parseDouble(value.trim());
      return Double.isFinite(parsed) ? parsed : fallback; // "NaN" parses, and would slip past every clamp
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static double clamp (double value, double min, double max)
  {
    return Math.max(min, Math.min(max, value));
  }
  private static final long REPORT_NANOS = 5_000_000_000L;

  /** Control knob for measurements: skip the waits between frames entirely. */
  static final boolean NO_WAIT = Boolean.getBoolean("skgfx.fg.nowait");
  static final boolean SWAP_INTERVAL_0 = Boolean.parseBoolean(System.getProperty("skgfx.fg.swapinterval0", "true"));
  private static final String[] SEGMENTS = {"draw", "swap1", "store", "wait", "draw2", "swap", "after"};

  private long lastFrame, lastSwap, reportStart, segStart, hookStart, lastEnd;
  private final double[] segSum = new double[SEGMENTS.length];
  private double hookSum, outsideSum;
  private double interval;
  /** The shortest game frame interval seen lately (creeping up so it follows heavier scenes) and the wait scale. */
  private double bestInterval, waitScale = 1.0;
  private final SkGfxSchedule schedule = new SkGfxSchedule();
  private long spacing;
  private int refreshRate, framesSinceRefresh = 1_000_000;
  private int frames, generated, skippedRate, skippedHitch;
  private int swaps, presents;
  private double sum, sumSq, min = Double.MAX_VALUE, max;

  /**
   * Call at the top of the swap hook. @return true when a generated frame may be shown before this real one: the game
   * frame interval is known and steady, and twice the game fps fits the display refresh.
   */
  boolean beginFrame (long window)
  {
    long now = System.nanoTime();
    boolean ok = false;
    hookStart = segStart = now;
    if (lastEnd != 0) outsideSum += now - lastEnd;
    if (lastFrame != 0) ok = frameTime(now - lastFrame);
    lastFrame = now;
    frames++;
    counterFrames++;
    if (reportStart == 0) reportStart = now;
    if (++framesSinceRefresh > 300) {
      framesSinceRefresh = 0;
      refreshRate = refreshRate(window);
    }
    // Below ~25 fps generating makes things worse, not better (and a wait that is a fraction of a slow frame costs the
    // game real frame rate), so stop until the game recovers; without our waits its frame interval drops back by itself.
    if (ok && interval > SLOW_FRAME_NANOS) {
      skippedRate++;
      ok = false;
    }
    return ok;
  }

  /** Frames in a row that are too long for the current interval, but not hitches, before they become the interval. */
  static final int REGIME_FRAMES = 5;

  private int regimeFrames;

  /**
   * Takes one frame time (nanoseconds since the previous frame). @return true when the frame is steady enough to
   * generate for: it fits the interval learned so far.
   * <p>
   * A single long frame is a hitch and is skipped. Several long-but-not-huge frames in a row are not hitches, they are
   * the game changing speed (a fast menu, then a steady slower zone): without adopting that new rhythm every frame
   * would look like a hitch against the old, shorter interval and generation would stay off for good.
   */
  boolean frameTime (long dt)
  {
    boolean tooLong = dt > HITCH_NANOS;
    if (tooLong || (interval > 0 && dt > 2 * interval)) {
      skippedHitch++;
      regimeFrames = tooLong ? 0 : regimeFrames + 1;
      if (regimeFrames >= REGIME_FRAMES) {
        interval = dt;
        bestInterval = dt;
        regimeFrames = 0;
      }
      return false;
    }
    regimeFrames = 0;
    interval = interval == 0 ? dt : interval * 0.9 + dt * 0.1;
    // Frame generation must never cost the game real frame rate. If the game's frame interval grows past the best it
    // has managed, our waits are eating into it, so they shrink (outputs bunch up) until the interval is back.
    bestInterval = bestInterval == 0 ? interval : Math.min(bestInterval * 1.0005, interval);
    if (interval > bestInterval * 1.04) waitScale = Math.max(0.15, waitScale * 0.92);
    else waitScale = Math.min(1.0, waitScale * 1.004);
    return true;
  }

  /** The game made a new window (maybe on another display): look the refresh rate up again at the next frame. */
  void windowChanged ()
  {
    framesSinceRefresh = 1_000_000;
  }

  /** The learned frame interval in nanoseconds (0 before the first steady frame). */
  double interval ()
  {
    return interval;
  }

  /** Closes the current timing segment (index into SEGMENTS) and starts the next. */
  void segment (int index)
  {
    long now = System.nanoTime();
    segSum[index] += now - segStart;
    segStart = now;
  }

  /** Call at the very end of the hook. */
  void endFrame ()
  {
    lastEnd = System.nanoTime();
    hookSum += lastEnd - hookStart;
  }

  /** Call right after every real glfwSwapBuffers: records spacing and reports. */
  void swapped ()
  {
    long now = System.nanoTime();
    counterPresents++;
    presents++;
    if (lastSwap != 0) {
      double ms = (now - lastSwap) / 1e6;
      sum += ms;
      sumSq += ms * ms;
      min = Math.min(min, ms);
      max = Math.max(max, ms);
      swaps++;
    }
    lastSwap = now;
    if (reportStart != 0 && now - reportStart >= REPORT_NANOS) {
      if (SkGfxHook.STATS) report(now);
      else clearReport(now);
    }
  }

  /**
   * How much a sleep can overshoot, as last observed: ~3 ms on macOS, up to the timer period (often 15.6 ms) on
   * Windows. The wait sleeps only what it can afford to overshoot and spins the rest, so it ends on time anywhere.
   */
  private long parkOvershoot = -1;

  /** The measured sleep overshoot, measured on first use (a wait that never happens costs nothing at start-up). */
  private long overshoot ()
  {
    if (parkOvershoot < 0) parkOvershoot = calibrate();
    return parkOvershoot;
  }

  /** The part of a wait that is spun instead of slept, for an observed sleep overshoot (in nanoseconds). */
  static long spinMargin (long overshoot)
  {
    return Math.max(1_000_000L, Math.min(12_000_000L, overshoot + overshoot / 4 + 500_000L));
  }

  /** Measures a few short sleeps once, so the first generated frames are not late on coarse timers. */
  private static long calibrate ()
  {
    long worst = 0;
    for (int i = 0; i < 3; i++) {
      long start = System.nanoTime();
      LockSupport.parkNanos(1_000_000L);
      worst = Math.max(worst, System.nanoTime() - start - 1_000_000L);
    }
    return Math.min(worst, 30_000_000L);
  }

  /**
   * The frames to show for the real frame that just arrived: weights of the current frame in (0, 1], the last of them
   * 1.0 when the real frame itself is on the output grid. Empty when generation is not wanted or not possible now.
   *
   * @param allowed {@link #beginFrame} found the frame interval steady and the game fast enough.
   */
  double[] plan (boolean allowed)
  {
    if (!allowed || interval <= 0) {
      schedule.reset();
      return NONE;
    }
    double fps = 1e9 / interval;
    double m;
    if (ADAPTIVE) {
      double target = TARGET > 0 ? TARGET : (refreshRate > 0 ? refreshRate : 60);
      m = target / fps;
    } else {
      m = MULTIPLIER;
      // Frames the display cannot show are wasted work.
      if (refreshRate > 0) m = Math.min(m, refreshRate / fps);
    }
    m = Math.min(m, MAX_MULTIPLIER);
    if (m < 1.05) {
      schedule.reset();
      return NONE;
    }
    spacing = Math.min(MAX_SPACING_NANOS, (long) (waitScale * interval / m));
    return schedule.outputs(m);
  }

  private static final double[] NONE = new double[0];

  /** Waits until the gap between two frames of a burst has passed since the last swap. */
  void waitSpacing ()
  {
    if (NO_WAIT) return;
    waitUntil(lastSwap + spacing);
  }

  private void waitUntil (long target)
  {
    long left;
    while ((left = target - System.nanoTime()) > 0) {
      long margin = spinMargin(overshoot());
      if (left > margin + 500_000L) {
        long request = left - margin;
        long start = System.nanoTime();
        LockSupport.parkNanos(request);
        long overshoot = Math.max(0, System.nanoTime() - start - request);
        // Remember the worst recent overshoot, forgetting it slowly (timers can get finer or coarser).
        parkOvershoot = Math.max(overshoot, parkOvershoot - parkOvershoot / 64);
      } else {
        Thread.onSpinWait();
      }
    }
  }

  /** Test aid: adds the measured GPU time (ms) of our own passes; index 0 = generated frame, 1 = real frame. */
  void gpu (int index, double ms)
  {
    gpuSum[index] += ms;
    gpuCount[index]++;
  }

  private final double[] gpuSum = new double[2];
  private final int[] gpuCount = new int[2];

  void generatedFrame ()
  {
    generated++;
    counterGenerated++;
  }

  // ---- the on-screen counter: game fps and, with frame generation, shown fps, over half-second windows
  private long counterStart;
  private int counterFrames, counterGenerated, counterPresents;
  private String counterText = "";

  /** "FPS 55/110" (game/shown) or "FPS 55" without generated frames; refreshed twice a second. */
  String counterText ()
  {
    long now = System.nanoTime();
    if (counterStart == 0) counterStart = now;
    long elapsed = now - counterStart;
    if (elapsed >= 500_000_000L && counterFrames > 0) {
      double seconds = elapsed / 1e9;
      long real = Math.round(counterFrames / seconds);
      long shown = Math.round(counterPresents / seconds);
      counterText = counterGenerated > 0 ? "FPS " + real + "/" + shown : "FPS " + real;
      counterStart = now;
      counterFrames = counterGenerated = counterPresents = 0;
    }
    return counterText;
  }

  /** Starts a new reporting window without printing (the pacer also runs just for the on-screen counter). */
  private void clearReport (long now)
  {
    java.util.Arrays.fill(segSum, 0);
    java.util.Arrays.fill(gpuSum, 0);
    java.util.Arrays.fill(gpuCount, 0);
    hookSum = outsideSum = 0;
    reportStart = now;
    frames = generated = skippedRate = skippedHitch = swaps = presents = 0;
    sum = sumSq = max = 0;
    min = Double.MAX_VALUE;
  }

  private void report (long now)
  {
    double seconds = (now - reportStart) / 1e9;
    double mean = swaps > 0 ? sum / swaps : 0;
    double sd = swaps > 0 ? Math.sqrt(Math.max(0, sumSq / swaps - mean * mean)) : 0;
    System.err.printf("[skgfx] stats: game %.1f fps, shown %.1f fps (generated %d, skipped: rate %d hitch %d), "
        + "swap interval ms avg %.2f min %.2f max %.2f sd %.2f, display %d Hz%n",
      frames / seconds, presents / seconds, generated, skippedRate, skippedHitch,
      mean, swaps > 0 ? min : 0, max, sd, refreshRate);
    System.err.printf("[skgfx] sleep overshoot %.2f ms, spin margin %.2f ms, wait scale %.2f, %s%n", overshoot() / 1e6,
      spinMargin(overshoot()) / 1e6, waitScale,
      ADAPTIVE ? "adaptive target " + (TARGET > 0 ? TARGET : refreshRate) + " fps" : "fixed " + MULTIPLIER + "x");
    StringBuilder sb = new StringBuilder("[skgfx] per game frame ms: outside hook ")
      .append(String.format("%.2f", outsideSum / frames / 1e6)).append(", in hook ")
      .append(String.format("%.2f", hookSum / frames / 1e6)).append(" (");
    for (int i = 0; i < SEGMENTS.length; i++) {
      sb.append(i > 0 ? ", " : "").append(SEGMENTS[i]).append(' ').append(String.format("%.2f", segSum[i] / frames / 1e6));
    }
    System.err.println(sb.append(')'));
    if (gpuCount[0] + gpuCount[1] > 0) {
      System.err.printf("[skgfx] gpu ms (own passes, with glFinish): generated frame %.2f, real frame %.2f%n",
        gpuCount[0] > 0 ? gpuSum[0] / gpuCount[0] : 0, gpuCount[1] > 0 ? gpuSum[1] / gpuCount[1] : 0);
    }
    clearReport(now);
  }

  /**
   * The monitor the window is on: its fullscreen monitor, else (windowed or borderless, where GLFW reports none) the one
   * it overlaps most, else the primary one. A game window on a second display runs at that display's refresh rate.
   */
  private static long monitorOf (long window)
  {
    long monitor = GLFW.glfwGetWindowMonitor(window);
    if (monitor != 0) return monitor;
    org.lwjgl.PointerBuffer monitors = GLFW.glfwGetMonitors();
    if (monitors != null && monitors.remaining() > 1) {
      int[] x = {0}, y = {0}, w = {0}, h = {0};
      GLFW.glfwGetWindowPos(window, x, y);
      GLFW.glfwGetWindowSize(window, w, h);
      int[][] rects = new int[monitors.remaining()][];
      for (int i = 0; i < rects.length; i++) {
        long other = monitors.get(monitors.position() + i);
        int[] mx = {0}, my = {0};
        GLFW.glfwGetMonitorPos(other, mx, my);
        GLFWVidMode mode = GLFW.glfwGetVideoMode(other);
        rects[i] = new int[] {mx[0], my[0], mode == null ? 0 : mode.width(), mode == null ? 0 : mode.height()};
      }
      int best = bestMonitor(x[0], y[0], w[0], h[0], rects);
      if (best >= 0) return monitors.get(monitors.position() + best);
    }
    return GLFW.glfwGetPrimaryMonitor();
  }

  /** Index of the monitor rectangle ({@code x, y, width, height}) the window rectangle overlaps most; -1 for none. */
  static int bestMonitor (int wx, int wy, int ww, int wh, int[][] monitors)
  {
    int best = -1;
    long bestArea = 0;
    for (int i = 0; i < monitors.length; i++) {
      long overlapX = Math.min((long) wx + ww, (long) monitors[i][0] + monitors[i][2]) - Math.max(wx, monitors[i][0]);
      long overlapY = Math.min((long) wy + wh, (long) monitors[i][1] + monitors[i][3]) - Math.max(wy, monitors[i][1]);
      if (overlapX <= 0 || overlapY <= 0) continue;
      if (overlapX * overlapY > bestArea) {
        bestArea = overlapX * overlapY;
        best = i;
      }
    }
    return best;
  }

  private static int refreshRate (long window)
  {
    try {
      long monitor = monitorOf(window);
      GLFWVidMode mode = monitor == 0 ? null : GLFW.glfwGetVideoMode(monitor);
      return mode == null ? 0 : mode.refreshRate();
    } catch (Throwable t) {
      return 0;
    }
  }
}
