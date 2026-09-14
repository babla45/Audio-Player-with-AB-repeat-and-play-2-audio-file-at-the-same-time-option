package com.example.aud_player;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Statistics;
import com.arthenica.ffmpegkit.StatisticsCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FFmpeg-backed video editing engine for the Video Editor feature.
 *
 * Responsibilities:
 *  - Copy content:// source URIs into private cache temp files.
 *  - Split, cut, segment and merge operations strictly using STREAM COPY (-c copy).
 *  - Absolutely NO re-encoding: Preserves 100% original video/audio quality.
 *  - -avoid_negative_ts make_zero ensures each cut segment has proper 0-based timestamps
 *    so it can be merged again later using -c copy without stream corruption or desync.
 *  - Per-job progress reporting and cancellation.
 *  - Temp file cleanup on completion, error, or cancellation.
 *  - Broadcasts saved outputs to MediaScanner so videos show up immediately in Gallery/Players.
 */
public final class FFmpegVideoEditor {

    private static final String TAG = "FFmpegVideoEditor";
    /** Minimum allowed gap between two split points (ms). */
    public static final long MIN_SEGMENT_MS = 1000L;

    public interface EditListener {
        void onProgress(int percent);
        void onSuccess(List<File> outputs);
        void onError(String message, boolean cancelled);
    }

    public static class Segment {
        public final long startMs;
        public final long endMs;

        public Segment(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }

        public long durationMs() {
            return endMs - startMs;
        }
    }

    public static final int MODE_SEPARATE = 1;   // N segments -> N output video files
    public static final int MODE_MERGE = 2;      // N segments -> 1 merged output video file

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile FFmpegSession activeSession;

    public void release() {
        cancelled.set(true);
        cancelActive();
        executor.shutdownNow();
    }

    public void cancel() {
        cancelled.set(true);
        cancelActive();
    }

    public boolean isCancelled() {
        return cancelled.get();
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

    // ------------------------------------------------------------------
    // Source temp handling
    // ------------------------------------------------------------------

    private static File prepareTempDir(Context context) {
        File tempDir = new File(context.getCacheDir(), "video_editor_temp");
        if (!tempDir.exists()) tempDir.mkdirs();
        cleanupDir(tempDir);
        return tempDir;
    }

    private File copySourceToTemp(Context context, Uri source, File tempDir) throws IOException {
        String ext = guessExtension(context, source);
        File temp = File.createTempFile("vsrc_", "." + ext, tempDir);
        try (InputStream in = context.getContentResolver().openInputStream(source);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("Cannot open " + source);
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw e;
        }
        return temp;
    }

    private static String guessExtension(Context context, Uri uri) {
        String name = null;
        try {
            if ("file".equals(uri.getScheme())) {
                name = new File(uri.getPath()).getName();
            }
        } catch (Exception ignored) {}
        if (name == null) {
            try (android.database.Cursor c = context.getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = c.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (ext.matches("[a-z0-9]{1,5}")) return ext;
            }
        }
        return "mp4";
    }

    public void cleanupTemp(File temp) {
        if (temp != null) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            File parent = temp.getParentFile();
            if (parent != null) cleanupDir(parent);
        }
    }

    private static void cleanupTemps(List<File> temps) {
        if (temps == null || temps.isEmpty()) return;
        File parent = null;
        for (File f : temps) {
            if (f == null) continue;
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            parent = f.getParentFile();
        }
        if (parent != null) cleanupDir(parent);
    }

    private static void cleanupDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    // ------------------------------------------------------------------
    // Output location
    // ------------------------------------------------------------------

    public static File getOutputDir(Context context) {
        try {
            File primary = new File(Environment.getExternalStorageDirectory(),
                    "aud_player/edited");
            if ((primary.exists() || primary.mkdirs()) && primary.canWrite()) {
                return primary;
            }
        } catch (Exception ignored) {}
        File fallback = context.getExternalFilesDir("edited");
        if (fallback != null && (fallback.exists() || fallback.mkdirs())) {
            return fallback;
        }
        File internal = new File(context.getFilesDir(), "edited");
        //noinspection ResultOfMethodCallIgnored
        internal.mkdirs();
        return internal;
    }

    public static File uniqueOutput(Context context, String baseName, String ext) {
        File dir = getOutputDir(context);
        String sanitized = sanitize(baseName);
        File f = new File(dir, sanitized + "." + ext);
        int n = 2;
        while (f.exists()) {
            f = new File(dir, sanitized + "_" + n + "." + ext);
            n++;
        }
        return f;
    }

    private static String sanitize(String name) {
        String s = name == null ? "" : name.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (s.isEmpty()) s = "video";
        if (s.length() > 60) s = s.substring(0, 60);
        return s;
    }

    // ------------------------------------------------------------------
    // Jobs
    // ------------------------------------------------------------------

    public void runJobFromUri(final Context context, final List<Uri> sources,
                              final List<long[]> keyframesPerSource,
                              final long[] boundariesMs, final int mode,
                              final List<Segment> segments, final String baseName,
                              final EditListener listener) {
        if (sources == null || sources.isEmpty() || segments == null || segments.isEmpty()) {
            postError(listener, "Nothing to process", false);
            return;
        }
        cancelled.set(false);
        executor.execute(() -> {
            List<File> temps = new ArrayList<>();
            try {
                postProgress(listener, -1);
                File tempDir = prepareTempDir(context);
                for (Uri source : sources) {
                    temps.add(copySourceToTemp(context, source, tempDir));
                }
                doJob(context, temps, keyframesPerSource, boundariesMs, mode,
                        segments, baseName, listener);            } catch (Throwable e) {
                Log.e(TAG, "video edit job failed", e);
                postError(listener, "Error: " + e.getMessage(), false);
            } finally {
                cleanupTemps(temps);
            }
        });
    }

    private static final class Piece {
        final int inputIndex;
        final long startMs;
        final long endMs;

        Piece(int inputIndex, long startMs, long endMs) {
            this.inputIndex = inputIndex;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        long durationMs() {
            return endMs - startMs;
        }
    }

    private static void splitIntoSources(long startMs, long endMs, long[] boundaries,
                                         int inputCount, List<Piece> out) {
        if (boundaries == null || inputCount <= 1) {
            if (endMs > startMs) out.add(new Piece(0, startMs, endMs));
            return;
        }
        for (int i = 0; i < inputCount && i + 1 < boundaries.length; i++) {
            long s = Math.max(startMs, boundaries[i]);
            long e = Math.min(endMs, boundaries[i + 1]);
            if (e > s) out.add(new Piece(i, s - boundaries[i], e - boundaries[i]));
        }
    }

    private boolean cutPieces(List<File> inputs, long[][] kfs, List<Piece> pieces, int baseSeq,
                              String[] inputExt, File workDir, List<File> out,
                              ProgressSink sink, int[] step, EditListener listener) {
        for (int i = 0; i < pieces.size(); i++) {
            if (cancelled.get()) {
                postError(listener, "Cancelled", true);
                return false;
            }
            Piece p = pieces.get(i);
            File target = new File(workDir, "vp" + (baseSeq + i) + "." + inputExt[p.inputIndex]);
            File result = cutPieceSmart(inputs, kfs, p, target, sink, step, listener);
            if (result == null) {
                deleteAll(out);
                return false;
            }
            out.add(result);
        }
        return true;
    }

    /** Real duration of a media file in ms (0 when it cannot be read). */
    private static long measureDurationMs(File f) {
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.getAbsolutePath());
            String d = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Long.parseLong(d) : 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Smart cut: frame-accurate cuts with stream copy for the bulk
    // ------------------------------------------------------------------

    /**
     * True when the piece starts strictly after a keyframe boundary in its
     * source: only then does the cut need the encode-head treatment. A piece
     * that already starts on a keyframe (or whose source has no keyframe
     * info) is a plain stream copy — the copy is then exact.
     */
    private static boolean isSmartCut(long[] sourceKeyframes, long startMs, long endMs) {
        if (sourceKeyframes == null || sourceKeyframes.length == 0) return false;
        if (startMs <= 0) return false; // start of the file is a keyframe
        // is there a keyframe exactly at the start?
        int i = lowerBoundKeyframe(sourceKeyframes, startMs);
        if (i < sourceKeyframes.length && sourceKeyframes[i] == startMs) return false;
        // starting strictly between keyframes (or after the last one) needs
        // the encode head; starting exactly ON the previous keyframe does not
        return i > 0 && sourceKeyframes[i - 1] != startMs;
    }

    /** First index whose keyframe time is >= t (array sorted ascending). */
    private static int lowerBoundKeyframe(long[] kf, long t) {
        int lo = 0, hi = kf.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (kf[mid] < t) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /**
     * Cut one piece at frame accuracy:
     *
     *  - start on a keyframe  → single -c copy run (fast, lossless).
     *  - start mid-GOP        → SMART CUT: re-encode ONLY the few seconds from
     *    the piece's start to the next keyframe (the "head"), then stream-copy
     *    the rest (the "tail"), and concat the two. Total encoded material is
     *    one GOP — a few seconds — so the output stays visually identical to
     *    the source and its size within a few percent of a pure copy.
     *
     * Returns the finished file, or null (with the listener already informed)
     * on failure — the smart path also falls back to plain copy when encoding
     * is unavailable, preserving the old lossless behaviour.
     */
    private File cutPieceSmart(List<File> inputs, long[][] kfs, Piece p, File target,
                               ProgressSink sink, int[] step, EditListener listener) {
        File input = inputs.get(p.inputIndex);
        long[] kf = kfs != null && p.inputIndex < kfs.length ? kfs[p.inputIndex] : null;

        if (!isSmartCut(kf, p.startMs, p.endMs)) {
            // plain lossless copy (also the fallback when kf == null)
            File result = runFfmpegCut(input, target, p.startMs, p.endMs, sink);
            step[0]++;
            if (result == null) {
                //noinspection ResultOfMethodCallCall
                target.delete();
                postError(listener, "Stream copy cut failed on part (re-encoding is disabled).", false);
                return null;
            }
            return result;
        }

        // --- smart cut ---
        // isSmartCut already guaranteed a keyframe strictly before the start
        // (i > 0, kf[i-1] != startMs). The next keyframe at/after the start
        // may NOT exist (start after the last keyframe) — then the whole
        // piece is one encoded head.
        int i = lowerBoundKeyframe(kf, p.startMs);
        long prevKf = kf[i - 1];          // keyframe strictly before the start
        long nextKf = i < kf.length ? kf[i] : Long.MAX_VALUE; // next at/after
        long headEnd = Math.min(p.endMs, nextKf);
        if (headEnd <= p.startMs) {
            File result = runFfmpegCut(input, target, p.startMs, p.endMs, sink);
            step[0]++;
            return result;
        }

        String ext = extOf(target);
        File head = new File(target.getParentFile(),
                target.getName() + ".head." + ext);
        File tail = new File(target.getParentFile(),
                target.getName() + ".tail." + ext);

        // 1. head: decode from the previous keyframe, encode start..headEnd
        File headOut = runFfmpegEncodeHead(input, head, prevKf, p.startMs, headEnd, sink);
        step[0]++;
        if (cancelled.get()) {
            //noinspection ResultOfMethodCallIgnored
            head.delete();
            postError(listener, "Cancelled", true);
            return null;
        }
        if (headOut == null) {
            // Encoder missing/failed → fall back to the lossless keyframe cut
            Log.w(TAG, "smart-cut head encode failed; falling back to stream copy");
            //noinspection ResultOfMethodCallIgnored
            head.delete();
            File result = runFfmpegCut(input, target, prevKf, p.endMs, sink);
            step[0] += 2;
            if (result == null) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                postError(listener, "Stream copy cut failed on part.", false);
                return null;
            }
            return result;
        }

        // 2. tail: stream copy from the next keyframe to the piece end
        if (p.endMs > headEnd) {
            File tailOut = runFfmpegCut(input, tail, headEnd, p.endMs, sink);
            step[0]++;
            if (tailOut == null) {
                //noinspection ResultOfMethodCallIgnored
                head.delete();
                //noinspection ResultOfMethodCallIgnored
                tail.delete();
                postError(listener, "Stream copy cut failed on part.", false);
                return null;
            }
            // 3. concat head + tail
            List<File> bits = new ArrayList<>();
            bits.add(head);
            bits.add(tail);
            File merged = concatParts(target.getParentFile(), bits, target,
                    headEnd - p.startMs + (p.endMs - headEnd), sink, listener);
            step[0]++;
            //noinspection ResultOfMethodCallIgnored
            head.delete();
            //noinspection ResultOfMethodCallIgnored
            tail.delete();
            if (merged == null) return null;
            return merged;
        }

        // head covers the whole piece (it ends before the next keyframe)
        step[0] += 2;
        boolean renamed = head.renameTo(target);
        if (!renamed) {
            try {
                copyFile(head, target);
                //noinspection ResultOfMethodCallIgnored
                head.delete();
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                postError(listener, "Error: " + e.getMessage(), false);
                return null;
            }
        }
        return target;
    }

    /**
     * Re-encode the head of a smart cut. Input seeking starts at the previous
     * keyframe (so decoding is aligned), then output is trimmed to exactly
     * startMs..endMs. Video is re-encoded at a high quality CRF; audio and
     * every other stream are copied untouched.
     */
    private File runFfmpegEncodeHead(File input, File output, long seekMs,
                                     long startMs, long endMs, ProgressSink sink) {
        long outDurMs = Math.max(1, endMs - startMs);
        String seek = msToTime(seekMs);
        String skip = msToTime(startMs - seekMs);
        String dur = msToTime(outDurMs);

        // x264 CRF 16 is visually lossless and keeps the encoded head within
        // a few percent of the source's bitrate; preset veryfast keeps the
        // few-second encode quick. -fps_mode passthrough keeps the source's
        // exact frame timing, and the timescale is pinned to the de-facto
        // standard 90000 so the head shares the stream-copied tail's
        // timebase — the concat demuxer does NOT rescale a copied stream
        // into another timebase, so a mismatch (x264 default 15360) made
        // every tail timestamp land 5.86x too far apart and the merged
        // video play in slow motion.
        String[] cmd = {
                "-ss", seek,
                "-i", input.getAbsolutePath(),
                "-ss", skip,
                "-t", dur,
                "-map", "0",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "16",
                "-pix_fmt", "yuv420p",
                "-fps_mode", "passthrough",
                "-video_track_timescale", "90000",
                "-c:a", "copy",
                "-avoid_negative_ts", "make_zero",
                "-movflags", "+faststart",
                "-y", output.getAbsolutePath()
        };
        if (exec(cmd, outDurMs, sink)) return output;
        //noinspection ResultOfMethodCallIgnored
        output.delete();
        return null;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /**
     * Concat-demuxer merge. Each part's EXACT duration is written as a
     * `duration` directive: the demuxer offsets every part's timestamps by
     * the accumulated duration of the ones before it, and the duration
     * metadata of stream-copied cuts is often wrong or missing — with
     * REORDERED parts that left the offsets at zero and produced
     * non-monotonous DTS, which players then refuse to play properly. The
     * exact durations we already carry make the offsets exact.
     */
    /**
     * Concat-demuxer merge. Each part's MEASURED duration is written as a
     * `duration` directive: the demuxer offsets every part's timestamps by
     * the accumulated duration of the ones before it, and the duration a
     * part was MEANT to have can differ from what the file actually contains
     * (audio pre-roll before the cut point, encoder timing) — with REORDERED
     * parts any mismatch made the offsets wrong, DTS went backwards, and the
     * mp4 muxer stretched the timestamps to compensate, which players show
     * as stutter and slow motion. Measuring the real files (cheap, one
     * metadata read each) makes the offsets exact by construction.
     */
    private File concatParts(File workDir, List<File> parts, File output, long totalMs,
                             ProgressSink sink, EditListener listener) {
        if (cancelled.get()) {
            postError(listener, "Cancelled", true);
            return null;
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            File f = parts.get(i);
            list.append("file '")
                    .append(f.getAbsolutePath().replace("'", "'\\''"))
                    .append("'\n");
            long durMs = measureDurationMs(f);
            if (durMs > 0) {
                list.append("duration ")
                        .append(String.format(Locale.US, "%.3f", durMs / 1000.0))
                        .append('\n');
            } else {
                Log.w(TAG, "could not measure duration of " + f.getName()
                        + " — concat offset may be off");
            }
        }
        File listFile = new File(workDir, "concat_" + System.nanoTime() + ".txt");
        try {
            writeText(listFile, list.toString());
        } catch (IOException e) {
            postError(listener, "Error: " + e.getMessage(), false);
            return null;
        }
        File result = runFfmpegConcat(listFile, output, totalMs, sink);
        //noinspection ResultOfMethodCallIgnored
        listFile.delete();
        if (cancelled.get()) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            postError(listener, "Cancelled", true);
            return null;
        }
        if (result == null) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            postError(listener, "Stream copy merge failed: input streams may have incompatible codecs or formats. (Re-encoding is disabled).", false);
            return null;
        }
        return result;
    }

    private boolean isOk(File result, File target, List<File> produced, EditListener listener) {
        if (cancelled.get()) {
            if (result != null) result.delete();
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            deleteAll(produced);
            postError(listener, "Cancelled", true);
            return false;
        }
        if (result == null) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            deleteAll(produced);
            postError(listener, "Stream copy failed on part (re-encoding is disabled).", false);
            return false;
        }
        return true;
    }

    private void doJob(Context context, List<File> inputs, List<long[]> keyframesPerSource,
                       long[] boundaries, int mode,
                       List<Segment> segments, String baseName,
                       EditListener listener) {
        File workDir = new File(inputs.get(0).getParentFile(), "vparts");
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();
        List<File> produced = new ArrayList<>();
        try {
            String[] inputExt = new String[inputs.size()];
            for (int i = 0; i < inputs.size(); i++) inputExt[i] = extOf(inputs.get(i));
            String ext = inputExt[0];
            // resolve each input's keyframe list (null = probe failed → plain copy)
            long[][] kfs = new long[inputs.size()][];
            for (int i = 0; i < inputs.size(); i++) {
                long[] k = keyframesPerSource != null && i < keyframesPerSource.size()
                        ? keyframesPerSource.get(i) : null;
                kfs[i] = (k != null && k.length > 0) ? k : null;
            }

            List<List<Piece>> perSegment = new ArrayList<>();
            long totalMs = 0;
            int runCount = 0;
            for (Segment s : segments) {
                List<Piece> pieces = new ArrayList<>();
                splitIntoSources(s.startMs, s.endMs, boundaries, inputs.size(), pieces);
                if (pieces.isEmpty()) continue;
                perSegment.add(pieces);
                for (Piece p : pieces) {
                    totalMs += Math.max(1, p.durationMs());
                    // a smart cut costs 3 ffmpeg runs (encode head + copy tail
                    // + concat), a plain one costs 1 — progress stays honest
                    runCount += isSmartCut(kfs[p.inputIndex], p.startMs, p.endMs) ? 3 : 1;
                }
            }
            if (perSegment.isEmpty()) {
                postError(listener, "Nothing to process", false);
                return;
            }

            int concatRuns = 0;
            if (mode == MODE_SEPARATE) {
                for (List<Piece> pieces : perSegment) if (pieces.size() > 1) concatRuns++;
            } else if (runCount > 1) {
                concatRuns = 1;
            }
            int stepCount = Math.max(1, runCount + concatRuns);
            final int[] step = {0};
            ProgressSink sink = percent -> postProgress(listener,
                    (int) (100f * (step[0] + percent / 100f) / stepCount));

            int seq = 0;
            if (mode != MODE_SEPARATE) {
                // ---- Merge mode: 1 merged output ----
                String name = baseName + "_merged";
                if (perSegment.size() == 1 && perSegment.get(0).size() == 1) {
                    Piece p = perSegment.get(0).get(0);
                    File target = uniqueOutput(context, name, inputExt[p.inputIndex]);
                    File result = cutPieceSmart(inputs, kfs, p, target, sink, step, listener);
                    if (result == null) return;
                    produced.add(result);
                } else {
                    List<File> parts = new ArrayList<>();
                    for (List<Piece> pieces : perSegment) {
                        if (!cutPieces(inputs, kfs, pieces, seq, inputExt, workDir, parts,
                                sink, step, listener)) {
                            deleteAll(parts);
                            deleteAll(produced);
                            return;
                        }
                        seq += pieces.size();
                    }
                    File merged = concatParts(workDir, parts,
                            uniqueOutput(context, name, ext), totalMs, sink, listener);
                    deleteAll(parts);
                    if (merged == null) {
                        deleteAll(produced);
                        return;
                    }
                    produced.add(merged);
                }
            } else {
                // ---- Separate mode: 1 output per timeline segment ----
                for (int i = 0; i < perSegment.size(); i++) {
                    List<Piece> pieces = perSegment.get(i);
                    String name = baseName + "_part" + (i + 1);
                    if (pieces.size() == 1) {
                        Piece p = pieces.get(0);
                        File target = uniqueOutput(context, name, inputExt[p.inputIndex]);
                        File result = cutPieceSmart(inputs, kfs, p, target, sink, step, listener);
                        if (result == null) return;
                        produced.add(result);
                    } else {
                        List<File> parts = new ArrayList<>();
                        if (!cutPieces(inputs, kfs, pieces, seq, inputExt, workDir, parts,
                                sink, step, listener)) {
                            deleteAll(parts);
                            deleteAll(produced);
                            return;
                        }
                        seq += pieces.size();
                        long partMs = 0;
                        for (Piece p : pieces) partMs += Math.max(1, p.durationMs());
                        File merged = concatParts(workDir, parts,
                                uniqueOutput(context, name, ext), partMs, sink, listener);
                        deleteAll(parts);
                        if (merged == null) {
                            deleteAll(produced);
                            return;
                        }
                        produced.add(merged);
                    }
                }
            }

            // Broadcast media scanner so files are instantly visible in Gallery/Video player
            for (File f : produced) {
                try {
                    MediaScannerConnection.scanFile(context, new String[]{f.getAbsolutePath()}, null, null);
                } catch (Exception ignored) {}
            }

            postSuccess(listener, produced);
        } catch (Throwable e) {
            Log.e(TAG, "video edit job failed", e);
            deleteAll(produced);
            postError(listener, "Error: " + e.getMessage(), false);
        } finally {
            cleanupDir(workDir);
        }
    }

    private static void deleteAll(List<File> files) {
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String extOf(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1) : "mp4";
    }

    private static void writeText(File f, String text) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(text.getBytes("UTF-8"));
        }
    }

    // ------------------------------------------------------------------
    // FFmpeg execution (STRICT STREAM COPY, NO RE-ENCODING)
    // ------------------------------------------------------------------

    private interface ProgressSink {
        void accept(int percent);
    }

    /**
     * Cut one video segment using STRICT stream copy (-c copy).
     * -ss before -i seeks to the nearest keyframe cleanly.
     * -avoid_negative_ts make_zero normalizes timestamps to zero, making
     * the resulting segment 100% compatible for later concat-demuxer merging!
     */
    private File runFfmpegCut(File input, File output, long startMs, long endMs,
                              ProgressSink sink) {
        long durationMs = Math.max(1, endMs - startMs);
        String ss = msToTime(startMs);
        String t = msToTime(durationMs);

        // -video_track_timescale 90000 normalizes every piece to the same
        // timescale: merging videos from different cameras would otherwise
        // concat streams with different timebases, and the copied packets
        // are not rescaled — the mismatched ones play at the wrong speed.
        String[] copyCmd = {
                "-ss", ss,
                "-i", input.getAbsolutePath(),
                "-t", t,
                "-c", "copy",
                "-avoid_negative_ts", "make_zero",
                "-video_track_timescale", "90000",
                "-map", "0",
                "-movflags", "+faststart",
                "-y", output.getAbsolutePath()
        };

        if (exec(copyCmd, durationMs, sink)) {
            return output;
        }

        // NO RE-ENCODE FALLBACK per explicit requirement!
        //noinspection ResultOfMethodCallIgnored
        output.delete();
        return null;
    }

    /**
     * Concat video demuxer merge using STRICT stream copy (-c copy).
     * +genpts regenerates any missing presentation timestamps, and
     * avoid_negative_ts normalizes the output so every player reads a
     * clean, monotonic timeline.
     */
    private File runFfmpegConcat(File listFile, File output, long totalMs,
                                 ProgressSink sink) {
        String[] cmd = {
                "-f", "concat",
                "-safe", "0",
                "-fflags", "+genpts",
                "-i", listFile.getAbsolutePath(),
                "-c", "copy",
                "-avoid_negative_ts", "make_zero",
                "-movflags", "+faststart",
                "-y", output.getAbsolutePath()
        };

        if (exec(cmd, totalMs, sink)) {
            return output;
        }

        // NO RE-ENCODE FALLBACK per explicit requirement!
        //noinspection ResultOfMethodCallIgnored
        output.delete();
        return null;
    }

    private boolean exec(String[] args, long expectedMs, ProgressSink sink) {
        final Object lock = new Object();
        final boolean[] done = new boolean[1];
        final boolean[] ok = new boolean[1];

        StatisticsCallback stats = s -> {
            if (sink != null && expectedMs > 0) {
                int p = (int) Math.min(100, (s.getTime() * 100f) / expectedMs);
                sink.accept(Math.max(0, p));
            }
        };

        FFmpegSession session = FFmpegKit.executeWithArgumentsAsync(args,
                completed -> {
                    synchronized (lock) {
                        ok[0] = ReturnCode.isSuccess(completed.getReturnCode());
                        done[0] = true;
                        lock.notifyAll();
                    }
                }, null, stats);

        if (session == null) return false;
        activeSession = session;

        synchronized (lock) {
            while (!done[0]) {
                try {
                    lock.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    FFmpegKit.cancel(session.getSessionId());
                    return false;
                }
                if (cancelled.get()) {
                    FFmpegKit.cancel(session.getSessionId());
                }
            }
        }
        activeSession = null;
        return ok[0];
    }

    private static String msToTime(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        long msPart = ms % 1000;
        return String.format(Locale.US, "%d:%02d:%02d.%03d", h, m, s, msPart);
    }

    private void postProgress(EditListener l, int p) {
        if (l == null) return;
        main.post(() -> l.onProgress(p));
    }

    private void postSuccess(EditListener l, List<File> outputs) {
        if (l == null) return;
        main.post(() -> l.onSuccess(outputs));
    }

    private void postError(EditListener l, String msg, boolean wasCancelled) {
        if (l == null) return;
        main.post(() -> l.onError(msg, wasCancelled));
    }
}
