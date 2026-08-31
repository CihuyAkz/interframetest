package dev.fallingcloud.interframe.client;

import dev.fallingcloud.interframe.Interframe;
import dev.fallingcloud.interframe.InterframeConfig;
import dev.fallingcloud.interframe.InterframeConfig.Mode;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.ScrollPanel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Interframe's own settings screen, opened from the mod list's "Config" button (registered in
 * {@link Interframe}).
 *
 * <p>The Fabric and NeoForge builds render Interframe's options as a page inside Sodium's video
 * settings, since Sodium ships an official build for both those loaders. Sodium does not ship an
 * official build for (Lex's) Forge, so there is nothing to hook into here — this screen reproduces
 * the same set of options (see {@link InterframeConfig}) with vanilla widgets instead, and writes
 * straight through to {@link InterframeConfig#save()} whenever a value changes, exactly like the
 * Sodium page does on the other loaders.
 */
public class InterframeConfigScreen extends Screen {
    private static final int ROW_HEIGHT = 25;
    private static final int ROW_WIDTH = 310;

    private final Screen parent;
    private final InterframeConfig cfg;
    private ContentPanel panel;

    // Widgets whose enabled state depends on the master switch.
    private final List<net.minecraft.client.gui.components.AbstractWidget> dependentOnEnabled = new ArrayList<>();

    public InterframeConfigScreen(Screen parent) {
        super(Component.translatable("interframe.page.title"));
        this.parent = parent;
        this.cfg = InterframeConfig.get();
    }

    @Override
    protected void init() {
        panel = new ContentPanel(this.minecraft, this.width, this.height - 60, 32, ROW_WIDTH);
        addRenderableWidget(panel);

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());
    }

    @Override
    public void onClose() {
        cfg.save();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        gfx.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
    }

    private void refreshEnabledState() {
        boolean on = cfg.enabled;
        for (var w : dependentOnEnabled) {
            w.active = on;
        }
    }

    /** Scrollable body holding every option row, vanilla-styled (mirrors {@code OptionsList}'s panel). */
    private final class ContentPanel extends ScrollPanel {
        private final int rowWidth;
        private final List<net.minecraft.client.gui.components.AbstractWidget> rows = new ArrayList<>();

        ContentPanel(net.minecraft.client.Minecraft mc, int width, int height, int top, int rowWidth) {
            super(mc, width, height, top);
            this.rowWidth = rowWidth;
            build();
        }

        private void build() {
            int x = this.width / 2 - rowWidth / 2;

            add(boolRow(x, Component.translatable("interframe.option.enabled"),
                    cfg.enabled, v -> {
                        cfg.enabled = v;
                        cfg.save();
                        refreshEnabledState();
                    }));

            add(enumRow(x));

            add(dependent(intRow(x, Component.translatable("interframe.option.generated"),
                    1, 3, cfg.generatedPerReal,
                    v -> String.format("%dx  (%d inserted)", v + 1, v),
                    v -> { cfg.generatedPerReal = v; cfg.save(); })));

            add(dependent(intRow(x, Component.translatable("interframe.option.reproject_strength"),
                    0, 100, cfg.reprojectStrength,
                    v -> v + "%",
                    v -> { cfg.reprojectStrength = v; cfg.save(); })));

            add(dependent(boolRow(x, Component.translatable("interframe.option.translation_warp"),
                    cfg.translationWarp,
                    v -> { cfg.translationWarp = v; cfg.save(); })));

            add(dependent(intRow(x, Component.translatable("interframe.option.look_ahead"),
                    0, 150, cfg.lookAhead,
                    v -> String.format("%.2f frames", v / 100.0),
                    v -> { cfg.lookAhead = v; cfg.save(); })));

            add(dependent(intRow(x, Component.translatable("interframe.option.max_warp"),
                    2, 90, cfg.maxWarpDegrees,
                    v -> v + "°/frame",
                    v -> { cfg.maxWarpDegrees = v; cfg.save(); })));

            add(dependent(intRow(x, Component.translatable("interframe.option.pacing"),
                    0, 100, cfg.pacingStrength,
                    v -> v == 0 ? Component.translatable("interframe.option.pacing.off").getString() : v + "%",
                    v -> { cfg.pacingStrength = v; cfg.save(); })));

            add(boolRow(x, Component.translatable("interframe.option.in_game_only"),
                    cfg.inGameOnly, v -> { cfg.inGameOnly = v; cfg.save(); }));

            add(boolRow(x, Component.translatable("interframe.option.preserve_hud"),
                    cfg.preserveHud, v -> { cfg.preserveHud = v; cfg.save(); }));

            refreshEnabledState();
        }

        private net.minecraft.client.gui.components.AbstractWidget dependent(
                net.minecraft.client.gui.components.AbstractWidget w) {
            dependentOnEnabled.add(w);
            return w;
        }

        private void add(net.minecraft.client.gui.components.AbstractWidget w) {
            rows.add(w);
        }

        private CycleButton<Boolean> boolRow(int x, Component label, boolean initial,
                                              java.util.function.Consumer<Boolean> setter) {
            return CycleButton.onOffBuilder(initial)
                    .create(x, 0, rowWidth, 20, label, (btn, val) -> setter.accept(val));
        }

        private CycleButton<Mode> enumRow(int x) {
            return CycleButton.<Mode>builder(m -> Component.translatable("interframe.mode." + m.name().toLowerCase()))
                    .withValues(Mode.values())
                    .withInitialValue(cfg.mode())
                    .create(x, 0, rowWidth, 20, Component.translatable("interframe.option.mode"),
                            (btn, val) -> {
                                cfg.mode = val;
                                cfg.save();
                            });
        }

        private AbstractSliderButton intRow(int x, Component label, int min, int max, int initial,
                                             java.util.function.IntFunction<String> formatter,
                                             java.util.function.IntConsumer setter) {
            double pct = Mth.clamp((initial - min) / (double) (max - min), 0.0, 1.0);
            return new AbstractSliderButton(x, 0, rowWidth, 20, Component.empty(), pct) {
                {
                    updateMessage();
                }

                @Override
                protected void updateMessage() {
                    int v = min + (int) Math.round(this.value * (max - min));
                    setMessage(Component.literal(label.getString() + ": " + formatter.apply(v)));
                }

                @Override
                protected void applyValue() {
                    int v = min + (int) Math.round(this.value * (max - min));
                    setter.accept(v);
                }
            };
        }

        @Override
        protected int contentHeight() {
            return rows.size() * ROW_HEIGHT + 10;
        }

        @Override
        protected void drawPanel(net.minecraft.client.gui.GuiGraphics gfx, int entryRight, int relativeY,
                                  int tick, int mouseX, int mouseY) {
            int y = relativeY;
            for (var w : rows) {
                w.setY(y);
                w.render(gfx, mouseX, mouseY, 0);
                y += ROW_HEIGHT;
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            for (var w : rows) {
                if (w.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            for (var w : rows) {
                if (w.mouseReleased(mouseX, mouseY, button)) {
                    return true;
                }
            }
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            for (var w : rows) {
                if (w.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
                    return true;
                }
            }
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
    }
}
