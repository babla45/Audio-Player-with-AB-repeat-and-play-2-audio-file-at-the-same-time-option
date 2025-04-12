package com.example.aud_player;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Class representing a playlist with a name and list of audio files
 */
public class Playlist {
    private String id;
    private String name;
    private List<AudioFile> songs;
    private long dateCreated;

    public Playlist(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.songs = new ArrayList<>();
        this.dateCreated = System.currentTimeMillis();
    }

    public Playlist(String id, String name, long dateCreated) {
        this.id = id;
        this.name = name;
        this.songs = new ArrayList<>();
        this.dateCreated = dateCreated;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<AudioFile> getSongs() {
        return songs;
    }

    public void addSong(AudioFile song) {
        if (!containsSong(song)) {
            songs.add(song);
        }
    }

    public void removeSong(AudioFile song) {
        songs.remove(song);
    }

    public void removeSong(int position) {
        if (position >= 0 && position < songs.size()) {
            songs.remove(position);
        }
    }

    public boolean containsSong(AudioFile song) {
        for (AudioFile audioFile : songs) {
            if (audioFile.getUri().equals(song.getUri())) {
                return true;
            }
        }
        return false;
    }

    public boolean containsSong(Uri uri) {
        for (AudioFile audioFile : songs) {
            if (audioFile.getUri().equals(uri)) {
                return true;
            }
        }
        return false;
    }

    public int getSize() {
        return songs.size();
    }

    public long getDateCreated() {
        return dateCreated;
    }

    @Override
    public String toString() {
        return name + " (" + songs.size() + " songs)";
    }
} 