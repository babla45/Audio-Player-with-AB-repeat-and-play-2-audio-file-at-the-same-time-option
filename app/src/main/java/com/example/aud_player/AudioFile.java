package com.example.aud_player;

import android.net.Uri;

public class AudioFile {
    private String title;
    private String duration;
    private Uri uri;
    private long id;
    private long fileSize;
    private String formattedSize;

    public AudioFile(String title, String duration, Uri uri, long id, long fileSize) {
        this.title = title;
        this.duration = duration;
        this.uri = uri;
        this.id = id;
        this.fileSize = fileSize;
        this.formattedSize = formatFileSize(fileSize);
    }

    public String getTitle() {
        return title;
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
    
    private String formatFileSize(long sizeInBytes) {
        if (sizeInBytes <= 0) return "0 B";
        
        final String[] units = new String[] { "B", "KB", "MB", "GB", "TB" };
        int digitGroups = (int) (Math.log10(sizeInBytes) / Math.log10(1024));
        
        if (digitGroups >= 2) {
            return String.format("%.2f %s", sizeInBytes / Math.pow(1024, digitGroups), units[digitGroups]);
        } else {
            return String.format("%.0f %s", sizeInBytes / Math.pow(1024, digitGroups), units[digitGroups]);
        }
    }
} 