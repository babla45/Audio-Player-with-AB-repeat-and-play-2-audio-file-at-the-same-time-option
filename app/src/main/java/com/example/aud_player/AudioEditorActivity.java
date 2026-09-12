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
import android.view.MotionEvent;
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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Audio Editor: multi-split and merge for the currently selected track,
 * powered by FFmpeg (FFmpegAudioEditor).
 *
 * - Merge tab: its own song list — EMPTY when the editor opens (the song the
 *   editor was launched with is not added automatically). ＋ opens the
 *   library in multi-select mode, so several songs can be added at once; the
 *   rows can then be reordered (drag the ⠿ handle) or removed, and joined
 *   into one file. The concatenation is a stream copy whenever the formats
 *   allow it, with the automatic AAC re-encode fallback when they don't.
 *   This list is independent of the Split timeline's sources.
 * - Split tab: professional timeline editor (video-editor style):
 *   scrollable + zoomable waveform timeline with a time ruler, numbered
 *   segment blocks, draggable split boundaries, tap-to-position playhead,
 *   play/pause and per-segment preview.
 * - Save options: separately (one file per segment) or merged — sequential
 *   order by default, manual reorder (▲/▼) before merging.
 * - Entry points: the app's own Menu → Audio Edit, and the system SHARE
 *   sheet — one shared audio file opens the Split tab, several open the
 *   Merge tab with all of them already loaded.
 * - Preview uses an independent MediaPlayer, leaving the main app
 *   playback untouched.
 */
public class AudioEditorActivity extends AppCompatActivity {

    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_DURATION_MS = "extra_duration_ms";

    /** Tab 0 — kept as 0 so the persisted last-tab pref keeps its meaning. */
    private static final int TAB_MERGE = 0;
    private static final int TAB_SPLIT = 1;
    private static final long PLAYHEAD_UPDATE_MS = 33; // ~30fps smooth playhead
    /** Max undo/redo history levels (professional-editor style). */
    private static final int MAX_HISTORY = 50;
    /** Default zoom when entering split mode: only a slice of the song is
     *  visible, so the first 15s waveform chunk paints almost instantly. */
    private static final float DEFAULT_SPLIT_ZOOM = 10f;

    /** Snapshot of the split-mode timeline state for undo/redo. */
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

    /**
     * One audio file on the edited timeline. Sources are played and exported
     * as a concatenation, so each one owns the global time range
     * [offsetMs, offsetMs + durationMs). With a single source the offset is 0
     * and every mapping is the identity — i.e. the behaviour before songs
     * could be appended.
     */
    private static class Source {
        final Uri uri;
        final String title;
        final long durationMs;
        long offsetMs;
        /** 600-bucket RMS waveform, or null until it has been decoded. */
        float[] waveform;
        /** true once a decode has been asked for (never decode twice). */
        boolean waveformRequested;

        Source(Uri uri, String title, long durationMs) {
            this.uri = uri;
            this.title = title;
            this.durationMs = Math.max(1, durationMs);
        }
    }

    private final List<Source> sources = new ArrayList<>();
    private String songTitle;
    /** TOTAL length of the edited timeline (all sources concatenated). */
    private long durationMs;

    /**
     * The Merge tab's own song list, deliberately SEPARATE from the Split
     * timeline's {@link #sources} — and EMPTY when the editor opens:
     * launching the editor from a playing song must not silently put that
     * song into a merge, and reordering/removing merge songs must not
     * disturb the Split tab's splits.
     */
    private final List<Source> mergeSources = new ArrayList<>();
    /** TOTAL length of the merge list (0 while it is empty). */
    private long mergeDurationMs;

    private EditorTimelineView timeline;
    private HorizontalScrollView timelineScroll;
    private TextView rangeText, durationText, hintText, currentTimeText, zoomText;
    private TextView tabMerge, tabSplit, addSplitBtn, saveBtn;
    private TextView zoomOutBtn, zoomInBtn, undoBtn, redoBtn, seqPreviewBtn, songNameText;
    /** Merge tab: "N songs • Total mm:ss" (doubles as the add-a-song hint). */
    private TextView mergeInfoText;
    /** Icon-only buttons (the ✂/🗑 labels were dropped for icons). */
    private ImageButton playBtn, addSongBtn, deleteSplitBtn, mergeAddSongBtn;
    /** Auto-scroll toggle: when on, the timeline follows the playhead. */
    private ImageButton autoScrollBtn;
    private boolean autoScrollEnabled = false;
    /** Timeline panel (Split only), merge action row, Split control bar. */
    private View timelinePanel, mergeActionRow, controlBar, undoRedoRow;
    private LinearLayout segmentList;
    private View progressOverlay;
    private ProgressBar progressBar;
    private TextView progressText, progressTitle;
    private View cancelBtn;

    /** Remembers the last-used tab so the editor reopens there. */
    private static final String PREFS = "audio_editor_prefs";
    private static final String KEY_LAST_TAB = "last_tab";

    private int currentTab = TAB_MERGE;
    private boolean splitZoomInitialized = false;
    private boolean jobRunning = false;

    // ---- segment-list drag reorder state ----
    /** Drag target of a row's ⠿ handle: Split parts or Merge songs. */
    private static final int DRAG_PARTS = 0;
    private static final int DRAG_SOURCES = 1;
    private int dragMode = DRAG_PARTS;
    /** Row currently being dragged (null when idle). */
    private View dragRow;
    private int dragFromPos = -1;
    private int dragTargetPos = -1;
    private float dragLastRawY;
    private float dragDownRawY;
    /** true once the drag actually moved (vs. a plain handle tap). */
    private boolean rowDragMoved;
    /** swallows the click that follows a finished drag. */
    private boolean suppressNextRowClick;

    // ---- sequence preview (parts in the current list order) ----
    private List<long[]> previewQueue;
    private int previewQueueIndex = -1;
    /** boundary already advanced through (async-seek double-fire guard). */
    private long lastQueueAdvanceEnd = Long.MIN_VALUE;

    private final FFmpegAudioEditor editor = new FFmpegAudioEditor();
    private final WaveformExtractor waveformExtractor = new WaveformExtractor();

    private MediaPlayer previewPlayer;
    private long previewStartMs, previewEndMs;
    /** Which source `previewPlayer` is currently playing. */
    private int previewSourceIndex;
    /**
     * Which list the preview is playing: null = the Split timeline's
     * {@link #sources} (playhead, deleted-range skipping and the compressed
     * time readout all apply), non-null = the Merge tab's list (nothing on
     * the hidden timeline may be touched).
     */
    private List<Source> previewList;
    /** Merge rows' per-song progress bars / ▶-⏸ buttons, parallel to the list. */
    private final List<ProgressBar> mergeRowBars = new ArrayList<>();
    private final List<ImageButton> mergeRowButtons = new ArrayList<>();

    /** "+": pick another song from the library and append it to the timeline. */
    private final ActivityResultLauncher<Intent> songPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) appendSong(uri);
                }
            });

    /**
     * Merge tab's "+": the picker runs in multi-select mode, so several songs
     * can be added in one go. Falls back to the single-URI contract if the
     * caller is handed an older-style result.
     */
    private final ActivityResultLauncher<Intent> mergePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                ArrayList<Uri> uris =
                        data.getParcelableArrayListExtra(AudioListActivity.EXTRA_PICKED_URIS);
                if (uris == null || uris.isEmpty()) {
                    Uri single = data.getData();
                    if (single == null) return;
                    uris = new ArrayList<>();
                    uris.add(single);
                }
                addMergeSongs(uris);
            });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable playheadUpdater = new Runnable() {
        @Override
        public void run() {
            if (previewPlayer != null && timeline != null) {
                try {
                    // The player only knows the file of the source it was
                    // created for; the timeline works in GLOBAL time.
                    long pos = toGlobal(previewSourceIndex,
                            previewPlayer.getCurrentPosition());
                    // skip over deleted segments (KineMaster-style: the
                    // playhead jumps the gap instead of playing removed audio).
                    // Merge-tab previews have no timeline: nothing to skip.
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
                            if (previewPlayer == null) return; // finished
                            pos = toGlobal(previewSourceIndex,
                                    previewPlayer.getCurrentPosition());
                        } else {
                            // async seek not reflected yet — hold the
                            // playhead at the new part's start
                            pos = previewStartMs;
                        }
                    }
                    if (previewList == null) {
                        timeline.setPlayheadMs(pos);
                        // auto-scroll only when the user turned the toggle on;
                        // otherwise the timeline stays where they left it and
                        // manual scrolling is always free
                        if (autoScrollEnabled) followPlayhead();
                    } else {
                        // Merge rows: progress of the song being played
                        updateMergeRowProgress(pos);
                    }
                    updateCurrentTimeText(pos);
                } catch (Exception ignored) {
                }
                mainHandler.postDelayed(this, PLAYHEAD_UPDATE_MS);
            }
        }
    };

    /** Auto-scrolls the part list while dragging a row near its edges. */
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
        setContentView(R.layout.activity_audio_editor);

        Intent intent = getIntent();
        // launched from the system share sheet ("Share" on audio files)?
        List<Uri> shared = sharedAudioUris(intent);
        boolean fromShare = !shared.isEmpty();

        Uri firstUri;
        if (fromShare) {
            firstUri = shared.get(0);
            songTitle = displayNameOf(firstUri);
            sources.add(new Source(firstUri, songTitle,
                    Math.max(1000, probeDuration(firstUri))));
        } else {
            String uriStr = intent.getStringExtra(EXTRA_URI);
            if (uriStr == null) {
                finish();
                return;
            }
            firstUri = Uri.parse(uriStr);
            songTitle = intent.getStringExtra(EXTRA_TITLE);
            if (songTitle == null) songTitle = "audio";
            long firstDurationMs = Math.max(1000, intent.getLongExtra(EXTRA_DURATION_MS, 0));
            if (firstDurationMs <= 1000) {
                firstDurationMs = probeDuration(firstUri);
            }
            // the timeline starts as this one file; "+" appends more after it
            sources.add(new Source(firstUri, songTitle, firstDurationMs));
        }
        rebuildTimeMap();

        // Sharing several songs is a merge request: pre-load the Merge tab
        // with all of them, in the order the share sheet handed them over.
        // (The Split timeline keeps the first song as its baseline, exactly
        // as if the editor had been opened on it.)
        if (fromShare && shared.size() > 1) {
            Source first = sources.get(0);
            mergeSources.add(new Source(first.uri, first.title, first.durationMs));
            for (int i = 1; i < shared.size(); i++) {
                Uri u = shared.get(i);
                mergeSources.add(new Source(u, displayNameOf(u),
                        Math.max(1000, probeDuration(u))));
            }
            rebuildMergeMap();
        }

        bindViews();
        setupTabs();
        setupTimeline();
        setupActions();

        updateSongNameLabel();

        // Timeline setup: fit-all scale after first layout
        timeline.setDuration(durationMs);
        timeline.setPlayheadMs(0);
        timeline.post(() -> {
            if (isFinishing() || timeline == null) return;
            applyFitZoom();
            // split tab restored from last session? jump it to the 5x default
            if (currentTab == TAB_SPLIT) applyDefaultSplitZoom();
        });

        // Waveform (null result falls back to placeholder bars)
        extractWaveforms();

        // reopen on the tab the user last worked on — but a share decides
        // the tab: one song is a split request, several are a merge request
        currentTab = fromShare
                ? (shared.size() > 1 ? TAB_MERGE : TAB_SPLIT)
                : getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getInt(KEY_LAST_TAB, TAB_MERGE);

        updateUiForTab();
    }

    /**
     * Audio files handed over by the system share sheet: one for ACTION_SEND,
     * any number for ACTION_SEND_MULTIPLE. Empty for every other launch, so a
     * normal "Audio Edit" from the app is completely unaffected.
     */
    private List<Uri> sharedAudioUris(Intent intent) {
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
                // a few senders put a plain array in EXTRA_STREAM
                for (Object o : (Object[]) raw) {
                    Uri u = streamUri(o);
                    if (u != null) out.add(u);
                }
            }
        }
        return out;
    }

    /**
     * EXTRA_STREAM is normally a content Uri, but a few file managers still
     * hand over a plain string — either a URI or a bare filesystem path.
     */
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
        timeline = findViewById(R.id.editorTimeline);
        timelineScroll = findViewById(R.id.editorTimelineScroll);
        rangeText = findViewById(R.id.editorRangeText);
        durationText = findViewById(R.id.editorDurationText);
        currentTimeText = findViewById(R.id.editorCurrentTimeText);
        zoomText = findViewById(R.id.editorZoomText);
        hintText = findViewById(R.id.editorHintText);
        tabMerge = findViewById(R.id.editorTabMerge);
        tabSplit = findViewById(R.id.editorTabSplit);
        addSplitBtn = findViewById(R.id.editorAddSplitBtn);
        deleteSplitBtn = findViewById(R.id.editorDeleteSplitBtn);
        saveBtn = findViewById(R.id.editorSaveBtn);
        zoomOutBtn = findViewById(R.id.editorZoomOutBtn);
        zoomInBtn = findViewById(R.id.editorZoomInBtn);
        playBtn = findViewById(R.id.editorPlayBtn);
        autoScrollBtn = findViewById(R.id.editorAutoScrollBtn);
        addSongBtn = findViewById(R.id.editorAddSongBtn);
        songNameText = findViewById(R.id.editorSongName);
        mergeAddSongBtn = findViewById(R.id.editorMergeAddSongBtn);
        mergeInfoText = findViewById(R.id.editorMergeInfoText);
        timelinePanel = findViewById(R.id.editorTimelinePanel);
        mergeActionRow = findViewById(R.id.editorMergeActionRow);
        controlBar = findViewById(R.id.editorControlBar);
        undoRedoRow = findViewById(R.id.editorUndoRedoRow);
        undoBtn = findViewById(R.id.editorUndoBtn);
        redoBtn = findViewById(R.id.editorRedoBtn);
        seqPreviewBtn = findViewById(R.id.editorSeqPreviewBtn);
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
        tabMerge.setOnClickListener(v -> {
            currentTab = TAB_MERGE;
            saveLastTab();
            updateUiForTab();
        });
        tabSplit.setOnClickListener(v -> {
            currentTab = TAB_SPLIT;
            saveLastTab();
            updateUiForTab();
        });
    }

    /** Persist the active tab so the editor reopens on it. */
    private void saveLastTab() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putInt(KEY_LAST_TAB, currentTab).apply();
    }

    private void setupTimeline() {
        timeline.setTimelineListener(new EditorTimelineView.TimelineListener() {
            @Override
            public void onSplitPointsChanged(List<Long> pointsMs) {
                renderSegmentList();
                updateRangeText();
            }

            @Override
            public void onPlayheadMoved(long playheadMs) {
                updateCurrentTimeText(playheadMs);
                // live seek while playing (pro editor behaviour) — but never
                // during a sequence preview: tapping elsewhere on the timeline
                // must not yank the ordered playback out of its current part
                if (previewPlayer != null && previewQueue == null
                        && previewPlayer.isPlaying()) {
                    previewEndMs = Math.max(previewEndMs, playheadMs + 200);
                    seekPreviewTo(playheadMs);
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

            @Override
            public void onSegmentLongPressed(int index) {
                showSegmentContextMenu(index);
            }

            @Override
            public void onSplitMoveStarted() {
                // pre-drag snapshot, pushed before the boundary actually moves
                pushUndoState(currentState());
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
            EditorState before = currentState();
            timeline.deleteSelectedMarker();
            pushUndoState(before);
        });
        undoBtn.setOnClickListener(v -> undo());
        redoBtn.setOnClickListener(v -> redo());
        seqPreviewBtn.setOnClickListener(v -> toggleSequencePreview());
        playBtn.setOnClickListener(v -> togglePlayPause());
        autoScrollBtn.setOnClickListener(v -> {
            autoScrollEnabled = !autoScrollEnabled;
            // dim = off, bright = on
            autoScrollBtn.setColorFilter(autoScrollEnabled
                    ? 0xFFFFFFFF : 0x88FFFFFF);
            if (autoScrollEnabled) followPlayhead();
        });
        zoomOutBtn.setOnClickListener(v -> zoomBy(1f / 1.6f));
        zoomInBtn.setOnClickListener(v -> zoomBy(1.6f));
        addSongBtn.setOnClickListener(v -> pickSongToAppend());
        mergeAddSongBtn.setOnClickListener(v -> pickSongsToMerge());
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
        // a preview belongs to the tab it was started from (Merge previews
        // play a list the Split tab knows nothing about)
        stopPreview();
        boolean merge = currentTab == TAB_MERGE;
        int active = 0xFFFFFFFF;
        int inactive = 0x88FFFFFF;
        tabMerge.setTextColor(merge ? active : inactive);
        tabSplit.setTextColor(merge ? inactive : active);

        // Merge is the song list only — no timeline, no split tooling. The
        // undo/redo row is hidden too: the history does not cover the song
        // list, so it would only offer to undo a Split-tab edit from here.
        timelinePanel.setVisibility(merge ? View.GONE : View.VISIBLE);
        mergeActionRow.setVisibility(merge ? View.VISIBLE : View.GONE);
        controlBar.setVisibility(merge ? View.GONE : View.VISIBLE);
        undoRedoRow.setVisibility(merge ? View.GONE : View.VISIBLE);
        seqPreviewBtn.setVisibility(View.VISIBLE);
        saveBtn.setText(merge ? "Merge" : "Save");

        if (merge) {
            updateMergeInfoText();
        } else {
            // first entry into split mode: 5x default (fast first paint)
            applyDefaultSplitZoom();
            updateRangeText();
            updateZoomText();
            hintText.setText("Tap the timeline to position the playhead, then ✂ Split. "
                    + "Long-press a part to delete it; drag a row's ⠿ handle "
                    + "to reorder. Preview plays the parts in list order.");
            updateSaveButtonState();
        }
        renderSegmentList();
        updateCurrentTimeText(timeline.getPlayheadMs());
        updateUndoRedoButtons();
    }

    private void updateRangeText() {
        List<long[]> segs = timeline.getSegments();
        int deleted = timeline.getDeletedRanges().size();
        int sel = timeline.getSelectedSplit();
        String splitInfo = sel >= 0
                ? "Split " + (sel + 1) + " at "
                + formatTime(timeline.getSplitPoints().get(sel))
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
    }

    /** Merge tab readout: how many songs, and how long the merged file will be. */
    private void updateMergeInfoText() {
        int n = mergeSources.size();
        if (n == 0) {
            mergeInfoText.setText("No songs yet  •  tap the ＋ button to add some");
        } else if (n == 1) {
            mergeInfoText.setText("1 song  •  add one more to merge");
        } else {
            mergeInfoText.setText(n + " songs  •  Total " + formatTime(mergeDurationMs));
        }
        updateSaveButtonState();
    }

    /**
     * In the Merge tab the button is "Merge" and needs at least two songs —
     * merging one song would just copy it. The Split tab's Save is never
     * disabled (as before).
     */
    private void updateSaveButtonState() {
        if (currentTab != TAB_MERGE) {
            saveBtn.setEnabled(true);
            saveBtn.setAlpha(1f);
            return;
        }
        boolean canMerge = mergeSources.size() >= 2 && !jobRunning;
        saveBtn.setEnabled(canMerge);
        saveBtn.setAlpha(canMerge ? 1f : 0.45f);
    }

    private void updateCurrentTimeText(long ms) {
        // Split tab: the display (edited-result) time, so it matches the
        // compressed ruler. Merge tab: its own axis, no timeline mapping.
        currentTimeText.setText("▶ " + formatTime(
                previewList != null ? ms : timeline.toDisplayMs(ms)));
    }

    private void updateZoomText() {
        float z = timeline.getZoomLevel();
        zoomText.setText(String.format("Zoom %.1fx", z));
    }

    // ------------------------------------------------------------------
    // Zoom
    // ------------------------------------------------------------------

    /** Fit-all zoom: content width == viewport width (edited result). */
    private void applyFitZoom() {
        int viewport = timelineScroll.getWidth();
        if (viewport <= 0) return;
        float fit = viewport / (float) Math.max(1, timeline.getEditedDurationMs());
        timeline.setFitPxPerMs(fit);
        timeline.setPxPerMs(fit);
        updateZoomText();
    }

    /**
     * Default zoom on first entry into split mode: 5x — only a slice of
     * the song is visible, so the first waveform chunk (30s) paints almost
     * instantly. Later zoom/tab changes are never overridden.
     */
    private void applyDefaultSplitZoom() {
        if (splitZoomInitialized || timelineScroll.getWidth() <= 0) return;
        splitZoomInitialized = true;
        timeline.setFitPxPerMs(Math.max(timeline.getFitPxPerMs(),
                timelineScroll.getWidth() / (float) durationMs));
        timeline.setPxPerMs(timeline.getFitPxPerMs() * DEFAULT_SPLIT_ZOOM);
        timelineScroll.scrollTo(0, 0);
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

    // ------------------------------------------------------------------
    // Split management
    // ------------------------------------------------------------------

    // ---- Undo / Redo (multi-level, professional-editor semantics) ----

    private EditorState currentState() {
        return new EditorState(timeline.getSplitPoints(), timeline.getDeletedRanges(),
                timeline.getSequence(), timeline.getSelectedSplit(),
                timeline.getSelectedSegment());
    }

    /**
     * Record a state snapshot as the "before" of a new editing action.
     * A new action invalidates the redo history (cleared), and the stack
     * is capped at MAX_HISTORY levels.
     */
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

    /** Restore a snapshot: splits, deletions, order, selection, list, readouts. */
    private void applyState(EditorState s) {
        stopPreview(); // restored state may differ from what was playing
        timeline.setSplitPoints(s.splitPoints);
        timeline.setDeletedRanges(s.deletedRanges);
        timeline.setSequence(s.sequence);
        timeline.restoreSelection(s.selectedSplit, s.selectedSegment);
        renderSegmentList();
        updateRangeText();
        updateUndoRedoButtons();
    }

    private void updateUndoRedoButtons() {
        undoBtn.setEnabled(!undoStack.isEmpty());
        undoBtn.setAlpha(undoStack.isEmpty() ? 0.45f : 1f);
        redoBtn.setEnabled(!redoStack.isEmpty());
        redoBtn.setAlpha(redoStack.isEmpty() ? 0.45f : 1f);
    }

    // ---- Long-press context menu ----

    private void showSegmentContextMenu(int segmentIndex) {
        showSegmentContextMenu(segmentIndex, timelineScroll);
    }

    /**
     * Long-press on a segment (timeline or part-list row): offer to delete
     * that ENTIRE part. The remaining parts butt together on the timeline
     * (KineMaster-style) and the deleted range is excluded when saving.
     * The original audio file is never modified during editing.
     */
    private void showSegmentContextMenu(int segmentIndex, View anchor) {
        if (timeline.getKeptSegments().size() <= 1) {
            Toast.makeText(this, "Can't delete the only remaining part",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        EditorTimelineView.SegmentInfo info = null;
        for (EditorTimelineView.SegmentInfo s : timeline.getKeptSegments()) {
            if (s.index == segmentIndex) {
                info = s;
                break;
            }
        }
        if (info == null) return; // pressed a deleted part — impossible via taps

        final int number = info.number;
        final int boundsIndex = info.index;
        final String range = formatTime(info.startMs) + " → " + formatTime(info.endMs);

        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, "🗑 Delete Part " + number + " (" + range + ")");
        menu.setOnMenuItemClickListener(item -> {
            confirmDeleteSegment(number, boundsIndex, range);
            return true;
        });
        menu.show();
    }

    private void confirmDeleteSegment(int number, int boundsIndex, String range) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Part " + number + "?")
                .setMessage(range + " will be removed from the edited result.\n\n"
                        + "The original file is not modified — you can Undo this.")
                .setPositiveButton("Delete", (d, w) -> {
                    EditorState before = currentState();
                    if (!timeline.deleteSegment(boundsIndex)) {
                        Toast.makeText(this, "Can't delete the only remaining part",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    stopPreview(); // stale queue must not keep old order playing
                    pushUndoState(before);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
    /** Pro workflow: add a split at the current playhead position. */
    private void addSplitAtPlayhead() {
        EditorState before = currentState();
        long t = timeline.getPlayheadMs();
        int idx = timeline.addSplitPoint(t);
        if (idx < 0) {
            Toast.makeText(this, "Can't split there — too close to the start, "
                    + "end or another split", Toast.LENGTH_SHORT).show();
            return;
        }
        pushUndoState(before);
        renderSegmentList();
        updateRangeText();
        // make the new boundary visible (playhead x uses the compressed
        // display mapping when segments have been deleted)
        int viewport = timelineScroll.getWidth();
        float x = timeline.getPlayheadX();
        if (x < timelineScroll.getScrollX() || x > timelineScroll.getScrollX() + viewport) {
            timelineScroll.smoothScrollTo((int) Math.max(0, x - viewport / 2f), 0);
        }
    }

    private void renderSegmentList() {
        segmentList.removeAllViews();
        // the Merge rows' controls are rebuilt with the rows themselves
        mergeRowBars.clear();
        mergeRowButtons.clear();
        if (currentTab == TAB_MERGE) {
            renderSourceList();
            return;
        }
        List<EditorTimelineView.SegmentInfo> segs = timeline.getKeptSegments();
        int selectedSeg = timeline.getSelectedSegment();
        for (int i = 0; i < segs.size(); i++) {
            EditorTimelineView.SegmentInfo seg = segs.get(i);
            segmentList.addView(buildSegmentRow(seg.number, seg.index, i,
                    seg.startMs, seg.endMs, selectedSeg));
        }
    }

    // ------------------------------------------------------------------
    // Merge tab: the song list (add / reorder / remove, then merge)
    // ------------------------------------------------------------------

    /** One row per song, in merge order: [⠿][title + times][▶][🗑]. */
    private void renderSourceList() {
        if (mergeSources.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("No songs added yet.\nTap the ＋ button above to pick songs "
                    + "— you can select several at once.");
            hint.setTextColor(0x99FFFFFF);
            hint.setTextSize(12f);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(dp(12), dp(28), dp(12), dp(28));
            segmentList.addView(hint);
            return;
        }
        for (int i = 0; i < mergeSources.size(); i++) {
            segmentList.addView(buildSourceRow(i, mergeSources.get(i)));
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
        times.setText(formatTime(startMs) + " → " + formatTime(endMs)
                + "   (" + formatTime(src.durationMs) + ")");
        times.setTextColor(0xAAFFFFFF);
        times.setTextSize(11f);
        textCol.addView(times);

        // per-song seek bar: tap or drag anywhere on it to jump to that point
        // of the song (it starts playing there if it wasn't the loaded one).
        // The strip is taller than the line so it is comfortable to hit.
        FrameLayout barHolder = new FrameLayout(this);
        LinearLayout.LayoutParams bhLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22));
        bhLp.topMargin = dp(1);
        barHolder.setLayoutParams(bhLp);
        android.util.TypedValue barTv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, barTv, true);
        barHolder.setBackgroundResource(barTv.resourceId != 0 ? barTv.resourceId : 0);
        barHolder.setContentDescription("Seek within this song");

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
        play.setContentDescription("Play/Pause this song");
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
        delete.setContentDescription("Remove this song from the merge");
        delete.setOnClickListener(v -> confirmRemoveMergeSource(index));
        row.addView(delete);

        return row;
    }

    /** Recompute the merge list's own axis (independent of the timeline's). */
    private void rebuildMergeMap() {
        long off = 0;
        for (Source s : mergeSources) {
            s.offsetMs = off;
            off += s.durationMs;
        }
        mergeDurationMs = Math.max(0, off);
    }

    /**
     * Add every picked song to the END of the merge list, in the order the
     * picker returned them. One toast and one re-render for the whole batch.
     */
    private void addMergeSongs(List<Uri> uris) {
        if (uris == null || uris.isEmpty() || isFinishing()) return;
        stopPreview();
        int added = 0;
        long addedMs = 0;
        for (Uri uri : uris) {
            if (uri == null) continue;
            long dur = Math.max(1000, probeDuration(uri));
            mergeSources.add(new Source(uri, displayNameOf(uri), dur));
            addedMs += dur;
            added++;
        }
        if (added == 0) return;
        rebuildMergeMap();
        updateMergeInfoText();
        renderSegmentList();
        Toast.makeText(this, "Added " + added + (added == 1 ? " song" : " songs")
                + "  •  " + formatTime(addedMs) + "  •  total "
                + formatTime(mergeDurationMs), Toast.LENGTH_SHORT).show();
    }

    /**
     * Drop one song from the merge list. Removing the last one is allowed —
     * the list simply goes back to being empty (the Split tab is untouched).
     */
    /** Ask before dropping a song from the merge list (same idiom as the Split tab). */
    private void confirmRemoveMergeSource(int index) {
        if (index < 0 || index >= mergeSources.size()) return;
        String title = mergeSources.get(index).title;
        new AlertDialog.Builder(this)
                .setTitle("Remove this song?")
                .setMessage(title + " will be removed from the merge list.\n\n"
                        + "The original file is not modified.")
                .setPositiveButton("Remove", (d, w) -> removeMergeSource(index))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void removeMergeSource(int index) {
        if (index < 0 || index >= mergeSources.size()) return;
        stopPreview();
        mergeSources.remove(index);
        rebuildMergeMap();
        updateMergeInfoText();
        renderSegmentList();
    }

    /**
     * Move the song at {@code from} to the WITH-item insertion position
     * {@code to} in the merge order — the same contract finishRowDrag gives
     * EditorTimelineView.reorderSegment.
     * Returns false when the move is a no-op. The caller re-renders the list.
     */
    private boolean moveMergeSource(int from, int to) {
        if (from < 0 || from >= mergeSources.size()
                || to < 0 || to > mergeSources.size()) return false;
        if (to == from || to == from + 1) return false;
        stopPreview(); // the old order must not keep playing
        // removing the item first shifts the insertion point by one for
        // downward moves
        mergeSources.add(to > from ? to - 1 : to, mergeSources.remove(from));
        rebuildMergeMap();
        updateMergeInfoText();
        return true;
    }

    private String rangeLabel(long startMs, long endMs) {
        return formatTime(startMs) + " → " + formatTime(endMs);
    }

    private View buildSegmentRow(final int number, final int boundsIndex,
                                 final int seqPos, long startMs, long endMs,
                                 int selectedSeg) {
        final boolean selected = boundsIndex == selectedSeg && currentTab == TAB_SPLIT;
        // describes content, not position: stays on the part that reaches
        // the end of the song even after a reorder
        boolean reachesEnd = endMs >= durationMs - 1;

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
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textCol.setLayoutParams(tcLp);

        TextView title = new TextView(this);
        title.setText("Part " + number + (reachesEnd ? "  (till end)" : ""));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(12f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        textCol.addView(title);

        TextView times = new TextView(this);
        times.setText(formatTime(startMs) + " → " + formatTime(endMs)
                + "   (" + formatTime(Math.max(0, endMs - startMs)) + ")");
        times.setTextColor(0xAAFFFFFF);
        times.setTextSize(11f);
        textCol.addView(times);

        row.addView(textCol);

        // borderless ripple background shared by the icon buttons
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);

        // ⠿ drag handle: touch = instant drag-and-drop reorder (no delay)
        if (currentTab == TAB_SPLIT) {
            ImageView handle = new ImageView(this);
            handle.setImageResource(R.drawable.ic_drag_handle);
            handle.setColorFilter(0xFFFFFFFF);
            handle.setBackgroundResource(tv.resourceId != 0 ? tv.resourceId : 0);
            LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(26), dp(30));
            hLp.rightMargin = dp(4);
            handle.setLayoutParams(hLp);
            handle.setContentDescription("Drag to reorder");
            handle.setOnTouchListener((v, ev) ->
                    handleHandleTouch(row, ev, seqPos, boundsIndex));
            row.addView(handle);
        }

        // play first, then delete (swapped per request): [⠿][text][▶][🗑]
        ImageButton play = new ImageButton(this);
        play.setImageResource(R.drawable.ic_play);
        play.setColorFilter(0xFFFFFFFF); // icons are black — force white
        play.setBackgroundResource(tv.resourceId != 0 ? tv.resourceId : 0);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        pbLp.leftMargin = dp(6);
        play.setLayoutParams(pbLp);
        play.setOnClickListener(v -> previewRange(startMs, endMs));
        row.addView(play);

        // delete icon: removes this segment from the edited result
        ImageButton delete = new ImageButton(this);
        delete.setImageResource(R.drawable.ic_delete);
        delete.setColorFilter(0xFFFFFFFF); // icons are black — force white
        delete.setBackgroundResource(tv.resourceId != 0 ? tv.resourceId : 0);
        LinearLayout.LayoutParams dbLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        dbLp.leftMargin = dp(6);
        delete.setLayoutParams(dbLp);
        delete.setOnClickListener(v -> {
            if (currentTab == TAB_SPLIT) {
                confirmDeleteSegment(number, boundsIndex, rangeLabel(startMs, endMs));
            }
        });
        row.addView(delete);

        // tapping a row selects that segment on the timeline
        row.setOnClickListener(v -> {
            if (suppressNextRowClick) {
                suppressNextRowClick = false;
                return;
            }
            if (currentTab == TAB_SPLIT) {
                timeline.selectSegment(boundsIndex);
            }
        });

        // reordering is done via the ⠿ handle; deletion via the 🗑 icon —
        // no long-press action on list rows (the timeline still has one)

        return row;
    }

    // ------------------------------------------------------------------
    // Segment list: drag-handle reorder + long-press delete
    // ------------------------------------------------------------------

    private static final float ROW_DRAG_SLOP_DP = 10f;

    /**
     * Touch handler for a row's ⠿ drag handle: the drag starts IMMEDIATELY
     * on touch (no long-press wait), consuming events so the ScrollView
     * never interferes. Tapping the handle without moving is a no-op.
     * Works on both lists: Split parts and Merge songs.
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
        if (Math.abs(rawY - dragDownRawY) > dp(ROW_DRAG_SLOP_DP)) rowDragMoved = true;
        positionDragRow(rawY);
        updateRowDragTargets();
    }

    /** Keep the dragged row centred on the finger (clamped to the list). */
    private void positionDragRow(float rawY) {
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
    // Playback / preview
    // ------------------------------------------------------------------

    /**
     * Bottom Preview button: play the parts CONTINUOUSLY in their current
     * list order — the sequence is the single source of truth, matching the
     * final Merge/Save. On the Split tab that is the part sequence of the
     * timeline; on the Merge tab it is the merge list itself (one part per
     * song, in list order), i.e. exactly what Merge will write out. One
     * player, seeked part to part: no duplicate or overlapping playback.
     * Click again to stop.
     */
    private void toggleSequencePreview() {
        if (previewQueue != null) {
            stopPreview();
            return;
        }
        boolean merge = currentTab == TAB_MERGE;
        if (merge) {
            startMergeSequencePreview();
            return;
        }
        List<long[]> segs = timeline.getSegments();
        if (segs.isEmpty()) return;
        stopPreview();
        previewList = null; // the timeline axis
        previewQueue = segs;
        previewQueueIndex = 0;
        lastQueueAdvanceEnd = Long.MIN_VALUE;
        createPreviewPlayer(segs.get(0)[0], segs.get(0)[1]);
    }

    /**
     * Merge tab play button: play the whole merge list in order, starting at
     * the first song — one part per song, exactly what Merge will write out.
     */
    private void startMergeSequencePreview() {
        if (mergeSources.isEmpty()) {
            Toast.makeText(this, "Add a song first", Toast.LENGTH_SHORT).show();
            return;
        }
        stopPreview();
        List<long[]> segs = new ArrayList<>(mergeSources.size());
        long off = 0;
        for (Source s : mergeSources) {
            segs.add(new long[]{off, off + s.durationMs});
            off += s.durationMs;
        }
        previewList = mergeSources; // the merge list's own axis
        previewQueue = segs;
        previewQueueIndex = 0;
        lastQueueAdvanceEnd = Long.MIN_VALUE;
        createPreviewPlayer(segs.get(0)[0], segs.get(0)[1]);
    }

    /**
     * ▶/⏸ on a Merge-tab row: pause/resume when that song is the one already
     * loaded in the player, otherwise start it (replacing whatever played).
     */
    private void toggleMergeSourcePreview(int index) {
        if (previewPlayer != null && previewList == mergeSources
                && index == previewSourceIndex) {
            togglePlayPause();
            return;
        }
        previewMergeSource(index);
    }

    /**
     * Start the song at {@code index} of the merge list from its beginning.
     * It runs on the merge list's own axis, so the hidden Split timeline is
     * never touched.
     */
    private void previewMergeSource(int index) {
        if (index < 0 || index >= mergeSources.size()) return;
        stopPreview();
        previewList = mergeSources;
        Source s = mergeSources.get(index);
        createPreviewPlayer(s.offsetMs, s.offsetMs + s.durationMs);
    }

    /**
     * Keep the playhead visible during playback (page-flip follow). Only
     * called when the auto-scroll toggle is on; manual scrolling stays free
     * and is never fought while the user drags the timeline.
     */
    private void followPlayhead() {
        if (previewPlayer == null || timeline == null) return;
        int viewport = timelineScroll.getWidth();
        if (viewport <= 0) return;
        float px = timeline.getPlayheadX() - timelineScroll.getScrollX();
        if (px > viewport - dp(48) || px < 0) {
            int target = (int) Math.max(0, timeline.getPlayheadX() - viewport / 2f);
            timelineScroll.smoothScrollTo(target, 0);
        }
    }

    /** Continue the sequence preview with the next part (same player). */
    private void advanceSequencePreview() {
        if (previewQueue == null || previewPlayer == null) return;
        if (previewQueueIndex >= previewQueue.size() - 1) {
            stopPreview(); // last part finished
            return;
        }
        previewQueueIndex++;
        long[] next = previewQueue.get(previewQueueIndex);
        try {
            previewStartMs = next[0];
            previewEndMs = next[1];
            int idx = sourceIndexAt(next[0]);
            if (idx != previewSourceIndex) {
                // the next part lives in another song: one MediaPlayer cannot
                // read two files, so swap the player (the queue is kept)
                releasePlayer();
                createPreviewPlayer(next[0], next[1], idx);
                return;
            }
            previewPlayer.seekTo((int) toLocal(idx, next[0]));
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
     * Split tab control bar: play from the playhead / pause / resume.
     * (In the Merge tab this is reached from a row's ⏸ button, which only
     * calls it once that row's song is loaded — the Merge tab's play
     * control is the bottom ▶ Preview / each row's ▶⏸.)
     */
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
                // one chain only: the updater keeps running while paused, so a
                // plain post() here would double the update rate every resume
                mainHandler.removeCallbacks(playheadUpdater);
                mainHandler.post(playheadUpdater);
            }
        } catch (Exception ignored) {
        }
        updatePlayButtons();
    }

    private void previewRange(long startMs, long endMs) {
        stopPreview();
        createPreviewPlayer(startMs, endMs);
    }

    /** Create the preview player for a single range (call after stopPreview). */
    private void createPreviewPlayer(long startMs, long endMs) {
        createPreviewPlayer(startMs, endMs, sourceIndexAt(startMs));
    }

    /**
     * Create the player for one range. `startMs`/`endMs` are GLOBAL timeline
     * times; the player is anchored to the source that owns the start, seeked
     * to the matching local time. A range that runs past the end of that song
     * keeps playing in the next one — see continueIntoNextSource().
     * The sequence queue is owned by the caller and never touched here.
     */
    private void createPreviewPlayer(long startMs, long endMs, int srcIndex) {
        List<Source> list = activeSources();
        if (list.isEmpty()) return;
        if (srcIndex < 0 || srcIndex >= list.size()) srcIndex = 0;
        try {
            final int index = srcIndex;
            previewSourceIndex = index;
            previewPlayer = new MediaPlayer();
            previewPlayer.setDataSource(this, list.get(index).uri);
            previewPlayer.setOnPreparedListener(mp -> {
                try {
                    previewStartMs = startMs;
                    previewEndMs = endMs;
                    mp.seekTo((int) toLocal(index, startMs));
                    mp.start();
                    updatePlayButtons();
                    // exactly one playhead chain: a source switch can recreate
                    // the player while the previous tick already re-posted it
                    mainHandler.removeCallbacks(playheadUpdater);
                    mainHandler.post(playheadUpdater);
                } catch (Exception e) {
                    stopPreview();
                }
            });
            previewPlayer.setOnCompletionListener(mp -> {
                if (mp != previewPlayer) return; // stale callback
                // the range continues in the next song: keep playing there
                if (continueIntoNextSource()) return;
                if (previewQueue != null && previewQueueIndex < previewQueue.size() - 1) {
                    advanceSequencePreview();
                } else {
                    stopPreview();
                }
            });
            previewPlayer.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "Preview failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            stopPreview();
        }
    }

    /**
     * Seek the preview to a GLOBAL time. Inside the current song this is a
     * plain seek; a target in another song needs a new player (a MediaPlayer
     * reads a single file), which is created for the remaining range.
     */
    private void seekPreviewTo(long globalMs) {
        if (previewPlayer == null) return;
        int idx = sourceIndexAt(globalMs);
        if (idx != previewSourceIndex) {
            releasePlayer(); // keep the queue: this is still the same preview
            createPreviewPlayer(globalMs, previewEndMs, idx);
            return;
        }
        try {
            previewPlayer.seekTo((int) toLocal(idx, globalMs));
        } catch (Exception ignored) {
        }
    }

    /**
     * The current song has played to its end but the preview range still has
     * time left: continue from the start of the next song, so a part that was
     * split across the seam (or a play-through to the end of the timeline)
     * sounds continuous. Returns true when playback was carried on.
     */
    private boolean continueIntoNextSource() {
        if (previewPlayer == null) return false;
        List<Source> list = activeSources();
        int next = previewSourceIndex + 1;
        if (next >= list.size()) return false;
        long seam = list.get(next).offsetMs;
        if (previewEndMs <= seam) return false; // the range ends in this song
        createPreviewPlayer(seam, previewEndMs, next);
        return true;
    }

    /** Release the player, keeping the queue (used to switch source mid-play). */
    private void releasePlayer() {
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

    /** Reflect playback state in the play/preview buttons. */
    private void updatePlayButtons() {
        boolean playing = false;
        if (previewPlayer != null) {
            try {
                playing = previewPlayer.isPlaying();
            } catch (Exception ignored) {
            }
        }
        int icon = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        playBtn.setImageResource(icon);
        // the playing Merge row shows ⏸, every other row shows ▶
        boolean mergePreview = previewList == mergeSources;
        for (int i = 0; i < mergeRowButtons.size(); i++) {
            mergeRowButtons.get(i).setImageResource(
                    mergePreview && i == previewSourceIndex
                            ? icon : R.drawable.ic_play);
        }
        seqPreviewBtn.setText(previewQueue != null ? "■ Stop" : "▶ Preview");
    }

    /**
     * Merge rows: the row of the song currently loaded shows a progress bar
     * filled to the position inside THAT song; every other row's bar is
     * empty (but still there — it is the seek control for that song).
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

    /**
     * Tap or drag on a Merge row's progress bar: seek inside that song. The
     * row's own ▶/⏸ state is respected — a paused song stays paused, a
     * playing one keeps playing (and an ordered preview carries on with the
     * following songs).
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
     * Move the merge preview to a point inside one song. The song already
     * loaded keeps its player — so an ordered preview continues into the
     * following songs — any other song starts fresh from that point.
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
                previewPlayer.seekTo((int) local);
            } catch (Exception ignored) {
            }
            updateMergeRowProgress(global);
            updateCurrentTimeText(global);
            return;
        }
        stopPreview();
        previewList = mergeSources;
        // play from the touched point to the end of that song
        createPreviewPlayer(global, s.offsetMs + s.durationMs);
    }

    /** Idle look again (▶ and empty bars) once a preview has ended. */
    private void resetMergeRowPlayback() {
        for (ProgressBar b : mergeRowBars) b.setProgress(0);
        for (ImageButton b : mergeRowButtons) {
            b.setImageResource(R.drawable.ic_play);
        }
    }

    // ------------------------------------------------------------------
    // Save flow
    // ------------------------------------------------------------------

    private void confirmSave() {
        if (jobRunning) return;
        if (currentTab == TAB_MERGE) {
            confirmMergeJob();
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

    /**
     * Order editor for merge: starts from the CURRENT list order (the
     * sequence), up/down to fine-tune. Parts keep their original numbers.
     */
    private void showMergeOrderDialog() {
        final List<EditorTimelineView.SegmentInfo> segs = timeline.getKeptSegments();
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
                        + "   (" + formatTime(seg.startMs) + " → " + formatTime(seg.endMs) + ")");
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

    /**
     * Output base name. Always the FIRST song of the LIST being written —
     * with removal in the Merge tab the opening song can change, so the
     * title the activity was launched with is no longer authoritative.
     */
    private String baseName() {
        return baseNameOf(sources);
    }

    private String baseNameOf(List<Source> list) {
        String n = list.isEmpty() ? songTitle : list.get(0).title;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n;
    }

    /**
     * Merge tab: join every song of the merge list, in list order, into one
     * file. The list is one global segment on ITS OWN axis, so
     * FFmpegAudioEditor copies each song (stream copy) and concatenates the
     * pieces — falling back to the AAC re-encode automatically when a copy
     * is impossible.
     */
    /** Confirm before the export actually starts (same idiom as the other confirms). */
    private void confirmMergeJob() {
        if (mergeSources.size() < 2) {
            runMergeJob(); // that path shows the "add one more" toast
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Merge " + mergeSources.size() + " songs?")
                .setMessage(mergeSources.size() + " songs will be joined into one file "
                        + "(" + formatTime(mergeDurationMs) + " total).\n\n"
                        + "The original files are not modified.")
                .setPositiveButton("Merge", (d, w) -> runMergeJob())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runMergeJob() {
        if (mergeSources.size() < 2) {
            Toast.makeText(this, "Add at least one more song to merge",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        List<FFmpegAudioEditor.Segment> segs = new ArrayList<>();
        segs.add(new FFmpegAudioEditor.Segment(0, mergeDurationMs));

        List<Uri> uris = new ArrayList<>(mergeSources.size());
        for (Source s : mergeSources) uris.add(s.uri);
        long[] boundaries = new long[mergeSources.size() + 1];
        for (int i = 0; i < mergeSources.size(); i++) {
            boundaries[i] = mergeSources.get(i).offsetMs;
        }
        boundaries[mergeSources.size()] = mergeDurationMs;

        // doJob names a MODE_MERGE output "<base>_merged"
        startJob(FFmpegAudioEditor.MODE_MERGE, uris, boundaries, segs,
                baseNameOf(mergeSources));
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
        startJob(mode, sourceUris(), sourceBoundaries(), segments, name);
    }

    /**
     * Run an export over an explicit song list. Split jobs pass the timeline's
     * sources; a Merge job passes the Merge tab's own list, which is a
     * different set of files on a different axis.
     */
    private void startJob(int mode, List<Uri> uris, long[] boundaries,
                          List<FFmpegAudioEditor.Segment> segments, String name) {
        stopPreview();
        jobRunning = true;
        progressTitle.setText("Processing…");
        progressBar.setIndeterminate(true);
        progressBar.setProgress(0);
        progressText.setText("Preparing…");
        progressOverlay.setVisibility(View.VISIBLE);

        editor.runJobFromUri(this, uris, boundaries, mode, segments, name,
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
                        updateSaveButtonState();
                        scanOutputs(outputs);
                        showResultDialog(outputs);
                    }

                    @Override
                    public void onError(String message, boolean cancelled) {
                        jobRunning = false;
                        progressOverlay.setVisibility(View.GONE);
                        updateSaveButtonState();
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
    // Multi-source timeline ("+" appends another song)
    // ------------------------------------------------------------------

    /**
     * Recompute the global start offset of every source and the total
     * timeline length. Sources are laid out back to back, so source i owns
     * the global range [offsetMs, offsetMs + durationMs).
     */
    private void rebuildTimeMap() {
        long off = 0;
        for (Source s : sources) {
            s.offsetMs = off;
            off += s.durationMs;
        }
        durationMs = Math.max(1, off);
    }

    /**
     * The axis the preview is currently running on: the Merge tab's list
     * while one of its previews plays, the timeline's sources otherwise.
     * Every global-time mapping below goes through this, so the two lists
     * can never be mixed up mid-playback.
     */
    private List<Source> activeSources() {
        return previewList != null ? previewList : sources;
    }

    /** Index of the source that owns a GLOBAL time (always 0 when alone). */
    private int sourceIndexAt(long globalMs) {
        List<Source> list = activeSources();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (globalMs >= list.get(i).offsetMs) return i;
        }
        return 0;
    }

    /** GLOBAL time -> time inside that source's own file. */
    private long toLocal(int index, long globalMs) {
        List<Source> list = activeSources();
        if (index < 0 || index >= list.size()) return Math.max(0, globalMs);
        return Math.max(0, globalMs - list.get(index).offsetMs);
    }

    /** Time inside a source's own file -> GLOBAL time. */
    private long toGlobal(int index, long localMs) {
        List<Source> list = activeSources();
        if (index < 0 || index >= list.size()) return localMs;
        return localMs + list.get(index).offsetMs;
    }

    private List<Uri> sourceUris() {
        List<Uri> list = new ArrayList<>(sources.size());
        for (Source s : sources) list.add(s.uri);
        return list;
    }

    /** Global start of every source, plus the total end (length n + 1). */
    private long[] sourceBoundaries() {
        long[] b = new long[sources.size() + 1];
        for (int i = 0; i < sources.size(); i++) b[i] = sources.get(i).offsetMs;
        b[sources.size()] = durationMs;
        return b;
    }

    /** "+": open the app's song list and append whatever the user picks. */
    private void pickSongToAppend() {
        try {
            // the list returns RESULT_OK with the picked track's URI
            songPickerLauncher.launch(new Intent(this, AudioListActivity.class));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open the song list", Toast.LENGTH_SHORT).show();
        }
    }

    /** Merge tab's "+": same picker, in multi-select mode. */
    private void pickSongsToMerge() {
        try {
            Intent i = new Intent(this, AudioListActivity.class);
            i.putExtra(AudioListActivity.EXTRA_MULTI_SELECT, true);
            mergePickerLauncher.launch(i);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open the song list", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Append a song to the END of the edited timeline. It becomes the last
     * source, so every existing operation — split, delete a part, reorder,
     * preview, save — applies to it exactly as it does to the first song.
     */
    private void appendSong(Uri uri) {
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
        updateUndoRedoButtons();

        updateSongNameLabel();
        durationText.setText("Total " + formatTime(durationMs));
        extractWaveforms();

        // zoom: split mode keeps the user's zoom but lets them zoom out to
        // the new full length (the timeline panel is hidden in Merge mode,
        // so its width is 0 there and this is skipped)
        if (timelineScroll.getWidth() > 0) {
            float keepPx = timeline.getPxPerMs();
            timeline.setFitPxPerMs(Math.min(timeline.getFitPxPerMs(),
                    timelineScroll.getWidth() / (float) durationMs));
            timeline.setPxPerMs(keepPx); // re-clamped to the new fit
            updateZoomText();
        }

        renderSegmentList();
        updateRangeText();
        updateCurrentTimeText(timeline.getPlayheadMs());

        String msg = "Added \"" + title + "\"  •  total " + formatTime(durationMs);
        if (hadHistory) msg += "\nUndo history cleared";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /** Human-readable name of a picked track (falls back to the URI tail). */
    private String displayNameOf(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (!TextUtils.isEmpty(n)) return n;
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return TextUtils.isEmpty(last) ? "audio" : last;
    }

    /**
     * Header label: the first song's name, plus how many more were appended
     * (with the total length) so the timeline length is never a surprise.
     */
    private void updateSongNameLabel() {
        if (songNameText == null || sources.isEmpty()) return;
        if (sources.size() == 1) {
            songNameText.setText(sources.get(0).title);
        } else {
            songNameText.setText(sources.get(0).title
                    + "  +" + (sources.size() - 1)
                    + " song" + (sources.size() > 2 ? "s" : "")
                    + "  •  " + formatTime(durationMs));
        }
    }

    // ------------------------------------------------------------------
    // Waveform (one decode per source, merged onto the global axis)
    // ------------------------------------------------------------------

    /** Decode every source that has no waveform yet. */
    private void extractWaveforms() {
        for (final Source s : sources) {
            if (s.waveformRequested) continue;
            s.waveformRequested = true;
            waveformExtractor.extract(this, s.uri, data -> {
                s.waveform = data; // null = decode failed -> placeholder bars
                if (timeline != null) timeline.setWaveform(combineWaveforms());
            });
        }
    }

    /**
     * Lay every source's 600-bucket waveform onto the single global axis the
     * timeline draws over `durationMs`. Bucket density is unchanged from the
     * single-song case (600 buckets per first-song length), so with one
     * source the array is exactly the one that used to be passed straight
     * through. A source that has not decoded yet leaves zeroes — the same
     * placeholder bars the timeline already draws while a chunk is pending.
     */
    private float[] combineWaveforms() {
        int n = sources.size();
        if (n == 0) return null;
        if (n == 1) return sources.get(0).waveform; // single song: unchanged
        long total = Math.max(1, durationMs);
        int len = (int) Math.min(200000L, Math.max(WaveformExtractor.BUCKETS,
                Math.round((double) WaveformExtractor.BUCKETS * total
                        / Math.max(1, sources.get(0).durationMs))));
        float[] out = new float[len];
        float k = len / (float) total; // buckets per ms
        for (Source s : sources) {
            if (s.waveform == null) continue;
            int from = (int) Math.max(0, Math.min(len - 1,
                    Math.round(s.offsetMs * k)));
            int to = (int) Math.max(from + 1, Math.min(len,
                    Math.round((s.offsetMs + s.durationMs) * k)));
            int span = Math.max(1, to - from);
            for (int i = from; i < to; i++) {
                // RMS buckets: the peak of the covered bucket is the honest
                // choice, averaging would flatten transients away
                int si = (int) ((i - from) * (long) s.waveform.length / span);
                if (si >= s.waveform.length) si = s.waveform.length - 1;
                if (si < 0) si = 0;
                out[i] = s.waveform[si];
            }
        }
        return out;
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
