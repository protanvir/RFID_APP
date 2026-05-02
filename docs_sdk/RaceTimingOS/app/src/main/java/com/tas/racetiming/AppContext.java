package com.tas.racetiming;

import android.app.Application;
import android.os.SystemClock;
import android.util.Log;

import com.tas.racetiming.uhf.UHFManager;

public class AppContext extends Application implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "AppContext";
    private static AppContext instance;

    public static AppContext getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        Log.e(TAG, "Uncaught exception: " + throwable.getMessage());
        UHFManager.getInstance().free();
        SystemClock.sleep(200);
        System.exit(0);
    }
}
