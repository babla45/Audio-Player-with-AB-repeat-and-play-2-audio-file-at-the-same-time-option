package com.example.aud_player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class PlaylistDetailActivity extends AppCompatActivity {

    private RecyclerView songRecyclerView;
    private AudioAdapter audioAdapter;
    private List<AudioFile> songs = new ArrayList<>();
    private PlaylistDatabaseHelper dbHelper;
    private TextView emptyView;
    private TextView playlistNameView;
    private TextView playlistInfoView;
    private Playlist currentPlaylist;
    private Button playAllButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlist_detail);

        // Initialize the database helper
        dbHelper = new PlaylistDatabaseHelper(this);

        // Get playlist ID from intent
        String playlistId = getIntent().getStringExtra("PLAYLIST_ID");
        if (playlistId == null) {
            Toast.makeText(this, "Error loading playlist", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Load the playlist
        currentPlaylist = dbHelper.getPlaylist(playlistId);
        if (currentPlaylist == null) {
            Toast.makeText(this, "Playlist not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Set up the toolbar
        setSupportActionBar(findViewById(R.id.playlist_detail_toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(currentPlaylist.getName());
        }

        // Initialize views
        songRecyclerView = findViewById(R.id.playlist_songs_recycler_view);
        emptyView = findViewById(R.id.empty_playlist_view);
        playlistNameView = findViewById(R.id.playlist_detail_name);
        playlistInfoView = findViewById(R.id.playlist_detail_info);
        playAllButton = findViewById(R.id.play_all_button);
        Button addSongsButton = findViewById(R.id.add_songs_button);

        // Set playlist details
        playlistNameView.setText(currentPlaylist.getName());
        String songCount = currentPlaylist.getSize() + " song" + (currentPlaylist.getSize() != 1 ? "s" : "");
        playlistInfoView.setText(songCount);

        // Set up RecyclerView
        songRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        audioAdapter = new AudioAdapter(songs);
        songRecyclerView.setAdapter(audioAdapter);

        // Set up listeners
        audioAdapter.setOnItemClickListener(this::playSong);
        audioAdapter.setOnOptionsItemClickListener(new AudioAdapter.OnOptionsItemClickListener() {
            @Override
            public void onFileDetailsClick(AudioFile audioFile) {
                // Show file details
                showFileDetailsDialog(audioFile);
            }

            @Override
            public void onRenameFileClick(AudioFile audioFile) {
                // Playlist songs can't be renamed from here
                Toast.makeText(PlaylistDetailActivity.this, "Can't rename from playlist", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onDeleteFileClick(AudioFile audioFile) {
                // Remove from playlist
                removeFromPlaylist(audioFile);
            }

            @Override
            public void onShareFileClick(AudioFile audioFile) {
                shareAudioFile(audioFile);
            }
        });

        playAllButton.setOnClickListener(v -> playAllSongs());
        addSongsButton.setOnClickListener(v -> addSongsToPlaylist());

        // Load songs
        loadPlaylistSongs();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPlaylistSongs();
    }

    private void loadPlaylistSongs() {
        songs.clear();
        songs.addAll(dbHelper.getPlaylistSongs(currentPlaylist.getId()));
        audioAdapter.notifyDataSetChanged();

        // Update playlist info
        String songCount = songs.size() + " song" + (songs.size() != 1 ? "s" : "");
        playlistInfoView.setText(songCount);

        // Show empty view if there are no songs
        if (songs.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            songRecyclerView.setVisibility(View.GONE);
            playAllButton.setEnabled(false);
        } else {
            emptyView.setVisibility(View.GONE);
            songRecyclerView.setVisibility(View.VISIBLE);
            playAllButton.setEnabled(true);
        }
    }

    private void playSong(AudioFile audioFile) {
        // Start MainActivity with the selected audio
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(audioFile.getUri());
        intent.putExtra("PLAYLIST_ID", currentPlaylist.getId());
        startActivity(intent);
    }

    private void playAllSongs() {
        if (songs.isEmpty()) {
            Toast.makeText(this, "No songs in playlist", Toast.LENGTH_SHORT).show();
            return;
        }

        // Start MainActivity with the first song in the playlist
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(songs.get(0).getUri());
        intent.putExtra("PLAYLIST_ID", currentPlaylist.getId());
        intent.putExtra("PLAY_ENTIRE_PLAYLIST", true);
        startActivity(intent);
    }

    private void addSongsToPlaylist() {
        // Open AudioListActivity with add to playlist mode
        Intent intent = new Intent(this, AudioListActivity.class);
        intent.putExtra("MODE", "ADD_TO_PLAYLIST");
        intent.putExtra("PLAYLIST_ID", currentPlaylist.getId());
        startActivity(intent);
    }

    private void removeFromPlaylist(AudioFile audioFile) {
        dbHelper.removeSongFromPlaylist(currentPlaylist.getId(), audioFile);
        loadPlaylistSongs();
        Toast.makeText(this, "Song removed from playlist", Toast.LENGTH_SHORT).show();
    }

    private void showFileDetailsDialog(AudioFile audioFile) {
        // Show file details dialog
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("File Details")
                .setMessage("Title: " + audioFile.getTitle() + "\n"
                        + "Duration: " + audioFile.getDuration() + "\n"
                        + "Size: " + audioFile.getFormattedSize())
                .setPositiveButton("OK", null)
                .show();
    }

    private void shareAudioFile(AudioFile audioFile) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("audio/*");
        shareIntent.putExtra(Intent.EXTRA_STREAM, audioFile.getUri());
        startActivity(Intent.createChooser(shareIntent, "Share audio file"));
    }
} 