package com.example.aud_player;

import android.net.Uri;

public class AudioFile {
    private String title;
    private String duration;
    private Uri uri;
    private long id;
    private long fileSize;
    private String formattedSize;
    private long dateAdded;
    private String originalTitle; // MediaStore TITLE (metadata tag)
    private String folderName; // MediaStore BUCKET_DISPLAY_NAME (parent folder)

    public AudioFile(String title, String duration, Uri uri, long id, long fileSize, long dateAdded) {
        this(title, duration, uri, id, fileSize, dateAdded, null);
    }

    public AudioFile(String title, String duration, Uri uri, long id, long fileSize, long dateAdded, String originalTitle) {
        this.title = title;
        this.duration = duration;
        this.uri = uri;
        this.id = id;
        this.fileSize = fileSize;
        this.formattedSize = formatFileSize(fileSize);
        this.dateAdded = dateAdded;
        this.originalTitle = originalTitle;
        this.folderName = null;
    }

    public String getTitle() {
        return title;
    }

    /**
     * Returns the MediaStore bucket (parent folder) display name, or null if
     * the file has no folder associated.
     */
    public String getFolderName() {
        return folderName;
    }

    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }

    /**
     * Returns the MediaStore TITLE metadata (may differ from display name / filename)
     */
    public String getOriginalTitle() {
        return originalTitle;
    }

    /**
     * Check if the given query matches either the displayed title or the original metadata title.
     * This ensures search finds songs regardless of which field contains the search term.
     */
    public boolean matchesSearch(String lowerQuery) {
        if (title != null && title.toLowerCase(java.util.Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        if (originalTitle != null && originalTitle.toLowerCase(java.util.Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        return false;
    }

    /**
     * Subsequence matching (Subsequence Search mode): true when the query's
     * characters appear in the title in the same order, but not necessarily
     * consecutively. E.g. "apl" matches "Apple" (a → p … l). Checks both the
     * displayed title and the original metadata title, like matchesSearch.
     */
    public boolean matchesSubsequence(String lowerQuery) {
        if (title != null && isSubsequenceOf(lowerQuery, title)) {
            return true;
        }
        if (originalTitle != null && isSubsequenceOf(lowerQuery, originalTitle)) {
            return true;
        }
        return false;
    }

    private static boolean isSubsequenceOf(String lowerQuery, String target) {
        if (lowerQuery == null || lowerQuery.isEmpty()) {
            return true;
        }
        String lowerTarget = target.toLowerCase(java.util.Locale.ROOT);
        int index = 0;
        for (char c : lowerQuery.toCharArray()) {
            index = lowerTarget.indexOf(c, index);
            if (index < 0) {
                return false;
            }
            index++;
        }
        return true;
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