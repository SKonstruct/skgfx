// SKapsule's graphics present pass: render the game offscreen, sharpen it (CAS) on the way to the
// window, and optionally keep the HUD in its own full-resolution layer ("Sharp HUD").
//
// The single source for SKapsule (Android) and SKapsule-iOS. Everything runs on
// the game's render thread with its GL context current, except skgfx_set_config(). It talks to gl4es
// through the hook table in gl4es' framebuffers.h, and fails open: any trouble leaves the game
// drawing straight into the window as before.
#ifndef SKGFX_H
#define SKGFX_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Any thread. enabled = draw offscreen at all (scale / sharpening / Sharp HUD wanted); scale is the
// scene's share of the window size (0.25..1); sharpen 0..1; hud = Sharp HUD.
void skgfx_set_config(int enabled, float scale, float sharpen, int hud);

// The size the game should believe its window is, for a native window of nw x nh. With the offscreen
// path off the platform reports its own surface size, so this returns the input unchanged. Any thread.
void skgfx_believed_size(int nw, int nh, int *bw, int *bh);

// Whether the offscreen path is wanted and has not failed. Any thread.
int skgfx_wanted(void);

// Render thread, once, after gl4es is initialised (handle = dlopen of libgl4es). Returns 0 and stays
// inert if the pass cannot be set up.
int skgfx_init(void *gl4es);

// Render thread, every frame, just before the platform's buffer swap. window_fbo is the framebuffer
// the window shows (0 on Android). Returns 1 if the frame was composed onto it.
int skgfx_present(unsigned window_fbo, int nw, int nh);

// Render thread, every frame, right after the platform's buffer swap.
void skgfx_after_swap(unsigned window_fbo, int nw, int nh);

// Frames presented since start (any thread): the FPS counter reads this.
uint64_t skgfx_frames(void);

#ifdef __cplusplus
}
#endif
#endif
