package com.tas.racetiming.uhf;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.InventoryParameter;
import com.rscja.deviceapi.entity.UHFTAGInfo;
import com.rscja.deviceapi.interfaces.IUHFInventoryCallback;

public class UHFManager {
    private static final String TAG = "UHFManager";
    private static UHFManager instance;

    private RFIDWithUHFUART reader;
    private boolean isInitialized = false;
    private int powerLevel = 26;
    private String epcFilter = "";
    private boolean epcFilterEnabled = false;
    private TagCallback tagCallback;
    private boolean isScanning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface TagCallback {
        void onTagRead(String epc, String rssi, String user);
    }

    public static synchronized UHFManager getInstance() {
        if (instance == null) {
            instance = new UHFManager();
        }
        return instance;
    }

    public boolean init(Activity activity) {
        if (isInitialized) return true;
        try {
            reader = RFIDWithUHFUART.getInstance();
            isInitialized = reader.init(activity);
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
                if (uhftagInfo == null || tagCallback == null) return;
                String epc = uhftagInfo.getEPC();
                String rssi = uhftagInfo.getRssi();
                if (epc == null || epc.isEmpty()) return;
                if (epcFilterEnabled && !epc.startsWith(epcFilter)) return;

                String user = uhftagInfo.getUser();
                final String epcFinal = epc;
                final String rssiFinal = rssi != null ? rssi : "";
                final String userFinal = user != null ? user : "";
                mainHandler.post(() -> {
                    if (tagCallback != null) {
                        tagCallback.onTagRead(epcFinal, rssiFinal, userFinal);
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
}
