package com.example.aud_player;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AudioListActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView emptyView;
    private List<AudioFile> audioFiles = new ArrayList<>();
    private boolean isSelectingSecondFile = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio_list);

        recyclerView = findViewById(R.id.audioRecyclerView);
        emptyView = findViewById(R.id.emptyView);

        // Get intent extras to determine if we're selecting primary or secondary audio
        if (getIntent() != null) {
            isSelectingSecondFile = getIntent().getBooleanExtra("select_second_file", false);
        }

        // Setup RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        
        // Load audio files
        loadAudioFiles();
    }

    private void loadAudioFiles() {
        // Query for all audio files
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE
        };

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";

        Cursor cursor = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                MediaStore.Audio.Media.TITLE + " ASC");

        if (cursor != null) {
            audioFiles.clear();
            
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String title = cursor.getString(titleColumn);
                long duration = cursor.getLong(durationColumn);
                long size = cursor.getLong(sizeColumn);
                String durationFormatted = formatDuration(duration);

                Uri contentUri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));

                audioFiles.add(new AudioFile(title, durationFormatted, contentUri, id, size));
            }
            cursor.close();
        }

        // Show empty view if no audio files
        if (audioFiles.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
            
            // Setup adapter
            AudioAdapter adapter = new AudioAdapter(audioFiles);
            adapter.setOnItemClickListener(audioFile -> {
                // Return the selected file to MainActivity
                Intent resultIntent = new Intent();
                resultIntent.setData(audioFile.getUri());
                setResult(RESULT_OK, resultIntent);
                finish();
            });
            recyclerView.setAdapter(adapter);
        }
    }

    private String formatDuration(long milliseconds) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.MINUTES.toSeconds(minutes);
        return String.format("%d:%02d", minutes, seconds);
    }
} 