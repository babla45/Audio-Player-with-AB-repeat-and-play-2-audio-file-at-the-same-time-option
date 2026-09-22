package com.example.aud_player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Foreground service that owns the Compress tab's encode job.
 *
 * <p>The job used to run on the editor activity's own engine, which made it a
 * hostage of that screen: pressing Home left the process with no foreground
 * component, so the low-memory killer was free to take it (ffmpeg and all), and
 * closing the screen cancelled it outright. Running it here instead gives the
 * encode the process priority a long job needs, so it carries on while the app
 * is in the background and can report progress and completion through a
 * notification.
 *
 * <p>Service and activity are in the same process, so the editor observes the
 * job through {@link #setObserver} instead of a binder. The service is
 * <em>started</em>, not bound, so it also outlives the activity that asked for
 * it. The split/merge engine is untouched and still lives on the activity.
 */
public class VideoCompressService extends Service {

    private static final String TAG = "VideoCompressService";

    /** Posted while the encode runs: silent, ongoing, with a live bar. */
    private static final String PROGRESS_CHANNEL_ID = "VideoCompressProgress";
    /** Posted once, when a job that left the screen finishes. */
    private static final String RESULT_CHANNEL_ID = "VideoCompressResult";
    private static final int PROGRESS_NOTIFICATION_ID = 2;
    private static final int RESULT_NOTIFICATION_ID = 3;

    public static final String ACTION_START = "com.example.aud_player.action.START_COMPRESS";
    public static final String ACTION_CANCEL = "com.example.aud_player.action.CANCEL_COMPRESS";

    private static final String EXTRA_URIS = "uris";
    private static final String EXTRA_TITLES = "titles";
    private static final String EXTRA_SIZES = "sizes";
    private static final String EXTRA_MAX_HEIGHT = "max_height";
    private static final String EXTRA_FPS = "fps";
    private static final String EXTRA_CRF = "crf";
    private static final String EXTRA_PRESET = "preset";

    // ------------------------------------------------------------------
    // The activity's side of the conversation
    // ------------------------------------------------------------------

    /**
     * What the running job reports to whoever is watching. Every call arrives
     * on the main thread, so an observer can touch its views directly.
     */
    public interface Observer {
        /** Whole-job progress, 0-100, or -1 while the engine is still preparing. */
        void onProgress(int percent);

        /** One file's own progress, {@code index} into the list the job was started with. */
        void onFileProgress(int index, int percent);

        /** Called exactly once, when the job ends for any reason. */
        void onFinished(Result result);
    }

    /** How the job ended: the files it wrote, what it had to compromise on, or why it failed. */
    public static final class Result {
        /** The written files, in list order; empty when nothing came out. */
        public final List<File> outputs;
        /** Anything the engine had to skip or re-encode, for the result dialog. */
        public final List<String> warnings;
        /** Titles of the sources, so the savings summary can be weighed off-screen. */
        public final List<String> originalTitles;
        /** Sizes of the sources in bytes, 0 where the provider would not say. */
        public final long[] originalSizes;
        /** Failure message, or null when the job succeeded. */
        public final String error;
        /** True when the user cancelled rather than the encode failing. */
        public final boolean cancelled;

        Result(List<File> outputs, List<String> warnings, List<String> originalTitles,
               long[] originalSizes, String error, boolean cancelled) {
            this.outputs = outputs != null ? outputs : Collections.emptyList();
            this.warnings = warnings != null ? warnings : Collections.emptyList();
            this.originalTitles = originalTitles != null ? originalTitles : Collections.emptyList();
            this.originalSizes = originalSizes != null ? originalSizes : new long[0];
            this.error = error;
            this.cancelled = cancelled;
        }
    }

    // Same process, so the activity can simply hold on to these.
    private static VideoCompressService instance;
    private static Observer observer;
    private static Result pendingResult;
    private static boolean backgroundMode;

    private final FFmpegVideoEditor editor = new FFmpegVideoEditor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean jobRunning = false;
    /** Set in onDestroy(): the callbacks must not touch a dead service. */
    private volatile boolean destroyed = false;
    private int fileCount = 1;
    private int lastPercent = Integer.MIN_VALUE;
    /** Last values posted, so the overlay can be restored after a rotation. */
    private int lastFileIndex = -1;
    private int lastFilePercent;
    /** The engine's notes, handed over with the result. */
    private List<String> lastWarnings = Collections.emptyList();

    // ------------------------------------------------------------------
    // Static entry points used by the editor activity
    // ------------------------------------------------------------------

    /**
     * Hand a compress job to the service. The videos are passed by Uri; the
     * activity's list supplies the titles and sizes so the savings summary
     * still works when the job finishes after that screen is gone.
     */
    public static void start(Context context, List<Uri> uris,
                             FFmpegVideoEditor.CompressOptions options,
                             List<String> titles, long[] sizes) {
        if (context == null || uris == null || uris.isEmpty()) return;
        FFmpegVideoEditor.CompressOptions o =
                options != null ? options : new FFmpegVideoEditor.CompressOptions();
        Intent i = new Intent(context, VideoCompressService.class);
        i.setAction(ACTION_START);
        i.putParcelableArrayListExtra(EXTRA_URIS, new ArrayList<>(uris));
        i.putStringArrayListExtra(EXTRA_TITLES,
                new ArrayList<>(titles != null ? titles : Collections.<String>emptyList()));
        i.putExtra(EXTRA_SIZES, sizes != null ? sizes : new long[0]);
        i.putExtra(EXTRA_MAX_HEIGHT, o.maxHeight);
        i.putExtra(EXTRA_FPS, o.fps);
        i.putExtra(EXTRA_CRF, o.crf);
        i.putExtra(EXTRA_PRESET, o.preset);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Throwable t) {
            Log.e(TAG, "could not start the compress service", t);
        }
    }

    /** Attach (or clear, with null) the watching activity. */
    public static void setObserver(Observer o) {
        observer = o;
    }

    /** True while a compress job is running, whether or not this process is on screen. */
    public static boolean isRunning() {
        VideoCompressService s = instance;
        return s != null && s.jobRunning;
    }

    /**
     * True once the user has sent the job to the background: the job keeps
     * going but the editor should not put its blocking overlay back up.
     */
    public static boolean isBackgroundMode() {
        return backgroundMode;
    }

    /** Tell the service the user is leaving the screen and wants the job to carry on. */
    public static void setBackgroundMode(boolean on) {
        backgroundMode = on;
    }

    /** Stop the job, whoever asked (the overlay's Cancel or the notification's). */
    public static void cancel() {
        VideoCompressService s = instance;
        if (s != null) {
            s.main.post(s::cancelJob);
        }
    }

    /** Take the last finished result, clearing it. Null when there is none. */
    public static Result peekResult() {
        Result r = pendingResult;
        pendingResult = null;
        return r;
    }

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_CANCEL.equals(action)) {
            cancelJob();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            // Nothing to do (a bare restart): do not sit in the foreground.
            if (!jobRunning) stopForegroundAndSelf();
            return START_NOT_STICKY;
        }
        if (jobRunning) return START_NOT_STICKY;   // one encode at a time

        ArrayList<Uri> uris = intent.getParcelableArrayListExtra(EXTRA_URIS);
        if (uris == null || uris.isEmpty()) {
            stopForegroundAndSelf();
            return START_NOT_STICKY;
        }
        ArrayList<String> titles = intent.getStringArrayListExtra(EXTRA_TITLES);
        long[] sizes = intent.getLongArrayExtra(EXTRA_SIZES);
        FFmpegVideoEditor.CompressOptions opts = new FFmpegVideoEditor.CompressOptions(
                intent.getIntExtra(EXTRA_MAX_HEIGHT, 720),
                intent.getIntExtra(EXTRA_FPS, 30),
                intent.getIntExtra(EXTRA_CRF, 28),
                intent.getStringExtra(EXTRA_PRESET));

        // CRITICAL: go foreground before any work, or the system kills us.
        jobRunning = true;
        backgroundMode = false;
        fileCount = uris.size();
        lastPercent = Integer.MIN_VALUE;
        lastFileIndex = -1;
        lastFilePercent = 0;
        lastWarnings = Collections.emptyList();
        startForeground(PROGRESS_NOTIFICATION_ID,
                buildProgressNotification("Preparing…", -1, 0));

        beginJob(uris, titles, sizes, opts);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;   // started, never bound
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        destroyed = true;
        // Safe when the job already ended; if the system is tearing us down
        // mid-encode it stops the worker and ffmpeg with it.
        editor.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // The job
    // ------------------------------------------------------------------

    private void beginJob(final List<Uri> uris, final List<String> titles, final long[] sizes,
                          final FFmpegVideoEditor.CompressOptions opts) {
        final List<String> originals = titles != null
                ? new ArrayList<>(titles) : new ArrayList<String>();
        final long[] originalSizes = sizes != null ? sizes : new long[0];

        editor.compressFromUri(getApplicationContext(), uris, opts,
                new FFmpegVideoEditor.EditListener() {
                    // these callbacks come from the engine's worker thread
                    @Override
                    public void onProgress(int percent) {
                        main.post(() -> applyProgress(percent));
                    }

                    @Override
                    public void onFileProgress(int index, int percent) {
                        main.post(() -> applyFileProgress(index, percent));
                    }

                    @Override
                    public void onWarnings(List<String> notes) {
                        // arrives on the worker thread just before the result,
                        // so handing it to the listener below is enough
                        lastWarnings = notes != null ? notes : Collections.<String>emptyList();
                    }

                    @Override
                    public void onSuccess(List<File> outputs) {
                        finish(new Result(outputs, lastWarnings, originals, originalSizes,
                                null, false));
                    }

                    @Override
                    public void onError(String message, boolean cancelled) {
                        finish(new Result(null, lastWarnings, originals, originalSizes,
                                message, cancelled));
                    }
                });
    }

    private void cancelJob() {
        if (!jobRunning) {
            stopForegroundAndSelf();
            return;
        }
        setProgressText("Cancelling…");
        editor.cancel();
    }

    private void applyProgress(int percent) {
        if (!jobRunning) return;
        if (percent == lastPercent) return;      // the bar moved by less than a percent
        lastPercent = percent;
        updateProgressNotification(percent);
        Observer o = observer;
        if (o != null) {
            try {
                o.onProgress(percent);
            } catch (Throwable t) {
                Log.w(TAG, "observer progress failed", t);
            }
        }
    }

    private void applyFileProgress(int index, int percent) {
        if (!jobRunning) return;
        lastFileIndex = index;
        lastFilePercent = percent;
        updateProgressNotification(lastPercent);
        Observer o = observer;
        if (o != null) {
            try {
                o.onFileProgress(index, percent);
            } catch (Throwable t) {
                Log.w(TAG, "observer file progress failed", t);
            }
        }
    }

    private void finish(final Result result) {
        main.post(() -> {
            if (!jobRunning || destroyed) return;   // already ended (cancel raced us)
            jobRunning = false;
            pendingResult = result;

            Observer o = observer;
            if (o != null) {
                try {
                    o.onFinished(result);
                } catch (Throwable t) {
                    Log.w(TAG, "observer finish failed", t);
                }
            }
            // A job the user deliberately sent away owes them a notification,
            // even if the editor happens to still be alive in the background.
            if (backgroundMode || o == null) {
                postResultNotification(result);
            }
            backgroundMode = false;
            stopForegroundAndSelf();
        });
    }

    private void stopForegroundAndSelf() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable t) {
            Log.w(TAG, "stopForeground failed", t);
        }
        stopSelf();
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;

        NotificationChannel progress = new NotificationChannel(
                PROGRESS_CHANNEL_ID, "Video compression",
                NotificationManager.IMPORTANCE_LOW);
        progress.setDescription("Progress while a video is being compressed");
        progress.setShowBadge(false);
        // silent: a notification that beeps at every percent would be unusable
        progress.setSound(null, null);
        progress.enableVibration(false);
        manager.createNotificationChannel(progress);

        NotificationChannel result = new NotificationChannel(
                RESULT_CHANNEL_ID, "Compression finished",
                NotificationManager.IMPORTANCE_DEFAULT);
        result.setDescription("Tells you when a background compression has finished");
        result.setShowBadge(true);
        manager.createNotificationChannel(result);
    }

    /** Tapping the notification brings the editor (and its result) back. */
    private PendingIntent editorIntent() {
        Intent open = new Intent(this, VideoEditorActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildProgressNotification(String text, int percent, int fileIndex) {
        Intent cancel = new Intent(this, VideoCompressService.class);
        cancel.setAction(ACTION_CANCEL);
        PendingIntent cancelPi = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = fileCount > 1
                ? "Compressing video " + Math.max(1, fileIndex + 1) + " of " + fileCount
                : "Compressing video";

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, PROGRESS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_video_edit)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(editorIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .addAction(0, "Cancel", cancelPi);
        if (percent >= 0) {
            b.setProgress(100, percent, false);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    private void updateProgressNotification(int percent) {
        if (!jobRunning) return;
        String text = percent < 0 ? "Preparing…"
                : (percent >= 100 ? "Finishing…" : percent + "%");
        if (lastFileIndex >= 0 && lastFilePercent > 0 && lastFilePercent < 100) {
            text = "File " + (lastFileIndex + 1) + " • " + lastFilePercent + "%";
        }
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(PROGRESS_NOTIFICATION_ID,
                        buildProgressNotification(text, percent, Math.max(0, lastFileIndex)));
            }
        } catch (Throwable t) {
            Log.w(TAG, "progress notification failed", t);
        }
    }

    private void setProgressText(String text) {
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(PROGRESS_NOTIFICATION_ID,
                        buildProgressNotification(text, -1, Math.max(0, lastFileIndex)));
            }
        } catch (Throwable t) {
            Log.w(TAG, "progress notification failed", t);
        }
    }

    /** The "it finished while you were away" notification. */
    private void postResultNotification(Result result) {
        String title;
        String text;
        if (result.cancelled) {
            title = "Compression cancelled";
            text = "The videos you already compressed were kept.";
        } else if (result.error != null) {
            title = "Compression failed";
            text = result.error;
        } else if (result.outputs.isEmpty()) {
            title = "Nothing was compressed";
            text = "Your originals are untouched.";
        } else {
            int n = result.outputs.size();
            title = n == 1 ? "Compression finished" : n + " videos compressed";
            File dir = result.outputs.get(0).getParentFile();
            text = dir != null ? "Saved to " + dir.getName() : "Saved";
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_video_edit)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(editorIntent())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(RESULT_NOTIFICATION_ID, b.build());
            }
        } catch (Throwable t) {
            Log.w(TAG, "result notification failed", t);
        }
    }
}
