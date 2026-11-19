// SKapsule's graphics present pass. See skgfx.h. The single source for SKapsule (Android) and SKapsule-iOS.
//
// The game believes it draws into the window (framebuffer 0). gl4es maps that to a framebuffer we
// choose (gl4es_skgfx_set_main_framebuffer), so the game lands in our scene target; at swap time one
// fullscreen draw puts it on the window, sharpened by CAS when asked. With Sharp HUD, the game's HUD
// pass (found by the same rule on desktop: the first full-window glOrtho after the 3D world) is
// redirected into a second target at the window's real resolution and composited over the scene.
//
// GL discipline: gl4es caches GL state, so anything it tracks is changed through its own entry points,
// and everything we change behind its back (our programs, bindings, vertex attribute 0) is restored to
// exactly what it was. Nothing here draws text: the FPS counter is a native view fed by skgfx_frames().

#include "skgfx.h"

#include <dlfcn.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

#if defined(__APPLE__)
#include <OpenGLES/ES2/gl.h>
#include <OpenGLES/ES2/glext.h>
#else
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#endif

#if defined(__ANDROID__)
#include <android/log.h>
#define SKLOG(...) __android_log_print(ANDROID_LOG_INFO, "skgfx", __VA_ARGS__)
#else
#define SKLOG(...) do { fprintf(stderr, "[skgfx] "); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

// Must match gl4es_skgfx_hooks_t in gl4es' framebuffers.h.
typedef struct {
    void (*ortho)(double left, double right, double bottom, double top, int on_default_fb);
    void (*draw)(int depth_test);
    void (*viewport)(int on_default_fb, GLint *x, GLint *y, GLsizei *width, GLsizei *height);
    void (*scissor)(int on_default_fb, GLint *x, GLint *y, GLsizei *width, GLsizei *height);
    int (*blendfunc)(GLenum sfactor, GLenum dfactor);
} gl4es_hooks_t;

// An orthographic pass counts as the HUD only after this many depth-tested draws (menus have a 2D
// background first).
#define MIN_WORLD_DRAWS 20
#define MAX_ATTRIBS 16

// --- configuration (any thread) ---------------------------------------------------------------
static _Atomic int cfg_enabled;
static _Atomic int cfg_scale_pm = 1000;  // per mille
static _Atomic int cfg_sharp_pm;         // per mille
static _Atomic int cfg_hud;
static _Atomic int cfg_failed;           // set once the pass could not be set up: everything stays off
static atomic_uint_fast64_t frame_count;

static int scaled_dim(int n, int pm) {
    int v = (int)(((long long)n * pm + 500) / 1000);
    return v < 1 ? 1 : v;
}

static int hud_effective(int nw, int nh) {
    int pm = atomic_load(&cfg_scale_pm);
    return atomic_load(&cfg_hud) && (scaled_dim(nw, pm) != nw || scaled_dim(nh, pm) != nh);
}

void skgfx_set_config(int enabled, float scale, float sharpen, int hud) {
    // A NaN passes every comparison below and would reach an int cast (undefined behaviour): use the defaults.
    if (scale != scale) scale = 1.0f;
    if (sharpen != sharpen) sharpen = 0.0f;
    if (scale < 0.25f) scale = 0.25f;
    if (scale > 1.0f) scale = 1.0f;
    if (sharpen < 0.0f) sharpen = 0.0f;
    if (sharpen > 1.0f) sharpen = 1.0f;
    atomic_store(&cfg_scale_pm, (int)(scale * 1000.0f + 0.5f));
    atomic_store(&cfg_sharp_pm, (int)(sharpen * 1000.0f + 0.5f));
    atomic_store(&cfg_hud, hud != 0);
    atomic_store(&cfg_enabled, enabled != 0);
}

void skgfx_believed_size(int nw, int nh, int *bw, int *bh) {
    if (!bw || !bh) return;
    // A surface that is gone or not laid out yet reports 0 x 0: there is nothing to scale, so the platform's own
    // numbers are returned unchanged (scaled_dim would turn 0 into 1 and the scale factor into infinity).
    if (nw <= 0 || nh <= 0 || !atomic_load(&cfg_enabled) || atomic_load(&cfg_failed)) {
        *bw = nw;
        *bh = nh;
        return;
    }
    if (hud_effective(nw, nh)) {
        *bw = nw;  // Sharp HUD: the game lays out at the window's real size
        *bh = nh;
    } else {
        int pm = atomic_load(&cfg_scale_pm);
        *bw = scaled_dim(nw, pm);
        *bh = scaled_dim(nh, pm);
    }
}

uint64_t skgfx_frames(void) { return atomic_load(&frame_count); }

int skgfx_wanted(void) { return atomic_load(&cfg_enabled) && !atomic_load(&cfg_failed); }

// --- render-thread state ----------------------------------------------------------------------
typedef struct {
    GLuint fbo, tex, depth;
    int w, h;
} target_t;

static struct {
    int ready;
    int engaged;
    int hud_broken;
    int bypass;       // our own viewport calls must not be rescaled by the hooks
    int ui_active;
    int packed_ds;    // packed depth+stencil renderbuffers available
    int nw, nh;       // native window size at the last reconfigure
    int hud_eff;
    float fx, fy;     // scene size / window size, for scaling the game's viewport and scissor
    target_t scene, ui;
    GLuint prog_cas, prog_copy, vbo;
    GLint cas_tex, cas_texel, cas_sharp, copy_tex;
    int world_draws;
    // What the game believes it set, so the scene's state can be put back after the HUD layer. A zero-sized scissor is
    // a real setting (it clips everything), so whether one was recorded is a flag, not a size above zero.
    GLint bvx, bvy, bvw, bvh, bsx, bsy, bsw, bsh;
    int have_vp, have_sc;
    GLenum bblend_s, bblend_d;
    int errors_logged;
    gl4es_hooks_t *hooks;
    void (*set_main_fb)(GLuint);
    void (*glViewport)(GLint, GLint, GLsizei, GLsizei);
    void (*glScissor)(GLint, GLint, GLsizei, GLsizei);
    void (*glBlendFunc)(GLenum, GLenum);
    void (*glBlendFuncSeparate)(GLenum, GLenum, GLenum, GLenum);
} g = { .bblend_s = GL_SRC_ALPHA, .bblend_d = GL_ONE_MINUS_SRC_ALPHA };

static int drain_errors(const char *where) {
    int n = 0;
    GLenum first = GL_NO_ERROR, e;
    while ((e = glGetError()) != GL_NO_ERROR && n < 16) {
        if (!n) first = e;
        n++;
    }
    if (n && !g.errors_logged) {
        g.errors_logged = 1;
        SKLOG("GL error 0x%x (%d in all) after %s (logged once)", (unsigned)first, n, where);
    }
    return n;
}

// Errors the game (or gl4es) left in the queue are not ours: clear them before our own GL work, so only our own
// calls can make a following drain_errors() fail the pass.
static void clear_pending_errors(void) {
    int n = 0;
    while (glGetError() != GL_NO_ERROR && n < 16) n++;
}

// --- shaders ----------------------------------------------------------------------------------
static const char *VERT_SRC =
    "attribute vec2 aPos;\n"
    "varying vec2 vUv;\n"
    "void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }\n";

// Contrast-adaptive sharpening, after AMD FidelityFX CAS (MIT, see THIRD-PARTY-NOTICES.md): the neighbours' range
// sets how hard each pixel is pushed, so flat areas are left alone and already-contrasty edges do not ring.
static const char *CAS_SRC =
    "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
    "precision highp float;\n"
    "#else\n"
    "precision mediump float;\n"
    "#endif\n"
    "uniform sampler2D uTex;\n"
    "uniform vec2 uTexel;\n"
    "uniform float uSharp;\n"
    "varying vec2 vUv;\n"
    "void main() {\n"
    "  vec3 e = texture2D(uTex, vUv).rgb;\n"
    "  if (uSharp <= 0.0) { gl_FragColor = vec4(e, 1.0); return; }\n"
    "  vec3 b = texture2D(uTex, vUv + vec2(0.0, -uTexel.y)).rgb;\n"
    "  vec3 d = texture2D(uTex, vUv + vec2(-uTexel.x, 0.0)).rgb;\n"
    "  vec3 f = texture2D(uTex, vUv + vec2(uTexel.x, 0.0)).rgb;\n"
    "  vec3 h = texture2D(uTex, vUv + vec2(0.0, uTexel.y)).rgb;\n"
    "  vec3 mn = min(min(min(d, e), min(f, b)), h);\n"
    "  vec3 mx = max(max(max(d, e), max(f, b)), h);\n"
    "  vec3 amp = clamp(min(mn, 1.0 - mx) / (mx + 1e-4), 0.0, 1.0);\n"
    "  amp = sqrt(amp);\n"
    "  float peak = -1.0 / mix(8.0, 5.0, uSharp);\n"
    "  vec3 w = amp * peak;\n"
    "  gl_FragColor = vec4(clamp(((b + d + f + h) * w + e) / (4.0 * w + 1.0), 0.0, 1.0), 1.0);\n"
    "}\n";

// The HUD layer is sampled with the same vUv as the scene: mediump (fp16) would quantise it to 1/1024 steps across
// the right half of a wide screen, so it asks for highp like CAS_SRC does.
static const char *COPY_SRC =
    "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
    "precision highp float;\n"
    "#else\n"
    "precision mediump float;\n"
    "#endif\n"
    "uniform sampler2D uTex;\n"
    "varying vec2 vUv;\n"
    "void main() { gl_FragColor = texture2D(uTex, vUv); }\n";

static GLuint compile(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        glGetShaderInfoLog(s, sizeof log - 1, NULL, log);
        SKLOG("shader compile failed: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static GLuint link_program(const char *frag) {
    GLuint v = compile(GL_VERTEX_SHADER, VERT_SRC), f = compile(GL_FRAGMENT_SHADER, frag);
    if (!v || !f) {
        if (v) glDeleteShader(v);
        if (f) glDeleteShader(f);
        return 0;
    }
    GLuint p = glCreateProgram();
    glAttachShader(p, v);
    glAttachShader(p, f);
    glBindAttribLocation(p, 0, "aPos");
    glLinkProgram(p);
    glDeleteShader(v);
    glDeleteShader(f);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        glGetProgramInfoLog(p, sizeof log - 1, NULL, log);
        SKLOG("program link failed: %s", log);
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

// --- targets ----------------------------------------------------------------------------------
// (Re)allocates a colour texture + depth/stencil renderbuffer behind one framebuffer. The framebuffer
// name never changes, so what gl4es holds stays valid across a resize.
static int target_resize(target_t *t, int w, int h) {
    GLint prev_fbo = 0, prev_tex = 0, prev_rb = 0;
    clear_pending_errors();
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prev_fbo);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &prev_tex);
    glGetIntegerv(GL_RENDERBUFFER_BINDING, &prev_rb);
    if (!t->fbo) {
        glGenFramebuffers(1, &t->fbo);
        glGenTextures(1, &t->tex);
        glGenRenderbuffers(1, &t->depth);
    }
    glBindTexture(GL_TEXTURE_2D, t->tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glBindRenderbuffer(GL_RENDERBUFFER, t->depth);
    glRenderbufferStorage(GL_RENDERBUFFER, g.packed_ds ? GL_DEPTH24_STENCIL8_OES : GL_DEPTH_COMPONENT16, w, h);
    glBindFramebuffer(GL_FRAMEBUFFER, t->fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t->tex, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, t->depth);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, g.packed_ds ? t->depth : 0);
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)prev_fbo);
    glBindRenderbuffer(GL_RENDERBUFFER, (GLuint)prev_rb);
    glBindTexture(GL_TEXTURE_2D, (GLuint)prev_tex);
    int errs = drain_errors("target resize");
    if (status != GL_FRAMEBUFFER_COMPLETE || errs) {
        SKLOG("target %dx%d incomplete (status 0x%x)", w, h, status);
        return 0;
    }
    t->w = w;
    t->h = h;
    return 1;
}

static void target_free(target_t *t) {
    if (t->fbo) glDeleteFramebuffers(1, &t->fbo);
    if (t->tex) glDeleteTextures(1, &t->tex);
    if (t->depth) glDeleteRenderbuffers(1, &t->depth);
    memset(t, 0, sizeof *t);
}

// --- the game's GL calls, as seen through gl4es -----------------------------------------------
static int round_pos(GLint v, float f) { return (int)(v * f + (v < 0 ? -0.5f : 0.5f)); }
// A viewport or scissor may be empty (an empty scissor clips everything): zero stays zero, a real size never
// rounds to nothing. (Targets are never empty: scaled_dim.)
static int round_size(GLsizei v, float f) {
    if (v <= 0) return (int)v;
    int r = (int)(v * f + 0.5f);
    return r < 1 ? 1 : r;
}

// Alpha blend factors for the HUD layer's coverage: it accumulates (over), except under additive blending
// (destination factor ONE: glow adds colour and covers nothing, or it would darken the scene behind it).
static GLenum coverage_src_alpha(GLenum dfactor) { return dfactor == GL_ONE ? GL_ZERO : GL_ONE; }
static GLenum coverage_dst_alpha(GLenum dfactor) { return dfactor == GL_ONE ? GL_ONE : GL_ONE_MINUS_SRC_ALPHA; }

static void h_draw(int depth_test) {
    if (depth_test) g.world_draws++;
}

static void h_viewport(int on_default, GLint *x, GLint *y, GLsizei *w, GLsizei *h) {
    if (g.bypass || !on_default) return;
    g.bvx = *x; g.bvy = *y; g.bvw = *w; g.bvh = *h;
    g.have_vp = 1;
    if (g.engaged && g.hud_eff && !g.ui_active) {
        *x = round_pos(*x, g.fx); *y = round_pos(*y, g.fy);
        *w = round_size(*w, g.fx); *h = round_size(*h, g.fy);
    }
}

static void h_scissor(int on_default, GLint *x, GLint *y, GLsizei *w, GLsizei *h) {
    if (g.bypass || !on_default) return;
    g.bsx = *x; g.bsy = *y; g.bsw = *w; g.bsh = *h;
    g.have_sc = 1;
    if (g.engaged && g.hud_eff && !g.ui_active) {
        *x = round_pos(*x, g.fx); *y = round_pos(*y, g.fy);
        *w = round_size(*w, g.fx); *h = round_size(*h, g.fy);
    }
}

static int h_blendfunc(GLenum s, GLenum d) {
    g.bblend_s = s;
    g.bblend_d = d;
    if (g.ui_active) {
        // The HUD layer starts transparent: colour blends as the game asked, alpha accumulates as coverage.
        g.glBlendFuncSeparate(s, d, coverage_src_alpha(d), coverage_dst_alpha(d));
        return 1;
    }
    return 0;
}

// The HUD starts here: the scene is complete, so the game draws on into a cleared, transparent layer
// at the window's real size.
static void begin_ui(void) {
    if (g.ui.w != g.nw || g.ui.h != g.nh) {
        if (!target_resize(&g.ui, g.nw, g.nh)) {
            SKLOG("Sharp HUD layer unavailable: HUD stays in the scene");
            g.hud_broken = 1;
            target_free(&g.ui);
            return;
        }
    }
    g.set_main_fb(g.ui.fbo);
    static int announced;
    if (!announced) {
        announced = 1;
        SKLOG("HUD layer starts after %d depth-tested draws", g.world_draws);
    }
    // Defaults for what a failed query leaves untouched, so nothing uninitialised is replayed below.
    GLboolean cmask[4] = { GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE }, dmask = GL_TRUE, scissor = glIsEnabled(GL_SCISSOR_TEST);
    GLint smask = 0xFF, smask_back = 0xFF, sclear = 0;
    GLfloat ccol[4] = { 0.0f, 0.0f, 0.0f, 0.0f }, dclear = 1.0f;
    glGetBooleanv(GL_COLOR_WRITEMASK, cmask);
    glGetBooleanv(GL_DEPTH_WRITEMASK, &dmask);
    glGetIntegerv(GL_STENCIL_WRITEMASK, &smask);
    glGetIntegerv(GL_STENCIL_BACK_WRITEMASK, &smask_back);
    glGetFloatv(GL_COLOR_CLEAR_VALUE, ccol);
    glGetFloatv(GL_DEPTH_CLEAR_VALUE, &dclear);
    glGetIntegerv(GL_STENCIL_CLEAR_VALUE, &sclear);
    glDisable(GL_SCISSOR_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glDepthMask(GL_TRUE);
    glStencilMask(0xFF);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClearDepthf(1.0f);
    glClearStencil(0);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
    glClearColor(ccol[0], ccol[1], ccol[2], ccol[3]);
    glClearDepthf(dclear);
    glClearStencil(sclear);
    glStencilMaskSeparate(GL_FRONT, (GLuint)smask);
    glStencilMaskSeparate(GL_BACK, (GLuint)smask_back);
    glDepthMask(dmask);
    glColorMask(cmask[0], cmask[1], cmask[2], cmask[3]);
    if (scissor) glEnable(GL_SCISSOR_TEST);
    drain_errors("HUD begin");
    // The game believes its viewport and scissor are unchanged and will not issue them again: the scene's were
    // scaled, so put back what it set (the whole window when it never set a viewport).
    g.bypass = 1;
    if (g.have_vp) g.glViewport(g.bvx, g.bvy, g.bvw, g.bvh);
    else g.glViewport(0, 0, g.nw, g.nh);
    if (g.have_sc) g.glScissor(g.bsx, g.bsy, g.bsw, g.bsh);
    g.bypass = 0;
    g.ui_active = 1;
    g.glBlendFuncSeparate(g.bblend_s, g.bblend_d, coverage_src_alpha(g.bblend_d), coverage_dst_alpha(g.bblend_d));
}

static void h_ortho(double l, double r, double b, double t, int on_default) {
    if (!g.engaged || !g.hud_eff || g.hud_broken || g.ui_active || !on_default) return;
    if (g.world_draws < MIN_WORLD_DRAWS) return;
    double w = r - l, h = t - b;
    if (w < g.nw - 1.5 || w > g.nw + 1.5 || h < g.nh - 1.5 || h > g.nh + 1.5) return;
    begin_ui();
}

// --- engaging and resizing --------------------------------------------------------------------
// The viewport and scissor the game believes it has set, put back as they are (the hooks must not scale them).
static void apply_game_state(void) {
    g.bypass = 1;
    if (g.have_vp) g.glViewport(g.bvx, g.bvy, g.bvw, g.bvh);
    if (g.have_sc) g.glScissor(g.bsx, g.bsy, g.bsw, g.bsh);
    g.bypass = 0;
}

// The same, scaled to the scene target the game is drawing into while Sharp HUD keeps its own size unscaled.
static void apply_scene_state(void) {
    if (!g.engaged || !g.hud_eff || g.ui_active) return;
    g.bypass = 1;
    if (g.have_vp) g.glViewport(round_pos(g.bvx, g.fx), round_pos(g.bvy, g.fy), round_size(g.bvw, g.fx), round_size(g.bvh, g.fy));
    else g.glViewport(0, 0, g.scene.w, g.scene.h);
    if (g.have_sc) g.glScissor(round_pos(g.bsx, g.fx), round_pos(g.bsy, g.fy), round_size(g.bsw, g.fx), round_size(g.bsh, g.fy));
    g.bypass = 0;
}

static void disengage(unsigned window_fbo) {
    if (g.engaged) {
        SKLOG("disengaged: the game draws into the window again");
        g.set_main_fb(window_fbo);
        // Whatever the hooks changed behind the game's back goes back to what it believes: its plain blend function
        // after the HUD layer, and its own (unscaled) viewport and scissor after the scene's scaled ones.
        if (g.ui_active) g.glBlendFunc(g.bblend_s, g.bblend_d);
        if (g.hud_eff && !g.ui_active) apply_game_state();
    }
    g.engaged = 0;
    g.ui_active = 0;
    target_free(&g.ui);
    target_free(&g.scene);
}

static void fail(unsigned window_fbo, const char *why) {
    SKLOG("graphics pass disabled: %s", why);
    atomic_store(&cfg_failed, 1);
    disengage(window_fbo);
}

static void reconfigure(unsigned window_fbo, int nw, int nh) {
    if (!g.ready) return;
    // A surface of 0 x 0 (backgrounded, mid-rotation) is not a size to build targets for; the next frame's size
    // is. Nothing changes until then.
    if (nw <= 0 || nh <= 0) return;
    if (!atomic_load(&cfg_enabled) || atomic_load(&cfg_failed)) {
        if (g.engaged) disengage(window_fbo);
        return;
    }
    int pm = atomic_load(&cfg_scale_pm);
    int sw = scaled_dim(nw, pm), sh = scaled_dim(nh, pm);
    g.nw = nw;
    g.nh = nh;
    g.hud_eff = hud_effective(nw, nh);
    g.fx = (float)sw / (float)nw;
    g.fy = (float)sh / (float)nh;
    if (!g.hud_eff && g.ui.fbo) target_free(&g.ui);
    if (g.scene.w != sw || g.scene.h != sh) {
        if (!target_resize(&g.scene, sw, sh)) {
            fail(window_fbo, "scene target");
            return;
        }
    }
    if (!g.engaged) {
        g.set_main_fb(g.scene.fbo);
        g.engaged = 1;
        g.world_draws = 0;
        SKLOG("engaged: scene %dx%d in window %dx%d, sharpen %d, Sharp HUD %d", sw, sh, nw, nh,
              atomic_load(&cfg_sharp_pm) / 10, g.hud_eff);
    }
}

// --- the present pass -------------------------------------------------------------------------
typedef struct {
    GLint fbo, prog, array_buf, viewport[4], active_tex, tex0;
    GLboolean scissor, depth, stencil, cull, blend, polyoff;
    GLint blend_src_rgb, blend_dst_rgb, blend_src_a, blend_dst_a, eq_rgb, eq_a;
    GLboolean cmask[4];
    GLint max_attribs, attrib_on[MAX_ATTRIBS];
    GLint a0_size, a0_type, a0_norm, a0_stride, a0_buf;
    void *a0_ptr;
} saved_t;

static void save_state(saved_t *s) {
    // Defaults for what a failed query leaves untouched, so restore_state never replays garbage.
    s->a0_size = 4;
    s->a0_type = GL_FLOAT;
    s->active_tex = GL_TEXTURE0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &s->fbo);
    glGetIntegerv(GL_CURRENT_PROGRAM, &s->prog);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &s->array_buf);
    glGetIntegerv(GL_VIEWPORT, s->viewport);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &s->active_tex);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &s->tex0);
    s->scissor = glIsEnabled(GL_SCISSOR_TEST);
    s->depth = glIsEnabled(GL_DEPTH_TEST);
    s->stencil = glIsEnabled(GL_STENCIL_TEST);
    s->cull = glIsEnabled(GL_CULL_FACE);
    s->blend = glIsEnabled(GL_BLEND);
    s->polyoff = glIsEnabled(GL_POLYGON_OFFSET_FILL);
    glGetIntegerv(GL_BLEND_SRC_RGB, &s->blend_src_rgb);
    glGetIntegerv(GL_BLEND_DST_RGB, &s->blend_dst_rgb);
    glGetIntegerv(GL_BLEND_SRC_ALPHA, &s->blend_src_a);
    glGetIntegerv(GL_BLEND_DST_ALPHA, &s->blend_dst_a);
    glGetIntegerv(GL_BLEND_EQUATION_RGB, &s->eq_rgb);
    glGetIntegerv(GL_BLEND_EQUATION_ALPHA, &s->eq_a);
    glGetBooleanv(GL_COLOR_WRITEMASK, s->cmask);
    glGetIntegerv(GL_MAX_VERTEX_ATTRIBS, &s->max_attribs);
    if (s->max_attribs > MAX_ATTRIBS) s->max_attribs = MAX_ATTRIBS;
    if (s->max_attribs < 0) s->max_attribs = 0;
    for (int i = 0; i < s->max_attribs; i++) glGetVertexAttribiv(i, GL_VERTEX_ATTRIB_ARRAY_ENABLED, &s->attrib_on[i]);
    glGetVertexAttribiv(0, GL_VERTEX_ATTRIB_ARRAY_SIZE, &s->a0_size);
    glGetVertexAttribiv(0, GL_VERTEX_ATTRIB_ARRAY_TYPE, &s->a0_type);
    glGetVertexAttribiv(0, GL_VERTEX_ATTRIB_ARRAY_NORMALIZED, &s->a0_norm);
    glGetVertexAttribiv(0, GL_VERTEX_ATTRIB_ARRAY_STRIDE, &s->a0_stride);
    glGetVertexAttribiv(0, GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING, &s->a0_buf);
    glGetVertexAttribPointerv(0, GL_VERTEX_ATTRIB_ARRAY_POINTER, &s->a0_ptr);
}

static void restore_state(const saved_t *s) {
    // Vertex attribute 0 first (it needs its own buffer bound while the pointer is set).
    glBindBuffer(GL_ARRAY_BUFFER, (GLuint)s->a0_buf);
    if (s->a0_size >= 1 && s->a0_size <= 4)
        glVertexAttribPointer(0, s->a0_size, (GLenum)s->a0_type, (GLboolean)s->a0_norm, s->a0_stride, s->a0_ptr);
    for (int i = 0; i < s->max_attribs; i++) {
        if (s->attrib_on[i]) glEnableVertexAttribArray(i); else glDisableVertexAttribArray(i);
    }
    glBindBuffer(GL_ARRAY_BUFFER, (GLuint)s->array_buf);
    glUseProgram((GLuint)s->prog);
    glBindTexture(GL_TEXTURE_2D, (GLuint)s->tex0);
    glActiveTexture((GLenum)s->active_tex);
    glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)s->fbo);
    glViewport(s->viewport[0], s->viewport[1], s->viewport[2], s->viewport[3]);
    glColorMask(s->cmask[0], s->cmask[1], s->cmask[2], s->cmask[3]);
    glBlendEquationSeparate((GLenum)s->eq_rgb, (GLenum)s->eq_a);
    glBlendFuncSeparate((GLenum)s->blend_src_rgb, (GLenum)s->blend_dst_rgb, (GLenum)s->blend_src_a, (GLenum)s->blend_dst_a);
    if (s->scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
    if (s->depth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
    if (s->stencil) glEnable(GL_STENCIL_TEST); else glDisable(GL_STENCIL_TEST);
    if (s->cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
    if (s->blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
    if (s->polyoff) glEnable(GL_POLYGON_OFFSET_FILL); else glDisable(GL_POLYGON_OFFSET_FILL);
}

// Opt-in measurement of what the pass costs the GPU (it stalls the pipeline, so never on by default):
// Android `setprop debug.skgfx.time 1` before the game starts, iOS the SKGFX_TIME environment variable.
static int timing_enabled(void) {
    static int on = -1;
    if (on < 0) {
#if defined(__ANDROID__)
        char value[PROP_VALUE_MAX] = {0};
        on = __system_property_get("debug.skgfx.time", value) > 0 && value[0] == '1';
#else
        const char *value = getenv("SKGFX_TIME");
        on = value && value[0] == '1';  // SKGFX_TIME=0 (or empty) is off, as on Android
#endif
    }
    return on;
}

static double ms_since(const struct timespec *t0) {
    struct timespec t1;
    clock_gettime(CLOCK_MONOTONIC, &t1);
    return (double)(t1.tv_sec - t0->tv_sec) * 1e3 + (double)(t1.tv_nsec - t0->tv_nsec) / 1e6;
}

static void draw_quad(GLuint prog, GLuint tex) {
    glUseProgram(prog);
    glBindTexture(GL_TEXTURE_2D, tex);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

int skgfx_present(unsigned window_fbo, int nw, int nh) {
    atomic_fetch_add(&frame_count, 1);
    if (!g.ready || !g.engaged || nw <= 0 || nh <= 0) return 0;
    saved_t s;
    memset(&s, 0, sizeof s);
    struct timespec t0;
    int timed = timing_enabled();
    if (timed) {
        glFinish();  // the game's own frame finishes first, so only our pass is measured below
        clock_gettime(CLOCK_MONOTONIC, &t0);
    }
    save_state(&s);
    glBindFramebuffer(GL_FRAMEBUFFER, window_fbo);
    glViewport(0, 0, nw, nh);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_POLYGON_OFFSET_FILL);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindBuffer(GL_ARRAY_BUFFER, g.vbo);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, (const void *)0);
    for (int i = 0; i < s.max_attribs; i++) {
        if (i == 0) glEnableVertexAttribArray(0); else glDisableVertexAttribArray(i);
    }
    // Scene -> window, sharpened when asked (a sharpness of 0 is a plain copy).
    glDisable(GL_BLEND);
    glUseProgram(g.prog_cas);
    glUniform2f(g.cas_texel, 1.0f / (float)g.scene.w, 1.0f / (float)g.scene.h);
    glUniform1f(g.cas_sharp, (float)atomic_load(&cfg_sharp_pm) / 1000.0f);
    draw_quad(g.prog_cas, g.scene.tex);
    // The HUD layer over it, premultiplied.
    if (g.ui_active) {
        glEnable(GL_BLEND);
        glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
        glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        draw_quad(g.prog_copy, g.ui.tex);
    }
    restore_state(&s);
    drain_errors("present");
    if (timed) {
        static double total_ms;
        static int frames;
        glFinish();
        total_ms += ms_since(&t0);
        if (++frames == 120) {
            SKLOG("present pass: %.2f ms per frame over %d frames (scene %dx%d -> window %dx%d, HUD %d)",
                  total_ms / frames, frames, g.scene.w, g.scene.h, nw, nh, g.ui_active);
            total_ms = 0;
            frames = 0;
        }
    }
    return 1;
}

void skgfx_after_swap(unsigned window_fbo, int nw, int nh) {
    if (!g.ready) return;
    int was_ui = g.ui_active;
    int was_engaged = g.engaged, was_hud = g.hud_eff;
    float was_fx = g.fx, was_fy = g.fy;
    g.world_draws = 0;
    reconfigure(window_fbo, nw, nh);  // may disengage (and then undo the HUD's blend function itself)
    if (g.engaged) {
        // The HUD layer is over: the game goes back to the scene, with the scene's viewport, scissor and blend
        // function (it does not repeat them). The same when the scene was just engaged or its size changed.
        g.ui_active = 0;
        // Anything else on this thread may have rebound a framebuffer since (SKapsule-iOS rebuilds the window's
        // renderbuffers between frames), so say where the game draws, every frame.
        g.set_main_fb(g.scene.fbo);
        if (was_ui) g.glBlendFunc(g.bblend_s, g.bblend_d);
        if (was_ui || !was_engaged || was_hud != g.hud_eff || was_fx != g.fx || was_fy != g.fy) apply_scene_state();
    } else {
        g.ui_active = 0;
    }
}

// --- setup ------------------------------------------------------------------------------------
// gl4es' own entry point for a GL function: the gl4es_ name (the only one a dylib on iOS exports, where
// the bare name would clash with the OpenGLES framework), else the bare one (Android).
static void *gl4es_sym(void *h, const char *name) {
    char prefixed[64];
    snprintf(prefixed, sizeof prefixed, "gl4es_%s", name);
    void *p = dlsym(h, prefixed);
    return p ? p : dlsym(h, name);
}

int skgfx_init(void *h) {
    if (g.ready) return 1;
    if (atomic_load(&cfg_failed) || !h) return 0;
    g.hooks = dlsym(h, "gl4es_skgfx_hooks");
    g.set_main_fb = (void (*)(GLuint))dlsym(h, "gl4es_skgfx_set_main_framebuffer");
    g.glViewport = (void (*)(GLint, GLint, GLsizei, GLsizei))gl4es_sym(h, "glViewport");
    g.glScissor = (void (*)(GLint, GLint, GLsizei, GLsizei))gl4es_sym(h, "glScissor");
    g.glBlendFunc = (void (*)(GLenum, GLenum))gl4es_sym(h, "glBlendFunc");
    g.glBlendFuncSeparate = (void (*)(GLenum, GLenum, GLenum, GLenum))gl4es_sym(h, "glBlendFuncSeparate");
    if (!g.hooks || !g.set_main_fb || !g.glViewport || !g.glScissor || !g.glBlendFunc || !g.glBlendFuncSeparate) {
        SKLOG("this gl4es has no graphics hooks: pass off");
        atomic_store(&cfg_failed, 1);
        return 0;
    }
    clear_pending_errors();
    const char *ext = (const char *)glGetString(GL_EXTENSIONS);
    const char *ver = (const char *)glGetString(GL_VERSION);
    g.packed_ds = (ext && strstr(ext, "GL_OES_packed_depth_stencil")) || (ver && strstr(ver, "OpenGL ES 3"));
    GLint prev_prog = 0, prev_buf = 0;
    glGetIntegerv(GL_CURRENT_PROGRAM, &prev_prog);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &prev_buf);
    g.prog_cas = link_program(CAS_SRC);
    g.prog_copy = link_program(COPY_SRC);
    int ok = g.prog_cas && g.prog_copy;
    if (ok) {
        g.cas_tex = glGetUniformLocation(g.prog_cas, "uTex");
        g.cas_texel = glGetUniformLocation(g.prog_cas, "uTexel");
        g.cas_sharp = glGetUniformLocation(g.prog_cas, "uSharp");
        g.copy_tex = glGetUniformLocation(g.prog_copy, "uTex");
        glUseProgram(g.prog_cas);
        glUniform1i(g.cas_tex, 0);
        glUseProgram(g.prog_copy);
        glUniform1i(g.copy_tex, 0);
        static const GLfloat quad[8] = { -1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f };
        glGenBuffers(1, &g.vbo);
        glBindBuffer(GL_ARRAY_BUFFER, g.vbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof quad, quad, GL_STATIC_DRAW);
    }
    glBindBuffer(GL_ARRAY_BUFFER, (GLuint)prev_buf);
    glUseProgram((GLuint)prev_prog);
    if (!ok || drain_errors("init")) {
        // Nothing of the half-built pass stays behind: the programs and the buffer go back to GL.
        if (g.prog_cas) glDeleteProgram(g.prog_cas);
        if (g.prog_copy) glDeleteProgram(g.prog_copy);
        if (g.vbo) glDeleteBuffers(1, &g.vbo);
        g.prog_cas = g.prog_copy = g.vbo = 0;
        atomic_store(&cfg_failed, 1);
        return 0;
    }
    g.hooks->ortho = h_ortho;
    g.hooks->draw = h_draw;
    g.hooks->viewport = h_viewport;
    g.hooks->scissor = h_scissor;
    g.hooks->blendfunc = h_blendfunc;
    g.ready = 1;
    SKLOG("ready (packed depth-stencil %d)", g.packed_ds);
    return 1;
}
