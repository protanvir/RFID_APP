package com.tas.racetiming.network;

import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
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
import java.util.concurrent.atomic.AtomicInteger;

public class UploadManager {
    private static final String TAG = "UploadManager";
    private static final String BASE_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/racetiming/";
    private static final String PENDING_FILE = "pending.csv";
    // byte offset into pending.csv (long); old line-count key "upload_cursor"
    // is abandoned — worst case on upgrade is a resend, which the server dedups
    private static final String KEY_CURSOR = "upload_cursor_pos";
    private static final long INTERVAL_MS = 1000;
    private static final String QUEUE_TS_FORMAT = "dd-MM-yyyy HH:mm:ss.SSS";

    private static UploadManager instance;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "UploadMgr"));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<UploadListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger passingCounter = new AtomicInteger(0);

    private static final long MAX_BACKOFF_MS = 60_000;

    private SharedPreferences prefs;
    private volatile boolean running = false;
    private java.util.concurrent.ScheduledFuture<?> cycleTask;

    // exponential backoff after consecutive failed cycles (scheduler thread only)
    private int consecutiveFailures = 0;
    private long nextAttemptMs = 0;

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

    public synchronized void start() {
        if (running) return;
        running = true;
        cycleTask = scheduler.scheduleWithFixedDelay(this::runCycle, 0, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        running = false;
        if (cycleTask != null) {
            cycleTask.cancel(false);
            cycleTask = null;
        }
    }

    public void enqueue(String epc, String rssi) {
        final Date readTime = new Date(); // capture at read time, not when the task runs
        scheduler.execute(() -> {
            try {
                File dir = new File(BASE_DIR);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, PENDING_FILE);
                String ts = new SimpleDateFormat(QUEUE_TS_FORMAT, Locale.US).format(readTime);
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
        long cursor = prefs != null ? prefs.getLong(KEY_CURSOR, 0) : 0;
        int lines = 0;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            if (cursor > 0 && cursor <= raf.length()) raf.seek(cursor);
            while (raf.readLine() != null) lines++;
        } catch (Exception ignored) {}
        return lines;
    }

    private void runCycle() {
        if (!running || prefs == null) return;
        if (System.currentTimeMillis() < nextAttemptMs) return; // backing off
        try {
            processBatch();
        } catch (Exception e) {
            Log.e(TAG, "cycle error: " + e.getMessage());
        }
    }

    private void processBatch() {
        File f = new File(BASE_DIR + PENDING_FILE);
        if (!f.exists()) return;

        long cursor = prefs.getLong(KEY_CURSOR, 0);
        int totalSent = 0, totalFailed = 0;

        // seek straight to the first unsent line instead of re-reading the
        // whole file every cycle (queue lines are ASCII, so readLine is safe)
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            if (cursor > raf.length()) cursor = 0; // stale cursor from a replaced file
            raf.seek(cursor);
            String line;
            while ((line = raf.readLine()) != null) {
                long lineEnd = raf.getFilePointer();
                line = line.trim();
                if (line.isEmpty()) { cursor = lineEnd; continue; }

                String[] parts = line.split(";", 3);
                String epc = parts.length > 0 ? parts[0] : "";
                String queuedTs = parts.length > 1 ? parts[1] : "";
                String rssi = parts.length > 2 ? parts[2] : "";
                if (epc.isEmpty()) { cursor = lineEnd; continue; }

                if (sendToPlatform(epc, queuedTs, rssi)) {
                    totalSent++;
                    cursor = lineEnd;
                    prefs.edit().putLong(KEY_CURSOR, cursor).apply();
                } else {
                    totalFailed++;
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "processBatch error: " + e.getMessage());
        }

        if (totalFailed > 0) {
            consecutiveFailures++;
            long backoff = Math.min(MAX_BACKOFF_MS, INTERVAL_MS << Math.min(consecutiveFailures, 6));
            nextAttemptMs = System.currentTimeMillis() + backoff;
            Log.w(TAG, "send failed, backing off " + backoff + "ms (streak " + consecutiveFailures + ")");
        } else if (totalSent > 0) {
            consecutiveFailures = 0;
            nextAttemptMs = 0;
        }

        if (totalSent > 0 || totalFailed > 0) {
            notifyStatus(totalSent, totalFailed);
        }

        long fileLen = f.length();
        if (fileLen > 0 && cursor >= fileLen) {
            f.delete();
            prefs.edit().putLong(KEY_CURSOR, 0).apply();
            notifyStatus(totalSent, 0);
        }

        // Close Rufus session when queue is empty
        RufusClient rc = RufusClient.getInstance();
        if (rc.getTokenSession() != null && pendingCount() == 0) {
            rc.doFinalClose();
        }
    }

    private boolean sendToPlatform(String epc, String queuedTs, String rssi) {
        String platform = prefs.getString("selected_platform", "off");
        if ("off".equals(platform)) return true;
        if ("wiclax".equals(platform)) return sendWiclax(epc, queuedTs);
        if ("rufus".equals(platform)) {
            if (RufusClient.getInstance().getTokenSession() == null) {
                RufusClient.getInstance().createSession();
                return false; // will retry next cycle when session is ready
            }
            return sendRufus(epc, queuedTs);
        }
        return true;
    }

    // Parse the timestamp stored in pending.csv; null if missing/corrupt.
    private Date parseQueuedTs(String ts) {
        if (ts == null || ts.isEmpty()) return null;
        try {
            return new SimpleDateFormat(QUEUE_TS_FORMAT, Locale.US).parse(ts);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean sendWiclax(String epc, String queuedTs) {
        String url = prefs.getString("server_url", "http://wiclax.com:35014");
        String user = prefs.getString("user", "");
        String deviceId = prefs.getString("device_id", "");
        if (user.isEmpty() || deviceId.isEmpty()) return true;

        Date readTime = parseQueuedTs(queuedTs);
        String ts = new SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US)
                .format(readTime != null ? readTime : new Date());
        String passingData = "@" + epc + "@" + ts;
        try {
            String fullUrl = url + "?user=" + URLEncoder.encode(user, "UTF-8")
                    + "&deviceId=" + URLEncoder.encode(deviceId, "UTF-8")
                    + "&passings=" + URLEncoder.encode(passingData, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            conn.disconnect();
            Log.i(TAG, "Wiclax code=" + code + " epc=" + epc);
            boolean ok = code >= 200 && code < 300;
            WiclaxClient.getInstance().reportSendResult(ok, ok ? "Sent (" + code + ")" : "HTTP " + code);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "Wiclax fail: " + e.getMessage());
            WiclaxClient.getInstance().reportSendResult(false, "Send failed");
            return false;
        }
    }

    private boolean sendRufus(String epc, String queuedTs) {
        String apiKey = prefs.getString("rufus_api_key", "");
        String tokenSession = RufusClient.getInstance().getTokenSession();
        if (apiKey.isEmpty()) { Log.w(TAG, "Rufus skip: no api key"); return false; }
        if (tokenSession == null) { Log.w(TAG, "Rufus skip: no session"); return false; }

        final int num = passingCounter.incrementAndGet();
        Date readTime = parseQueuedTs(queuedTs);

        try {
            // API expects RFC 3339; convert device-local read time to real UTC
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));

            org.json.JSONObject passing = new org.json.JSONObject();
            passing.put("timestamp", iso.format(readTime != null ? readTime : new Date()));
            passing.put("timezone", java.util.TimeZone.getDefault().getID());
            passing.put("num_passing", num);
            passing.put("bib", epc.replaceFirst("^0+(?!$)", ""));
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
            if (code >= 200 && code < 300) {
                String resp = new String(readAll(conn.getInputStream()), "UTF-8");
                conn.disconnect();
                // log counts, not the raw body — the response echoes token_session
                String counts = "";
                try {
                    org.json.JSONObject rb = new org.json.JSONObject(resp).getJSONObject("body");
                    counts = "inserted=" + rb.optInt("inserted_count", -1)
                            + " dup=" + rb.optInt("duplicate_count", 0);
                } catch (Exception ignored) {}
                Log.i(TAG, "Rufus POST code=" + code + " epc=" + epc + " " + counts);
                return true;
            } else {
                String err = conn.getErrorStream() != null
                        ? new String(readAll(conn.getErrorStream()), "UTF-8") : "";
                conn.disconnect();
                Log.w(TAG, "Rufus POST code=" + code + " epc=" + epc + " err=" + err);
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Rufus fail: " + e.getMessage());
            return false;
        }
    }

    private byte[] readAll(java.io.InputStream is) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    private void notifyStatus() {
        notifyStatus(0, 0);
    }

    private void notifyStatus(int sent, int failed) {
        int pending = pendingCount();
        String platform = prefs != null ? prefs.getString("selected_platform", "off") : "off";
        mainHandler.post(() -> {
            for (UploadListener l : listeners) l.onStatus(platform, sent, failed, pending);
        });
    }
}
