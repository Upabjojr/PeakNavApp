package com.peaknav.utils;

public interface PeakNavLogger {

    void error(String tag, String msg);

    void warn(String tag, String msg);

    void info(String tag, String msg);

    void debug(String tag, String msg);
}
