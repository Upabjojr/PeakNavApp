package com.peaknav.utils;

import android.util.Log;

import java.util.logging.Logger;

public class AndroidLogger implements PeakNavLogger {
    // Logger logger = Logger.getLogger("foo");

    @Override
    public void error(String tag, String msg) {
        Log.e(tag, msg);
    }

    @Override
    public void warn(String tag, String msg) {
        Log.w(tag, msg);
    }

    @Override
    public void info(String tag, String msg) {
        Log.i(tag, msg);
    }

    @Override
    public void debug(String tag, String msg) {
        Log.d(tag, msg);
    }
}
