package com.example.aud_player;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exports/imports the app's complete SharedPreferences state
 * ("audio_player_prefs") to named JSON backup files in the .aud_player
 * folder so settings survive an uninstall + reinstall.
 *
 * Backup locations (tried in order):
 *  1. <shared storage>/.aud_player/<name>.json  — direct file write;
 *     works with All files access (MANAGE_EXTERNAL_STORAGE, which the app
 *     already requests) or legacy storage.
 *  2. Download/.aud_player via MediaStore (API 29+) — no permission needed;
 *     also survives uninstall.
 *  3. App-specific external files dir — last resort only; this one does NOT
 *     survive uninstall, so the user is warned.
 *
 * Backup format: {"app": "aud_player", "schema": 1, "exportedAt": ...,
 *                 "prefs": {key: {"type": "...", "value": ...}}}
 */
public final class SettingsBackupManager {

    private static final String PREFS_NAME = "audio_player_prefs";
    private static final String BACKUP_DIR = ".aud_player";
    private static final String FILE_EXTENSION = ".json";
    private static final String DEFAULT_BACKUP_NAME = "settings_backup";
    private static final String APP_ID = "aud_player";
    private static final int SCHEMA_VERSION = 1;
    private static final String MEDIASTORE_RELATIVE_PATH = "Download/" + BACKUP_DIR;

    /** One selectable backup file shown in the import list. */
    public static class BackupEntry {
        public final String displayName; // file name without extension
        public final long lastModified;  // epoch millis, 0 when unknown
        final File file;                 // direct file source, or null
        final Uri uri;                   // MediaStore source, or null

        BackupEntry(String displayName, long lastModified, File file, Uri uri) {
            this.displayName = displayName;
            this.lastModified = lastModified;
            this.file = file;
            this.uri = uri;
        }
    }

    private SettingsBackupManager() {
        // utility class
    }

    /**
     * Exports all current preferences under the given file name.
     * Returns null on success or a human-readable error message.
     */
    public static String exportSettings(Context context, String name) {
        String fileName = sanitizeFileName(name) + FILE_EXTENSION;
        String json;
        try {
            json = buildBackupJson(context);
        } catch (Exception e) {
            return "Failed to collect settings: " + e.getMessage();
        }

        // 1. Direct write to <shared storage>/.aud_player/
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), BACKUP_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new SecurityException("Cannot create " + BACKUP_DIR);
            }
            File file = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            return null;
        } catch (Exception ignored) {
            // fall through to MediaStore
        }

        // 2. MediaStore Downloads (API 29+) — survives uninstall, no
        //    storage permission required
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                deleteMediaStoreBackup(context, fileName);
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, MEDIASTORE_RELATIVE_PATH);
                Uri uri = context.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (FileOutputStream fos =
                                 (FileOutputStream) context.getContentResolver()
                                         .openOutputStream(uri)) {
                        fos.write(json.getBytes(StandardCharsets.UTF_8));
                        fos.getFD().sync();
                    }
                    return null;
                }
            } catch (Exception ignored) {
                // fall through to app-specific dir
            }
        }

        // 3. Last resort: app-specific external dir (does NOT survive
        //    uninstall — the caller warns the user about this)
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                File file = new File(dir, fileName);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(json.getBytes(StandardCharsets.UTF_8));
                    fos.getFD().sync();
                }
                return "warning:app-specific";
            }
        } catch (Exception ignored) {
        }
        return "Could not write the backup file. Check storage permission and try again.";
    }

    /**
     * Lists every backup file the app can find, newest first. Sources:
     * shared storage .aud_player/, MediaStore Download/.aud_player/ and
     * the app-specific dir (last resort exports).
     */
    public static List<BackupEntry> listBackups(Context context) {
        List<BackupEntry> entries = new ArrayList<>();

        // 1. Direct files in <shared storage>/.aud_player/
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), BACKUP_DIR);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.canRead() && f.getName().endsWith(FILE_EXTENSION)) {
                        entries.add(new BackupEntry(
                                stripExtension(f.getName()), f.lastModified(), f, null));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 2. MediaStore Download/.aud_player (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                        + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
                try (Cursor cursor = context.getContentResolver().query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{
                                MediaStore.MediaColumns._ID,
                                MediaStore.MediaColumns.DISPLAY_NAME,
                                MediaStore.MediaColumns.DATE_MODIFIED},
                        selection,
                        new String[]{MEDIASTORE_RELATIVE_PATH + "%", "%" + FILE_EXTENSION},
                        null)) {
                    if (cursor != null) {
                        while (cursor.moveToNext()) {
                            String displayName =
                                    stripExtension(cursor.getString(1));
                            // MediaStore may report duplicates of direct files;
                            // skip names already found via direct access
                            if (containsName(entries, displayName)) continue;
                            long modified = cursor.getLong(2) * 1000L;
                            Uri uri = Uri.withAppendedPath(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    String.valueOf(cursor.getLong(0)));
                            entries.add(new BackupEntry(displayName, modified, null, uri));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 3. App-specific external dir
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && f.canRead() && f.getName().endsWith(FILE_EXTENSION)
                                && !containsName(entries, stripExtension(f.getName()))) {
                            entries.add(new BackupEntry(
                                    stripExtension(f.getName()), f.lastModified(), f, null));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Newest first
        entries.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));
        return entries;
    }

    /**
     * Imports preferences from the selected backup. The backup is fully
     * parsed and validated BEFORE anything is written, so a corrupt file
     * never damages existing settings. Returns null on success or an
     * error message on failure.
     */
    public static String importSettings(Context context, BackupEntry entry) {
        if (entry == null) {
            return "No backup selected";
        }
        String json = readBackupJson(context, entry);
        if (json == null) {
            return "Could not read the backup file";
        }

        JSONObject root;
        JSONObject prefsJson;
        try {
            root = new JSONObject(json);
            if (!APP_ID.equals(root.optString("app"))) {
                return "This file is not an Aud Player settings backup";
            }
            int schema = root.optInt("schema", -1);
            if (schema < 1 || schema > SCHEMA_VERSION) {
                return "Unsupported backup version (schema " + schema + ")";
            }
            prefsJson = root.optJSONObject("prefs");
            if (prefsJson == null) {
                return "Backup file contains no settings";
            }
        } catch (Exception e) {
            return "Backup file is corrupted or invalid";
        }

        // Parse everything into a map first; only on full success do we
        // touch the real preferences
        Map<String, Object> parsed = new HashMap<>();
        try {
            java.util.Iterator<String> keys = prefsJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject entryJson = prefsJson.optJSONObject(key);
                if (entryJson == null) continue;
                String type = entryJson.optString("type");
                switch (type) {
                    case "boolean":
                        parsed.put(key, entryJson.getBoolean("value"));
                        break;
                    case "int":
                        parsed.put(key, entryJson.getInt("value"));
                        break;
                    case "long":
                        parsed.put(key, entryJson.getLong("value"));
                        break;
                    case "float":
                        parsed.put(key, (float) entryJson.getDouble("value"));
                        break;
                    case "string":
                        parsed.put(key, entryJson.getString("value"));
                        break;
                    case "stringset": {
                        JSONArray arr = entryJson.getJSONArray("value");
                        Set<String> set = new HashSet<>();
                        for (int i = 0; i < arr.length(); i++) {
                            set.add(arr.getString(i));
                        }
                        parsed.put(key, set);
                        break;
                    }
                    default:
                        // Unknown type: skip that key rather than failing
                        break;
                }
            }
        } catch (Exception e) {
            return "Backup file is corrupted or invalid";
        }

        if (parsed.isEmpty()) {
            return "Backup file contains no settings";
        }

        // Everything validated — now apply
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        for (Map.Entry<String, Object> e : parsed.entrySet()) {
            Object value = e.getValue();
            if (value instanceof Boolean) editor.putBoolean(e.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(e.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(e.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(e.getKey(), (Float) value);
            else if (value instanceof String) editor.putString(e.getKey(), (String) value);
            else if (value instanceof Set) {
                //noinspection unchecked
                editor.putStringSet(e.getKey(), (Set<String>) value);
            }
        }
        editor.apply();
        return null;
    }

    /** Turns a user-typed name into a safe file name. */
    private static String sanitizeFileName(String name) {
        if (name == null) return DEFAULT_BACKUP_NAME;
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty()) return DEFAULT_BACKUP_NAME;
        if (cleaned.length() > 60) cleaned = cleaned.substring(0, 60);
        return cleaned;
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) return "";
        int idx = fileName.toLowerCase().lastIndexOf(FILE_EXTENSION);
        return idx > 0 ? fileName.substring(0, idx) : fileName;
    }

    private static boolean containsName(List<BackupEntry> entries, String displayName) {
        for (BackupEntry e : entries) {
            if (e.displayName.equals(displayName)) return true;
        }
        return false;
    }

    private static String buildBackupJson(Context context) throws Exception {
        Map<String, ?> all = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getAll();

        JSONObject prefsJson = new JSONObject();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            JSONObject item = new JSONObject();
            if (value instanceof Boolean) {
                item.put("type", "boolean");
                item.put("value", (Boolean) value);
            } else if (value instanceof Integer) {
                item.put("type", "int");
                item.put("value", (Integer) value);
            } else if (value instanceof Long) {
                item.put("type", "long");
                item.put("value", (Long) value);
            } else if (value instanceof Float) {
                item.put("type", "float");
                item.put("value", (Double) ((Float) value).doubleValue());
            } else if (value instanceof String) {
                item.put("type", "string");
                item.put("value", (String) value);
            } else if (value instanceof Set) {
                item.put("type", "stringset");
                JSONArray arr = new JSONArray();
                for (Object s : (Set<?>) value) {
                    arr.put(String.valueOf(s));
                }
                item.put("value", arr);
            } else {
                continue; // unknown type: skip
            }
            prefsJson.put(key, item);
        }

        JSONObject root = new JSONObject();
        root.put("app", APP_ID);
        root.put("schema", SCHEMA_VERSION);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("prefs", prefsJson);
        return root.toString(2);
    }

    private static String readBackupJson(Context context, BackupEntry entry) {
        try {
            if (entry.file != null && entry.file.canRead()) {
                return readFileToString(entry.file);
            }
            if (entry.uri != null) {
                try (InputStream is = context.getContentResolver().openInputStream(entry.uri)) {
                    if (is != null) {
                        return readStreamToString(is);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String readFileToString(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            return readStreamToString(in);
        }
    }

    private static String readStreamToString(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Removes an older MediaStore backup with the same file name. */
    private static void deleteMediaStoreBackup(Context context, String fileName) {
        try {
            String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                    + MediaStore.MediaColumns.DISPLAY_NAME + " = ?";
            context.getContentResolver().delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    selection,
                    new String[]{MEDIASTORE_RELATIVE_PATH + "%", fileName});
        } catch (Exception ignored) {
        }
    }
}
