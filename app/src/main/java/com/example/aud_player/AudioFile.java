package com.example.aud_player;

import android.net.Uri;

public class AudioFile {
    private String title;
    private String duration;
    private Uri uri;
    private long id;

    public AudioFile(String title, String duration, Uri uri, long id) {
        this.title = title;
        this.duration = duration;
        this.uri = uri;
        this.id = id;
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
} 