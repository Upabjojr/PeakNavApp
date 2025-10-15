package com.peaknav.utils;

public class StoppableThread extends Thread {
    protected volatile boolean stopLoopB = false;

    public void stopLoop() {
        stopLoopB = true;
    }
}
