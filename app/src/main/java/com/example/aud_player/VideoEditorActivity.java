package com.example.aud_player;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Video Editor: Lossless multi-split and merge using strict stream copy (-c copy).
 * Features exact UI/UX parity with Audio Editor:
 * - Live video preview viewport (TextureView + MediaPlayer).
 * - Merge tab: independent list, empty by default, multi-select picker, row progress scrub, drag reorder.
 * - Split tab: timeline view, playhead sync, draggable splits, segment deletion with compression,
 *   50-level Undo/Redo, drag-and-drop reordering, sequence preview.
 * - Save: Save separately or Save merged using FFmpeg stream copy with zero re-encoding.
 */
public class VideoEditorActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {

    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_DURATION_MS = "extra_duration_ms";

    private static final int TAB_MERGE = 0;
    private static final int TAB_SPLIT = 1;
    private static final long PLAYHEAD_UPDATE_MS = 33;
    private static final int MAX_HISTORY = 50;
    private static final float DEFAULT_SPLIT_ZOOM = 10f;

    private static class EditorState {
        final List<Long> splitPoints;
        final List<long[]> deletedRanges;
        final List<Integer> sequence;
        final int selectedSplit;
        final int selectedSegment;

        EditorState(List<Long> splitPoints, List<long[]> deletedRanges,
                    List<Integer> sequence, int selectedSplit, int selectedSegment) {
            this.splitPoints = new ArrayList<>(splitPoints);
            this.deletedRanges = new ArrayList<>();
            if (deletedRanges != null) {
                for (long[] r : deletedRanges) {
                    this.deletedRanges.add(new long[]{r[0], r[1]});
                }
            }
            this.sequence = new ArrayList<>(sequence == null ? new ArrayList<>() : sequence);
            this.selectedSplit = selectedSplit;
            this.selectedSegment = selectedSegment;
        }
    }

    private final Deque<EditorState> undoStack = new ArrayDeque<>();
    private final Deque<EditorState> redoStack = new ArrayDeque<>();

    private static class Source {
        final Uri uri;
        final String title;
        final long durationMs;
        long offsetMs;
        float[] waveform;
        boolean waveformRequested;
        /**
         * Keyframe times of this video (ms from its own start), or null while
         * the probe is still running. A lossless stream copy can only START a
         * cut on a keyframe, so the timeline snaps its markers onto these -
         * that is what makes the drawn cut line and the written cut agree, and
         * what keeps a split-then-merged video exactly as long as the source.
         */
        long[] keyframesMs;
        /**
         * Full probe result: keyframes plus the stream signature (codec,
         * picture size, audio format). A merge cannot change those, so they
         * are checked before a job starts instead of writing a file that plays
         * wrong.
         */
        FFmpegVideoEditor.MediaInfo media;
        boolean keyframesRequested;
        /** True once the probe has answered (even if it found nothing). */
        boolean probeDone;

        Source(Uri uri, String title, long durationMs) {
            this.uri = uri;
            this.title = title;
            this.durationMs = Math.max(1, durationMs);
        }
    }

    private final List<Source> sources = new ArrayList<>();
    private String videoTitle;
    private long durationMs;

    private final List<Source> mergeSources = new ArrayList<>();
    private long mergeDurationMs;

    private FrameLayout videoPreviewContainer;
    private TextureView videoTextureView;
    private Surface videoSurface;
    private View videoEmptyContainer;
    private View videoEmptyPickBtn;

    private EditorTimelineView timeline;
    private HorizontalScrollView timelineScroll;
    private TextView rangeText, durationText, hintText, currentTimeText, zoomText;
    private TextView tabMerge, tabSplit, addSplitBtn, saveBtn, clearBtn;
    private TextView zoomOutBtn, zoomInBtn, undoBtn, redoBtn, seqPreviewBtn, songNameText;
    private TextView mergeInfoText;
    private ImageButton playBtn, addSongBtn, deleteSplitBtn, mergeAddSongBtn;
    private ImageButton autoScrollBtn;
    private boolean autoScrollEnabled = false;
    private View timelinePanel, mergeActionRow, controlBar, controlBarScroll, undoRedoRow;
    private LinearLayout segmentList;
    private View progressOverlay;
    private ProgressBar progressBar;
    private TextView progressText, progressTitle;
    private View cancelBtn;

    private static final String PREFS = "video_editor_prefs";
    private static final String KEY_LAST_TAB = "last_tab";

    private int currentTab = TAB_MERGE;
    private boolean splitZoomInitialized = false;
    private boolean jobRunning = false;

    private static final int DRAG_PARTS = 0;
    private static final int DRAG_SOURCES = 1;
    private int dragMode = DRAG_PARTS;
    private View dragRow;
    private int dragFromPos = -1;
    private int dragTargetPos = -1;
    private float dragLastRawY;
    private float dragDownRawY;
    private boolean rowDragMoved;
    private boolean suppressNextRowClick;

    private List<long[]> previewQueue;
    private int previewQueueIndex = -1;
    private long lastQueueAdvanceEnd = Long.MIN_VALUE;

    /** How long to wait for keyframe probes before starting the job anyway. */
    private static final long PROBE_WAIT_MS = 12000L;
    private static final long PROBE_POLL_MS = 120L;

    private Runnable pendingJob;
    private long pendingJobDeadline;
    private List<String> jobWarnings = Collections.emptyList();

    private final FFmpegVideoEditor editor = new FFmpegVideoEditor();
    private final WaveformExtractor waveformExtractor = new WaveformExtractor();

    private MediaPlayer previewPlayer;
    private long previewStartMs, previewEndMs;
    private int previewSourceIndex;
    private List<Source> previewList;
    private final List<ProgressBar> mergeRowBars = new ArrayList<>();
    private final List<ImageButton> mergeRowButtons = new ArrayList<>();
    /** Split-tab part rows' ▶ buttons, and the [startMs, endMs] each one plays. */
    private final List<ImageButton> partRowButtons = new ArrayList<>();
    private final List<long[]> partRowRanges = new ArrayList<>();

    private final ActivityResultLauncher<Intent> videoPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) appendVideo(uri);
                }
            });

    private final ActivityResultLauncher<Intent> primaryPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) loadPrimaryVideo(uri);
                }
            });

    private final ActivityResultLauncher<Intent> mergePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                ArrayList<Uri> uris = data.getParcelableArrayListExtra(VideoListActivity.EXTRA_PICKED_URIS);
                if (uris == null || uris.isEmpty()) {
                    Uri single = data.getData();
                    if (single == null) return;
                    uris = new ArrayList<>();
                    uris.add(single);
                }
                addMergeVideos(uris);
            });

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable playheadUpdater = new Runnable() {
        @Override
        public void run() {
            if (previewPlayer != null && timeline != null) {
                try {
                    long pos = toGlobal(previewSourceIndex, previewPlayer.getCurrentPosition());
                    // A player that has just started can still report the
                    // position it held BEFORE the seek (usually 0) for the
                    // first few ticks. Letting that through dragged the
                    // playhead — and the auto-scroll — back to the start of
                    // the video, which read as playback having started in a
                    // different part. The range never starts before
                    // previewStartMs, so never report less than that.
                    if (pos < previewStartMs) pos = previewStartMs;
                    if (previewList == null) {
                        long mapped = timeline.skipDeletedForward(pos);
                        if (mapped < 0) {
                            stopPreview();
                            return;
                        }
                        if (mapped != pos) {
                            seekPreviewTo(mapped);
                            pos = mapped;
                        }
                    }
                    if (previewEndMs > 0 && pos >= previewEndMs - 40) {
                        if (previewQueue == null) {
                            stopPreview();
                            return;
                        }
                        if (previewEndMs != lastQueueAdvanceEnd) {
                            lastQueueAdvanceEnd = previewEndMs;
                            advanceSequencePreview();
                            if (previewPlayer == null) return;
                            pos = toGlobal(previewSourceIndex, previewPlayer.getCurrentPosition());
                        } else {
                            pos = previewStartMs;
                        }
                    }
                    if (previewList == null) {
                        timeline.setPlayheadMs(pos);
                        if (autoScrollEnabled) followPlayhead();
                    } else {
                        updateMergeRowProgress(pos);
                    }
                    updateCurrentTimeText(pos);
                } catch (Exception ignored) {}
                mainHandler.postDelayed(this, PLAYHEAD_UPDATE_MS);
            }
        }
    };

    private final Runnable dragAutoScroll = new Runnable() {
        @Override
        public void run() {
            if (dragRow == null) return;
            ScrollView sv = (ScrollView) segmentList.getParent();
            int[] loc = new int[2];
            sv.getLocationOnScreen(loc);
            float inSv = dragLastRawY - loc[1];
            int edge = dp(56);
            int speed = dp(10);
            boolean scrolled = false;
            if (inSv < edge && sv.getScrollY() > 0) {
                sv.smoothScrollBy(0, -speed);
                scrolled = true;
            } else if (inSv > sv.getHeight() - edge
                    && sv.getScrollY() + sv.getHeight() < sv.getChildAt(0).getHeight()) {
                sv.smoothScrollBy(0, speed);
                scrolled = true;
            }
            if (scrolled) {
                positionDragRow(dragLastRawY);
                updateRowDragTargets();
            }
            mainHandler.postDelayed(this, 64);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_editor);

        Intent intent = getIntent();
        List<Uri> shared = sharedVideoUris(intent);
        boolean fromShare = !shared.isEmpty();

        if (fromShare) {
            Uri firstUri = shared.get(0);
            videoTitle = displayNameOf(firstUri);
            sources.add(new Source(firstUri, videoTitle, Math.max(1000, probeDuration(firstUri))));
            if (shared.size() > 1) {
                Source first = sources.get(0);
                mergeSources.add(new Source(first.uri, first.title, first.durationMs));
                for (int i = 1; i < shared.size(); i++) {
                    Uri u = shared.get(i);
                    mergeSources.add(new Source(u, displayNameOf(u), Math.max(1000, probeDuration(u))));
                }
                rebuildMergeMap();
            }
        } else {
            String uriStr = intent.getStringExtra(EXTRA_URI);
            if (uriStr != null) {
                Uri firstUri = Uri.parse(uriStr);
                videoTitle = intent.getStringExtra(EXTRA_TITLE);
                if (videoTitle == null) videoTitle = displayNameOf(firstUri);
                long firstDurationMs = Math.max(1000, intent.getLongExtra(EXTRA_DURATION_MS, 0));
                if (firstDurationMs <= 1000) {
                    firstDurationMs = probeDuration(firstUri);
                }
                sources.add(new Source(firstUri, videoTitle, firstDurationMs));
            }
        }

        rebuildTimeMap();
        bindViews();
        setupVideoTexture();
        setupTabs();
        setupTimeline();
        setupActions();

        updateSongNameLabel();

        timeline.setDuration(durationMs);
        timeline.setPlayheadMs(0);
        timeline.post(() -> {
            if (isFinishing() || timeline == null) return;
            applyFitZoom();
            if (currentTab == TAB_SPLIT) applyDefaultSplitZoom();
        });

        extractWaveforms();
        requestKeyframes();

        currentTab = fromShare
                ? (shared.size() > 1 ? TAB_MERGE : TAB_SPLIT)
                : getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_LAST_TAB, TAB_MERGE);

        updateUiForTab();
    }

    private List<Uri> sharedVideoUris(Intent intent) {
        List<Uri> out = new ArrayList<>();
        if (intent == null || intent.getExtras() == null) return out;
        String action = intent.getAction();
        Object raw = intent.getExtras().get(Intent.EXTRA_STREAM);
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = streamUri(raw);
            if (u != null) out.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            if (raw instanceof List) {
                for (Object o : (List<?>) raw) {
                    Uri u = streamUri(o);
                    if (u != null) out.add(u);
                }
            } else if (raw instanceof Object[]) {
                for (Object o : (Object[]) raw) {
                    Uri u = streamUri(o);
                    if (u != null) out.add(u);
                }
            }
        }
        return out;
    }

    private Uri streamUri(Object raw) {
        if (raw instanceof Uri) return (Uri) raw;
        if (raw instanceof String) {
            String s = (String) raw;
            if (s.isEmpty()) return null;
            return s.contains("://") ? Uri.parse(s) : Uri.fromFile(new File(s));
        }
        return null;
    }

    private void bindViews() {
        videoPreviewContainer = findViewById(R.id.videoPreviewContainer);
        videoTextureView = findViewById(R.id.videoTextureView);
        videoEmptyContainer = findViewById(R.id.videoEmptyContainer);
        videoEmptyPickBtn = findViewById(R.id.videoEmptyPickBtn);

        timeline = findViewById(R.id.editorTimeline);
        timelineScroll = findViewById(R.id.editorTimelineScroll);
        rangeText = findViewById(R.id.editorRangeText);
        durationText = findViewById(R.id.editorDurationText);
        hintText = findViewById(R.id.editorHintText);
        currentTimeText = findViewById(R.id.editorCurrentTimeText);
        zoomText = findViewById(R.id.editorZoomText);

        tabMerge = findViewById(R.id.editorTabMerge);
        tabSplit = findViewById(R.id.editorTabSplit);

        mergeActionRow = findViewById(R.id.editorMergeActionRow);
        mergeAddSongBtn = findViewById(R.id.editorMergeAddSongBtn);
        mergeInfoText = findViewById(R.id.editorMergeInfoText);

        controlBarScroll = findViewById(R.id.editorControlBarScroll);
        controlBar = findViewById(R.id.editorControlBar);
        playBtn = findViewById(R.id.editorPlayBtn);
        autoScrollBtn = findViewById(R.id.editorAutoScrollBtn);
        addSplitBtn = findViewById(R.id.editorAddSplitBtn);
        deleteSplitBtn = findViewById(R.id.editorDeleteSplitBtn);
        addSongBtn = findViewById(R.id.editorAddSongBtn);
        clearBtn = findViewById(R.id.editorClearBtn);
        zoomOutBtn = findViewById(R.id.editorZoomOutBtn);
        zoomInBtn = findViewById(R.id.editorZoomInBtn);

        undoRedoRow = findViewById(R.id.editorUndoRedoRow);
        undoBtn = findViewById(R.id.editorUndoBtn);
        redoBtn = findViewById(R.id.editorRedoBtn);

        timelinePanel = findViewById(R.id.editorTimelinePanel);
        segmentList = findViewById(R.id.editorSegmentList);
        seqPreviewBtn = findViewById(R.id.editorSeqPreviewBtn);
        saveBtn = findViewById(R.id.editorSaveBtn);
        songNameText = findViewById(R.id.editorSongName);

        progressOverlay = findViewById(R.id.editorProgressOverlay);
        progressBar = findViewById(R.id.editorProgressBar);
        progressText = findViewById(R.id.editorProgressText);
        progressTitle = findViewById(R.id.editorProgressTitle);
        cancelBtn = findViewById(R.id.editorCancelBtn);

        findViewById(R.id.editorBackBtn).setOnClickListener(v -> finish());
        if (videoEmptyPickBtn != null) {
            videoEmptyPickBtn.setOnClickListener(v -> pickPrimaryVideo());
        }
        updateDurationText();
    }

    /** "Total mm:ss" readout for the edited timeline (all sources joined). */
    private void updateDurationText() {
        if (durationText != null) durationText.setText("Total " + msToTime(durationMs));
    }

    private void setupVideoTexture() {
        videoTextureView.setSurfaceTextureListener(this);
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        videoSurface = new Surface(surface);
        if (previewPlayer != null) {
            previewPlayer.setSurface(videoSurface);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        if (previewPlayer != null) {
            try {
                previewPlayer.setSurface(null);
            } catch (Exception ignored) {}
        }
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}

    private void adjustVideoAspectRatio(int videoW, int videoH) {
        if (videoW <= 0 || videoH <= 0 || videoPreviewContainer == null) return;
        int containerW = videoPreviewContainer.getWidth();
        int containerH = videoPreviewContainer.getHeight();
        if (containerW <= 0 || containerH <= 0) return;

        float videoAspect = (float) videoW / videoH;
        float containerAspect = (float) containerW / containerH;

        int finalW, finalH;
        if (videoAspect > containerAspect) {
            finalW = containerW;
            finalH = (int) (containerW / videoAspect);
        } else {
            finalH = containerH;
            finalW = (int) (containerH * videoAspect);
        }

        ViewGroup.LayoutParams lp = videoTextureView.getLayoutParams();
        lp.width = finalW;
        lp.height = finalH;
        videoTextureView.setLayoutParams(lp);
    }

    private void setupTabs() {
        tabMerge.setOnClickListener(v -> selectTab(TAB_MERGE));
        tabSplit.setOnClickListener(v -> selectTab(TAB_SPLIT));
    }

    private void selectTab(int tab) {
        if (currentTab == tab) return;
        currentTab = tab;
        // persist the active tab so the editor reopens on it
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_LAST_TAB, tab).apply();
        updateUiForTab(); // also stops any preview from the other tab
    }

    private void updateUiForTab() {
        // a preview belongs to the tab it was started from (Merge previews play
        // a list the Split tab knows nothing about)
        stopPreview();
        boolean isMerge = currentTab == TAB_MERGE;

        tabMerge.setTextColor(isMerge ? 0xFFFFFFFF : 0x88FFFFFF);
        tabSplit.setTextColor(!isMerge ? 0xFFFFFFFF : 0x88FFFFFF);

        timelinePanel.setVisibility(isMerge ? View.GONE : View.VISIBLE);
        // the bar lives inside a HorizontalScrollView — show/hide the wrapper
        // (the inner bar itself always stays visible)
        controlBarScroll.setVisibility(isMerge ? View.GONE : View.VISIBLE);
        undoRedoRow.setVisibility(isMerge ? View.GONE : View.VISIBLE);
        mergeActionRow.setVisibility(isMerge ? View.VISIBLE : View.GONE);
        // Preview stays available on both tabs: on Merge it plays the merge
        // list in order, i.e. exactly what Merge writes out.
        seqPreviewBtn.setVisibility(View.VISIBLE);

        saveBtn.setText(isMerge ? "Merge" : "Save");

        if (!isMerge && sources.isEmpty()) {
            videoEmptyContainer.setVisibility(View.VISIBLE);
        } else {
            videoEmptyContainer.setVisibility(View.GONE);
        }

        updateSongNameLabel();
        updateSaveButtonState();
        if (isMerge) {
            updateMergeInfoText();
        } else {
            // first entry into split mode: 10x default (fast first paint)
            applyDefaultSplitZoom();
            updateRangeText();
            updateZoomText();
            hintText.setText("Lossless split: every part is a stream copy, so nothing is "
                    + "re-encoded and the parts together are exactly the original file. Splits "
                    + "snap to the nearest keyframe (the tick marks) - a copy can only start "
                    + "there. Tap to place the pointer, then ✂ Split. Long-press a part to "
                    + "delete it; drag a row's ⠿ handle to reorder. Preview plays the parts in "
                    + "list order.");
        }
        renderSegmentList();
        updateCurrentTimeText(timeline.getPlayheadMs());
        updateUndoRedoButtons();
    }

    private void setupTimeline() {
        // The timeline's fit scale needs the viewport width, which only exists
        // after the first layout — and it changes again on rotation or window
        // resize. Report it on every layout change, and re-apply the default
        // split zoom here too: the Split panel only gets a width once it is
        // actually visible.
        timelineScroll.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                 oldL, oldT, oldR, oldB) -> {
            int w = right - left;
            if (w <= 0) return;
            timeline.setViewportWidth(w);
            if (currentTab == TAB_SPLIT) applyDefaultSplitZoom();
            updateZoomText();
        });

        timeline.setTimelineListener(new EditorTimelineView.TimelineListener() {
            @Override
            public void onSplitPointsChanged(List<Long> pointsMs) {
                // No undo snapshot here: a boundary drag fires this on every
                // move event. The explicit actions (addSplitAtPlayhead, delete
                // a split, delete a part, onSplitMoveStarted, a drop) each push
                // the pre-change state themselves — pushing here too filled the
                // 50-level history with duplicates and made Undo need two taps.
                renderSegmentList();
                updateRangeText();
            }

            @Override
            public void onPlayheadMoved(long playheadMs) {
                updateCurrentTimeText(playheadMs);
                // A sequence preview owns the player: tapping elsewhere on the
                // timeline must not yank the ordered playback out of its part.
                if (previewQueue != null) return;
                if (previewPlayer != null && previewPlayer.isPlaying()) {
                    // live seek while playing (pro editor behaviour)
                    previewEndMs = Math.max(previewEndMs, playheadMs + 200);
                }
                // also paints the frame under the playhead when paused
                seekPreviewTo(playheadMs);
            }

            @Override
            public void onSplitSelected(int index) {
                updateSplitActionButtons(index >= 0);
                updateRangeText();
            }

            @Override
            public void onSegmentSelected(int index) {
                renderSegmentList();
                updateRangeText();
            }

            @Override
            public void onSegmentLongPressed(int index) {
                confirmDeleteSegment(index);
            }

            @Override
            public void onSplitMoveStarted() {
                // pre-drag snapshot, pushed before the boundary actually moves
                pushUndoState(currentState());
            }
        });
    }

    private void setupActions() {
        playBtn.setOnClickListener(v -> togglePlayPause());
        autoScrollBtn.setOnClickListener(v -> {
            autoScrollEnabled = !autoScrollEnabled;
            autoScrollBtn.setColorFilter(autoScrollEnabled ? 0xFFFFFFFF : 0x88FFFFFF);
            if (autoScrollEnabled) followPlayhead();
        });

        addSplitBtn.setOnClickListener(v -> addSplitAtPlayhead());
        deleteSplitBtn.setOnClickListener(v -> {
            if (timeline.getSelectedSplit() < 0) {
                Toast.makeText(this, "Tap a split line on the timeline first", Toast.LENGTH_SHORT).show();
                return;
            }
            EditorState before = currentState();
            timeline.deleteSelectedMarker();
            pushUndoState(before);
            renderSegmentList();
            updateRangeText();
            updateSplitActionButtons(false);
        });

        addSongBtn.setOnClickListener(v -> pickVideoToAppend());
        mergeAddSongBtn.setOnClickListener(v -> pickVideosToMerge());

        clearBtn.setOnClickListener(v -> confirmClearTimeline());

        zoomOutBtn.setOnClickListener(v -> zoomBy(1f / 1.6f));
        zoomInBtn.setOnClickListener(v -> zoomBy(1.6f));

        undoBtn.setOnClickListener(v -> undo());
        redoBtn.setOnClickListener(v -> redo());

        seqPreviewBtn.setOnClickListener(v -> toggleSequencePreview());
        saveBtn.setOnClickListener(v -> confirmSave());
        cancelBtn.setOnClickListener(v -> {
            if (jobRunning) {
                editor.cancel();
                progressTitle.setText("Cancelling…");
            }
        });
    }

    private void addSplitAtPlayhead() {
        EditorState before = currentState();
        long t = timeline.getPlayheadMs();
        int idx = timeline.addSplitPoint(t);
        if (idx < 0) {
            Toast.makeText(this, "Can't split there — too close to the start, end or another split", Toast.LENGTH_SHORT).show();
            return;
        }
        pushUndoState(before);
        // The split can land on a different keyframe than the pointer when
        // the pointer was left between keyframes by playback (pausing never
        // snaps it). Move the pointer onto the actual split so the line and
        // the pointer always agree.
        timeline.setPlayheadMs(timeline.getSplitPoints().get(idx));
        renderSegmentList();
        updateRangeText();
        int viewport = timelineScroll.getWidth();
        float x = timeline.getPlayheadX();
        if (x < timelineScroll.getScrollX() || x > timelineScroll.getScrollX() + viewport) {
            timelineScroll.smoothScrollTo((int) Math.max(0, x - viewport / 2f), 0);
        }
    }

    private void pickPrimaryVideo() {
        Intent intent = new Intent(this, VideoListActivity.class);
        primaryPickerLauncher.launch(intent);
    }

    /**
     * Ask before wiping the timeline (same idiom as delete/merge confirm) —
     * this clears every loaded video AND every edit, and undo cannot bring
     * the sources back.
     */
    private void confirmClearTimeline() {
        if (sources.isEmpty()) return; // nothing to clear
        new AlertDialog.Builder(this)
                .setTitle("Clear timeline?")
                .setMessage(sources.size() + (sources.size() == 1 ? " video" : " videos")
                        + " and all splits/deletions will be removed from the Split tab.\n\n"
                        + "The original files are not modified.")
                .setPositiveButton("Clear", (d, w) -> clearTimeline())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Unload every timeline video and reset the Split tab to its empty state. */
    private void clearTimeline() {
        stopPreview();
        sources.clear();
        videoTitle = "";
        rebuildTimeMap();          // durationMs -> 0, offsets recomputed
        timeline.setDuration(1);   // empty axis (the view guards against 0)
        timeline.setPlayheadMs(0);
        timeline.setSplitPoints(null);
        timeline.setDeletedRanges(null);
        timeline.setWaveform(null);
        undoStack.clear();
        redoStack.clear();
        splitZoomInitialized = false;
        clearVideoFrame();         // the TextureView keeps its last frame otherwise
        updateDurationText();
        updateUiForTab();          // shows the empty "pick a video" state
        Toast.makeText(this, "Timeline cleared", Toast.LENGTH_SHORT).show();
    }

    /**
     * Erase the last decoded frame from the preview TextureView. Releasing the
     * MediaPlayer does NOT clear it — the surface keeps the final picture, so
     * after Clear the empty state would sit behind a stale video thumbnail.
     */
    private void clearVideoFrame() {
        if (videoTextureView == null) return;
        try {
            Canvas c = videoTextureView.lockCanvas();
            if (c != null) {
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                videoTextureView.unlockCanvasAndPost(c);
            }
        } catch (Exception ignored) {}
        // restore the full-container size adjustVideoAspectRatio() had shrunk
        ViewGroup.LayoutParams lp = videoTextureView.getLayoutParams();
        if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT
                || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            videoTextureView.setLayoutParams(lp);
        }
    }

    private void loadPrimaryVideo(Uri uri) {
        stopPreview();
        sources.clear();
        videoTitle = displayNameOf(uri);
        sources.add(new Source(uri, videoTitle, Math.max(1000, probeDuration(uri))));
        rebuildTimeMap();
        timeline.setDuration(durationMs);
        timeline.setPlayheadMs(0);
        extractWaveforms();
        requestKeyframes();
        undoStack.clear();
        redoStack.clear();
        // the new video needs its own default split zoom, and the fit must be
        // refreshed against its length BEFORE that (1x is the whole new file)
        splitZoomInitialized = false;
        applyFitZoom();
        updateDurationText();
        updateUiForTab();
    }

    private void pickVideoToAppend() {
        Intent intent = new Intent(this, VideoListActivity.class);
        videoPickerLauncher.launch(intent);
    }

    private void pickVideosToMerge() {
        Intent intent = new Intent(this, VideoListActivity.class);
        intent.putExtra(VideoListActivity.EXTRA_MULTI_SELECT, true);
        mergePickerLauncher.launch(intent);
    }

    private void appendVideo(Uri uri) {
        if (uri == null || isFinishing()) return;
        stopPreview();
        long dur = Math.max(1000, probeDuration(uri));
        String title = displayNameOf(uri);
        sources.add(new Source(uri, title, dur));
        rebuildTimeMap();
        timeline.setDuration(durationMs);

        // EditorState snapshots do not cover the source list, so undoing past
        // an append would restore splits onto a shorter timeline. Clearing the
        // history is the predictable choice; every other edit still undoes.
        boolean hadHistory = !undoStack.isEmpty() || !redoStack.isEmpty();
        undoStack.clear();
        redoStack.clear();

        extractWaveforms();
        requestKeyframes();
        updateDurationText();

        // zoom: split mode keeps the user's zoom but lets the fit grow to the
        // new full length (the timeline panel is hidden in Merge mode, so its
        // width is 0 there and this is skipped)
        if (timelineScroll.getWidth() > 0) {
            float keepPx = timeline.getPxPerMs();
            timeline.setViewportWidth(timelineScroll.getWidth()); // new fit
            timeline.setPxPerMs(keepPx); // re-clamped to the new fit
            updateZoomText();
        }

        updateUiForTab(); // re-renders the part list, labels and readouts
        String msg = "Added \"" + title + "\"  •  total " + msToTime(durationMs);
        if (hadHistory) msg += "\nUndo history cleared";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void addMergeVideos(List<Uri> uris) {
        if (uris == null || uris.isEmpty() || isFinishing()) return;
        stopPreview();
        int added = 0;
        long addedMs = 0;
        for (Uri u : uris) {
            if (u == null) continue;
            long dur = Math.max(1000, probeDuration(u));
            mergeSources.add(new Source(u, displayNameOf(u), dur));
            addedMs += dur;
            added++;
        }
        if (added == 0) return;
        rebuildMergeMap();
        requestKeyframes();     // signature + keyframe probe for the new files
        updateMergeInfoText();
        updateSongNameLabel();
        updateSaveButtonState();
        renderSegmentList();
        Toast.makeText(this, "Added " + added + (added == 1 ? " video" : " videos")
                + "  •  " + msToTime(addedMs) + "  •  total "
                + msToTime(mergeDurationMs), Toast.LENGTH_SHORT).show();
    }

    /** Ask before dropping a video from the merge list (same idiom as Split). */
    private void confirmRemoveMergeSource(int index) {
        if (index < 0 || index >= mergeSources.size()) return;
        String title = mergeSources.get(index).title;
        new AlertDialog.Builder(this)
                .setTitle("Remove this video?")
                .setMessage(title + " will be removed from the merge list.\n\n"
                        + "The original file is not modified.")
                .setPositiveButton("Remove", (d, w) -> removeMergeSource(index))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Drop one video from the merge list. Removing the last one is allowed —
     * the list simply goes back to being empty (the Split tab is untouched).
     */
    private void removeMergeSource(int index) {
        if (index < 0 || index >= mergeSources.size()) return;
        stopPreview();
        mergeSources.remove(index);
        rebuildMergeMap();
        updateMergeInfoText();
        updateSongNameLabel();
        updateSaveButtonState();
        renderSegmentList();
    }

    /**
     * Move the video at {@code from} to the WITH-item insertion position
     * {@code to} in the merge order. Returns false when the move is a no-op.
     * The caller re-renders the list.
     */
    private boolean moveMergeSource(int from, int to) {
        int n = mergeSources.size();
        if (from < 0 || from >= n || to < 0 || to > n) return false;
        if (to == from || to == from + 1) return false;
        stopPreview(); // the old order must not keep playing
        // removing the item first shifts the insertion point by one for
        // downward moves
        mergeSources.add(to > from ? to - 1 : to, mergeSources.remove(from));
        rebuildMergeMap();
        updateMergeInfoText();
        return true;
    }

    private void rebuildTimeMap() {
        long running = 0;
        for (Source s : sources) {
            s.offsetMs = running;
            running += s.durationMs;
        }
        durationMs = running;
        // the grid depends on the offsets, so it follows every change to them
        refreshSnapPoints();
    }

    private void rebuildMergeMap() {
        long running = 0;
        for (Source s : mergeSources) {
            s.offsetMs = running;
            running += s.durationMs;
        }
        mergeDurationMs = running;
    }

    private List<Source> activeSources() {
        return previewList != null ? previewList : sources;
    }

    private int sourceIndexAt(long globalMs) {
        List<Source> list = activeSources();
        if (list.isEmpty()) return 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (globalMs >= list.get(i).offsetMs) return i;
        }
        return 0;
    }

    private long toLocal(int index, long globalMs) {
        List<Source> list = activeSources();
        if (index < 0 || index >= list.size()) return globalMs;
        return Math.max(0, globalMs - list.get(index).offsetMs);
    }

    private long toGlobal(int index, long localMs) {
        List<Source> list = activeSources();
        if (index < 0 || index >= list.size()) return localMs;
        return list.get(index).offsetMs + localMs;
    }

    /**
     * Header label: the first video's name, plus how many more were appended
     * (with the total length) so the timeline length is never a surprise.
     */
    private void updateSongNameLabel() {
        if (songNameText == null) return;
        if (currentTab == TAB_MERGE) {
            songNameText.setText(mergeSources.isEmpty()
                    ? "No videos selected"
                    : mergeSources.size() + " video" + (mergeSources.size() == 1 ? "" : "s")
                    + "  •  " + msToTime(mergeDurationMs));
            return;
        }
        if (sources.isEmpty()) {
            songNameText.setText("No video loaded");
        } else if (sources.size() == 1) {
            songNameText.setText(sources.get(0).title);
        } else {
            songNameText.setText(sources.get(0).title
                    + "  +" + (sources.size() - 1)
                    + " video" + (sources.size() > 2 ? "s" : "")
                    + "  •  " + msToTime(durationMs));
        }
    }

    private void updateMergeInfoText() {
        int n = mergeSources.size();
        if (n == 0) {
            mergeInfoText.setText("No videos yet  •  tap the ＋ button to add some");
        } else if (n == 1) {
            mergeInfoText.setText("1 video  •  add one more to merge");
        } else {
            String clash = mergeClash();
            if (clash != null) {
                mergeInfoText.setText(n + " videos  •  " + clash);
            } else {
                mergeInfoText.setText(n + " videos  •  Total " + msToTime(mergeDurationMs));
            }
        }
    }

    /**
     * A merge is a stream copy, so it cannot change the codec, the picture
     * size or the audio format. When the list holds videos that differ in
     * those, say it here - the engine would otherwise refuse the export, and
     * saying it early is friendlier than a dialog after the work.
     */
    private String mergeClash() {
        for (int i = 0; i < mergeSources.size(); i++) {
            for (int j = i + 1; j < mergeSources.size(); j++) {
                FFmpegVideoEditor.MediaInfo a = mergeSources.get(i).media;
                FFmpegVideoEditor.MediaInfo b = mergeSources.get(j).media;
                if (a == null || b == null) continue;      // not probed yet
                String reason = a.incompatibleWith(b);
                if (reason != null) return "Cannot merge: " + reason;
            }
        }
        return null;
    }

    private void updateSaveButtonState() {
        if (currentTab == TAB_MERGE) {
            boolean canMerge = mergeSources.size() >= 2 && !jobRunning;
            saveBtn.setEnabled(canMerge);
            saveBtn.setAlpha(canMerge ? 1.0f : 0.45f);
        } else {
            boolean canSave = !sources.isEmpty() && !jobRunning;
            saveBtn.setEnabled(canSave);
            saveBtn.setAlpha(canSave ? 1.0f : 0.45f);
        }
    }

    // ------------------------------------------------------------------
    // Playback & Video Preview
    // ------------------------------------------------------------------

    /**
     * Split tab control bar: play from the playhead / pause / resume.
     * A paused player resumes where it is (a scrub leaves it paused on the
     * frame under the playhead); otherwise playback starts at the playhead and
     * runs to the end of the edited timeline, continuing across videos.
     */
    private void togglePlayPause() {
        if (previewPlayer != null) {
            try {
                if (previewPlayer.isPlaying()) {
                    previewPlayer.pause();
                    mainHandler.removeCallbacks(playheadUpdater);
                } else {
                    previewPlayer.start();
                    // one chain only: a plain post() on every resume would
                    // double the update rate
                    mainHandler.removeCallbacks(playheadUpdater);
                    mainHandler.post(playheadUpdater);
                }
                updatePlayButtons();
                return;
            } catch (Exception ignored) {}
        }
        long pos = timeline.getPlayheadMs();
        if (pos >= durationMs - 40) pos = 0;
        previewRange(pos, durationMs);
    }

    /**
     * Frame-accurate seek. MediaPlayer's plain seekTo() snaps to the nearest
     * KEYFRAME — with arbitrary (non-keyframe) split points that landed the
     * playhead a whole GOP earlier, so playing one part audibly started in
     * the previous one. SEEK_CLOSEST (API 26+) lands on the exact frame;
     * below API 26 plain seek is the only option.
     */
    private static void seekAccurate(MediaPlayer mp, long localMs) {
        int pos = (int) Math.max(0, localMs);
        if (Build.VERSION.SDK_INT >= 26) {
            mp.seekTo(pos, MediaPlayer.SEEK_CLOSEST);
        } else {
            mp.seekTo(pos);
        }
    }

    /**
     * ▶/⏸ on a Split-tab part row. When THIS part is already the loaded
     * range the button toggles play/pause, exactly like the transport button;
     * otherwise it starts this part from its own beginning. Without the
     * toggle a second tap just restarted the part from the top, and the
     * button icon never changed.
     */
    private void togglePartPreview(long startMs, long endMs) {
        if (previewPlayer != null && previewQueue == null && previewList == null
                && previewStartMs == startMs && previewEndMs == endMs) {
            togglePlayPause();
            return;
        }
        previewRange(startMs, endMs);
    }

    /** Play one [startMs, endMs) range of the timeline axis. */
    private void previewRange(long startMs, long endMs) {
        stopPreview();
        previewList = null; // the timeline axis
        if (sources.isEmpty()) return;
        // Move the playhead onto the requested part straight away. The player
        // needs a moment to prepare and seek, and until it reports a position
        // the OLD one stayed on screen — with auto-scroll following it — so
        // tapping a later part looked like playback had begun in an earlier one.
        timeline.setPlayheadMs(startMs);
        updateCurrentTimeText(startMs);
        if (autoScrollEnabled) followPlayhead();
        createPreviewPlayer(startMs, endMs, sourceIndexAt(startMs));
    }

    /**
     * Seek the preview to a GLOBAL time. Inside the current video this is a
     * plain seek; a target in another video needs a new player (a MediaPlayer
     * reads a single file), which is created for the remaining range while the
     * queue is kept — this is still the same preview.
     */
    private void seekPreviewTo(long globalMs) {
        List<Source> list = activeSources();
        if (list.isEmpty()) return;
        int targetIdx = sourceIndexAt(globalMs);
        if (previewPlayer == null) {
            // nothing loaded yet: paint the range from here — the player is
            // created paused so the frame under the playhead is visible
            // without starting playback
            createPreviewPlayer(globalMs, Math.max(globalMs + 1, durationMs), targetIdx, false);
            return;
        }
        if (targetIdx == previewSourceIndex) {
            try {
                seekAccurate(previewPlayer, toLocal(targetIdx, globalMs));
            } catch (Exception ignored) {}
            return;
        }
        boolean wasPlaying = false;
        try {
            wasPlaying = previewPlayer.isPlaying();
        } catch (Exception ignored) {}
        releasePlayer(); // keep the queue: this is still the same preview
        createPreviewPlayer(globalMs, previewEndMs, targetIdx, wasPlaying);
    }

    private void createPreviewPlayer(long startMs, long endMs, int index) {
        createPreviewPlayer(startMs, endMs, index, true);
    }

    /**
     * Create the player for one range. `startMs`/`endMs` are GLOBAL timeline
     * times; the player is anchored to the source that owns the start, seeked
     * to the matching local time. A range that runs past the end of that video
     * keeps playing in the next one — see continueIntoNextSource().
     * With {@code autoPlay} false the player is prepared and seeked but left
     * paused, so an idle scrub paints the frame under the playhead.
     * The sequence queue is owned by the caller and never touched here.
     */
    private void createPreviewPlayer(long startMs, long endMs, int index, boolean autoPlay) {
        List<Source> list = activeSources();
        if (list.isEmpty()) return;
        if (index < 0 || index >= list.size()) index = 0;
        releasePlayer();
        previewStartMs = startMs;
        previewEndMs = endMs;
        previewSourceIndex = index;
        Source src = list.get(index);
        final long localStart = toLocal(index, startMs);
        final boolean play = autoPlay;

        try {
            previewPlayer = new MediaPlayer();
            previewPlayer.setDataSource(this, src.uri);
            if (videoSurface != null) {
                previewPlayer.setSurface(videoSurface);
            }
            previewPlayer.setOnVideoSizeChangedListener((mp, w, h) -> adjustVideoAspectRatio(w, h));
            previewPlayer.setOnPreparedListener(mp -> {
                try {
                    if (localStart <= 0) {
                        // No seek needed — act immediately
                        afterPrepared(mp, play);
                    } else {
                        // Seek first; start only after seek completes to avoid
                        // playheadUpdater reading getCurrentPosition()=0 for a
                        // mid-file clip and incorrectly advancing or stopping.
                        mp.setOnSeekCompleteListener(smp -> {
                            try {
                                smp.setOnSeekCompleteListener(null);
                                afterPrepared(smp, play);
                            } catch (Exception ignored) {}
                        });
                        seekAccurate(mp, localStart);
                    }
                } catch (Exception ignored) {}
            });
            previewPlayer.setOnCompletionListener(mp -> {
                if (mp != previewPlayer) return; // stale callback
                // the range continues in the next video: keep playing there
                if (continueIntoNextSource()) return;
                if (previewQueue != null) {
                    advanceSequencePreview();
                } else {
                    stopPreview();
                }
            });
            previewPlayer.prepareAsync();
        } catch (Exception e) {
            stopPreview();
        }
    }

    /**
     * A prepared (and, when needed, seeked) player is ready: start playing, or
     * hold it on the frame at the seek target. Pausing must happen here rather
     * than right after prepareAsync — MediaPlayer.pause() on a player that is
     * still Preparing throws IllegalStateException.
     */
    private void afterPrepared(MediaPlayer mp, boolean autoPlay) {
        if (!autoPlay) {
            updatePlayButtons();
            return;
        }
        mp.start();
        mainHandler.removeCallbacks(playheadUpdater);
        mainHandler.post(playheadUpdater);
        updatePlayButtons();
    }

    /**
     * The current video has played to its end but the preview range still has
     * time left: continue from the start of the next video, so a part that was
     * split across the seam (or a play-through to the end of the timeline)
     * plays continuously. Returns true when playback was carried on.
     */
    private boolean continueIntoNextSource() {
        if (previewPlayer == null) return false;
        List<Source> list = activeSources();
        int next = previewSourceIndex + 1;
        if (next >= list.size()) return false;
        long seam = list.get(next).offsetMs;
        if (previewEndMs <= seam) return false; // the range ends in this video
        createPreviewPlayer(seam, previewEndMs, next);
        return true;
    }

    private void stopPreview() {
        releasePlayer();
        previewQueue = null;
        previewQueueIndex = -1;
        lastQueueAdvanceEnd = Long.MIN_VALUE;
        previewList = null; // back to the timeline axis
        resetMergeRowPlayback();
        updatePlayButtons();
        if (timeline != null) updateCurrentTimeText(timeline.getPlayheadMs());
    }

    private void releasePlayer() {
        mainHandler.removeCallbacks(playheadUpdater);
        if (previewPlayer != null) {
            try {
                previewPlayer.stop();
                previewPlayer.release();
            } catch (Exception ignored) {}
            previewPlayer = null;
        }
    }

    /** Reflect playback state in the play/preview buttons. */
    private void updatePlayButtons() {
        boolean isPlaying = false;
        if (previewPlayer != null) {
            try {
                isPlaying = previewPlayer.isPlaying();
            } catch (Exception ignored) {}
        }
        int icon = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play;
        playBtn.setImageResource(icon);
        // the row whose range is the loaded one shows ⏸, every other row shows ▶
        for (int i = 0; i < partRowButtons.size(); i++) {
            long[] r = partRowRanges.get(i);
            boolean active = previewPlayer != null && previewQueue == null
                    && r[0] == previewStartMs && r[1] == previewEndMs;
            partRowButtons.get(i).setImageResource(active ? icon : R.drawable.ic_play);
        }
        // the playing Merge row shows ⏸, every other row shows ▶
        boolean mergePreview = previewList == mergeSources;
        for (int i = 0; i < mergeRowButtons.size(); i++) {
            mergeRowButtons.get(i).setImageResource(
                    mergePreview && i == previewSourceIndex ? icon : R.drawable.ic_play);
        }
        seqPreviewBtn.setText(previewQueue != null ? "■ Stop" : "▶ Preview");
    }

    /**
     * Bottom Preview button: play the parts CONTINUOUSLY in their current list
     * order — the sequence is the single source of truth, matching the final
     * Merge/Save. On the Split tab that is the part sequence of the timeline;
     * on the Merge tab it is the merge list itself (one part per video, in list
     * order), i.e. exactly what Merge will write out. Click again to stop.
     */
    private void toggleSequencePreview() {
        if (previewQueue != null) {
            stopPreview();
            return;
        }
        if (currentTab == TAB_MERGE) {
            startMergeSequencePreview();
            return;
        }
        startSequencePreview();
    }

    /** Split tab: play the parts in the timeline's play order. */
    private void startSequencePreview() {
        // getSegments() is ALREADY in play (sequence) order — re-indexing it
        // with getSequence() (which holds BOUNDS indices) skipped or
        // duplicated parts as soon as one was deleted or reordered.
        List<long[]> segs = timeline.getSegments();
        if (segs.isEmpty()) return;
        stopPreview();
        previewList = null; // the timeline axis
        previewQueue = segs;
        previewQueueIndex = 0;
        lastQueueAdvanceEnd = Long.MIN_VALUE;
        createPreviewPlayer(segs.get(0)[0], segs.get(0)[1], sourceIndexAt(segs.get(0)[0]));
    }

    /**
     * Merge tab preview: play the whole merge list in order, starting at the
     * first video — one part per video, exactly what Merge will write out.
     */
    private void startMergeSequencePreview() {
        if (mergeSources.isEmpty()) {
            Toast.makeText(this, "Add a video first", Toast.LENGTH_SHORT).show();
            return;
        }
        List<long[]> segs = new ArrayList<>(mergeSources.size());
        long off = 0;
        for (Source s : mergeSources) {
            segs.add(new long[]{off, off + s.durationMs});
            off += s.durationMs;
        }
        stopPreview();
        previewList = mergeSources; // the merge list's own axis
        previewQueue = segs;
        previewQueueIndex = 0;
        lastQueueAdvanceEnd = Long.MIN_VALUE;
        createPreviewPlayer(segs.get(0)[0], segs.get(0)[1], 0);
    }

    /** Continue the sequence preview with the next part. */
    private void advanceSequencePreview() {
        if (previewQueue == null) return;
        int nextIdx = previewQueueIndex + 1;
        if (nextIdx >= previewQueue.size()) {
            stopPreview(); // last part finished
            return;
        }
        previewQueueIndex = nextIdx;
        long[] next = previewQueue.get(nextIdx);
        int idx = sourceIndexAt(next[0]);
        if (previewPlayer == null || idx != previewSourceIndex) {
            // the next part lives in another video (or nothing is loaded): one
            // MediaPlayer cannot read two files, so swap the player — the
            // queue is kept, this is still the same preview
            createPreviewPlayer(next[0], next[1], idx);
            return;
        }
        try {
            previewStartMs = next[0];
            previewEndMs = next[1];
            seekAccurate(previewPlayer, toLocal(idx, next[0]));
            previewPlayer.start();
            if (previewList == null) {
                timeline.setPlayheadMs(next[0]);
                if (autoScrollEnabled) followPlayhead();
            }
            updateCurrentTimeText(next[0]);
        } catch (Exception e) {
            stopPreview();
        }
    }

    /**
     * ▶/⏸ on a Merge-tab row: pause/resume when that video is the one already
     * loaded in the player, otherwise start it (replacing whatever played).
     */
    private void toggleMergeSourcePreview(int index) {
        if (previewPlayer != null && previewList == mergeSources
                && index == previewSourceIndex) {
            togglePlayPause();
            return;
        }
        if (index < 0 || index >= mergeSources.size()) return;
        stopPreview();
        previewList = mergeSources;
        Source s = mergeSources.get(index);
        createPreviewPlayer(s.offsetMs, s.offsetMs + s.durationMs, index);
    }

    /**
     * Merge rows: the row of the video currently loaded shows a progress bar
     * filled to the position inside THAT video; every other row's bar is empty
     * (but still there — it is the seek control for that video).
     */
    private void updateMergeRowProgress(long globalPos) {
        if (mergeRowBars.isEmpty()) return;
        int playing = previewList == mergeSources ? previewSourceIndex : -1;
        for (int i = 0; i < mergeRowBars.size(); i++) {
            ProgressBar bar = mergeRowBars.get(i);
            if (i != playing || i >= mergeSources.size()) {
                if (bar.getProgress() != 0) bar.setProgress(0);
                continue;
            }
            long dur = Math.max(1, mergeSources.get(i).durationMs);
            long local = Math.max(0, Math.min(dur, toLocal(i, globalPos)));
            bar.setProgress((int) (1000L * local / dur));
        }
    }

    private void resetMergeRowPlayback() {
        for (ProgressBar b : mergeRowBars) b.setProgress(0);
        for (ImageButton btn : mergeRowButtons) btn.setImageResource(R.drawable.ic_play);
    }

    /**
     * Tap or drag on a Merge row's seek strip: seek inside that video. The
     * row's own ▶/⏸ state is respected — a paused video stays paused, a
     * playing one keeps playing (and an ordered preview carries on with the
     * following videos).
     */
    private boolean handleMergeBarTouch(View barArea, MotionEvent ev, int index) {
        int action = ev.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP) {
            return false;
        }
        if (barArea.getWidth() <= 0 || index < 0 || index >= mergeSources.size()) {
            return false;
        }
        if (action == MotionEvent.ACTION_DOWN && barArea.getParent() != null) {
            // keep the row's ScrollView from stealing the drag
            barArea.getParent().requestDisallowInterceptTouchEvent(true);
        }
        float fraction = Math.max(0f, Math.min(1f,
                ev.getX() / (float) barArea.getWidth()));
        seekMergeSourceTo(index, (long) (fraction * mergeSources.get(index).durationMs));
        return true;
    }

    /**
     * Move the merge preview to a point inside one video. The video already
     * loaded keeps its player — so an ordered preview continues into the
     * following videos — any other video starts fresh from that point.
     */
    private void seekMergeSourceTo(int index, long localMs) {
        if (index < 0 || index >= mergeSources.size()) return;
        Source s = mergeSources.get(index);
        long dur = Math.max(1, s.durationMs);
        long local = Math.max(0, Math.min(dur - 1, localMs));
        long global = s.offsetMs + local;

        if (previewPlayer != null && previewList == mergeSources
                && index == previewSourceIndex) {
            try {
                seekAccurate(previewPlayer, local);
            } catch (Exception ignored) {}
            updateMergeRowProgress(global);
            updateCurrentTimeText(global);
            return;
        }
        stopPreview();
        previewList = mergeSources;
        // play from the touched point to the end of that video
        createPreviewPlayer(global, s.offsetMs + s.durationMs, index);
    }

    // ------------------------------------------------------------------
    // Undo / Redo & Timeline
    // ------------------------------------------------------------------

    private EditorState currentState() {
        return new EditorState(
                timeline.getSplitPoints(),
                timeline.getDeletedRanges(),
                timeline.getSequence(),
                timeline.getSelectedSplit(),
                timeline.getSelectedSegment()
        );
    }

    private void pushUndoState(EditorState before) {
        undoStack.push(before);
        while (undoStack.size() > MAX_HISTORY) undoStack.removeLast();
        redoStack.clear();
        updateUndoRedoButtons();
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(currentState());
        applyState(undoStack.pop());
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(currentState());
        applyState(redoStack.pop());
    }

    private void applyState(EditorState s) {
        stopPreview();
        timeline.setSplitPoints(s.splitPoints);
        timeline.setDeletedRanges(s.deletedRanges);
        timeline.setSequence(s.sequence);
        timeline.restoreSelection(s.selectedSplit, s.selectedSegment);
        renderSegmentList();
        updateRangeText();
        updateUndoRedoButtons();
    }

    private void updateUndoRedoButtons() {
        boolean canUndo = !undoStack.isEmpty();
        boolean canRedo = !redoStack.isEmpty();
        undoBtn.setEnabled(canUndo);
        undoBtn.setAlpha(canUndo ? 1.0f : 0.4f);
        redoBtn.setEnabled(canRedo);
        redoBtn.setAlpha(canRedo ? 1.0f : 0.4f);
    }

    private void updateRangeText() {
        List<long[]> segs = timeline.getSegments();
        int deleted = timeline.getDeletedRanges().size();
        int sel = timeline.getSelectedSplit();
        String splitInfo = sel >= 0
                ? "Split " + (sel + 1) + " at " + msToTime(timeline.getSplitPoints().get(sel))
                : "No split selected";
        boolean reordered = false;
        List<Integer> seq = timeline.getSequence();
        for (int i = 1; i < seq.size(); i++) {
            if (seq.get(i) < seq.get(i - 1)) {
                reordered = true;
                break;
            }
        }
        rangeText.setText(splitInfo + "  •  " + segs.size() + " part"
                + (segs.size() == 1 ? "" : "s")
                + (deleted > 0 ? "  •  " + deleted + " deleted" : "")
                + (reordered ? "  •  reordered" : ""));
        // the fit scale depends on the edited length and the block count, so it
        // moves with these edits — keep the zoom readout in step
        updateZoomText();
    }

    private void updateCurrentTimeText(long ms) {
        currentTimeText.setText("▶ " + msToTime(
                previewList != null ? ms : timeline.toDisplayMs(ms)));
    }

    private void updateZoomText() {
        float z = timeline.getZoomLevel();
        zoomText.setText(String.format(Locale.US, "Zoom %.1fx", z));
    }

    private void updateSplitActionButtons(boolean hasSelection) {
        deleteSplitBtn.setEnabled(hasSelection);
        deleteSplitBtn.setAlpha(hasSelection ? 1.0f : 0.45f);
    }

    private void followPlayhead() {
        if (timeline == null || timelineScroll == null) return;
        float x = timeline.getPlayheadX();
        int scrollX = timelineScroll.getScrollX();
        int width = timelineScroll.getWidth();
        if (x < scrollX || x > scrollX + width - dp(40)) {
            timelineScroll.smoothScrollTo((int) (x - width / 2f), 0);
        }
    }

    /** Zoom while keeping the viewport center time stable. */
    private void zoomBy(float factor) {
        int viewport = timelineScroll.getWidth();
        if (viewport <= 0) return;
        float oldPx = timeline.getPxPerMs();
        long centerMs = (long) ((timelineScroll.getScrollX() + viewport / 2f) / oldPx);
        // the fit scale can have changed (parts deleted, viewport resized):
        // refresh it first, then re-clamp the zoom against it
        timeline.setViewportWidth(viewport);
        timeline.setPxPerMs(oldPx * factor);
        updateZoomText();
        final int targetX = (int) (centerMs * timeline.getPxPerMs() - viewport / 2f);
        timeline.post(() -> {
            if (timeline == null || timelineScroll == null) return;
            timelineScroll.scrollTo(Math.max(0, targetX), 0);
        });
    }

    private void applyFitZoom() {
        int viewport = timelineScroll.getWidth();
        if (viewport <= 0) return;
        timeline.setViewportWidth(viewport);
        timeline.setPxPerMs(timeline.getFitPxPerMs());
        updateZoomText();
    }

    private void applyDefaultSplitZoom() {
        if (splitZoomInitialized || timelineScroll.getWidth() <= 0) return;
        splitZoomInitialized = true;
        timeline.setViewportWidth(timelineScroll.getWidth());
        timeline.setPxPerMs(timeline.getFitPxPerMs() * DEFAULT_SPLIT_ZOOM);
        timelineScroll.scrollTo(0, 0);
        updateZoomText();
    }

    private void confirmDeleteSegment(int boundsIndex) {
        if (timeline.getKeptSegments().size() <= 1) {
            Toast.makeText(this, "Can't delete the only remaining part",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        EditorTimelineView.SegmentInfo info = null;
        for (EditorTimelineView.SegmentInfo s : timeline.getKeptSegments()) {
            if (s.index == boundsIndex) {
                info = s;
                break;
            }
        }
        if (info == null) return;
        final int num = info.number;
        final int bIdx = info.index;

        new AlertDialog.Builder(this)
                .setTitle("Delete Part " + num + "?")
                .setMessage("Remove " + msToTime(info.startMs) + " → " + msToTime(info.endMs)
                        + " (" + msToTime(info.endMs - info.startMs) + ") from the video?\n\n"
                        + "The original file is not modified — you can Undo this.")
                .setPositiveButton("Delete", (d, w) -> {
                    EditorState before = currentState();
                    if (!timeline.deleteSegment(bIdx)) {
                        Toast.makeText(this, "Can't delete the only remaining part", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    stopPreview();
                    pushUndoState(before);
                    renderSegmentList();
                    updateRangeText();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Rendering Rows (Split parts / Merge sources)
    // ------------------------------------------------------------------

    private void renderSegmentList() {
        segmentList.removeAllViews();
        // the row controls are rebuilt with the rows themselves
        mergeRowBars.clear();
        mergeRowButtons.clear();
        partRowButtons.clear();
        partRowRanges.clear();
        if (currentTab == TAB_MERGE) {
            renderSourceList();
            return;
        }
        List<EditorTimelineView.SegmentInfo> segs = timeline.getKeptSegments();
        if (segs.isEmpty()) return;

        int selectedSeg = timeline.getSelectedSegment();
        for (int i = 0; i < segs.size(); i++) {
            EditorTimelineView.SegmentInfo seg = segs.get(i);
            View row = buildSegmentRow(seg.number, seg.index, i, seg.startMs, seg.endMs, selectedSeg, seg.endMs - seg.startMs);
            segmentList.addView(row);
        }
    }

    private View buildSegmentRow(final int number, final int boundsIndex,
                                 final int seqPos, long startMs, long endMs,
                                 int selectedSeg, long durMs) {
        final boolean selected = boundsIndex == selectedSeg && currentTab == TAB_SPLIT;
        // describes content, not position: stays on the part that reaches the
        // end of the video even after a reorder
        final boolean reachesEnd = endMs >= durationMs - 1;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(selected
                ? R.drawable.bg_editor_segment_selected : R.drawable.bg_editor_segment_item);
        int pad = dp(8);
        row.setPadding(pad, dp(4), pad, dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        row.setLayoutParams(lp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        textCol.setLayoutParams(textLp);

        TextView title = new TextView(this);
        title.setText("Part " + number + (reachesEnd ? "  (till end)" : ""));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(13);
        title.getPaint().setFakeBoldText(true);

        TextView sub = new TextView(this);
        sub.setText(msToTime(startMs) + " → " + msToTime(endMs) + "  •  " + msToTime(durMs));
        sub.setTextColor(0x88FFFFFF);
        sub.setTextSize(11);

        textCol.addView(title);
        textCol.addView(sub);
        row.addView(textCol);

        // borderless ripple background shared by the icon buttons
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);

        // ⠿ drag handle: touch = instant drag-and-drop reorder (no delay)
        ImageView dragHandle = new ImageView(this);
        dragHandle.setImageResource(R.drawable.ic_drag_handle);
        dragHandle.setColorFilter(0xFFFFFFFF);
        dragHandle.setBackgroundResource(tv.resourceId != 0 ? tv.resourceId : 0);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(26), dp(30));
        hLp.rightMargin = dp(4);
        dragHandle.setLayoutParams(hLp);
        dragHandle.setContentDescription("Drag to reorder");
        dragHandle.setOnTouchListener((v, ev) ->
                handleHandleTouch(row, ev, seqPos, boundsIndex));
        row.addView(dragHandle);

        ImageButton playPartBtn = new ImageButton(this);
        playPartBtn.setImageResource(R.drawable.ic_play);
        playPartBtn.setColorFilter(0xFFFFFFFF); // icons are black — force white
        playPartBtn.setBackgroundResource(tv.resourceId != 0 ? tv.resourceId : 0);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        btnLp.leftMargin = dp(6);
        playPartBtn.setLayoutParams(btnLp);
        playPartBtn.setContentDescription("Play/Pause this part");
        // toggle: plays this part, or pauses/resumes it when it is the loaded one
        playPartBtn.setOnClickListener(v -> togglePartPreview(startMs, endMs));
        row.addView(playPartBtn);
        partRowButtons.add(playPartBtn);
        partRowRanges.add(new long[]{startMs, endMs});

        ImageButton delPartBtn = new ImageButton(this);
        delPartBtn.setImageResource(R.drawable.ic_delete);
        delPartBtn.setColorFilter(0xFFFFFFFF); // icons are black — force white
        delPartBtn.setBackgroundResource(tv.resourceId != 0 ? tv.resourceId : 0);
        LinearLayout.LayoutParams dbLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        dbLp.leftMargin = dp(6);
        delPartBtn.setLayoutParams(dbLp);
        delPartBtn.setContentDescription("Delete this part");
        delPartBtn.setOnClickListener(v -> confirmDeleteSegment(boundsIndex));
        row.addView(delPartBtn);

        // tapping a row selects that segment on the timeline
        row.setOnClickListener(v -> {
            if (suppressNextRowClick) {
                suppressNextRowClick = false; // swallows the click after a drag
                return;
            }
            if (currentTab == TAB_SPLIT) {
                timeline.selectSegment(boundsIndex);
            }
        });

        return row;
    }

    private void renderSourceList() {
        segmentList.removeAllViews();
        mergeRowBars.clear();
        mergeRowButtons.clear();

        if (mergeSources.isEmpty()) {
            // Prominent empty-state card so the user knows how to add videos
            LinearLayout emptyCard = new LinearLayout(this);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            emptyCard.setGravity(Gravity.CENTER);
            emptyCard.setBackgroundResource(R.drawable.bg_editor_segment_item);
            int ep = dp(24);
            emptyCard.setPadding(ep, ep, ep, ep);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.topMargin = dp(8);
            emptyCard.setLayoutParams(elp);

            TextView emptyIcon = new TextView(this);
            emptyIcon.setText("🎬");
            emptyIcon.setTextSize(36);
            emptyIcon.setGravity(Gravity.CENTER);
            emptyCard.addView(emptyIcon);

            TextView emptyMsg = new TextView(this);
            emptyMsg.setText("কোনো ভিডিও যোগ করা হয়নি");
            emptyMsg.setTextColor(0xCCFFFFFF);
            emptyMsg.setTextSize(14);
            emptyMsg.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            msgLp.topMargin = dp(8);
            emptyMsg.setLayoutParams(msgLp);
            emptyCard.addView(emptyMsg);

            TextView addBtn = new TextView(this);
            addBtn.setText("＋  ভিডিও যোগ করুন");
            addBtn.setTextColor(0xFFFFFFFF);
            addBtn.setTextSize(15);
            addBtn.setGravity(Gravity.CENTER);
            addBtn.setBackgroundResource(R.drawable.bg_editor_btn_primary);
            int bph = dp(14);
            int bpv = dp(10);
            addBtn.setPadding(bph, bpv, bph, bpv);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            btnLp.topMargin = dp(16);
            btnLp.gravity = Gravity.CENTER_HORIZONTAL;
            addBtn.setLayoutParams(btnLp);
            addBtn.setOnClickListener(v -> pickVideosToMerge());
            emptyCard.addView(addBtn);

            segmentList.addView(emptyCard);
            return;
        }

        for (int i = 0; i < mergeSources.size(); i++) {
            View row = buildSourceRow(i, mergeSources.get(i));
            segmentList.addView(row);
        }
    }

    private View buildSourceRow(final int index, final Source src) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_editor_segment_item);
        int pad = dp(8);
        row.setPadding(pad, dp(4), pad, dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        row.setLayoutParams(lp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText((index + 1) + ".  " + src.title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(12f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(title);

        long startMs = src.offsetMs;
        long endMs = src.offsetMs + src.durationMs;
        TextView times = new TextView(this);
        times.setText(msToTime(startMs) + " → " + msToTime(endMs)
                + "   (" + msToTime(src.durationMs) + ")");
        times.setTextColor(0xAAFFFFFF);
        times.setTextSize(11f);
        textCol.addView(times);

        // per-video seek bar: tap or drag anywhere on it to jump to that point
        // of the video (it starts playing there if it wasn't the loaded one).
        // The strip is taller than the line so it is comfortable to hit.
        FrameLayout barHolder = new FrameLayout(this);
        LinearLayout.LayoutParams bhLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22));
        bhLp.topMargin = dp(1);
        barHolder.setLayoutParams(bhLp);
        android.util.TypedValue barTv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, barTv, true);
        barHolder.setBackgroundResource(barTv.resourceId != 0 ? barTv.resourceId : 0);
        barHolder.setContentDescription("Seek within this video");

        ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(0);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF6C63FF));
        bar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        bar.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5), Gravity.CENTER_VERTICAL));
        barHolder.addView(bar);
        barHolder.setOnTouchListener((v, ev) -> handleMergeBarTouch(v, ev, index));
        textCol.addView(barHolder);
        mergeRowBars.add(bar);

        row.addView(textCol);

        // borderless ripple background shared by the icon buttons
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);

        // ⠿ drag handle: instant drag-and-drop reorder of the merge order
        ImageView handle = new ImageView(this);
        handle.setImageResource(R.drawable.ic_drag_handle);
        handle.setColorFilter(0xFFFFFFFF);
        handle.setBackgroundResource(tv.resourceId != 0 ? tv.resourceId : 0);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(26), dp(30));
        hLp.rightMargin = dp(4);
        handle.setLayoutParams(hLp);
        handle.setContentDescription("Drag to reorder");
        handle.setOnTouchListener((v, ev) -> handleHandleTouch(row, ev, index, -1));
        row.addView(handle);

        ImageButton play = new ImageButton(this);
        play.setImageResource(R.drawable.ic_play);
        play.setColorFilter(0xFFFFFFFF);
        play.setBackgroundResource(tv.resourceId != 0 ? tv.resourceId : 0);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        pbLp.leftMargin = dp(6);
        play.setLayoutParams(pbLp);
        play.setContentDescription("Play/Pause this video");
        play.setOnClickListener(v -> toggleMergeSourcePreview(index));
        row.addView(play);
        mergeRowButtons.add(play);

        ImageButton delete = new ImageButton(this);
        delete.setImageResource(R.drawable.ic_delete);
        delete.setColorFilter(0xFFFFFFFF);
        delete.setBackgroundResource(tv.resourceId != 0 ? tv.resourceId : 0);
        LinearLayout.LayoutParams dbLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        dbLp.leftMargin = dp(6);
        delete.setLayoutParams(dbLp);
        delete.setContentDescription("Remove this video from the merge");
        delete.setOnClickListener(v -> confirmRemoveMergeSource(index));
        row.addView(delete);

        return row;
    }

    private static final float ROW_DRAG_SLOP_DP = 10f;

    /**
     * Touch handler for a row's ⠿ drag handle: the drag starts IMMEDIATELY
     * on touch (no long-press wait), consuming events so the ScrollView
     * never interferes. Tapping the handle without moving is a no-op.
     * Works on both lists: Split parts and Merge videos.
     */
    private boolean handleHandleTouch(final View row, MotionEvent ev,
                                      final int seqPos, final int boundsIndex) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if ((currentTab != TAB_SPLIT && currentTab != TAB_MERGE)
                        || row.getParent() != segmentList
                        || segmentList.getChildCount() <= 1) {
                    return false; // nothing to reorder — ignore
                }
                dragDownRawY = ev.getRawY();
                dragLastRawY = ev.getRawY();
                rowDragMoved = false;
                row.performHapticFeedback(
                        android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                startRowDrag(row, seqPos, boundsIndex,
                        currentTab == TAB_MERGE ? DRAG_SOURCES : DRAG_PARTS);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (dragRow == row) {
                    updateRowDrag(ev.getRawY());
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
                if (dragRow == row) {
                    finishRowDrag(boundsIndex);
                    return true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                if (dragRow == row) {
                    abortRowDrag();
                    return true;
                }
                break;
        }
        return false;
    }

    private void startRowDrag(View row, int seqPos, int boundsIndex, int mode) {
        dragRow = row;
        dragMode = mode;
        dragFromPos = seqPos;
        dragTargetPos = seqPos;
        row.setElevation(dp(8));
        row.setAlpha(0.95f);
        // stop the ScrollView from stealing the gesture mid-drag
        segmentList.getParent().requestDisallowInterceptTouchEvent(true);
        mainHandler.removeCallbacks(dragAutoScroll);
        mainHandler.postDelayed(dragAutoScroll, 64);
        updateRowDragTargets();
    }

    private void updateRowDrag(float rawY) {
        if (dragRow == null) return;
        dragLastRawY = rawY;
        if (Math.abs(rawY - dragDownRawY) > dp((int) ROW_DRAG_SLOP_DP)) rowDragMoved = true;
        positionDragRow(rawY);
        updateRowDragTargets();
    }

    /** Keep the dragged row centred on the finger (clamped to the list). */
    private void positionDragRow(float rawY) {
        if (dragRow == null) return;
        int[] loc = new int[2];
        segmentList.getLocationOnScreen(loc);
        float listY = rawY - loc[1];
        float t = listY - dragRow.getHeight() / 2f - dragRow.getTop();
        float minT = -dragRow.getTop();
        float maxT = Math.max(minT, segmentList.getHeight() - dragRow.getBottom());
        dragRow.setTranslationY(Math.max(minT, Math.min(maxT, t)));
    }

    /**
     * Recompute the insertion target from the finger position and shift
     * the other rows to open a gap there (the clear insertion cue).
     */
    private void updateRowDragTargets() {
        if (dragRow == null || dragFromPos < 0) return;
        int n = segmentList.getChildCount();
        float rowStep = dragRow.getHeight() + dp(3); // height + row top margin
        int[] loc = new int[2];
        segmentList.getLocationOnScreen(loc);
        float listY = dragLastRawY - loc[1];
        // target = how many (unshifted) row centres lie above the finger
        int target = 0;
        for (int i = 0; i < n; i++) {
            if (i == dragFromPos) continue;
            View c = segmentList.getChildAt(i);
            float center = c.getTop() + c.getHeight() / 2f;
            if (listY > center) target++;
        }
        dragTargetPos = target;
        for (int i = 0; i < n; i++) {
            if (i == dragFromPos) continue;
            View c = segmentList.getChildAt(i);
            int slots;
            if (i < dragFromPos) slots = (dragTargetPos <= i) ? 1 : 0;
            else slots = (dragTargetPos <= i - 1) ? 0 : -1;
            c.setTranslationY(slots * rowStep);
        }
    }

    /** Finger released after a drag: commit the reorder. */
    private void finishRowDrag(int boundsIndex) {
        View row = dragRow;
        int from = dragFromPos;
        int to = dragTargetPos;
        int mode = dragMode;
        boolean moved = rowDragMoved;
        resetRowDrag();
        if (row == null || from < 0) return;
        suppressNextRowClick = true;
        // dragTargetPos counts the OTHER rows above the finger — an
        // insertion index in the list WITHOUT the dragged item. Convert to
        // the WITH-item index the reorder expects, otherwise every downward
        // drop lands one slot off (or snaps back home).
        int ins = to > from ? to + 1 : to;
        if (moved && ins != from && ins != from + 1) {
            if (mode == DRAG_SOURCES) {
                moveMergeSource(from, ins); // merge order; re-render below
                renderSegmentList();
                return;
            }
            EditorState before = currentState();
            if (timeline.reorderSegment(from, ins)) {
                stopPreview(); // preview must follow the new order
                pushUndoState(before);
                return; // reorderSegment already re-rendered the list
            }
        }
        renderSegmentList(); // dropped back in place — snap clean
    }

    /** Gesture cancelled (system/interception): restore visuals, no commit. */
    private void abortRowDrag() {
        resetRowDrag();
        suppressNextRowClick = true;
    }

    private void resetRowDrag() {
        mainHandler.removeCallbacks(dragAutoScroll);
        if (dragRow != null) {
            dragRow.setElevation(0);
            dragRow.setAlpha(1f);
        }
        for (int i = 0; i < segmentList.getChildCount(); i++) {
            segmentList.getChildAt(i).setTranslationY(0);
        }
        dragRow = null;
        dragFromPos = -1;
        dragTargetPos = -1;
    }

    // ------------------------------------------------------------------
    // Save / Export
    // ------------------------------------------------------------------

    private void confirmSave() {
        if (jobRunning) return;
        if (currentTab == TAB_MERGE) {
            confirmMergeJob();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Save Video")
                .setItems(new CharSequence[]{"Save merged (one video file)", "Save separately (individual parts)"},
                        (dialog, which) -> {
                            if (which == 0) {
                                showMergeOrderDialog();
                            } else {
                                runSplitJob(FFmpegVideoEditor.MODE_SEPARATE, null);
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Order editor for merge: starts from the CURRENT list order (the
     * sequence), up/down to fine-tune. Parts keep their original numbers.
     */
    private void showMergeOrderDialog() {
        final List<EditorTimelineView.SegmentInfo> segs = timeline.getKeptSegments();
        if (segs.isEmpty()) {
            Toast.makeText(this, "No video parts to save", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) order.add(i); // current order

        View sheet = LayoutInflater.from(this).inflate(R.layout.dialog_editor_merge_order, null);
        LinearLayout list = sheet.findViewById(R.id.mergeOrderList);

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            list.removeAllViews();
            for (int pos = 0; pos < order.size(); pos++) {
                final int p = pos;
                EditorTimelineView.SegmentInfo seg = segs.get(order.get(pos));
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
                label.setText((pos + 1) + ".  Part " + seg.number
                        + "   (" + msToTime(seg.startMs) + " → " + msToTime(seg.endMs) + ")");
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
                .setPositiveButton("Merge", (d, w) -> runSplitJob(FFmpegVideoEditor.MODE_MERGE, order))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Confirm before the export actually starts (same idiom as the other confirms). */
    private void confirmMergeJob() {
        if (mergeSources.size() < 2) {
            runMergeJob(); // that path shows the "add one more" toast
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Merge " + mergeSources.size() + " videos?")
                .setMessage(mergeSources.size() + " videos will be joined into one file "
                        + "(" + msToTime(mergeDurationMs) + " total).\n\n"
                        + "The original files are not modified.")
                .setPositiveButton("Merge", (d, w) -> runMergeJob())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runMergeJob() {
        if (mergeSources.size() < 2) {
            Toast.makeText(this, "Add at least one more video to merge",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        stopPreview();
        List<Uri> uris = new ArrayList<>(mergeSources.size());
        for (Source s : mergeSources) uris.add(s.uri);
        // boundaries must be the merge list's OWN offsets — the list is a
        // separate axis from the timeline's sources.
        long[] boundaries = new long[mergeSources.size() + 1];
        for (int i = 0; i < mergeSources.size(); i++) {
            boundaries[i] = mergeSources.get(i).offsetMs;
        }
        boundaries[mergeSources.size()] = mergeDurationMs;

        List<FFmpegVideoEditor.Segment> segs = new ArrayList<>();
        segs.add(new FFmpegVideoEditor.Segment(0, mergeDurationMs));
        startJob(FFmpegVideoEditor.MODE_MERGE, uris, boundaries, segs, baseNameOf(mergeSources));
    }

    /**
     * Split job over the timeline. {@code customOrder} holds POSITIONS into
     * {@link EditorTimelineView#getSegments()} (which already returns the
     * parts in play order), so no further index translation is needed.
     */
    private void runSplitJob(int mode, List<Integer> customOrder) {
        if (sources.isEmpty()) return;
        stopPreview();
        List<long[]> segs = timeline.getSegments();
        if (segs.isEmpty()) {
            Toast.makeText(this, "No video parts to save", Toast.LENGTH_SHORT).show();
            return;
        }

        List<FFmpegVideoEditor.Segment> ordered = new ArrayList<>();
        if (mode == FFmpegVideoEditor.MODE_MERGE && customOrder != null) {
            for (int idx : customOrder) {
                if (idx >= 0 && idx < segs.size()) {
                    long[] s = segs.get(idx);
                    ordered.add(new FFmpegVideoEditor.Segment(s[0], s[1]));
                }
            }
        } else {
            for (long[] s : segs) {
                ordered.add(new FFmpegVideoEditor.Segment(s[0], s[1]));
            }
        }
        if (ordered.isEmpty()) {
            Toast.makeText(this, "No video parts to save", Toast.LENGTH_SHORT).show();
            return;
        }

        List<Uri> uris = new ArrayList<>();
        long[] boundaries = new long[sources.size() + 1];
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            uris.add(s.uri);
            // the same time axis the timeline draws in: offsetMs is where this
            // source starts in the edited timeline
            boundaries[i] = s.offsetMs;
            boundaries[i + 1] = s.offsetMs + s.durationMs;
        }

        startJob(mode, uris, boundaries, ordered, baseNameOf(sources));
    }

    private void startJob(int mode, List<Uri> uris, long[] boundaries,
                          List<FFmpegVideoEditor.Segment> segs, String baseName) {
        stopPreview();
        jobRunning = true;
        updateSaveButtonState();
        progressTitle.setText("Saving Video…");
        progressBar.setIndeterminate(true);
        progressBar.setProgress(0);
        progressText.setText("Preparing…");
        progressOverlay.setVisibility(View.VISIBLE);

        // A lossless cut can only start on a keyframe, so the probe's answers
        // decide where the parts are actually cut. Exporting before they are
        // in would cut off-grid - ffmpeg would then start each part at the
        // previous keyframe and the parts would repeat video at every seam.
        // The probe is a quick demux pass, so the job waits for it.
        requestKeyframes();
        pendingJob = () -> launchEditorJob(mode, uris, boundaries, segs, baseName);
        pendingJobDeadline = SystemClock.uptimeMillis() + PROBE_WAIT_MS;
        if (probesPending()) {
            progressText.setText("Reading keyframe positions…");
            mainHandler.postDelayed(probeWaiter, PROBE_POLL_MS);
        } else {
            Runnable job = pendingJob;
            pendingJob = null;
            job.run();
        }
    }

    /** True while a loaded video is still waiting for its probe answer. */
    private boolean probesPending() {
        for (Source s : sources) if (!s.probeDone) return true;
        for (Source s : mergeSources) if (!s.probeDone) return true;
        return false;
    }

    /**
     * Starts the export as soon as every probe has answered. The deadline is
     * only a safety net for a file whose probe never returns: the engine then
     * reads the keyframes itself and, if it cannot, says so in the result.
     */
    private final Runnable probeWaiter = new Runnable() {
        @Override
        public void run() {
            if (!jobRunning || pendingJob == null) return;
            if (!probesPending() || SystemClock.uptimeMillis() > pendingJobDeadline) {
                Runnable job = pendingJob;
                pendingJob = null;
                job.run();
                return;
            }
            progressText.setText("Reading keyframe positions…");
            mainHandler.postDelayed(this, PROBE_POLL_MS);
        }
    };

    private void launchEditorJob(int mode, List<Uri> uris, long[] boundaries,
                                 List<FFmpegVideoEditor.Segment> segs, String baseName) {
        // what the engine needs to know about each file: keyframes (cuts) and
        // the stream signature (whether a merge is possible at all)
        List<FFmpegVideoEditor.MediaInfo> infos = new ArrayList<>(uris.size());
        for (Uri u : uris) {
            Source match = null;
            for (Source s : sources) if (s.uri.equals(u)) { match = s; break; }
            for (Source s : mergeSources) if (s.uri.equals(u) && match == null) match = s;
            infos.add(match != null ? match.media : null);
        }

        editor.runJobFromUri(this, uris, infos, boundaries, mode, segs, baseName,
                new FFmpegVideoEditor.EditListener() {
                    @Override
                    public void onProgress(int percent) {
                        if (percent < 0) {
                            progressBar.setIndeterminate(true);
                            progressText.setText("Preparing videos…");
                        } else {
                            progressBar.setIndeterminate(false);
                            progressBar.setProgress(percent);
                            progressText.setText(percent + "%");
                        }
                    }

                    @Override
                    public void onWarnings(List<String> notes) {
                        // collected while the job runs; shown with the result
                        jobWarnings = notes;
                    }

                    @Override
                    public void onSuccess(List<File> outputs) {
                        jobRunning = false;
                        pendingJob = null;
                        progressOverlay.setVisibility(View.GONE);
                        updateSaveButtonState();
                        showResultDialog(outputs, jobWarnings);
                    }

                    @Override
                    public void onError(String message, boolean cancelled) {
                        jobRunning = false;
                        pendingJob = null;
                        progressOverlay.setVisibility(View.GONE);
                        updateSaveButtonState();
                        if (cancelled) {
                            Toast.makeText(VideoEditorActivity.this,
                                    "Cancelled", Toast.LENGTH_SHORT).show();
                        } else {
                            new AlertDialog.Builder(VideoEditorActivity.this)
                                    .setTitle("Export Failed")
                                    .setMessage(message)
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                    }
                });
    }

    /**
     * Where the outputs went - the same recap the audio editor shows. Any
     * compromise the engine had to make (a file re-wrapped, a cut moved onto a
     * keyframe, keyframes unavailable) is listed here instead of being hidden:
     * the user should know why a part boundary moved.
     */
    private void showResultDialog(List<File> outputs) {
        showResultDialog(outputs, jobWarnings);
    }

    private void showResultDialog(List<File> outputs, List<String> notes) {
        if (outputs == null || outputs.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < outputs.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(outputs.get(i).getName());
        }
        File dir = outputs.get(0).getParentFile();
        if (dir != null) sb.append("\n\nFolder: ").append(dir.getAbsolutePath());
        if (notes != null && !notes.isEmpty()) {
            sb.append("\n\nNotes:");
            for (String n : notes) sb.append("\n• ").append(n);
        }
        new AlertDialog.Builder(this)
                .setTitle(outputs.size() == 1 ? "Saved" : "Saved " + outputs.size() + " files")
                .setMessage(sb)
                .setPositiveButton("Done", null)
                .show();
    }

    /**
     * Ask every loaded video about its keyframes (the cut positions a
     * lossless copy can actually use) and its stream signature (what a merge
     * cannot change). Each answer refreshes the snap grid, and an export waits
     * for the answers before it starts.
     */
    private void requestKeyframes() {
        List<Source> all = new ArrayList<>(sources);
        for (Source s : mergeSources) {
            boolean dup = false;
            for (Source o : sources) if (o == s) { dup = true; break; }
            if (!dup) all.add(s);
        }
        for (Source s : all) {
            if (s.probeDone || s.keyframesRequested) continue;
            s.keyframesRequested = true;
            KeyframeProbe.probeInfo(this, s.uri, info -> {
                s.keyframesMs = info.keyframesMs;
                s.media = FFmpegVideoEditor.MediaInfo.from(info, s.title);
                s.probeDone = true;
                refreshSnapPoints();
                if (currentTab == TAB_MERGE) {
                    updateMergeInfoText();      // may reveal a mismatch now known
                    updateSaveButtonState();
                }
            });
        }
    }

    /**
     * Hand the timeline the keyframe grid of the whole edited timeline: every
     * source's keyframes, shifted by that source's offset. Markers can then
     * only be placed where the sources can really be cut, so a split line is
     * where the cut lands - and a split followed by a merge reproduces the
     * original length. Sources whose probe has not answered yet contribute
     * nothing (their stretch of timeline stays free until it does).
     */
    private void refreshSnapPoints() {
        if (timeline == null) return;
        int total = 0;
        for (Source s : sources) {
            if (s.keyframesMs != null) total += s.keyframesMs.length;
        }
        if (total == 0) {
            timeline.setSnapPoints(null);
            return;
        }
        long[] grid = new long[total];
        int n = 0;
        for (Source s : sources) {
            long[] kfs = s.keyframesMs;
            if (kfs == null) continue;
            for (long k : kfs) {
                long global = s.offsetMs + k;
                if (global < s.offsetMs + s.durationMs) grid[n++] = global;
            }
        }
        timeline.setSnapPoints(n > 0 ? (n == grid.length ? grid : Arrays.copyOf(grid, n)) : null);
    }

    private void extractWaveforms() {
        for (Source s : sources) {
            if (s.waveform == null && !s.waveformRequested) {
                s.waveformRequested = true;
                waveformExtractor.extract(this, s.uri, data -> {
                    s.waveform = data;
                    combineWaveforms();
                });
            }
        }
    }

    private void combineWaveforms() {
        int buckets = WaveformExtractor.bucketsFor(durationMs);
        float[] globalWaveform = new float[buckets];
        for (Source s : sources) {
            if (s.waveform == null) continue;
            int startIdx = (int) (s.offsetMs * buckets / Math.max(1, durationMs));
            int endIdx = (int) ((s.offsetMs + s.durationMs) * buckets / Math.max(1, durationMs));
            int span = Math.max(1, endIdx - startIdx);
            for (int i = 0; i < span && startIdx + i < buckets; i++) {
                int srcIdx = (int) ((float) i / span * s.waveform.length);
                if (srcIdx < s.waveform.length) {
                    globalWaveform[startIdx + i] = s.waveform[srcIdx];
                }
            }
        }
        timeline.setWaveform(globalWaveform);
    }

    private static String baseNameOf(List<Source> list) {
        if (list.isEmpty()) return "video";
        String s = list.get(0).title;
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private long probeDuration(Uri uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(this, uri);
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Long.parseLong(d) : 0;
        } catch (Exception ignored) {
            return 0;
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {}
        }
    }

    private String displayNameOf(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name != null) return name;
        if ("file".equals(uri.getScheme())) return new File(uri.getPath()).getName();
        return "video";
    }

    private static String msToTime(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        long m = totalSec / 60;
        long s = totalSec % 60;
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void finish() {
        // never leave an FFmpeg job running behind a closing screen
        if (jobRunning) {
            try {
                editor.cancel();
            } catch (Exception ignored) {}
        }
        pendingJob = null;
        mainHandler.removeCallbacks(probeWaiter);
        super.finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPreview();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pendingJob = null;
        mainHandler.removeCallbacks(probeWaiter);
        stopPreview();
        editor.release();
        waveformExtractor.release();
    }
}
