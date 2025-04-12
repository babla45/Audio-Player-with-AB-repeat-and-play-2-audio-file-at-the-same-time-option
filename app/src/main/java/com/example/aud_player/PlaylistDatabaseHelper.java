package com.example.aud_player;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class PlaylistDatabaseHelper extends SQLiteOpenHelper {

    private static final String TAG = "PlaylistDBHelper";
    private static final String DATABASE_NAME = "playlists.db";
    private static final int DATABASE_VERSION = 1;

    // Table names
    private static final String TABLE_PLAYLISTS = "playlists";
    private static final String TABLE_PLAYLIST_ITEMS = "playlist_items";

    // Common column names
    private static final String KEY_ID = "id";
    
    // Playlists table columns
    private static final String KEY_PLAYLIST_NAME = "name";
    private static final String KEY_DATE_CREATED = "date_created";
    
    // Playlist items table columns
    private static final String KEY_PLAYLIST_ID = "playlist_id";
    private static final String KEY_TRACK_URI = "track_uri";
    private static final String KEY_TRACK_TITLE = "track_title";
    private static final String KEY_TRACK_DURATION = "track_duration";
    private static final String KEY_TRACK_SIZE = "track_size";
    private static final String KEY_TRACK_DATE_ADDED = "track_date_added";
    private static final String KEY_SORT_ORDER = "sort_order";

    // Create table statements
    private static final String CREATE_TABLE_PLAYLISTS = "CREATE TABLE " + TABLE_PLAYLISTS + "("
            + KEY_ID + " TEXT PRIMARY KEY,"
            + KEY_PLAYLIST_NAME + " TEXT,"
            + KEY_DATE_CREATED + " INTEGER" + ")";

    private static final String CREATE_TABLE_PLAYLIST_ITEMS = "CREATE TABLE " + TABLE_PLAYLIST_ITEMS + "("
            + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
            + KEY_PLAYLIST_ID + " TEXT,"
            + KEY_TRACK_URI + " TEXT,"
            + KEY_TRACK_TITLE + " TEXT,"
            + KEY_TRACK_DURATION + " TEXT,"
            + KEY_TRACK_SIZE + " INTEGER,"
            + KEY_TRACK_DATE_ADDED + " INTEGER,"
            + KEY_SORT_ORDER + " INTEGER,"
            + "FOREIGN KEY(" + KEY_PLAYLIST_ID + ") REFERENCES " + TABLE_PLAYLISTS + "(" + KEY_ID + ") ON DELETE CASCADE" + ")";

    public PlaylistDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_PLAYLISTS);
        db.execSQL(CREATE_TABLE_PLAYLIST_ITEMS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Drop older tables if they exist
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_PLAYLIST_ITEMS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_PLAYLISTS);

        // Create tables again
        onCreate(db);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // Enable foreign key constraints
        db.execSQL("PRAGMA foreign_keys=ON");
    }

    /**
     * Create a new playlist
     * @param playlist Playlist object to create
     * @return ID of the created playlist
     */
    public String createPlaylist(Playlist playlist) {
        SQLiteDatabase db = this.getWritableDatabase();
        
        ContentValues values = new ContentValues();
        values.put(KEY_ID, playlist.getId());
        values.put(KEY_PLAYLIST_NAME, playlist.getName());
        values.put(KEY_DATE_CREATED, playlist.getDateCreated());
        
        // Insert row
        db.insert(TABLE_PLAYLISTS, null, values);
        
        Log.d(TAG, "Created playlist: " + playlist.getName());
        
        return playlist.getId();
    }

    /**
     * Get a single playlist by id
     * @param playlistId ID of the playlist to retrieve
     * @return Playlist object
     */
    public Playlist getPlaylist(String playlistId) {
        SQLiteDatabase db = this.getReadableDatabase();
        
        String selectQuery = "SELECT * FROM " + TABLE_PLAYLISTS + " WHERE " + KEY_ID + " = ?";
        
        Cursor c = db.rawQuery(selectQuery, new String[] { playlistId });
        
        if (c != null && c.moveToFirst()) {
            String id = c.getString(c.getColumnIndexOrThrow(KEY_ID));
            String name = c.getString(c.getColumnIndexOrThrow(KEY_PLAYLIST_NAME));
            long dateCreated = c.getLong(c.getColumnIndexOrThrow(KEY_DATE_CREATED));
            
            Playlist playlist = new Playlist(id, name, dateCreated);
            
            // Load playlist songs
            playlist.getSongs().addAll(getPlaylistSongs(id));
            
            c.close();
            return playlist;
        }
        
        if (c != null) {
            c.close();
        }
        
        return null;
    }

    /**
     * Get all playlists
     * @return List of all playlists
     */
    public List<Playlist> getAllPlaylists() {
        List<Playlist> playlists = new ArrayList<>();
        String selectQuery = "SELECT * FROM " + TABLE_PLAYLISTS + " ORDER BY " + KEY_DATE_CREATED + " DESC";
        
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.rawQuery(selectQuery, null);
        
        if (c.moveToFirst()) {
            do {
                String id = c.getString(c.getColumnIndexOrThrow(KEY_ID));
                String name = c.getString(c.getColumnIndexOrThrow(KEY_PLAYLIST_NAME));
                long dateCreated = c.getLong(c.getColumnIndexOrThrow(KEY_DATE_CREATED));
                
                Playlist playlist = new Playlist(id, name, dateCreated);
                
                // Load playlist songs count for display
                playlist.getSongs().addAll(getPlaylistSongs(id));
                
                playlists.add(playlist);
            } while (c.moveToNext());
        }
        
        c.close();
        return playlists;
    }

    /**
     * Update a playlist
     * @param playlist Playlist to update
     * @return Number of rows affected
     */
    public int updatePlaylist(Playlist playlist) {
        SQLiteDatabase db = this.getWritableDatabase();
        
        ContentValues values = new ContentValues();
        values.put(KEY_PLAYLIST_NAME, playlist.getName());
        
        // Update row
        return db.update(TABLE_PLAYLISTS, values, KEY_ID + " = ?", new String[] { playlist.getId() });
    }

    /**
     * Delete a playlist
     * @param playlistId ID of the playlist to delete
     */
    public void deletePlaylist(String playlistId) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_PLAYLISTS, KEY_ID + " = ?", new String[] { playlistId });
    }

    /**
     * Add a song to a playlist
     * @param playlistId ID of the playlist
     * @param audioFile Audio file to add
     * @return ID of the created entry
     */
    public long addSongToPlaylist(String playlistId, AudioFile audioFile) {
        SQLiteDatabase db = this.getWritableDatabase();
        
        // Check if song already exists in the playlist
        if (isSongInPlaylist(playlistId, audioFile.getUri())) {
            Log.d(TAG, "Song already exists in playlist");
            return -1;
        }
        
        ContentValues values = new ContentValues();
        values.put(KEY_PLAYLIST_ID, playlistId);
        values.put(KEY_TRACK_URI, audioFile.getUri().toString());
        values.put(KEY_TRACK_TITLE, audioFile.getTitle());
        values.put(KEY_TRACK_DURATION, audioFile.getDuration());
        values.put(KEY_TRACK_SIZE, audioFile.getFileSize());
        values.put(KEY_TRACK_DATE_ADDED, audioFile.getDateAdded());
        
        // Get the current highest sort order and add 1
        int sortOrder = getHighestSortOrder(playlistId) + 1;
        values.put(KEY_SORT_ORDER, sortOrder);
        
        // Insert row
        return db.insert(TABLE_PLAYLIST_ITEMS, null, values);
    }

    /**
     * Remove a song from a playlist
     * @param playlistId ID of the playlist
     * @param audioFile Audio file to remove
     */
    public void removeSongFromPlaylist(String playlistId, AudioFile audioFile) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_PLAYLIST_ITEMS, 
                KEY_PLAYLIST_ID + " = ? AND " + KEY_TRACK_URI + " = ?", 
                new String[] { playlistId, audioFile.getUri().toString() });
    }

    /**
     * Get all songs in a playlist
     * @param playlistId ID of the playlist
     * @return List of audio files in the playlist
     */
    public List<AudioFile> getPlaylistSongs(String playlistId) {
        List<AudioFile> songs = new ArrayList<>();
        
        String selectQuery = "SELECT * FROM " + TABLE_PLAYLIST_ITEMS + 
                " WHERE " + KEY_PLAYLIST_ID + " = ?" + 
                " ORDER BY " + KEY_SORT_ORDER + " ASC";
        
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor c = db.rawQuery(selectQuery, new String[] { playlistId });
        
        if (c.moveToFirst()) {
            do {
                String uriString = c.getString(c.getColumnIndexOrThrow(KEY_TRACK_URI));
                String title = c.getString(c.getColumnIndexOrThrow(KEY_TRACK_TITLE));
                String duration = c.getString(c.getColumnIndexOrThrow(KEY_TRACK_DURATION));
                long size = c.getLong(c.getColumnIndexOrThrow(KEY_TRACK_SIZE));
                long dateAdded = c.getLong(c.getColumnIndexOrThrow(KEY_TRACK_DATE_ADDED));
                
                Uri uri = Uri.parse(uriString);
                
                AudioFile audioFile = new AudioFile(title, duration, uri, 0, size, dateAdded);
                songs.add(audioFile);
            } while (c.moveToNext());
        }
        
        c.close();
        return songs;
    }

    /**
     * Check if a song is already in a playlist
     * @param playlistId ID of the playlist
     * @param uri URI of the song
     * @return True if the song is in the playlist, false otherwise
     */
    public boolean isSongInPlaylist(String playlistId, Uri uri) {
        SQLiteDatabase db = this.getReadableDatabase();
        
        String selectQuery = "SELECT COUNT(*) FROM " + TABLE_PLAYLIST_ITEMS + 
                " WHERE " + KEY_PLAYLIST_ID + " = ? AND " + KEY_TRACK_URI + " = ?";
        
        Cursor cursor = db.rawQuery(selectQuery, new String[] { playlistId, uri.toString() });
        cursor.moveToFirst();
        int count = cursor.getInt(0);
        cursor.close();
        
        return count > 0;
    }

    /**
     * Get the highest sort order in a playlist
     * @param playlistId ID of the playlist
     * @return Highest sort order value
     */
    private int getHighestSortOrder(String playlistId) {
        SQLiteDatabase db = this.getReadableDatabase();
        
        String selectQuery = "SELECT MAX(" + KEY_SORT_ORDER + ") FROM " + TABLE_PLAYLIST_ITEMS + 
                " WHERE " + KEY_PLAYLIST_ID + " = ?";
        
        Cursor cursor = db.rawQuery(selectQuery, new String[] { playlistId });
        int sortOrder = 0;
        
        if (cursor.moveToFirst()) {
            sortOrder = cursor.getInt(0);
        }
        
        cursor.close();
        return sortOrder;
    }
} 