package com.tas.racetiming.tools;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Text formats used when showing or uploading a passing. Pure Java. */
public final class PassingFormat {
    private PassingFormat() {}

    /**
     * Wiclax HTTP relay passing: SEQ@EPC@yyyyMMddHHmmssSSS.
     * SEQ is left empty when there is no sequence number (Wiclax then shows 0).
     */
    public static String wiclax(long seq, String epc, long readTimeMs) {
        String ts = new SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).format(new Date(readTimeMs));
        return (seq > 0 ? String.valueOf(seq) : "") + "@" + epc + "@" + ts;
    }

    /**
     * Short form of a chip code for display: "000000000000000000000102" -> "102".
     * Codes that are not a zero-padded number of up to 6 digits are shortened
     * to their last 8 characters. Uploads always use the full EPC.
     */
    public static String shortBib(String epc) {
        if (epc == null || epc.isEmpty()) return "";
        if (epc.matches("0*[0-9]{1,6}")) {
            String s = epc.replaceFirst("^0+", "");
            return s.isEmpty() ? "0" : s;
        }
        return epc.length() > 10 ? "…" + epc.substring(epc.length() - 8) : epc;
    }

    /** Parses a reader RSSI string such as "-54.70"; NaN if empty or not a number. */
    public static double parseRssi(String rssi) {
        if (rssi == null) return Double.NaN;
        try {
            return Double.parseDouble(rssi.trim().replace(',', '.'));
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
