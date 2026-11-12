package com.tas.racetiming.scan;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.util.Log;

import com.tas.racetiming.model.SessionData;
import com.tas.racetiming.model.TagRead;
import com.tas.racetiming.network.UploadManager;
import com.tas.racetiming.tools.BibMap;
import com.tas.racetiming.tools.FileUtils;
import com.tas.racetiming.tools.PassingFormat;
import com.tas.racetiming.uhf.UHFManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns scanning and the read pipeline (CSV log + upload queue + live stats)
 * for the whole process. It used to live in DashboardFragment, which meant
 * reads were only handled while that screen's view existed. The dashboard is
 * now just a listener, and ScanService keeps the process alive.
 *
 * All methods must be called on the main thread.
 */
public class ScanController {
    private static final String TAG = "ScanController";
    public static final String PREFS = "tas_timer_settings";
    public static final String KEY_MIN_RSSI = "min_rssi";
    public static final String KEY_TRIGGER_HOLD = "trigger_hold";
    /** rows kept for the live feed */
    public static final int FEED_MAX_ROWS = 50;
    /** RSSI column value for a passing typed in by hand */
    public static final String RSSI_MANUAL = "MANUAL";

    public interface Listener {
        /** A read, marker or clear changed the feed/counters. */
        void onReadsChanged();

        void onScanStateChanged(boolean scanning);
    }

    /** Running totals for one tag (per-tag view). */
    public static class TagStat {
        public final String epc;
        public long lastMs;
        public int count;
        public double bestRssi = Double.NaN;

        TagStat(String epc) {
            this.epc = epc;
        }
    }

    private static ScanController instance;

    /** True while MainActivity is in front (set from its onResume/onPause). */
    public static volatile boolean uiResumed = false;

    private Context app;
    private final SessionData session = new SessionData();
    private final LinkedList<TagRead> recentReads = new LinkedList<>();
    // insertion order = least recently seen first (a tag is re-inserted on each read)
    private final LinkedHashMap<String, TagStat> stats = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private ToneGenerator toneGenerator;
    private long lastBeepMs = 0;

    public static synchronized ScanController getInstance() {
        if (instance == null) instance = new ScanController();
        return instance;
    }

    private ScanController() {}

    public void init(Context context) {
        if (app == null) app = context.getApplicationContext();
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public boolean isScanning() {
        return session.isScanning();
    }

    public long getTotalReads() {
        return session.getTotalReads();
    }

    public int getUniqueTagCount() {
        return session.getUniqueTagCount();
    }

    /** Newest first. */
    public List<TagRead> getRecentReads() {
        return recentReads;
    }

    /** Per-tag totals, most recently seen first, at most max entries. */
    public List<TagStat> getTagStats(int max) {
        ArrayList<TagStat> all = new ArrayList<>(stats.values());
        ArrayList<TagStat> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < max; i--) out.add(all.get(i));
        return out;
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, 0);
    }

    /** Push the saved reader settings to the module (they used to be applied
     *  only once the Settings screen had been opened). */
    public void applyReaderSettings() {
        if (app == null) return;
        SharedPreferences p = prefs();
        UHFManager uhf = UHFManager.getInstance();
        uhf.setPower(p.getInt("power", 26));
        uhf.setEpcFilter(p.getString("epc_prefix", ""), p.getBoolean("epc_filter", false));
        uhf.setMinRssi(p.getInt(KEY_MIN_RSSI, 0));
    }

    /** Starts scanning. Returns false if the reader could not be started. */
    public boolean start() {
        if (app == null) return false;
        if (session.isScanning()) return true;
        UHFManager uhf = UHFManager.getInstance();
        if (!uhf.isInitialized()) uhf.init(app);
        applyReaderSettings();
        uhf.setTagCallback(this::onTagRead);
        if (!uhf.startInventory()) {
            Log.e(TAG, "startInventory failed");
            return false;
        }
        session.start();
        ScanService.start(app); // keep the process alive while scanning
        for (Listener l : listeners) l.onScanStateChanged(true);
        return true;
    }

    public void stop() {
        if (!session.isScanning()) return;
        session.stop();
        UHFManager.getInstance().stopInventory();
        if (!uiResumed && app != null) {
            // the app is in the background: give the trigger back to the
            // system scanner now that our scan has ended
            try {
                com.rscja.scanner.utility.ScannerUtility.getScannerInerface().open(app);
            } catch (Throwable t) {
                Log.w(TAG, "Keyboard emulator open failed: " + t.getMessage());
            }
        }
        for (Listener l : listeners) l.onScanStateChanged(false);
    }

    /** Clears the feed and counters (does not touch the CSV log or the queue). */
    public void clear() {
        recentReads.clear();
        stats.clear();
        session.reset();
        for (Listener l : listeners) l.onReadsChanged();
    }

    private void onTagRead(String epc, String rssi, String user, long readTimeMs) {
        record(epc, rssi, readTimeMs);
        playBeep();
    }

    /**
     * Records a passing typed in by hand, stamped with the current time.
     * The typed bib is turned into a chip code using, in order: the bib list,
     * a tag already seen in this session with that bib, else the text as typed.
     * Returns the chip code used.
     */
    public String manualRead(String typed) {
        String bib = typed == null ? "" : typed.trim();
        if (bib.isEmpty()) return null;
        String epc = BibMap.epcForBib(bib);
        if (epc == null) {
            for (TagStat s : stats.values()) {
                if (bib.equals(BibMap.display(s.epc))) {
                    epc = s.epc;
                    break;
                }
            }
        }
        if (epc == null) epc = bib;
        record(epc, RSSI_MANUAL, System.currentTimeMillis());
        return epc;
    }

    /** Records the gun start: logged locally and shown in the feed, not uploaded. */
    public void markStart() {
        long now = System.currentTimeMillis();
        FileUtils.writeMarker("START", now);
        pushFeed(new TagRead("", "▶ START", feedTime(now), ""));
        for (Listener l : listeners) l.onReadsChanged();
    }

    private void record(String epc, String rssi, long readTimeMs) {
        session.incrementReads(epc);

        TagStat s = stats.remove(epc);
        if (s == null) s = new TagStat(epc);
        s.lastMs = readTimeMs;
        s.count++;
        double v = PassingFormat.parseRssi(rssi);
        if (!Double.isNaN(v) && (Double.isNaN(s.bestRssi) || v > s.bestRssi)) s.bestRssi = v;
        stats.put(epc, s); // re-insert: now the most recently seen

        pushFeed(new TagRead(epc, BibMap.display(epc), feedTime(readTimeMs), rssi));

        FileUtils.writeTagToCsv(epc, rssi, readTimeMs);
        UploadManager.getInstance().enqueue(epc, rssi, readTimeMs);

        for (Listener l : listeners) l.onReadsChanged();
    }

    private void pushFeed(TagRead read) {
        recentReads.addFirst(read);
        if (recentReads.size() > FEED_MAX_ROWS) recentReads.removeLast();
    }

    public static String feedTime(long ms) {
        return new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date(ms));
    }

    private void playBeep() {
        long now = System.currentTimeMillis();
        if (now - lastBeepMs < 80) return;
        lastBeepMs = now;
        try {
            if (toneGenerator == null) {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            }
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 60);
        } catch (Exception ignored) {}
    }
}
