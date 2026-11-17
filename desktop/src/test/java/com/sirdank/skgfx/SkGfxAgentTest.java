package com.sirdank.skgfx;

import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;

import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static org.junit.jupiter.api.Assertions.*;

public class SkGfxAgentTest
{
  /** Builds a class whose one method calls the LWJGL methods the agent intercepts, plus one it must leave alone. */
  private static byte[] fakeGameClass ()
  {
    ClassDesc glfw = ClassDesc.ofInternalName("org/lwjgl/glfw/GLFW");
    ClassDesc gl11 = ClassDesc.ofInternalName("org/lwjgl/opengl/GL11");
    ClassDesc gl30 = ClassDesc.ofInternalName("org/lwjgl/opengl/GL30");
    return ClassFile.of().build(ClassDesc.of("fake.Game"), cb -> cb
      .withMethodBody("render", MethodTypeDesc.ofDescriptor("(J)V"), ACC_PUBLIC | ACC_STATIC, code -> code
        .lload(0)
        .invokestatic(glfw, "glfwSwapBuffers", MethodTypeDesc.ofDescriptor("(J)V"))
        .iconst_0().iconst_0().iconst_1().iconst_1()
        .invokestatic(gl11, "glViewport", MethodTypeDesc.ofDescriptor("(IIII)V"))
        .iconst_0().iconst_0().iconst_1().iconst_1()
        .invokestatic(gl11, "glScissor", MethodTypeDesc.ofDescriptor("(IIII)V"))
        .iconst_0().iconst_0()
        .invokestatic(gl30, "glBindFramebuffer", MethodTypeDesc.ofDescriptor("(II)V"))
        .iconst_0().iconst_0()
        // Same name, different owner: must not be rewritten.
        .invokestatic(gl11, "glBindTexture", MethodTypeDesc.ofDescriptor("(II)V"))
        .return_()));
  }

  private static List<String> invokedOwners (byte[] bytes)
  {
    List<String> owners = new ArrayList<>();
    ClassFile.of().parse(bytes).methods().forEach(m -> m.code().ifPresent(code -> code.forEach(e -> {
      if (e instanceof InvokeInstruction i) {
        owners.add(i.owner().asInternalName() + "." + i.name().stringValue());
      }
    })));
    return owners;
  }

  @Test
  public void rewritesInterceptedCallSitesOnly ()
  {
    List<String> owners = invokedOwners(SkGfxAgent.rewrite(fakeGameClass()));
    String hook = "com/sirdank/skgfx/SkGfxHook";
    assertTrue(owners.contains(hook + ".swapBuffers"));
    assertTrue(owners.contains(hook + ".viewport"));
    assertTrue(owners.contains(hook + ".scissor"));
    assertTrue(owners.contains(hook + ".bindFramebuffer"));
    assertTrue(owners.contains("org/lwjgl/opengl/GL11.glBindTexture"), "untouched call rewritten: " + owners);
    assertFalse(owners.stream().anyMatch(o -> o.equals("org/lwjgl/glfw/GLFW.glfwSwapBuffers")));
  }

  @Test
  public void traceModeLogsEveryStaticGlCallAndStaysVerifiable ()
  {
    byte[] out = SkGfxAgent.rewrite(fakeGameClass(), true);
    List<String> owners = invokedOwners(out);
    // glBindTexture is not an intercepted hook, but trace mode must still log it (and still make the call).
    assertTrue(owners.contains("org/lwjgl/opengl/GL11.glBindTexture"));
    assertTrue(owners.stream().anyMatch(o -> o.equals("com/sirdank/skgfx/SkGfxTrace.call")), owners.toString());
    // Hooked calls are logged and then routed to the hook.
    assertTrue(owners.contains("com/sirdank/skgfx/SkGfxHook.viewport"));
    assertTrue(ClassFile.of().verify(out).isEmpty());
    // Without trace the logger is not referenced at all.
    assertFalse(invokedOwners(SkGfxAgent.rewrite(fakeGameClass(), false)).stream().anyMatch(o -> o.contains("SkGfxTrace")));
  }

  @Test
  public void readBacksGetAResolveHookAndStayVerifiable ()
  {
    byte[] copier = ClassFile.of().build(ClassDesc.of("fake.Copier"), cb -> cb
      .withMethodBody("copy", MethodTypeDesc.ofDescriptor("()V"), ACC_PUBLIC | ACC_STATIC, code -> {
        code.iconst_0().iconst_0().iconst_0().iconst_0().iconst_0().iconst_0().iconst_1().iconst_1();
        code.invokestatic(ClassDesc.ofInternalName("org/lwjgl/opengl/GL11"), "glCopyTexSubImage2D",
          MethodTypeDesc.ofDescriptor("(IIIIIIII)V"));
        code.return_();
      }));
    byte[] out = SkGfxAgent.rewrite(copier);
    List<String> owners = invokedOwners(out);
    assertEquals(List.of("com/sirdank/skgfx/SkGfxHook.beforeRead", "org/lwjgl/opengl/GL11.glCopyTexSubImage2D"), owners);
    assertTrue(ClassFile.of().verify(out).isEmpty());
  }

  @Test
  public void hudSplitHooksOnlyWhenEnabled ()
  {
    ClassDesc gl11 = ClassDesc.ofInternalName("org/lwjgl/opengl/GL11");
    ClassDesc gl12 = ClassDesc.ofInternalName("org/lwjgl/opengl/GL12");
    byte[] hud = ClassFile.of().build(ClassDesc.of("fake.Hud"), cb -> cb
      .withMethodBody("draw", MethodTypeDesc.ofDescriptor("()V"), ACC_PUBLIC | ACC_STATIC, code -> code
        .iconst_0().invokestatic(gl11, "glEnable", MethodTypeDesc.ofDescriptor("(I)V"))
        .iconst_0().iconst_0().invokestatic(gl11, "glBlendFunc", MethodTypeDesc.ofDescriptor("(II)V"))
        .iconst_0().iconst_0().iconst_0().iconst_0().iconst_0().lconst_0()
        .invokestatic(gl12, "glDrawRangeElements", MethodTypeDesc.ofDescriptor("(IIIIIJ)V"))
        .return_()));
    assertNull(SkGfxAgent.rewrite(hud, false, false));
    byte[] out = SkGfxAgent.rewrite(hud, false, true);
    assertEquals(List.of("com/sirdank/skgfx/SkGfxHook.enable", "com/sirdank/skgfx/SkGfxHook.blendFunc",
      "com/sirdank/skgfx/SkGfxHook.beforeDraw", "org/lwjgl/opengl/GL12.glDrawRangeElements"), invokedOwners(out));
    assertTrue(ClassFile.of().verify(out).isEmpty());
  }

  @Test
  public void cameraHooksReplaceMatrixBindAndDrawCalls ()
  {
    ClassDesc gl11 = ClassDesc.ofInternalName("org/lwjgl/opengl/GL11");
    ClassDesc gl12 = ClassDesc.ofInternalName("org/lwjgl/opengl/GL12");
    byte[] game = ClassFile.of().build(ClassDesc.of("fake.World"), cb -> cb
      .withMethodBody("draw", MethodTypeDesc.ofDescriptor("()V"), ACC_PUBLIC | ACC_STATIC, code -> code
        .invokestatic(gl11, "glLoadIdentity", MethodTypeDesc.ofDescriptor("()V"))
        .fconst_0().fconst_0().fconst_0().invokestatic(gl11, "glTranslatef", MethodTypeDesc.ofDescriptor("(FFF)V"))
        .iconst_0().iconst_0().invokestatic(gl11, "glBindTexture", MethodTypeDesc.ofDescriptor("(II)V"))
        .iconst_0().iconst_0().iconst_0().iconst_0().iconst_0().lconst_0()
        .invokestatic(gl12, "glDrawRangeElements", MethodTypeDesc.ofDescriptor("(IIIIIJ)V"))
        .return_()));
    assertNull(SkGfxAgent.rewrite(game, false, false, false));
    byte[] out = SkGfxAgent.rewrite(game, false, true, true);
    assertEquals(List.of("com/sirdank/skgfx/SkGfxHook.loadIdentity", "com/sirdank/skgfx/SkGfxHook.translatef",
      "com/sirdank/skgfx/SkGfxHook.bindTexture", "com/sirdank/skgfx/SkGfxHook.drawRangeElements"),
      invokedOwners(out));
    assertTrue(ClassFile.of().verify(out).isEmpty());
  }

  @Test
  public void leavesUnrelatedClassesAlone ()
  {
    byte[] plain = ClassFile.of().build(ClassDesc.of("fake.Plain"), cb -> cb
      .withMethodBody("noop", MethodTypeDesc.ofDescriptor("()V"), ACC_PUBLIC | ACC_STATIC,
        code -> code.return_()));
    assertFalse(SkGfxAgent.mentionsInterceptedOwner(plain));
    assertNull(SkGfxAgent.rewrite(plain));
    // A class that mentions GL11 but only calls untouched methods is also left alone.
    byte[] textureOnly = ClassFile.of().build(ClassDesc.of("fake.Textures"), cb -> cb
      .withMethodBody("bind", MethodTypeDesc.ofDescriptor("()V"), ACC_PUBLIC | ACC_STATIC, code -> code
        .iconst_0().iconst_0()
        .invokestatic(ClassDesc.ofInternalName("org/lwjgl/opengl/GL11"), "glBindTexture",
          MethodTypeDesc.ofDescriptor("(II)V"))
        .return_()));
    assertTrue(SkGfxAgent.mentionsInterceptedOwner(textureOnly));
    assertNull(SkGfxAgent.rewrite(textureOnly));
    assertTrue(SkGfxAgent.mentionsInterceptedOwner(fakeGameClass()));
  }
}
