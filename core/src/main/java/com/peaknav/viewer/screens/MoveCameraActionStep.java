package com.peaknav.viewer.screens;

import static com.peaknav.gesture.MountainInputController.FIELD_OF_VIEW_MAX;
import static com.peaknav.gesture.MountainInputController.FIELD_OF_VIEW_MIN;
import static com.peaknav.utils.PeakNavUtils.getC;

import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction;
import com.peaknav.utils.Units;
import com.peaknav.viewer.PerspectiveCameraExt;

import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MoveCameraActionStep extends TemporalAction {

    private final Vector3 targetPosition = new Vector3();
    private final Vector3 targetDirection = new Vector3();
    private final Vector3 targetUp = new Vector3();

    private final Vector3 startPosition = new Vector3();
    private final Vector3 startDirection = new Vector3();
    private final Vector3 startUp = new Vector3();

    private final float DURATION_NORMAL = 1.0f;

    // A fly-to used to slide the camera along a straight line to the destination. These give it a
    // gentle parabolic arc instead — the camera lifts a bit through the middle of the move and
    // settles back down at the end, reading as flying over the terrain rather than through it. The
    // peak lift is a fraction of the horizontal travel (so short hops barely arc and long jumps
    // arc more), capped so a very long jump doesn't fling the camera into space.
    private static final float ARC_HEIGHT_FRACTION = 0.28f;
    private static final float ARC_HEIGHT_MAX = Units.convertMetersToLatits(3000);

    // Fraction of the flight at which the camera starts turning to look at the destination. Before
    // this the camera keeps its heading while it approaches; the turn then runs over the rest of
    // the move. 0 means the turn tracks the whole move (the default for every other camera step).
    private final float directionStartFraction;

    // Fraction of the move at which the position reaches the destination. With this below 1 the
    // camera lands before the step ends, so the heading (which runs until the very end) keeps
    // turning for a moment after arrival. 1 means the position tracks the whole move.
    private final float positionEndFraction;

    private final PerspectiveCameraExt cam;
    private final ReentrantReadWriteLock camQueueLock;

    private final boolean movingPosition, movingDirection, movingUp;
    private boolean setLocationAtEnd = false;
    /**
     * Whether this move gets the parabolic lift. A path made of many short consecutive hops (the
     * GPX tour) must not: each hop would arc by a fraction of its own length, so the camera bobbed
     * up and down once per waypoint instead of flying a level line.
     */
    private boolean arcLift = true;
    private MoveCameraAction moveCameraAction;

    @Override
    public void restart() {
        camQueueLock.writeLock().lock();
        try {
            super.restart();
        } finally {
            camQueueLock.writeLock().unlock();
        }
    }

    private void readyForRestart(boolean immediate) {
        restart();
        // setContinuousTracking(false);
        if (immediate) {
            setDuration(0);
        } else {
            setDuration(DURATION_NORMAL);
        }
    }

    public MoveCameraActionStep(
            MoveCameraAction moveCameraAction,
            Vector3 targetPosition, Vector3 targetDirection, Vector3 targetUp,
            boolean immediate, Interpolation interpolation,
            boolean setLocationAtEnd, float directionStartFraction,
            float positionEndFraction, float durationSeconds) {
        this.moveCameraAction = moveCameraAction;
        this.cam = moveCameraAction.cam;
        this.camQueueLock = moveCameraAction.camQueueLock;
        this.directionStartFraction = directionStartFraction;
        this.positionEndFraction = positionEndFraction;

        readyForRestart(immediate);
        // A longer move makes the heading turn (which runs over the whole move) slower.
        if (!immediate && durationSeconds > 0f) {
            setDuration(durationSeconds);
        }
        if (targetPosition != null) {
            this.targetPosition.set(targetPosition);
            movingPosition = true;
        } else {
            movingPosition = false;
        }
        if (targetDirection != null) {
            this.targetDirection.set(targetDirection);
        }
        movingDirection = targetDirection != null;
        if (targetUp != null) {
            this.targetUp.set(targetUp);
        }
        movingUp = targetUp != null;
        if (interpolation != null) {
            this.setInterpolation(interpolation);
        }
        this.setLocationAtEnd = setLocationAtEnd;
    }

    void setArcLift(boolean arcLift) {
        this.arcLift = arcLift;
    }

    @Override
    protected void update(float percent) {
        camQueueLock.writeLock().lock();
        try {
            // Position finishes at positionEndFraction (< 1) and holds after. It gets its own
            // ease-in-out over [0, positionEndFraction] rather than sharing the whole-move curve:
            // that way it accelerates from rest and eases to a stop exactly at the destination,
            // instead of being cut off while still moving. When positionEndFraction is 1 (every
            // other camera move) it just follows the caller's own interpolation.
            float posEased = (positionEndFraction > 0f && positionEndFraction < 1f)
                    ? easeWindow(percent, 0f, positionEndFraction)
                    : percent;
            if (movingPosition)
                cam.position.set(getPercentage(posEased, startPosition, targetPosition));

            // Heading waits until directionStartFraction, then turns over the rest of the move. It
            // gets its own ease-in-out over [directionStartFraction, 1] so the turn accelerates
            // from rest and eases to a stop instead of snapping into and out of motion. When
            // directionStartFraction is 0 it just follows the caller's own interpolation.
            float dirEased = (directionStartFraction > 0f && directionStartFraction < 1f)
                    ? easeWindow(percent, directionStartFraction, 1f)
                    : percent;
            if (movingDirection)
                cam.direction.set(getPercentageDir(dirEased, startDirection, targetDirection));
            if (movingUp)
                cam.up.set(getPercentageDir(dirEased, startUp, targetUp));
            if (cam.fieldOfView > FIELD_OF_VIEW_MAX || cam.fieldOfView < FIELD_OF_VIEW_MIN) {
                cam.resizeFieldOfViewToBounds();
            }
            cam.update();
        } finally {
            camQueueLock.writeLock().unlock();
        }
    }

    // Ramp a linear time value from 0 to 1 over [start, end] with an ease-in-out that has zero
    // velocity at both ends (Interpolation.smooth), so anything driven by it accelerates from rest
    // and eases to a stop. Held at 0 before the window and 1 after, so a phase given a sub-window
    // sits still until its turn and then comes cleanly to rest — no sudden starts or stops.
    private static float easeWindow(float percent, float start, float end) {
        if (percent <= start) return 0f;
        if (percent >= end) return 1f;
        return Interpolation.smooth.apply((percent - start) / (end - start));
    }

    private Vector3 getPercentage(float percent, Vector3 startV, Vector3 targetV) {
        // s + p*(t - s) = (1-p)*s + p*t
        Vector3 tmpStart = startV.cpy();
        Vector3 tmpTarget = targetV.cpy();
        tmpStart.scl(1-percent);
        tmpTarget.scl(percent);
        Vector3 result = tmpStart.add(tmpTarget);

        // Add a parabolic lift on top of the straight interpolation: 4*p*(1-p) is 0 at both ends
        // and peaks at 1 when p == 0.5, so the camera rises through the middle of the flight and
        // comes back down for the landing. Scaled by the horizontal distance travelled (and
        // capped), so it grows with how far you go. Immediate moves run with p == 1, where the
        // term is 0, so they are unaffected.
        if (!arcLift) {
            return result;
        }
        float dx = targetV.x - startV.x;
        float dy = targetV.y - startV.y;
        float horizontal = (float) Math.sqrt(dx * dx + dy * dy);
        float arcHeight = Math.min(ARC_HEIGHT_FRACTION * horizontal, ARC_HEIGHT_MAX);
        result.z += arcHeight * 4f * percent * (1 - percent);
        return result;
    }

    private Vector3 getPercentageDir(float percent, Vector3 startV, Vector3 targetV) {
        // s + p*(t - s) = (1-p)*s + p*t
        Vector3 tmpStart = startV.cpy().nor();
        Vector3 tmpTarget = targetV.cpy().nor();
        Vector3 tmpCurrent = new Vector3();
        tmpCurrent.z = tmpStart.z*(1-percent) + tmpTarget.z*percent;
        double angleStart = Math.atan2(tmpStart.y, tmpStart.x);
        double angleTarget = Math.atan2(tmpTarget.y, tmpTarget.x);
        // Turn the SHORT way round. This used to add 2*PI whenever the target angle was the
        // smaller one, which forced every turn to go counter-clockwise: a heading 1 degree to
        // the right made the camera sweep 359 degrees the other way, the "sudden 360 spin" seen
        // all through the GPX fly-along (and behind the slow, wide turns other camera moves had
        // to be tuned around).
        double delta = angleTarget - angleStart;
        delta = ((delta + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI;
        double angleCurrent = angleStart + delta * percent;
        float xy = (float) Math.sqrt(1-tmpCurrent.z*tmpCurrent.z);
        tmpCurrent.x = xy*(float)Math.cos(angleCurrent);
        tmpCurrent.y = xy*(float)Math.sin(angleCurrent);
        return tmpCurrent;
    }

    @Override
    public boolean isComplete() {
        camQueueLock.readLock().lock();
        try {
            return super.isComplete();
        } finally {
            camQueueLock.readLock().unlock();
        }
    }

    @Override
    protected synchronized void begin() {
        camQueueLock.readLock().lock();
        try {
            // if (movingPosition)
            startPosition.set(cam.position);
            // if (movingDirection)
            startDirection.set(cam.direction);
            // if (movingUp)
            startUp.set(cam.up);
        } finally {
            camQueueLock.readLock().unlock();
        }
    }

    @Override
    protected void end() {
        if (setLocationAtEnd) {
            getC().L.setCurrentTargetCoords(
                    cam.position.y,
                    Units.convertLatitsToLonits(cam.position.x, cam.position.y)
            );
        }
    }

}
