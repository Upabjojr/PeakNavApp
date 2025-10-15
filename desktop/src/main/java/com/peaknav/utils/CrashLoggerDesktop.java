package com.peaknav.utils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import javax.swing.JOptionPane;

public class CrashLoggerDesktop extends CrashLogger {

    @Override
    protected String getLogMessage() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(outputStream);
        throwable.printStackTrace(printStream);
        String output = outputStream.toString();
        return output;
    }

    public CrashLoggerDesktop(Throwable throwable, String fileNamePrefix) {
        super(throwable, fileNamePrefix);
    }

    @Override
    protected String getFileName() {
        return fileNamePrefix + "." + formattedDate + ".log";
    }

    @Override
    public void displayTimeWarning(String warning) {
        // JOptionPane.showMessageDialog(null, throwable.toString() + " ::\n" + warning);
    }

    @Override
    public void logToFile() {

    }

}
