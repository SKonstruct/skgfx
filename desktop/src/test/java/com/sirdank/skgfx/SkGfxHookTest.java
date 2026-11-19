package com.sirdank.skgfx;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.*;

/** The decisions of the hook that do not need a GL context (the hook itself only runs inside the game). */
public class SkGfxHookTest
{
  @Test
  public void anEmptyRectangleStaysEmptyWhileATargetNeverDoes ()
  {
    assertEquals(0, SkGfxHook.scaledRect(0, 0.5f));
    assertEquals(0, SkGfxHook.scaledRect(0, 4f));
    assertEquals(-3, SkGfxHook.scaledRect(-3, 0.5f), "a negative size stays the game's own GL error");
    assertEquals(1, SkGfxHook.scaledRect(1, 0.25f), "a real rectangle never rounds to nothing");
    assertEquals(50, SkGfxHook.scaledRect(100, 0.5f));
    assertEquals(400, SkGfxHook.scaledRect(100, 4f));
    assertTrue(SkGfxHook.scaled(0) >= 1, "targets cannot be empty");
  }

  @Test
  public void additiveHudEffectsDoNotCoverTheSceneBehindThem ()
  {
    // Ordinary blending accumulates coverage in alpha (over)...
    assertEquals(GL11.GL_ONE, SkGfxHook.coverageSrcAlpha(GL11.GL_ONE_MINUS_SRC_ALPHA));
    assertEquals(GL11.GL_ONE_MINUS_SRC_ALPHA, SkGfxHook.coverageDstAlpha(GL11.GL_ONE_MINUS_SRC_ALPHA));
    assertEquals(GL11.GL_ONE, SkGfxHook.coverageSrcAlpha(GL11.GL_ZERO));
    // ...additive light (destination factor ONE: SRC_ALPHA/ONE and ONE/ONE) leaves it alone.
    assertEquals(GL11.GL_ZERO, SkGfxHook.coverageSrcAlpha(GL11.GL_ONE));
    assertEquals(GL11.GL_ONE, SkGfxHook.coverageDstAlpha(GL11.GL_ONE));
  }

  @Test
  public void aReadFramebufferOfTheWindowIsTheResolvedOne ()
  {
    int draw = 7, read = 5;
    assertEquals(draw, SkGfxHook.redirected(0x8D40, 0, draw, read));
    assertEquals(draw, SkGfxHook.redirected(0x8CA9, 0, draw, read));
    assertEquals(read, SkGfxHook.redirected(SkGfxHook.GL_READ_FRAMEBUFFER, 0, draw, read));
    // The game's own framebuffers are never redirected.
    assertEquals(42, SkGfxHook.redirected(SkGfxHook.GL_READ_FRAMEBUFFER, 42, draw, read));
    assertEquals(42, SkGfxHook.redirected(0x8D40, 42, draw, read));
  }

  @Test
  public void theCounterIsGreenOnlyWhenAGeneratedFrameIsShown ()
  {
    assertFalse(SkGfxHook.generating(new double[0]));
    assertFalse(SkGfxHook.generating(new double[] {1.0}), "just the real frame");
    assertTrue(SkGfxHook.generating(new double[] {0.5, 1.0}));
    assertTrue(SkGfxHook.generating(new double[] {1.0 / 3, 2.0 / 3, 1.0}));
    assertTrue(SkGfxHook.generating(new double[] {0.7}));
  }

  @Test
  public void aResizeDuringTheHudStillRestoresTheSceneState ()
  {
    assertTrue(SkGfxHook.sceneNeedsRestoring(true, false), "the resize dropped the HUD layer, the frame was a HUD frame");
    assertTrue(SkGfxHook.sceneNeedsRestoring(true, true));
    assertTrue(SkGfxHook.sceneNeedsRestoring(false, true));
    assertFalse(SkGfxHook.sceneNeedsRestoring(false, false));
  }

  @Test
  public void aSwapOnAnotherWindowIsANewContext ()
  {
    assertFalse(SkGfxHook.isNewWindow(0, 100), "the first window is not a change");
    assertFalse(SkGfxHook.isNewWindow(100, 100));
    assertTrue(SkGfxHook.isNewWindow(100, 200));
    assertFalse(SkGfxHook.isNewWindow(100, 0), "no current window is not a new one");
  }

  @Test
  public void aFailedCounterStaysOff ()
  {
    assertFalse(SkGfxHook.overlayFailed());
    SkGfxHook.overlayFailed(new IllegalStateException("test"));
    assertTrue(SkGfxHook.overlayFailed());
  }

  @Test
  public void aMinimizedWindowIsNotAResize ()
  {
    assertTrue(SkGfxPipeline.isMinimized(0, 0));
    assertTrue(SkGfxPipeline.isMinimized(800, 0));
    assertTrue(SkGfxPipeline.isMinimized(0, 600));
    assertFalse(SkGfxPipeline.isMinimized(1, 1));
    assertFalse(SkGfxPipeline.isMinimized(1920, 1080));
  }
}
