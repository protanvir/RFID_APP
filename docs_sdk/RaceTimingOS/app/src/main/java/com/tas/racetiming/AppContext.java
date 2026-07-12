package com.tas.racetiming;

import android.app.Application;
import android.util.Log;

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
    }

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
