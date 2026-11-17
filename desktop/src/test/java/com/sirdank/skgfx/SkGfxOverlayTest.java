package com.sirdank.skgfx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SkGfxOverlayTest
{
  @Test
  public void everyCharacterTheCounterPrintsHasAFullGlyph ()
  {
    for (char c : "FPS 0123456789/".toCharArray()) {
      String[] rows = SkGfxOverlay.glyph(c);
      assertEquals(7, rows.length, "rows of '" + c + "'");
      for (String row : rows) assertTrue(row.matches("[01]{5}"), "row of '" + c + "': " + row);
      if (c != ' ') assertTrue(String.join("", rows).contains("1"), "'" + c + "' has lit pixels");
    }
    // Digits are told apart by their pixels.
    assertNotEquals(String.join("", SkGfxOverlay.glyph('1')), String.join("", SkGfxOverlay.glyph('7')));
    // Anything else prints as blank rather than failing.
    assertFalse(String.join("", SkGfxOverlay.glyph('?')).contains("1"));
  }

  @Test
  public void textWidthAndSize ()
  {
    assertEquals(0, SkGfxOverlay.textWidth(""));
    assertEquals(5, SkGfxOverlay.textWidth("5"));
    assertEquals(6 * 6 - 1, SkGfxOverlay.textWidth("55/110"));
    assertEquals(2, SkGfxOverlay.cellSize(300));          // never smaller than 2 px per font pixel
    assertTrue(SkGfxOverlay.cellSize(1714) > SkGfxOverlay.cellSize(844)); // bigger windows get a bigger counter
  }
}
