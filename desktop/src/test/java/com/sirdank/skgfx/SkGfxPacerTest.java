package com.sirdank.skgfx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SkGfxPacerTest
{
  @Test
  public void spinMarginFollowsTheTimerButStaysBounded ()
  {
    // Precise timers still spin a little; a macOS-like 3 ms overshoot spins a bit more than that.
    assertEquals(1_000_000L, SkGfxPacer.spinMargin(0));
    long mac = SkGfxPacer.spinMargin(3_000_000L);
    assertTrue(mac > 3_000_000L && mac < 5_000_000L, "macOS-like: " + mac);
    // A Windows-like 15.6 ms timer period would mean spinning the whole wait: capped so it can still sleep a long wait.
    assertEquals(12_000_000L, SkGfxPacer.spinMargin(15_600_000L));
    assertTrue(SkGfxPacer.spinMargin(5_000_000L) > SkGfxPacer.spinMargin(2_000_000L));
  }
}
