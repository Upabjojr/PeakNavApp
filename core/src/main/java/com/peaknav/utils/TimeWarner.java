package com.peaknav.utils;

import java.util.LinkedList;
import java.util.List;

public class TimeWarner {

    private final long threshold;
    private final List<String> messages = new LinkedList<>();
    private long previous = 0;

    private long trackM1, trackM2;

    public TimeWarner(long threshold) {
        this.threshold = threshold;
    }

    public void track() {
        track("");
    }

    private String timeWarningMessage(String name, long diff) {
        return "[" + name + "] took too long: " + diff + " ms!";
    }

    private void stackMessages(String name, long diff) {
        String output = timeWarningMessage(name, diff);
        long current = System.currentTimeMillis();
        messages.add(output);
        if (current - previous > 10000) {
            StringBuilder warning = new StringBuilder();
            for (String message : messages)
                warning.append(warning).append("\n").append(message);
            Throwable throwable = new Throwable(warning.toString());
            CrashLogger crashLogger = PeakNavUtils.getLoadFactory().getCrashLogger(throwable, "time");
            crashLogger.displayTimeWarning(warning.toString());
            messages.clear();
            previous = current;
        }
    }

    public void clear() {
            }

    public void track(String name) {
        long current = System.currentTimeMillis();
        trackM2 = trackM1;
        trackM1 = current;
        if (trackM2 == 0) {
            return;
        }
        long diff = current - trackM2;
        if (diff > threshold) {
            String msg = "TOO LONG [" + name + "]: " + diff + " vs. threshold of " + threshold;
            System.err.println(msg);
            stackMessages(name, diff);

            StackTraceElement[] stes = Thread.currentThread().getStackTrace();
            Thread.dumpStack();
            for (StackTraceElement ste : stes) {
                System.err.println(ste.getFileName() + ":" + ste.getMethodName() + ":" + ste.getLineNumber());
            }
        }
    }
}
