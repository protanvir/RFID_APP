package com.tas.racetiming.tools;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileUtils {
    private static final String TAG = "FileUtils";
    private static final String BASE_DIR = "/sdcard/racetiming/";

    // Tag reads arrive on the main thread at burst rates; file I/O there risks ANR
    private static final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "CsvWriter"));

    public static String getDailyFileName() {
        String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        return BASE_DIR + "racetiming_" + date + ".csv";
    }

    public static void ensureDirExists() {
        File dir = new File(BASE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public static void writeTagToCsv(String epc, String rssi, long readTimeMs) {
        final Date readTime = new Date(readTimeMs); // time the reader saw the tag
        ioExecutor.execute(() -> writeTagLine(epc, rssi, readTime));
    }

    private static void writeTagLine(String epc, String rssi, Date readTime) {
        try {
            ensureDirExists();
            File file = new File(getDailyFileName());
            boolean isNew = !file.exists();

            SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss.SSS", Locale.US);
            String timestamp = sdf.format(readTime);

            String line = epc + ";" + timestamp + ";" + rssi + ";;;0\r\n";

            FileOutputStream fos = new FileOutputStream(file, true);
            OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            if (isNew) {
                writer.write("\uFEFF"); // BOM for Excel compat
            }
            writer.write(line);
            writer.flush();
            writer.close();
            fos.close();

        } catch (Exception e) {
            Log.e(TAG, "writeTagToCsv error: " + e.getMessage());
        }
    }

    /** Daily log file for the day containing the given time. */
    public static String getDailyFileName(long timeMs) {
        String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(timeMs));
        return BASE_DIR + "racetiming_" + date + ".csv";
    }

    /**
     * Records a race marker (e.g. the gun start) in markers_YYYYMMDD.csv as
     * "LABEL;dd-MM-yyyy HH:mm:ss.SSS". Kept out of the reads log so that file
     * stays one tag read per line. Markers are not uploaded.
     */
    public static void writeMarker(String label, long timeMs) {
        ioExecutor.execute(() -> {
            try {
                ensureDirExists();
                String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(timeMs));
                String ts = new SimpleDateFormat(QueueLine.TS_FORMAT, Locale.US).format(new Date(timeMs));
                try (FileOutputStream fos = new FileOutputStream(BASE_DIR + "markers_" + date + ".csv", true)) {
                    fos.write((label + ";" + ts + "\r\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                Log.e(TAG, "writeMarker error: " + e.getMessage());
            }
        });
    }

    /**
     * Reads logged tag reads with fromMs <= read time <= toMs from the daily
     * log of the day containing fromMs. Blocking; call off the main thread.
     */
    public static java.util.List<QueueLine> readLogRange(long fromMs, long toMs) {
        java.util.List<QueueLine> out = new java.util.ArrayList<>();
        File f = new File(getDailyFileName(fromMs));
        if (!f.exists()) return out;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                QueueLine ql = QueueLine.parse(line.replace("﻿", ""));
                if (ql == null || ql.readTimeMs <= 0) continue;
                if (ql.readTimeMs >= fromMs && ql.readTimeMs <= toMs) out.add(ql);
            }
        } catch (Exception e) {
            Log.e(TAG, "readLogRange error: " + e.getMessage());
        }
        return out;
    }

    /**
     * Deletes daily read logs and marker files older than keepDays. Does
     * nothing when keepDays <= 0 (keep forever). Runs in the background.
     */
    public static void cleanupOldLogs(int keepDays) {
        if (keepDays <= 0) return;
        ioExecutor.execute(() -> {
            try {
                long cutoff = System.currentTimeMillis() - keepDays * 24L * 3600_000L;
                File[] files = new File(BASE_DIR).listFiles();
                if (files == null) return;
                for (File f : files) {
                    String n = f.getName();
                    boolean isLog = n.matches("(racetiming|markers)_\\d{8}\\.csv");
                    if (isLog && f.lastModified() < cutoff) {
                        Log.i(TAG, "deleting old log " + n + ": " + f.delete());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "cleanup error: " + e.getMessage());
            }
        });
    }
}
