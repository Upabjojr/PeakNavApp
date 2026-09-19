package com.peaknav.viewer.widgets;

import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;

/**
 * The waiting slideshow on its own, opened from the menu rather than met while waiting.
 *
 * <p>The same pictures and captions the welcome screen and the loading screen show (see
 * {@link SlideShow}), over a dark backdrop with nothing else on it but the button that closes
 * it. It covers the map and swallows taps, so nothing behind it moves while it is up.
 */
public final class SlideShowOverlay {

    private final Table root;
    private final SlideShow slideShow;

    public SlideShowOverlay(float widgetUnitStep, Label.LabelStyle captionStyle) {
        // Room for the close button and a margin, and nothing else: this screen is the pictures.
        slideShow = new SlideShow(widgetUnitStep, captionStyle, 4f);

        root = new Table();
        root.setFillParent(true);
        root.setBackground(getC().widgetTextures.getUniformDrawable(new Color(0.06f, 0.08f, 0.1f, 0.96f)));
        root.setTouchable(Touchable.enabled);
        root.setVisible(false);
        // Nothing behind the backdrop may be touched while it is up.
        root.addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                return true;
            }
        });

        Button close = getC().widgetTextures.getButtonWithIcon("icons/icon_x.png");
        close.setName("slideshow_close");   // for /widgets, which places the tutorial's markers
        close.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                hide();
            }
        });

        Table topRow = new Table();
        topRow.add().expandX();
        topRow.add(close).width(widgetUnitStep).height(widgetUnitStep)
                .padTop(0.4f * widgetUnitStep).padRight(0.4f * widgetUnitStep);
        root.top();
        root.add(topRow).growX().row();
        root.add(slideShow.getTable()).expand().center().row();
    }

    public Table getTable() {
        return root;
    }

    public boolean isVisible() {
        return root.isVisible();
    }

    public void show() {
        root.setVisible(true);
        root.toFront();
    }

    public void hide() {
        root.setVisible(false);
    }

    /** Advances the pictures while the viewer is open. Render thread, every frame. */
    public void update(float delta) {
        slideShow.update(delta, root.isVisible());
    }

    public void dispose() {
        slideShow.dispose();
    }
}
