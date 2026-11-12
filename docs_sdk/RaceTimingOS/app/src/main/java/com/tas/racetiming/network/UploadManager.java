package com.tas.racetiming.network;

import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tas.racetiming.tools.PassingFormat;
import com.tas.racetiming.tools.QueueLine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * File-backed upload queue. Every read is appended to pending.csv and sent in
 * order by a single background thread; a byte cursor in the preferences marks
 * the first unsent line. Runs for the whole life of the process (started from
 * AppContext), not just while a screen is showing.
 */
public class UploadManager {
    private static final String TAG = "UploadManager";
    private static final String BASE_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/racetiming/";
    private static final String PENDING_FILE = "pending.csv";
    // byte offset into pending.csv (long); old line-count key "upload_cursor"
    // is abandoned — worst case on upgrade is a resend, which the server dedups
    private static final String KEY_CURSOR = "upload_cursor_pos";
    private static final long INTERVAL_MS = 1000;
    // capped low: during a race a long backoff delays every queued passing
    private static final long MAX_BACKOFF_MS = 15_000;
    // per-device-ID passing counter for Wiclax: key = prefix + device_id
    private static final String KEY_SEQ_PREFIX = "wiclax_seq_";
    private static final String KEY_SEQ_RUFUS = "rufus_seq";
    // Rufus accepts a list of passings per request; Wiclax is sent one by one
    // (batching on the Wiclax relay is unverified)
    private static final int RUFUS_BATCH = 50;

    public static final String PLATFORM_OFF = "off";
    public static final String PLATFORM_WICLAX = "wiclax";
    public static final String PLATFORM_RUFUS = "rufus";

    private static UploadManager instance;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "UploadMgr"));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<UploadListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger pending = new AtomicInteger(0);

    private volatile SharedPreferences prefs;
    private volatile boolean running = false;
    private java.util.concurrent.ScheduledFuture<?> cycleTask;

    // exponential backoff after consecutive failed cycles (scheduler thread only)
    private int consecutiveFailures = 0;
    private long nextAttemptMs = 0;

    // health info for the status panel
    private volatile long lastSuccessMs = 0;
    private volatile String lastError = "";
    private volatile boolean lastCycleFailed = false;

    public interface UploadListener {
        void onStatus(String platform, int sent, int failed, int pending);
    }

    public static synchronized UploadManager getInstance() {
        if (instance == null) instance = new UploadManager();
        return instance;
    }

    private UploadManager() {}

    public void init(SharedPreferences prefs) {
        if (this.prefs != null) return;
        this.prefs = prefs;
        File dir = new File(BASE_DIR);
        if (!dir.exists()) dir.mkdirs();
        scheduler.execute(() -> {
            pending.set(countPendingInFile());
            notifyStatus();
        });
    }

    public void addListener(UploadListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
        notifyStatus(0, lastCycleFailed ? 1 : 0); // give the new listener the current state
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

    public String getPlatform() {
        SharedPreferences p = prefs;
        return p != null ? p.getString("selected_platform", PLATFORM_OFF) : PLATFORM_OFF;
    }

    public int pendingCount() {
        return Math.max(0, pending.get());
    }

    /** Time of the last successful upload, epoch ms; 0 = none since app start. */
    public long getLastSuccessMs() {
        return lastSuccessMs;
    }

    /** Reason for the most recent failure; empty when the last attempt worked. */
    public String getLastError() {
        return lastError;
    }

    public boolean isRetrying() {
        return lastCycleFailed;
    }

    /** Last sequence number handed out for the current platform/device ID. */
    public long getCurrentSeq() {
        SharedPreferences p = prefs;
        if (p == null) return 0;
        String key = seqKey(getPlatform());
        return key == null ? 0 : p.getLong(key, 0);
    }

    /**
     * Queue one read for upload. The queue line stores the exact read instant
     * and a sequence number, so a passing uploaded later (after the network
     * comes back, or on a retry) carries the same time and number.
     *
     * With the platform set to Off nothing is queued: Off means "do not
     * upload". The read is still in the daily CSV and can be sent later with
     * Re-send from log.
     */
    public void enqueue(String epc, String rssi, long readTimeMs) {
        if (PLATFORM_OFF.equals(getPlatform())) return;
        scheduler.execute(() -> {
            appendToQueue(epc, rssi, readTimeMs);
            notifyStatus();
        });
    }

    /** Queue reads recovered from a CSV log. Returns immediately; work is async. */
    public void enqueueAll(List<QueueLine> reads) {
        if (reads == null || reads.isEmpty()) return;
        scheduler.execute(() -> {
            for (QueueLine r : reads) appendToQueue(r.epc, r.rssi, r.readTimeMs);
            notifyStatus();
        });
    }

    // scheduler thread only
    private void appendToQueue(String epc, String rssi, long readTimeMs) {
        try {
            File dir = new File(BASE_DIR);
            if (!dir.exists()) dir.mkdirs();
            QueueLine ql = new QueueLine(epc, rssi, readTimeMs, nextSeq());
            try (FileOutputStream fos = new FileOutputStream(new File(dir, PENDING_FILE), true)) {
                fos.write((ql.format() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            pending.incrementAndGet();
        } catch (Exception e) {
            Log.e(TAG, "enqueue error: " + e.getMessage());
            lastError = "Cannot write upload queue (storage)";
        }
    }

    private String seqKey(String platform) {
        SharedPreferences p = prefs;
        if (p == null) return null;
        if (PLATFORM_WICLAX.equals(platform)) return KEY_SEQ_PREFIX + p.getString("device_id", "").trim();
        if (PLATFORM_RUFUS.equals(platform)) return KEY_SEQ_RUFUS;
        return null;
    }

    // Sequential passing number (1, 2, 3, ...). Wiclax shows it and draws a
    // red line on any gap; Rufus uses it as num_passing. Scheduler thread only.
    // commit() (not apply) so a crash can't reuse a number already handed out.
    private long nextSeq() {
        String key = seqKey(getPlatform());
        if (key == null) return 0;
        long seq = prefs.getLong(key, 0) + 1;
        prefs.edit().putLong(key, seq).commit();
        return seq;
    }

    private int countPendingInFile() {
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
            processQueue();
        } catch (Exception e) {
            Log.e(TAG, "cycle error: " + e.getMessage());
        }
    }

    private void processQueue() {
        File f = new File(BASE_DIR + PENDING_FILE);
        if (!f.exists()) return;

        String platform = getPlatform();
        // Off = paused. Reads queued before the platform was switched off stay
        // in the file and go out when a platform is selected again.
        if (PLATFORM_OFF.equals(platform)) return;
        int batchMax = PLATFORM_RUFUS.equals(platform) ? RUFUS_BATCH : 1;

        long cursor = prefs.getLong(KEY_CURSOR, 0);
        int totalSent = 0;
        boolean failed = false;

        // seek straight to the first unsent line instead of re-reading the
        // whole file every cycle (queue lines are ASCII, so readLine is safe)
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            if (cursor > raf.length()) cursor = 0; // stale cursor from a replaced file
            raf.seek(cursor);
            while (true) {
                List<QueueLine> batch = new ArrayList<>();
                long batchEnd = cursor;
                int consumed = 0; // lines covered by this batch, sendable or not
                String line;
                while (batch.size() < batchMax && (line = raf.readLine()) != null) {
                    batchEnd = raf.getFilePointer();
                    consumed++;
                    QueueLine ql = QueueLine.parse(line);
                    if (ql == null) continue; // blank
                    if (ql.readTimeMs <= 0) {
                        // never substitute the upload time: a wrong passing time is
                        // worse than a missing one (the raw read is still in the CSV)
                        Log.e(TAG, "skip queue line with no valid read time: " + line);
                        continue;
                    }
                    batch.add(ql);
                }
                if (consumed == 0) break; // end of file

                if (!batch.isEmpty() && !send(platform, batch)) {
                    failed = true;
                    break;
                }
                totalSent += batch.size();
                pending.addAndGet(-consumed);
                cursor = batchEnd;
                prefs.edit().putLong(KEY_CURSOR, cursor).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "processQueue error: " + e.getMessage());
        }

        if (failed) {
            consecutiveFailures++;
            long backoff = Math.min(MAX_BACKOFF_MS, INTERVAL_MS << Math.min(consecutiveFailures, 6));
            nextAttemptMs = System.currentTimeMillis() + backoff;
            Log.w(TAG, "send failed, backing off " + backoff + "ms (streak " + consecutiveFailures + ")");
        } else if (totalSent > 0) {
            consecutiveFailures = 0;
            nextAttemptMs = 0;
            lastSuccessMs = System.currentTimeMillis();
            lastError = "";
        }
        lastCycleFailed = failed;

        long fileLen = f.length();
        if (!failed && fileLen > 0 && cursor >= fileLen) {
            f.delete();
            pending.set(0);
            prefs.edit().putLong(KEY_CURSOR, 0).apply();
        }

        if (totalSent > 0 || failed) {
            notifyStatus(totalSent, failed ? 1 : 0);
        }

        // Close Rufus session when queue is empty
        RufusClient rc = RufusClient.getInstance();
        if (rc.getTokenSession() != null && pendingCount() == 0) {
            rc.doFinalClose();
        }
    }

    private boolean send(String platform, List<QueueLine> batch) {
        if (PLATFORM_WICLAX.equals(platform)) {
            for (QueueLine ql : batch) {
                if (!sendWiclax(ql)) return false;
            }
            return true;
        }
        if (PLATFORM_RUFUS.equals(platform)) {
            if (RufusClient.getInstance().getTokenSession() == null) {
                RufusClient.getInstance().createSession();
                lastError = "Rufus: opening session";
                return false; // will retry next cycle when session is ready
            }
            return sendRufus(batch);
        }
        return false;
    }

    private boolean sendWiclax(QueueLine ql) {
        String url = prefs.getString("server_url", "http://wiclax.com:35014").trim();
        String user = prefs.getString("user", "").trim();
        String deviceId = prefs.getString("device_id", "").trim();
        if (url.isEmpty()) url = "http://wiclax.com:35014";
        if (user.isEmpty() || deviceId.isEmpty()) {
            // keep the passing queued until the settings are filled in
            lastError = "Wiclax: set user and device ID in Settings";
            WiclaxClient.getInstance().reportSendResult(false, "Set user and device ID");
            return false;
        }

        // Relay format SEQ@EPC@yyyyMMddHHmmssSSS, built from the read time
        String passingData = PassingFormat.wiclax(ql.seq, ql.epc, ql.readTimeMs);
        try {
            String fullUrl = url + "?user=" + URLEncoder.encode(user, "UTF-8")
                    + "&deviceId=" + URLEncoder.encode(deviceId, "UTF-8")
                    + "&passings=" + URLEncoder.encode(passingData, "UTF-8").replace("+", "%20");
            HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            // Drain the body: HttpURLConnection only returns the socket to the
            // keep-alive pool once the response is fully read. Without this every
            // passing paid a fresh TCP connect (~600 ms per send).
            String body = "";
            try {
                java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (is != null) {
                    body = new String(readAll(is), StandardCharsets.UTF_8).trim();
                    is.close();
                }
            } catch (Exception ignored) {}
            if (body.length() > 120) body = body.substring(0, 120);
            Log.i(TAG, "Wiclax code=" + code + " passing=" + passingData + " resp=" + body);
            // The relay answers HTTP 200 even when it rejects a passing, with a
            // body like "error: invalid user account or device ID" (verified on
            // the Wiclax pointages page: such passings are not recorded). Treat a
            // body error as a failure so the passing stays queued and retries
            // with backoff, and show the reason in the status.
            boolean bodyError = body.toLowerCase(Locale.US).startsWith("error");
            boolean ok = code >= 200 && code < 300 && !bodyError;
            if (bodyError) Log.w(TAG, "Wiclax rejected passing: " + body);
            String info = ok ? "Sent (" + code + ")"
                    : bodyError ? "Wiclax: " + body.substring(body.indexOf(':') + 1).trim()
                    : "HTTP " + code;
            if (!ok) lastError = info;
            WiclaxClient.getInstance().reportSendResult(ok, info);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "Wiclax fail: " + e.getMessage());
            lastError = "No connection to Wiclax";
            WiclaxClient.getInstance().reportSendResult(false, "Send failed");
            return false;
        }
    }

    private boolean sendRufus(List<QueueLine> batch) {
        String apiKey = prefs.getString("rufus_api_key", "").trim();
        String tokenSession = RufusClient.getInstance().getTokenSession();
        if (apiKey.isEmpty()) {
            lastError = "Rufus: set the API key in Settings";
            return false;
        }
        if (tokenSession == null) {
            lastError = "Rufus: no session";
            return false;
        }

        try {
            // API expects RFC 3339; convert device-local read time to real UTC
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String tz = java.util.TimeZone.getDefault().getID();

            org.json.JSONArray list = new org.json.JSONArray();
            for (QueueLine ql : batch) {
                org.json.JSONObject passing = new org.json.JSONObject();
                passing.put("timestamp", iso.format(new Date(ql.readTimeMs)));
                passing.put("timezone", tz);
                // persisted sequence number, so it does not restart with the app
                passing.put("num_passing", ql.seq);
                passing.put("bib", ql.epc.replaceFirst("^0+(?!$)", ""));
                passing.put("chip", ql.epc);
                list.put(passing);
            }

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
                Log.i(TAG, "Rufus POST code=" + code + " passings=" + batch.size() + " " + counts);
                return true;
            } else {
                String err = conn.getErrorStream() != null
                        ? new String(readAll(conn.getErrorStream()), "UTF-8") : "";
                conn.disconnect();
                Log.w(TAG, "Rufus POST code=" + code + " passings=" + batch.size() + " err=" + err);
                lastError = "Rufus: HTTP " + code;
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Rufus fail: " + e.getMessage());
            lastError = "No connection to Rufus";
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
        notifyStatus(0, lastCycleFailed ? 1 : 0);
    }

    private void notifyStatus(int sent, int failed) {
        int p = pendingCount();
        String platform = getPlatform();
        mainHandler.post(() -> {
            for (UploadListener l : listeners) l.onStatus(platform, sent, failed, p);
        });
    }
}
