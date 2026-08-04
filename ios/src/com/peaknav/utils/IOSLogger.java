package com.peaknav.utils;

/**
 * Logging on iOS. Goes to stdout, which is where the device console and Xcode both read
 * it from; RoboVM maps System.out onto NSLog for a packaged app.
 */
public class IOSLogger implements PeakNavLogger {

    @Override
    public void error(String tag, String msg) {
        System.out.println("E/" + tag + ": " + msg);
    }

    @Override
    public void warn(String tag, String msg) {
        System.out.println("W/" + tag + ": " + msg);
    }

    @Override
    public void info(String tag, String msg) {
        System.out.println("I/" + tag + ": " + msg);
    }

    @Override
    public void debug(String tag, String msg) {
        System.out.println("D/" + tag + ": " + msg);
    }
}
