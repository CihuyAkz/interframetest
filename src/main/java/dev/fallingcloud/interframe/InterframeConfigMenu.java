package dev.fallingcloud.interframe;

import dev.fallingcloud.interframe.InterframeConfig.Mode;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Adds Interframe's page to Sodium 0.8's video settings — the same menu Reese's Sodium Options re-renders,
 * so the page appears identically there too.
 *
 * <p>On Fabric, Sodium discovers config pages via the {@code sodium:config_api_user} entrypoint declared
 * in {@code fabric.mod.json} (pointing at this class), rather than the {@code ConfigEntryPointForge}
 * annotation the NeoForge build needs. No annotation is required here.
 */
public class InterframeConfigMenu implements ConfigEntryPoint {

    private static final Identifier ENABLED = id("enabled");
    private static final Identifier MODE = id("mode");
    private static final Identifier GENERATED = id("generated");
    private static final Identifier REPROJECT_STRENGTH = id("reproject_strength");
    private static final Identifier TRANSLATION_WARP = id("translation_warp");
    private static final Identifier LOOK_AHEAD = id("look_ahead");
    private static final Identifier MAX_WARP = id("max_warp");
    private static final Identifier PACING = id("pacing");
    private static final Identifier IN_GAME_ONLY = id("in_game_only");
    private static final Identifier PRESERVE_HUD = id("preserve_hud");

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        InterframeConfig cfg = InterframeConfig.get();
        StorageEventHandler save = cfg::save;

        ModOptionsBuilder mod = builder
                .registerModOptions(Interframe.MOD_ID, "Interframe", Interframe.VERSION)
                .setIcon(Identifier.parse("interframe:icon.png"));

        OptionGroupBuilder master = builder.createOptionGroup();
        master.addOption(bool(builder, ENABLED, "interframe.option.enabled", save,
                v -> cfg.enabled = v, () -> cfg.enabled, true));
        master.addOption(modeOption(builder, save, cfg));

        OptionGroupBuilder tuning = builder.createOptionGroup();
        tuning.addOption(slider(builder, GENERATED, "interframe.option.generated", save,
                v -> cfg.generatedPerReal = v, () -> cfg.generatedPerReal, 1,
                new Range(1, 3, 1), v -> Component.literal((v + 1) + "x  (" + v + " inserted)")));
        tuning.addOption(slider(builder, REPROJECT_STRENGTH, "interframe.option.reproject_strength", save,
                v -> cfg.reprojectStrength = v, () -> cfg.reprojectStrength, 100,
                new Range(0, 100, 5), v -> Component.literal(v + "%")));
        tuning.addOption(bool(builder, TRANSLATION_WARP, "interframe.option.translation_warp", save,
                v -> cfg.translationWarp = v, () -> cfg.translationWarp, true));
        tuning.addOption(slider(builder, LOOK_AHEAD, "interframe.option.look_ahead", save,
                v -> cfg.lookAhead = v, () -> cfg.lookAhead, 60,
                new Range(0, 150, 5),
                v -> Component.literal(String.format("%.2f frames", v / 100.0))));
        tuning.addOption(slider(builder, MAX_WARP, "interframe.option.max_warp", save,
                v -> cfg.maxWarpDegrees = v, () -> cfg.maxWarpDegrees, 25,
                new Range(2, 90, 1), v -> Component.literal(v + "°/frame")));
        tuning.addOption(slider(builder, PACING, "interframe.option.pacing", save,
                v -> cfg.pacingStrength = v, () -> cfg.pacingStrength, 90,
                new Range(0, 100, 5),
                v -> v == 0 ? Component.translatable("interframe.option.pacing.off")
                        : Component.literal(v + "%")));

        OptionGroupBuilder advanced = builder.createOptionGroup();
        advanced.addOption(bool(builder, IN_GAME_ONLY, "interframe.option.in_game_only", save,
                v -> cfg.inGameOnly = v, () -> cfg.inGameOnly, true));
        advanced.addOption(bool(builder, PRESERVE_HUD, "interframe.option.preserve_hud", save,
                v -> cfg.preserveHud = v, () -> cfg.preserveHud, true));

        OptionPageBuilder page = builder.createOptionPage();
        page.setName(Component.translatable("interframe.page.title"));
        page.addOptionGroup(master);
        page.addOptionGroup(tuning);
        page.addOptionGroup(advanced);

        mod.addPage(page);
    }

    private EnumOptionBuilder<Mode> modeOption(ConfigBuilder builder, StorageEventHandler save, InterframeConfig cfg) {
        EnumOptionBuilder<Mode> b = builder.createEnumOption(MODE, Mode.class);
        b.setName(Component.translatable("interframe.option.mode"));
        b.setTooltip(Component.translatable("interframe.option.mode.tooltip"));
        b.setElementNameProvider(m -> Component.translatable("interframe.mode." + m.name().toLowerCase()));
        b.setBinding(v -> cfg.mode = v, cfg::mode);
        b.setDefaultValue(Mode.REPROJECT);
        b.setEnabledProvider((ConfigState state) -> state.readBooleanOption(ENABLED), ENABLED);
        b.setStorageHandler(save);
        return b;
    }

    private static BooleanOptionBuilder bool(ConfigBuilder builder, Identifier rl, String key,
                                             StorageEventHandler save,
                                             java.util.function.Consumer<Boolean> setter,
                                             java.util.function.Supplier<Boolean> getter,
                                             boolean def) {
        BooleanOptionBuilder b = builder.createBooleanOption(rl);
        b.setName(Component.translatable(key));
        b.setTooltip(Component.translatable(key + ".tooltip"));
        b.setBinding(setter, getter);
        b.setStorageHandler(save);
        b.setDefaultValue(def);
        return b;
    }

    private static IntegerOptionBuilder slider(ConfigBuilder builder, Identifier rl, String key,
                                               StorageEventHandler save,
                                               java.util.function.Consumer<Integer> setter,
                                               java.util.function.Supplier<Integer> getter,
                                               int def, Range range, ControlValueFormatter formatter) {
        IntegerOptionBuilder b = builder.createIntegerOption(rl);
        b.setName(Component.translatable(key));
        b.setTooltip(Component.translatable(key + ".tooltip"));
        b.setBinding(setter, getter);
        b.setRange(range);
        b.setValueFormatter(formatter);
        // Grey the tuning sliders out while the mod is disabled (same UX as Foveate).
        b.setEnabledProvider((ConfigState state) -> state.readBooleanOption(ENABLED), ENABLED);
        b.setStorageHandler(save);
        b.setDefaultValue(def);
        return b;
    }

    private static Identifier id(String path) {
        return Identifier.parse(Interframe.MOD_ID + ":" + path);
    }
}
