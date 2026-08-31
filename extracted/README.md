# Interframe — Forge 1.20.1 port

This is a Forge 1.20.1 port of the original Fabric build (MC 1.21.1) of Interframe, an AI/GPU frame
generation mod. It also adds OptiFine detection/compatibility handling, which didn't exist in the
Fabric build (OptiFine isn't relevant on Fabric).

## What changed from the Fabric source

| Area | Fabric build | This Forge port |
|---|---|---|
| Build tool | Fabric Loom | ForgeGradle 6 (`net.minecraftforge.gradle`) + SpongePowered Mixin Gradle plugin |
| Target | MC 1.21.1, Fabric Loader ≥0.16.9 | MC 1.20.1, Forge ≥47 |
| Entry point | `ClientModInitializer.onInitializeClient()` | `@Mod("interframe")` constructor + `FMLClientSetupEvent` |
| Metadata | `fabric.mod.json` | `META-INF/mods.toml` |
| Mixin loading | `fabric.mod.json` → `"mixins"` | `interframe.mixins.json` (via `mixin { config ... }` in build.gradle; Forge picks it up from the `MixinConfigs` jar manifest attribute) |
| Config dir | `FabricLoader.getInstance().getConfigDir()` | `net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()` |
| Settings UI | Registered into **Sodium's** config API (`sodium:config_api_user` entrypoint, `InterframeConfigMenu`) | Sodium doesn't exist on Forge, so this is now a small standalone vanilla-widget screen (`gui/InterframeOptionsScreen`), reached via Forge's `ConfigScreenHandler` extension point (the "Config" button next to Interframe in the Mods list) |
| Everything else | `FrameGenerator`, the synthesiser stack (`synth/*`), `gl/*`, `CameraSnapshot`, `FoveaBridge` | **Unchanged.** None of it used Fabric APIs — it's all Mojang/LWJGL classes, so it ported as-is. |

The two mixins (`MixinLevelRenderer`, `MixinWindow`) are logically identical to the Fabric build. Their
injection points (`LevelRenderer.renderLevel` HEAD/RETURN, `Window.updateDisplay` HEAD) are Mojang's own
classes and are the same shape on 1.20.1 as on 1.21.1 for the purposes of this mod's `@Inject`s (no
explicit method descriptor is used, so Mixin resolves the single matching overload automatically —
same as the original). **Still worth a real compile-and-launch test**, since I ported this without a
live Forge/MDK environment to compile against.

## OptiFine support

OptiFine is closed-source, not published to a normal Maven repo, and patches Minecraft's class files
directly rather than acting as a well-behaved mod loader citizen — it has a long history of conflicting
with other rendering mods (mixin-based or otherwise), especially ones that touch the same rendering
internals it does (this is *why* Sodium and OptiFine are mutually exclusive on Fabric).

What this port actually does for OptiFine compatibility:

1. **`compat/OptiFineBridge.java`** — new, reflection-only (never compiled against OptiFine directly).
   Detects whether OptiFine is installed (`net.optifine.Config` presence) and whether an OptiFine
   shaderpack is currently active (`net.optifine.shaders.Shaders.isShaderPackLoaded()`, with a
   fallback method name tried defensively since this isn't a stable public API).
2. **Shaderpack-aware depth gating** — `FrameGenerator.onWorldDepth()` now disables translational
   (parallax) reprojection under an active OptiFine shaderpack too, not just Iris, for the same reason:
   the vanilla depth buffer isn't the shaderpack's, so parallax correction would misplace geometry.
   Rotation-only warp still applies.
3. **Fail-soft mixins** — both `@Inject`s use `require = 0` instead of Mixin's default `require = 1`.
   If OptiFine's bytecode patching on some build ever shifts `renderLevel`/`updateDisplay` enough that
   Mixin can't find the injection point, Forge logs a warning and Interframe just doesn't engage (no
   frame generation) instead of crashing the game at startup.
4. **A logged heads-up** at mod init if OptiFine is detected at all, so users/log-readers know why
   frame generation might be inactive if it silently didn't engage.

**What this does *not* do:** it doesn't make OptiFine's own shader/chunk-rendering pipeline aware of
Interframe, and it can't guarantee every OptiFine build is compatible — OptiFine has no public API or
changelog contract the way Fabric/Forge mods do. Treat "supports OptiFine" here as "detects it, degrades
its own behavior sensibly under it, and won't crash the game if the deep hook doesn't apply" rather than
"guaranteed to double your framerate under every OptiFine + shaderpack combo." If you specifically want
guaranteed compatibility with a Sodium-like renderer on Forge, pair this with **Embeddium** (Sodium's
Forge/NeoForge fork) instead of OptiFine — that's the setup this mod's depth/HUD-preservation logic was
originally designed against.

## Building

```
./gradlew build
```

Before building for real:

- Check https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html for the current
  latest 1.20.1 Forge build and update `forge_version` in `gradle.properties` (I set a known-recent
  value at the time of writing, but I can't fetch the live index from here).
- If you want compile-time type safety against the real OptiFine API instead of pure reflection, see
  the commented-out `compileOnly files('libs/OptiFine_...jar')` line in `build.gradle` — drop a matching
  jar in `libs/` and uncomment it. Not required; `OptiFineBridge` works purely reflectively without it.
- Run `./gradlew runClient` and sanity-check the mixins actually apply (watch the log for Mixin apply
  errors) before shipping, especially with OptiFine installed in the run's `mods`/instance.

## Original Fabric README

See the credits/description above — the mod description, license (MIT) and author are unchanged from
the source you provided; only the loader/target version integration changed.
