package com.example.aud_player;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

public class PlaylistActivity extends AppCompatActivity {

    private RecyclerView playlistRecyclerView;
    private PlaylistAdapter playlistAdapter;
    private List<Playlist> playlists = new ArrayList<>();
    private PlaylistDatabaseHelper dbHelper;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlist);

        // Initialize the database helper
        dbHelper = new PlaylistDatabaseHelper(this);

        // Set up the toolbar
        setSupportActionBar(findViewById(R.id.playlist_toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Playlists");
        }

        // Initialize views
        playlistRecyclerView = findViewById(R.id.playlist_recycler_view);
        emptyView = findViewById(R.id.empty_playlists_view);
        FloatingActionButton addPlaylistButton = findViewById(R.id.add_playlist_button);

        // Set up RecyclerView
        playlistRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        playlistAdapter = new PlaylistAdapter(playlists);
        playlistRecyclerView.setAdapter(playlistAdapter);

        // Set up listeners
        playlistAdapter.setOnPlaylistClickListener(this::openPlaylist);
        playlistAdapter.setOnPlaylistOptionsClickListener(new PlaylistAdapter.OnPlaylistOptionsClickListener() {
            @Override
            public void onRenamePlaylistClick(Playlist playlist) {
                showRenamePlaylistDialog(playlist);
            }

            @Override
            public void onDeletePlaylistClick(Playlist playlist) {
                showDeletePlaylistDialog(playlist);
            }
        });

        addPlaylistButton.setOnClickListener(v -> showCreatePlaylistDialog());

        // Load playlists
        loadPlaylists();
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
        loadPlaylists();
    }

    private void loadPlaylists() {
        playlists.clear();
        playlists.addAll(dbHelper.getAllPlaylists());
        playlistAdapter.notifyDataSetChanged();

        // Show empty view if there are no playlists
        if (playlists.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            playlistRecyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            playlistRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void showCreatePlaylistDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Create Playlist");

        // Set up the input
        final EditText input = new EditText(this);
        input.setHint("Playlist Name");
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton("Create", (dialog, which) -> {
            String playlistName = input.getText().toString().trim();
            if (!TextUtils.isEmpty(playlistName)) {
                Playlist newPlaylist = new Playlist(playlistName);
                dbHelper.createPlaylist(newPlaylist);
                loadPlaylists();
                Toast.makeText(PlaylistActivity.this, "Playlist created", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(PlaylistActivity.this, "Playlist name cannot be empty", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void showRenamePlaylistDialog(Playlist playlist) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Playlist");

        // Set up the input
        final EditText input = new EditText(this);
        input.setText(playlist.getName());
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!TextUtils.isEmpty(newName)) {
                playlist.setName(newName);
                dbHelper.updatePlaylist(playlist);
                loadPlaylists();
                Toast.makeText(PlaylistActivity.this, "Playlist renamed", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(PlaylistActivity.this, "Playlist name cannot be empty", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void showDeletePlaylistDialog(Playlist playlist) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Playlist")
                .setMessage("Are you sure you want to delete the playlist '" + playlist.getName() + "'?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    dbHelper.deletePlaylist(playlist.getId());
                    loadPlaylists();
                    Toast.makeText(PlaylistActivity.this, "Playlist deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openPlaylist(Playlist playlist) {
        Intent intent = new Intent(this, PlaylistDetailActivity.class);
        intent.putExtra("PLAYLIST_ID", playlist.getId());
        startActivity(intent);
    }
} 