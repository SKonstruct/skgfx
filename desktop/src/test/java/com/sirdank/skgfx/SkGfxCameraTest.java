package com.sirdank.skgfx;

import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class SkGfxCameraTest
{
  private static FloatBuffer buffer (double[] m)
  {
    FloatBuffer fb = FloatBuffer.allocate(16);
    for (double v : m) fb.put((float) v);
    fb.flip();
    return fb;
  }

  private static double[] mat (double degrees, double ax, double ay, double az, double tx, double ty, double tz)
  {
    return SkGfxCamera.multiply(SkGfxCamera.translation(tx, ty, tz), rotationOnly(degrees, ax, ay, az));
  }

  private static double[] rotationOnly (double degrees, double ax, double ay, double az)
  {
    double n = Math.sqrt(ax * ax + ay * ay + az * az);
    double x = ax / n, y = ay / n, z = az / n;
    double c = Math.cos(Math.toRadians(degrees)), s = Math.sin(Math.toRadians(degrees)), k = 1 - c;
    double[] r = SkGfxCamera.identity();
    r[0] = x * x * k + c; r[1] = y * x * k + z * s; r[2] = z * x * k - y * s;
    r[4] = x * y * k - z * s; r[5] = y * y * k + c; r[6] = z * y * k + x * s;
    r[8] = x * z * k + y * s; r[9] = y * z * k - x * s; r[10] = z * z * k + c;
    return r;
  }

  /** Draws one frame: static objects under the camera, plus some that move. */
  private static void frame (SkGfxCamera cam, double[] view, long seed, int statics, int movers, int movedBy)
  {
    cam.matrixMode(SkGfxCamera.PROJECTION);
    cam.perspective(-1, 1, -0.5, 0.5, 1, 50);
    cam.matrixMode(SkGfxCamera.MODELVIEW);
    Random rnd = new Random(7);
    for (int i = 0; i < statics + movers; i++) {
      double[] object = mat(rnd.nextDouble() * 360, 0.3, 1, 0.2, rnd.nextDouble() * 10, rnd.nextDouble() * 10, -rnd.nextDouble() * 10);
      if (i >= statics) object = SkGfxCamera.multiply(SkGfxCamera.translation(movedBy * 0.37, 0.1 * movedBy, 0), object);
      cam.bindArray(100 + i);
      cam.bindElement(500 + i);
      cam.bindTexture(7 + i % 5);
      cam.load(buffer(SkGfxCamera.multiply(view, object)));
      cam.draw(4, 0, 12, 24, 5123, 0);
    }
    cam.endWorld();
    cam.finishFrame();
  }

  @Test
  public void recoversTheCameraDeltaDespiteMovingObjects ()
  {
    double[] viewA = mat(20, 1, 0, 0, 1, -2, -15);
    double[] viewB = mat(21.5, 0.2, 1, 0, 1.3, -2.1, -15.4);
    SkGfxCamera cam = new SkGfxCamera();
    frame(cam, viewA, 1, 60, 15, 0);
    assertFalse(cam.estimate().valid()); // first frame: nothing to compare with
    frame(cam, viewB, 2, 60, 15, 1);
    SkGfxCamera.Estimate e = cam.estimate();
    assertTrue(e.valid(), e.toString());
    double[] expected = SkGfxCamera.multiply(viewB, SkGfxCamera.affineInverse(viewA));
    for (int i = 0; i < 16; i++) assertEquals(expected[i], e.delta()[i], 2e-3, "entry " + i);
    assertTrue(e.agreeing() >= 55, "agreeing " + e.agreeing());
  }

  @Test
  public void aStillCameraGivesTheIdentity ()
  {
    double[] view = mat(10, 0, 1, 0, 0, 0, -12);
    SkGfxCamera cam = new SkGfxCamera();
    frame(cam, view, 1, 40, 5, 0);
    frame(cam, view, 2, 40, 5, 1);
    assertTrue(cam.estimate().valid());
    for (int i = 0; i < 16; i++) assertEquals(SkGfxCamera.identity()[i], cam.estimate().delta()[i], 2e-3);
  }

  @Test
  public void tooFewMatchingDrawsIsNotTrusted ()
  {
    SkGfxCamera cam = new SkGfxCamera();
    frame(cam, mat(0, 0, 1, 0, 0, 0, -10), 1, 5, 0, 0);
    frame(cam, mat(3, 0, 1, 0, 1, 0, -10), 2, 5, 0, 0);
    assertFalse(cam.estimate().valid());
  }

  @Test
  public void mostDrawsMovingIsNotTrusted ()
  {
    SkGfxCamera cam = new SkGfxCamera();
    // Every object moves differently: no delta gets a quarter of the votes.
    for (int f = 0; f < 2; f++) {
      cam.matrixMode(SkGfxCamera.PROJECTION);
      cam.perspective(-1, 1, -0.5, 0.5, 1, 50);
      cam.matrixMode(SkGfxCamera.MODELVIEW);
      for (int i = 0; i < 40; i++) {
        cam.bindArray(i);
        cam.load(buffer(SkGfxCamera.translation(i * 0.3 * (f + 1) * (1 + i % 7), 0, -5)));
        cam.draw(4, 0, 1, 3, 5123, 0);
      }
      cam.endWorld();
      cam.finishFrame();
    }
    assertFalse(cam.estimate().valid());
  }

  @Test
  public void halfStepSquaredIsTheStep ()
  {
    double[] delta = mat(8, 0.3, 1, 0.1, 0.4, -0.2, 0.9);
    double[] half = SkGfxCamera.halfStep(delta);
    assertNotNull(half);
    double[] twice = SkGfxCamera.multiply(half, half);
    for (int i = 0; i < 16; i++) assertEquals(delta[i], twice[i], 1e-9, "entry " + i);
    // An identity step stays identity.
    double[] none = SkGfxCamera.halfStep(SkGfxCamera.identity());
    for (int i = 0; i < 16; i++) assertEquals(SkGfxCamera.identity()[i], none[i], 1e-12);
  }

  @Test
  public void stepsComposeToTheWholeStep ()
  {
    double[] delta = mat(11, 0.2, 1, -0.3, 0.7, -0.1, 1.4);
    for (double s : new double[] {0.1, 0.25, 1.0 / 3, 0.7, 0.9}) {
      double[] first = SkGfxCamera.step(delta, s);
      double[] rest = SkGfxCamera.step(delta, 1 - s);
      assertNotNull(first);
      double[] whole = SkGfxCamera.multiply(first, rest);
      for (int i = 0; i < 16; i++) assertEquals(delta[i], whole[i], 1e-9, "s=" + s + " entry " + i);
    }
    // The ends: no motion at 0, the whole step at 1.
    double[] none = SkGfxCamera.step(delta, 0);
    double[] all = SkGfxCamera.step(delta, 1);
    for (int i = 0; i < 16; i++) {
      assertEquals(SkGfxCamera.identity()[i], none[i], 1e-9);
      assertEquals(delta[i], all[i], 1e-9);
    }
    // A pure translation is just scaled.
    double[] pure = SkGfxCamera.step(SkGfxCamera.translation(1, 2, 3), 0.25);
    assertEquals(0.25, pure[12], 1e-12);
    assertEquals(0.5, pure[13], 1e-12);
    assertEquals(0.75, pure[14], 1e-12);
  }

  @Test
  public void aScaledDeltaHasNoHalfStep ()
  {
    double[] scaled = SkGfxCamera.identity();
    scaled[0] = 1.5;
    assertNull(SkGfxCamera.halfStep(scaled));
  }
}
