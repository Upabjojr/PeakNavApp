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

    @Override
    public boolean act(float delta) {
        if (steps.size() == 0) {
            return true;
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
        if (immediate) {
            steps.clear();
        }
        steps.add(new MoveCameraActionStep(this, targetPosition,
                targetDirection, targetUp,
                immediate, interpolation, setLocationAtEnd));
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
