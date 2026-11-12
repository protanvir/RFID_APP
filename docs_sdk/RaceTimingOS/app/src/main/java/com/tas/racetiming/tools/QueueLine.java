package com.tas.racetiming.tools;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One line of the upload queue file (pending.csv).
 *
 * Format: EPC;dd-MM-yyyy HH:mm:ss.SSS;RSSI;epochMs;seq
 *
 * epochMs is the exact instant the reader saw the tag and is what uploads
 * use. The formatted timestamp is kept for humans and as a fallback for lines
 * written by older builds (which had no epochMs / seq fields).
 *
 * Pure Java (no Android classes) so it can be unit tested on the JVM.
 */
public final class QueueLine {
    public static final String TS_FORMAT = "dd-MM-yyyy HH:mm:ss.SSS";

    public final String epc;
    public final String rssi;
    /** Read time, epoch ms. 0 = line has no usable time and must not be uploaded. */
    public final long readTimeMs;
    /** Sequential passing number; 0 = none (line from an older build). */
    public final long seq;

    public QueueLine(String epc, String rssi, long readTimeMs, long seq) {
        this.epc = epc != null ? epc : "";
        this.rssi = rssi != null ? rssi : "";
        this.readTimeMs = readTimeMs;
        this.seq = seq;
    }

    public String format() {
        String ts = new SimpleDateFormat(TS_FORMAT, Locale.US).format(new Date(readTimeMs));
        // ';' is the field separator, so it can never appear inside a field
        return epc.replace(";", "") + ";" + ts + ";" + rssi.replace(";", "") + ";" + readTimeMs + ";" + seq;
    }

    /** Returns null for blank lines or lines with no EPC. */
    public static QueueLine parse(String line) {
        if (line == null) return null;
        line = line.trim();
        if (line.isEmpty()) return null;
        String[] p = line.split(";", -1);
        String epc = p[0].trim();
        if (epc.isEmpty()) return null;
        String ts = p.length > 1 ? p[1] : "";
        String rssi = p.length > 2 ? p[2] : "";
        long epoch = p.length > 3 ? parseLong(p[3]) : 0;
        long seq = p.length > 4 ? parseLong(p[4]) : 0;
        if (epoch <= 0) epoch = parseTs(ts);
        return new QueueLine(epc, rssi, epoch, Math.max(0, seq));
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Parses the formatted timestamp; 0 if missing or corrupt. */
    public static long parseTs(String ts) {
        if (ts == null || ts.trim().isEmpty()) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat(TS_FORMAT, Locale.US);
            f.setLenient(false);
            Date d = f.parse(ts.trim());
            return d != null ? d.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
