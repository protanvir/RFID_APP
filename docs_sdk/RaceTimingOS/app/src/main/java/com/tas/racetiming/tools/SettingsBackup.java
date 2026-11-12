package com.tas.racetiming.tools;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

/**
 * Saves / restores all app settings to a JSON file on shared storage, so a
 * reinstall or a second device does not have to be set up by hand. Includes
 * the sequence counters, so numbering carries on instead of restarting at 1.
 *
 * The file holds the Rufus API key in plain text; treat it like a password.
 */
public final class SettingsBackup {
    public static final String FILE = "/sdcard/racetiming/settings_backup.json";
    // the upload cursor points into this device's queue file; never copy it
    private static final String SKIP_KEY = "upload_cursor_pos";

    private SettingsBackup() {}

    /** Returns the number of settings written. */
    public static int exportTo(SharedPreferences prefs) throws Exception {
        JSONObject root = new JSONObject();
        int n = 0;
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (SKIP_KEY.equals(e.getKey())) continue;
            Object v = e.getValue();
            String type = v instanceof Boolean ? "bool" : v instanceof Integer ? "int"
                    : v instanceof Long ? "long" : v instanceof Float ? "float"
                    : v instanceof String ? "string" : null;
            if (type == null) continue;
            JSONObject item = new JSONObject();
            item.put("type", type);
            item.put("value", v);
            root.put(e.getKey(), item);
            n++;
        }
        File f = new File(FILE);
        File dir = f.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        return n;
    }

    /** Returns the number of settings restored. Throws if the file is missing or invalid. */
    public static int importFrom(SharedPreferences prefs) throws Exception {
        File f = new File(FILE);
        if (!f.exists()) throw new Exception("No backup file at " + FILE);
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int off = 0, r;
            while (off < data.length && (r = fis.read(data, off, data.length - off)) > 0) off += r;
        }
        JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
        SharedPreferences.Editor ed = prefs.edit();
        int n = 0;
        for (Iterator<String> it = root.keys(); it.hasNext(); ) {
            String key = it.next();
            if (SKIP_KEY.equals(key)) continue;
            JSONObject item = root.getJSONObject(key);
            switch (item.getString("type")) {
                case "bool": ed.putBoolean(key, item.getBoolean("value")); break;
                case "int": ed.putInt(key, item.getInt("value")); break;
                case "long": ed.putLong(key, item.getLong("value")); break;
                case "float": ed.putFloat(key, (float) item.getDouble("value")); break;
                case "string": ed.putString(key, item.getString("value")); break;
                default: continue;
            }
            n++;
        }
        if (!ed.commit()) throw new Exception("Could not save settings");
        return n;
    }
}
