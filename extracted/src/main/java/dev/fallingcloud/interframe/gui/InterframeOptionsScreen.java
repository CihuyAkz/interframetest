package dev.fallingcloud.interframe.gui;

import dev.fallingcloud.interframe.Interframe;
import dev.fallingcloud.interframe.InterframeConfig;
import dev.fallingcloud.interframe.InterframeConfig.Mode;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

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
 *
 * <p>The row list is scrollable: rows are laid out on a virtual (unscrolled) Y axis, and
 * {@link #layoutRows()} maps that virtual position onto the screen using {@link #scrollOffset}, hiding
 * and disabling (not just visually clipping) any row that would land outside the viewport so a scrolled-
 * away row can never still eat a click. The "Done" button is pinned below the viewport so it's always
 * reachable regardless of scroll position.
 */
public class InterframeOptionsScreen extends Screen {

    private final Screen parent;
    private final InterframeConfig cfg = InterframeConfig.get();

    private static final int ROW_HEIGHT = 22;
    private static final int WIDGET_WIDTH = 300;
    /** Space reserved above the viewport for the title, and below it for the pinned Done button. */
    private static final int TOP_MARGIN = 28;
    private static final int BOTTOM_RESERVED = 30;
    private static final int SCROLLBAR_WIDTH = 4;

    /** One scrollable row: the widget plus its position on the virtual (unscrolled) content axis. */
    private record Row(AbstractWidget widget, int virtualY, int height) {
    }

    private final List<Row> rows = new ArrayList<>();
    private int contentHeight;
    private int scrollOffset;
    private int viewportTop;
    private int viewportBottom;
    private Button doneButton;

    public InterframeOptionsScreen(Screen parent) {
        super(Component.translatable("interframe.page.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows.clear();
        scrollOffset = 0;

        int x = this.width / 2 - WIDGET_WIDTH / 2;
        int y = 0; // virtual (unscrolled) content Y — real screen Y is computed in layoutRows()

        y = addRow(x, y, new Checkbox(0, 0, WIDGET_WIDTH, ROW_HEIGHT - 2,
                Component.translatable("interframe.option.enabled"), cfg.enabled) {
            @Override
            public void onPress() {
                super.onPress();
                cfg.enabled = this.selected();
                cfg.save();
            }
        });

        y = addModeCycleButton(x, y);

        y = addIntSlider(x, y, "interframe.option.generated", 1, 3,
                cfg.generatedPerReal, v -> cfg.generatedPerReal = v,
                v -> (v + 1) + "x  (" + v + " inserted)");

        y = addIntSlider(x, y, "interframe.option.reproject_strength", 0, 100,
                cfg.reprojectStrength, v -> cfg.reprojectStrength = v,
                v -> v + "%");

        y = addRow(x, y, new Checkbox(0, 0, WIDGET_WIDTH, ROW_HEIGHT - 2,
                Component.translatable("interframe.option.translation_warp"), cfg.translationWarp) {
            @Override
            public void onPress() {
                super.onPress();
                cfg.translationWarp = this.selected();
                cfg.save();
            }
        });

        y = addIntSlider(x, y, "interframe.option.look_ahead", 0, 150,
                cfg.lookAhead, v -> cfg.lookAhead = v,
                v -> String.format("%.2f frames", v / 100.0));

        y = addIntSlider(x, y, "interframe.option.max_warp", 2, 90,
                cfg.maxWarpDegrees, v -> cfg.maxWarpDegrees = v,
                v -> v + "\u00b0/frame");

        y = addIntSlider(x, y, "interframe.option.pacing", 0, 100,
                cfg.pacingStrength, v -> cfg.pacingStrength = v,
                v -> v == 0 ? Component.translatable("interframe.option.pacing.off").getString() : v + "%");

        y = addRow(x, y, new Checkbox(0, 0, WIDGET_WIDTH, ROW_HEIGHT - 2,
                Component.translatable("interframe.option.in_game_only"), cfg.inGameOnly) {
            @Override
            public void onPress() {
                super.onPress();
                cfg.inGameOnly = this.selected();
                cfg.save();
            }
        });

        y = addRow(x, y, new Checkbox(0, 0, WIDGET_WIDTH, ROW_HEIGHT - 2,
                Component.translatable("interframe.option.preserve_hud"), cfg.preserveHud) {
            @Override
            public void onPress() {
                super.onPress();
                cfg.preserveHud = this.selected();
                cfg.save();
            }
        });

        contentHeight = y;

        // Pinned outside the scroll viewport, always visible and always clickable.
        doneButton = Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(x, this.height - BOTTOM_RESERVED + 6, WIDGET_WIDTH, 20).build();
        this.addRenderableWidget(doneButton);

        viewportTop = TOP_MARGIN;
        viewportBottom = this.height - BOTTOM_RESERVED;
        layoutRows();
    }

    /** Registers a row at virtual Y {@code y} and returns the next free virtual Y. */
    private int addRow(int x, int y, AbstractWidget widget) {
        widget.setX(x);
        rows.add(new Row(widget, y, ROW_HEIGHT));
        this.addRenderableWidget(widget);
        return y + ROW_HEIGHT;
    }

    private int addModeCycleButton(int x, int y) {
        Button b = Button.builder(modeLabel(cfg.mode()), btn -> {
            Mode[] values = Mode.values();
            int next = (cfg.mode().ordinal() + 1) % values.length;
            cfg.mode = values[next];
            cfg.save();
            btn.setMessage(modeLabel(cfg.mode()));
        }).bounds(0, 0, WIDGET_WIDTH, 20).build();
        return addRow(x, y, b);
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
        AbstractSliderButton slider = new AbstractSliderButton(0, 0, WIDGET_WIDTH, ROW_HEIGHT - 2,
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
        };
        return addRow(x, y, slider);
    }

    /**
     * Maps every row's virtual Y onto the screen using {@link #scrollOffset}. A row that would land even
     * partially outside the viewport is pushed far off-screen AND disabled, so it can neither render nor
     * receive clicks/drags while scrolled away — clipping alone isn't enough since hit-testing ignores
     * scissor rectangles.
     */
    private void layoutRows() {
        int maxScroll = Math.max(0, contentHeight - (viewportBottom - viewportTop));
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        for (Row row : rows) {
            int screenY = viewportTop + row.virtualY() - scrollOffset;
            boolean visible = screenY >= viewportTop && screenY + row.height() <= viewportBottom;
            if (visible) {
                row.widget().setY(screenY);
                row.widget().visible = true;
                row.widget().active = true;
            } else {
                row.widget().setY(this.height + 10_000); // clear of the real screen either way
                row.widget().visible = false;
                row.widget().active = false;
            }
        }
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (viewportBottom - viewportTop));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (maxScroll() <= 0) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        scrollOffset -= (int) Math.round(delta * (ROW_HEIGHT * 0.75));
        layoutRows();
        return true;
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        // Minimal scrollbar so it's obvious there's more content, not just visual polish.
        int max = maxScroll();
        if (max > 0) {
            int trackX = this.width / 2 + WIDGET_WIDTH / 2 + 6;
            int viewH = viewportBottom - viewportTop;
            int thumbH = Math.max(12, viewH * viewH / contentHeight);
            int thumbY = viewportTop + (int) ((viewH - thumbH) * (scrollOffset / (float) max));
            graphics.fill(trackX, viewportTop, trackX + SCROLLBAR_WIDTH, viewportBottom, 0x40FFFFFF);
            graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH, 0xC0FFFFFF);
        }
    }

    @Override
    public void onClose() {
        cfg.save();
        this.minecraft.setScreen(parent);
    }
}
