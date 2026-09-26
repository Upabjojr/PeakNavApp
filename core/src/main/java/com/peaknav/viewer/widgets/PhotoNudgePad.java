package com.peaknav.viewer.widgets;

import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.peaknav.gesture.MountainInputController;

/**
 * Four buttons at the edges of the screen for lining the terrain up with a photo more finely
 * than a finger can: each tap moves the terrain by one pixel of the screen, and holding one
 * keeps it moving, slowly at first and faster the longer it is held.
 *
 * <p>While a point of the photo is pinned the same four buttons turn and stretch the terrain
 * about the pin instead, which is what is left to adjust once one summit sits on its own: the
 * left and right ones turn it anticlockwise and clockwise, the top one stretches it out from
 * the pin, the bottom one shrinks it in. Each step moves a point a quarter of the screen away
 * from the pin by about a pixel.
 *
 * <p>The buttons sit on the middle of each edge, clear of the controls already there: below the
 * top row, above the elevation and coordinate readouts at the bottom, beside the elevation bar
 * on the left, and inside the column of buttons on the right.
 */
public class PhotoNudgePad extends Group {

    /** How opaque the buttons are at rest, so the photo shows through them; pressed, fully. */
    static final float REST_ALPHA = 0.5f;
    /** Seconds a held button waits before it repeats, then steps a second at first... */
    static final float REPEAT_DELAY = 0.4f;
    static final float SLOW_STEPS_PER_SECOND = 12f;
    /** ...and after this long held, this many: a long press crosses the screen in seconds. */
    static final float FAST_AFTER = 2f;
    static final float FAST_STEPS_PER_SECOND = 60f;

    private enum Edge { UP, DOWN, LEFT, RIGHT }

    private final float unit;
    private final float size;
    private final ImageButton[] buttons = new ImageButton[4];
    private final Drawable[] moveIcons = new Drawable[4];
    private final Drawable[] pinIcons = new Drawable[4];
    private boolean pinned;
    private Edge held;
    private float heldFor;
    private float stepsOwed;

    public PhotoNudgePad(float widgetUnitStep) {
        this.unit = widgetUnitStep;
        this.size = 1.4f * widgetUnitStep;
        setTouchable(Touchable.childrenOnly);
        String[] move = {"up", "down", "left", "right"};
        // In pinned mode: stretch, shrink, turn anticlockwise, turn clockwise.
        String[] pin = {"stretch", "shrink", "rotate_ccw", "rotate_cw"};
        for (final Edge edge : Edge.values()) {
            int i = edge.ordinal();
            moveIcons[i] = getC().widgetTextures.getTextureRegionDrawable("icons/icon_nudge_" + move[i] + ".png");
            pinIcons[i] = getC().widgetTextures.getTextureRegionDrawable("icons/icon_nudge_" + pin[i] + ".png");
            ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle();
            style.imageUp = moveIcons[i];
            ImageButton button = new ImageButton(style);
            button.getImageCell().size(size);
            button.setSize(size, size);
            button.getColor().a = REST_ALPHA;
            button.setName("photo_nudge_" + move[i]);   // for /widgets
            button.addListener(new InputListener() {
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, int b) {
                    held = edge;
                    heldFor = 0f;
                    event.getListenerActor().getColor().a = 1f;
                    stepsOwed = 0f;
                    step(edge);   // a tap moves at once, by one step
                    // An action while held keeps the app drawing at full rate (IdleFrameRate).
                    addAction(Actions.forever(Actions.delay(1f)));
                    return true;
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, int b) {
                    held = null;
                    clearActions();
                    event.getListenerActor().getColor().a = REST_ALPHA;
                }
            });
            buttons[i] = button;
            addActor(button);
        }
        setVisible(false);
    }

    /**
     * Shown only with a photo behind the terrain; in the pinned mode's guise while a point is
     * pinned. Call once a frame.
     */
    public void update(boolean photoShown, boolean pinnedNow) {
        if (isVisible() != photoShown) {
            setVisible(photoShown);
            held = null;
            clearActions();
        }
        if (!photoShown) {
            return;
        }
        if (pinnedNow != pinned) {
            pinned = pinnedNow;
            for (int i = 0; i < 4; i++) {
                ((ImageButton.ImageButtonStyle) buttons[i].getStyle()).imageUp = pinned ? pinIcons[i] : moveIcons[i];
            }
            held = null;
            clearActions();
        }
        place();
    }

    private void place() {
        if (getStage() == null) {
            return;
        }
        float w = getStage().getWidth(), h = getStage().getHeight();
        float pad = 0.2f * unit;
        float middleY = (h - size) / 2;
        position(Edge.UP, (w - size) / 2, h - 1.3f * unit - size - pad);
        position(Edge.DOWN, (w - size) / 2, 3.1f * unit + pad);   // above the readouts
        position(Edge.LEFT, 1.4f * unit, middleY);
        // On the centre line, like the others; where the column of the photo's controls on the
        // right reaches that high - the terrain-opacity bar, and in debug builds a button over
        // it, on a screen held sideways - it steps left, clear of the column.
        float rightX = w - 0.25f * unit - size, rightY = middleY;
        com.peaknav.viewer.widgets.WidgetGetter.TableLocation location =
                getC().getMapViewerScreen().tableLocation;
        float columnTop = location == null ? Float.NaN : location.photoColumnTop();
        if (!Float.isNaN(columnTop) && rightY < columnTop + pad) {
            rightX = w - 1.4f * unit - pad - size;
        }
        position(Edge.RIGHT, rightX, rightY);
    }

    private void position(Edge edge, float x, float y) {
        Button b = buttons[edge.ordinal()];
        if (b.getX() != x || b.getY() != y) {
            b.setPosition(x, y);
        }
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (held == null || !isVisible()) {
            return;
        }
        heldFor += delta;
        if (heldFor < REPEAT_DELAY) {
            return;
        }
        stepsOwed += delta * (heldFor < FAST_AFTER ? SLOW_STEPS_PER_SECOND : FAST_STEPS_PER_SECOND);
        while (stepsOwed >= 1f) {
            stepsOwed -= 1f;
            step(held);
        }
    }

    /** One step: a pixel's move, or in the pinned mode a pixel's turn or stretch a quarter-screen out. */
    private void step(Edge edge) {
        MountainInputController controller = getC().getMapViewerScreen().controller;
        if (controller == null) {
            return;
        }
        if (!pinned) {
            switch (edge) {
                case UP:    controller.nudgeTerrain(0f, 1f); break;
                case DOWN:  controller.nudgeTerrain(0f, -1f); break;
                case LEFT:  controller.nudgeTerrain(-1f, 0f); break;
                case RIGHT: controller.nudgeTerrain(1f, 0f); break;
            }
            return;
        }
        // A point a quarter of the screen from the pin moves by about one pixel.
        float quarter = 0.25f * Math.max(1, Gdx.graphics.getHeight());
        switch (edge) {
            case UP:    controller.stretchAboutPin(1f + 1f / quarter); break;
            case DOWN:  controller.stretchAboutPin(1f / (1f + 1f / quarter)); break;
            case LEFT:  controller.turnAboutPin((float) -Math.toDegrees(1f / quarter)); break;
            case RIGHT: controller.turnAboutPin((float) Math.toDegrees(1f / quarter)); break;
        }
    }
}
