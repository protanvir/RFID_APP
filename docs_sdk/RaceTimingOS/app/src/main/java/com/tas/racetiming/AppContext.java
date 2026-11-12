package com.tas.racetiming;

import android.app.Application;
import android.util.Log;

import com.tas.racetiming.network.UploadManager;
import com.tas.racetiming.scan.ScanController;
import com.tas.racetiming.tools.BibMap;
import com.tas.racetiming.tools.ClockCheck;
import com.tas.racetiming.tools.FileUtils;
import com.tas.racetiming.uhf.UHFManager;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AppContext extends Application implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "AppContext";
    private static AppContext instance;

    private Thread.UncaughtExceptionHandler previousHandler;

    public static AppContext getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);

        // Process-wide services: started here, not from a screen, so scanning
        // and uploads do not depend on any view being alive.
        android.content.SharedPreferences prefs = getSharedPreferences(ScanController.PREFS, 0);
        ScanController.getInstance().init(this);
        UploadManager um = UploadManager.getInstance();
        um.init(prefs);
        um.start();

        // Rufus used to be configured only when the Settings screen was opened,
        // so uploads after a fresh app start failed until someone visited it
        com.tas.racetiming.network.RufusClient rufus = com.tas.racetiming.network.RufusClient.getInstance();
        rufus.configure(prefs.getString("rufus_api_key", ""),
                prefs.getString("rufus_serial", ""), prefs.getString("rufus_alias", ""));
        String rufusDeviceId = prefs.getString("rufus_device_id", null);
        if (rufusDeviceId != null && !rufusDeviceId.isEmpty()) rufus.setDeviceId(rufusDeviceId);

        new Thread(BibMap::load, "BibLoad").start();
        FileUtils.cleanupOldLogs(prefs.getInt(KEY_KEEP_DAYS, 0));
        ClockCheck.runAsync();
    }

    /** Days of read logs to keep; 0 = keep forever. */
    public static final String KEY_KEEP_DAYS = "keep_days";

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        Log.e(TAG, "Uncaught exception on " + thread.getName(), throwable);
        writeCrashFile(thread, throwable);
        try {
            UHFManager.getInstance().free();
        } catch (Throwable ignored) {}
        // chain to the system handler so Android shows the crash dialog and
        // logs the tombstone instead of exiting silently
        if (previousHandler != null) {
            previousHandler.uncaughtException(thread, throwable);
        } else {
            System.exit(1);
        }
    }

    private void writeCrashFile(Thread thread, Throwable throwable) {
        try {
            File dir = new File("/sdcard/racetiming/crashes");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            try (PrintWriter pw = new PrintWriter(new FileWriter(new File(dir, "crash_" + ts + ".log")))) {
                pw.println("Thread: " + thread.getName());
                pw.println("Time: " + new Date());
                throwable.printStackTrace(pw);
            }
        } catch (Throwable ignored) {} // storage may be unavailable — never crash the crash handler
    }
}
