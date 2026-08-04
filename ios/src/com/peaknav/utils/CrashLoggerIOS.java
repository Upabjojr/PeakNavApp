package com.peaknav.utils;

/**
 * A crash report on iOS: written beside the caches so it survives the process, and printed
 * so it reaches the device console while a developer is attached.
 */
public class CrashLoggerIOS extends CrashLogger {

    public CrashLoggerIOS(Throwable throwable, String fileNamePrefix) {
        super(throwable, fileNamePrefix);
    }

    @Override
    protected String getLogMessage() {
        StringBuilder message = new StringBuilder();
        message.append(throwable == null ? "no throwable" : throwable.toString()).append('\n');
        if (throwable != null) {
            for (StackTraceElement frame : throwable.getStackTrace()) {
                message.append("\tat ").append(frame).append('\n');
            }
        }
        return message.toString();
    }

    @Override
    protected String getFileName() {
        return fileNamePrefix + System.currentTimeMillis() + ".log";
    }

    @Override
    public void displayTimeWarning(String warning) {
        System.out.println("W/PeakNav: " + warning);
    }

    @Override
    public void logToFile() {
        System.out.println(getLogMessage());
        logToExternalFile();
    }
}
