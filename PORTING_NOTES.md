# Porting notes: 1.21.1 → 1.21.11

This port was done **without a working Fabric/Minecraft toolchain** (the environment that produced it has
no network access to download Minecraft, mappings, Fabric Loader, Sodium, or run Gradle/Loom). Every
change below is backed by research (Fabric's own 1.21.11 blog post, NeoForge's version-migration primers,
Mojang's official mapping files, and current Modrinth listings), but **none of it has been compiled or run
against the real game.** Treat this as a well-researched first draft, not a finished port. Run
`./gradlew build` yourself and work through whatever it flags — the items below are the most likely spots.

## Update: first real `./gradlew build` — 2 compile errors, both fixed

A real build (see attached CI log) surfaced exactly the two spots flagged below as unverified guesses,
and no others:

- `Window#getHandle()` doesn't exist — the earlier guess that `getWindow()` was renamed to `getHandle()`
  was wrong. Official Mojang mappings never renamed it; `getHandle()` is only the *Yarn* mapping name.
  Fixed in `FrameGenerator.java` by reverting to `.getWindow()`.
- `RenderSystem.getProjectionMatrix()` is indeed gone, as suspected, and there's no public CPU-side
  read-back of the new GPU-side `RawProjectionMatrix` (its only public member is a write-only `set(...)`).
  Fixed in `MixinLevelRenderer.java` by rebuilding an equivalent matrix with the public
  `GameRenderer#getBasicProjectionMatrix(float fovDegrees)`, using the base `Options#fov()` setting for
  the angle. This is a *reconstruction*, not a read of the exact live value `GameRenderer` uses internally
  (that computation, `GameRenderer#getFov`, is private) — see the javadoc on `MixinLevelRenderer` for the
  precise trade-off (FOV tangents can lag ~1 tick during sprint/zoom/nausea transitions only).

Both fixes are backed by Fabric's published yarn mapping docs for 1.21.11 (`GameRenderer` and `Window`
class listings) rather than guesswork. Everything else in this document reflects the original,
not-yet-compiled port and is left as-is below.

## What's confirmed and changed

- **`minecraft_version` → `1.21.11`**, `loom_version` → `1.14-SNAPSHOT` (Fabric's documented recommendation
  as of Dec 2025 — check for anything newer since; 1.21.11 is Fabric's last *obfuscated* target and still
  uses `loom.officialMojangMappings()`, so the mappings setup itself didn't need to change).
- **`fabric_loader_version` → `0.18.1`**, **`fabric_api_version` → `0.141.1+1.21.11`** — latest stable
  listed on Modrinth as of this port; check Modrinth for anything released since.
- **`sodium_version` → `mc1.21.11-0.8.13-fabric`** — Sodium stayed on the `0.8.x` line all the way from
  1.21.1 through 1.21.11, so this is a low-risk bump. Iris `1.10.x` tracks it (any recent `1.10.x` build
  should do; not pinned in this project's `build.gradle` since it's `modLocalRuntime`-optional).
- **`RenderSystem.flipFrame(long window)` → `RenderSystem.flipFrame(long window, TracyFrameCapturer
  capturer)`** (this happened around 1.21.2, for the Tracy profiler integration). Fixed both call sites in
  `FrameGenerator` to pass `null`.
- **`RenderTarget` no longer exposes a raw GL framebuffer handle.** This is the big one. As of ~1.21.5:
  - `frameBufferId` (a plain `int` field) was **removed**.
  - `colorTextureId`/`getColorTextureId()` → `colorTexture`/`getColorTexture()`, now returning a
    `GpuTexture` (or a `GpuTextureView` wrapping one — see below), not a raw int.
  - Same for `depthBufferId`/`getDepthTextureId()` → `getDepthTexture()`.
  - `checkStatus`, `bindWrite`, `unbindWrite`, `setClearColor`, `clear`, `unbindRead` were all removed;
    `blitToScreen` no longer takes parameters.
  - This mod's whole capture pipeline (`onWorldDepth`, `onWorldColor`, `onPresent`) bound
    `target.frameBufferId` directly as `GL_READ_FRAMEBUFFER` for `glCopyTexSubImage2D`/`glBlitFramebuffer`.
    That line no longer compiles.

  **Fix applied:** a new class, `dev.fallingcloud.interframe.gl.GpuInterop`, resolves a usable framebuffer
  *reflectively* instead of hard-coding one exact method chain, because the precise shape (does
  `getColorTexture()` return a `GpuTexture` or a `GpuTextureView`? is `.glId()` public?) could not be
  checked against the real jar. It tries, in order: (1) a `getFbo()` no-arg method on whatever
  `getColorTexture()`/`getDepthTexture()` returns — 1.21.11 added exactly this convenience on
  `GlTextureView` per NeoForge's 1.21.10→1.21.11 primer ("`GlTextureView#getFbo` — Gets the framebuffer
  object of a texture, using the cache if present"), which would be the clean, intended path; (2) unwrap
  `.texture()` if the first object was a view, then pull a raw GL texture name off it via `glId()` /
  `getId()` / `getTextureId()` (official Mojang mappings for `com.mojang.blaze3d.opengl.GlTexture` do show
  a package method `int glId()`), and wrap that raw id in a small FBO Interframe manages itself; (3) a
  legacy fallback to the old `int` fields, in case this is ever backported. If every path fails, capture is
  disabled for that frame/session (logged once) rather than crashing.

  **This is the single most important thing to verify by hand.** If `./gradlew build` fails in
  `GpuInterop`, or if the mod loads but frame generation silently never engages, add a log line dumping
  `target.getColorTexture().getClass()` and its declared methods, and tighten `GpuInterop` to call the real
  one directly instead of going through reflection for the hot path (reflection overhead here runs twice
  per frame — fine for correctness, worth tightening once you know the real shape, per-frame allocation
  aside the lookups are cheap but not free).

- **`Window.updateDisplay()` mixin target** — no evidence found that this method was renamed or removed
  through 1.21.11, so left as-is. Because the `@Inject` targets it by bare name (no descriptor), it will
  still bind even if its parameter list changed; it will only break if the method itself was renamed.

- **`LevelRenderer.renderLevel` mixin target** — same reasoning: Minecraft's rendering pipeline had a
  substantial rewrite starting at 1.21.6 (extraction/drawing split, frame-graph-based main pass —
  `addMainPass(FrameGraphBuilder, ...)`), and `renderLevel`'s parameter list changed at least once in that
  window, but the method itself is still called `renderLevel` on `LevelRenderer` as of 1.21.10 (confirmed
  via NeoForge's `RenderLevelStageEvent` javadoc, which still references
  `LevelRenderer.renderLevel(...)` at that version). Left both `@Inject`s targeting it by bare name for the
  same reason as above. `RenderSystem.getProjectionMatrix()` and `Camera#rotation()` are assumed unchanged
  — no evidence found otherwise, but not independently confirmed either.

## What almost certainly did NOT need changes

- **`ShaderProgram.java` and `GlGuard.java`** — pure raw-LWJGL-OpenGL code with zero Mojang API surface.
  Fabric's own December 2025 announcement confirms 1.21.11 (and its immediate successor, `26.1`) are still
  **OpenGL-only**; the option to switch to a Vulkan backend doesn't land until `26.2`, and *that's* the
  version where "raw OpenGL calls rather than going through the Blaze3D API...need to migrate." For
  1.21.11 specifically, raw GL state save/restore and a hand-rolled fullscreen-triangle shader program
  should keep working exactly as before. (Worth knowing for the future: if you ever chase a `26.2`+ port,
  this is the part that would need a real rewrite against Blaze3D's `RenderPass`/`GpuTextureView`
  abstraction — not now.)
- **Sodium config integration (`InterframeConfigMenu.java`)** — uses Sodium's dedicated, stable
  `net.caffeinemc.mods.sodium.api.config` package, which exists specifically so third-party config pages
  don't break across Sodium point releases. Sodium stayed on the `0.8.x` line the entire way from 1.21.1 to
  1.21.11, so this is low-risk, but it's still worth a build/run check since it wasn't independently
  verified.
- **`FoveaBridge.java`** — already fully reflective (per its own design, to stay optional/soft-dependency),
  so it degrades the same way regardless of API drift.

## Not investigated at all (lower priority, but flag if you hit issues)

- The optional ONNX Runtime backend (`OnnxSynthesizer.java`) — version pin left at `1.18.0`; this is
  independent of the Minecraft version and wasn't re-checked.
- Whether Fabric API's newly-reintroduced `WorldRenderEvents` (back for 1.21.10+, after being removed for
  1.21.9) would now be a cleaner, more compatibility-friendly hook than the raw `LevelRenderer.renderLevel`
  mixin — Fabric's own docs recommend the events specifically "to avoid compatibility problems with
  3rd-party renderer implementations" (i.e. Sodium/Iris). The mixin approach should still work, but
  migrating `MixinLevelRenderer`'s two injection points to `WorldRenderEvents.START` / an appropriate
  "after world, before hand" event would be a good follow-up once you have a working build to test against
  — it's more likely to stay compatible across *future* Minecraft updates than raw mixins are.
- Whether `Camera`'s orientation accessor is still named `rotation()` and still returns the same
  `Quaternionf`-compatible type used in `MixinLevelRenderer`.

## Suggested verification order

1. `./gradlew build` and fix whatever doesn't compile — `GpuInterop`/`FrameGenerator` first.
2. Launch with `./gradlew runClient`, join a world with frame generation enabled, and check the log for
   `[Interframe]` warnings — especially the "Could not resolve the main render target's GL framebuffer"
   line from `GpuInterop`, which means the reflective lookup needs a real method name added/fixed.
3. If depth/world-color capture logs the driver-rejected-copy warnings that already existed in the
   original code, that's more likely a genuine attachment/format mismatch in `buildSingleAttachmentFbo`
   than a version issue — sanity-check the depth texture's internal format still matches what Minecraft's
   own depth attachment uses.
4. Only after all of the above works, do a look at replacing the `LevelRenderer.renderLevel` mixin with
   `WorldRenderEvents` per the note above, if you want a more future-proof hook.
