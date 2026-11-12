package com.tas.racetiming.scan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.tas.racetiming.MainActivity;
import com.tas.racetiming.R;
import com.tas.racetiming.network.UploadManager;

/**
 * Foreground service that keeps the app alive while it is scanning or still
 * has passings to upload. Without it Android may stop the process as soon as
 * the app is no longer on screen, silently ending the race recording.
 *
 * It holds a CPU wake lock and a Wi-Fi lock so reads and uploads continue
 * with the screen off, and stops itself once there is nothing left to do.
 */
public class ScanService extends Service {
    private static final String TAG = "ScanService";
    private static final String CHANNEL_ID = "scan";
    private static final int NOTIF_ID = 1;
    private static final long TICK_MS = 2000;
    // idle = not scanning and nothing uploadable; stop after this many ticks
    private static final int IDLE_TICKS_TO_STOP = 3;
    // not scanning and uploads have made no progress for this long: stop
    // holding the device awake. Uploads resume when the app is opened again.
    private static final long STALLED_UPLOAD_LIMIT_MS = 30 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private int idleTicks = 0;
    private int lastPending = -1;
    private long lastProgressTick;

    public static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, ScanService.class));
        } catch (Exception e) {
            // e.g. background-start restrictions; scanning still works in-app
            Log.w(TAG, "could not start service: " + e.getMessage());
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean scanning = ScanController.getInstance().isScanning();
            UploadManager um = UploadManager.getInstance();
            int pending = um.pendingCount();
            boolean uploading = pending > 0 && !UploadManager.PLATFORM_OFF.equals(um.getPlatform());

            long now = SystemClock.elapsedRealtime();
            if (scanning || pending != lastPending) lastProgressTick = now;
            lastPending = pending;
            boolean stalled = !scanning && now - lastProgressTick > STALLED_UPLOAD_LIMIT_MS;

            if ((!scanning && !uploading) || stalled) {
                if (++idleTicks >= IDLE_TICKS_TO_STOP || stalled) {
                    Log.i(TAG, "nothing to do, stopping" + (stalled ? " (uploads stalled)" : ""));
                    stopSelf();
                    return;
                }
            } else {
                idleTicks = 0;
            }
            updateNotification(scanning, pending);
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        lastProgressTick = SystemClock.elapsedRealtime();
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "racetiming:scan");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "racetiming:upload");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "lock error: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification(
                ScanController.getInstance().isScanning(), UploadManager.getInstance().pendingCount()));
        idleTicks = 0;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
        // not sticky: if the process is killed there is no scan to resume, and
        // the upload queue is on disk and continues on the next launch
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Scanning and upload",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(boolean scanning, int pending) {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        String title = scanning ? "Scanning" : "Uploading passings";
        String text = ScanController.getInstance().getTotalReads() + " reads"
                + (pending > 0 ? " • " + pending + " waiting to upload" : " • all uploaded");
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_scan)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(boolean scanning, int pending) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(scanning, pending));
        } catch (Exception ignored) {}
    }
}
