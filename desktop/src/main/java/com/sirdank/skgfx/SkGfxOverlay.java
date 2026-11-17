package com.sirdank.skgfx;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.glUseProgram;

/**
 * The small frame-rate counter in the window's top left corner ("FPS 55/110": the game's frames per second, then what
 * is shown with frame generation). Drawn with immediate-mode quads from a 5x7 pixel font, over whatever framebuffer is
 * bound, so it needs no textures, shaders or fonts and works the same with or without the offscreen pipeline.
 */
final class SkGfxOverlay
{
  private static final int GLYPH_WIDTH = 5, GLYPH_HEIGHT = 7;

  /** 5x7 glyphs, one string of seven 5-bit rows per character; only what the counter prints. */
  private static final String[][] FONT = {
    {"0", "01110", "10001", "10011", "10101", "11001", "10001", "01110"},
    {"1", "00100", "01100", "00100", "00100", "00100", "00100", "01110"},
    {"2", "01110", "10001", "00001", "00010", "00100", "01000", "11111"},
    {"3", "11110", "00001", "00001", "01110", "00001", "00001", "11110"},
    {"4", "00010", "00110", "01010", "10010", "11111", "00010", "00010"},
    {"5", "11111", "10000", "11110", "00001", "00001", "10001", "01110"},
    {"6", "00110", "01000", "10000", "11110", "10001", "10001", "01110"},
    {"7", "11111", "00001", "00010", "00100", "01000", "01000", "01000"},
    {"8", "01110", "10001", "10001", "01110", "10001", "10001", "01110"},
    {"9", "01110", "10001", "10001", "01111", "00001", "00010", "01100"},
    {"/", "00001", "00010", "00010", "00100", "01000", "01000", "10000"},
    {"F", "11111", "10000", "10000", "11110", "10000", "10000", "10000"},
    {"P", "11110", "10001", "10001", "11110", "10000", "10000", "10000"},
    {"S", "01111", "10000", "10000", "01110", "00001", "00001", "11110"},
    {" ", "00000", "00000", "00000", "00000", "00000", "00000", "00000"},
  };

  private SkGfxOverlay () { }

  /** The rows of a character's glyph (seven 5-character strings), or blanks for one the font does not have. */
  static String[] glyph (char c)
  {
    for (String[] entry : FONT) {
      if (entry[0].charAt(0) == c) return java.util.Arrays.copyOfRange(entry, 1, 1 + GLYPH_HEIGHT);
    }
    return glyph(' ');
  }

  /** Width of the text in font pixels (a one pixel gap between characters). */
  static int textWidth (String text)
  {
    return text.isEmpty() ? 0 : text.length() * (GLYPH_WIDTH + 1) - 1;
  }

  /** Size of one font pixel on screen for a window of this height: bigger windows get a bigger counter. */
  static int cellSize (int windowHeight)
  {
    return Math.max(2, Math.round(windowHeight / 360f));
  }

  /**
   * Draws the text at the top left of a {@code width} x {@code height} framebuffer. Leaves the game's GL state as it
   * was (attributes, matrices, matrix mode and program are saved and restored).
   */
  static void draw (String text, int width, int height, boolean generating)
  {
    if (text == null || text.isEmpty()) return;
    int cell = cellSize(height);
    int margin = cell * 3;
    int pad = cell * 2;
    int boxWidth = textWidth(text) * cell + pad * 2;
    int boxHeight = GLYPH_HEIGHT * cell + pad * 2;
    int top = height - margin;

    int savedProgram = glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
    int savedMatrixMode = glGetInteger(GL_MATRIX_MODE);
    glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_CURRENT_BIT | GL_VIEWPORT_BIT | GL_TRANSFORM_BIT
      | GL_TEXTURE_BIT | GL_SCISSOR_BIT | GL_LIGHTING_BIT | GL_DEPTH_BUFFER_BIT);
    glMatrixMode(GL_PROJECTION);
    glPushMatrix();
    glLoadIdentity();
    glOrtho(0, width, 0, height, -1, 1);
    glMatrixMode(GL_MODELVIEW);
    glPushMatrix();
    glLoadIdentity();
    try {
      glViewport(0, 0, width, height);
      glUseProgram(0);
      for (int unit = 2; unit >= 0; unit--) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glDisable(GL_TEXTURE_2D);
      }
      glDisable(GL_DEPTH_TEST);
      glDisable(GL_STENCIL_TEST);
      glDisable(GL_SCISSOR_TEST);
      glDisable(GL_CULL_FACE);
      glDisable(GL_LIGHTING);
      glDisable(GL_FOG);
      glDisable(GL_ALPHA_TEST);
      glEnable(GL_BLEND);
      glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
      glColorMask(true, true, true, true);

      glBegin(GL_QUADS);
      glColor4f(0f, 0f, 0f, 0.55f);
      quad(margin, top - boxHeight, boxWidth, boxHeight);
      // Green while frames are being generated, white otherwise.
      if (generating) glColor4f(0.45f, 1f, 0.55f, 1f);
      else glColor4f(1f, 1f, 1f, 1f);
      for (int i = 0; i < text.length(); i++) {
        String[] rows = glyph(text.charAt(i));
        int x = margin + pad + i * (GLYPH_WIDTH + 1) * cell;
        for (int row = 0; row < GLYPH_HEIGHT; row++) {
          for (int col = 0; col < GLYPH_WIDTH; col++) {
            if (rows[row].charAt(col) == '1') quad(x + col * cell, top - pad - (row + 1) * cell, cell, cell);
          }
        }
      }
      glEnd();
    } finally {
      glMatrixMode(GL_MODELVIEW);
      glPopMatrix();
      glMatrixMode(GL_PROJECTION);
      glPopMatrix();
      glMatrixMode(savedMatrixMode);
      glPopAttrib();
      glUseProgram(savedProgram);
    }
  }

  private static void quad (int x, int y, int w, int h)
  {
    glVertex2f(x, y);
    glVertex2f(x + w, y);
    glVertex2f(x + w, y + h);
    glVertex2f(x, y + h);
  }
}
