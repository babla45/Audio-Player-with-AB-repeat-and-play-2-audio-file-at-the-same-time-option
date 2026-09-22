package com.example.aud_player;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FFmpeg-backed split / merge engine for the Video Editor.
 *
 * <p><b>What the requirements turned into</b>
 * <ul>
 * <li><b>No re-encoding, ever.</b> Every ffmpeg run is a plain stream copy
 *     ({@code -c copy}); no encoder is named anywhere, so each part holds the
 *     source's own frames and its size is its share of the original file plus
 *     a few kB of container headers.</li>
 * <li><b>Fast.</b> A copy runs at disk speed; the merge is one concat-demuxer
 *     pass; the only extra work is copying a content:// Uri into the app cache
 *     (ffmpeg needs a real file path) and, for sources whose container cannot
 *     be seeked exactly, one re-wrap pass into MP4.</li>
 * <li><b>Parts that play, and only parts that play.</b> A cut may only start
 *     on a keyframe of the file being cut - that is a property of writing the
 *     compressed stream without decoding it. Cut points are therefore moved
 *     onto the nearest keyframe (the timeline shows them as ticks) instead of
 *     silently dragging the previous GOP into a part, which is what used to
 *     make a re-joined video repeat video/audio at every seam.</li>
 * <li><b>Merging two different videos is either correct or refused.</b> A
 *     copy cannot change codec or frame size, so incompatible sources are
 *     rejected with a message before anything is written.</li>
 * </ul>
 *
 * <p><b>The three things that make the output correct</b>
 * <ol>
 * <li>{@code -video_track_timescale 90000} on every piece AND on the merge.
 *     Without it two sources with different container timescales (an MP4 at
 *     1/90000 next to one at 1/30000, or a Matroska file at 1/1000) produce a
 *     merged file whose duration is wrong - 10 s came out as 4.98 s. The pin
 *     makes every piece and every merge land in the same time base.</li>
 * <li>The concat list's {@code duration} line uses the <b>video track's</b>
 *     duration, never the container's. The container reports the audio stream,
 *     and AAC priming/padding makes that up to 24 ms longer per part; over a
 *     many-part merge the parts then drift apart and the file grows (20 x 1 s
 *     became 20.35 s, and the audio slid out of sync).</li>
 * <li>A source whose container is not seekable by ffmpeg - Matroska is the
 *     common one - is re-wrapped into MP4 once ({@code -c copy} again) before
 *     cutting. Cutting a Matroska file directly makes ffmpeg seek a whole GOP
 *     early (a cut asked for 4.0 s produced 5.0 s of video). Re-wrapping moves
 *     the container's index, not the media, so nothing is lost.</li>
 * </ol>
 *
 * <p>Everything runs through {@link FFmpegKit} with {@code -c copy}; the whole
 * job is one background thread, reports coarse progress, and can be cancelled.
 */
public class FFmpegVideoEditor {

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public interface EditListener {
        /** -1 while preparing, then 0..100. */
        void onProgress(int percent);

        /**
         * Called when the job needs the user's attention about a compromise it
         * had to make (a cut moved to the nearest keyframe, a source that could
         * not be re-wrapped, keyframes that could not be read). Always followed
         * by {@link #onSuccess}.
         */
        default void onWarnings(List<String> warnings) {
        }

        /**
         * Per-file progress in a multi-file job (Compress): how far file
         * number {@code index} of the list has got, 0..100. {@code index} is
         * the position in the list handed to
         * {@link #compressFromUri(Context, List, CompressOptions, EditListener)}.
         * The default does nothing, so split/merge callers are unaffected.
         */
        default void onFileProgress(int index, int percent) {
        }

        void onSuccess(List<File> outputs);

        void onError(String message, boolean cancelled);
    }

    /** A range of the edited timeline, in ms. */
    public static class Segment {
        public final long startMs;
        public final long endMs;

        public Segment(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }

        public long durationMs() {
            return Math.max(0, endMs - startMs);
        }
    }

    /** Write one file per segment (the split tab). */
    public static final int MODE_SEPARATE = 1;
    /** Write one file holding every segment in order (the merge tab). */
    public static final int MODE_MERGE = 2;

    /**
     * Subfolders inside <shared storage>/aud_player/ for each kind of job.
     * Keeps Merge / Split / Compress results separate on disk without touching
     * filenames, naming conventions, the fallback chain, or the scan-after-write
     * flow.
     */
    public static final String SUBFOLDER_MERGE = "merged";
    public static final String SUBFOLDER_SPLIT = "split";
    public static final String SUBFOLDER_COMPRESS = "compressed";
    /** Kept as the default when no subfolder is supplied (backward compatible). */
    private static final String SUBFOLDER_DEFAULT = "edited";

    /** Any part shorter than this is refused by the timeline anyway. */
    public static final long MIN_SEGMENT_MS = 1000L;

    /**
     * What the Compress tab lets the user choose. The values a fresh instance
     * carries are exactly the ones the original Termux compressor used
     * (1280x720, 30 fps, libx264 CRF 28 veryfast), so leaving the chips alone
     * reproduces that script's output.
     */
    public static final class CompressOptions {
        /** Target height in pixels; the width follows the source aspect ratio. 0 = keep the source's. */
        public int maxHeight = 720;
        /** Output frame rate. 0 = keep the source's. */
        public int fps = 30;
        /** x264 quality: lower is better and bigger. */
        public int crf = 28;
        /** x264 speed preset. */
        public String preset = "veryfast";

        public CompressOptions() {
        }

        public CompressOptions(int maxHeight, int fps, int crf, String preset) {
            this.maxHeight = maxHeight;
            this.fps = fps;
            this.crf = crf;
            this.preset = preset;
        }

        public CompressOptions copy() {
            return new CompressOptions(maxHeight, fps, crf, preset);
        }
    }

    /** Audio bitrate for the AAC fallback (the encoder the compressor defaulted to). */
    private static final String FALLBACK_AUDIO_BITRATE = "128k";

    private static final String TAG = "FFmpegVideoEditor";

    /** Containers whose seek index ffmpeg reads exactly. */
    private static final Set<String> MOV_FAMILY = new HashSet<>(Arrays.asList(
            "mp4", "m4v", "mov", "3gp", "3g2", "3gpp"));

    /** Cut points are moved onto the nearest keyframe of the file being cut. */
    private static final long KEYFRAME_TOLERANCE_MS = 1500L;
    /** Extra free space demanded on top of the sources' size. */
    private static final long FREE_SPACE_MARGIN = 32L * 1024 * 1024;

    // ------------------------------------------------------------------
    // Job
    // ------------------------------------------------------------------

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private volatile FFmpegSession activeSession;
    private final int[] step = new int[]{0, 1};
    private EditListener listener;
    /** Flips on success, so half-finished outputs are removed on any other exit. */
    private final AtomicBoolean jobSucceeded = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
        cancelActive();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** Call from the activity's onDestroy(): cancels and frees the thread. */
    public void release() {
        cancel();
        executor.shutdownNow();
    }

    private void cancelActive() {
        FFmpegSession session = activeSession;
        if (session != null) {
            try {
                FFmpegKit.cancel(session.getSessionId());
            } catch (Throwable t) {
                Log.w(TAG, "cancel failed", t);
            }
        }
    }

    /**
     * Runs a split (N outputs) or merge (1 output) job.
     *
     * @param sources      the videos, in timeline order
     * @param mediaInfos   one probe result per source (null entries are fine -
     *                     the engine probes what it needs itself)
     * @param boundariesMs where each source starts in the edited timeline,
     *                     length sources.size() + 1
     * @param mode         {@link #MODE_SEPARATE} or {@link #MODE_MERGE}
     * @param segments     the ranges to write, in play order
     * @param baseName     output file name stem
     */
    public void runJobFromUri(final Context context, final List<Uri> sources,
                              final List<MediaInfo> mediaInfos, final long[] boundariesMs,
                              final int mode, final List<Segment> segments,
                              final String baseName, final EditListener editListener) {
        if (context == null || sources == null || sources.isEmpty()
                || segments == null || segments.isEmpty()) {
            postError(editListener, "Nothing to process", false);
            return;
        }
        cancelled.set(false);
        finished.set(false);
        jobSucceeded.set(false);
        listener = editListener;
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        executor.execute(() -> {
            try {
                doJob(app, sources, mediaInfos, boundariesMs, mode, segments, baseName);
            } catch (Throwable t) {
                Log.e(TAG, "job failed", t);
                postError(listener, "The edit failed: " + t.getMessage(), false);
            }
        });
    }

    /**
     * Re-encodes each video to a smaller file — the Compress tab.
     *
     * <p>Unlike the split/merge engine, which never decodes a frame, this one
     * is an encoder job: every source is decoded, scaled and written with
     * libx264. The videos are processed one at a time, in list order, because
     * encoding is CPU-bound and running several at once heats the device and
     * usually ends up slower overall.
     *
     * @param sources  the videos to compress, in list order
     * @param options  the chips the user picked (null = the script's defaults)
     * @param listener progress/result callbacks; {@link EditListener#onFileProgress}
     *                 reports the per-file bar, {@code onProgress} the whole job
     */
    public void compressFromUri(final Context context, final List<Uri> sources,
                                final CompressOptions options, final EditListener listener) {
        if (context == null || sources == null || sources.isEmpty()) {
            postError(listener, "Nothing to compress", false);
            return;
        }
        cancelled.set(false);
        finished.set(false);
        jobSucceeded.set(false);
        this.listener = listener;
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        final CompressOptions opts = options != null ? options.copy() : new CompressOptions();
        executor.execute(() -> {
            try {
                doCompress(app, sources, opts);
            } catch (Throwable t) {
                if (cancelled.get()) {
                    postError(this.listener, "Cancelled", true);
                } else {
                    Log.e(TAG, "compress failed", t);
                    postError(this.listener, t instanceof IOException ? t.getMessage()
                            : "The compression failed: " + t.getMessage(), false);
                }
            }
        });
    }

    private void doJob(Context context, List<Uri> sources, List<MediaInfo> mediaInfos,
                       long[] boundariesMs, int mode, List<Segment> segments, String baseName) {
        postProgress(-1);
        File workDir = null;
        List<File> outputs = new ArrayList<>();
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        try {
            File cache = context.getCacheDir();
            if (cache == null) throw new IOException("No cache folder");
            workDir = new File(cache, "video_edit_" + System.nanoTime());
            if (!workDir.mkdirs() && !workDir.isDirectory()) {
                throw new IOException("Cannot create a working folder");
            }

            // ---- 1. every source becomes a local file ------------------
            List<Source> items = new ArrayList<>(sources.size());
            for (int i = 0; i < sources.size(); i++) {
                checkCancelled();
                MediaInfo info = (mediaInfos != null && i < mediaInfos.size())
                        ? mediaInfos.get(i) : null;
                Source src = new Source(i, sources.get(i), info);
                src.local = copySourceToLocal(context, src.uri, workDir);
                src.workingFile = src.local;
                src.workingExt = extOf(src.local);
                items.add(src);
            }

            // ---- 2. the sources' sizes decide whether this fits --------
            checkFreeSpace(context, items);

            // ---- 3. plan (nothing is written yet) ---------------------
            List<Output> plan = plan(items, boundariesMs, mode, segments, baseName);
            if (plan.isEmpty()) {
                postError(listener, "Nothing to process", false);
                return;
            }

            // ---- 4. refuse to merge what cannot be merged --------------
            String clash = firstClash(items, plan);
            if (clash != null) {
                postError(listener, clash, false);
                return;
            }

            // ---- 5. make every source exactly cuttable -----------------
            for (Source src : items) {
                checkCancelled();
                prepareSource(src, workDir, warnings);
            }
            int steps = 0;
            for (Output out : plan) steps += Math.max(1, out.pieces.size());
            step[0] = 0;
            step[1] = Math.max(1, steps);
            postProgress(0);

            // ---- 6. build the outputs --------------------------------
            for (Output out : plan) {
                checkCancelled();
                File result = buildOutput(context, items, out, workDir, warnings);
                if (result == null) {
                    if (cancelled.get()) {
                        postError(listener, "Cancelled", true);
                    } else {
                        postError(listener, "The edit could not be finished. Your originals are untouched.",
                                false);
                    }
                    return;
                }
                outputs.add(result);
            }

            // ---- 7. hand them over -----------------------------------
            for (File f : outputs) {
                try {
                    MediaScannerConnection.scanFile(context, new String[]{f.getAbsolutePath()}, null, null);
                } catch (Throwable ignored) {
                }
            }
            postProgress(100);
            jobSucceeded.set(true);        // the finished files belong to the user now
            if (!warnings.isEmpty()) {
                try {
                    listener.onWarnings(new ArrayList<>(warnings));
                } catch (Throwable t) {
                    Log.w(TAG, "warning callback failed", t);
                }
            }
            if (finished.compareAndSet(false, true)) {
                listener.onSuccess(outputs);
            }
        } catch (Throwable t) {
            if (cancelled.get()) {
                postError(listener, "Cancelled", true);
            } else {
                Log.e(TAG, "job failed", t);
                postError(listener, t instanceof IOException ? t.getMessage()
                        : "The edit failed: " + t.getMessage(), false);
            }
        } finally {
            if (!jobSucceeded.get()) {
                for (File f : outputs) safeDelete(f);
            }
            if (workDir != null) deleteTree(workDir);
        }
    }

    // ------------------------------------------------------------------
    // Sources
    // ------------------------------------------------------------------

    private static final class Source {
        final int index;
        final Uri uri;
        final MediaInfo info;
        File local;               // byte copy of the Uri: ffmpeg needs a path
        File workingFile;         // the file that is really cut
        String workingExt;
        boolean rewrapped;        // workingFile is a fresh MP4 of this source
        long durationMs;
        long[] keyframesMs;
        boolean keyframesKnown;
        /** The planned cut points this source contributes (start/end pairs). */
        final List<long[]> requestedBounds = new ArrayList<>();
        /** Cut point -> the keyframe it really cuts on. */
        java.util.Map<Long, Long> aligned = Collections.emptyMap();

        Source(int index, Uri uri, MediaInfo info) {
            this.index = index;
            this.uri = uri;
            this.info = info;
        }

        String label() {
            if (info != null && info.label != null) return info.label;
            String last = uri != null ? uri.getLastPathSegment() : null;
            return last != null ? last : "video";
        }
    }

    private static final class Piece {
        final int sourceIndex;
        long startMs;             // source-local
        long endMs;

        Piece(int sourceIndex, long startMs, long endMs) {
            this.sourceIndex = sourceIndex;
            this.startMs = Math.max(0, startMs);
            this.endMs = Math.max(this.startMs, endMs);
        }

        long durationMs() {
            return endMs - startMs;
        }
    }

    private static final class Output {
        final String name;
        /** Subfolder under aud_player/ where this output should be written. */
        final String subfolder;
        final List<Piece> pieces = new ArrayList<>();

        Output(String name, String subfolder) {
            this.name = name;
            this.subfolder = subfolder;
        }
    }

    /**
     * Copy of a content:// Uri into the cache. A provider that hands out a real
     * file descriptor is moved with the kernel's sendfile (FileChannel
     * transferTo), which is several times faster than a read/write loop on a
     * multi-GB video; anything else falls back to a 1 MB stream copy.
     *
     * <p>A {@code file://} source is used in place - no copy at all.
     */
    private static File copySourceToLocal(Context context, Uri uri, File workDir) throws IOException {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            File direct = new File(uri.getPath() == null ? "" : uri.getPath());
            if (direct.isFile() && direct.canRead()) return direct;
        }
        String ext = guessExtension(context, uri);
        File target = File.createTempFile("vsrc_", "." + ext, workDir);
        long expected = expectedSize(context, uri);

        try (FileOutputStream fos = new FileOutputStream(target)) {
            FileChannel out = fos.getChannel();
            long copied = 0;
            ParcelFileDescriptor pfd = null;
            try {
                pfd = context.getContentResolver().openFileDescriptor(uri, "r");
                if (pfd != null) {
                    try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                        FileChannel ch = in.getChannel();
                        long size = ch.size();
                        long at = 0;
                        while (at < size) {
                            long n = ch.transferTo(at, size - at, out);
                            if (n <= 0) break;
                            at += n;
                        }
                        copied = at;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "kernel copy unavailable, falling back to a stream copy", t);
            } finally {
                if (pfd != null) {
                    try {
                        pfd.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            if (copied <= 0) {
                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Cannot read " + uri);
                    byte[] buf = new byte[1024 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(ByteBuffer.wrap(buf, 0, n));
                }
            }
        }
        if (target.length() == 0) {
            throw new IOException("The video could not be read (" + uri + ")");
        }
        long got = target.length();
        if (expected > 0 && Math.abs(got - expected) > Math.max(1024L, expected / 1000L)) {
            Log.w(TAG, "source copy is " + got + " bytes, the provider reports " + expected);
        }
        return target;
    }

    /**
     * Re-wraps a source whose container ffmpeg cannot seek exactly (Matroska,
     * WebM, ...) into MP4, then probes the keyframes of the file that will
     * really be cut. Nothing is re-encoded: this is another stream copy.
     */
    private void prepareSource(Source src, File workDir, List<String> warnings) throws IOException {
        src.workingFile = src.local;
        src.workingExt = extOf(src.local);
        src.rewrapped = false;

        if (!MOV_FAMILY.contains(src.workingExt)) {
            File wrapped = new File(workDir, "wrap_" + src.index + ".mp4");
            String[] args = {
                    "-i", src.local.getAbsolutePath(),
                    "-map", "0:v:0?", "-map", "0:a?",
                    "-c", "copy",
                    "-avoid_negative_ts", "make_zero",
                    "-video_track_timescale", "90000",
                    "-y", wrapped.getAbsolutePath()
            };
            if (exec(args, 0, null) && wrapped.isFile() && wrapped.length() > 0) {
                src.workingFile = wrapped;
                src.workingExt = "mp4";
                src.rewrapped = true;
            } else {
                safeDelete(wrapped);
                addWarning(warnings, "\"" + src.label() + "\" stays in its own format, so a cut in it "
                        + "may start at the keyframe before the cut line (that container cannot be "
                        + "seeked exactly). The parts are still lossless copies.");
            }
        }

        src.durationMs = videoDurationMs(src.workingFile, 0);

        long[] passed = src.info != null ? src.info.keyframesMs : null;
        if (src.rewrapped || passed == null || passed.length == 0) {
            src.keyframesMs = probeKeyframes(src.workingFile);
        } else {
            src.keyframesMs = passed;
        }
        src.keyframesKnown = src.keyframesMs != null && src.keyframesMs.length > 0;
        if (!src.keyframesKnown) {
            addWarning(warnings, "The keyframes of \"" + src.label() + "\" could not be read, so a part "
                    + "may repeat some video from before its cut line. The parts are still lossless.");
        }

        // Cut points that are not on a keyframe are moved onto one. The same
        // mapping is used for the piece that ENDS at a cut point and the piece
        // that STARTS there, so nothing is duplicated and nothing is lost.
        if (src.keyframesKnown) alignBoundaries(src, warnings);
    }

    /**
     * Moves this source's requested cut points onto keyframes - one mapping
     * per cut point, shared by the two pieces that meet there.
     */
    private void alignBoundaries(Source src, List<String> warnings) {
        long dur = src.durationMs > 0 ? src.durationMs : Long.MAX_VALUE;
        java.util.Map<Long, Long> map = new java.util.HashMap<>();
        long worst = 0;
        for (long[] pair : src.requestedBounds) {
            for (long b : new long[]{pair[0], pair[1]}) {
                if (b <= 0 || b >= dur || map.containsKey(b)) continue;
                long k = nearestKeyframe(src.keyframesMs, b, dur);
                if (k > 0 && k != b) {
                    map.put(b, k);
                    worst = Math.max(worst, Math.abs(k - b));
                }
            }
        }
        // a move that would leave a piece with nothing to play is dropped,
        // even if that means the cut keeps its original time
        boolean dropped = true;
        while (dropped) {
            dropped = false;
            for (long[] pair : src.requestedBounds) {
                long s = map.containsKey(pair[0]) ? map.get(pair[0]) : pair[0];
                long e = map.containsKey(pair[1]) ? map.get(pair[1]) : pair[1];
                if (e - s < MIN_SEGMENT_MS) {
                    if (map.remove(pair[1]) != null || map.remove(pair[0]) != null) dropped = true;
                }
            }
        }
        src.aligned = map;
        // below a couple of frames the move is invisible (it is how the
        // container's own grid differs from the one the timeline drew), so
        // only a noticeable move is reported
        if (!map.isEmpty() && worst > 100) {
            addWarning(warnings, "A cut point in \"" + src.label() + "\" moved "
                    + (worst < 1000 ? worst + " ms" : Math.round(worst / 1000.0) + " s")
                    + " onto the nearest keyframe - cutting anywhere else would make the parts "
                    + "repeat video that the neighbouring part also has.");
        }
    }

    /** Applies this source's cut-point mapping to a planned time. */
    private static long aligned(Source src, long t) {
        Long moved = src.aligned.get(t);
        return moved != null ? moved : t;
    }

    /**
     * The keyframe a cut at {@code t} should use: the nearest one strictly
     * inside the file (a cut at 0 or at the very end is not a cut), ties
     * resolved to the later keyframe. Returns -1 when there is no usable
     * keyframe, and the caller then keeps the requested time.
     */
    static long nearestKeyframe(long[] kfs, long t, long sourceDurationMs) {
        if (kfs == null || kfs.length == 0) return -1;
        long best = -1;
        long bestGap = Long.MAX_VALUE;
        for (int i = 0; i < kfs.length; i++) {
            long k = kfs[i];
            if (k <= 0) continue;
            if (sourceDurationMs > 0 && k >= sourceDurationMs) break;
            long gap = Math.abs(k - t);
            if (gap < bestGap || (gap == bestGap && k > best)) {
                best = k;
                bestGap = gap;
            }
        }
        return best;
    }

    /** First index whose keyframe is >= t. */
    private static int lowerBound(long[] a, long t) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < t) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    // ------------------------------------------------------------------
    // Planning
    // ------------------------------------------------------------------

    /**
     * Splits every segment at the source boundaries, then groups the pieces
     * per output. Nothing is written here, so a bad request is rejected before
     * any ffmpeg work.
     */
    private List<Output> plan(List<Source> items, long[] boundariesMs, int mode,
                              List<Segment> segments, String baseName) {
        List<List<Piece>> perSegment = new ArrayList<>();
        for (Segment seg : segments) {
            long start = Math.max(0, seg.startMs);
            long end = Math.max(start, seg.endMs);
            if (end - start < 1) continue;
            List<Piece> pieces = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                long from = boundary(boundariesMs, i, items);
                long to = boundary(boundariesMs, i + 1, items);
                long s = Math.max(start, from);
                long e = Math.min(end, to);
                if (e > s) pieces.add(new Piece(i, s - from, e - from));
            }
            if (!pieces.isEmpty()) perSegment.add(pieces);
        }
        for (List<Piece> pieces : perSegment) {
            for (Piece p : pieces) {
                items.get(p.sourceIndex).requestedBounds.add(new long[]{p.startMs, p.endMs});
            }
        }

        String stem = (baseName == null || baseName.trim().isEmpty()) ? "video" : baseName.trim();
        List<Output> plan = new ArrayList<>();
        if (mode == MODE_MERGE) {
            if (!perSegment.isEmpty()) {
                Output out = new Output(stem + "_merged", SUBFOLDER_MERGE);
                for (List<Piece> pieces : perSegment) out.pieces.addAll(pieces);
                plan.add(out);
            }
        } else {
            for (int i = 0; i < perSegment.size(); i++) {
                Output out = new Output(stem + "_part" + (i + 1), SUBFOLDER_SPLIT);
                out.pieces.addAll(perSegment.get(i));
                plan.add(out);
            }
        }
        return plan;
    }

    private static long boundary(long[] boundaries, int index, List<Source> items) {
        if (boundaries != null && index < boundaries.length) return boundaries[index];
        if (index < items.size()) return items.get(index).durationMs;
        long total = 0;
        for (Source s : items) total += s.durationMs;
        return total;
    }

    /**
     * The copy cannot change codec or picture size, so two sources that differ
     * in those cannot share one file. Checked over the plan, before any cut, so
     * the user gets a sentence instead of a file that plays wrong.
     */
    private String firstClash(List<Source> items, List<Output> plan) {
        for (Output out : plan) {
            for (int i = 0; i < out.pieces.size(); i++) {
                for (int j = i + 1; j < out.pieces.size(); j++) {
                    MediaInfo a = items.get(out.pieces.get(i).sourceIndex).info;
                    MediaInfo b = items.get(out.pieces.get(j).sourceIndex).info;
                    if (a == null || b == null) continue;
                    String reason = a.incompatibleWith(b);
                    if (reason != null) {
                        return "These videos cannot be merged without re-encoding: " + reason
                                + ". Save them separately, or pick videos that were recorded "
                                + "the same way.";
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Building one output
    // ------------------------------------------------------------------

    private File buildOutput(Context context, List<Source> items, Output out, File workDir,
                             List<String> warnings) throws IOException {
        String ext = items.get(out.pieces.get(0).sourceIndex).workingExt;

        // (a) a single part: no temporary file, nothing to merge
        if (out.pieces.size() == 1) {
            Piece p = out.pieces.get(0);
            Source src = items.get(p.sourceIndex);
            long startMs = aligned(src, p.startMs);
            long endMs = Math.max(startMs, aligned(src, p.endMs));
            if (startMs <= 0 && src.durationMs > 0 && endMs >= src.durationMs) {
                // the whole source, so the answer is the source itself: the
                // fastest and the most faithful output there is
                File target = uniqueOutput(context, out.subfolder, out.name, extOf(src.local));
                copyFile(src.local, target);
                step[0]++;
                return target;
            }
            File target = uniqueOutput(context, out.subfolder, out.name, ext);
            if (cutPiece(src, target, startMs, Math.max(1, endMs - startMs), warnings)) {
                step[0]++;
                return target;
            }
            safeDelete(target);
            return null;
        }

        // (b) several parts: cut each one, then merge them in one copy pass
        List<File> parts = new ArrayList<>();
        for (Piece p : out.pieces) {
            checkCancelled();
            Source src = items.get(p.sourceIndex);
            long startMs = aligned(src, p.startMs);
            long endMs = Math.max(startMs, aligned(src, p.endMs));
            boolean whole = src.durationMs > 0 && startMs <= 0 && endMs >= src.durationMs;
            if (whole && src.rewrapped) {
                // the re-wrap pass already pinned this file's timescale and
                // holds the whole source, so it can go into the merge as it is
                parts.add(src.workingFile);
                step[0]++;
                continue;
            }
            File part = new File(workDir, "part_" + src.index + "_" + startMs + "." + src.workingExt);
            safeDelete(part);
            if (!cutPiece(src, part, startMs, Math.max(1, endMs - startMs), warnings)) {
                safeDelete(part);
                return null;
            }
            step[0]++;
            parts.add(part);
        }
        if (parts.isEmpty()) return null;
        if (parts.size() == 1) {
            File target = uniqueOutput(context, out.subfolder, out.name, ext);
            copyFile(parts.get(0), target);
            return target;
        }
        String outExt = MOV_FAMILY.contains(ext) ? ext : "mkv";
        File target = uniqueOutput(context, out.subfolder, out.name, outExt);
        if (!concat(workDir, parts, target, warnings)) {
            safeDelete(target);
            return null;
        }
        return target;
    }

    /**
     * One piece, written with {@code -c copy}. The retry maps every stream of
     * the input, which is what saves files carrying a timecode or metadata
     * track the MP4 muxer refuses to take by name.
     */
    private boolean cutPiece(Source src, File target, long startMs, long durationMs,
                             List<String> warnings) {
        String[] base = cutArgs(src.workingFile, target, startMs, durationMs, false);
        if (exec(base, durationMs, p -> postStepProgress(p))) return true;
        safeDelete(target);
        String[] relaxed = withMapAll(base);
        if (exec(relaxed, durationMs, null)) return true;
        safeDelete(target);
        return false;
    }

    static String[] cutArgs(File in, File out, long startMs, long durationMs, boolean faststart) {
        List<String> a = new ArrayList<>();
        a.add("-ss");
        a.add(msToTime(startMs));
        a.add("-i");
        a.add(in.getAbsolutePath());
        a.add("-t");
        a.add(msToTime(durationMs));
        a.addAll(maps());
        a.add("-c");
        a.add("copy");
        a.add("-avoid_negative_ts");
        a.add("make_zero");
        a.add("-video_track_timescale");
        a.add("90000");
        if (faststart) {
            a.add("-movflags");
            a.add("+faststart");
        }
        a.add("-y");
        a.add(out.getAbsolutePath());
        return a.toArray(new String[0]);
    }

    /**
     * Merges the parts with the concat demuxer. Each line carries the part's
     * video duration, which is what the demuxer advances the time line by: the
     * container duration is the audio stream's and is up to 24 ms longer per
     * part (AAC priming), which over a many-part merge slides the audio out of
     * sync and grows the file.
     */
    private boolean concat(File workDir, List<File> parts, File target, List<String> warnings)
            throws IOException {
        File list = new File(workDir, "concat_" + target.getName() + ".txt");
        StringBuilder sb = new StringBuilder();
        for (File f : parts) {
            if (!f.isFile() || f.length() == 0) return false;
            sb.append("file '").append(f.getAbsolutePath().replace("'", "'\\''")).append("'\n");
            sb.append("duration ").append(String.format(Locale.US, "%.3f",
                    pieceOffsetMs(f) / 1000.0)).append('\n');
        }
        writeText(list, sb.toString());

        String[] args = concatArgs(list, target, true);
        boolean ok = exec(args, 0, null);
        if (!ok) {
            safeDelete(target);
            ok = exec(withMapAll(args), 0, null);
        }
        safeDelete(list);
        step[0]++;
        return ok && target.isFile() && target.length() > 0;
    }

    static String[] concatArgs(File list, File out, boolean faststart) {
        List<String> a = new ArrayList<>();
        a.add("-f");
        a.add("concat");
        a.add("-safe");
        a.add("0");
        a.add("-fflags");
        a.add("+genpts");
        a.add("-i");
        a.add(list.getAbsolutePath());
        a.addAll(maps());
        a.add("-c");
        a.add("copy");
        a.add("-avoid_negative_ts");
        a.add("make_zero");
        a.add("-video_track_timescale");
        a.add("90000");
        if (faststart) {
            a.add("-movflags");
            a.add("+faststart");
        }
        a.add("-y");
        a.add(out.getAbsolutePath());
        return a.toArray(new String[0]);
    }

    private static List<String> maps() {
        return new ArrayList<>(Arrays.asList("-map", "0:v:0?", "-map", "0:a?", "-map", "0:s?"));
    }

    /** Replaces the explicit -map arguments with a single "-map 0". */
    static String[] withMapAll(String[] args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("-map".equals(args[i])) {
                i++;
                continue;
            }
            out.add(args[i]);
        }
        int at = out.size();
        for (int i = out.size() - 1; i >= 0; i--) {
            if ("-y".equals(out.get(i)) && i + 1 < out.size()) {
                at = i;
                break;
            }
        }
        out.add(at, "0");
        out.add(at, "-map");
        return out.toArray(new String[0]);
    }

    /**
     * What the concat demuxer advances for this part: the video track's
     * duration, then the container's, then the requested length.
     */
    long pieceOffsetMs(File f) {
        long ms = videoDurationMs(f, 0);
        if (ms > 0) return ms;
        return ffprobeLikeDurationMs(f);
    }

    /**
     * The video track's duration. Read from the track format (MediaExtractor),
     * which is what ffprobe prints for the stream as well.
     */
    private static long videoDurationMs(File f, long fallbackMs) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        long us = fmt.getLong(MediaFormat.KEY_DURATION);
                        if (us > 0) return us / 1000L;
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "video duration probe failed for " + f.getName(), t);
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
        return fallbackMs > 0 ? fallbackMs : durationMs(f);
    }

    static long durationMs(File f) {
        if (f == null || !f.isFile() || f.length() == 0) return 0;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.getAbsolutePath());
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d.trim());
        } catch (Throwable t) {
            return 0;
        } finally {
            try {
                mmr.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static long ffprobeLikeDurationMs(File f) {
        long ms = durationMs(f);
        return ms > 0 ? ms : 0;
    }

    /** Keyframes of a file, in ms - what the timeline draws and cuts on. */
    static long[] probeKeyframes(File f) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) return new long[0];
            ex.selectTrack(track);
            List<Long> times = new ArrayList<>();
            while (true) {
                long us = ex.getSampleTime();
                if (us < 0) break;
                if ((ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    times.add((us + 999) / 1000);
                }
                if (!ex.advance()) break;
            }
            long[] out = new long[times.size()];
            for (int i = 0; i < out.length; i++) out[i] = times.get(i);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "keyframe probe failed for " + f, t);
            return new long[0];
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Compressing (the one place this engine decodes and re-encodes)
    // ------------------------------------------------------------------

    /**
     * The whole Compress job. Videos are done one at a time and each source's
     * local copy is deleted as soon as its output is written, so peak disk use
     * stays at roughly one video plus its output rather than the whole list.
     *
     * <p>A file that fails is reported as a note and the rest still run - the
     * point of a batch compressor is that one bad file does not lose the work
     * of the others.
     */
    private void doCompress(Context context, List<Uri> sources, CompressOptions opts) {
        postProgress(-1);
        File workDir = null;
        List<File> outputs = new ArrayList<>();
        List<String> notes = Collections.synchronizedList(new ArrayList<>());
        try {
            File cache = context.getCacheDir();
            if (cache == null) throw new IOException("No cache folder");
            workDir = new File(cache, "video_edit_" + System.nanoTime());
            if (!workDir.mkdirs() && !workDir.isDirectory()) {
                throw new IOException("Cannot create a working folder");
            }

            step[0] = 0;
            step[1] = Math.max(1, sources.size());

            for (int i = 0; i < sources.size(); i++) {
                checkCancelled();
                Uri uri = sources.get(i);
                // the loop counter moves on, the callbacks must not
                final int fileIndex = i;
                if (uri == null) {
                    step[0]++;
                    postStepProgress(0);
                    continue;
                }

                String title = displayName(context, uri);
                if (title.toLowerCase(Locale.ROOT).endsWith("_compressed.mp4")) {
                    addWarning(notes, "\"" + title + "\" is already a compressed copy - skipped.");
                    step[0]++;
                    postFileProgress(fileIndex, 100);
                    continue;
                }

                // room for the copy and the file being written next to it
                long expected = expectedSize(context, uri);
                if (expected > 0) requireFreeSpace(context, expected * 2);

                File local = null;
                File target = null;
                try {
                    local = copySourceToLocal(context, uri, workDir);
                    long durationMs = durationMs(local);
                    target = uniqueOutput(context, SUBFOLDER_COMPRESS,
                            stemOf(title) + "_compressed", "mp4");
                    postFileProgress(fileIndex, 0);

                    boolean ok = exec(compressArgs(local, target, opts, true), durationMs,
                            p -> postFileProgress(fileIndex, p));
                    if (!ok) {
                        checkCancelled();
                        // The source's audio cannot live in an MP4 (Opus, Vorbis,
                        // PCM from an MKV/WebM...). Re-encode just the sound and
                        // keep the video exactly as the first pass would have it.
                        safeDelete(target);
                        postFileProgress(fileIndex, 0);
                        ok = exec(compressArgs(local, target, opts, false), durationMs,
                                p -> postFileProgress(fileIndex, p));
                        if (ok) {
                            addWarning(notes, "\"" + title + "\" has an audio track that MP4 cannot "
                                    + "hold, so its sound was re-encoded to AAC.");
                        }
                    }

                    if (!ok || target == null || !target.isFile() || target.length() == 0) {
                        safeDelete(target);
                        addWarning(notes, "\"" + title + "\" could not be compressed and was left out.");
                        step[0]++;
                        postStepProgress(0);
                        continue;
                    }

                    try {
                        MediaScannerConnection.scanFile(context,
                                new String[]{target.getAbsolutePath()}, null, null);
                    } catch (Throwable ignored) {
                    }
                    outputs.add(target);
                    step[0]++;
                    postFileProgress(fileIndex, 100);
                } finally {
                    // the source copy is dead weight once this file is written
                    if (local != null) safeDelete(local);
                }
            }

            if (outputs.isEmpty()) {
                postError(listener, "Nothing could be compressed. Your originals are untouched.", false);
                return;
            }
            jobSucceeded.set(true);        // the finished files belong to the user now
            if (!notes.isEmpty()) {
                try {
                    listener.onWarnings(new ArrayList<>(notes));
                } catch (Throwable t) {
                    Log.w(TAG, "warning callback failed", t);
                }
            }
            if (finished.compareAndSet(false, true)) {
                listener.onSuccess(outputs);
            }
        } catch (Throwable t) {
            if (cancelled.get()) {
                postError(listener, "Cancelled", true);
            } else {
                Log.e(TAG, "compress failed", t);
                postError(listener, t instanceof IOException ? t.getMessage()
                        : "The compression failed: " + t.getMessage(), false);
            }
        } finally {
            if (!jobSucceeded.get()) {
                for (File f : outputs) safeDelete(f);
            }
            if (workDir != null) deleteTree(workDir);
        }
    }

    /**
     * The arguments for one compression run.
     *
     * <p>{@code scale=-2:min(ih,H)} keeps the source's aspect ratio and only
     * ever shrinks: forcing the script's literal {@code 1280:720} would stretch
     * a 4:3 video and would blow a 480p one up. {@code -2} keeps the width even,
     * which yuv420p requires.
     *
     * <p>Unlike the cut/merge commands this one does NOT pass
     * {@code -hide_banner}/{@code -loglevel}, because ffmpeg-kit builds its
     * progress statistics out of ffmpeg's own log output - silencing it would
     * freeze the progress bar at 0.
     *
     * @param copyAudio true to keep the source's audio stream untouched; false
     *                  to re-encode it to AAC (the fallback for audio MP4
     *                  cannot carry)
     */
    static String[] compressArgs(File in, File out, CompressOptions opts, boolean copyAudio) {
        CompressOptions o = opts != null ? opts : new CompressOptions();
        List<String> a = new ArrayList<>();
        a.add("-y");
        a.add("-i");
        a.add(in.getAbsolutePath());

        List<String> filters = new ArrayList<>();
        if (o.maxHeight > 0) {
            filters.add("scale=-2:'min(ih," + o.maxHeight + ")'");
        }
        if (o.fps > 0) {
            filters.add("fps=" + o.fps);
        }
        if (!filters.isEmpty()) {
            filters.add("format=yuv420p");
            a.add("-vf");
            a.add(android.text.TextUtils.join(",", filters));
        }

        // explicit maps: the same guard the cuts use, so a timecode or data
        // track cannot make the encoder refuse the file
        a.add("-map");
        a.add("0:v:0?");
        a.add("-map");
        a.add("0:a?");
        a.add("-c:v");
        a.add("libx264");
        a.add("-preset");
        a.add(o.preset != null && !o.preset.isEmpty() ? o.preset : "veryfast");
        a.add("-crf");
        a.add(String.valueOf(o.crf));
        a.add("-pix_fmt");
        a.add("yuv420p");
        if (copyAudio) {
            a.add("-c:a");
            a.add("copy");
        } else {
            a.add("-c:a");
            a.add("aac");
            a.add("-b:a");
            a.add(FALLBACK_AUDIO_BITRATE);
        }
        a.add("-movflags");
        a.add("+faststart");
        a.add(out.getAbsolutePath());
        return a.toArray(new String[0]);
    }

    /** The file's name without its extension; the output is named after it. */
    private static String stemOf(String name) {
        if (name == null || name.isEmpty()) return "video";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** The provider's display name for a Uri, falling back to the path's last segment. */
    private static String displayName(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Throwable ignored) {
        }
        String last = uri != null ? uri.getLastPathSegment() : null;
        return last != null && !last.isEmpty() ? last : "video";
    }

    /** Per-file progress: the row's bar, then the job's own bar. */
    private void postFileProgress(int index, int percent) {
        int p = Math.max(0, Math.min(100, percent));
        EditListener l = listener;
        if (l != null) {
            try {
                l.onFileProgress(index, p);
            } catch (Throwable t) {
                Log.w(TAG, "file progress callback failed", t);
            }
        }
        postStepProgress(p);
    }

    // ------------------------------------------------------------------
    // Running ffmpeg
    // ------------------------------------------------------------------

    private interface ProgressSink {
        void accept(int percentInStep);
    }

    private boolean exec(String[] args, long expectedMs, ProgressSink sink) {
        if (cancelled.get()) return false;
        com.arthenica.ffmpegkit.StatisticsCallback cb = null;
        if (sink != null && expectedMs > 0) {
            cb = st -> {
                if (st != null) {
                    int p = (int) Math.min(100, (st.getTime() * 100f) / expectedMs);
                    sink.accept(Math.max(0, p));
                }
            };
        }
        final Object lock = new Object();
        final boolean[] done = new boolean[1];
        final boolean[] ok = new boolean[1];
        FFmpegSession session;
        try {
            session = FFmpegKit.executeWithArgumentsAsync(args, completed -> {
                synchronized (lock) {
                    ok[0] = completed != null && ReturnCode.isSuccess(completed.getReturnCode());
                    done[0] = true;
                    lock.notifyAll();
                }
            }, null, cb);
        } catch (Throwable t) {
            Log.e(TAG, "ffmpeg could not be started", t);
            return false;
        }
        if (session == null) return false;
        activeSession = session;
        synchronized (lock) {
            while (!done[0]) {
                try {
                    lock.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    FFmpegKit.cancel(session.getSessionId());
                    activeSession = null;
                    return false;
                }
                if (cancelled.get()) FFmpegKit.cancel(session.getSessionId());
            }
        }
        activeSession = null;
        return ok[0] && !cancelled.get();
    }

    private void checkCancelled() {
        if (cancelled.get()) throw new Cancelled();
    }

    private static final class Cancelled extends RuntimeException {
    }

    private void postStepProgress(int percentInStep) {
        int total = Math.max(1, step[1]);
        int p = (int) (100f * (step[0] + Math.max(0, Math.min(100, percentInStep)) / 100f) / total);
        postProgress(Math.max(0, Math.min(100, p)));
    }

    private void postProgress(int percent) {
        EditListener l = listener;
        if (l == null) return;
        try {
            l.onProgress(percent);
        } catch (Throwable t) {
            Log.w(TAG, "progress callback failed", t);
        }
    }

    private void postError(EditListener l, String message, boolean wasCancelled) {
        if (l == null) return;
        if (!finished.compareAndSet(false, true)) return;
        try {
            l.onError(message, wasCancelled);
        } catch (Throwable t) {
            Log.w(TAG, "error callback failed", t);
        }
    }

    private static void addWarning(List<String> warnings, String text) {
        if (warnings == null) return;
        synchronized (warnings) {
            if (!warnings.contains(text)) warnings.add(text);
        }
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    /**
     * The folder the parts are written to.
     * <p>Routes into {@code <shared>/aud_player/<subfolder>} where the
     * subfolder depends on the job: {@link #SUBFOLDER_MERGE} for the Merge
     * tab, {@link #SUBFOLDER_SPLIT} for Split, {@link #SUBFOLDER_COMPRESS}
     * for Compress. The no-arg overload keeps the legacy
     * {@link #SUBFOLDER_DEFAULT} target so any external caller is unaffected.
     */
    public static File getOutputDir(Context context) {
        return getOutputDir(context, SUBFOLDER_DEFAULT);
    }

    /**
     * Output dir for a job in the named subfolder inside aud_player/ — the
     * shared-storage folder is preferred, with the same fallback chain as the
     * no-arg overload.
     */
    public static File getOutputDir(Context context, String subfolder) {
        String sf = sanitizeSubfolder(subfolder);
        try {
            File primary = new File(Environment.getExternalStorageDirectory(), "aud_player/" + sf);
            if ((primary.exists() || primary.mkdirs()) && primary.canWrite()) return primary;
        } catch (Throwable ignored) {
        }
        try {
            File external = context.getExternalFilesDir(sf);
            if (external != null && (external.exists() || external.mkdirs())) return external;
        } catch (Throwable ignored) {
        }
        File internal = new File(context.getFilesDir(), sf);
        //noinspection ResultOfMethodCallIgnored
        internal.mkdirs();
        return internal;
    }

    /** Never overwrite an existing file: name.mp4, name_2.mp4, name_3.mp4 ... */
    public static File uniqueOutput(Context context, String baseName, String ext) {
        return uniqueOutput(context, SUBFOLDER_DEFAULT, baseName, ext);
    }

    /** Same deduplication rule, but writing into the named subfolder. */
    public static File uniqueOutput(Context context, String subfolder,
                                    String baseName, String ext) {
        File dir = getOutputDir(context, subfolder);
        String name = sanitize(baseName);
        String suffix = (ext == null || ext.isEmpty()) ? "mp4" : ext.toLowerCase(Locale.ROOT);
        File f = new File(dir, name + "." + suffix);
        int n = 2;
        while (f.exists()) {
            f = new File(dir, name + "_" + n + "." + suffix);
            n++;
        }
        return f;
    }

    /**
     * Subfolder name sanitiser: empty / null falls back to the default; the
     * value is treated as a single path segment so traversal characters are
     * collapsed to '_' just like a base filename.
     */
    private static String sanitizeSubfolder(String name) {
        if (name == null) return SUBFOLDER_DEFAULT;
        String s = name.trim();
        if (s.isEmpty()) return SUBFOLDER_DEFAULT;
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (s.length() > 40) s = s.substring(0, 40);
        return s;
    }

    private static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) return "video";
        String s = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    static String extOf(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "mp4";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String guessExtension(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null) {
                        int dot = name.lastIndexOf('.');
                        if (dot >= 0 && dot < name.length() - 1) {
                            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
                            if (ext.matches("[a-z0-9]{1,5}")) return ext;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "mp4";
    }

    private static long expectedSize(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** Source sizes plus a margin, checked against the cache before starting. */
    private void checkFreeSpace(Context context, List<Source> items) throws IOException {
        long needed = 0;
        for (Source s : items) {
            needed += s.local != null && s.local.isFile() ? s.local.length() : 0;
        }
        requireFreeSpace(context, needed);
    }

    /** The cache must hold {@code needed} bytes plus the usual margin. */
    private void requireFreeSpace(Context context, long needed) throws IOException {
        try {
            StatFs stat = new StatFs(context.getCacheDir().getAbsolutePath());
            long free = stat.getAvailableBytes();
            if (free > 0 && free < needed + FREE_SPACE_MARGIN) {
                throw new IOException("Not enough free space to edit these videos ("
                        + human(needed) + " needed, " + human(free) + " free). Free up some space "
                        + "and try again.");
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            FileChannel ic = in.getChannel();
            long size = ic.size();
            long at = 0;
            while (at < size) {
                long n = ic.transferTo(at, size - at, out.getChannel());
                if (n <= 0) break;
                at += n;
            }
            if (at < size) {
                byte[] buf = new byte[1024 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    private static void writeText(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
        }
    }

    private static void safeDelete(File f) {
        try {
            if (f != null && f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteTree(File dir) {
        if (dir == null) return;
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) deleteTree(k);
                else safeDelete(k);
            }
        }
        safeDelete(dir);
    }

    /** Bytes as a short human string ("1.2 GB" / "48 MB" / "900 kB"). */
    public static String human(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0);
        if (bytes >= 1024L * 1024) return String.format(Locale.US, "%.0f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.0f kB", bytes / 1024.0);
    }

    /** hh:mm:ss.mmm for ffmpeg. */
    static String msToTime(long ms) {
        long t = Math.max(0, ms);
        long h = t / 3600000L;
        long m = (t / 60000L) % 60;
        long s = (t / 1000L) % 60;
        long milli = t % 1000L;
        return String.format(Locale.US, "%d:%02d:%02d.%03d", h, m, s, milli);
    }

    // ------------------------------------------------------------------
    // Source signature
    // ------------------------------------------------------------------

    /**
     * What a lossless copy cannot change about a video, so it can be checked
     * before the merge instead of producing a file that plays wrong.
     */
    public static final class MediaInfo {
        /** Keyframe times of the source, ms from its start (may be empty). */
        public long[] keyframesMs = new long[0];
        public String videoMime;
        public String audioMime;
        public int width;
        public int height;
        public int rotationDegrees;
        public int sampleRate;
        public int channelCount;
        public boolean hasVideo;
        public boolean hasAudio;
        /** True once a probe really looked at the file. */
        public boolean probed;
        /** Name used in messages. */
        public String label;

        public MediaInfo() {
        }

        public static MediaInfo from(KeyframeProbe.Info info, String label) {
            MediaInfo m = new MediaInfo();
            m.label = label;
            if (info == null) return m;
            m.keyframesMs = info.keyframesMs != null ? info.keyframesMs : new long[0];
            m.videoMime = info.videoMime;
            m.audioMime = info.audioMime;
            m.width = info.width;
            m.height = info.height;
            m.rotationDegrees = info.rotationDegrees;
            m.sampleRate = info.sampleRate;
            m.channelCount = info.channelCount;
            m.hasVideo = info.hasVideo;
            m.hasAudio = info.hasAudio;
            m.probed = true;
            return m;
        }

        public boolean hasKeyframes() {
            return keyframesMs != null && keyframesMs.length > 0;
        }

        /** @return null when the two may be merged, else a reason the user can act on. */
        public String incompatibleWith(MediaInfo other) {
            if (other == null || !probed || !other.probed) return null;   // unknown: allow
            String a = label != null ? label : "video 1";
            String b = other.label != null ? other.label : "video 2";
            if (hasVideo != other.hasVideo) {
                return "\"" + a + "\" and \"" + b + "\" are not both videos";
            }
            if (hasVideo && !eq(videoMime, other.videoMime)) {
                return "different video codecs (" + a + ": " + shortMime(videoMime)
                        + ", " + b + ": " + shortMime(other.videoMime) + ")";
            }
            if (hasVideo && (width != other.width || height != other.height)) {
                return "different picture sizes (" + a + ": " + width + "x" + height
                        + ", " + b + ": " + other.width + "x" + other.height + ")";
            }
            if (hasAudio != other.hasAudio) {
                return "one of them has no sound while the other one has";
            }
            if (hasAudio && (!eq(audioMime, other.audioMime)
                    || sampleRate != other.sampleRate || channelCount != other.channelCount)) {
                return "different audio formats (" + a + ": " + sampleRate + " Hz "
                        + channelCount + "ch, " + b + ": " + other.sampleRate + " Hz "
                        + other.channelCount + "ch)";
            }
            if (rotationDegrees != other.rotationDegrees) {
                return "different rotation (" + a + ": " + rotationDegrees + "°, "
                        + b + ": " + other.rotationDegrees + "°)";
            }
            return null;
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }

        private static String shortMime(String mime) {
            if (mime == null) return "unknown";
            int slash = mime.indexOf('/');
            return slash >= 0 ? mime.substring(slash + 1) : mime;
        }
    }
}
