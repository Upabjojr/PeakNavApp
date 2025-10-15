package com.peaknav.utils;

import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

public class DesktopLogger implements PeakNavLogger {
    Logger logger;

    public DesktopLogger() {
        logger = Logger.getLogger("global");
        logger.setUseParentHandlers(false);
        ConsoleHandler consoleHandler = new ConsoleHandler() {
            {
                setOutputStream(System.out);
                setLevel(Level.ALL);
            }
        };
        consoleHandler.setLevel(Level.ALL);
        logger.addHandler(consoleHandler);
    }

    @Override
    public void error(String tag, String msg) {
        logger.logp(Level.SEVERE, tag, "", msg);
    }

    @Override
    public void warn(String tag, String msg) {
        logger.logp(Level.WARNING, tag, "", msg);
    }

    @Override
    public void info(String tag, String msg) {
        logger.logp(Level.INFO, tag, "", msg);
    }

    @Override
    public void debug(String tag, String msg) {
        logger.logp(Level.INFO, tag, "", msg);
    }
}
