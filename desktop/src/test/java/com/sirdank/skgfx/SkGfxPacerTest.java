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

  @Test
  public void propertyValuesThatAreNotFiniteNumbersAreIgnored ()
  {
    assertEquals(2.0, SkGfxPacer.finiteOr(null, 2.0));
    assertEquals(2.0, SkGfxPacer.finiteOr("NaN", 2.0));
    assertEquals(2.0, SkGfxPacer.finiteOr("Infinity", 2.0));
    assertEquals(2.0, SkGfxPacer.finiteOr("-Infinity", 2.0));
    assertEquals(2.0, SkGfxPacer.finiteOr("fast", 2.0));
    assertEquals(1.5, SkGfxPacer.finiteOr(" 1.5 ", 2.0));
  }

  @Test
  public void aWindowedGameUsesTheRefreshRateOfTheMonitorItIsOn ()
  {
    int[][] monitors = {{0, 0, 1920, 1080}, {1920, 0, 2560, 1440}};
    assertEquals(0, SkGfxPacer.bestMonitor(100, 100, 1280, 720, monitors));
    assertEquals(1, SkGfxPacer.bestMonitor(2000, 50, 1280, 720, monitors));
    // Straddling the two: the one with more of the window.
    assertEquals(1, SkGfxPacer.bestMonitor(1500, 0, 1280, 720, monitors));
    assertEquals(0, SkGfxPacer.bestMonitor(800, 0, 1280, 720, monitors));
    // Left of the first monitor (negative coordinates) or nowhere: none, the caller falls back to the primary.
    assertEquals(-1, SkGfxPacer.bestMonitor(-5000, 0, 800, 600, monitors));
    assertEquals(0, SkGfxPacer.bestMonitor(-700, 0, 800, 600, new int[][] {{-1920, 0, 1920, 1080}, {0, 0, 1920, 1080}}),
      "a monitor to the left of the origin is found too");
    assertEquals(-1, SkGfxPacer.bestMonitor(0, 0, 0, 0, monitors), "an empty window overlaps nothing");
  }
}
