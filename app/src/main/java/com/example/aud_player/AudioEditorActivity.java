package com.example.aud_player;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Audio Editor: cut/trim, multi-split and merge for the currently selected
 * track, powered by FFmpeg (FFmpegAudioEditor).
 *
 * - Cut/Trim tab: two draggable A/B handles on the timeline.
 * - Split tab: professional timeline editor (video-editor style):
 *   scrollable + zoomable waveform timeline with a time ruler, numbered
 *   segment blocks, draggable split boundaries, tap-to-position playhead,
 *   play/pause and per-segment preview.
 * - Save options: separately (one file per segment) or merged — sequential
 *   order by default, manual reorder (▲/▼) before merging.
 * - Preview uses an independent MediaPlayer, leaving the main app
 *   playback untouched.
 */
public class AudioEditorActivity extends AppCompatActivity {

    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_DURATION_MS = "extra_duration_ms";

    private static final int TAB_CUT = 0;
    private static final int TAB_SPLIT = 1;
    private static final long PLAYHEAD_UPDATE_MS = 33; // ~30fps smooth playhead

    private Uri sourceUri;
    private String songTitle;
    private long durationMs;

    private EditorTimelineView timeline;
    private HorizontalScrollView timelineScroll;
    private TextView rangeText, durationText, hintText, currentTimeText, zoomText;
    private TextView tabCut, tabSplit, addSplitBtn, deleteSplitBtn, previewBtn, saveBtn;
    private TextView zoomOutBtn, zoomInBtn;
    private ImageButton playBtn;
    private View cutActionRow, controlBar;
    private LinearLayout segmentList;
    private View progressOverlay;
    private ProgressBar progressBar;
    private TextView progressText, progressTitle;
    private View cancelBtn;

    private int currentTab = TAB_CUT;
    private boolean jobRunning = false;

    private final FFmpegAudioEditor editor = new FFmpegAudioEditor();
    private final WaveformExtractor waveformExtractor = new WaveformExtractor();

    private MediaPlayer previewPlayer;
    private long previewStartMs, previewEndMs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable playheadUpdater = new Runnable() {
        @Override
        public void run() {
            if (previewPlayer != null && timeline != null) {
                try {
                    int pos = previewPlayer.getCurrentPosition();
                    if (previewEndMs > 0 && pos >= previewEndMs - 40) {
                        stopPreview();
                        return;
                    }
                    timeline.setPlayheadMs(pos);
                    updateCurrentTimeText(pos);
                    followPlayhead();
                } catch (Exception ignored) {
                }
                mainHandler.postDelayed(this, PLAYHEAD_UPDATE_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio_editor);

        Intent intent = getIntent();
        String uriStr = intent.getStringExtra(EXTRA_URI);
        if (uriStr == null) {
            finish();
            return;
        }
        sourceUri = Uri.parse(uriStr);
        songTitle = intent.getStringExtra(EXTRA_TITLE);
        if (songTitle == null) songTitle = "audio";
        durationMs = Math.max(1000, intent.getLongExtra(EXTRA_DURATION_MS, 0));
        if (durationMs <= 1000) {
            durationMs = probeDuration(sourceUri);
        }

        bindViews();
        setupTabs();
        setupTimeline();
        setupActions();

        TextView songName = findViewById(R.id.editorSongName);
        songName.setText(songTitle);

        // Timeline setup: fit-all scale after first layout
        timeline.setDuration(durationMs);
        timeline.setPlayheadMs(0);
        timeline.setCutSelection(0, durationMs);
        timeline.setMode(EditorTimelineView.MODE_CUT);
        timeline.post(() -> {
            if (isFinishing() || timeline == null) return;
            applyFitZoom();
        });

        // Waveform (null result falls back to placeholder bars)
        waveformExtractor.extract(this, sourceUri, data -> {
            if (!isFinishing()) timeline.setWaveform(data);
        });

        updateUiForTab();
    }

    private void bindViews() {
        timeline = findViewById(R.id.editorTimeline);
        timelineScroll = findViewById(R.id.editorTimelineScroll);
        rangeText = findViewById(R.id.editorRangeText);
        durationText = findViewById(R.id.editorDurationText);
        currentTimeText = findViewById(R.id.editorCurrentTimeText);
        zoomText = findViewById(R.id.editorZoomText);
        hintText = findViewById(R.id.editorHintText);
        tabCut = findViewById(R.id.editorTabCut);
        tabSplit = findViewById(R.id.editorTabSplit);
        addSplitBtn = findViewById(R.id.editorAddSplitBtn);
        deleteSplitBtn = findViewById(R.id.editorDeleteSplitBtn);
        previewBtn = findViewById(R.id.editorPreviewBtn);
        saveBtn = findViewById(R.id.editorSaveBtn);
        zoomOutBtn = findViewById(R.id.editorZoomOutBtn);
        zoomInBtn = findViewById(R.id.editorZoomInBtn);
        playBtn = findViewById(R.id.editorPlayBtn);
        cutActionRow = findViewById(R.id.editorCutActionRow);
        controlBar = findViewById(R.id.editorControlBar);
        segmentList = findViewById(R.id.editorSegmentList);
        progressOverlay = findViewById(R.id.editorProgressOverlay);
        progressBar = findViewById(R.id.editorProgressBar);
        progressText = findViewById(R.id.editorProgressText);
        progressTitle = findViewById(R.id.editorProgressTitle);
        cancelBtn = findViewById(R.id.editorCancelBtn);

        findViewById(R.id.editorBackBtn).setOnClickListener(v -> finish());
        durationText.setText("Total " + formatTime(durationMs));
    }

    private void setupTabs() {
        tabCut.setOnClickListener(v -> {
            currentTab = TAB_CUT;
            updateUiForTab();
        });
        tabSplit.setOnClickListener(v -> {
            currentTab = TAB_SPLIT;
            updateUiForTab();
        });
    }

    private void setupTimeline() {
        timeline.setTimelineListener(new EditorTimelineView.TimelineListener() {
            @Override
            public void onSplitPointsChanged(List<Long> pointsMs) {
                renderSegmentList();
                updateRangeText();
            }

            @Override
            public void onCutSelectionChanged(long startMs, long endMs) {
                updateRangeText();
            }

            @Override
            public void onPlayheadMoved(long playheadMs) {
                updateCurrentTimeText(playheadMs);
                // live seek while playing (pro editor behaviour)
                if (previewPlayer != null && previewPlayer.isPlaying()) {
                    try {
                        previewPlayer.seekTo((int) playheadMs);
                        previewEndMs = Math.max(previewEndMs, playheadMs + 200);
                    } catch (Exception ignored) {
                    }
                }
            }

            @Override
            public void onSplitSelected(int index) {
                deleteSplitBtn.setEnabled(index >= 0);
                deleteSplitBtn.setAlpha(index >= 0 ? 1f : 0.55f);
                updateRangeText();
            }

            @Override
            public void onSegmentSelected(int index) {
                renderSegmentList();
                updateRangeText();
            }
        });
    }

    private void setupActions() {
        addSplitBtn.setOnClickListener(v -> addSplitAtPlayhead());
        deleteSplitBtn.setOnClickListener(v -> {
            if (timeline.getSelectedSplit() < 0) {
                Toast.makeText(this, "Tap a split line on the timeline first",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            timeline.deleteSelectedMarker();
        });
        previewBtn.setOnClickListener(v -> toggleCutPreview());
        playBtn.setOnClickListener(v -> togglePlayPause());
        zoomOutBtn.setOnClickListener(v -> zoomBy(1f / 1.6f));
        zoomInBtn.setOnClickListener(v -> zoomBy(1.6f));
        saveBtn.setOnClickListener(v -> confirmSave());
        cancelBtn.setOnClickListener(v -> {
            if (jobRunning) {
                editor.cancel();
                progressTitle.setText("Cancelling…");
            }
        });
    }

    // ------------------------------------------------------------------
    // Tab / UI state
    // ------------------------------------------------------------------

    private void updateUiForTab() {
        boolean cut = currentTab == TAB_CUT;
        int active = 0xFFFFFFFF;
        int inactive = 0x88FFFFFF;
        tabCut.setTextColor(cut ? active : inactive);
        tabSplit.setTextColor(cut ? inactive : active);

        cutActionRow.setVisibility(cut ? View.VISIBLE : View.GONE);
        controlBar.setVisibility(cut ? View.GONE : View.VISIBLE);

        timeline.setMode(cut ? EditorTimelineView.MODE_CUT
                : EditorTimelineView.MODE_SPLIT);
        if (cut) {
            // cut mode: non-scrolling, content == viewport
            applyFitZoom();
        }

        hintText.setText(cut
                ? "Drag A / B handles to select the part to keep, then press Preview."
                : "Tap the timeline to position the playhead, then ✂ Split. Drag split lines to adjust; tap one to select it.");
        renderSegmentList();
        updateRangeText();
        updateCurrentTimeText(timeline.getPlayheadMs());
        updateZoomText();
    }

    private void updateRangeText() {
        if (currentTab == TAB_CUT) {
            long s = timeline.getCutStartMs();
            long e = timeline.getCutEndMs();
            rangeText.setText(formatTime(s) + " → " + formatTime(e)
                    + "  (" + formatTime(Math.max(0, e - s)) + ")");
        } else {
            List<long[]> segs = timeline.getSegments();
            int sel = timeline.getSelectedSplit();
            String splitInfo = sel >= 0
                    ? "Split " + (sel + 1) + " at "
                    + formatTime(timeline.getSplitPoints().get(sel))
                    : "No split selected";
            rangeText.setText(splitInfo + "  •  " + segs.size() + " part"
                    + (segs.size() == 1 ? "" : "s"));
        }
    }

    private void updateCurrentTimeText(long ms) {
        currentTimeText.setText("▶ " + formatTime(ms));
    }

    private void updateZoomText() {
        float z = timeline.getZoomLevel();
        zoomText.setText(String.format("Zoom %.1fx", z));
    }

    // ------------------------------------------------------------------
    // Zoom
    // ------------------------------------------------------------------

    /** Fit-all zoom: content width == viewport width. */
    private void applyFitZoom() {
        int viewport = timelineScroll.getWidth();
        if (viewport <= 0) return;
        float fit = viewport / (float) durationMs;
        timeline.setFitPxPerMs(fit);
        timeline.setPxPerMs(fit);
        updateZoomText();
    }

    /** Zoom while keeping the viewport center time stable. */
    private void zoomBy(float factor) {
        int viewport = timelineScroll.getWidth();
        if (viewport <= 0) return;
        float oldPx = timeline.getPxPerMs();
        long centerMs = (long) ((timelineScroll.getScrollX() + viewport / 2f) / oldPx);
        timeline.setFitPxPerMs(Math.max(timeline.getFitPxPerMs(),
                viewport / (float) durationMs));
        timeline.setPxPerMs(oldPx * factor);
        updateZoomText();
        final int targetX = (int) (centerMs * timeline.getPxPerMs() - viewport / 2f);
        timeline.post(() -> {
            if (timeline == null || timelineScroll == null) return;
            timelineScroll.scrollTo(Math.max(0, targetX), 0);
        });
    }

    /** Keep the playhead visible during playback (page-flip follow). */
    private void followPlayhead() {
        if (previewPlayer == null) return;
        int viewport = timelineScroll.getWidth();
        float px = timeline.getPlayheadX() - timelineScroll.getScrollX();
        if (px > viewport - dp(48) || px < 0) {
            int target = (int) Math.max(0, timeline.getPlayheadX() - viewport / 2f);
            timelineScroll.smoothScrollTo(target, 0);
        }
    }

    // ------------------------------------------------------------------
    // Split management
    // ------------------------------------------------------------------

    /** Pro workflow: add a split at the current playhead position. */
    private void addSplitAtPlayhead() {
        long t = timeline.getPlayheadMs();
        int idx = timeline.addSplitPoint(t);
        if (idx < 0) {
            Toast.makeText(this, "Can't split there — too close to the start, "
                    + "end or another split", Toast.LENGTH_SHORT).show();
            return;
        }
        renderSegmentList();
        updateRangeText();
        // make the new boundary visible
        int viewport = timelineScroll.getWidth();
        float x = t * timeline.getPxPerMs();
        if (x < timelineScroll.getScrollX() || x > timelineScroll.getScrollX() + viewport) {
            timelineScroll.smoothScrollTo((int) Math.max(0, x - viewport / 2f), 0);
        }
    }

    private void renderSegmentList() {
        segmentList.removeAllViews();
        List<long[]> segs = timeline.getSegments();
        if (currentTab == TAB_CUT) {
            long[] seg = segs.get(0);
            segmentList.addView(buildSegmentRow(1, seg[0], seg[1], false, -1));
            return;
        }
        int selectedSeg = timeline.getSelectedSegment();
        for (int i = 0; i < segs.size(); i++) {
            long[] seg = segs.get(i);
            boolean isEnd = i == segs.size() - 1;
            segmentList.addView(buildSegmentRow(i + 1, seg[0], seg[1], isEnd,
                    selectedSeg));
        }
    }

    private View buildSegmentRow(final int index, long startMs, long endMs,
                                 boolean isLast, int selectedSeg) {
        final boolean selected = (index - 1) == selectedSeg && currentTab == TAB_SPLIT;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(selected
                ? R.drawable.bg_editor_segment_selected : R.drawable.bg_editor_segment_item);
        int pad = dp(12);
        row.setPadding(pad, dp(10), pad, dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textCol.setLayoutParams(tcLp);

        TextView title = new TextView(this);
        title.setText("Part " + index + (isLast ? "  (till end)" : ""));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(13f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(title);

        TextView times = new TextView(this);
        times.setText(formatTime(startMs) + " → " + formatTime(endMs)
                + "   (" + formatTime(Math.max(0, endMs - startMs)) + ")");
        times.setTextColor(0xAAFFFFFF);
        times.setTextSize(12f);
        textCol.addView(times);

        row.addView(textCol);

        ImageButton play = new ImageButton(this);
        play.setImageResource(R.drawable.ic_play);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        play.setBackgroundResource(tv.resourceId != 0 ? tv.resourceId : 0);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        pbLp.leftMargin = dp(8);
        play.setLayoutParams(pbLp);
        play.setOnClickListener(v -> previewRange(startMs, endMs));
        row.addView(play);

        // tapping a row selects that segment on the timeline
        row.setOnClickListener(v -> {
            if (currentTab == TAB_SPLIT) {
                timeline.selectSegment(index - 1);
            }
        });

        return row;
    }

    // ------------------------------------------------------------------
    // Playback / preview
    // ------------------------------------------------------------------

    /** Cut tab: play the A/B selection, or stop if already playing. */
    private void toggleCutPreview() {
        if (previewPlayer != null) {
            stopPreview();
            return;
        }
        previewRange(timeline.getCutStartMs(), timeline.getCutEndMs());
    }

    /** Split tab control bar: play from playhead / pause / resume. */
    private void togglePlayPause() {
        if (previewPlayer == null) {
            // start from the playhead to the end of the track
            previewRange(timeline.getPlayheadMs(), durationMs);
            return;
        }
        try {
            if (previewPlayer.isPlaying()) {
                previewPlayer.pause();
            } else {
                previewPlayer.start();
                mainHandler.post(playheadUpdater);
            }
        } catch (Exception ignored) {
        }
        updatePlayButtons();
    }

    private void previewRange(long startMs, long endMs) {
        stopPreview();
        try {
            previewPlayer = new MediaPlayer();
            previewPlayer.setDataSource(this, sourceUri);
            previewPlayer.setOnPreparedListener(mp -> {
                try {
                    previewStartMs = startMs;
                    previewEndMs = endMs;
                    mp.seekTo((int) startMs);
                    mp.start();
                    updatePlayButtons();
                    mainHandler.post(playheadUpdater);
                } catch (Exception e) {
                    stopPreview();
                }
            });
            previewPlayer.setOnCompletionListener(mp -> stopPreview());
            previewPlayer.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "Preview failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            stopPreview();
        }
    }

    private void stopPreview() {
        mainHandler.removeCallbacks(playheadUpdater);
        if (previewPlayer != null) {
            try {
                previewPlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                previewPlayer.release();
            } catch (Exception ignored) {
            }
            previewPlayer = null;
        }
        updatePlayButtons();
        if (timeline != null) updateCurrentTimeText(timeline.getPlayheadMs());
    }

    /** Reflect playback state in the play button + cut preview button. */
    private void updatePlayButtons() {
        boolean playing = false;
        if (previewPlayer != null) {
            try {
                playing = previewPlayer.isPlaying();
            } catch (Exception ignored) {
            }
        }
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        previewBtn.setText(playing ? "■ Stop" : "▶ Preview");
    }

    // ------------------------------------------------------------------
    // Save flow
    // ------------------------------------------------------------------

    private void confirmSave() {
        if (jobRunning) return;
        if (currentTab == TAB_CUT) {
            runCutJob();
            return;
        }
        // split mode: choose output style
        View sheet = LayoutInflater.from(this).inflate(R.layout.dialog_editor_save_options, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Save Split Result")
                .setView(sheet)
                .setNegativeButton("Cancel", null)
                .create();

        sheet.findViewById(R.id.saveOptionSeparate).setOnClickListener(v -> {
            dialog.dismiss();
            runSplitJob(false, null);
        });
        sheet.findViewById(R.id.saveOptionMerge).setOnClickListener(v -> {
            dialog.dismiss();
            showMergeOrderDialog();
        });
        dialog.show();
    }

    /** Order editor for merge: sequential by default, up/down to reorder. */
    private void showMergeOrderDialog() {
        final List<long[]> segs = timeline.getSegments();
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) order.add(i);

        View sheet = LayoutInflater.from(this).inflate(R.layout.dialog_editor_merge_order, null);
        LinearLayout list = sheet.findViewById(R.id.mergeOrderList);

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            list.removeAllViews();
            for (int pos = 0; pos < order.size(); pos++) {
                final int p = pos;
                long[] seg = segs.get(order.get(pos));
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundResource(R.drawable.bg_editor_segment_item);
                row.setPadding(dp(12), dp(8), dp(8), dp(8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                row.setLayoutParams(lp);

                TextView label = new TextView(this);
                label.setText((pos + 1) + ".  Part " + (order.get(pos) + 1)
                        + "   (" + formatTime(seg[0]) + " → " + formatTime(seg[1]) + ")");
                label.setTextColor(0xFFFFFFFF);
                label.setTextSize(13f);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                label.setLayoutParams(ll);
                row.addView(label);

                TextView up = new TextView(this);
                up.setText("▲");
                up.setTextColor(0xFF6C63FF);
                up.setTextSize(16f);
                up.setPadding(dp(10), 0, dp(10), 0);
                up.setOnClickListener(v -> {
                    if (p > 0) {
                        int val = order.remove(p);
                        order.add(p - 1, val);
                        rebuild[0].run();
                    }
                });
                row.addView(up);

                TextView down = new TextView(this);
                down.setText("▼");
                down.setTextColor(0xFF6C63FF);
                down.setTextSize(16f);
                down.setPadding(dp(10), 0, dp(10), 0);
                down.setOnClickListener(v -> {
                    if (p < order.size() - 1) {
                        int val = order.remove(p);
                        order.add(p + 1, val);
                        rebuild[0].run();
                    }
                });
                row.addView(down);

                list.addView(row);
            }
        };
        rebuild[0].run();

        new AlertDialog.Builder(this)
                .setTitle("Merge Order")
                .setView(sheet)
                .setPositiveButton("Merge", (d, w) -> runSplitJob(true, order))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String baseName() {
        String n = songTitle;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n;
    }

    private void runCutJob() {
        List<FFmpegAudioEditor.Segment> segs = new ArrayList<>();
        segs.add(new FFmpegAudioEditor.Segment(
                timeline.getCutStartMs(), timeline.getCutEndMs()));
        startJob(FFmpegAudioEditor.MODE_CUT, segs, baseName() + "_cut");
    }

    private void runSplitJob(boolean merge, List<Integer> customOrder) {
        List<long[]> segs = timeline.getSegments();
        List<FFmpegAudioEditor.Segment> ordered = new ArrayList<>();
        if (merge && customOrder != null) {
            for (int idx : customOrder) {
                long[] s = segs.get(idx);
                ordered.add(new FFmpegAudioEditor.Segment(s[0], s[1]));
            }
        } else {
            for (long[] s : segs) {
                ordered.add(new FFmpegAudioEditor.Segment(s[0], s[1]));
            }
        }
        int mode = merge ? FFmpegAudioEditor.MODE_MERGE : FFmpegAudioEditor.MODE_SEPARATE;
        startJob(mode, ordered, baseName());
    }

    private void startJob(int mode, List<FFmpegAudioEditor.Segment> segments, String name) {
        stopPreview();
        jobRunning = true;
        progressTitle.setText("Processing…");
        progressBar.setIndeterminate(true);
        progressBar.setProgress(0);
        progressText.setText("Preparing…");
        progressOverlay.setVisibility(View.VISIBLE);

        editor.runJobFromUri(this, sourceUri, mode, segments, name,
                new FFmpegAudioEditor.EditListener() {
                    @Override
                    public void onProgress(int percent) {
                        if (percent < 0) {
                            progressBar.setIndeterminate(true);
                            progressText.setText("Preparing…");
                        } else {
                            progressBar.setIndeterminate(false);
                            progressBar.setProgress(percent);
                            progressText.setText(percent + "%");
                        }
                    }

                    @Override
                    public void onSuccess(List<File> outputs) {
                        jobRunning = false;
                        progressOverlay.setVisibility(View.GONE);
                        scanOutputs(outputs);
                        showResultDialog(outputs);
                    }

                    @Override
                    public void onError(String message, boolean cancelled) {
                        jobRunning = false;
                        progressOverlay.setVisibility(View.GONE);
                        if (cancelled) {
                            Toast.makeText(AudioEditorActivity.this,
                                    "Cancelled", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(AudioEditorActivity.this,
                                    "Failed: " + message, Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    /** Make the outputs visible to other media apps. */
    private void scanOutputs(List<File> outputs) {
        try {
            String[] paths = new String[outputs.size()];
            String[] mimes = new String[outputs.size()];
            for (int i = 0; i < outputs.size(); i++) {
                paths[i] = outputs.get(i).getAbsolutePath();
                mimes[i] = "audio/*";
            }
            android.media.MediaScannerConnection.scanFile(this, paths, mimes, null);
        } catch (Exception ignored) {
        }
    }

    private void showResultDialog(List<File> outputs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < outputs.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(outputs.get(i).getName());
        }
        File dir = outputs.isEmpty() ? null : outputs.get(0).getParentFile();
        new AlertDialog.Builder(this)
                .setTitle(outputs.size() == 1 ? "Saved" : "Saved " + outputs.size() + " files")
                .setMessage(sb + (dir != null ? "\n\nFolder: " + dir.getAbsolutePath() : ""))
                .setPositiveButton("Done", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private long probeDuration(Uri uri) {
        try {
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(this, uri);
            String d = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            mmr.release();
            if (!TextUtils.isEmpty(d)) return Long.parseLong(d);
        } catch (Exception ignored) {
        }
        return 60000;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String formatTime(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPreview();
        editor.release();
        waveformExtractor.release();
    }

    @Override
    public void finish() {
        if (jobRunning) {
            editor.cancel();
        }
        super.finish();
    }
}
