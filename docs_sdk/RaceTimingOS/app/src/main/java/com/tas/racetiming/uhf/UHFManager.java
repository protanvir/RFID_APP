package com.tas.racetiming.uhf;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.InventoryParameter;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;
import com.rscja.utility.BatteryUtils;
import com.tas.racetiming.tools.PassingFormat;

public class UHFManager {
    private static final String TAG = "UHFManager";
    private static UHFManager instance;

    private RFIDWithUHFUART reader;
    private boolean isInitialized = false;
    private int powerLevel = 26;
    private String epcFilter = "";
    private boolean epcFilterEnabled = false;
    private volatile int minRssi = 0;
    private TagCallback tagCallback;
    private boolean isScanning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface TagCallback {
        // readTimeMs: wall-clock time the SDK reported the tag (epoch ms)
        void onTagRead(String epc, String rssi, String user, long readTimeMs);
    }

    public static synchronized UHFManager getInstance() {
        if (instance == null) {
            instance = new UHFManager();
        }
        return instance;
    }

    // Takes any Context and keeps only the application context: scanning now
    // outlives the activity (foreground service), so the reader must not hold
    // on to a destroyed Activity.
    public boolean init(Context context) {
        if (isInitialized) return true;
        try {
            reader = RFIDWithUHFUART.getInstance();
            isInitialized = reader.init(context.getApplicationContext());
            if (isInitialized) {
                reader.setPower(powerLevel);
                Log.i(TAG, "UHF initialized, power: " + powerLevel);
            } else {
                Log.e(TAG, "UHF init returned false");
            }
        } catch (Exception e) {
            Log.e(TAG, "UHF init exception: " + e.getMessage());
            isInitialized = false;
        }
        return isInitialized;
    }

    public void free() {
        stopInventory();
        tagCallback = null;
        if (reader != null) {
            reader.free();
            reader = null;
        }
        isInitialized = false;
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public boolean setPower(int level) {
        powerLevel = level;
        if (reader != null && isInitialized) {
            return reader.setPower(level);
        }
        return false;
    }

    public int getPowerLevel() {
        return powerLevel;
    }

    public void setEpcFilter(String prefix, boolean enabled) {
        this.epcFilter = prefix != null ? prefix : "";
        this.epcFilterEnabled = enabled;
    }

    /** Reads weaker than this (dBm, e.g. -70) are ignored; 0 turns the filter off. */
    public void setMinRssi(int dbm) {
        this.minRssi = dbm;
    }

    public void setTagCallback(TagCallback callback) {
        this.tagCallback = callback;
    }

    public boolean startInventory() {
        if (reader == null || !isInitialized) return false;
        if (isScanning) {
            stopInventory();
            SystemClock.sleep(100);
        }

        reader.setInventoryCallback(new IUHFInventoryCallback() {
            @Override
            public void callback(UHFTAGInfo uhftagInfo) {
                // stamp the read here, on the SDK thread, before the main-thread
                // hop, so UI/queue delays never shift the passing time
                final long readTimeMs = System.currentTimeMillis();
                if (uhftagInfo == null || tagCallback == null) return;
                String epc = uhftagInfo.getEPC();
                String rssi = uhftagInfo.getRssi();
                if (epc == null || epc.isEmpty()) return;
                if (epcFilterEnabled && !epc.startsWith(epcFilter)) return;
                if (minRssi != 0) {
                    // a read with no usable RSSI value is kept, not dropped
                    double v = PassingFormat.parseRssi(rssi);
                    if (!Double.isNaN(v) && v < minRssi) return;
                }

                String user = uhftagInfo.getUser();
                final String epcFinal = epc;
                final String rssiFinal = rssi != null ? rssi : "";
                final String userFinal = user != null ? user : "";
                mainHandler.post(() -> {
                    if (tagCallback != null) {
                        tagCallback.onTagRead(epcFinal, rssiFinal, userFinal, readTimeMs);
                    }
                });
            }
        });

        InventoryParameter param = new InventoryParameter();
        isScanning = reader.startInventoryTag(param);
        return isScanning;
    }

    public void stopInventory() {
        isScanning = false;
        tagCallback = null;
        if (reader != null) {
            try { reader.stopInventory(); } catch (Exception ignored) {}
            try { reader.setInventoryCallback(null); } catch (Exception ignored) {}
        }
    }

    public boolean isScanning() {
        return isScanning;
    }

    public String getVersion() {
        try {
            if (reader != null && isInitialized) {
                return reader.getVersion();
            }
        } catch (Exception ignored) {}
        return "";
    }

    public String getHardwareVersion() {
        try {
            if (reader != null && isInitialized) {
                return reader.getHardwareVersion();
            }
        } catch (Exception ignored) {}
        return "";
    }

    public int getBatteryPercentage() {
        return -1;
    }

    public int getTemperature() {
        try {
            if (reader != null && isInitialized) {
                return reader.getTemperature();
            }
        } catch (Exception ignored) {}
        return Integer.MIN_VALUE;
    }

    public int getBatteryHealth() {
        try {
            return BatteryUtils.getBatteryHealth();
        } catch (Exception ignored) {}
        return -1;
    }
}
