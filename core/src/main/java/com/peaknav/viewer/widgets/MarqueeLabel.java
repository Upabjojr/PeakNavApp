package com.peaknav.viewer.widgets;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Action;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.utils.Align;

/**
 * A button's caption that fits the room it is given: when the text is longer than its cell, it
 * stays still for a moment, then slides slowly left until its end shows, rests there, jumps back
 * to the start and does it again. It is drawn clipped to its cell, so it never runs under the
 * button beside it or off the menu, and it will be as narrow as its cell asks.
 *
 * <p>The slide is driven by an action, which is what keeps the app drawing at full rate while it
 * runs (see IdleFrameRate); a caption that fits has none. Each time the caption comes back on
 * screen - a menu opened again - it starts over from the beginning.
 */
public class MarqueeLabel extends Label {

    /** Seconds the start of the text is shown before it moves. */
    static final float WAIT_SECONDS = 2f;
    /** Seconds the end rests before the text goes back to its start. */
    static final float REST_SECONDS = 1.5f;
    /** How fast it slides, in line heights a second: slow enough to read as it goes. */
    static final float SPEED_LINES_PER_SECOND = 1.3f;

    private float offset;
    private float time;
    private long drawnFrame = -2;
    private final Action slide = new Action() {
        @Override
        public boolean act(float delta) {
            time += delta;
            return false;
        }
    };

    public MarqueeLabel(CharSequence text, LabelStyle style) {
        super(text, style);
    }

    /** As narrow as the cell asks: the text slides instead of pushing the button wider. */
    @Override
    public float getMinWidth() {
        return 0f;
    }

    /** How far past its cell the text reaches; 0 when it fits. */
    float overflow() {
        return Math.max(0f, getPrefWidth() - getWidth());
    }

    /** Where the text stands, {@code time} seconds into a cycle, sliding at {@code speed} per second. */
    static float offsetAt(float time, float overflow, float speed) {
        if (overflow <= 0f || speed <= 0f) {
            return 0f;
        }
        float slideSeconds = overflow / speed;
        float cycle = WAIT_SECONDS + slideSeconds + REST_SECONDS;
        float t = time % cycle;
        if (t < WAIT_SECONDS) {
            return 0f;
        }
        return Math.min(overflow, (t - WAIT_SECONDS) * speed);
    }

    @Override
    public void act(float delta) {
        boolean overflowing = overflow() > 0.5f;
        if (overflowing && !getActions().contains(slide, true)) {
            addAction(slide);
        } else if (!overflowing && getActions().contains(slide, true)) {
            removeAction(slide);
            time = 0f;
        }
        super.act(delta);
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        long frame = Gdx.graphics.getFrameId();
        if (frame > drawnFrame + 1) {
            time = 0f;   // back on screen: from the start again
        }
        drawnFrame = frame;
        float overflow = overflow();
        if (overflow <= 0.5f) {
            super.draw(batch, parentAlpha);
            return;
        }
        validate();
        offset = offsetAt(time, overflow, SPEED_LINES_PER_SECOND * getStyle().font.getLineHeight());
        // Where the text's start stands when it does not fit: left-aligned text at the left edge,
        // centred text half its overflow out, right-aligned all of it.
        int align = getLabelAlign();
        float start = (align & Align.left) != 0 ? 0f : (align & Align.right) != 0 ? overflow : overflow / 2f;
        // In the batch's coordinates, which are the parent's while it draws its children.
        batch.flush();
        if (!clipBegin(getX(), getY(), getWidth(), getHeight())) {
            return;
        }
        moveBy(start - offset, 0f);
        super.draw(batch, parentAlpha);
        moveBy(offset - start, 0f);
        batch.flush();
        clipEnd();
    }
}
