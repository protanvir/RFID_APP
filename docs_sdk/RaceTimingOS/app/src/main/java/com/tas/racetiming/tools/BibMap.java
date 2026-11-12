package com.tas.racetiming.tools;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Optional chip-to-bib list, loaded from /sdcard/racetiming/bibs.csv.
 * One "EPC,BIB" (or "EPC;BIB") pair per line; a header line is ignored.
 * Only used for display and manual entry; uploads always send the EPC.
 */
public final class BibMap {
    private static final String TAG = "BibMap";
    public static final String FILE = "/sdcard/racetiming/bibs.csv";

    private static volatile Map<String, String> epcToBib = new HashMap<>();
    private static volatile Map<String, String> bibToEpc = new HashMap<>();

    private BibMap() {}

    /** Reloads the file. Returns the number of pairs loaded (0 if no file). */
    public static int load() {
        Map<String, String> e2b = new HashMap<>();
        Map<String, String> b2e = new HashMap<>();
        File f = new File(FILE);
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.replace("﻿", "").trim();
                    if (line.isEmpty()) continue;
                    String[] p = line.split("[,;\t]");
                    if (p.length < 2) continue;
                    String epc = p[0].trim().toUpperCase();
                    String bib = p[1].trim();
                    // skip a header row such as "EPC,BIB"
                    if (epc.isEmpty() || bib.isEmpty() || !epc.matches("[0-9A-F]+")) continue;
                    e2b.put(epc, bib);
                    b2e.put(bib, epc);
                }
            } catch (Exception ex) {
                Log.e(TAG, "load error: " + ex.getMessage());
            }
        }
        epcToBib = e2b;
        bibToEpc = b2e;
        return e2b.size();
    }

    public static int size() {
        return epcToBib.size();
    }

    /** Bib to show for a chip: the list entry if there is one, else the short form. */
    public static String display(String epc) {
        if (epc == null) return "";
        String bib = epcToBib.get(epc.toUpperCase());
        return bib != null ? bib : PassingFormat.shortBib(epc);
    }

    /** Chip code for a typed bib, or null if the list has no entry for it. */
    public static String epcForBib(String bib) {
        return bib == null ? null : bibToEpc.get(bib.trim());
    }
}
