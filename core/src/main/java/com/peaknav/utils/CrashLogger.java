package com.peaknav.utils;

import com.badlogic.gdx.Gdx;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public abstract class CrashLogger {

    protected final Throwable throwable;
    protected final String fileNamePrefix;
    protected final String formattedDate;
    private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd.HH_mm_ss.SSS", Locale.ITALY);


    public CrashLogger(Throwable throwable, String fileNamePrefix) {
        this.throwable = throwable;
        this.fileNamePrefix = fileNamePrefix;

        Date date = new Date();
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        formattedDate = format.format(date);
    }

    protected void logToExternalFile() {
        String fileName = getFileName();
        if (fileName == null)
            return;
        File file = Gdx.files.external(fileName).file();
        try {
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(getLogMessage().getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    protected abstract String getLogMessage();

    protected abstract String getFileName();

    public abstract void displayTimeWarning(String warning);

    public abstract void logToFile();
}
