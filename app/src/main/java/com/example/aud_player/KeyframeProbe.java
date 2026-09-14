package com.example.aud_player;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists the keyframe (sync sample) times of a video file, in milliseconds.
 *
 * A lossless (-c copy) cut can only start on a keyframe — that is a property
 * of the container, not a limitation of ffmpeg — so these times are the only
 * places the video splitter can actually cut. The timeline snaps its split
 * points to them, which is what makes a cut land where the split line is
 * drawn instead of a little earlier.
 *
 * Android's own MediaExtractor is used rather than ffprobe: it needs no extra
 * native package (the ffmpeg-kit "min" build ships no ffprobe binary) and it
 * reports the sync samples from the same container index ffmpeg reads when it
 * seeks, so the two agree.
 */
final class KeyframeProbe {

    interface Callback {
        /**
         * Keyframe times in ms, ascending — empty when the file has no video
         * track or could not be read. {@code videoMime} is the video track's
         * MIME type (e.g. "video/hevc") or null, used by the smart cut to
         * pick a matching encoder.
         */
        void onKeyframes(long[] timesMs, String videoMime);
    }

    private static final String TAG = "KeyframeProbe";

    /** One demux pass at a time; these are short and only run on file load. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "keyframe-probe");
        t.setDaemon(true);
        return t;
    });

    private KeyframeProbe() {}

    /** Probe off the main thread; the callback is delivered on the main thread. */
    static void probe(Context context, Uri uri, Callback callback) {
        if (context == null || uri == null || callback == null) return;
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        IO.execute(() -> {
            long[] times = new long[0];
            String mime = null;
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(app, uri, null);
                int track = -1;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    String m = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("video/")) {
                        track = i;
                        mime = m;
                        break;
                    }
                }
                if (track >= 0) {
                    extractor.selectTrack(track);
                    List<Long> list = new ArrayList<>();
                    while (true) {
                        long timeUs = extractor.getSampleTime();
                        if (timeUs < 0) break; // end of stream
                        if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            // Round UP to the next ms (never truncate): the cut
                            // command runs -ss with this time, and -ss before -i
                            // with -c copy seeks to the keyframe at-or-BEFORE the
                            // seek point. A truncated time can sit a fraction of
                            // a ms before the very keyframe it came from, which
                            // made the cut start at the PREVIOUS keyframe — a
                            // whole GOP earlier than the pointer.
                            list.add((timeUs + 999) / 1000);
                        }
                        if (!extractor.advance()) break;
                    }
                    times = new long[list.size()];
                    for (int i = 0; i < times.length; i++) times[i] = list.get(i);
                }
                Log.i(TAG, "keyframes=" + times.length + " mime=" + mime
                        + (times.length > 0 ? " first=" + times[0] + "ms last="
                        + times[times.length - 1] + "ms" : ""));
            } catch (Throwable t) {
                Log.w(TAG, "keyframe probe failed for " + uri, t);
            } finally {
                try {
                    extractor.release();
                } catch (Exception ignored) {
                }
            }
            final long[] resultTimes = times;
            final String resultMime = mime;
            main.post(() -> callback.onKeyframes(resultTimes, resultMime));
        });
    }
}
