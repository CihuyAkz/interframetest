# Interframe (Forge port)

Port of Interframe — AI frame generation for Minecraft — to **(Lex's) Minecraft Forge**, targeting
**1.21.1 / Forge 52.1.0**. This is the *original* Forge, not NeoForge.

## What changed vs. the Fabric/NeoForge builds

Almost the entire mod (camera capture mixins, the GL reprojection/blend pipeline, the shader helper,
the optional ONNX/RIFE backend) is written directly against vanilla Minecraft + LWJGL + Mixin, so it
carried over completely unchanged. Only the loader-integration seams changed:

| File | Change |
|---|---|
| `Interframe.java` | `ClientModInitializer` -> `@Mod` class; registers a `ConfigScreenHandler` extension point instead. |
| `InterframeConfig.java` | Config dir now comes from `FMLPaths.CONFIGDIR` instead of `FabricLoader`. |
| `synth/OnnxSynthesizer.java` | Same change, for the `model.onnx` path. |
| `InterframeConfigMenu.java` (Sodium page) | **Removed.** Sodium does not ship an official Forge build, so there's nothing to register a page into. Replaced with `client/InterframeConfigScreen.java`, a small native settings screen (vanilla `CycleButton`/slider widgets) reachable from the mod list's **Config** button. It exposes the exact same options as the Sodium page, saving to the same `config/interframe.json`. |
| `fabric.mod.json` -> `META-INF/mods.toml` | Mod metadata + mixin config declared Forge-style. |
| `build.gradle` | Fabric Loom -> ForgeGradle 6 + the SpongePowered Mixin Gradle plugin (for refmap generation/annotation processing). |

Mixins are unchanged (`MixinWindow`, `MixinLevelRenderer`) — Forge uses the same SpongePowered Mixin
under the hood, wired up via the `[[mixins]]` table in `mods.toml`.

## Building

```
./gradlew build
```

The jar lands in `build/libs/`. This container has no network access, so the Gradle wrapper JAR/Forge
userdev/mixin artifacts could **not** be downloaded or compile-tested here — run the build yourself and
fix up anything Gradle flags (most likely spots: the exact Forge/mixin-plugin version pins in
`gradle.properties`/`build.gradle`, and any vanilla GUI API that shifted slightly from what's coded in
`InterframeConfigScreen`, e.g. `ScrollPanel`'s constructor signature).

You'll also need the Gradle wrapper itself (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) — copy those
three from any current Forge MDK (https://files.minecraftforge.net, 1.21.1 -> Mdk) into this project
root, since they weren't part of the uploaded source and couldn't be fetched here either.

## Notes

- Iris compatibility, the RIFE/ONNX optional backend, and all tuning options behave identically to the
  other loaders — none of that logic was touched.
- Sodium integration is gone by necessity (no Forge Sodium build to hook into). If Forge later gets an
  unofficial Sodium port that exposes the same `net.caffeinemc.mods.sodium.api.config` API, the old
  `InterframeConfigMenu.java` from the Fabric/NeoForge builds can be dropped back in as an *additional*
  page alongside the native screen — it doesn't need to be either/or.
