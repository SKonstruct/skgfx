package com.sirdank.skgfx;

/**
 * Which frames to show for each real frame, for any multiplier. Time is measured in real-frame intervals: the previous
 * real frame is at 0 and the one that just arrived at 1. Outputs sit on an even grid {@code 1/m} apart; each output
 * shows the scene at its grid position, as a mix of the two real frames weighted by that position (so 1.0 is the real
 * frame itself). 2x gives 0.5 and 1.0 every time, 3x gives 1/3, 2/3 and 1.0, 1.5x alternates between one and two
 * outputs, and the grid carries over from one real frame to the next so the average is exactly {@code m}.
 */
final class SkGfxSchedule
{
  private double next = -1;

  /** Starts the grid over (after generation was paused). */
  void reset ()
  {
    next = -1;
  }

  /**
   * @param m Outputs per real frame (at least 1).
   * @return The outputs for the interval that just ended, as weights of the current frame in (0, 1]; a weight of 1.0
   * is the real frame.
   */
  double[] outputs (double m)
  {
    // Not a multiplier (NaN, below 1, infinite: a step of 0 would never reach the end of the interval): no frames.
    if (!Double.isFinite(m) || m < 1.0) {
      reset();
      return new double[0];
    }
    double step = 1.0 / m;
    if (next <= 0) next = step;
    if (next > step) next = step; // the spacing dropped (the multiplier rose): do not wait out the old, longer one
    double[] phases = new double[(int) Math.ceil(m) + 1];
    int count = 0;
    while (next <= 1.0 + 1e-9 && count < phases.length) {
      phases[count++] = 1.0 - next < 1e-6 ? 1.0 : next;
      next += step;
    }
    next -= 1.0;
    if (next <= 1e-9) next = step;
    return java.util.Arrays.copyOf(phases, count);
  }
}
