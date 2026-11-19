package com.sirdank.skgfx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SkGfxScheduleTest
{
  @Test
  public void twoTimesIsOneGeneratedFramePlusTheRealOne ()
  {
    SkGfxSchedule schedule = new SkGfxSchedule();
    for (int i = 0; i < 5; i++) assertArrayEquals(new double[] {0.5, 1.0}, schedule.outputs(2.0), 1e-9);
  }

  @Test
  public void threeTimesIsTwoGeneratedFrames ()
  {
    SkGfxSchedule schedule = new SkGfxSchedule();
    for (int i = 0; i < 5; i++) assertArrayEquals(new double[] {1.0 / 3, 2.0 / 3, 1.0}, schedule.outputs(3.0), 1e-9);
  }

  @Test
  public void fractionalMultipliersAverageOut ()
  {
    for (double m : new double[] {1.1, 1.25, 1.5, 2.5, 2.9, 4.7}) {
      SkGfxSchedule schedule = new SkGfxSchedule();
      int outputs = 0;
      int hooks = 2000;
      for (int i = 0; i < hooks; i++) {
        double[] phases = schedule.outputs(m);
        assertTrue(phases.length >= 1, "m=" + m);
        double previous = 0;
        for (double phase : phases) {
          assertTrue(phase > 0 && phase <= 1.0, "phase " + phase);
          assertTrue(phase > previous, "phases increase");
          previous = phase;
        }
        outputs += phases.length;
      }
      assertEquals(m, (double) outputs / hooks, 0.01, "average outputs per real frame at " + m);
    }
  }

  @Test
  public void oneAndAHalfAlternates ()
  {
    SkGfxSchedule schedule = new SkGfxSchedule();
    assertEquals(1, schedule.outputs(1.5).length);
    assertEquals(2, schedule.outputs(1.5).length);
    assertEquals(1, schedule.outputs(1.5).length);
  }

  @Test
  public void aSpacingThatDropsTakesEffectAtOnce ()
  {
    SkGfxSchedule schedule = new SkGfxSchedule();
    schedule.outputs(1.1);
    double[] after = schedule.outputs(3.0);
    assertEquals(1.0, after[after.length - 1], 1e-9);
    assertTrue(after.length >= 2 && after.length <= 3);
  }

  @Test
  public void somethingThatIsNotAMultiplierGivesNoFramesInsteadOfLoopingForever ()
  {
    SkGfxSchedule schedule = new SkGfxSchedule();
    for (double m : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, -2, 0.5}) {
      assertEquals(0, schedule.outputs(m).length, "m=" + m);
    }
    assertArrayEquals(new double[] {0.5, 1.0}, schedule.outputs(2.0), 1e-9);
  }
}
