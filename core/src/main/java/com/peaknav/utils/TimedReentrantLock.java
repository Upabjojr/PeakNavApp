package com.peaknav.utils;

import java.util.concurrent.locks.ReentrantLock;

public class TimedReentrantLock extends ReentrantLock {
    volatile long startLock;
    private final long timeout;

    public TimedReentrantLock(long timeout) {
        this.timeout = timeout;
    }

    public TimedReentrantLock() {
        this(50);
    }

    private void checkDeltaTime(long time1, long time2) {
        if (time2 - time1 > timeout) {
            System.err.println("long wait for lock");
        }
    }
    public void lock() {
        long lock1 = System.currentTimeMillis();
        super.lock();
        long lock2 = System.currentTimeMillis();
        checkDeltaTime(lock1, lock2);
        startLock = System.currentTimeMillis();
    }

    public void unlock() {
        long stopLock = System.currentTimeMillis();
        long startLock = this.startLock;
        checkDeltaTime(startLock, stopLock);
        super.unlock();
    }
}
