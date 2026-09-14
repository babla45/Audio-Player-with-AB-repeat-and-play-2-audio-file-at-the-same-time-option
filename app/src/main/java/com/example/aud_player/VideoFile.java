package com.example.aud_player;

import android.net.Uri;

import java.util.Locale;

public class VideoFile {
    private final String title;
    private final long durationMs;
    private final String duration;
    private final Uri uri;
    private final long id;
    private final long fileSize;
    private final String formattedSize;
    private final long dateAdded;
    private final String path;

    public VideoFile(String title, long durationMs, Uri uri, long id, long fileSize, long dateAdded, String path) {
        this.title = title != null ? title : "Video";
        this.durationMs = durationMs;
        this.duration = formatDuration(durationMs);
        this.uri = uri;
        this.id = id;
        this.fileSize = fileSize;
        this.formattedSize = formatFileSize(fileSize);
        this.dateAdded = dateAdded;
        this.path = path;
    }

    public String getTitle() {
        return title;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getDuration() {
        return duration;
    }

    public Uri getUri() {
        return uri;
    }

    public long getId() {
        return id;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getFormattedSize() {
        return formattedSize;
    }

    public long getDateAdded() {
        return dateAdded;
    }

    public String getPath() {
        return path;
    }

    public boolean matchesSearch(String lowerQuery) {
        if (lowerQuery == null || lowerQuery.isEmpty()) return true;
        return title.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "00:00";
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
