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
    private final PerspectiveCameraExt cam;
    private final ReentrantReadWriteLock camQueueLock;

    private final boolean movingPosition, movingDirection, movingUp;
    private boolean setLocationAtEnd = false;
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
            boolean setLocationAtEnd) {
        this.moveCameraAction = moveCameraAction;
        this.cam = moveCameraAction.cam;
        this.camQueueLock = moveCameraAction.camQueueLock;

        readyForRestart(immediate);
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

    @Override
    protected void update(float percent) {
        camQueueLock.writeLock().lock();
        try {
            if (movingPosition)
                cam.position.set(getPercentage(percent, startPosition, targetPosition));
            if (movingDirection)
                cam.direction.set(getPercentageDir(percent, startDirection, targetDirection));
            if (movingUp)
                cam.up.set(getPercentageDir(percent, startUp, targetUp));
            if (cam.fieldOfView > FIELD_OF_VIEW_MAX || cam.fieldOfView < FIELD_OF_VIEW_MIN) {
                cam.resizeFieldOfViewToBounds();
            }
            cam.update();
        } finally {
            camQueueLock.writeLock().unlock();
        }
    }

    private Vector3 getPercentage(float percent, Vector3 startV, Vector3 targetV) {
        // s + p*(t - s) = (1-p)*s + p*t
        Vector3 tmpStart = startV.cpy();
        Vector3 tmpTarget = targetV.cpy();
        tmpStart.scl(1-percent);
        tmpTarget.scl(percent);
        return tmpStart.add(tmpTarget);
    }

    private Vector3 getPercentageDir(float percent, Vector3 startV, Vector3 targetV) {
        // s + p*(t - s) = (1-p)*s + p*t
        Vector3 tmpStart = startV.cpy().nor();
        Vector3 tmpTarget = targetV.cpy().nor();
        Vector3 tmpCurrent = new Vector3();
        tmpCurrent.z = tmpStart.z*(1-percent) + tmpTarget.z*percent;
        double angleStart = Math.atan2(tmpStart.y, tmpStart.x);
        double angleTarget = Math.atan2(tmpTarget.y, tmpTarget.x);
        if (angleTarget < angleStart)
            angleTarget += 2*Math.PI;
        double angleCurrent = angleStart*(1-percent) + angleTarget*percent;
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
