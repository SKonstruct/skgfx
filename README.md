# skgfx

Graphics enhancements for Spiral Knights, shared by the SKonstruct launchers: render scale, contrast-adaptive
sharpening, a sharp HUD layer, an FPS counter and (experimental) frame generation.

> Unofficial fan project. Not affiliated with Grey Havens. Spiral Knights © Grey Havens, LLC.

## Layout

- `mobile/`: `skgfx.c` and `skgfx.h`, the present pass used by the Android and iOS apps (OpenGL ES through gl4es).
  This is the single source for both apps.
- `desktop/`: the Java agent for desktop Spiral Knights (see below). An independent Maven project.

## Desktop agent

`desktop/` builds `skgfx-agent.jar`, a Java agent that runs inside the game's JVM. It never touches the game's own
classes by name: it rewrites call sites of a few stable LWJGL methods (`glfwSwapBuffers`, `glBindFramebuffer`,
`glViewport`, `glScissor`, ...) so the game renders into an offscreen target that is scaled, optionally sharpened and
then presented. It keeps working across game updates and falls back to the plain calls if anything fails.

**Requires Java 24 or newer** (it rewrites classes with `java.lang.classfile`, final in Java 24; the jar is built for
Java 24). LWJGL is a compile-time (`provided`) dependency only: the agent uses the LWJGL 3 already on the game's
class path (built against 3.4.3).

### Build

```
cd desktop
./mvnw -B verify
```

This uses the bundled Apache Maven Wrapper (`mvnw.cmd` on Windows) and needs only a JDK 24 or newer on `JAVA_HOME` or `PATH` (CI builds with 25).
The jar is written to `desktop/target/skgfx-agent.jar`.

### Use

Add the agent and the properties you want to the game's JVM arguments:

```
-javaagent:/path/to/skgfx-agent.jar -Dskgfx.scale=1.5 -Dskgfx.sharpen=0.4
```

### Properties

With no properties set the agent does nothing visible except the FPS counter.

| Property | Values | Effect |
| --- | --- | --- |
| `skgfx.scale` | 0.25 to 4.0, default 1 | Render scale. Above 1 supersamples, below 1 renders smaller and upscales. |
| `skgfx.sharpen` | 0 to 1, default 0 | Contrast-adaptive sharpening strength (0 is a plain bilinear fetch). |
| `skgfx.hudsplit` | `true`/`false` | Draw the HUD in its own transparent layer at full resolution and composite it over the (scaled) scene. |
| `skgfx.counter` | `false` to disable | FPS counter in the window's top left corner; on by default. |
| `skgfx.fg` | `true`/`false` | Frame generation prototype (experimental): shows a blend of the previous and current frame between real frames. Implies `skgfx.hudsplit` and `skgfx.stats`. |
| `skgfx.fg.multiplier` | 1.1 to 6.0 | Fixed mode: frames shown per real frame. When unset, frame generation is adaptive. |
| `skgfx.fg.target` | fps, default 0 | Adaptive mode: the frame rate to reach; 0 means the screen's refresh rate. |
| `skgfx.fg.swapinterval0` | `true` (default)/`false` | Whether frame generation forces `glfwSwapInterval(0)`. |
| `skgfx.stats` | `true`/`false` | Log real fps and swap-to-swap spacing every few seconds. |
| `skgfx.debugTint` | `true`/`false` | Corner marker, for testing. |
| `skgfx.gpuTime` | `true`/`false` | Test aid: measure the GPU time of the agent's passes (stalls the pipeline). |
| `skgfx.fg.nowait` | `true`/`false` | Test aid: skip the waits between frames. |
| `skgfx.fg.debugHold` | `true`/`false` | Test aid: show a generated frame in place of the real one. |
| `skgfx.fg.debugShift` | `dx,dy,dz` | Test aid: replace the camera delta with a fixed eye-space translation. |
| `skgfx.trace` | `true`/`false` | Developer tool: record every GL/GLFW call of one or two frames to `skgfx-trace-<n>.txt`. |
| `skgfx.trace.frame` | frame number (counting from 1) | Start the capture at this frame (otherwise create `skgfx-trace.trigger` in the working directory). |
| `skgfx.trace.frames` | count, default 2 | Number of frames a capture covers. |

## License

[MIT](LICENSE). The sharpening shader follows AMD FidelityFX CAS; see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
