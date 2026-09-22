package com.example.aud_player;

import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
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
import java.util.concurrent.TimeUnit;

/**
 * Lists the Audio Editor's finished files (Merge and Split results, plus
 * anything else the editor wrote under aud_player/) and lets the user play,
 * rename or delete them.
 *
 * <p>This is the audio twin of {@link ProcessedVideosActivity}: same chips,
 * same filters, same row actions (tap = play, long-press or the row's
 * three-dot button = Play / Rename / Delete).
 */
public class ProcessedAudioActivity extends AppCompatActivity {

    private static final String[] PROCESSED_SUBFOLDERS = {
            FFmpegAudioEditor.SUBFOLDER_MERGE,
            FFmpegAudioEditor.SUBFOLDER_SPLIT,
    };

    /** Mirrors FFmpegAudioEditor's private SUBFOLDER_DEFAULT target. */
    private static final String SUBFOLDER_EDITED = "edited";

    private static final String[] AUDIO_EXTENSIONS = {
            ".mp3", ".m4a", ".m4b", ".aac", ".wav", ".ogg", ".oga", ".opus",
            ".flac", ".amr", ".awb", ".wma", ".mka", ".aif", ".aiff", ".mp2", ".caf",
    };

    private RecyclerView recyclerView;
    private View emptyView;
    private TextView emptyText;
    private TextView allCountText, mergedCountText, splitCountText, otherCountText;
    private TextView subtitleText;
    private AudioAdapter adapter;

    // Filter chips (the "All" chip is the default: everything, combined).
    private static final int FILTER_ALL = 0;
    private static final int FILTER_MERGED = 1;
    private static final int FILTER_SPLIT = 2;
    private static final int FILTER_OTHER = 3;
    private int currentFilter = FILTER_ALL;

    /** Everything found on disk, kept per category so a chip can filter it. */
    private final List<AudioFile> mergedAudio = new ArrayList<>();
    private final List<AudioFile> splitAudio = new ArrayList<>();
    private final List<AudioFile> otherAudio = new ArrayList<>();

    /** The rows currently on screen — the adapter's source list. */
    private final List<AudioFile> processedAudio = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_processed_audio);

        recyclerView = findViewById(R.id.processedRecyclerView);
        emptyView = findViewById(R.id.processedEmptyView);
        emptyText = findViewById(R.id.processedEmptyText);
        allCountText = findViewById(R.id.processedAllCount);
        mergedCountText = findViewById(R.id.processedMergedCount);
        splitCountText = findViewById(R.id.processedSplitCount);
        otherCountText = findViewById(R.id.processedOtherCount);
        subtitleText = findViewById(R.id.processedSubtitle);

        // Chips double as filters; the chosen filter survives a reload.
        allCountText.setOnClickListener(v -> setFilter(FILTER_ALL));
        mergedCountText.setOnClickListener(v -> setFilter(FILTER_MERGED));
        splitCountText.setOnClickListener(v -> setFilter(FILTER_SPLIT));
        otherCountText.setOnClickListener(v -> setFilter(FILTER_OTHER));

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AudioAdapter(processedAudio);
        adapter.setOnItemClickListener(this::playAudio);
        adapter.setOnItemLongClickListener(this::showOptionsDialog);
        // Same menu as the long-press, reachable from the row's three-dot button.
        adapter.setOnOptionsClickListener(this::showOptionsDialog);
        recyclerView.setAdapter(adapter);

        ImageButton backBtn = findViewById(R.id.processedBackBtn);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> finish());
        }

        loadProcessedAudio();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProcessedAudio();
    }

    private void loadProcessedAudio() {
        mergedAudio.clear();
        splitAudio.clear();
        otherAudio.clear();

        // Paths already listed, so a file reachable from two places is shown once.
        Set<String> seen = new HashSet<>();

        // The two job folders, each into its own category.
        scanInto(getProcessedDir(PROCESSED_SUBFOLDERS[0]), mergedAudio, seen);
        scanInto(getProcessedDir(PROCESSED_SUBFOLDERS[1]), splitAudio, seen);

        // Everything else under aud_player/ — loose files in the root and any
        // other subfolder (e.g. "edited") — so nothing processed is missed.
        File root = getAudPlayerRoot();
        if (root != null) {
            scanInto(root, otherAudio, seen);
            File[] subs = root.listFiles(File::isDirectory);
            if (subs != null) {
                for (File sub : subs) {
                    scanInto(sub, otherAudio, seen);
                }
            }
        }

        int total = mergedAudio.size() + splitAudio.size() + otherAudio.size();
        allCountText.setText("All: " + total);
        mergedCountText.setText("Merged: " + mergedAudio.size());
        splitCountText.setText("Split: " + splitAudio.size());
        otherCountText.setText("Other: " + otherAudio.size());

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
        processedAudio.clear();
        switch (currentFilter) {
            case FILTER_MERGED:
                processedAudio.addAll(mergedAudio);
                break;
            case FILTER_SPLIT:
                processedAudio.addAll(splitAudio);
                break;
            case FILTER_OTHER:
                processedAudio.addAll(otherAudio);
                break;
            default:
                // "All": one combined list, grouped by category.
                processedAudio.addAll(mergedAudio);
                processedAudio.addAll(splitAudio);
                processedAudio.addAll(otherAudio);
                break;
        }

        updateChipStyles();

        int shown = processedAudio.size();
        subtitleText.setText(shown > 0
                ? shown + (shown == 1 ? " file" : " files") + " • aud_player folder"
                : "aud_player folder");

        if (processedAudio.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            emptyText.setText(emptyMessage());
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
        }

        adapter.updateList(processedAudio);
    }

    /** Selected chip reads solid white on the accent pill, as in the editor tabs. */
    private void updateChipStyles() {
        styleChip(allCountText, currentFilter == FILTER_ALL);
        styleChip(mergedCountText, currentFilter == FILTER_MERGED);
        styleChip(splitCountText, currentFilter == FILTER_SPLIT);
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
                return "No merged audio yet";
            case FILTER_SPLIT:
                return "No split audio yet";
            case FILTER_OTHER:
                return "No other audio";
            default:
                return "No processed audio yet\n\nFiles from Merge & Split will appear here";
        }
    }

    /** Audio files directly inside {@code dir}, never null. */
    private static File[] listAudioFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return new File[0];
        File[] files = dir.listFiles((f, name) -> isAudioName(name));
        return files != null ? files : new File[0];
    }

    /** Adds the audio files in {@code dir} to {@code target}, skipping paths already listed. */
    private void scanInto(File dir, List<AudioFile> target, Set<String> seen) {
        for (File f : listAudioFiles(dir)) {
            if (seen.add(f.getAbsolutePath())) {
                target.add(audioFileFromFile(f));
            }
        }
    }

    private static boolean isAudioName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String ext : AUDIO_EXTENSIONS) {
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
            File dir = FFmpegAudioEditor.getOutputDir(this, subfolder);
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

    private AudioFile audioFileFromFile(File f) {
        String name = f.getName();
        long size = f.length();
        long date = f.lastModified();

        long durationMs = 0;
        try {
            // release() rather than try-with-resources: MediaMetadataRetriever
            // only became AutoCloseable in API 29, minSdk here is 24.
            android.media.MediaMetadataRetriever retriever =
                    new android.media.MediaMetadataRetriever();
            retriever.setDataSource(f.getAbsolutePath());
            String dur = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) durationMs = Long.parseLong(dur);
            retriever.release();
        } catch (Throwable ignored) {}

        Uri uri = Uri.fromFile(f);
        return new AudioFile(name, formatDuration(durationMs), uri, 0, size, date);
    }

    /** "m:ss", the same shape the library lists use. */
    private static String formatDuration(long ms) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(ms);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(ms) - TimeUnit.MINUTES.toSeconds(minutes);
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    /** Tap plays the file. */
    private void playAudio(AudioFile audioFile) {
        Intent intent = new Intent(this, AudioPlayerActivity.class);
        intent.putExtra(AudioPlayerActivity.EXTRA_PATH, audioFile.getUri().getPath());
        intent.putExtra(AudioPlayerActivity.EXTRA_TITLE, audioFile.getTitle());
        startActivity(intent);
    }

    /** Long-press (or the row's three-dot) offers the actions for a finished file. */
    private void showOptionsDialog(AudioFile audioFile) {
        final String[] options = {"Play", "Rename", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(audioFile.getTitle())
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        playAudio(audioFile);
                    } else if (which == 1) {
                        showRenameDialog(audioFile);
                    } else {
                        showDeleteDialog(audioFile);
                    }
                })
                .show();
    }

    private void showRenameDialog(AudioFile audioFile) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(audioFile.getTitle());
        input.setSelection(input.getText().length());

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename audio");
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
            if (renameAudio(audioFile, newName)) {
                dialog.dismiss();
            }
        });
    }

    /**
     * Renames the file on disk, keeping the original extension when the user
     * leaves it off. Returns true when the dialog can close.
     */
    private boolean renameAudio(AudioFile audioFile, String newName) {
        try {
            String currentTitle = audioFile.getTitle();
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

            File src = new File(audioFile.getUri().getPath());
            if (!src.exists()) {
                android.widget.Toast.makeText(this, "File not found",
                        android.widget.Toast.LENGTH_SHORT).show();
                loadProcessedAudio();
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

            // Let the music library and other apps notice the change.
            MediaScannerConnection.scanFile(this,
                    new String[]{src.getAbsolutePath(), dst.getAbsolutePath()}, null, null);

            android.widget.Toast.makeText(this, "Renamed", android.widget.Toast.LENGTH_SHORT).show();
            loadProcessedAudio();
            return true;
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "Could not rename the file",
                    android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void showDeleteDialog(AudioFile audioFile) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this file?")
                .setMessage(audioFile.getTitle()
                        + "\n\nThis file will be permanently deleted.")
                .setPositiveButton("Delete", (d, w) -> deleteAudio(audioFile))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteAudio(AudioFile audioFile) {
        Uri uri = audioFile.getUri();
        if ("file".equals(uri.getScheme())) {
            File f = new File(uri.getPath());
            if (f.exists() && f.delete()) {
                MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
                loadProcessedAudio();
                return;
            }
        }
        try {
            int deleted = getContentResolver().delete(uri, null, null);
            if (deleted > 0) {
                loadProcessedAudio();
                return;
            }
        } catch (Throwable ignored) {}
        android.widget.Toast.makeText(this, "Could not delete the file",
                android.widget.Toast.LENGTH_SHORT).show();
    }
}
