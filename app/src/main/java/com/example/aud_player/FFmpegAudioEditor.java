package com.example.aud_player;

import android.content.Context;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FFmpeg-backed audio editing engine for the Audio Editor feature.
 *
 * Responsibilities:
 *  - Copy a content:// source URI into a private cache temp file (FFmpeg
 *    cannot reliably read MediaStore content URIs directly).
 *  - Cut/trim, multi-split and merge operations using stream copy
 *    (-c copy) so audio quality is preserved and no re-encoding happens
 *    whenever possible.
 *  - Mixed-format merge: {@link #planFormats} reports which container most of
 *    the sources already use, and a merge may name it as its target. Only the
 *    sources in the OTHER containers are then converted — once, up front — so
 *    the merge itself stays a stream copy instead of the whole job being
 *    re-encoded.
 *  - Re-encode fallback (native AAC into .m4a) if stream copy fails.
 *  - Per-job progress reporting and cancellation.
 *  - Temp file cleanup on success, failure or cancellation.
 *
 * All callbacks are invoked on the main thread.
 */
public final class FFmpegAudioEditor {

    private static final String TAG = "FFmpegAudioEditor";
    /** Minimum allowed gap between two split points (ms). */
    public static final long MIN_SEGMENT_MS = 1000L;

    public interface EditListener {
        /** Progress 0..100 for the current overall job. */
        void onProgress(int percent);

        /** Job finished successfully; outputs contains the written files. */
        void onSuccess(List<File> outputs);

        /** Job failed (cancelled == true when the user cancelled). */
        void onError(String message, boolean cancelled);
    }

    /** One output range [startMs, endMs). */
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

    public static final int MODE_SEPARATE = 1;   // N segments -> N outputs
    public static final int MODE_MERGE = 2;      // N segments -> 1 merged output

    /**
     * Subfolders inside <shared storage>/aud_player/ for each kind of job.
     * Keeps Merge / Split results separate on disk without touching filenames,
     * naming conventions, the fallback chain, or the scan-after-write flow.
     */
    public static final String SUBFOLDER_MERGE = "merged";
    public static final String SUBFOLDER_SPLIT = "split";
    /** Kept as the default when no subfolder is supplied (backward compatible). */
    private static final String SUBFOLDER_DEFAULT = "edited";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile FFmpegSession activeSession;

    /** Shut down the worker thread. Call when the editor UI is destroyed. */
    public void release() {
        cancelled.set(true);
        cancelActive();
        executor.shutdownNow();
    }

    /** Cancel the running job (if any). */
    public void cancel() {
        cancelled.set(true);
        cancelActive();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** Cancel the currently active FFmpeg session (if any). */
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

    /**
     * Prepare the private temp dir for a job: create it and drop leftovers
     * from a previous run. Called ONCE per job — copying each source must not
     * wipe the sources already copied before it.
     */
    private static File prepareTempDir(Context context) {
        File tempDir = new File(context.getCacheDir(), "editor_temp");
        if (!tempDir.exists()) tempDir.mkdirs();
        cleanupDir(tempDir); // no stale temp files
        return tempDir;
    }

    /** Copy one source URI into the already-prepared temp dir. */
    private File copySourceToTemp(Context context, Uri source, File tempDir) throws IOException {
        String ext = guessExtension(context, source);
        File temp = File.createTempFile("src_", "." + ext, tempDir);
        try (InputStream in = context.getContentResolver().openInputStream(source);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("Cannot open " + source);
            byte[] buf = new byte[64 * 1024];
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

    /** Copy the source URI to a private cache file; FFmpeg needs a real path. */
    public File copyToTemp(Context context, Uri source) throws IOException {
        return copySourceToTemp(context, source, prepareTempDir(context));
    }

    private static String guessExtension(Context context, Uri uri) {
        String name = null;
        try {
            if ("file".equals(uri.getScheme())) {
                name = new File(uri.getPath()).getName();
            }
        } catch (Exception ignored) {
        }
        if (name == null) {
            try (android.database.Cursor c = context.getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = c.getString(idx);
                }
            } catch (Exception ignored) {
            }
        }
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase();
                if (ext.matches("[a-z0-9]{1,5}")) return ext;
            }
        }
        return "mp3";
    }

    /**
     * What a merge's sources are made of, by file name: the extension the most
     * sources already use — {@link #dominantExt}, i.e. the target the user is
     * offered — and the full breakdown in first-seen order.
     */
    public static final class FormatPlan {
        /** Extension the largest number of sources use; null for an empty list. */
        public final String dominantExt;
        /** How many sources carry {@link #dominantExt}. */
        public final int dominantCount;
        /** ext -> how many sources use it, in the order the sources appear. */
        public final LinkedHashMap<String, Integer> counts;
        /** Every source's own extension, in source order (parallel to the input). */
        public final List<String> exts;

        FormatPlan(String dominantExt, int dominantCount,
                   LinkedHashMap<String, Integer> counts, List<String> exts) {
            this.dominantExt = dominantExt;
            this.dominantCount = dominantCount;
            this.counts = counts;
            this.exts = exts;
        }

        public int totalCount() {
            int n = 0;
            for (int v : counts.values()) n += v;
            return n;
        }

        /** True when the sources are not all in one single format. */
        public boolean isMixed() {
            return counts.size() > 1;
        }

        /** How many sources a conversion to {@link #dominantExt} would touch. */
        public int convertCount() {
            return totalCount() - dominantCount;
        }

        /** "2 × .mp3, 1 × .m4a" — a breakdown a dialog can show verbatim. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getValue()).append(" × .").append(e.getKey());
            }
            return sb.toString();
        }
    }

    /**
     * Group the sources by the format their file name declares. Name based on
     * purpose: probing each file would mean copying it into the cache first,
     * and the file name is what the user's "convert these?" question is about.
     * A tie between two formats keeps the one that appears first.
     */
    public static FormatPlan planFormats(Context context, List<Uri> sources) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        List<String> exts = new ArrayList<>();
        if (sources != null) {
            for (Uri u : sources) {
                if (u == null) continue;
                String ext = guessExtension(context, u);
                exts.add(ext);
                Integer n = counts.get(ext);
                counts.put(ext, n == null ? 1 : n + 1);
            }
        }
        String dominant = null;
        int best = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > best) { // strict: a tie keeps the first format
                best = e.getValue();
                dominant = e.getKey();
            }
        }
        return new FormatPlan(dominant, best, counts, exts);
    }

    /** Delete the temp source file (and folder) after processing. */
    public void cleanupTemp(File temp) {
        if (temp != null) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            File parent = temp.getParentFile();
            if (parent != null) cleanupDir(parent);
        }
    }

    /** Delete every temp source file (and the folder) after processing. */
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

    /**
     * Output dir for edited audio: <shared storage>/aud_player/<subfolder>
     * when writable (visible folder, per user requirement), falling back to
     * the app-specific external files dir.
     * <p>The default subfolder is {@link #SUBFOLDER_DEFAULT}; the merge and
     * split jobs route their files to {@link #SUBFOLDER_MERGE} and
     * {@link #SUBFOLDER_SPLIT} respectively.
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
            File primary = new File(Environment.getExternalStorageDirectory(),
                    "aud_player/" + sf);
            if ((primary.exists() || primary.mkdirs()) && primary.canWrite()) {
                return primary;
            }
        } catch (Exception ignored) {
        }
        File fallback = context.getExternalFilesDir(sf);
        if (fallback != null && (fallback.exists() || fallback.mkdirs())) {
            return fallback;
        }
        File internal = new File(context.getFilesDir(), sf);
        //noinspection ResultOfMethodCallIgnored
        internal.mkdirs();
        return internal;
    }

    /** Generate a non-clobbering output file inside the output dir. */
    public static File uniqueOutput(Context context, String baseName, String ext) {
        return uniqueOutput(context, SUBFOLDER_DEFAULT, baseName, ext);
    }

    /** Generate a non-clobbering output file inside the named subfolder. */
    public static File uniqueOutput(Context context, String subfolder,
                                    String baseName, String ext) {
        File dir = getOutputDir(context, subfolder);
        String sanitized = sanitize(baseName);
        File f = new File(dir, sanitized + "." + ext);
        int n = 2;
        while (f.exists()) {
            f = new File(dir, sanitized + "_" + n + "." + ext);
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
        String s = name == null ? "" : name.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (s.isEmpty()) s = "audio";
        if (s.length() > 60) s = s.substring(0, 60);
        return s;
    }

    // ------------------------------------------------------------------
    // Jobs
    // ------------------------------------------------------------------

    /**
     * Run one editing job.
     *
     * MODE_SEPARATE : one output per segment, "<base>_partN.<ext>".
     * MODE_MERGE    : all segments (in list order — caller controls custom
     *                 order) merged into one "<base>_merged.<ext>".
     */
    public void runJob(final Context context, final File input, final int mode,
                       final List<Segment> segments, final String baseName,
                       final EditListener listener) {
        if (input == null || !input.exists() || segments == null || segments.isEmpty()) {
            postError(listener, "Nothing to process", false);
            return;
        }
        cancelled.set(false);
        List<File> inputs = new ArrayList<>(1);
        inputs.add(input);
        executor.execute(() -> doJob(context, inputs, null, mode, segments, baseName,
                null, listener));
    }

    /**
     * Same as runJob but takes a content:// source URI: copies it to a
     * private temp file first and always cleans the temp up afterwards
     * (success, failure or cancellation). Reports progress -1 (indeterminate)
     * while copying.
     */
    public void runJobFromUri(final Context context, final Uri source, final int mode,
                              final List<Segment> segments, final String baseName,
                              final EditListener listener) {
        List<Uri> one = new ArrayList<>(1);
        one.add(source);
        runJobFromUri(context, one, null, mode, segments, baseName, listener);
    }

    /**
     * Multi-source job. The edited timeline is the CONCATENATION of
     * `sources`, and `boundariesMs` (length sources.size()+1, starting at 0)
     * gives the global time each source starts at. Segment times are GLOBAL,
     * so one segment may span a source seam — it is cut as one piece per
     * source and the pieces are concatenated back together.
     *
     * Pass a null `boundariesMs` for the classic single-source behaviour:
     * every segment belongs to input 0 and its times are already local.
     */
    public void runJobFromUri(final Context context, final List<Uri> sources,
                              final long[] boundariesMs, final int mode,
                              final List<Segment> segments, final String baseName,
                              final EditListener listener) {
        runJobFromUri(context, sources, boundariesMs, mode, segments, baseName,
                null, listener);
    }

    /**
     * As above, with `mergeTargetExt` naming the container a MERGE should end
     * up in. When it is set, only the sources held in the other containers are
     * converted — once, before anything is cut — so the cuts, the concat and
     * the merged file itself all stay stream copies, instead of the whole job
     * being re-encoded because one source did not fit.
     * <p>Null is the original behaviour and is what a split always uses: its
     * outputs never mix containers, so there is nothing to reconcile.
     */
    public void runJobFromUri(final Context context, final List<Uri> sources,
                              final long[] boundariesMs, final int mode,
                              final List<Segment> segments, final String baseName,
                              final String mergeTargetExt, final EditListener listener) {
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
                doJob(context, temps, boundariesMs, mode, segments, baseName,
                        mergeTargetExt, listener);
            } catch (Throwable e) {
                // Throwable (not Exception): a NoClassDefFoundError or other
                // linkage error must surface as onError, never crash the app
                Log.e(TAG, "edit job failed", e);
                postError(listener, "Error: " + e.getMessage(), false);
            } finally {
                cleanupTemps(temps);
            }
        });
    }

    /** One cuttable range inside one input file (local time). */
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

    /**
     * Express one GLOBAL timeline segment as per-input pieces (local time).
     * When the boundaries are unknown (single source) the segment maps onto
     * input 0 unchanged — the pre-multi-source behaviour.
     */
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

    /**
     * Cut every piece, in order, into the work dir; the files actually
     * written are appended to `out`. `baseSeq` keeps temp names unique across
     * the whole job. Returns false after posting the failure/cancellation.
     */
    private boolean cutPieces(List<File> inputs, List<Piece> pieces, int baseSeq,
                              String[] inputExt, File workDir, List<File> out,
                              ProgressSink sink, int[] step, EditListener listener) {
        for (int i = 0; i < pieces.size(); i++) {
            if (cancelled.get()) {
                postError(listener, "Cancelled", true);
                return false;
            }
            Piece p = pieces.get(i);
            File target = new File(workDir,
                    "p" + (baseSeq + i) + "." + inputExt[p.inputIndex]);
            File result = runFfmpegCut(inputs.get(p.inputIndex), target, p.startMs, p.endMs, sink);
            step[0]++;
            if (cancelled.get()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                postError(listener, "Cancelled", true);
                return false;
            }
            if (result == null) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                postError(listener, "FFmpeg failed on a part", false);
                return false;
            }
            out.add(result);
        }
        return true;
    }

    /** Concat the part files (in order) into `output`; null after an error. */
    private File concatParts(File workDir, List<File> parts, File output, long totalMs,
                             ProgressSink sink, EditListener listener) {
        if (cancelled.get()) {
            postError(listener, "Cancelled", true);
            return null;
        }
        StringBuilder list = new StringBuilder();
        for (File f : parts) {
            list.append("file '")
                    .append(f.getAbsolutePath().replace("'", "'\\''"))
                    .append("'\n");
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
            postError(listener, "FFmpeg merge failed", false);
            return null;
        }
        return result;
    }

    /** Post the failure/cancellation of a single cut; true when it produced. */
    private boolean isOk(File result, File target, List<File> produced, EditListener listener) {
        if (cancelled.get()) {
            //noinspection ResultOfMethodCallIgnored
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
            postError(listener, "FFmpeg failed", false);
            return false;
        }
        return true;
    }

    private void doJob(Context context, List<File> inputs, long[] boundaries, int mode,
                       List<Segment> segments, String baseName, String mergeTargetExt,
                       EditListener listener) {
        File workDir = new File(inputs.get(0).getParentFile(), "parts");
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();
        List<File> produced = new ArrayList<>();
        // Working copy of the source list: a format conversion swaps entries
        // here, while the caller's own list — whose cleanup deletes the copied
        // sources — must keep pointing at the files that really exist.
        List<File> src = new ArrayList<>(inputs);
        try {
            // Each input keeps its OWN container extension: stream-copying an
            // m4a source into a ".mp3" target would fail outright.
            String[] inputExt = new String[src.size()];
            for (int i = 0; i < src.size(); i++) inputExt[i] = extOf(src.get(i));
            String ext = inputExt[0];

            // Every timeline segment, expressed as per-input pieces.
            List<List<Piece>> perSegment = new ArrayList<>();
            long totalMs = 0;
            int pieceCount = 0;
            for (Segment s : segments) {
                List<Piece> pieces = new ArrayList<>();
                splitIntoSources(s.startMs, s.endMs, boundaries, src.size(), pieces);
                if (pieces.isEmpty()) continue; // zero-length segment
                perSegment.add(pieces);
                pieceCount += pieces.size();
                for (Piece p : pieces) totalMs += Math.max(1, p.durationMs());
            }
            if (perSegment.isEmpty()) {
                postError(listener, "Nothing to process", false);
                return;
            }

            // ---- Mixed-format merge: how many sources must change format ----
            // A target only ever comes from a MERGE (a split writes one file
            // per segment, so its containers never mix). Counting them up front
            // lets the progress bar account for the work exactly.
            int conversions = 0;
            if (mergeTargetExt != null && mode == MODE_MERGE) {
                for (File f : src) {
                    if (!mergeTargetExt.equals(extOf(f))) conversions++;
                }
            }

            // One concat per multi-piece output: a single run for the
            // single-output modes, one per part for MODE_SEPARATE.
            int concatRuns = 0;
            if (mode == MODE_SEPARATE) {
                for (List<Piece> pieces : perSegment) if (pieces.size() > 1) concatRuns++;
            } else if (pieceCount > 1) {
                concatRuns = 1;
            }
            int stepCount = Math.max(1, pieceCount + concatRuns + conversions);
            final int[] step = {0};
            // one ffmpeg run's 0..100 folded into the job's overall progress
            ProgressSink sink = percent -> postProgress(listener,
                    (int) (100f * (step[0] + percent / 100f) / stepCount));

            // Convert the minority containers first: with every source in one
            // container the cuts below, the concat, and therefore the merged
            // file itself are all plain stream copies.
            if (conversions > 0) {
                int done = convertMismatched(src, inputExt, mergeTargetExt, workDir,
                        boundaries, sink, step, listener);
                if (done < 0) { // cancelled / errored — already posted
                    deleteAll(produced);
                    return;
                }
                if (done > 0) {
                    for (int i = 0; i < inputExt.length; i++) inputExt[i] = mergeTargetExt;
                    ext = mergeTargetExt;
                }
                // done == 0: the conversion was impossible, the originals are
                // back in place and the merge runs exactly as it did before.
            }

            int seq = 0;
            if (mode != MODE_SEPARATE) {
                // ---- Merge: exactly one output file ----
                String name = baseName + "_merged";
                if (pieceCount == 1) {
                    // fast path: the single piece IS the result
                    Piece p = perSegment.get(0).get(0);
                    File target = uniqueOutput(context, SUBFOLDER_MERGE, name, inputExt[p.inputIndex]);
                    File result = runFfmpegCut(src.get(p.inputIndex), target,
                            p.startMs, p.endMs, sink);
                    step[0]++;
                    if (!isOk(result, target, produced, listener)) return;
                    produced.add(result);
                } else {
                    List<File> parts = new ArrayList<>();
                    for (List<Piece> pieces : perSegment) {
                        if (!cutPieces(src, pieces, seq, inputExt, workDir, parts,
                                sink, step, listener)) {
                            deleteAll(parts);
                            deleteAll(produced);
                            return;
                        }
                        seq += pieces.size();
                    }
                    File merged = concatParts(workDir, parts,
                            uniqueOutput(context, SUBFOLDER_MERGE, name, ext),
                            totalMs, sink, listener);
                    deleteAll(parts);
                    if (merged == null) {
                        deleteAll(produced);
                        return;
                    }
                    produced.add(merged);
                }
            } else {
                // ---- Separate: one output per timeline segment ----
                for (int i = 0; i < perSegment.size(); i++) {
                    List<Piece> pieces = perSegment.get(i);
                    String name = baseName + "_part" + (i + 1);
                    if (pieces.size() == 1) {
                        Piece p = pieces.get(0);
                        File target = uniqueOutput(context, SUBFOLDER_SPLIT, name, inputExt[p.inputIndex]);
                        File result = runFfmpegCut(src.get(p.inputIndex), target,
                                p.startMs, p.endMs, sink);
                        step[0]++;
                        if (!isOk(result, target, produced, listener)) return;
                        produced.add(result);
                    } else {
                        List<File> parts = new ArrayList<>();
                        if (!cutPieces(src, pieces, seq, inputExt, workDir, parts,
                                sink, step, listener)) {
                            deleteAll(parts);
                            deleteAll(produced);
                            return;
                        }
                        seq += pieces.size();
                        long partMs = 0;
                        for (Piece p : pieces) partMs += Math.max(1, p.durationMs());
                        File merged = concatParts(workDir, parts,
                                uniqueOutput(context, SUBFOLDER_SPLIT, name, ext),
                                partMs, sink, listener);
                        deleteAll(parts);
                        if (merged == null) {
                            deleteAll(produced);
                            return;
                        }
                        produced.add(merged);
                    }
                }
            }

            postSuccess(listener, produced);
        } catch (Throwable e) {
            // Throwable (not Exception): linkage errors etc. must surface as
            // onError, never crash the app
            Log.e(TAG, "edit job failed", e);
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
        return dot >= 0 ? n.substring(dot + 1) : "mp3";
    }

    /**
     * Convert every source whose container is not `targetExt` into a temp file
     * of that container, swapped into `src` in its place. `ext` is deliberately
     * left alone — the caller rewrites it only once the whole swap succeeded,
     * so a half-done conversion can never reach the cutting stage.
     * <p>Counts one progress step per attempted conversion, which is what the
     * caller reserved for this phase.
     *
     * @return how many sources were converted; 0 when the conversion turned out
     *         to be impossible (every swap is undone, so the merge simply runs
     *         the way it did before); -1 when the job was cancelled or errored,
     *         already posted — the caller must stop.
     */
    private int convertMismatched(List<File> src, String[] ext, String targetExt,
                                  File workDir, long[] boundaries,
                                  ProgressSink sink, int[] step, EditListener listener) {
        List<Integer> swapped = new ArrayList<>();
        List<File> originals = new ArrayList<>();
        List<File> made = new ArrayList<>();
        for (int i = 0; i < src.size(); i++) {
            File in = src.get(i);
            if (targetExt.equals(ext[i])) continue;
            if (cancelled.get()) {
                deleteAll(made);
                postError(listener, "Cancelled", true);
                return -1;
            }
            // The source's own length is a close enough total for the
            // conversion's 0..100 readout (unknown for a single-source job,
            // which can never reach here).
            long expectedMs = 0;
            if (boundaries != null && i + 1 < boundaries.length) {
                expectedMs = Math.max(0, boundaries[i + 1] - boundaries[i]);
            }
            File out = new File(workDir, "conv" + i + "_" + System.nanoTime()
                    + "." + targetExt);
            File converted = runFfmpegConvert(in, out, targetExt, expectedMs, sink);
            step[0]++;
            if (converted == null) {
                if (cancelled.get()) {
                    deleteAll(made);
                    postError(listener, "Cancelled", true);
                    return -1;
                }
                // No encoder for that container, or a file FFmpeg cannot read:
                // put the originals back and let the merge take the old route.
                Log.w(TAG, "cannot convert " + in.getName() + " to ." + targetExt);
                for (int k = made.size() - 1; k >= 0; k--) {
                    src.set(swapped.get(k), originals.get(k));
                    //noinspection ResultOfMethodCallIgnored
                    made.get(k).delete();
                }
                return 0;
            }
            swapped.add(i);
            originals.add(in);
            made.add(converted);
            src.set(i, converted);
        }
        return made.size();
    }

    /** Write a small text file. */
    private static void writeText(File f, String text) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(text.getBytes("UTF-8"));
        }
    }

    // ------------------------------------------------------------------
    // FFmpeg execution
    // ------------------------------------------------------------------

    private interface ProgressSink {
        void accept(int percent);
    }

    /**
     * Cut one segment. Tries stream copy first (fast, lossless); on failure
     * retries with re-encode (native AAC -> .m4a when the container differs).
     * Returns the file actually written, or null on failure.
     */
    private File runFfmpegCut(File input, File output, long startMs, long endMs,
                              ProgressSink sink) {
        long durationMs = Math.max(1, endMs - startMs);
        String ss = msToTime(startMs);
        String t = msToTime(durationMs);

        // 1) stream copy
        String[] copyCmd = {"-ss", ss, "-i", input.getAbsolutePath(),
                "-t", t, "-c", "copy", "-y", output.getAbsolutePath()};
        if (exec(copyCmd, durationMs, sink)) return output;
        if (cancelled.get()) return null;

        // 2) re-encode fallback (native AAC encoder, always available)
        //noinspection ResultOfMethodCallIgnored
        output.delete();
        File encoded = reencodedTarget(output);
        String[] encCmd = {"-ss", ss, "-i", input.getAbsolutePath(),
                "-t", t, "-c:a", "aac", "-b:a", "192k", "-y", encoded.getAbsolutePath()};
        if (exec(encCmd, durationMs, sink)) {
            return encoded;
        }
        //noinspection ResultOfMethodCallIgnored
        encoded.delete();
        return null;
    }

    /** For a re-encode we can only guarantee AAC in m4a/mp4 containers. */
    private static File reencodedTarget(File output) {
        String outPath = output.getAbsolutePath().toLowerCase();
        if (outPath.endsWith(".m4a") || outPath.endsWith(".mp4")) {
            return output;
        }
        return new File(output.getAbsolutePath().replaceAll("\\.[^.]+$", "") + ".m4a");
    }

    /**
     * Re-encode one whole file into `output`, a container named by `targetExt`.
     * Only the minority formats of a mixed-format merge ever come through here,
     * which is what keeps the merge itself a stream copy. Null when FFmpeg
     * cannot write that container.
     */
    private File runFfmpegConvert(File input, File output, String targetExt,
                                  long expectedMs, ProgressSink sink) {
        String[] enc = encoderFor(targetExt);
        List<String> args = new ArrayList<>();
        args.add("-i");
        args.add(input.getAbsolutePath());
        args.add("-vn"); // cover art / video streams are not the audio being joined
        args.add("-c:a");
        args.add(enc[0]);
        if (enc[1] != null) {
            args.add("-b:a");
            args.add(enc[1]);
        }
        args.add("-y");
        args.add(output.getAbsolutePath());
        if (exec(args.toArray(new String[0]), expectedMs, sink)) return output;
        //noinspection ResultOfMethodCallIgnored
        output.delete();
        return null;
    }

    /**
     * Encoder for a target container, with its bitrate (null for the lossless
     * ones). The GPL build carries libmp3lame, libvorbis and libopus, and AAC
     * is FFmpeg's own encoder, so every mapping here is available.
     */
    private static String[] encoderFor(String ext) {
        switch (ext == null ? "" : ext) {
            case "mp3":
                return new String[]{"libmp3lame", "192k"};
            case "ogg":
            case "oga":
                return new String[]{"libvorbis", "192k"};
            case "opus":
                return new String[]{"libopus", "128k"};
            case "flac":
                return new String[]{"flac", null};
            case "wav":
                return new String[]{"pcm_s16le", null};
            case "aif":
            case "aiff":
            case "aifc":
                return new String[]{"pcm_s16be", null};
            // m4a / mp4 / aac / 3gp / caf, and any unexpected extension: AAC is
            // the encoder FFmpeg always has, and those are the containers it
            // belongs in.
            default:
                return new String[]{"aac", "192k"};
        }
    }

    /** Concat demuxer merge, stream copy with re-encode fallback. */
    private File runFfmpegConcat(File listFile, File output, long totalMs,
                                 ProgressSink sink) {
        String[] cmd = {"-f", "concat", "-safe", "0", "-i", listFile.getAbsolutePath(),
                "-c", "copy", "-y", output.getAbsolutePath()};
        if (exec(cmd, totalMs, sink)) return output;
        if (cancelled.get()) return null;
        // fallback: re-encode concat
        //noinspection ResultOfMethodCallIgnored
        output.delete();
        File encoded = reencodedTarget(output);
        String[] encCmd = {"-f", "concat", "-safe", "0", "-i", listFile.getAbsolutePath(),
                "-c:a", "aac", "-b:a", "192k", "-y", encoded.getAbsolutePath()};
        if (exec(encCmd, totalMs, sink)) {
            return encoded;
        }
        //noinspection ResultOfMethodCallIgnored
        encoded.delete();
        return null;
    }

    /**
     * Run one FFmpeg command asynchronously and block the worker thread
     * until completion, with progress + cancellation support.
     */
    private boolean exec(String[] args, long expectedMs, ProgressSink sink) {
        final Object lock = new Object();
        final boolean[] done = new boolean[1];
        final boolean[] ok = new boolean[1];

        StatisticsCallback stats = new StatisticsCallback() {
            @Override
            public void apply(Statistics s) {
                if (sink != null && expectedMs > 0) {
                    int p = (int) Math.min(100, (s.getTime() * 100f) / expectedMs);
                    sink.accept(Math.max(0, p));
                }
            }
        };

        FFmpegSession session = FFmpegKit.executeWithArgumentsAsync(args,
                new FFmpegSessionCompleteCallback() {
                    @Override
                    public void apply(FFmpegSession completed) {
                        synchronized (lock) {
                            ok[0] = ReturnCode.isSuccess(completed.getReturnCode());
                            done[0] = true;
                            lock.notifyAll();
                        }
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

    private static String quote(String s) {
        if (s == null || s.isEmpty()) return "''";
        if (s.matches("[a-zA-Z0-9_./:=\\-]+")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String msToTime(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        long msPart = ms % 1000;
        return String.format("%d:%02d:%02d.%03d", h, m, s, msPart);
    }

    // ------------------------------------------------------------------
    // Main-thread callbacks
    // ------------------------------------------------------------------

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
