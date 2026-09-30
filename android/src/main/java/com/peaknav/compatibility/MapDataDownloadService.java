package com.peaknav.compatibility;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.peaknav.utils.PeakNavUtils;
import com.peaknav.views.AndroidLauncher;

/**
 * Keeps a map data download going with the screen locked or the app in the background.
 *
 * <p>The download runs on the app's own threads, and nothing in the app stops it; Android did.
 * An app with nothing on screen is cached, and a cached app is frozen within seconds (Android
 * 14 on) and its CPU let sleep, so the download stood still until the app was opened again. A
 * foreground service, which is what the system asks of an app doing work the user started and
 * is waiting for, keeps the process running, with a notification saying so; the partial wake
 * lock keeps the CPU awake under it. There only while a download runs: started by the first,
 * stopped when the last one ends (see PeakNavAppState.setMapDataDownloadStarted).
 *
 * <p>Every call is handed to the main thread, in order: a stop can then never overtake the start
 * it follows, and a service asked to stop before it got going still calls startForeground first -
 * one started with startForegroundService that never does is a crash.
 */
public class MapDataDownloadService extends Service {

    private static final String CHANNEL_ID = "peaknav_map_data_download";
    private static final int NOTIFICATION_ID = 2;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The running service, once created; main thread only. */
    private static MapDataDownloadService instance;
    /** Whether a download wants the service; main thread only. */
    private static boolean wanted = false;
    /** The percentage last shown, so the notification is rebuilt only on a change. */
    private static int percentShown = -1;

    private PowerManager.WakeLock wakeLock;

    /** A download began: keep the app running until {@link #stop} comes. */
    public static void start(final Context context) {
        final Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (wanted) {
                return;
            }
            wanted = true;
            percentShown = -1;
            if (instance != null) {
                instance.goForeground(0);
                return;
            }
            try {
                ContextCompat.startForegroundService(app, new Intent(app, MapDataDownloadService.class));
            } catch (RuntimeException notAllowed) {
                // From Android 12 a service cannot be started in the foreground from the
                // background (ForegroundServiceStartNotAllowedException). Downloads begin with
                // the app on screen, so this is not expected; if it happens the download simply
                // runs as it always did.
                wanted = false;
                System.err.println("[Download] no foreground service: " + notAllowed);
            }
        });
    }

    /** The last download ended. */
    public static void stop() {
        MAIN.post(() -> {
            wanted = false;
            if (instance != null) {
                instance.finish();
            }
            // Not created yet: onStartCommand sees wanted is false and ends it there.
        });
    }

    /** How far the download has got, 0 to 1, on the notification. */
    public static void progress(final float ratio) {
        final int percent = Math.max(0, Math.min(100, (int) Math.floor(ratio * 100f)));
        MAIN.post(() -> {
            if (instance != null && wanted && percent != percentShown) {
                instance.notifyProgress(percent);
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Always, even when about to stop: see the class comment.
        goForeground(Math.max(0, percentShown));
        if (!wanted) {
            finish();
            return START_NOT_STICKY;
        }
        if (wakeLock == null) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "PeakNav:mapDataDownload");
                wakeLock.setReferenceCounted(false);
                // A bound on it whatever happens: no download of this app takes hours.
                wakeLock.acquire(3 * 60 * 60 * 1000L);
            }
        }
        // Not restarted if the system kills it: the download it stood for died with the
        // process, and the queue is taken up at the next start anyway.
        return START_NOT_STICKY;
    }

    /**
     * Android 15 on: a data-sync service may run six hours a day, and is told when that is up.
     * The download goes on as it can; the service has to go.
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        wanted = false;   // or the next download would take it for still running
        finish();
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void goForeground(int percent) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(percent), type);
        percentShown = percent;
    }

    private void notifyProgress(int percent) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(percent));
        }
        percentShown = percent;
    }

    private void finish() {
        releaseWakeLock();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
            wakeLock = null;
        }
    }

    private Notification buildNotification(int percent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                // Low: it says what is going on, with no sound and no heads-up over whatever
                // the user went off to do.
                manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                        PeakNavUtils.s("Download_in_progress"), NotificationManager.IMPORTANCE_LOW));
            }
        }
        Intent open = new Intent(this, AndroidLauncher.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int immutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(PeakNavUtils.s("Download_in_progress"))
                .setContentText(percent + "%")
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(tap)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }
}
