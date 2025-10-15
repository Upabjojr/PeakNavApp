package com.peaknav.utils;

public abstract class StoppableRunnable implements Runnable {

    private volatile boolean stopRequest = false;
    private Integer priority = null;

    public void stop() {
        stopRequest = true;
    }

    protected boolean checkStop() {
        if (stopRequest) {
            stopRequest = false;
            return true;
        }
        return false;
    }

    protected void checkStopThrow() {
        if (checkStop()) {
            throw new StopThreadException();
        }
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public Integer getPriority() {
        return priority;
    }
}
