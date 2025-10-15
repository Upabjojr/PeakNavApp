package com.peaknav.utils;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PeakNavThreadExecutor extends ThreadPoolExecutor {

    private final int numThreads;
    private final String name;

    private volatile boolean stopFlag = false;

    // private BlockingQueue<FutureTask> submittedFutureTasks = new LinkedBlockingQueue<>();
    private BlockingQueue<StoppableRunnable> submittedStoppableRunnable = new LinkedBlockingQueue<>();

/*    public void executeWithFutureTask(Runnable runnable) {
        FutureTask<Void> futureTask = new FutureTask<>(runnable, null);
        submittedFutureTasks.add(futureTask);
        submit(futureTask);
    }*/

    public synchronized void executeStoppableRunnable(StoppableRunnable runnable) {
        submittedStoppableRunnable.add(runnable);
        execute(() -> {
            try {
                runnable.run();
            } catch (StopThreadException stopThreadException) {}
        });
        // submittedStoppableRunnable.remove(runnable);
    }

    public PeakNavThreadExecutor(int numThreads, String name) {
        super(numThreads, numThreads,
                0L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                new PeakNavThreadFactory(name));
        this.numThreads = numThreads;
        this.name = name;
    }

    public synchronized void stopLoop() {
        getQueue().clear();
        while (!submittedStoppableRunnable.isEmpty()) {
            StoppableRunnable stoppableRunnable = submittedStoppableRunnable.remove();
            stoppableRunnable.stop();
        }
        stopFlag = true;
    }

    public synchronized void stopLoopByType(Class<?> klass) {
        for (StoppableRunnable runnable : submittedStoppableRunnable) {
            if (runnable.getClass().isAssignableFrom(klass)) {
                submittedStoppableRunnable.remove(runnable);
                runnable.stop();
            }
        }
    }
}
