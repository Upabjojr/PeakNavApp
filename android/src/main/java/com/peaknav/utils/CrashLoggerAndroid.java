package com.peaknav.utils;

import static com.peaknav.utils.PeakNavUtils.getC;
import static com.peaknav.utils.PeakNavUtils.getNativeScreenCaller;
import static com.peaknav.utils.PreferencesManager.P;

import android.content.Context;
import android.provider.Settings;

import com.badlogic.gdx.Gdx;
import com.peaknav.compatibility.NativeScreenCallerAndroid;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class CrashLoggerAndroid extends CrashLogger {
    private final String androidId;
    private static final String logGroupName = "PeakNavApp";
    private static final String logStreamName = "PeakNavAppCrashes";

    public CrashLoggerAndroid(Throwable throwable, String fileNamePrefix, Context context) {
        super(throwable, fileNamePrefix);

        androidId = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ANDROID_ID);
    }

    @Override
    protected String getLogMessage() {
        OutputStream outstream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(outstream);
        throwable.printStackTrace(printStream);
        String stackTrace = outstream.toString();
        String message = fileNamePrefix + "\n" + androidId + "\n" + stackTrace;
        return message;
    }

    @Override
    public void logToFile() {
        String message = getLogMessage();
        long timestamp = System.currentTimeMillis();
        try {
            FileOutputStream fout = new FileOutputStream(Gdx.files.external("errlog."+timestamp+".log").file());
            fout.write(message.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected String getFileName() {
        return fileNamePrefix + "." + androidId + "." + formattedDate + ".log";
    }

    @Override
    public void displayTimeWarning(String warning) {
        // MapViewerSingleton.getAppInstance().nativeScreenCaller.makeToast(warning);
        // MapViewerSingleton.getAppInstance().loadFactory.getPeakNavNotificationManager().setText(warning, 0);
    }
}
