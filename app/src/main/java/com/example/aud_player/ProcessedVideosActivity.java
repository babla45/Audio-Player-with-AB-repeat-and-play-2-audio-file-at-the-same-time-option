package com.example.aud_player;

import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lists videos from the processed (merged/split/compressed) subfolders
 * inside aud_player/ and lets the user open or delete them.
 */
public class ProcessedVideosActivity extends AppCompatActivity {

    private static final String[] PROCESSED_SUBFOLDERS = {
            FFmpegVideoEditor.SUBFOLDER_MERGE,
            FFmpegVideoEditor.SUBFOLDER_SPLIT,
            FFmpegVideoEditor.SUBFOLDER_COMPRESS,
    };

    /** Mirrors FFmpegVideoEditor's private SUBFOLDER_DEFAULT target. */
    private static final String SUBFOLDER_EDITED = "edited";

    private static final String[] VIDEO_EXTENSIONS = {
            ".mp4", ".mkv", ".mov", ".3gp", ".avi", ".webm", ".m4v", ".ts", ".flv", ".wmv",
    };

    private RecyclerView recyclerView;
    private View emptyView;
    private TextView emptyText;
    private TextView mergedCountText, splitCountText, compressedCountText, otherCountText;
    private TextView subtitleText;
    private VideoAdapter adapter;
    private final List<VideoFile> processedVideos = new ArrayList<>();
    private int mergedCount = 0, splitCount = 0, compressedCount = 0, otherCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_processed_videos);

        recyclerView = findViewById(R.id.processedRecyclerView);
        emptyView = findViewById(R.id.processedEmptyView);
        emptyText = findViewById(R.id.processedEmptyText);
        mergedCountText = findViewById(R.id.processedMergedCount);
        splitCountText = findViewById(R.id.processedSplitCount);
        compressedCountText = findViewById(R.id.processedCompressedCount);
        otherCountText = findViewById(R.id.processedOtherCount);
        subtitleText = findViewById(R.id.processedSubtitle);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VideoAdapter(processedVideos);
        adapter.setOnItemClickListener(videoFile -> playVideo(videoFile));
        adapter.setOnItemLongClickListener(videoFile -> showOptionsDialog(videoFile));
        recyclerView.setAdapter(adapter);

        ImageButton backBtn = findViewById(R.id.processedBackBtn);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> finish());
        }

        loadProcessedVideos();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProcessedVideos();
    }

    private void loadProcessedVideos() {
        processedVideos.clear();
        mergedCount = 0;
        splitCount = 0;
        compressedCount = 0;
        otherCount = 0;

        // Paths already listed, so a file reachable from two places is shown once.
        Set<String> seen = new HashSet<>();

        // The three job folders, each with its own counter.
        int[] counts = {0, 0, 0};
        for (int i = 0; i < PROCESSED_SUBFOLDERS.length; i++) {
            File dir = getProcessedDir(PROCESSED_SUBFOLDERS[i]);
            for (File f : listVideoFiles(dir)) {
                if (seen.add(f.getAbsolutePath())) {
                    processedVideos.add(videoFileFromFile(f));
                    counts[i]++;
                }
            }
        }
        mergedCount = counts[0];
        splitCount = counts[1];
        compressedCount = counts[2];

        // Everything else under aud_player/ — loose files in the root and any
        // other subfolder (e.g. "edited") — so no processed video is missed.
        File root = getAudPlayerRoot();
        if (root != null) {
            for (File f : listVideoFiles(root)) {
                if (seen.add(f.getAbsolutePath())) {
                    processedVideos.add(videoFileFromFile(f));
                    otherCount++;
                }
            }
            File[] subs = root.listFiles(File::isDirectory);
            if (subs != null) {
                for (File sub : subs) {
                    for (File f : listVideoFiles(sub)) {
                        if (seen.add(f.getAbsolutePath())) {
                            processedVideos.add(videoFileFromFile(f));
                            otherCount++;
                        }
                    }
                }
            }
        }

        mergedCountText.setText("Merged: " + mergedCount);
        splitCountText.setText("Split: " + splitCount);
        compressedCountText.setText("Compressed: " + compressedCount);
        otherCountText.setText("Other: " + otherCount);

        int total = processedVideos.size();
        subtitleText.setText(total > 0
                ? total + (total == 1 ? " video" : " videos") + " • aud_player folder"
                : "aud_player folder");

        if (processedVideos.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            emptyText.setText("No processed videos yet\n\nVideos from Merge, Split & Compress will appear here");
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
        }

        adapter.updateList(processedVideos);
    }

    /** Video files directly inside {@code dir}, never null. */
    private static File[] listVideoFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return new File[0];
        File[] files = dir.listFiles((f, name) -> isVideoName(name));
        return files != null ? files : new File[0];
    }

    private static boolean isVideoName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String ext : VIDEO_EXTENSIONS) {
            if (n.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * Resolves the folder the same way the editor writes it, so the list always
     * points at wherever the job actually landed (shared storage, app-external
     * or internal fallback).
     */
    private File getProcessedDir(String subfolder) {
        try {
            File dir = FFmpegVideoEditor.getOutputDir(this, subfolder);
            if (dir != null && dir.isDirectory()) return dir;
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * The aud_player/ folder itself, taken as the parent of a job folder so it
     * follows the same fallback chain as the writers.
     */
    private File getAudPlayerRoot() {
        for (String sub : PROCESSED_SUBFOLDERS) {
            File dir = getProcessedDir(sub);
            if (dir != null && dir.getParentFile() != null) return dir.getParentFile();
        }
        File dir = getProcessedDir(SUBFOLDER_EDITED);
        return dir != null ? dir.getParentFile() : null;
    }

    private VideoFile videoFileFromFile(File f) {
        String name = f.getName();
        long size = f.length();
        long date = f.lastModified();

        long durationMs = 0;
        try {
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            retriever.setDataSource(f.getAbsolutePath());
            String dur = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) durationMs = Long.parseLong(dur);
            retriever.release();
        } catch (Throwable ignored) {}

        Uri uri = Uri.fromFile(f);
        return new VideoFile(name, durationMs, uri, 0, size, date, f.getAbsolutePath());
    }

    /** Tap plays the video. */
    private void playVideo(VideoFile videoFile) {
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        intent.putExtra(VideoPlayerActivity.EXTRA_PATH, videoFile.getPath());
        intent.putExtra(VideoPlayerActivity.EXTRA_TITLE, videoFile.getTitle());
        startActivity(intent);
    }

    /** Long-press offers the actions that make sense for a finished video. */
    private void showOptionsDialog(VideoFile videoFile) {
        final String[] options = {"Play", "Rename", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(videoFile.getTitle())
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        playVideo(videoFile);
                    } else if (which == 1) {
                        showRenameDialog(videoFile);
                    } else {
                        showDeleteDialog(videoFile);
                    }
                })
                .show();
    }

    private void showRenameDialog(VideoFile videoFile) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(videoFile.getTitle());
        input.setSelection(input.getText().length());

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename video");
        builder.setView(input);
        // Listener set after show() so an empty name keeps the dialog open.
        builder.setPositiveButton("Rename", null);
        builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty()) {
                input.setError("Name cannot be empty");
                return;
            }
            if (renameVideo(videoFile, newName)) {
                dialog.dismiss();
            }
        });
    }

    /**
     * Renames the file on disk, keeping the original extension when the user
     * leaves it off. Returns true when the dialog can close.
     */
    private boolean renameVideo(VideoFile videoFile, String newName) {
        try {
            String currentTitle = videoFile.getTitle();
            String extension = "";
            int dot = currentTitle.lastIndexOf('.');
            if (dot != -1) {
                extension = currentTitle.substring(dot);
            }
            if (newName.indexOf('.') < 0 && !extension.isEmpty()) {
                newName = newName + extension;
            }

            // The typed text is one path segment, never a path.
            if (newName.contains("/") || newName.contains("\\") || newName.contains("..")) {
                android.widget.Toast.makeText(this, "Names can't contain / or \\",
                        android.widget.Toast.LENGTH_SHORT).show();
                return false;
            }

            File src = new File(videoFile.getPath());
            if (!src.exists()) {
                android.widget.Toast.makeText(this, "File not found",
                        android.widget.Toast.LENGTH_SHORT).show();
                loadProcessedVideos();
                return true;
            }

            File dst = new File(src.getParentFile(), newName);
            if (dst.equals(src)) {
                return true;
            }
            if (dst.exists()) {
                android.widget.Toast.makeText(this, "A file with that name already exists",
                        android.widget.Toast.LENGTH_SHORT).show();
                return false;
            }
            if (!src.renameTo(dst)) {
                android.widget.Toast.makeText(this, "Could not rename the file",
                        android.widget.Toast.LENGTH_SHORT).show();
                return false;
            }

            // Let the gallery and other apps notice the change.
            MediaScannerConnection.scanFile(this,
                    new String[]{src.getAbsolutePath(), dst.getAbsolutePath()}, null, null);

            android.widget.Toast.makeText(this, "Renamed", android.widget.Toast.LENGTH_SHORT).show();
            loadProcessedVideos();
            return true;
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "Could not rename the file",
                    android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void showDeleteDialog(VideoFile videoFile) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this video?")
                .setMessage(videoFile.getTitle()
                        + "\n\nThis file will be permanently deleted.")
                .setPositiveButton("Delete", (d, w) -> deleteVideo(videoFile))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteVideo(VideoFile videoFile) {
        Uri uri = videoFile.getUri();
        if ("file".equals(uri.getScheme())) {
            File f = new File(uri.getPath());
            if (f.exists() && f.delete()) {
                MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
                loadProcessedVideos();
                return;
            }
        }
        try {
            int deleted = getContentResolver().delete(uri, null, null);
            if (deleted > 0) {
                loadProcessedVideos();
                return;
            }
        } catch (Throwable ignored) {}
        android.widget.Toast.makeText(this, "Could not delete the file",
                android.widget.Toast.LENGTH_SHORT).show();
    }
}
