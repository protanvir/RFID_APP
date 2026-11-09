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

    // capped low: during a race a long backoff delays every queued passing
    private static final long MAX_BACKOFF_MS = 15_000;
    // Wiclax relay timestamp: yyyyMMddHHmmssSSS
    private static final String WICLAX_TS_FORMAT = "yyyyMMddHHmmssSSS";
    // minutes added to the read time before sending to Wiclax (Settings)
    public static final String KEY_WICLAX_OFFSET_MIN = "wiclax_offset_min";
    // per-device-ID passing counter: key = prefix + device_id
    private static final String KEY_SEQ_PREFIX = "wiclax_seq_";

    private final AtomicInteger pending = new AtomicInteger(0);

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
        scheduler.execute(() -> pending.set(countPendingInFile()));
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

    // Every read is queued (no dedup). The queue line stores the exact read
    // instant as epoch ms, so a passing uploaded later (e.g. after the network
    // comes back) always carries the read time, never the upload time.
    // Line format: EPC;dd-MM-yyyy HH:mm:ss.SSS;RSSI;epochMs;seq
    //
    // seq is a per-device-ID passing number (1, 2, 3, ...) that Wiclax shows
    // in its sequential-number column; a jump of more than 1 is drawn as a red
    // line, flagging lost passings. It is assigned here, at read time, and
    // stored with the read so retries and offline backlog keep the same number.
    public void enqueue(String epc, String rssi, long readTimeMs) {
        scheduler.execute(() -> {
            try {
                File dir = new File(BASE_DIR);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, PENDING_FILE);
                String ts = new SimpleDateFormat(QUEUE_TS_FORMAT, Locale.US).format(new Date(readTimeMs));
                String safeRssi = rssi != null ? rssi.replace(";", "") : "";
                long seq = nextSeq();
                String line = epc + ";" + ts + ";" + safeRssi + ";" + readTimeMs + ";" + seq + "\r\n";
                FileOutputStream fos = new FileOutputStream(file, true);
                fos.write(line.getBytes(StandardCharsets.UTF_8));
                fos.close();
                pending.incrementAndGet();
            } catch (Exception e) {
                Log.e(TAG, "enqueue error: " + e.getMessage());
            }
            notifyStatus();
        });
    }

    // Runs on the single scheduler thread only, so no locking is needed.
    // commit() (not apply) so a crash can't reuse a number already handed out.
    private long nextSeq() {
        if (prefs == null) return 0;
        String key = KEY_SEQ_PREFIX + prefs.getString("device_id", "");
        long seq = prefs.getLong(key, 0) + 1;
        prefs.edit().putLong(key, seq).commit();
        return seq;
    }

    // In-memory count, so status updates don't rescan the queue file on every
    // tag read (that scan ran on the upload thread and delayed sending)
    public int pendingCount() {
        return Math.max(0, pending.get());
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

                String[] parts = line.split(";");
                String epc = parts.length > 0 ? parts[0] : "";
                String queuedTs = parts.length > 1 ? parts[1] : "";
                String rssi = parts.length > 2 ? parts[2] : "";
                String epochStr = parts.length > 3 ? parts[3] : "";
                // 0 = line queued by an older build without a seq field
                long seq = 0;
                if (parts.length > 4) {
                    try { seq = Long.parseLong(parts[4].trim()); } catch (NumberFormatException ignored) {}
                }
                if (epc.isEmpty()) { cursor = lineEnd; pending.decrementAndGet(); continue; }

                long readTimeMs = resolveReadTime(epochStr, queuedTs);
                if (readTimeMs <= 0) {
                    // never substitute the upload time: a wrong passing time is
                    // worse than a missing one (the raw read is still in the CSV)
                    Log.e(TAG, "skip queue line with no valid read time: " + line);
                    cursor = lineEnd;
                    pending.decrementAndGet();
                    prefs.edit().putLong(KEY_CURSOR, cursor).apply();
                    continue;
                }

                if (sendToPlatform(epc, readTimeMs, rssi, seq)) {
                    totalSent++;
                    pending.decrementAndGet();
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
            pending.set(0);
            prefs.edit().putLong(KEY_CURSOR, 0).apply();
            notifyStatus(totalSent, 0);
        }

        // Close Rufus session when queue is empty
        RufusClient rc = RufusClient.getInstance();
        if (rc.getTokenSession() != null && pendingCount() == 0) {
            rc.doFinalClose();
        }
    }

    // epoch ms field (new lines); falls back to the formatted timestamp for
    // lines queued by older builds. Returns 0 if neither is usable.
    private long resolveReadTime(String epochStr, String queuedTs) {
        if (epochStr != null && !epochStr.isEmpty()) {
            try {
                long v = Long.parseLong(epochStr.trim());
                if (v > 0) return v;
            } catch (NumberFormatException ignored) {}
        }
        Date d = parseQueuedTs(queuedTs);
        return d != null ? d.getTime() : 0;
    }

    private boolean sendToPlatform(String epc, long readTimeMs, String rssi, long seq) {
        String platform = prefs.getString("selected_platform", "off");
        if ("off".equals(platform)) return true;
        if ("wiclax".equals(platform)) return sendWiclax(epc, readTimeMs, seq);
        if ("rufus".equals(platform)) {
            if (RufusClient.getInstance().getTokenSession() == null) {
                RufusClient.getInstance().createSession();
                return false; // will retry next cycle when session is ready
            }
            return sendRufus(epc, readTimeMs);
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

    private boolean sendWiclax(String epc, long readTimeMs, long seq) {
        String url = prefs.getString("server_url", "http://wiclax.com:35014");
        String user = prefs.getString("user", "");
        String deviceId = prefs.getString("device_id", "");
        if (user.isEmpty() || deviceId.isEmpty()) {
            Log.w(TAG, "Wiclax skip: user/deviceId not set, passing dropped epc=" + epc);
            return true;
        }

        // Wiclax interprets the timestamp in its own event/PC timezone. If that
        // differs from the device's (e.g. device UTC+6:00, Wiclax UTC+5:30) the
        // passing shows shifted, so apply the configured offset in minutes.
        int offsetMin = prefs.getInt(KEY_WICLAX_OFFSET_MIN, 0);
        long sendMs = readTimeMs + offsetMin * 60_000L;
        String ts = new SimpleDateFormat(WICLAX_TS_FORMAT, Locale.US).format(new Date(sendMs));
        // Relay format "@EPC@yyyyMMddHHmmssSSS" (same as the desktop app). The
        // generic-acquisition ';' line from the Wiclax TCP spec is not what this
        // HTTP relay expects.
        // Fields are '@'-separated: SEQ@EPC@TIMESTAMP. Wiclax shows the first
        // field as the sequential number (it showed 0 when it was empty).
        String passingData = (seq > 0 ? String.valueOf(seq) : "") + "@" + epc + "@" + ts;
        try {
            String fullUrl = url + "?user=" + URLEncoder.encode(user, "UTF-8")
                    + "&deviceId=" + URLEncoder.encode(deviceId, "UTF-8")
                    // %20 not '+': the timestamp has a space and not every
                    // server decodes '+' as space in the query string
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
            WiclaxClient.getInstance().reportSendResult(ok, info);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "Wiclax fail: " + e.getMessage());
            WiclaxClient.getInstance().reportSendResult(false, "Send failed");
            return false;
        }
    }

    private boolean sendRufus(String epc, long readTimeMs) {
        String apiKey = prefs.getString("rufus_api_key", "");
        String tokenSession = RufusClient.getInstance().getTokenSession();
        if (apiKey.isEmpty()) { Log.w(TAG, "Rufus skip: no api key"); return false; }
        if (tokenSession == null) { Log.w(TAG, "Rufus skip: no session"); return false; }

        final int num = passingCounter.incrementAndGet();

        try {
            // API expects RFC 3339; convert device-local read time to real UTC
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));

            org.json.JSONObject passing = new org.json.JSONObject();
            passing.put("timestamp", iso.format(new Date(readTimeMs)));
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
