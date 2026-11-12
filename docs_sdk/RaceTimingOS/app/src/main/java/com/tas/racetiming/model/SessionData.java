package com.tas.racetiming.model;

import java.util.LinkedHashSet;
import java.util.Set;

public class SessionData {
    private long totalReads = 0;
    private final Set<String> uniqueTags = new LinkedHashSet<>();
    private long sessionStartTimeMs = 0;
    private long sessionStopTimeMs = 0;
    private boolean isScanning = false;

    public void incrementReads(String epc) {
        totalReads++;
        uniqueTags.add(epc);
    }

    public long getTotalReads() {
        return totalReads;
    }

    public int getUniqueTagCount() {
        return uniqueTags.size();
    }

    public void start() {
        sessionStartTimeMs = System.currentTimeMillis();
        sessionStopTimeMs = 0;
        isScanning = true;
        // counters are NOT reset here: with hold-to-scan every trigger press
        // starts a scan, and the totals must keep running. CLEAR resets them.
    }

    public void stop() {
        isScanning = false;
        sessionStopTimeMs = System.currentTimeMillis();
    }

    public void reset() {
        totalReads = 0;
        uniqueTags.clear();
        sessionStartTimeMs = 0;
        sessionStopTimeMs = 0;
    }

    public boolean isScanning() {
        return isScanning;
    }

    public String getElapsedTimeFormatted() {
        if (sessionStartTimeMs == 0) return "--:--:--";
        long end = (isScanning || sessionStopTimeMs == 0)
                ? System.currentTimeMillis() : sessionStopTimeMs; // freeze when stopped
        long elapsed = end - sessionStartTimeMs;
        long hours = elapsed / 3600000;
        long mins = (elapsed % 3600000) / 60000;
        long secs = (elapsed % 60000) / 1000;
        return String.format("%02d:%02d:%02d", hours, mins, secs);
    }
}
