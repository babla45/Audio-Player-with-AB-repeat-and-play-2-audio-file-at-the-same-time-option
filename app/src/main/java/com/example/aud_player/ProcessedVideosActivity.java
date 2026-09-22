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
    private TextView allCountText, mergedCountText, splitCountText, compressedCountText, otherCountText;
    private TextView subtitleText;
    private VideoAdapter adapter;

    // Filter chips (the "All" chip is the default: everything, combined).
    private static final int FILTER_ALL = 0;
    private static final int FILTER_MERGED = 1;
    private static final int FILTER_SPLIT = 2;
    private static final int FILTER_COMPRESSED = 3;
    private static final int FILTER_OTHER = 4;
    private int currentFilter = FILTER_ALL;

    /** Everything found on disk, kept per category so a chip can filter it. */
    private final List<VideoFile> mergedVideos = new ArrayList<>();
    private final List<VideoFile> splitVideos = new ArrayList<>();
    private final List<VideoFile> compressedVideos = new ArrayList<>();
    private final List<VideoFile> otherVideos = new ArrayList<>();

    /** The rows currently on screen — the adapter's source list. */
    private final List<VideoFile> processedVideos = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_processed_videos);

        recyclerView = findViewById(R.id.processedRecyclerView);
        emptyView = findViewById(R.id.processedEmptyView);
        emptyText = findViewById(R.id.processedEmptyText);
        allCountText = findViewById(R.id.processedAllCount);
        mergedCountText = findViewById(R.id.processedMergedCount);
        splitCountText = findViewById(R.id.processedSplitCount);
        compressedCountText = findViewById(R.id.processedCompressedCount);
        otherCountText = findViewById(R.id.processedOtherCount);
        subtitleText = findViewById(R.id.processedSubtitle);

        // Chips double as filters; the saved filter survives a reload.
        allCountText.setOnClickListener(v -> setFilter(FILTER_ALL));
        mergedCountText.setOnClickListener(v -> setFilter(FILTER_MERGED));
        splitCountText.setOnClickListener(v -> setFilter(FILTER_SPLIT));
        compressedCountText.setOnClickListener(v -> setFilter(FILTER_COMPRESSED));
        otherCountText.setOnClickListener(v -> setFilter(FILTER_OTHER));

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VideoAdapter(processedVideos);
        adapter.setOnItemClickListener(videoFile -> playVideo(videoFile));
        adapter.setOnItemLongClickListener(videoFile -> showOptionsDialog(videoFile));
        // Same menu as the long-press, reachable from the row's overflow button.
        adapter.setShowOptionsButton(true);
        adapter.setOnOptionsClickListener(videoFile -> showOptionsDialog(videoFile));
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
        mergedVideos.clear();
        splitVideos.clear();
        compressedVideos.clear();
        otherVideos.clear();

        // Paths already listed, so a file reachable from two places is shown once.
        Set<String> seen = new HashSet<>();

        // The three job folders, each into its own category.
        scanInto(getProcessedDir(PROCESSED_SUBFOLDERS[0]), mergedVideos, seen);
        scanInto(getProcessedDir(PROCESSED_SUBFOLDERS[1]), splitVideos, seen);
        scanInto(getProcessedDir(PROCESSED_SUBFOLDERS[2]), compressedVideos, seen);

        // Everything else under aud_player/ — loose files in the root and any
        // other subfolder (e.g. "edited") — so no processed video is missed.
        File root = getAudPlayerRoot();
        if (root != null) {
            scanInto(root, otherVideos, seen);
            File[] subs = root.listFiles(File::isDirectory);
            if (subs != null) {
                for (File sub : subs) {
                    scanInto(sub, otherVideos, seen);
                }
            }
        }

        int total = mergedVideos.size() + splitVideos.size()
                + compressedVideos.size() + otherVideos.size();
        allCountText.setText("All: " + total);
        mergedCountText.setText("Merged: " + mergedVideos.size());
        splitCountText.setText("Split: " + splitVideos.size());
        compressedCountText.setText("Compressed: " + compressedVideos.size());
        otherCountText.setText("Other: " + otherVideos.size());

        applyFilter();
    }

    /** Switches the visible category; the count badges stay on the totals. */
    private void setFilter(int filter) {
        if (currentFilter == filter) return;
        currentFilter = filter;
        applyFilter();
    }

    /** Rebuilds the visible rows for {@link #currentFilter}. */
    private void applyFilter() {
        processedVideos.clear();
        switch (currentFilter) {
            case FILTER_MERGED:
                processedVideos.addAll(mergedVideos);
                break;
            case FILTER_SPLIT:
                processedVideos.addAll(splitVideos);
                break;
            case FILTER_COMPRESSED:
                processedVideos.addAll(compressedVideos);
                break;
            case FILTER_OTHER:
                processedVideos.addAll(otherVideos);
                break;
            default:
                // "All": one combined list, grouped by category.
                processedVideos.addAll(mergedVideos);
                processedVideos.addAll(splitVideos);
                processedVideos.addAll(compressedVideos);
                processedVideos.addAll(otherVideos);
                break;
        }

        updateChipStyles();

        int shown = processedVideos.size();
        subtitleText.setText(shown > 0
                ? shown + (shown == 1 ? " video" : " videos") + " • aud_player folder"
                : "aud_player folder");

        if (processedVideos.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            emptyText.setText(emptyMessage());
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
        }

        adapter.updateList(processedVideos);
    }

    /** Selected chip reads solid white on the accent pill, as in the editor tabs. */
    private void updateChipStyles() {
        styleChip(allCountText, currentFilter == FILTER_ALL);
        styleChip(mergedCountText, currentFilter == FILTER_MERGED);
        styleChip(splitCountText, currentFilter == FILTER_SPLIT);
        styleChip(compressedCountText, currentFilter == FILTER_COMPRESSED);
        styleChip(otherCountText, currentFilter == FILTER_OTHER);
    }

    private void styleChip(TextView chip, boolean selected) {
        if (chip == null) return;
        chip.setTextColor(selected ? 0xFFFFFFFF : 0x88FFFFFF);
        chip.setBackgroundResource(selected
                ? R.drawable.bg_editor_chip_selected
                : R.drawable.bg_editor_chip);
    }

    private String emptyMessage() {
        switch (currentFilter) {
            case FILTER_MERGED:
                return "No merged videos yet";
            case FILTER_SPLIT:
                return "No split videos yet";
            case FILTER_COMPRESSED:
                return "No compressed videos yet";
            case FILTER_OTHER:
                return "No other videos";
            default:
                return "No processed videos yet\n\nVideos from Merge, Split & Compress will appear here";
        }
    }

    /** Video files directly inside {@code dir}, never null. */
    private static File[] listVideoFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return new File[0];
        File[] files = dir.listFiles((f, name) -> isVideoName(name));
        return files != null ? files : new File[0];
    }

    /** Adds the videos in {@code dir} to {@code target}, skipping paths already listed. */
    private void scanInto(File dir, List<VideoFile> target, Set<String> seen) {
        for (File f : listVideoFiles(dir)) {
            if (seen.add(f.getAbsolutePath())) {
                target.add(videoFileFromFile(f));
            }
        }
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
