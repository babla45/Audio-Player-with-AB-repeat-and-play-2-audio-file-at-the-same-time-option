package com.example.aud_player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Professional timeline editor view (video-editor style, adapted for audio).
 *
 *  - Wide scrollable/zoomable timeline: content width = duration * pxPerMs,
 *    hosted inside a HorizontalScrollView by the activity.
 *  - Time ruler with adaptive tick marks (00:00, 00:05, ...) and labels.
 *  - Waveform divided into visually separated numbered segment blocks.
 *  - Prominent playhead line (with cap) synced to playback.
 *  - Tap the timeline to move the playhead (and select the tapped
 *    segment); tap a split boundary to select it.
 *  - Split boundaries are draggable for precise adjustment; overlapping
 *    / too-close positions are clamped so invalid splits are impossible.
 *  - Zoom (pxPerMs) is controlled by the activity's zoom buttons.
 */
public class EditorTimelineView extends View {

    public interface TimelineListener {
        void onSplitPointsChanged(List<Long> pointsMs);

        /** Playhead moved (tap or playback). */
        void onPlayheadMoved(long playheadMs);

        /** A split boundary was selected (index, or -1 when none). */
        void onSplitSelected(int index);

        /** A segment block was selected (index, or -1 when none). */
        void onSegmentSelected(int index);

        /** A segment block was long-pressed (split mode) — show context menu. */
        void onSegmentLongPressed(int index);

        /** A split boundary drag started moving (push undo state now). */
        void onSplitMoveStarted();
    }

    private static final float HANDLE_SLOP_DP = 20f;
    private static final float TAP_SLOP_DP = 8f;
    private static final float MIN_TICK_SPACING_DP = 64f;
    private static final float RULER_HEIGHT_DP = 24f;
    private static final float SEGMENT_GAP_DP = 3f;
    private static final float BAR_WIDTH_DP = 3f;
    private static final float BAR_GAP_DP = 1.5f;
    /** Waveform vertical fill inside its block: 0.64 → ~18% empty at top
     *  and bottom, so bar peaks are visually distinct from the box edges. */
    private static final float WAVEFORM_FILL = 0.64f;
    /** Zoom limits as multiples of the fit-all scale. */
    public static final float MAX_ZOOM_MULTIPLE = 24f;

    private long durationMs = 0;
    private float[] waveform; // nullable
    private long playheadMs = 0;

    private final List<Long> splitPoints = new ArrayList<>();
    /**
     * Deleted segment ranges in ORIGINAL time [startMs, endMs], sorted,
     * with adjacent/overlapping ranges merged. Kept segments butt together
     * on screen (KineMaster-style) and are excluded from export.
     */
    private final List<long[]> deletedRanges = new ArrayList<>();
    private long minGapMs = FFmpegAudioEditor.MIN_SEGMENT_MS;

    // ---- kept-layout cache (compressed display mapping) ----

    /** One kept segment: original range + original number + bounds index. */
    public static class SegmentInfo {
        public final long startMs;
        public final long endMs;
        /** Original part number (stable across deletions). */
        public final int number;
        /** Index of this segment in the full bounds array. */
        public final int index;

        SegmentInfo(long startMs, long endMs, int number, int index) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.number = number;
            this.index = index;
        }
    }

    private final List<SegmentInfo> keptSegs = new ArrayList<>();
    /** keptSegs.get(i) starts at this DISPLAY time (ms). */
    private final List<Long> keptPrefix = new ArrayList<>();
    private long totalKeptMs = 0;
    private boolean layoutDirty = true;
    /**
     * Play/export order: bounds indices in the order the parts appear in
     * the part list, in Preview and in the export. It is the single source
     * of truth for ORDERING ONLY — the timeline itself always stays
     * chronological. Remapped whenever splits are added or removed;
     * validated (and defensively rebuilt) in rebuildLayout.
     */
    private final List<Integer> sequence = new ArrayList<>();
    /** True when the display mapping is non-linear (segments were deleted). */
    private boolean displayCompressed = false;

    /** Horizontal zoom: pixels per millisecond of audio. */
    private float pxPerMs = 0.05f;
    /** Fit-all scale (content == viewport width). Set by the activity. */
    private float fitPxPerMs = 0.05f;

    private int selectedSplit = -1;
    private int selectedSegment = -1;

    private TimelineListener listener;

    // ------------------------------------------------------------------
    // Paints
    // ------------------------------------------------------------------

    private final Paint rulerBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockSelectedBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadCapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint seamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private static final int COLOR_BG = 0xFF1D1D24;
    private static final int COLOR_RULER_BG = 0xFF26262E;
    private static final int COLOR_TICK = 0x66FFFFFF;
    private static final int COLOR_RULER_TEXT = 0xFFB9B9C6;
    private static final int COLOR_BLOCK_BG = 0xFF26262E;
    private static final int COLOR_BLOCK_SELECTED_BG = 0xFF312C4E;
    private static final int COLOR_BLOCK_BORDER = 0x33FFFFFF;
    private static final int COLOR_BLOCK_SELECTED_BORDER = 0xFF6C63FF;
    private static final int COLOR_WAVE = 0xFF8F89E8;
    private static final int COLOR_WAVE_SELECTED = 0xFFB9B3FF;
    private static final int COLOR_MARKER = 0xFFFF7043;
    private static final int COLOR_MARKER_SELECTED = 0xFFF4511E;
    private static final int COLOR_PLAYHEAD = 0xFFFF5252;
    /** Dashed seam between kept parts across a deleted gap. */
    private static final int COLOR_SEAM = 0x99FF7043;
    private static final int PLACEHOLDER_COLOR = 0x44999999;
    private static final int PLACEHOLDER_SELECTED_COLOR = 0x666C63FF;

    /** drag state: 0 none, 1 cut start, 2 cut end, 10+i split boundary i */
    private int dragTarget = 0;
    private float downX, downY;
    private boolean tapPending;
    /** long-press state (split mode segments) */
    private Runnable longPressRunnable;
    private boolean longPressed;
    /** true once the current boundary drag pushed its undo state */
    private boolean splitMoveNotified;

    public EditorTimelineView(Context context) {
        super(context);
        init();
    }

    public EditorTimelineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EditorTimelineView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        rulerBgPaint.setColor(COLOR_RULER_BG);
        tickPaint.setColor(COLOR_TICK);
        tickPaint.setStrokeWidth(dp(1));
        textPaint.setColor(COLOR_RULER_TEXT);
        textPaint.setTextSize(sp(10));
        blockBgPaint.setColor(COLOR_BLOCK_BG);
        blockSelectedBgPaint.setColor(COLOR_BLOCK_SELECTED_BG);
        blockBorderPaint.setColor(COLOR_BLOCK_BORDER);
        blockBorderPaint.setStyle(Paint.Style.STROKE);
        blockBorderPaint.setStrokeWidth(dp(1));
        wavePaint.setColor(COLOR_WAVE);
        waveSelectedPaint.setColor(COLOR_WAVE_SELECTED);
        markerPaint.setColor(COLOR_MARKER);
        markerPaint.setStrokeWidth(dp(2));
        markerHandlePaint.setColor(COLOR_MARKER);
        playheadPaint.setColor(COLOR_PLAYHEAD);
        playheadPaint.setStrokeWidth(dp(2));
        playheadCapPaint.setColor(COLOR_PLAYHEAD);
        linePaint.setColor(0x1AFFFFFF);
        linePaint.setStrokeWidth(dp(1));
        seamPaint.setColor(COLOR_SEAM);
        seamPaint.setStyle(Paint.Style.STROKE);
        seamPaint.setStrokeWidth(dp(2));
        seamPaint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{dp(4), dp(4)}, 0));
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public void setTimelineListener(TimelineListener l) {
        this.listener = l;
    }

    public void setDuration(long durationMs) {
        this.durationMs = Math.max(1, durationMs);
        layoutDirty = true;
        requestLayout();
        invalidate();
    }

    public void setWaveform(float[] data) {
        this.waveform = data;
        invalidate();
    }

    public void setMinGapMs(long minGapMs) {
        this.minGapMs = Math.max(100, minGapMs);
    }

    // ---- zoom ----

    /** Fit-all scale: content width == viewport width. */
    public void setFitPxPerMs(float fit) {
        this.fitPxPerMs = Math.max(0.0001f, fit);
    }

    public float getFitPxPerMs() {
        return fitPxPerMs;
    }

    public void setPxPerMs(float value) {
        float min = fitPxPerMs;
        float max = fitPxPerMs * MAX_ZOOM_MULTIPLE;
        this.pxPerMs = Math.max(min, Math.min(max, value));
        requestLayout();
        invalidate();
    }

    public float getPxPerMs() {
        return pxPerMs;
    }

    public float getZoomLevel() {
        return pxPerMs / Math.max(0.0001f, fitPxPerMs);
    }

    /** Content width in px at the current zoom (for scroll math). */
    public int getContentWidth() {
        return (int) (getEditedDurationMs() * pxPerMs);
    }

    // ---- playhead ----

    public void setPlayheadMs(long ms) {
        this.playheadMs = clampPlayhead(ms);
        invalidate();
    }

    public long getPlayheadMs() {
        return playheadMs;
    }

    /** x position of the playhead in content coordinates. */
    public float getPlayheadX() {
        return timeToX(playheadMs);
    }

    // ---- split points ----

    /**
     * Drop every edit (splits, deletions, order, selection). Used when the
     * song list changes underneath the timeline: the axis shifts, so any
     * existing split would point at the wrong audio.
     */
    public void clearEdits() {
        splitPoints.clear();
        deletedRanges.clear();
        sequence.clear();
        selectedSplit = -1;
        selectedSegment = -1;
        layoutDirty = true;
        if (listener != null) {
            listener.onSplitSelected(-1);
            notifySplitChanged();
        }
        invalidate();
    }

    public void setSplitPoints(List<Long> points) {
        splitPoints.clear();
        if (points != null) {
            List<Long> copy = new ArrayList<>(points);
            Collections.sort(copy);
            // An explicit flag instead of a -Long.MAX_VALUE sentinel: that
            // sentinel overflows in (p - last) for every positive p, which
            // rejected the FIRST point — and since `last` then never moved,
            // every following point was rejected too, so restoring a
            // snapshot silently emptied the timeline (one undo wiped the
            // whole session). The gap rule itself is unchanged.
            long last = 0;
            boolean hasLast = false;
            for (Long p : copy) {
                if (p == null) continue;
                if (p > minGapMs && p < durationMs - minGapMs
                        && (!hasLast || p - last > minGapMs)) {
                    splitPoints.add(p);
                    last = p;
                    hasLast = true;
                }
            }
        }
        selectedSplit = -1;
        layoutDirty = true;
        invalidate();
    }

    public List<Long> getSplitPoints() {
        return new ArrayList<>(splitPoints);
    }

    /** Add a split at the given time if valid; returns the new index or -1. */
    public int addSplitPoint(long t) {
        // Distances are measured through KEPT audio only: a deleted
        // (invisible) seam between t and a boundary contributes nothing,
        // so it can no longer trigger a false "too close" rejection.
        if (keptMsBetween(0, t) < minGapMs
                || keptMsBetween(t, durationMs) < minGapMs) return -1;
        if (isInDeletedRange(t)) return -1;
        for (Long p : splitPoints) {
            if (keptMsBetween(Math.min(p, t), Math.max(p, t)) < minGapMs) return -1;
        }
        int i = 0;
        while (i < splitPoints.size() && splitPoints.get(i) < t) i++;
        splitPoints.add(i, t);
        remapSequenceSplitAdded(i);
        selectedSplit = i;
        layoutDirty = true;
        if (listener != null) {
            listener.onSplitSelected(i);
            notifySplitChanged();
        }
        invalidate();
        return i;
    }

    public void deleteSelectedMarker() {
        if (selectedSplit >= 0) {
            deleteSplit(selectedSplit);
        }
    }

    /**
     * Remove the split point at the given index. The surrounding segments
     * merge automatically (segments are always derived from the split
     * list, so no orphaned state is possible). Selection indices are
     * adjusted, not orphaned.
     */
    public void deleteSplit(int index) {
        if (index < 0 || index >= splitPoints.size()) return;
        splitPoints.remove(index);
        remapSequenceSplitRemoved(index);
        if (selectedSplit == index) {
            selectedSplit = -1;
        } else if (selectedSplit > index) {
            selectedSplit--;
        }
        layoutDirty = true;
        if (listener != null) listener.onSplitSelected(selectedSplit);
        notifySplitChanged();
        invalidate();
    }

    // ---- deleted segments ----

    /** Current deleted ranges (copies; for undo snapshots). */
    public List<long[]> getDeletedRanges() {
        List<long[]> out = new ArrayList<>();
        for (long[] r : deletedRanges) out.add(new long[]{r[0], r[1]});
        return out;
    }

    /**
     * Restore a set of deleted ranges (undo/redo). Ranges are copied,
     * sorted, adjacent-merged, and any split points that end up strictly
     * inside a range are dropped so no orphaned boundaries remain.
     */
    public void setDeletedRanges(List<long[]> ranges) {
        deletedRanges.clear();
        if (ranges != null) {
            List<long[]> copy = new ArrayList<>();
            for (long[] r : ranges) {
                if (r != null && r.length == 2 && r[1] > r[0]) copy.add(new long[]{r[0], r[1]});
            }
            Collections.sort(copy, (a, b) -> Long.compare(a[0], b[0]));
            for (long[] r : copy) {
                if (!deletedRanges.isEmpty() && r[0] <= deletedRanges.get(deletedRanges.size() - 1)[1]) {
                    long[] last = deletedRanges.get(deletedRanges.size() - 1);
                    last[1] = Math.max(last[1], r[1]); // merge adjacent/overlap
                } else {
                    deletedRanges.add(r);
                }
            }
        }
        removeSplitsInsideDeletedRanges();
        layoutDirty = true;
        requestLayout();
        invalidate();
    }

    /** Drop split points strictly inside a deleted range (meaningless). */
    private void removeSplitsInsideDeletedRanges() {
        boolean changed = false;
        // descending: removing index i leaves all lower indices valid,
        // and each remap sees the bounds array as it is at that moment
        for (int i = splitPoints.size() - 1; i >= 0; i--) {
            if (isInDeletedRange(splitPoints.get(i))) {
                splitPoints.remove(i);
                remapSequenceSplitRemoved(i);
                changed = true;
            }
        }
        if (changed) selectedSplit = -1;
    }

    /**
     * True if split i borders a deleted segment on either side. Such
     * boundaries are structural (edges of a deletion) and are frozen:
     * not draggable and not selectable — the seam between two kept parts
     * across a deleted gap is drawn as a fixed divider instead.
     */
    private boolean isSplitFrozen(int i) {
        List<long[]> bounds = computeBounds();
        // split i sits between bounds[i] and bounds[i+1] in this indexing
        long leftStart = bounds.get(i)[0];
        long leftEnd = splitPoints.get(i);
        long rightStart = splitPoints.get(i);
        long rightEnd = bounds.get(i + 2)[0];
        return isDeletedRange(leftStart, leftEnd) || isDeletedRange(rightStart, rightEnd);
    }

    /**
     * Delete the ENTIRE segment at the given bounds index: its time range
     * is removed from the edited result (kept parts butt together) and
     * excluded from export. Returns false when it is the only kept part.
     */
    public boolean deleteSegment(int boundsIndex) {
        if (layoutDirty) rebuildLayout();
        if (keptSegs.size() <= 1) return false;
        List<long[]> bounds = computeBounds();
        if (boundsIndex < 0 || boundsIndex >= bounds.size() - 1) return false;
        long s = bounds.get(boundsIndex)[0];
        long e = bounds.get(boundsIndex + 1)[0];
        if (isDeletedRange(s, e)) return false; // already deleted

        List<long[]> updated = getDeletedRanges();
        updated.add(new long[]{s, e});
        sequence.remove(Integer.valueOf(boundsIndex)); // drop from the play order
        setDeletedRanges(updated);

        selectedSplit = -1;
        selectedSegment = -1;
        if (listener != null) {
            listener.onSplitSelected(-1);
            notifySplitChanged();
        }
        invalidate();
        return true;
    }

    /**
     * Kept segments with their ORIGINAL part numbers (stable), in the
     * current PLAY order (the sequence) — this is what the part list and
     * the merge dialog show. The timeline itself stays chronological.
     */
    public List<SegmentInfo> getKeptSegments() {
        if (layoutDirty) rebuildLayout();
        List<SegmentInfo> out = new ArrayList<>();
        for (int idx : sequence) {
            for (SegmentInfo seg : keptSegs) {
                if (seg.index == idx) {
                    out.add(seg);
                    break;
                }
            }
        }
        return out;
    }

    // ---- segment order (drag-and-drop reorder) ----

    /** Current segment order (bounds indices; deep copy for snapshots). */
    public List<Integer> getSequence() {
        return new ArrayList<>(sequence);
    }

    /** Restore a segment order (undo/redo). Validated at layout rebuild. */
    public void setSequence(List<Integer> order) {
        sequence.clear();
        if (order != null) {
            for (Integer v : order) {
                if (v != null) sequence.add(v);
            }
        }
        layoutDirty = true;
        requestLayout();
        invalidate();
    }

    /**
     * Move the segment at sequence position fromPos so it becomes the item
     * at insertion index toPos (drag-and-drop reorder). Returns false for
     * out-of-range positions.
     */
    public boolean reorderSegment(int fromPos, int toPos) {
        if (layoutDirty) rebuildLayout();
        if (fromPos < 0 || fromPos >= sequence.size()) return false;
        toPos = Math.max(0, Math.min(sequence.size(), toPos));
        int v = sequence.remove(fromPos);
        // removing the item shifts the insertion semantics by one
        int ins = toPos > fromPos ? toPos - 1 : toPos;
        sequence.add(ins, v);
        layoutDirty = true;
        requestLayout();
        invalidate();
        if (listener != null) notifySplitChanged(); // re-renders the part list
        return true;
    }

    /**
     * A split was inserted at splitPoints index splitIdx: a new segment
     * (bounds index splitIdx+1) came into existence. Shift sequence
     * entries up and slot the new part right after its chronological
     * left neighbour.
     */
    private void remapSequenceSplitAdded(int splitIdx) {
        for (int i = 0; i < sequence.size(); i++) {
            int v = sequence.get(i);
            if (v > splitIdx) sequence.set(i, v + 1);
        }
        int at = sequence.indexOf(Integer.valueOf(splitIdx));
        if (at >= 0) sequence.add(at + 1, splitIdx + 1);
        else sequence.add(splitIdx + 1);
    }

    /**
     * A split was removed from splitPoints index splitIdx: segments
     * splitIdx and splitIdx+1 merged into segment splitIdx, and bounds
     * indices above shift down by one.
     */
    private void remapSequenceSplitRemoved(int splitIdx) {
        sequence.remove(Integer.valueOf(splitIdx + 1));
        for (int i = 0; i < sequence.size(); i++) {
            int v = sequence.get(i);
            if (v > splitIdx + 1) sequence.set(i, v - 1);
        }
    }

    public int getSelectedSplit() {
        return selectedSplit;
    }

    public int getSelectedSegment() {
        return selectedSegment;
    }

    /** Restore a snapshot's selection after setSplitPoints (undo/redo). */
    public void restoreSelection(int splitIdx, int segmentIdx) {
        this.selectedSplit = (splitIdx >= 0 && splitIdx < splitPoints.size())
                ? splitIdx : -1;
        this.selectedSegment = (segmentIdx >= 0 && segmentIdx <= splitPoints.size())
                ? segmentIdx : -1;
        invalidate();
    }

    /** Select a segment block (e.g. from the part list). */
    public void selectSegment(int index) {
        if (index < 0 || index > splitPoints.size()) return;
        selectedSegment = index;
        if (listener != null) listener.onSegmentSelected(index);
        invalidate();
    }

    /**
     * Segments implied by the current state: the KEPT segments only, in the
     * current PLAY order (the sequence) — deleted parts are excluded, and
     * the order here drives the part list, Preview and the export.
     */
    public List<long[]> getSegments() {
        List<long[]> segs = new ArrayList<>();
        if (layoutDirty) rebuildLayout();
        for (int idx : sequence) {
            for (SegmentInfo seg : keptSegs) {
                if (seg.index == idx) {
                    segs.add(new long[]{seg.startMs, seg.endMs});
                    break;
                }
            }
        }
        return segs;
    }

    // ------------------------------------------------------------------
    // Geometry helpers (compressed display mapping in SPLIT mode)
    // ------------------------------------------------------------------

    /**
     * Rebuild the kept-segment layout cache. Kept segments are ALWAYS laid
     * out chronologically — the timeline never rearranges when the play
     * order changes; deleted segments are simply skipped and the remaining
     * ones butt together. keptPrefix[i] is the DISPLAY time at which kept
     * segment i starts. The sequence (play/export order) is validated here
     * — it must reference each kept segment exactly once — and defensively
     * rebuilt chronologically if any state ever left it inconsistent.
     * Cheap (segment counts are small); called lazily from onDraw/touch.
     */
    private void rebuildLayout() {
        layoutDirty = false;
        keptSegs.clear();
        keptPrefix.clear();
        totalKeptMs = 0;
        List<long[]> bounds = computeBounds();
        int n = bounds.size() - 1;
        boolean[] kept = new boolean[n];
        int keptCount = 0;
        for (int i = 0; i < n; i++) {
            kept[i] = !isDeletedRange(bounds.get(i)[0], bounds.get(i + 1)[0]);
            if (kept[i]) keptCount++;
        }
        boolean valid = sequence.size() == keptCount;
        if (valid) {
            boolean[] seen = new boolean[n];
            for (int v : sequence) {
                if (v < 0 || v >= n || seen[v] || !kept[v]) {
                    valid = false;
                    break;
                }
                seen[v] = true;
            }
        }
        if (!valid) {
            sequence.clear();
            for (int i = 0; i < n; i++) {
                if (kept[i]) sequence.add(i);
            }
        }
        for (int i = 0; i < n; i++) {
            if (!kept[i]) continue;
            long s = bounds.get(i)[0];
            long e = bounds.get(i + 1)[0];
            keptSegs.add(new SegmentInfo(s, e, i + 1, i));
            keptPrefix.add(totalKeptMs);
            totalKeptMs += Math.max(0, e - s);
        }
        if (keptSegs.isEmpty() && durationMs > 0) {
            // defensive: never let the layout go fully empty
            sequence.clear();
            sequence.add(0);
            keptSegs.add(new SegmentInfo(0, durationMs, 1, 0));
            keptPrefix.add(0L);
            totalKeptMs = durationMs;
        }
        // non-linear display mapping only when parts were deleted — a
        // reorder changes the PLAY order, not the timeline layout
        displayCompressed = keptCount != n;
    }

    /** Full bounds array: [0, splitPoints..., durationMs] as ranges. */
    private List<long[]> computeBounds() {
        List<long[]> bounds = new ArrayList<>();
        bounds.add(new long[]{0});
        for (Long p : splitPoints) bounds.add(new long[]{p});
        bounds.add(new long[]{durationMs});
        return bounds;
    }

    /** True if the segment [s,e] exactly matches a deleted range. */
    private boolean isDeletedRange(long s, long e) {
        for (long[] r : deletedRanges) {
            if (r[0] == s && r[1] == e) return true;
        }
        return false;
    }

    /** True if ms lies strictly inside any deleted range. */
    private boolean isInDeletedRange(long ms) {
        for (long[] r : deletedRanges) {
            if (ms > r[0] && ms < r[1]) return true;
        }
        return false;
    }

    /**
     * Kept (non-deleted) milliseconds between a and b (a <= b). Used for
     * split proximity: only audio that will actually remain in the edited
     * result counts towards the minimum-gap distance.
     */
    private long keptMsBetween(long a, long b) {
        if (b <= a) return 0;
        long kept = b - a;
        for (long[] r : deletedRanges) {
            long s = Math.max(a, r[0]);
            long e = Math.min(b, r[1]);
            if (e > s) kept -= e - s;
        }
        return Math.max(0, kept);
    }

    /** Kept duration shown on the timeline (edited result length). */
    public long getEditedDurationMs() {
        if (layoutDirty) rebuildLayout();
        if (!displayCompressed) return durationMs;
        return totalKeptMs;
    }

    /**
     * Display x where kept block i starts. Blocks are separated by
     * SEGMENT_GAP_DP of visual space; the time mapping MUST account for
     * those gaps or taps land later in time than where they were placed —
     * the error grows with every preceding block, which made mid-part
     * splits fail with "too close to another split".
     */
    private float displayStartX(int i) {
        return keptPrefix.get(i) * pxPerMs + (i == 0 ? 0 : dp(SEGMENT_GAP_DP));
    }

    /**
     * Display x for an original time. Kept segments are laid out in sequence
     * order with the visual gaps included; a time inside a deleted gap maps
     * to the seam before the next kept part chronologically.
     */
    private float timeToX(long ms) {
        if (layoutDirty) rebuildLayout();
        for (int i = 0; i < keptSegs.size(); i++) {
            SegmentInfo seg = keptSegs.get(i);
            if (ms >= seg.startMs && ms <= seg.endMs) {
                return displayStartX(i) + (ms - seg.startMs) * pxPerMs;
            }
        }
        // not inside any kept part: next kept start chronologically
        long bestStart = Long.MAX_VALUE;
        float bestX = getEditedDurationMs() * pxPerMs
                + Math.max(0, keptSegs.size() - 1) * dp(SEGMENT_GAP_DP);
        for (int i = 0; i < keptSegs.size(); i++) {
            SegmentInfo seg = keptSegs.get(i);
            if (seg.startMs > ms && seg.startMs < bestStart) {
                bestStart = seg.startMs;
                bestX = displayStartX(i);
            }
        }
        return bestX;
    }

    /**
     * Original time for a display x. The result always lies inside a kept
     * segment (a tap on a seam selects the next kept part). Gap-aware so a
     * tap maps to exactly the time under the finger.
     */
    private long xToTime(float x) {
        if (layoutDirty) rebuildLayout();
        for (int i = 0; i < keptSegs.size(); i++) {
            SegmentInfo seg = keptSegs.get(i);
            float xs = displayStartX(i);
            float xe = xs + (seg.endMs - seg.startMs) * pxPerMs;
            if (x < xe || i == keptSegs.size() - 1) {
                long inSeg = Math.max(0, Math.min(seg.endMs - seg.startMs,
                        (long) ((x - xs) / pxPerMs)));
                return seg.startMs + inSeg;
            }
        }
        return durationMs;
    }

    /**
     * Display (edited-result) time for an original time — used for the
     * current-time readout so it matches the compressed ruler. The visual
     * gaps between blocks are excluded (they are not audible time).
     */
    public long toDisplayMs(long origMs) {
        if (layoutDirty) rebuildLayout();
        if (!displayCompressed) {
            return Math.max(0, Math.min(durationMs, origMs));
        }
        for (int i = 0; i < keptSegs.size(); i++) {
            SegmentInfo seg = keptSegs.get(i);
            if (origMs >= seg.startMs && origMs <= seg.endMs) {
                return keptPrefix.get(i) + (origMs - seg.startMs);
            }
        }
        return totalKeptMs;
    }

    /**
     * Playback helper: ms if it lies inside kept audio; otherwise the
     * start of the next kept segment after the deleted gap; -1 when past
     * the last kept audio (stop playback). Works in ORIGINAL time, so it
     * is independent of the display order.
     */
    public long skipDeletedForward(long ms) {
        if (deletedRanges.isEmpty()) return Math.min(ms, durationMs);
        if (layoutDirty) rebuildLayout();
        for (SegmentInfo seg : keptSegs) {
            if (ms >= seg.startMs && ms < seg.endMs) return ms;
        }
        long best = Long.MAX_VALUE;
        for (SegmentInfo seg : keptSegs) {
            if (seg.startMs > ms && seg.startMs < best) best = seg.startMs;
        }
        return best == Long.MAX_VALUE ? -1 : best;
    }

    /**
     * Display x of split i — its actual chronological position on the
     * timeline (the display never rearranges with the play order).
     */
    private float splitDisplayX(int i) {
        if (layoutDirty) rebuildLayout();
        long t = splitPoints.get(i);
        return timeToX(t);
    }

    private long clampPlayhead(long ms) {
        return Math.max(0, Math.min(durationMs, ms));
    }

    private float rulerHeight() {
        return dp(RULER_HEIGHT_DP);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    private float handleSlopPx() {
        return dp(HANDLE_SLOP_DP);
    }

    private float tapSlopPx() {
        return dp(TAP_SLOP_DP);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (h == 0) h = getSuggestedMinimumHeight();
        // Content width comes from the EDITED duration x zoom (deleted
        // segments are compressed out) plus the visual gaps between blocks,
        // so the last block is never clipped; the hosting
        // HorizontalScrollView gives us UNSPECIFIED/match behaviour.
        long edited = getEditedDurationMs();
        float gaps = Math.max(0, keptSegs.size() - 1) * dp(SEGMENT_GAP_DP);
        int w = (int) Math.max(dp(1), edited * pxPerMs + gaps);
        setMeasuredDimension(w, h);
    }

    // ------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (durationMs <= 0) return false;
        if (layoutDirty) rebuildLayout();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                tapPending = true;
                longPressed = false;
                splitMoveNotified = false;
                dragTarget = hitTest(downX, downY);
                if (dragTarget >= 10) {
                    // consuming drags for boundaries: stop the
                    // HorizontalScrollView from stealing the gesture
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragTarget == 0) {
                    // long-press on a segment opens the delete-split menu;
                    // it must NOT move the playhead or start playback
                    longPressRunnable = () -> {
                        longPressed = true;
                        performHapticFeedback(
                                android.view.HapticFeedbackConstants.LONG_PRESS);
                        int seg = segmentIndexAt(xToTime(downX));
                        selectedSegment = seg;
                        if (listener != null) listener.onSegmentSelected(seg);
                        if (listener != null) listener.onSegmentLongPressed(seg);
                        invalidate();
                    };
                    postDelayed(longPressRunnable,
                            android.view.ViewConfiguration.getLongPressTimeout());
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (tapPending && (Math.abs(dx) > tapSlopPx() || Math.abs(dy) > tapSlopPx())) {
                    tapPending = false;
                    cancelPendingLongPress();
                    if (dragTarget == 0) {
                        // let the ScrollView scroll instead
                        getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    }
                }
                if (dragTarget >= 10) {
                    // boundary drags engage only after the tap slop is
                    // exceeded (prevents accidental moves on taps and
                    // junk undo snapshots from sub-slop jitter)
                    if (tapPending) return true;
                    int idx = dragTarget - 10;
                    if (idx < splitPoints.size()) {
                        if (!splitMoveNotified) {
                            // first actual movement of this drag — snapshot
                            // the pre-drag state for undo before mutating
                            splitMoveNotified = true;
                            if (listener != null) listener.onSplitMoveStarted();
                        }
                        long t = clampMarker(idx, xToTime(event.getX()));
                        splitPoints.set(idx, t);
                        layoutDirty = true;
                        notifySplitChanged();
                        invalidate();
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                cancelPendingLongPress();
                getParent().requestDisallowInterceptTouchEvent(false);
                if (tapPending && !longPressed) {
                    if (dragTarget == 0) {
                        handleTap(downX, downY);
                    } else if (dragTarget >= 10) {
                        // tap (no drag) on a boundary: select that split
                        int idx = dragTarget - 10;
                        if (idx < splitPoints.size()) {
                            selectedSplit = idx;
                            if (listener != null) listener.onSplitSelected(idx);
                            invalidate();
                        }
                    }
                }
                dragTarget = 0;
                tapPending = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                cancelPendingLongPress();
                getParent().requestDisallowInterceptTouchEvent(false);
                dragTarget = 0;
                tapPending = false;
                return true;

            default:
                return false;
        }
    }

    private void cancelPendingLongPress() {
        if (longPressRunnable != null) {
            removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    /**
     * Tap on empty timeline: select boundary if tapped on one, otherwise
     * move the playhead to the tapped time and select that segment.
     */
    private void handleTap(float x, float y) {
        // boundary under the tap? (frozen deletion edges are skipped)
        float slop = handleSlopPx();
        for (int i = 0; i < splitPoints.size(); i++) {
            if (isSplitFrozen(i)) continue;
            if (Math.abs(x - splitDisplayX(i)) <= slop) {
                selectedSplit = i;
                if (listener != null) listener.onSplitSelected(i);
                invalidate();
                return;
            }
        }

        // otherwise: move playhead + select the tapped segment
        selectedSplit = -1;
        if (listener != null) listener.onSplitSelected(-1);
        long t = xToTime(x);
        playheadMs = clampPlayhead(t);
        if (listener != null) listener.onPlayheadMoved(playheadMs);
        selectedSegment = segmentIndexAt(playheadMs);
        if (listener != null) listener.onSegmentSelected(selectedSegment);
        invalidate();
    }

    private int hitTest(float x, float y) {
        float slop = handleSlopPx();

        // boundary handles live in the waveform area; frozen boundaries
        // (deletion edges) are not interactive
        int best = -1;
        float bestDx = Float.MAX_VALUE;
        for (int i = 0; i < splitPoints.size(); i++) {
            if (isSplitFrozen(i)) continue;
            float dx = Math.abs(x - splitDisplayX(i));
            if (dx <= slop && dx < bestDx) {
                bestDx = dx;
                best = i;
            }
        }
        return best >= 0 ? 10 + best : 0;
    }

    private int segmentIndexAt(long ms) {
        int idx = 0;
        for (Long p : splitPoints) {
            if (ms >= p) idx++;
            else break;
        }
        return idx;
    }

    private long clampMarker(int idx, long t) {
        t = Math.max(minGapMs, Math.min(durationMs - minGapMs, t));
        if (idx > 0) {
            Long prev = splitPoints.get(idx - 1);
            t = Math.max(t, prev + minGapMs);
        }
        if (idx < splitPoints.size() - 1) {
            Long next = splitPoints.get(idx + 1);
            t = Math.min(t, next - minGapMs);
        }
        return t;
    }

    private void notifySplitChanged() {
        if (listener != null) listener.onSplitPointsChanged(new ArrayList<>(splitPoints));
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (durationMs <= 0) return;
        if (layoutDirty) rebuildLayout();
        drawSplitTimeline(canvas);
    }

    // ---- pro scrollable timeline ----

    private void drawSplitTimeline(Canvas canvas) {
        float h = getHeight();
        float rh = rulerHeight();
        float waveTop = rh + dp(6);
        float waveBottom = h - dp(8);
        float cy = (waveTop + waveBottom) / 2f;
        // amplitude fills only WAVEFORM_FILL of the block height — the
        // remaining headroom keeps peaks clear of the box edges and makes
        // loud/quiet differences easier to read
        float maxAmp = (waveBottom - waveTop) / 2f * WAVEFORM_FILL;

        canvas.drawColor(COLOR_BG);

        drawRuler(canvas, rh);

        float gap = dp(SEGMENT_GAP_DP);

        // kept segment blocks (deleted parts are compressed out; numbers
        // stay the ORIGINAL part numbers, e.g. Part 1 | Part 3)
        for (int i = 0; i < keptSegs.size(); i++) {
            SegmentInfo seg = keptSegs.get(i);
            boolean first = i == 0;
            boolean last = i == keptSegs.size() - 1;
            float xs = keptPrefix.get(i) * pxPerMs + (first ? 0 : gap);
            float xe = (keptPrefix.get(i) + (seg.endMs - seg.startMs)) * pxPerMs
                    - (last ? 0 : gap);
            if (xe - xs < dp(2)) continue;
            boolean selected = seg.index == selectedSegment;

            rect.set(xs, waveTop, xe, waveBottom);
            canvas.drawRoundRect(rect, dp(6), dp(6),
                    selected ? blockSelectedBgPaint : blockBgPaint);

            // clip to the block and draw waveform bars (bucket lookup via
            // ORIGINAL time — the waveform spans the source file)
            int save = canvas.save();
            canvas.clipRect(xs, waveTop, xe, waveBottom);
            drawWaveBars(canvas, xs, xe, seg.startMs, cy, maxAmp,
                    selected ? PAINT_MODE_SELECTED : PAINT_MODE_NORMAL);
            canvas.restoreToCount(save);

            // border + number badge
            Paint border = new Paint(blockBorderPaint);
            if (selected) border.setColor(COLOR_BLOCK_SELECTED_BORDER);
            canvas.drawRoundRect(rect, dp(6), dp(6), border);
            drawSegmentBadge(canvas, seg.number, xs, xe, waveTop, selected);
        }

        // seams: where two kept parts meet without being chronologically
        // contiguous — across a deleted gap OR at a reorder junction
        for (int i = 0; i + 1 < keptSegs.size(); i++) {
            SegmentInfo a = keptSegs.get(i);
            SegmentInfo b = keptSegs.get(i + 1);
            if (a.endMs == b.startMs) continue; // naturally contiguous
            float x = keptPrefix.get(i + 1) * pxPerMs;
            seamPaint.setColor(COLOR_SEAM);
            canvas.drawLine(x, rh, x, h - dp(2), seamPaint);
        }

        // split boundaries (lines + handle chips); frozen deletion edges
        // are represented by the seams above and are not interactive
        for (int i = 0; i < splitPoints.size(); i++) {
            if (isSplitFrozen(i)) continue;
            float x = splitDisplayX(i);
            boolean sel = i == selectedSplit;
            markerPaint.setColor(sel ? COLOR_MARKER_SELECTED : COLOR_MARKER);
            canvas.drawLine(x, rh, x, h - dp(2), markerPaint);

            markerHandlePaint.setColor(sel ? COLOR_MARKER_SELECTED : COLOR_MARKER);
            rect.set(x - dp(10), rh + dp(2), x + dp(10), rh + dp(2) + dp(26));
            canvas.drawRoundRect(rect, dp(7), dp(7), markerHandlePaint);

            float oldSize = textPaint.getTextSize();
            int oldColor = textPaint.getColor();
            textPaint.setTextSize(sp(11));
            textPaint.setColor(Color.WHITE);
            textPaint.setFakeBoldText(true);
            String n = String.valueOf(i + 1);
            canvas.drawText(n, x - textPaint.measureText(n) / 2f,
                    rh + dp(2) + dp(18), textPaint);
            textPaint.setTextSize(oldSize);
            textPaint.setColor(oldColor);
            textPaint.setFakeBoldText(false);
        }

        // playhead (prominent, full height with cap)
        float px = timeToX(playheadMs);
        canvas.drawLine(px, 0, px, h, playheadPaint);
        playheadCapPaint.setPathEffect(null);
        rect.set(px - dp(7), 0, px + dp(7), dp(12));
        canvas.drawRoundRect(rect, dp(3), dp(3), playheadCapPaint);
    }

    private static final int PAINT_MODE_NORMAL = 0;
    private static final int PAINT_MODE_SELECTED = 1;

    private void drawWaveBars(Canvas canvas, float xs, float xe, long origStartMs,
                              float cy, float maxAmp, int paintMode) {
        float barW = dp(BAR_WIDTH_DP);
        float step = barW + dp(BAR_GAP_DP);
        int bars = (int) ((xe - xs) / step);
        if (bars <= 0) return;
        for (int b = 0; b < bars; b++) {
            float x = xs + b * step;
            float amp;
            if (waveform != null && waveform.length > 0) {
                // ORIGINAL time covered by this bar (display time advances
                // 1:1 with original time inside a kept segment)
                float t0 = origStartMs + (x - xs) / pxPerMs;
                float t1 = origStartMs + (x + barW - xs) / pxPerMs;
                int i0 = (int) Math.max(0, Math.min(waveform.length - 1,
                        t0 * waveform.length / Math.max(1, durationMs)));
                int i1 = (int) Math.max(0, Math.min(waveform.length - 1,
                        t1 * waveform.length / Math.max(1, durationMs)));
                float v = 0;
                for (int i = i0; i <= i1; i++) v = Math.max(v, waveform[i]);
                amp = v * maxAmp;
            } else {
                int pseudo = (int) (x / step);
                amp = (0.15f + 0.25f * (float) Math.abs(Math.sin(pseudo * 0.35))) * maxAmp;
            }
            if (amp < dp(1)) amp = dp(1);
            Paint p;
            if (waveform != null) {
                p = paintMode == PAINT_MODE_SELECTED ? waveSelectedPaint : wavePaint;
            } else {
                placeholderPaint.setColor(paintMode == PAINT_MODE_SELECTED
                        ? PLACEHOLDER_SELECTED_COLOR : PLACEHOLDER_COLOR);
                p = placeholderPaint;
            }
            canvas.drawRect(x, cy - amp, x + barW, cy + amp, p);
        }
    }

    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawSegmentBadge(Canvas canvas, int number, float xs, float xe,
                                  float top, boolean selected) {
        String label = String.valueOf(number);
        float oldSize = textPaint.getTextSize();
        int oldColor = textPaint.getColor();
        textPaint.setTextSize(sp(11));
        textPaint.setFakeBoldText(true);
        float tw = textPaint.measureText(label);
        float cx = (xs + xe) / 2f;
        float baseline = top + dp(16);
        // subtle pill behind the number for readability
        rect.set(cx - tw / 2 - dp(7), top + dp(3), cx + tw / 2 + dp(7), top + dp(19));
        badgePaint.setColor(selected ? 0xFF6C63FF : 0x33FFFFFF);
        canvas.drawRoundRect(rect, dp(8), dp(8), badgePaint);
        textPaint.setColor(selected ? Color.WHITE : 0xFFD9D9E3);
        canvas.drawText(label, cx - tw / 2, baseline, textPaint);
        textPaint.setTextSize(oldSize);
        textPaint.setColor(oldColor);
        textPaint.setFakeBoldText(false);
    }

    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Adaptive ruler: pick a "nice" second step with >= 64dp spacing.
     * Ticks are in DISPLAY (edited-result) time, like pro editors. */
    private void drawRuler(Canvas canvas, float rh) {
        canvas.drawRect(0, 0, getWidth(), rh, rulerBgPaint);

        long totalMs = getEditedDurationMs();
        long stepMs = chooseRulerStepMs();
        float labelW = dp(52);
        // ticks
        for (long t = 0; t <= totalMs; t += stepMs) {
            float x = t * pxPerMs;
            if (x < -labelW || x > getWidth() + labelW) continue;
            canvas.drawLine(x, rh - dp(6), x, rh, tickPaint);
            String label = formatRuler(t);
            float tw = textPaint.measureText(label);
            canvas.drawText(label, Math.max(dp(2), x - tw / 2f), rh - dp(9), textPaint);
        }
        // baseline between ruler and waveform area
        canvas.drawLine(0, rh, getWidth(), rh, linePaint);
    }

    private long chooseRulerStepMs() {
        long[] stepsSec = {1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600};
        float minPx = dp(MIN_TICK_SPACING_DP);
        for (long s : stepsSec) {
            if (s * 1000f * pxPerMs >= minPx) return s * 1000;
        }
        return 3600_000L;
    }

    private static String formatRuler(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }
}
