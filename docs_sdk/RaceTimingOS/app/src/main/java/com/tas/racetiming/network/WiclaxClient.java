package com.tas.racetiming.network;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

// Status broadcaster for the Wiclax platform; actual sending lives in
// UploadManager, which reports results here via reportSendResult
public class WiclaxClient {
    private static WiclaxClient instance;

    private String wiclaxUrl = "http://wiclax.com:35014";
    private String wiclaxUser = "";
    private String wiclaxDeviceId = "";
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile String currentStatus = "Ready";
    private final CopyOnWriteArrayList<WiclaxListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface WiclaxListener {
        void onStatusChanged(boolean connected, String info);
    }

    public static synchronized WiclaxClient getInstance() {
        if (instance == null) instance = new WiclaxClient();
        return instance;
    }

    private WiclaxClient() {}

    public void configure(String url, String user, String deviceId) {
        this.wiclaxUrl = (url != null && !url.isEmpty()) ? url : "http://wiclax.com:35014";
        this.wiclaxUser = user != null ? user : "";
        this.wiclaxDeviceId = deviceId != null ? deviceId : "";
    }

    public void addListener(WiclaxListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(WiclaxListener listener) {
        listeners.remove(listener);
    }

    public void start() { notifyStatus(false, "Ready"); }
    public void stop() { connected.set(false); notifyStatus(false, "Stopped"); }
    public boolean isConnected() { return connected.get(); }
    public String getCurrentStatus() { return currentStatus; }

    // called by UploadManager after each Wiclax send attempt
    public void reportSendResult(boolean ok, String info) {
        connected.set(ok);
        notifyStatus(ok, info);
    }

    private void notifyStatus(boolean conn, String info) {
        currentStatus = info;
        mainHandler.post(() -> {
            for (WiclaxListener l : listeners) l.onStatusChanged(conn, info);
        });
    }
}
