package com.peaknav.viewer.screens;

import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction;
import com.peaknav.viewer.PerspectiveCameraExt;

import java.util.Deque;
import java.util.NoSuchElementException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MoveCameraAction extends TemporalAction {

    public final ReentrantReadWriteLock camQueueLock = new ReentrantReadWriteLock();
    public PerspectiveCameraExt cam;

    // private boolean continuousTracking = false;
    private Deque<MoveCameraActionStep> steps = new LinkedBlockingDeque<>();

    public MoveCameraAction() {
    }

    /** While paused the queued steps are kept but not advanced, so the camera holds its pose. */
    private volatile boolean paused = false;

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused() {
        return paused;
    }

    /** Drops every queued move (and unpauses), e.g. when the GPX being toured is cleared. */
    public synchronized void clearSteps() {
        steps.clear();
        paused = false;
    }

    @Override
    public boolean act(float delta) {
        if (steps.size() == 0) {
            return true;
        }
        if (paused) {
            return false; // keep the queue, just stop advancing it
        }
        MoveCameraActionStep step;
        try {
            step = steps.getFirst();
        } catch (NoSuchElementException noSuchElementException) {
            return true;
        }
        boolean b = step.act(delta);
        if (b) {
            steps.remove(step);
        }
        return steps.size() == 0;
    }

    @Override
    protected void update(float percent) {
        if (steps.size() == 0)
            return;
        MoveCameraActionStep step;
        if (percent >= 1)
            step = steps.pollFirst();
        else
            step = steps.getFirst();
        step.update(percent);
    }

    public void setCameraVectors(
            Vector3 targetPosition, Vector3 targetDirection, Vector3 targetUp,
            boolean immediate) {
        setCameraVectors(
                targetPosition, targetDirection, targetUp,
                immediate, null, false);
    }

    public synchronized void setCameraVectors(
            Vector3 targetPosition, Vector3 targetDirection, Vector3 targetUp,
            boolean immediate, Interpolation interpolation, boolean setLocationAtEnd) {
        setCameraVectors(targetPosition, targetDirection, targetUp,
                immediate, interpolation, setLocationAtEnd, 0f);
    }

    // directionStartFraction delays the heading change until that fraction of the move (0 = track
    // the whole move); see MoveCameraActionStep.
    public synchronized void setCameraVectors(
            Vector3 targetPosition, Vector3 targetDirection, Vector3 targetUp,
            boolean immediate, Interpolation interpolation, boolean setLocationAtEnd,
            float directionStartFraction) {
        setCameraVectors(targetPosition, targetDirection, targetUp,
                immediate, interpolation, setLocationAtEnd, directionStartFraction, 1f, 0f);
    }

    // positionEndFraction lets the position finish before the step does (so the heading keeps
    // turning after arrival); durationSeconds overrides the move length (0 = default). See
    // MoveCameraActionStep.
    public synchronized void setCameraVectors(
            Vector3 targetPosition, Vector3 targetDirection, Vector3 targetUp,
            boolean immediate, Interpolation interpolation, boolean setLocationAtEnd,
            float directionStartFraction, float positionEndFraction, float durationSeconds) {
        if (immediate) {
            steps.clear();
        }
        steps.add(new MoveCameraActionStep(this, targetPosition,
                targetDirection, targetUp,
                immediate, interpolation, setLocationAtEnd,
                directionStartFraction, positionEndFraction, durationSeconds));
    }

    /**
     * Queues a move with the parabolic lift suppressed — for a path built from many short
     * consecutive hops, where a per-hop arc reads as the camera bobbing rather than flying.
     */
    public synchronized void addFlatStep(
            Vector3 targetPosition, Vector3 targetDirection, Vector3 targetUp,
            Interpolation interpolation, float durationSeconds) {
        MoveCameraActionStep step = new MoveCameraActionStep(this, targetPosition,
                targetDirection, targetUp,
                false, interpolation, false, 0f, 1f, durationSeconds);
        step.setArcLift(false);
        steps.add(step);
    }

    /** How many queued moves are still to play; used to report tour progress. */
    public int remainingSteps() {
        return steps.size();
    }

    /*
    public synchronized void setCameraVectorsWithoutRestarting(Vector3 targetDirection, Vector3 targetUp) {
        movingPosition = false;
        movingDirection = true;
        movingUp = true;
        readyForRestart(true);
        if (targetDirection.len() < 0.5f)
            return;
        if (targetUp.len() < 0.5f)
            return;
        System.err.println(targetDirection + " - " + targetUp);
        // this.targetPosition.set(targetPosition);
        this.targetDirection.set(targetDirection);
        this.targetUp.set(targetUp);
    }
     */

    public synchronized void setCameraUp(Vector3 targetUp, boolean immediate) {
        setCameraVectors(
                null, null, targetUp, true
        );
    }

    /*
    @Override
    public boolean act(float delta) {
        if (isContinuousTracking()) {
            return actContinuously(delta);
        } else {
            return super.act(delta);
        }
    }

    private synchronized boolean actContinuously(float delta) {
        movingPosition = false;
        update(0.1f);
        camQueueLock.readLock().lock();
        try {
            if (movingPosition)
                startPosition.set(cam.position);
            if (movingDirection)
                startDirection.set(cam.direction);
            if (movingUp)
                startUp.set(cam.up);
            return false;
        } finally {
            camQueueLock.readLock().unlock();
        }
    }
     */

    @Override
    public boolean isComplete() {
        if (steps.size() == 0)
            return true;
        return false;
    }


    public void setCameraToActUpon(PerspectiveCameraExt cam) {
        this.cam = cam;
    }

    /*
    public synchronized boolean isContinuousTracking() {
        return continuousTracking;
    }

    private final Interpolation continuousInterpolation = new Interpolation() {
        @Override
        public float apply(float a) {
            return 0;
        }
    };

    public synchronized void setContinuousTracking(boolean continuousTracking) {
        this.continuousTracking = continuousTracking;
        if (continuousTracking) {
            setDuration(Float.POSITIVE_INFINITY);
        } else {
            setDuration(DURATION_NORMAL);
        }
        restart();
    }
     */
}
