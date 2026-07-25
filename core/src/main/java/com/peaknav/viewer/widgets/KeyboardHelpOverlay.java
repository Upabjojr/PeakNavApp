package com.peaknav.viewer.widgets;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.s;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;

import java.util.ArrayList;
import java.util.List;

/**
 * A centred overlay listing the desktop keyboard controls. It is raised when the user
 * presses a key that has no binding, and dismissed with Esc or a click. Purely a stage
 * actor, so it needs no native code.
 *
 * <p>Text is sized as a fraction of the stage height every time it is shown, so the
 * panel stays a modest, consistent size on any window instead of being pinned to the
 * size the app happened to start at.
 */
public class KeyboardHelpOverlay {

    /** Row text height as a fraction of the stage height; the panel grows from this. */
    private static final float ROW_HEIGHT_FRACTION = 1f / 26f;

    private final Table root;
    private final Table panel;
    private final BitmapFont font;
    private final Label title;
    private final Label hint;
    private final List<Label> bodyLabels = new ArrayList<>();

    public KeyboardHelpOverlay(float widgetUnitStep) {
        // One shared white font (it carries the arrow glyphs); relayout() rescales the
        // labels, so the font's own generated size does not matter.
        font = getC().styleSingleton.getBitmapFont();
        Label.LabelStyle titleStyle = new Label.LabelStyle(font, Color.WHITE);
        Label.LabelStyle keyStyle = new Label.LabelStyle(font, Color.WHITE);
        Label.LabelStyle descStyle = new Label.LabelStyle(font, new Color(0.82f, 0.82f, 0.82f, 1f));

        panel = new Table();
        panel.setBackground(getC().widgetTextures.getUniformDrawable(new Color(0f, 0f, 0f, 0.85f)));
        panel.pad(widgetUnitStep * 0.5f);
        panel.defaults().padBottom(widgetUnitStep * 0.1f);

        title = new Label(s("Keyboard_controls"), titleStyle);
        panel.add(title).colspan(2).center().padBottom(widgetUnitStep * 0.35f).row();

        addRow(panel, "← → ↑ ↓   W A S D", s("Aim_view"), keyStyle, descStyle, widgetUnitStep);
        addRow(panel, "Page Up / Page Down", s("Change_altitude"), keyStyle, descStyle, widgetUnitStep);
        addRow(panel, "+ / -", s("Zoom_in_out"), keyStyle, descStyle, widgetUnitStep);
        addRow(panel, "Shift", s("Fine_adjust"), keyStyle, descStyle, widgetUnitStep);

        hint = new Label(s("Close_help_hint"), descStyle);
        panel.add(hint).colspan(2).center().padTop(widgetUnitStep * 0.35f).row();
        bodyLabels.add(hint);

        root = new Table();
        root.setFillParent(true);
        root.center();
        // A dim scrim over the whole screen, and — being touchable — it swallows clicks
        // so a tap to dismiss never leaks through to the map buttons underneath.
        root.setBackground(getC().widgetTextures.getUniformDrawable(new Color(0f, 0f, 0f, 0.45f)));
        root.setTouchable(Touchable.enabled);
        root.setVisible(false);
        root.add(panel);

        root.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                hide();
            }
        });
    }

    private void addRow(Table panel, String keys, String desc, Label.LabelStyle keyStyle,
                        Label.LabelStyle descStyle, float widgetUnitStep) {
        Label keyLabel = new Label(keys, keyStyle);
        Label descLabel = new Label(desc, descStyle);
        panel.add(keyLabel).right().padRight(widgetUnitStep * 0.55f);
        panel.add(descLabel).left().row();
        bodyLabels.add(keyLabel);
        bodyLabels.add(descLabel);
    }

    /** Rescales the text to the current stage height so the panel is a modest fraction of it. */
    private void relayout() {
        if (root.getStage() == null) {
            return;
        }
        float rowHeight = root.getStage().getHeight() * ROW_HEIGHT_FRACTION;
        float scale = rowHeight / font.getLineHeight();
        // Label.setFontScale is absolute — it replaces the font's own data scale — but the shared
        // font is baked supersampled and carries a base scale (< 1) that maps the high-res atlas back
        // to display size. Fold that base in so these labels stay the intended on-screen size.
        float base = font.getScaleY();
        title.setFontScale(scale * 1.35f * base);
        hint.setFontScale(scale * 0.78f * base);
        for (Label label : bodyLabels) {
            if (label != hint) {
                label.setFontScale(scale * base);
            }
        }
        panel.invalidateHierarchy();
    }

    public Table getRoot() {
        return root;
    }

    public boolean isVisible() {
        return root.isVisible();
    }

    /** Whether a hardware keyboard is present, so the controls are worth showing. */
    public static boolean isKeyboardAvailable() {
        return Gdx.input.isPeripheralAvailable(Input.Peripheral.HardwareKeyboard);
    }

    /**
     * Reveals the overlay — but only when a hardware keyboard is actually attached, so
     * touch-only devices (most phones) never see keyboard instructions. This is the
     * single authoritative gate; every caller ultimately goes through here.
     */
    public void show() {
        if (!isKeyboardAvailable()) {
            return;
        }
        relayout();
        root.toFront();
        root.setVisible(true);
    }

    public void hide() {
        root.setVisible(false);
    }

    public void toggle() {
        if (isVisible()) {
            hide();
        } else {
            show();
        }
    }
}
