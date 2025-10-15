package com.peaknav.utils;

import java.util.Locale;
import java.util.concurrent.ThreadFactory;

public class PeakNavThreadFactory implements ThreadFactory {
    private String threadName;
    private int count;

    public PeakNavThreadFactory(String threadName) {
        this.threadName = threadName;
        this.count = 0;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable);
        if (runnable instanceof StoppableRunnable) {
            StoppableRunnable stoppableRunnable = (StoppableRunnable) runnable;
            Integer priority = stoppableRunnable.getPriority();
            if (priority != null)
                thread.setPriority(priority);
        }
        String className = runnable.getClass().getName();
        thread.setName(String.format(Locale.ENGLISH, "%s_%s_%03d", threadName, className, count++));
        // TODO: don't catch exceptions in Desktop app?
        thread.setUncaughtExceptionHandler((thread1, throwable) -> {
            if (throwable instanceof StopThreadException) {
                return;
            }
            System.err.println(thread1.getName());
            throwable.printStackTrace();
        });
        return thread;
    }
}
