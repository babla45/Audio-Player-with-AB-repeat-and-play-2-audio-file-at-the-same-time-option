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
 * SPLIT mode (the redesign):
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
 *
 * CUT mode keeps the original compact non-scrolling A/B-handle behaviour.
 */
public class EditorTimelineView extends View {

    public static final int MODE_CUT = 0;
    public static final int MODE_SPLIT = 1;

    public interface TimelineListener {
        void onSplitPointsChanged(List<Long> pointsMs);

        void onCutSelectionChanged(long startMs, long endMs);

        /** Playhead moved (tap or playback). */
        void onPlayheadMoved(long playheadMs);

        /** A split boundary was selected (index, or -1 when none). */
        void onSplitSelected(int index);

        /** A segment block was selected (index, or -1 when none). */
        void onSegmentSelected(int index);
    }

    private static final float HANDLE_SLOP_DP = 20f;
    private static final float TAP_SLOP_DP = 8f;
    private static final float MIN_TICK_SPACING_DP = 64f;
    private static final float RULER_HEIGHT_DP = 24f;
    private static final float SEGMENT_GAP_DP = 3f;
    private static final float BAR_WIDTH_DP = 3f;
    private static final float BAR_GAP_DP = 1.5f;
    /** Zoom limits as multiples of the fit-all scale. */
    public static final float MAX_ZOOM_MULTIPLE = 24f;

    private int mode = MODE_CUT;
    private long durationMs = 0;
    private float[] waveform; // nullable
    private long playheadMs = 0;

    private long cutStartMs = 0;
    private long cutEndMs = 0;
    private final List<Long> splitPoints = new ArrayList<>();
    private long minGapMs = FFmpegAudioEditor.MIN_SEGMENT_MS;

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
    private final Paint waveDimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadCapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
    private static final int COLOR_WAVE_DIM = 0x55808080;
    private static final int COLOR_SELECTION = 0x2E6C63FF;
    private static final int COLOR_MARKER = 0xFFFF7043;
    private static final int COLOR_MARKER_SELECTED = 0xFFF4511E;
    private static final int COLOR_PLAYHEAD = 0xFFFF5252;
    private static final int PLACEHOLDER_COLOR = 0x44999999;
    private static final int PLACEHOLDER_SELECTED_COLOR = 0x666C63FF;

    /** drag state: 0 none, 1 cut start, 2 cut end, 10+i split boundary i */
    private int dragTarget = 0;
    private float downX, downY;
    private boolean tapPending;

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
        waveDimPaint.setColor(COLOR_WAVE_DIM);
        selectionPaint.setColor(COLOR_SELECTION);
        markerPaint.setColor(COLOR_MARKER);
        markerPaint.setStrokeWidth(dp(2));
        markerHandlePaint.setColor(COLOR_MARKER);
        playheadPaint.setColor(COLOR_PLAYHEAD);
        playheadPaint.setStrokeWidth(dp(2));
        playheadCapPaint.setColor(COLOR_PLAYHEAD);
        linePaint.setColor(0x1AFFFFFF);
        linePaint.setStrokeWidth(dp(1));
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public void setTimelineListener(TimelineListener l) {
        this.listener = l;
    }

    public void setMode(int mode) {
        this.mode = mode;
        this.selectedSplit = -1;
        this.selectedSegment = -1;
        requestLayout();
        invalidate();
    }

    public void setDuration(long durationMs) {
        this.durationMs = Math.max(1, durationMs);
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
        return (int) (durationMs * pxPerMs);
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
        return playheadMs * pxPerMs;
    }

    // ---- cut mode (unchanged behaviour) ----

    public void setCutSelection(long startMs, long endMs) {
        cutStartMs = clampCut(0, startMs);
        cutEndMs = clampCut(1, endMs);
        if (cutEndMs - cutStartMs < minGapMs) {
            cutEndMs = Math.min(durationMs, cutStartMs + minGapMs);
        }
        invalidate();
    }

    public long getCutStartMs() {
        return cutStartMs;
    }

    public long getCutEndMs() {
        return cutEndMs;
    }

    // ---- split points ----

    public void setSplitPoints(List<Long> points) {
        splitPoints.clear();
        if (points != null) {
            List<Long> copy = new ArrayList<>(points);
            Collections.sort(copy);
            long last = -Long.MAX_VALUE;
            for (Long p : copy) {
                if (p == null) continue;
                if (p > minGapMs && p < durationMs - minGapMs && p - last > minGapMs) {
                    splitPoints.add(p);
                    last = p;
                }
            }
        }
        selectedSplit = -1;
        invalidate();
    }

    public List<Long> getSplitPoints() {
        return new ArrayList<>(splitPoints);
    }

    /** Add a split at the given time if valid; returns the new index or -1. */
    public int addSplitPoint(long t) {
        if (t <= minGapMs || t >= durationMs - minGapMs) return -1;
        for (Long p : splitPoints) {
            if (Math.abs(p - t) < minGapMs) return -1;
        }
        int i = 0;
        while (i < splitPoints.size() && splitPoints.get(i) < t) i++;
        splitPoints.add(i, t);
        selectedSplit = i;
        if (listener != null) {
            listener.onSplitSelected(i);
            notifySplitChanged();
        }
        invalidate();
        return i;
    }

    public void deleteSelectedMarker() {
        if (selectedSplit >= 0 && selectedSplit < splitPoints.size()) {
            splitPoints.remove(selectedSplit);
            selectedSplit = -1;
            if (listener != null) listener.onSplitSelected(-1);
            notifySplitChanged();
            invalidate();
        }
    }

    public int getSelectedSplit() {
        return selectedSplit;
    }

    public int getSelectedSegment() {
        return selectedSegment;
    }

    /** Select a segment block (e.g. from the part list). */
    public void selectSegment(int index) {
        if (index < 0 || index > splitPoints.size()) return;
        selectedSegment = index;
        if (listener != null) listener.onSegmentSelected(index);
        invalidate();
    }

    /** Segments implied by the current state (split mode: N+1 blocks). */
    public List<long[]> getSegments() {
        List<long[]> segs = new ArrayList<>();
        if (mode == MODE_CUT) {
            segs.add(new long[]{cutStartMs, cutEndMs});
            return segs;
        }
        long start = 0;
        for (Long p : splitPoints) {
            segs.add(new long[]{start, p});
            start = p;
        }
        segs.add(new long[]{start, durationMs});
        return segs;
    }

    // ------------------------------------------------------------------
    // Geometry helpers
    // ------------------------------------------------------------------

    private float timeToX(long ms) {
        return ms * pxPerMs;
    }

    private long xToTime(float x) {
        long ms = (long) (x / pxPerMs);
        return Math.max(0, Math.min(durationMs, ms));
    }

    private long clampPlayhead(long ms) {
        return Math.max(0, Math.min(durationMs, ms));
    }

    private float rulerHeight() {
        return mode == MODE_SPLIT ? dp(RULER_HEIGHT_DP) : 0;
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
        // Content width comes from duration x zoom; the hosting
        // HorizontalScrollView gives us UNSPECIFIED/match behaviour.
        int w = (int) Math.max(dp(1), durationMs * pxPerMs);
        setMeasuredDimension(w, h);
    }

    // ------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (durationMs <= 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                tapPending = true;
                dragTarget = hitTest(downX, downY);
                if (dragTarget == 1 || dragTarget == 2 || dragTarget >= 10) {
                    // consuming drags for handles/boundaries: stop the
                    // HorizontalScrollView from stealing the gesture
                    getParent().requestDisallowInterceptTouchEvent(true);
                } else if (mode == MODE_CUT) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (tapPending && (Math.abs(dx) > tapSlopPx() || Math.abs(dy) > tapSlopPx())) {
                    tapPending = false;
                    if (dragTarget == 0) {
                        // let the ScrollView scroll instead
                        getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    }
                }
                if (dragTarget == 1) {
                    cutStartMs = clampCut(0, xToTime(event.getX()));
                    if (listener != null) listener.onCutSelectionChanged(cutStartMs, cutEndMs);
                    invalidate();
                } else if (dragTarget == 2) {
                    cutEndMs = clampCut(1, xToTime(event.getX()));
                    if (listener != null) listener.onCutSelectionChanged(cutStartMs, cutEndMs);
                    invalidate();
                } else if (dragTarget >= 10) {
                    int idx = dragTarget - 10;
                    if (idx < splitPoints.size()) {
                        long t = clampMarker(idx, xToTime(event.getX()));
                        splitPoints.set(idx, t);
                        notifySplitChanged();
                        invalidate();
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                getParent().requestDisallowInterceptTouchEvent(false);
                if (tapPending && dragTarget == 0) {
                    handleTap(downX, downY);
                }
                dragTarget = 0;
                tapPending = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                dragTarget = 0;
                tapPending = false;
                return true;

            default:
                return false;
        }
    }

    /**
     * Tap on empty timeline: select boundary if tapped on one, otherwise
     * move the playhead to the tapped time and select that segment.
     */
    private void handleTap(float x, float y) {
        if (mode == MODE_CUT) {
            // cut mode keeps old behaviour: nearest handle jumped on tap band
            long t = xToTime(x);
            float dxs = Math.abs(x - timeToX(cutStartMs));
            float dxe = Math.abs(x - timeToX(cutEndMs));
            if (dxs < dxe) cutStartMs = clampCut(0, t);
            else cutEndMs = clampCut(1, t);
            if (listener != null) listener.onCutSelectionChanged(cutStartMs, cutEndMs);
            invalidate();
            return;
        }

        // boundary under the tap?
        float slop = handleSlopPx();
        for (int i = 0; i < splitPoints.size(); i++) {
            if (Math.abs(x - timeToX(splitPoints.get(i))) <= slop) {
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
        float h = getHeight();

        if (mode == MODE_CUT) {
            float dxs = Math.abs(x - timeToX(cutStartMs));
            float dxe = Math.abs(x - timeToX(cutEndMs));
            if (y > h * 0.05f && y < h * 0.95f) {
                if (dxs <= dxe) return 1;
                return 2;
            }
            return 0;
        }

        // split mode: boundary handles live in the waveform area
        int best = -1;
        float bestDx = Float.MAX_VALUE;
        for (int i = 0; i < splitPoints.size(); i++) {
            float dx = Math.abs(x - timeToX(splitPoints.get(i)));
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

    private long clampCut(int which, long t) {
        t = Math.max(0, Math.min(durationMs, t));
        if (which == 0) {
            return Math.min(t, cutEndMs - minGapMs);
        }
        return Math.max(t, cutStartMs + minGapMs);
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
        if (mode == MODE_SPLIT) {
            drawSplitTimeline(canvas);
        } else {
            drawCutTimeline(canvas);
        }
    }

    // ---- SPLIT mode: pro scrollable timeline ----

    private void drawSplitTimeline(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float rh = rulerHeight();
        float waveTop = rh + dp(6);
        float waveBottom = h - dp(8);
        float cy = (waveTop + waveBottom) / 2f;
        float maxAmp = (waveBottom - waveTop) / 2f;

        canvas.drawColor(COLOR_BG);

        drawRuler(canvas, rh);

        List<Long> bounds = new ArrayList<>();
        bounds.add(0L);
        bounds.addAll(splitPoints);
        bounds.add(durationMs);

        float gap = dp(SEGMENT_GAP_DP);

        // segment blocks with waveform
        for (int seg = 0; seg < bounds.size() - 1; seg++) {
            long startMs = bounds.get(seg);
            long endMs = bounds.get(seg + 1);
            float xs = timeToX(startMs) + (seg == 0 ? 0 : gap);
            float xe = timeToX(endMs) - (seg == bounds.size() - 2 ? 0 : gap);
            if (xe - xs < dp(2)) continue;
            boolean selected = seg == selectedSegment;

            rect.set(xs, waveTop, xe, waveBottom);
            canvas.drawRoundRect(rect, dp(6), dp(6),
                    selected ? blockSelectedBgPaint : blockBgPaint);

            // clip to the block and draw waveform bars
            int save = canvas.save();
            canvas.clipRect(xs, waveTop, xe, waveBottom);
            drawWaveBars(canvas, xs, xe, cy, maxAmp, selected ? PAINT_MODE_SELECTED : PAINT_MODE_NORMAL);
            canvas.restoreToCount(save);

            // border + number badge
            Paint border = new Paint(blockBorderPaint);
            if (selected) border.setColor(COLOR_BLOCK_SELECTED_BORDER);
            canvas.drawRoundRect(rect, dp(6), dp(6), border);
            drawSegmentBadge(canvas, seg + 1, xs, xe, waveTop, selected);
        }

        // split boundaries (lines + handle chips)
        for (int i = 0; i < splitPoints.size(); i++) {
            float x = timeToX(splitPoints.get(i));
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

    private void drawWaveBars(Canvas canvas, float xs, float xe, float cy,
                              float maxAmp, int paintMode) {
        float barW = dp(BAR_WIDTH_DP);
        float step = barW + dp(BAR_GAP_DP);
        int bars = (int) ((xe - xs) / step);
        if (bars <= 0) return;
        for (int b = 0; b < bars; b++) {
            float x = xs + b * step;
            float amp;
            if (waveform != null && waveform.length > 0) {
                // bucket covered by this bar
                float t0 = (x - 0) / Math.max(1, getWidth()) * waveform.length;
                float t1 = (x + barW) / Math.max(1, getWidth()) * waveform.length;
                int i0 = Math.max(0, (int) t0);
                int i1 = Math.min(waveform.length - 1, Math.max(i0, (int) t1));
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

    /** Adaptive ruler: pick a "nice" second step with >= 64dp spacing. */
    private void drawRuler(Canvas canvas, float rh) {
        canvas.drawRect(0, 0, getWidth(), rh, rulerBgPaint);

        long stepMs = chooseRulerStepMs();
        float labelW = dp(52);
        // ticks
        for (long t = 0; t <= durationMs; t += stepMs) {
            float x = timeToX(t);
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

    // ---- CUT mode: original compact timeline ----

    private void drawCutTimeline(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cy = h / 2f;
        float maxAmp = h * 0.40f;

        float xs = timeToX(cutStartMs);
        float xe = timeToX(cutEndMs);
        rect.set(xs, h * 0.06f, xe, h * 0.94f);
        canvas.drawRoundRect(rect, dp(6), dp(6), selectionPaint);

        int bucketCount = waveform != null ? waveform.length : 64;
        float barW = w / (float) bucketCount;
        for (int i = 0; i < bucketCount; i++) {
            float x = i * barW;
            float amp;
            if (waveform != null) {
                amp = waveform[i] * maxAmp;
            } else {
                amp = (0.15f + 0.25f * (float) Math.abs(Math.sin(i * 0.35))) * maxAmp;
            }
            if (amp < dp(1)) amp = dp(1);
            boolean inSelection = x >= xs && x <= xe;
            Paint p;
            if (waveform != null) {
                p = inSelection ? wavePaint : waveDimPaint;
            } else {
                placeholderPaint.setColor(inSelection
                        ? PLACEHOLDER_SELECTED_COLOR : PLACEHOLDER_COLOR);
                p = placeholderPaint;
            }
            canvas.drawRect(x + barW * 0.15f, cy - amp, x + barW * 0.85f, cy + amp, p);
        }

        canvas.drawLine(0, cy, w, cy, linePaint);

        canvas.drawText(formatRuler(0), dp(2), h - dp(3), textPaint);
        String endLabel = formatRuler(durationMs);
        canvas.drawText(endLabel, w - textPaint.measureText(endLabel) - dp(2), h - dp(3), textPaint);
        String midLabel = formatRuler(durationMs / 2);
        canvas.drawText(midLabel, w / 2f - textPaint.measureText(midLabel) / 2f, h - dp(3), textPaint);

        drawCutHandle(canvas, xs, h, "A");
        drawCutHandle(canvas, xe, h, "B");

        // playhead (if any) in accent
        if (playheadMs >= 0) {
            float px = timeToX(playheadMs);
            canvas.drawLine(px, 0, px, h, playheadPaint);
        }
    }

    private void drawCutHandle(Canvas canvas, float x, float h, String label) {
        markerHandlePaint.setColor(COLOR_MARKER);
        rect.set(x - dp(10), h * 0.0f, x + dp(10), h * 0.13f);
        canvas.drawRoundRect(rect, dp(7), dp(7), markerHandlePaint);
        markerPaint.setColor(COLOR_MARKER);
        canvas.drawLine(x, h * 0.13f, x, h, markerPaint);
        float oldSize = textPaint.getTextSize();
        int oldColor = textPaint.getColor();
        textPaint.setTextSize(sp(10));
        textPaint.setColor(Color.WHITE);
        canvas.drawText(label, x - textPaint.measureText(label) / 2f, h * 0.10f, textPaint);
        textPaint.setTextSize(oldSize);
        textPaint.setColor(oldColor);
    }
}
