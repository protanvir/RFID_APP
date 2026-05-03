package com.tas.racetiming.network;

import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class UploadManager {
    private static final String TAG = "UploadManager";
    private static final String BASE_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/racetiming/";
    private static final String PENDING_FILE = "pending.csv";
    private static final String KEY_CURSOR = "upload_cursor";
    private static final long INTERVAL_MS = 1000;

    private static UploadManager instance;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "UploadMgr"));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<UploadListener> listeners = new CopyOnWriteArrayList<>();

    private SharedPreferences prefs;
    private boolean running = false;

    public interface UploadListener {
        void onStatus(String platform, int sent, int failed, int pending);
    }

    public static synchronized UploadManager getInstance() {
        if (instance == null) instance = new UploadManager();
        return instance;
    }

    private UploadManager() {}

    public void init(SharedPreferences prefs) {
        this.prefs = prefs;
        File dir = new File(BASE_DIR);
        if (!dir.exists()) dir.mkdirs();
    }

    public void addListener(UploadListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(UploadListener l) {
        listeners.remove(l);
    }

    public void start() {
        if (running) return;
        running = true;
        scheduler.scheduleWithFixedDelay(this::runCycle, 0, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
    }

    public void enqueue(String epc, String rssi) {
        scheduler.execute(() -> {
            try {
                File dir = new File(BASE_DIR);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, PENDING_FILE);
                String ts = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss.SSS", Locale.US).format(new Date());
                String line = epc + ";" + ts + ";" + rssi + "\r\n";
                FileOutputStream fos = new FileOutputStream(file, true);
                fos.write(line.getBytes(StandardCharsets.UTF_8));
                fos.close();
            } catch (Exception e) {
                Log.e(TAG, "enqueue error: " + e.getMessage());
            }
            notifyStatus();
        });
    }

    public int pendingCount() {
        File f = new File(BASE_DIR + PENDING_FILE);
        if (!f.exists()) return 0;
        int cursor = prefs != null ? prefs.getInt(KEY_CURSOR, 0) : 0;
        int lines = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            while (r.readLine() != null) lines++;
        } catch (Exception ignored) {}
        return Math.max(0, lines - cursor);
    }

    private void runCycle() {
        if (!running || prefs == null) return;
        try {
            processBatch();
        } catch (Exception e) {
            Log.e(TAG, "cycle error: " + e.getMessage());
        }
    }

    private void processBatch() {
        File f = new File(BASE_DIR + PENDING_FILE);
        if (!f.exists()) return;

        int cursor = prefs.getInt(KEY_CURSOR, 0);
        int totalSent = 0, totalFailed = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            int idx = 0;
            while ((line = br.readLine()) != null) {
                if (idx < cursor) { idx++; continue; }
                line = line.trim();
                if (line.isEmpty()) { idx++; cursor++; continue; }

                String[] parts = line.split(";", 3);
                String epc = parts.length > 0 ? parts[0] : "";
                String rssi = parts.length > 2 ? parts[2] : "";
                if (epc.isEmpty()) { idx++; cursor++; continue; }

                if (sendToPlatform(epc, rssi)) {
                    totalSent++;
                    cursor = idx + 1;
                    prefs.edit().putInt(KEY_CURSOR, cursor).apply();
                } else {
                    totalFailed++;
                    break;
                }
                idx++;
            }
        } catch (Exception e) {
            Log.e(TAG, "processBatch error: " + e.getMessage());
        }

        if (totalSent > 0 || totalFailed > 0) {
            notifyStatus();
        }

        int totalLines = countLines(f);
        if (cursor >= totalLines && totalLines > 0) {
            f.delete();
            prefs.edit().putInt(KEY_CURSOR, 0).apply();
            notifyStatus();
        }

        RufusClient rc = RufusClient.getInstance();
        if (rc.isCloseScheduled() && pendingCount() == 0) {
            rc.doFinalClose();
        }
    }

    private boolean sendToPlatform(String epc, String rssi) {
        String platform = prefs.getString("selected_platform", "off");
        if ("off".equals(platform)) return true;
        if ("wiclax".equals(platform)) return sendWiclax(epc);
        if ("rufus".equals(platform)) return sendRufus(epc);
        return true;
    }

    private boolean sendWiclax(String epc) {
        String url = prefs.getString("server_url", "http://wiclax.com:35014");
        String user = prefs.getString("user", "");
        String deviceId = prefs.getString("device_id", "");
        if (user.isEmpty() || deviceId.isEmpty()) return true;

        String ts = new SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).format(new Date());
        String passingData = "@" + epc + "@" + ts;
        String fullUrl = url + "?user=" + URLEncoder.encode(user)
                + "&deviceId=" + URLEncoder.encode(deviceId)
                + "&passings=" + URLEncoder.encode(passingData);
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            conn.disconnect();
            Log.i(TAG, "Wiclax code=" + code + " epc=" + epc);
            return code >= 200 && code < 300;
        } catch (Exception e) {
            Log.w(TAG, "Wiclax fail: " + e.getMessage());
            return false;
        }
    }

    private boolean sendRufus(String epc) {
        String apiKey = prefs.getString("rufus_api_key", "");
        String tokenSession = RufusClient.getInstance().getTokenSession();
        if (apiKey.isEmpty()) { Log.w(TAG, "Rufus skip: no api key"); return false; }
        if (tokenSession == null) { Log.w(TAG, "Rufus skip: no session"); return false; }

        try {
            org.json.JSONObject passing = new org.json.JSONObject();
            passing.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date()));
            passing.put("num_passing", 0);
            passing.put("bib", epc);
            passing.put("chip", epc);

            org.json.JSONArray list = new org.json.JSONArray();
            list.put(passing);

            org.json.JSONObject body = new org.json.JSONObject();
            body.put("token_session", tokenSession);
            body.put("passings_list", list);

            URL url = new URL("https://api.runonrufus.com/v0/passings");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
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
            String resp = code >= 200 && code < 300 ?
                new String(readAll(conn.getInputStream()), "UTF-8") :
                (conn.getErrorStream() != null ? new String(readAll(conn.getErrorStream()), "UTF-8") : "");
            conn.disconnect();
            Log.i(TAG, "Rufus POST code=" + code + " epc=" + epc + " resp=" + resp);
            return code >= 200 && code < 300;
        } catch (Exception e) {
            Log.w(TAG, "Rufus fail: " + e.getMessage());
            return false;
        }
    }

    private int countLines(File f) {
        int n = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            while (r.readLine() != null) n++;
        } catch (Exception ignored) {}
        return n;
    }

    private byte[] readAll(java.io.InputStream is) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    private void notifyStatus() {
        int pending = pendingCount();
        String platform = prefs != null ? prefs.getString("selected_platform", "off") : "off";
        mainHandler.post(() -> {
            for (UploadListener l : listeners) l.onStatus(platform, 0, 0, pending);
        });
    }
}
