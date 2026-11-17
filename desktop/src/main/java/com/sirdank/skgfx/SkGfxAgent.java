package com.sirdank.skgfx;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;

/**
 * Graphics-enhancement agent for the game ({@code -javaagent:...skgfx-agent.jar}). It never touches the game's own
 * classes by name: it rewrites call sites of a few stable LWJGL methods, so it keeps working across game updates.
 * <p>
 * Rewritten call sites (all single or few call sites in the game, and all on the render thread):
 * <ul>
 *   <li>{@code GLFW.glfwSwapBuffers(long)}: the per-frame hook where the scaled frame is presented.</li>
 *   <li>{@code GL30.glBindFramebuffer(int, int)}: a bind of framebuffer 0 lands on the offscreen framebuffer
 *       instead, so the game draws there without knowing.</li>
 *   <li>{@code GL11.glViewport/glScissor}: scaled while the game believes it is drawing to framebuffer 0, so the
 *       full-size layout it computes rasterizes into the scaled target. The game's believed sizes never change, so
 *       projection, UI layout and mouse picking stay exact; its own offscreen passes keep their real viewports.</li>
 *   <li>{@code GL11.glReadPixels/glCopyTexSubImage2D}: not replaced, but preceded by {@code SkGfxHook.beforeRead()}
 *       so a multisampled target is resolved before the game reads it.</li>
 * </ul>
 * Configured with system properties, passed next to the -javaagent line:
 * {@code skgfx.scale} (render scale, 0.25-4.0; above 1 supersamples, below 1 renders smaller and upscales),
 * {@code skgfx.sharpen} (0-1 contrast-adaptive sharpening), {@code skgfx.debugTint} (corner marker, for testing).
 */
public final class SkGfxAgent
{
  private static final String GLFW = "org/lwjgl/glfw/GLFW";
  private static final String GL11 = "org/lwjgl/opengl/GL11";
  private static final String GL12 = "org/lwjgl/opengl/GL12";
  private static final String GL30 = "org/lwjgl/opengl/GL30";
  private static final String GL_PREFIX = "org/lwjgl/opengl/GL";
  private static final ClassDesc HOOK = ClassDesc.of("com.sirdank.skgfx.SkGfxHook");
  private static final ClassDesc TRACE = ClassDesc.of("com.sirdank.skgfx.SkGfxTrace");
  private static final ClassDesc OBJECT = ClassDesc.of("java.lang.Object");

  public static void premain (String args, Instrumentation inst)
    throws Exception
  {
    // The hook classes must be visible to the game's classes, wherever they were loaded from.
    String self = SkGfxAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
    inst.appendToSystemClassLoaderSearch(new JarFile(self));
    inst.addTransformer(new Transformer());
    System.err.println("[skgfx] agent active: scale=" + SkGfxHook.SCALE + " sharpen=" + SkGfxHook.SHARPEN
      + (SkGfxTrace.ENABLED ? " TRACE (touch skgfx-trace.trigger in the game folder to capture)" : ""));
  }

  private static final class Transformer implements ClassFileTransformer
  {
    @Override
    public byte[] transform (ClassLoader loader, String name, Class<?> cls, ProtectionDomain domain, byte[] bytes)
    {
      // Never touch ourselves, LWJGL itself (the hook calls the real methods) or the JDK.
      if (name == null || name.startsWith("com/sirdank/skgfx/") || name.startsWith("org/lwjgl/")
        || name.startsWith("java/") || name.startsWith("jdk/") || name.startsWith("sun/")) {
        return null;
      }
      // Cheap pre-filter: only classes whose constant pool can name an intercepted owner are worth parsing,
      // and only a class where a call site was actually replaced is handed back rewritten. Everything else
      // keeps its original bytes, so the agent leaves the rest of the game untouched.
      if (!mentionsInterceptedOwner(bytes)) return null;
      try {
        return rewrite(bytes);
      } catch (Throwable t) {
        // An unreadable class is left as it was; the game must keep launching no matter what.
        System.err.println("[skgfx] couldn't instrument " + name + ": " + t);
        return null;
      }
    }
  }

  /** Rewrites the intercepted LWJGL call sites in one class, or returns null when it has none. */
  static byte[] rewrite (byte[] bytes)
  {
    return rewrite(bytes, SkGfxTrace.ENABLED);
  }

  static byte[] rewrite (byte[] bytes, boolean trace)
  {
    return rewrite(bytes, trace, SkGfxHook.HUD_SPLIT, SkGfxHook.FRAME_GEN);
  }

  static byte[] rewrite (byte[] bytes, boolean trace, boolean hudSplit)
  {
    return rewrite(bytes, trace, hudSplit, false);
  }

  /** As {@link #rewrite(byte[])}; with {@code trace}, every static GL/GLFW call is also logged before it runs. */
  static byte[] rewrite (byte[] bytes, boolean trace, boolean hudSplit, boolean camera)
  {
    boolean[] hit = {false};
    CodeTransform swap = (builder, element) -> {
      if (!(element instanceof InvokeInstruction call)) {
        builder.with(element);
        return;
      }
      if (isReadBack(call)) {
        hit[0] = true;
        if (trace && traceable(call)) emitTrace(builder, call);
        builder.invokestatic(HOOK, "beforeRead", MethodTypeDesc.ofDescriptor("()V"));
        builder.with(element);
        return;
      }
      if (!(camera && cameraHook(call) != null) && hudSplit && isDraw(call)) {
        hit[0] = true;
        if (trace && traceable(call)) emitTrace(builder, call);
        builder.invokestatic(HOOK, "beforeDraw", MethodTypeDesc.ofDescriptor("()V"));
        builder.with(element);
        return;
      }
      String target = hookMethod(call, hudSplit);
      if (target == null && camera) target = cameraHook(call);
      boolean traced = trace && traceable(call);
      if (target == null && !traced) {
        builder.with(element);
        return;
      }
      hit[0] = true;
      if (traced) emitTrace(builder, call);
      if (target != null) {
        builder.invokestatic(HOOK, target, MethodTypeDesc.ofDescriptor(call.type().stringValue()));
      } else {
        builder.with(element);
      }
    };
    ClassFile cf = ClassFile.of();
    byte[] out = cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(swap));
    return hit[0] ? out : null;
  }

  private static boolean traceable (InvokeInstruction call)
  {
    if (call.opcode() != Opcode.INVOKESTATIC) return false;
    String owner = call.owner().asInternalName();
    return owner.equals(GLFW) || owner.startsWith(GL_PREFIX);
  }

  /**
   * Logs a static call without disturbing it: its arguments are moved into fresh locals, handed to
   * {@code SkGfxTrace.call(name, args)} boxed, and put back on the stack for the original call.
   */
  private static void emitTrace (CodeBuilder b, InvokeInstruction call)
  {
    MethodTypeDesc mt = call.typeSymbol();
    int n = mt.parameterCount();
    TypeKind[] kinds = new TypeKind[n];
    int[] slots = new int[n];
    for (int i = 0; i < n; i++) {
      kinds[i] = TypeKind.from(mt.parameterType(i)).asLoadable();
      slots[i] = b.allocateLocal(kinds[i]);
    }
    for (int i = n - 1; i >= 0; i--) b.storeLocal(kinds[i], slots[i]);
    String owner = call.owner().asInternalName();
    b.loadConstant(owner.substring(owner.lastIndexOf('/') + 1) + "." + call.name().stringValue());
    b.loadConstant(n);
    b.anewarray(OBJECT);
    for (int i = 0; i < n; i++) {
      b.dup();
      b.loadConstant(i);
      b.loadLocal(kinds[i], slots[i]);
      box(b, kinds[i]);
      b.aastore();
    }
    b.invokestatic(TRACE, "call", MethodTypeDesc.ofDescriptor("(Ljava/lang/String;[Ljava/lang/Object;)V"));
    for (int i = 0; i < n; i++) b.loadLocal(kinds[i], slots[i]);
  }

  private static void box (CodeBuilder b, TypeKind kind)
  {
    switch (kind) {
      case INT -> b.invokestatic(ClassDesc.of("java.lang.Integer"), "valueOf", MethodTypeDesc.ofDescriptor("(I)Ljava/lang/Integer;"));
      case LONG -> b.invokestatic(ClassDesc.of("java.lang.Long"), "valueOf", MethodTypeDesc.ofDescriptor("(J)Ljava/lang/Long;"));
      case FLOAT -> b.invokestatic(ClassDesc.of("java.lang.Float"), "valueOf", MethodTypeDesc.ofDescriptor("(F)Ljava/lang/Float;"));
      case DOUBLE -> b.invokestatic(ClassDesc.of("java.lang.Double"), "valueOf", MethodTypeDesc.ofDescriptor("(D)Ljava/lang/Double;"));
      default -> { }
    }
  }

  /** Matrix, bind and draw calls the camera estimator replays; hook signatures match the originals. */
  private static String cameraHook (InvokeInstruction call)
  {
    String owner = call.owner().asInternalName();
    String name = call.name().stringValue();
    String desc = call.type().stringValue();
    boolean buffer = desc.equals("(Ljava/nio/FloatBuffer;)V");
    if (owner.equals(GL11)) {
      return switch (name) {
        case "glMatrixMode" -> desc.equals("(I)V") ? "matrixMode" : null;
        case "glLoadIdentity" -> desc.equals("()V") ? "loadIdentity" : null;
        case "glPushMatrix" -> desc.equals("()V") ? "pushMatrix" : null;
        case "glPopMatrix" -> desc.equals("()V") ? "popMatrix" : null;
        case "glTranslatef" -> desc.equals("(FFF)V") ? "translatef" : null;
        case "glScalef" -> desc.equals("(FFF)V") ? "scalef" : null;
        case "glRotatef" -> desc.equals("(FFFF)V") ? "rotatef" : null;
        case "glLoadMatrixf" -> buffer ? "loadMatrixf" : null;
        case "glMultMatrixf" -> buffer ? "multMatrixf" : null;
        case "glFrustum" -> desc.equals("(DDDDDD)V") ? "frustum" : null;
        case "glBindTexture" -> desc.equals("(II)V") ? "bindTexture" : null;
        default -> null;
      };
    }
    if (owner.equals("org/lwjgl/opengl/GL15") && name.equals("glBindBuffer") && desc.equals("(II)V")) return "bindBuffer";
    if (owner.equals(GL12) && name.equals("glDrawRangeElements") && desc.equals("(IIIIIJ)V")) return "drawRangeElements";
    return null;
  }

  /** Draw calls, counted (not replaced) so the HUD split knows the 3D world has been drawn. */
  private static boolean isDraw (InvokeInstruction call)
  {
    if (call.opcode() != Opcode.INVOKESTATIC) return false;
    String owner = call.owner().asInternalName();
    if (!owner.equals(GL11) && !owner.equals(GL12)) return false;
    String name = call.name().stringValue();
    return name.equals("glDrawElements") || name.equals("glDrawArrays") || name.equals("glDrawRangeElements");
  }

  /** Calls that read the bound framebuffer; the hook runs first so a multisampled target is resolved. */
  private static boolean isReadBack (InvokeInstruction call)
  {
    return call.opcode() == Opcode.INVOKESTATIC && call.owner().asInternalName().equals(GL11)
      && (call.name().stringValue().equals("glReadPixels") || call.name().stringValue().equals("glCopyTexSubImage2D"));
  }

  /** The SkGfxHook method replacing this call, or null for calls left alone. Hook signatures match the originals. */
  private static String hookMethod (InvokeInstruction call, boolean hudSplit)
  {
    String owner = call.owner().asInternalName();
    String name = call.name().stringValue();
    String desc = call.type().stringValue();
    if (owner.equals(GLFW) && name.equals("glfwSwapBuffers") && desc.equals("(J)V")) return "swapBuffers";
    if (owner.equals(GL11) && name.equals("glViewport") && desc.equals("(IIII)V")) return "viewport";
    if (owner.equals(GL11) && name.equals("glScissor") && desc.equals("(IIII)V")) return "scissor";
    if (owner.equals(GL30) && name.equals("glBindFramebuffer") && desc.equals("(II)V")) return "bindFramebuffer";
    if (hudSplit && owner.equals(GL11)) {
      if (name.equals("glOrtho") && desc.equals("(DDDDDD)V")) return "ortho";
      if (name.equals("glEnable") && desc.equals("(I)V")) return "enable";
      if (name.equals("glDisable") && desc.equals("(I)V")) return "disable";
      if (name.equals("glBlendFunc") && desc.equals("(II)V")) return "blendFunc";
    }
    return null;
  }

  /** Scans raw class bytes for the UTF-8 constants naming the intercepted owners. */
  static boolean mentionsInterceptedOwner (byte[] bytes)
  {
    if (SkGfxTrace.ENABLED) return contains(bytes, OWNER_GLFW) || contains(bytes, OWNER_GL_PREFIX);
    return contains(bytes, OWNER_GLFW) || contains(bytes, OWNER_GL11) || contains(bytes, OWNER_GL12)
      || contains(bytes, OWNER_GL30);
  }

  private static final byte[] OWNER_GLFW = GLFW.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private static final byte[] OWNER_GL11 = GL11.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private static final byte[] OWNER_GL12 = GL12.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private static final byte[] OWNER_GL30 = GL30.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private static final byte[] OWNER_GL_PREFIX = GL_PREFIX.getBytes(java.nio.charset.StandardCharsets.UTF_8);

  private static boolean contains (byte[] haystack, byte[] needle)
  {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) continue outer;
      }
      return true;
    }
    return false;
  }

  private SkGfxAgent () { }
}
