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
 * Reads what the editor needs to know about a video before touching it:
 *
 * <ul>
 * <li><b>Keyframe (sync sample) times.</b> A lossless {@code -c copy} cut can
 *     only start on a keyframe - that is a property of the container, not a
 *     limitation of ffmpeg - so these times are the only positions the
 *     splitter may cut at. The timeline snaps its markers onto them, which is
 *     what makes the drawn cut line and the written cut agree; without it a
 *     part carries the previous GOP along and a re-joined video repeats a
 *     second of video and audio at every seam.</li>
 * <li><b>The stream signature</b> (codec, picture size, audio format). A copy
 *     cannot change any of these, so two videos may only end up in one output
 *     file when they match; the editor checks this before it starts writing
 *     instead of producing a file that plays wrong.</li>
 * </ul>
 *
 * <p>Android's own MediaExtractor is used rather than ffprobe: it needs no
 * extra native package (the ffmpeg-kit "min" build ships no ffprobe binary)
 * and it reports the sync samples from the same container index ffmpeg reads
 * when it seeks, so the two agree.
 */
final class KeyframeProbe {

    interface Callback {
        /**
         * @param timesMs   keyframe times in ms, ascending - empty when the
         *                  file has no video track or could not be read
         * @param videoMime the video track's MIME type (e.g. "video/hevc") or null
         */
        void onKeyframes(long[] timesMs, String videoMime);
    }

    /** Everything the probe can tell about one file. */
    static final class Info {
        long[] keyframesMs = new long[0];
        String videoMime;
        String audioMime;
        int width;
        int height;
        int rotationDegrees;
        int sampleRate;
        int channelCount;
        boolean hasVideo;
        boolean hasAudio;
        /** True when the file could be opened at all. */
        boolean readable;

        boolean hasKeyframes() {
            return keyframesMs != null && keyframesMs.length > 0;
        }
    }

    interface InfoCallback {
        void onInfo(Info info);
    }

    private static final String TAG = "KeyframeProbe";

    /** One demux pass at a time; these are short and only run on file load. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "keyframe-probe");
        t.setDaemon(true);
        return t;
    });

    private KeyframeProbe() {
    }

    /** Probes off the main thread; the callback is delivered on the main thread. */
    static void probe(Context context, Uri uri, Callback callback) {
        if (callback == null) return;
        probeInfo(context, uri, info -> callback.onKeyframes(info.keyframesMs, info.videoMime));
    }

    /** Full probe: keyframes plus the stream signature. */
    static void probeInfo(Context context, Uri uri, InfoCallback callback) {
        if (context == null || uri == null || callback == null) return;
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        final Handler main = new Handler(Looper.getMainLooper());
        IO.execute(() -> {
            final Info info = new Info();
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(app, uri, null);
                info.readable = true;
                int videoTrack = -1;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat fmt = extractor.getTrackFormat(i);
                    String mime = fmt.getString(MediaFormat.KEY_MIME);
                    if (mime == null) continue;
                    if (mime.startsWith("video/") && videoTrack < 0) {
                        videoTrack = i;
                        info.hasVideo = true;
                        info.videoMime = mime;
                        info.width = intOr(fmt, MediaFormat.KEY_WIDTH, 0);
                        info.height = intOr(fmt, MediaFormat.KEY_HEIGHT, 0);
                        info.rotationDegrees = intOr(fmt, MediaFormat.KEY_ROTATION, 0);
                    } else if (mime.startsWith("audio/") && info.audioMime == null) {
                        info.hasAudio = true;
                        info.audioMime = mime;
                        info.sampleRate = intOr(fmt, MediaFormat.KEY_SAMPLE_RATE, 0);
                        info.channelCount = intOr(fmt, MediaFormat.KEY_CHANNEL_COUNT, 0);
                    }
                }
                if (videoTrack >= 0) {
                    extractor.selectTrack(videoTrack);
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
                            // made the cut start at the PREVIOUS keyframe - a
                            // whole GOP earlier than the pointer.
                            list.add((timeUs + 999) / 1000);
                        }
                        if (!extractor.advance()) break;
                    }
                    info.keyframesMs = new long[list.size()];
                    for (int i = 0; i < info.keyframesMs.length; i++) {
                        info.keyframesMs[i] = list.get(i);
                    }
                }
                Log.i(TAG, "probe " + uri.getLastPathSegment() + ": keyframes="
                        + info.keyframesMs.length + " video=" + info.videoMime
                        + " audio=" + info.audioMime + " " + info.width + "x" + info.height
                        + (info.keyframesMs.length > 0 ? " first=" + info.keyframesMs[0]
                        + " last=" + info.keyframesMs[info.keyframesMs.length - 1] : ""));
            } catch (Throwable t) {
                Log.w(TAG, "probe failed for " + uri, t);
            } finally {
                try {
                    extractor.release();
                } catch (Exception ignored) {
                }
            }
            main.post(() -> callback.onInfo(info));
        });
    }

    private static int intOr(MediaFormat fmt, String key, int fallback) {
        try {
            return fmt.containsKey(key) ? fmt.getInteger(key) : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
