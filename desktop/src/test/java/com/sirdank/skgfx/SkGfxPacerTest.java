package com.sirdank.skgfx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SkGfxPacerTest
{
  @Test
  public void aMenuThatRunsFastDoesNotLockGenerationOutOfASlowerZone ()
  {
    SkGfxPacer pacer = new SkGfxPacer();
    for (int i = 0; i < 60; i++) assertTrue(pacer.frameTime(5_000_000L), "menu frame " + i);
    assertEquals(5_000_000.0, pacer.interval(), 1.0);
    // Entering the zone: a steady 20 ms. The first frames look like hitches against a 5 ms interval...
    for (int i = 0; i < SkGfxPacer.REGIME_FRAMES; i++) assertFalse(pacer.frameTime(20_000_000L), "hitch " + i);
    // ...then the new rhythm is the interval and generation can follow it again.
    assertEquals(20_000_000.0, pacer.interval(), 1.0);
    assertTrue(pacer.frameTime(20_000_000L));
    assertTrue(pacer.frameTime(21_000_000L));
  }

  @Test
  public void aSingleHitchStillDoesNotChangeTheInterval ()
  {
    SkGfxPacer pacer = new SkGfxPacer();
    for (int i = 0; i < 30; i++) pacer.frameTime(8_000_000L);
    assertFalse(pacer.frameTime(60_000_000L));
    assertFalse(pacer.frameTime(250_000_000L), "longer than a hitch is never a new rhythm");
    assertEquals(8_000_000.0, pacer.interval(), 1.0);
    assertTrue(pacer.frameTime(8_000_000L));
    // Hitches interrupted by a normal frame do not add up to a new rhythm either.
    for (int round = 0; round < 10; round++) {
      pacer.frameTime(30_000_000L);
      pacer.frameTime(30_000_000L);
      assertTrue(pacer.frameTime(8_000_000L));
    }
    assertEquals(8_000_000.0, pacer.interval(), 100_000.0);
  }

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
