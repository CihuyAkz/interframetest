package dev.fallingcloud.interframe.gui;

import dev.fallingcloud.interframe.Interframe;
import dev.fallingcloud.interframe.InterframeConfig;
import dev.fallingcloud.interframe.InterframeConfig.Mode;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Interframe's settings screen for the Forge port.
 *
 * <p>The Fabric build registered its options page through Sodium's {@code ConfigEntryPoint} API
 * ({@code InterframeConfigMenu}, {@code sodium:config_api_user} entrypoint), so it appeared inside
 * Sodium's own video-settings menu. Sodium itself doesn't exist on Forge — the closest equivalent is
 * Embeddium, whose config-menu API differs and isn't guaranteed present — so rather than hard-depend on
 * a specific Forge Sodium fork, this port ships its own small vanilla-widget screen instead, reached via
 * Forge's standard mod-config-screen hook (the "Config" button next to Interframe in the Mods list; see
 * the registration in {@link Interframe}).
 *
 * <p>Every setting here is a 1:1 port of the same field in {@link InterframeConfig} that the Sodium menu
 * exposed — same defaults, same ranges — just laid out as plain vanilla widgets instead of Sodium's
 * option-page components. Saves immediately on every change (matches the old behaviour, where each
 * option's {@code StorageEventHandler} saved on interaction).
 */
public class InterframeOptionsScreen extends Screen {

    private final Screen parent;
    private final InterframeConfig cfg = InterframeConfig.get();

    private static final int ROW_HEIGHT = 22;
    private static final int WIDGET_WIDTH = 300;

    public InterframeOptionsScreen(Screen parent) {
        super(Component.translatable("interframe.page.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - WIDGET_WIDTH / 2;
        int y = 32;

        addRow(new Checkbox(x, y, WIDGET_WIDTH, ROW_HEIGHT - 2,
                Component.translatable("interframe.option.enabled"), cfg.enabled) {
            @Override
            public void onPress() {
                super.onPress();
                cfg.enabled = this.selected();
                cfg.save();
            }
        });
        y += ROW_HEIGHT;

        y = addModeCycleButton(x, y);

        y = addIntSlider(x, y, "interframe.option.generated", 1, 3,
                cfg.generatedPerReal, v -> cfg.generatedPerReal = v,
                v -> (v + 1) + "x  (" + v + " inserted)");

        y = addIntSlider(x, y, "interframe.option.reproject_strength", 0, 100,
                cfg.reprojectStrength, v -> cfg.reprojectStrength = v,
                v -> v + "%");

        addRow(new Checkbox(x, y, WIDGET_WIDTH, ROW_HEIGHT - 2,
                Component.translatable("interframe.option.translation_warp"), cfg.translationWarp) {
            @Override
            public void onPress() {
                super.onPress();
                cfg.translationWarp = this.selected();
                cfg.save();
            }
        });
        y += ROW_HEIGHT;

        y = addIntSlider(x, y, "interframe.option.look_ahead", 0, 150,
                cfg.lookAhead, v -> cfg.lookAhead = v,
                v -> String.format("%.2f frames", v / 100.0));

        y = addIntSlider(x, y, "interframe.option.max_warp", 2, 90,
                cfg.maxWarpDegrees, v -> cfg.maxWarpDegrees = v,
                v -> v + "°/frame");

        y = addIntSlider(x, y, "interframe.option.pacing", 0, 100,
                cfg.pacingStrength, v -> cfg.pacingStrength = v,
                v -> v == 0 ? Component.translatable("interframe.option.pacing.off").getString() : v + "%");

        addRow(new Checkbox(x, y, WIDGET_WIDTH, ROW_HEIGHT - 2,
                Component.translatable("interframe.option.in_game_only"), cfg.inGameOnly) {
            @Override
            public void onPress() {
                super.onPress();
                cfg.inGameOnly = this.selected();
                cfg.save();
            }
        });
        y += ROW_HEIGHT;

        addRow(new Checkbox(x, y, WIDGET_WIDTH, ROW_HEIGHT - 2,
                Component.translatable("interframe.option.preserve_hud"), cfg.preserveHud) {
            @Override
            public void onPress() {
                super.onPress();
                cfg.preserveHud = this.selected();
                cfg.save();
            }
        });
        y += ROW_HEIGHT + 8;

        addRow(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(x, y, WIDGET_WIDTH, 20).build());
    }

    private int addModeCycleButton(int x, int y) {
        addRow(Button.builder(modeLabel(cfg.mode()), b -> {
            Mode[] values = Mode.values();
            int next = (cfg.mode().ordinal() + 1) % values.length;
            cfg.mode = values[next];
            cfg.save();
            b.setMessage(modeLabel(cfg.mode()));
        }).bounds(x, y, WIDGET_WIDTH, 20).build());
        return y + ROW_HEIGHT;
    }

    private static MutableComponent modeLabel(Mode mode) {
        return Component.translatable("interframe.option.mode").append(": ")
                .append(Component.translatable("interframe.mode." + mode.name().toLowerCase()));
    }

    private interface IntFormatter {
        String format(int value);
    }

    private int addIntSlider(int x, int y, String key, int min, int max, int initial,
                              java.util.function.IntConsumer setter, IntFormatter formatter) {
        addRow(new AbstractSliderButton(x, y, WIDGET_WIDTH, ROW_HEIGHT - 2,
                Component.empty(), (initial - min) / (double) (max - min)) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                int value = valueToInt();
                setMessage(Component.translatable(key).append(": " + formatter.format(value)));
            }

            @Override
            protected void applyValue() {
                setter.accept(valueToInt());
                cfg.save();
            }

            private int valueToInt() {
                return (int) Math.round(min + this.value * (max - min));
            }
        });
        return y + ROW_HEIGHT;
    }

    private void addRow(net.minecraft.client.gui.components.events.GuiEventListener widget) {
        // addRenderableWidget requires the concrete AbstractWidget type; every call site above passes one.
        this.addRenderableWidget((net.minecraft.client.gui.components.AbstractWidget) widget);
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        cfg.save();
        this.minecraft.setScreen(parent);
    }
}
