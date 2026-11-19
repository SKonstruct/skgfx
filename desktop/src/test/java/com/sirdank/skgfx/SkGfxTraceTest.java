package com.sirdank.skgfx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class SkGfxTraceTest
{
  @Test
  public void primitiveArraysAreWrittenOutNotAsObjectIdentities ()
  {
    String line = SkGfxTrace.format("GL20.glUniform4fv", new Object[] {3, new float[] {1.5f, 2f}, new int[0]});
    assertEquals("GL20.glUniform4fv(3, float[2]{1.5 2.0}, int[0]{})", line);
    String longArray = SkGfxTrace.format("x", new Object[] {new double[20]});
    assertTrue(longArray.endsWith(" ...})"), longArray);
    assertFalse(SkGfxTrace.format("x", new Object[] {new float[] {1f}}).contains("[F@"));
    assertEquals("x(null, \"hi\")", SkGfxTrace.format("x", new Object[] {null, "hi"}));
  }

  @Test
  public void aMissingTriggerStartsNothing (@TempDir Path dir)
  {
    SkGfxTrace.resetTriggerForTest();
    assertFalse(SkGfxTrace.takeTrigger(dir.resolve("nothing").toFile()));
  }

  @Test
  public void aTriggerIsPickedUpOnceAndDeleted (@TempDir Path dir) throws Exception
  {
    SkGfxTrace.resetTriggerForTest();
    File trigger = Files.createFile(dir.resolve("skgfx-trace.trigger")).toFile();
    assertTrue(SkGfxTrace.takeTrigger(trigger));
    assertFalse(trigger.exists());
    assertFalse(SkGfxTrace.takeTrigger(trigger));
  }

  @Test
  public void aTriggerThatCannotBeDeletedStartsOneCaptureNotOneEveryThirtyFrames (@TempDir Path dir) throws Exception
  {
    SkGfxTrace.resetTriggerForTest();
    File folder = Files.createDirectory(dir.resolve("locked")).toFile();
    File trigger = Files.createFile(folder.toPath().resolve("skgfx-trace.trigger")).toFile();
    folder.setWritable(false);
    try {
      // Windows ignores the flag on folders and root ignores it everywhere: then there is nothing to test.
      org.junit.jupiter.api.Assumptions.assumeFalse(folder.canWrite(), "the folder is still writable");
      assertTrue(SkGfxTrace.takeTrigger(trigger), "the first sighting starts a capture");
      assertTrue(trigger.exists(), "it could not be deleted");
      assertFalse(SkGfxTrace.takeTrigger(trigger), "and the same file does not start another");
      assertFalse(SkGfxTrace.takeTrigger(trigger));
    } finally {
      folder.setWritable(true);
      SkGfxTrace.resetTriggerForTest();
    }
  }
}
