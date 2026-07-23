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

/**
 * A full-screen overlay that lists the desktop keyboard controls. It is shown from the
 * "?" button (and the "?" key, which activates that same button); a click anywhere, or
 * pressing "?" again, dismisses it. Purely a stage actor, so it needs no native code.
 */
public class KeyboardHelpOverlay {

    private final Table root;
    private final float widgetUnitStep;
    private final Label.LabelStyle keyStyle;
    private final Label.LabelStyle descStyle;

    public KeyboardHelpOverlay(float widgetUnitStep) {
        this.widgetUnitStep = widgetUnitStep;

        // One shared white font (it carries the arrow glyphs); per-label scaling keeps
        // the panel compact regardless of the font's on-screen size.
        BitmapFont font = getC().styleSingleton.getBitmapFont();
        Label.LabelStyle titleStyle = new Label.LabelStyle(font, Color.WHITE);
        keyStyle = new Label.LabelStyle(font, Color.WHITE);
        descStyle = new Label.LabelStyle(font, new Color(0.82f, 0.82f, 0.82f, 1f));

        Table panel = new Table();
        panel.setBackground(getC().widgetTextures.getUniformDrawable(new Color(0f, 0f, 0f, 0.85f)));
        panel.pad(widgetUnitStep * 0.6f);

        Label title = new Label(s("Keyboard_controls"), titleStyle);
        title.setFontScale(0.9f);
        panel.add(title).colspan(2).center().padBottom(widgetUnitStep * 0.4f).row();

        addRow(panel, "← → ↑ ↓", s("Aim_view"));
        addRow(panel, "Page Up / Page Down", s("Change_altitude"));
        addRow(panel, "+ / -", s("Zoom_in_out"));
        addRow(panel, "Shift", s("Fine_adjust"));

        Label hint = new Label(s("Close_help_hint"), descStyle);
        hint.setFontScale(0.6f);
        panel.add(hint).colspan(2).center().padTop(widgetUnitStep * 0.4f).row();

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

    private void addRow(Table panel, String keys, String desc) {
        Label keyLabel = new Label(keys, keyStyle);
        keyLabel.setFontScale(0.72f);
        Label descLabel = new Label(desc, descStyle);
        descLabel.setFontScale(0.72f);
        panel.add(keyLabel).right().padRight(widgetUnitStep * 0.6f).padBottom(widgetUnitStep * 0.12f);
        panel.add(descLabel).left().padBottom(widgetUnitStep * 0.12f).row();
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
