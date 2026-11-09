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
}
