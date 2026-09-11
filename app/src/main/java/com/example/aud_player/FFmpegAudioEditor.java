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
import java.util.List;
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

    public static final int MODE_CUT = 0;        // one segment -> one output
    public static final int MODE_SEPARATE = 1;   // N segments -> N outputs
    public static final int MODE_MERGE = 2;      // N segments -> 1 merged output

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

    /** Copy the source URI to a private cache file; FFmpeg needs a real path. */
    public File copyToTemp(Context context, Uri source) throws IOException {
        File tempDir = new File(context.getCacheDir(), "editor_temp");
        if (!tempDir.exists()) tempDir.mkdirs();
        cleanupDir(tempDir); // no stale temp files

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

    /** Delete the temp source file (and folder) after processing. */
    public void cleanupTemp(File temp) {
        if (temp != null) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            File parent = temp.getParentFile();
            if (parent != null) cleanupDir(parent);
        }
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
     * Output dir for edited audio: <shared storage>/aud_player/edited when
     * writable (visible folder, per user requirement), falling back to the
     * app-specific external files dir.
     */
    public static File getOutputDir(Context context) {
        try {
            File primary = new File(Environment.getExternalStorageDirectory(),
                    "aud_player/edited");
            if ((primary.exists() || primary.mkdirs()) && primary.canWrite()) {
                return primary;
            }
        } catch (Exception ignored) {
        }
        File fallback = context.getExternalFilesDir("edited");
        if (fallback != null && (fallback.exists() || fallback.mkdirs())) {
            return fallback;
        }
        File internal = new File(context.getFilesDir(), "edited");
        //noinspection ResultOfMethodCallIgnored
        internal.mkdirs();
        return internal;
    }

    /** Generate a non-clobbering output file inside the output dir. */
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
     * MODE_CUT      : segments must contain exactly 1 entry; output is a
     *                 single file "<base>.<ext>".
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
        executor.execute(() -> doJob(context, input, mode, segments, baseName, listener));
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
        if (source == null || segments == null || segments.isEmpty()) {
            postError(listener, "Nothing to process", false);
            return;
        }
        cancelled.set(false);
        executor.execute(() -> {
            File temp = null;
            try {
                postProgress(listener, -1);
                temp = copyToTemp(context, source);
                doJob(context, temp, mode, segments, baseName, listener);
            } catch (Throwable e) {
                // Throwable (not Exception): a NoClassDefFoundError or other
                // linkage error must surface as onError, never crash the app
                Log.e(TAG, "edit job failed", e);
                postError(listener, "Error: " + e.getMessage(), false);
            } finally {
                cleanupTemp(temp);
            }
        });
    }

    private void doJob(Context context, File input, int mode,
                       List<Segment> segments, String baseName,
                       EditListener listener) {
        File workDir = new File(input.getParentFile(), "parts");
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();
        List<File> produced = new ArrayList<>();
        try {
                String ext = extOf(input);
                long totalMs = 0;
                for (Segment s : segments) totalMs += Math.max(1, s.durationMs());
                int stepCount = (mode == MODE_MERGE) ? segments.size() + 1 : segments.size();

                for (int i = 0; i < segments.size(); i++) {
                    if (cancelled.get()) {
                        deleteAll(produced);
                        postError(listener, "Cancelled", true);
                        return;
                    }
                    Segment s = segments.get(i);

                    File target;
                    if (mode == MODE_SEPARATE) {
                        target = uniqueOutput(context, baseName + "_part" + (i + 1), ext);
                    } else if (mode == MODE_CUT) {
                        target = uniqueOutput(context, baseName, ext);
                    } else {
                        target = new File(workDir, "p" + i + "." + ext);
                    }

                    final int idx = i;
                    File result = runFfmpegCut(input, target, s.startMs, s.endMs,
                            percent -> postProgress(listener,
                                    (int) (100f * (idx + percent / 100f) / stepCount)));

                    if (cancelled.get()) {
                        //noinspection ResultOfMethodCallIgnored
                        target.delete();
                        deleteAll(produced);
                        postError(listener, "Cancelled", true);
                        return;
                    }
                    if (result == null) {
                        //noinspection ResultOfMethodCallIgnored
                        target.delete();
                        deleteAll(produced);
                        postError(listener, "FFmpeg failed on segment " + (idx + 1), false);
                        return;
                    }
                    produced.add(result);
                }

                if (mode == MODE_MERGE) {
                    if (cancelled.get()) {
                        deleteAll(produced);
                        postError(listener, "Cancelled", true);
                        return;
                    }
                    File merged = uniqueOutput(context, baseName + "_merged", ext);
                    StringBuilder list = new StringBuilder();
                    for (File f : produced) {
                        list.append("file '")
                                .append(f.getAbsolutePath().replace("'", "'\\''"))
                                .append("'\n");
                    }
                    File listFile = new File(workDir, "concat.txt");
                    writeText(listFile, list.toString());
                    File mergedResult = runFfmpegConcat(listFile, merged, totalMs,
                            percent -> postProgress(listener, percent));
                    //noinspection ResultOfMethodCallIgnored
                    listFile.delete();
                    if (cancelled.get()) {
                        //noinspection ResultOfMethodCallIgnored
                        merged.delete();
                        deleteAll(produced);
                        postError(listener, "Cancelled", true);
                        return;
                    }
                    if (mergedResult == null) {
                        //noinspection ResultOfMethodCallIgnored
                        merged.delete();
                        deleteAll(produced);
                        postError(listener, "FFmpeg merge failed", false);
                        return;
                    }
                    produced = new ArrayList<>();
                    produced.add(merged);
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
