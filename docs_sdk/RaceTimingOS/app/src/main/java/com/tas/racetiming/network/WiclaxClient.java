package com.tas.racetiming.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class WiclaxClient {
    private static final String TAG = "WiclaxClient";
    private static WiclaxClient instance;

    private String wiclaxUrl = "http://wiclax.com:35014";
    private String wiclaxUser = "";
    private String wiclaxDeviceId = "";
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile String currentStatus = "Ready";
    private final CopyOnWriteArrayList<WiclaxListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Wiclax-IO"));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int readsReceived = 0;
    private int sendsSucceeded = 0;
    private int sendsFailed = 0;
    private String lastError = "";
    private String lastSentLine = "";

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

    public synchronized String getDebugInfo() {
        return "reads=" + readsReceived + " sent=" + sendsSucceeded
                + " fail=" + sendsFailed + " conn=" + connected.get()
                + " err=" + lastError
                + " last=[" + lastSentLine + "]";
    }

    public void sendPassing(String epc, String rssi) {
        readsReceived++;
        if (wiclaxUser.isEmpty() || wiclaxDeviceId.isEmpty()) {
            sendsFailed++;
            lastError = "user or deviceId empty";
            Log.d(TAG, "SKIP: user or deviceId not configured");
            return;
        }

        String ts = new SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).format(new Date());
        String passingData = "@" + epc + "@" + ts;
        String fullUrl = wiclaxUrl + "?user=" + wiclaxUser
                + "&deviceId=" + wiclaxDeviceId
                + "&passings=" + URLEncoder.encode(passingData);
        lastSentLine = fullUrl;

        try {
            ioExecutor.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(fullUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    int code = conn.getResponseCode();

                    connected.set(true);
                    sendsSucceeded++;
                    lastError = "";
                    Log.i(TAG, "GET " + code + " url=" + fullUrl);
                    notifyStatus(true, "Sent (" + code + ")");
                } catch (Exception e) {
                    sendsFailed++;
                    lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Log.e(TAG, "FAIL url=" + fullUrl + " err=" + lastError);
                    connected.set(false);
                    notifyStatus(false, "Send failed");
                } finally {
                    if (conn != null) conn.disconnect();
                }
            });
        } catch (RejectedExecutionException e) {
            sendsFailed++;
            lastError = "executor stopped";
            Log.e(TAG, "FAIL executor stopped");
        }
    }

    private void notifyStatus(boolean conn, String info) {
        currentStatus = info;
        mainHandler.post(() -> {
            for (WiclaxListener l : listeners) l.onStatusChanged(conn, info);
        });
    }
}
