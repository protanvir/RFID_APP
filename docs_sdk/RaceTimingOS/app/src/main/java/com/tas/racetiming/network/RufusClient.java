package com.tas.racetiming.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RufusClient {
    private static final String TAG = "RufusClient";
    private static final String DEFAULT_URL = "https://api.runonrufus.com/v0";
    private static RufusClient instance;

    private String apiKey;
    private String serialNumber;
    private String alias;
    private String serverUrl = DEFAULT_URL;
    private String deviceId;
    private String tokenSession;
    private boolean configured = false;
    private int passingCounter = 0;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Rufus-IO"));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<RufusListener> listeners = new CopyOnWriteArrayList<>();

    private volatile String status = "Ready";

    public interface RufusListener {
        void onStatusChanged(String status);
    }

    public static synchronized RufusClient getInstance() {
        if (instance == null) instance = new RufusClient();
        return instance;
    }

    private RufusClient() {}

    public void configure(String apiKey, String serialNumber, String alias) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.serialNumber = serialNumber != null ? serialNumber.trim() : "";
        this.alias = alias != null ? alias.trim() : "";
        this.configured = !this.apiKey.isEmpty() && !this.serialNumber.isEmpty();
    }

    public boolean isConfigured() {
        return configured;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getTokenSession() {
        return tokenSession;
    }

    public void setTokenSession(String tokenSession) {
        this.tokenSession = tokenSession;
    }

    public String getStatus() {
        return status;
    }

    public void addListener(RufusListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(RufusListener listener) {
        listeners.remove(listener);
    }

    private void setStatus(String s) {
        status = s;
        mainHandler.post(() -> {
            for (RufusListener l : listeners) l.onStatusChanged(s);
        });
    }

    // POST /devices — bind device
    public void bindDevice() {
        if (!configured) { setStatus("Not configured"); return; }
        setStatus("Binding…");
        ioExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("type", "CUSTOM_CHRONO");
                body.put("model", "C72-UHF");
                body.put("serial_number", serialNumber);
                body.put("alias", alias.isEmpty() ? serialNumber : alias);

                JSONObject resp = doPost("/devices", body);
                if (resp != null) {
                    JSONObject b = resp.getJSONObject("body");
                    deviceId = b.getString("deviceid");
                    Log.i(TAG, "Device bound: " + deviceId);
                    setStatus("Bound: " + deviceId.substring(0, 8) + "…");
                } else {
                    setStatus("Bind failed");
                }
            } catch (Exception e) {
                Log.e(TAG, "bindDevice error: " + e.getMessage());
                setStatus("Bind error");
            }
        });
    }

    // POST /sessions — create session
    public void createSession() {
        if (!configured || deviceId == null) { setStatus("No device"); return; }
        setStatus("Creating session…");
        ioExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("deviceid", deviceId);
                body.put("alias", "C72-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()));

                JSONObject resp = doPost("/sessions", body);
                if (resp != null) {
                    tokenSession = resp.getJSONObject("body").getString("token_session");
                    passingCounter = 0;
                    Log.i(TAG, "Session created: " + tokenSession);
                    setStatus("Session active");
                } else {
                    setStatus("Session failed");
                }
            } catch (Exception e) {
                Log.e(TAG, "createSession error: " + e.getMessage());
                setStatus("Session error");
            }
        });
    }

    // POST /passings — send a tag read
    public void sendPassing(String epc) {
        if (!configured) return;
        final String ts = tokenSession;
        if (ts == null) return;
        passingCounter++;
        int num = passingCounter;
        ioExecutor.execute(() -> {
            try {
                JSONObject passing = new JSONObject();
                passing.put("timestamp", isoNow());
                passing.put("num_passing", num);
                passing.put("bib", epc);
                passing.put("chip", epc);

                JSONArray list = new JSONArray();
                list.put(passing);

                JSONObject body = new JSONObject();
                body.put("token_session", ts);
                body.put("passings_list", list);

                JSONObject resp = doPost("/passings", body);
                if (resp != null) {
                    int inserted = resp.getJSONObject("body").optInt("inserted_count", 0);
                    setStatus("Sent (" + inserted + ")");
                } else {
                    setStatus("Send failed");
                }
            } catch (Exception e) {
                Log.e(TAG, "sendPassing error: " + e.getMessage());
                setStatus("Send error");
            }
        });
    }

    // schedule close — keeps tokenSession alive for the upload queue to drain
    private boolean closeScheduled = false;

    public void scheduleClose() {
        if (tokenSession == null) return;
        closeScheduled = true;
        setStatus("Closing…");
    }

    public boolean isCloseScheduled() {
        return closeScheduled;
    }

    // called by UploadManager when queue is empty
    public void doFinalClose() {
        if (tokenSession == null) { closeScheduled = false; return; }
        String ts = tokenSession;
        tokenSession = null;
        closeScheduled = false;
        ioExecutor.execute(() -> {
            try {
                doPatch("/sessions/close/" + ts, new JSONObject());
                Log.i(TAG, "Session closed: " + ts);
                setStatus("Session closed");
            } catch (Exception e) {
                Log.e(TAG, "final close error: " + e.getMessage());
            }
        });
    }

    // PATCH /devices/{deviceid}/status — telemetry
    public void reportTelemetry(int batteryPct, boolean charging, int tempCelsius) {
        if (!configured || deviceId == null) return;
        ioExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("reported_at", isoNow());
                body.put("device_status", "ONLINE");
                body.put("battery_percentage", batteryPct);
                body.put("charging", charging);
                body.put("temperature_celsius", tempCelsius);

                doPatch("/devices/" + deviceId + "/status", body);
            } catch (Exception e) {
                Log.e(TAG, "telemetry error: " + e.getMessage());
            }
        });
    }

    // --- Session lifecycle helper ---

    public interface SessionCallback {
        void onReady();
        void onError(String message);
    }

    private SessionCallback pendingCallback;

    public void startSession(SessionCallback callback) {
        this.pendingCallback = callback;
        if (deviceId == null) {
            setStatus("Binding…");
            ioExecutor.execute(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("type", "CUSTOM_CHRONO");
                    body.put("model", "C72-UHF");
                    body.put("serial_number", serialNumber);
                    body.put("alias", alias.isEmpty() ? serialNumber : alias);

                    JSONObject resp = doPost("/devices", body);
                    if (resp != null) {
                        deviceId = resp.getJSONObject("body").getString("deviceid");
                        Log.i(TAG, "Device bound: " + deviceId);
                        mainHandler.post(this::doCreateSession);
                    } else {
                        setStatus("Bind failed");
                        notifySessionError("Bind failed");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "bind error: " + e.getMessage());
                    setStatus("Bind error");
                    notifySessionError(e.getMessage());
                }
            });
        } else {
            mainHandler.post(this::doCreateSession);
        }
    }

    private void doCreateSession() {
        setStatus("Creating session…");
        ioExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("deviceid", deviceId);
                body.put("alias", "C72-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()));

                JSONObject resp = doPost("/sessions", body);
                if (resp != null) {
                    tokenSession = resp.getJSONObject("body").getString("token_session");
                    passingCounter = 0;
                    Log.i(TAG, "Session created: " + tokenSession);
                    setStatus("Session active");
                    notifySessionReady();
                } else {
                    setStatus("Session failed");
                    notifySessionError("Session failed");
                }
            } catch (Exception e) {
                Log.e(TAG, "createSession error: " + e.getMessage());
                setStatus("Session error");
                notifySessionError(e.getMessage());
            }
        });
    }

    private void notifySessionReady() {
        mainHandler.post(() -> {
            if (pendingCallback != null) {
                pendingCallback.onReady();
                pendingCallback = null;
            }
        });
    }

    private void notifySessionError(String msg) {
        mainHandler.post(() -> {
            if (pendingCallback != null) {
                pendingCallback.onError(msg);
                pendingCallback = null;
            }
        });
    }

    // --- HTTP helpers ---

    private JSONObject doPost(String path, JSONObject body) throws Exception {
        return doRequest("POST", path, body);
    }

    private JSONObject doPatch(String path, JSONObject body) throws Exception {
        return doRequest("PATCH", path, body);
    }

    private JSONObject doRequest(String method, String path, JSONObject body) throws Exception {
        URL url = new URL(serverUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("api_key", apiKey);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);

            byte[] data = body.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(data.length);
            OutputStream os = conn.getOutputStream();
            os.write(data);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String resp = new String(readAll(conn.getInputStream()), "UTF-8");
                return new JSONObject(resp);
            } else {
                String err = conn.getErrorStream() != null
                        ? new String(readAll(conn.getErrorStream()), "UTF-8") : "";
                Log.w(TAG, method + " " + path + " -> " + code + " " + err);
                return null;
            }
        } finally {
            conn.disconnect();
        }
    }

    private byte[] readAll(java.io.InputStream is) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    private String isoNow() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date());
    }
}
