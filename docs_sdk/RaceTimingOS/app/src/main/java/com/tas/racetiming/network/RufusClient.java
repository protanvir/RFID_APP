package com.tas.racetiming.network;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RufusClient {
    private static final String TAG = "RufusClient";

    private String apiKey;
    private String serverUrl;
    private int port;
    private boolean configured = false;

    public void configure(String apiKey, String serverUrl, int port) {
        this.apiKey = apiKey;
        this.serverUrl = serverUrl;
        this.port = port;
        this.configured = apiKey != null && !apiKey.isEmpty()
                && serverUrl != null && !serverUrl.isEmpty();
    }

    public boolean isConfigured() {
        return configured;
    }

    public void sendPassing(String epc, String rssi) {
        if (!configured) return;
        // TODO: Implement Rufus REST API client
        // POST https://api.runonrufus.com/v0/passings
        // Headers: api_key: <key>
        // Body: { token_session: "...", passings_list: [{ timestamp, num_passing, bib, chip }] }
        Log.d(TAG, "Rufus sendPassing stub: " + epc + " rssi=" + rssi);
    }

    public void reportTelemetry(int batteryPct, boolean charging, double latitude, double longitude) {
        if (!configured) return;
        // TODO: Implement telemetry reporting
        // PATCH /devices/{deviceid}/status
    }
}
