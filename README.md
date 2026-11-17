# skgfx

Graphics enhancements for Spiral Knights, shared by the SKonstruct launchers: render scale, contrast-adaptive
sharpening, a sharp HUD layer, an FPS counter and (experimental) frame generation.

> Unofficial fan project. Not affiliated with Grey Havens. Spiral Knights © Grey Havens, LLC.

## Layout

- `mobile/`: `skgfx.c` and `skgfx.h`, the present pass used by the Android and iOS apps (OpenGL ES through gl4es).
  This is the single source for both apps.
- `desktop/`: the Java agent for the desktop launcher. Not added yet.

## License

[MIT](LICENSE). The sharpening shader follows AMD FidelityFX CAS; see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
