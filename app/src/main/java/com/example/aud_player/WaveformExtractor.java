package com.example.aud_player;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Extracts a coarse waveform (peak/RMS per bucket) from an audio URI by
 * decoding PCM with MediaExtractor + MediaCodec. Runs entirely off the main
 * thread; result is delivered on the main thread. Falls back to null when
 * decoding fails so the UI can draw a placeholder timeline.
 *
 * Memory stays bounded: samples are aggregated into buckets as they are
 * decoded, nothing is retained.
 */
public final class WaveformExtractor {

    private static final String TAG = "WaveformExtractor";
    /** Number of waveform buckets — enough resolution for a timeline view. */
    public static final int BUCKETS = 600;
    private static final long TIMEOUT_US = 10_000L;

    public interface Callback {
        /** data[i] in 0..1; null when extraction failed. */
        void onWaveformReady(float[] data);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());

    public void release() {
        cancelled.set(true);
        executor.shutdownNow();
    }

    public void extract(Context context, Uri uri, final Callback callback) {
        final Context app = context.getApplicationContext();
        executor.execute(() -> {
            float[] result = null;
            try {
                result = decode(app, uri);
            } catch (Throwable t) {
                Log.w(TAG, "waveform extraction failed: " + t.getMessage());
            }
            if (cancelled.get()) return;
            final float[] data = result;
            main.post(() -> callback.onWaveformReady(data));
        });
    }

    private float[] decode(Context context, Uri uri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }
            if (trackIndex < 0 || format == null) return null;

            extractor.selectTrack(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            long durationUs = 1000;
            try {
                durationUs = Math.max(1000, format.getLong(MediaFormat.KEY_DURATION));
            } catch (Exception ignored) {
            }

            // Aggregate absolute sample peaks into buckets.
            float[] buckets = new float[BUCKETS];
            int[] counts = new int[BUCKETS];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int deadLoops = 0;

            while (!outputDone && !cancelled.get()) {
                if (++deadLoops > 200_000) break; // safety valve on hangs

                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        int size = extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            codec.queueInputBuffer(inIdx, 0, size, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                        if (outBuf != null) {
                            accumulate(outBuf, info, durationUs, buckets, counts);
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone) {
                        // give the decoder a moment; loop guard above catches hangs
                    }
                }
                // INFO_OUTPUT_FORMAT_CHANGED: ignore, channel count from format
            }

            // Normalize peaks to 0..1 (peak of 32767 maps to 1.0).
            for (int i = 0; i < BUCKETS; i++) {
                if (counts[i] > 0) {
                    buckets[i] = Math.min(1f, buckets[i] / 32767f);
                }
            }
            // Light smoothing so single loud samples don't spike hard.
            float[] smoothed = new float[BUCKETS];
            for (int i = 0; i < BUCKETS; i++) {
                float a = buckets[Math.max(0, i - 1)];
                float b = buckets[i];
                float c = buckets[Math.min(BUCKETS - 1, i + 1)];
                smoothed[i] = (a + 2 * b + c) / 4f;
            }
            return smoothed;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {
            }
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** Add one output buffer's PCM peaks into the right time buckets. */
    private static void accumulate(ByteBuffer buf, MediaCodec.BufferInfo info,
                                   long durationUs, float[] buckets, int[] counts) {
        int pos = info.offset;
        int limit = pos + info.size;
        int bytesPerFrame = 2; // assume 16-bit PCM output
        long pts = info.presentationTimeUs;
        int channels = 2; // conservative; per-sample channel mixing is fine
        try {
            buf.order(ByteOrder.LITTLE_ENDIAN);
            // Bucket per ~4ms window of samples for speed on long files.
            int stepSamples = 8 * channels;
            int i = pos;
            while (i + 1 < limit) {
                short s = buf.getShort(i);
                float abs = Math.abs(s);
                double frac = pts / (double) durationUs;
                int bucket = (int) (frac * (BUCKETS - 1));
                if (bucket >= 0 && bucket < BUCKETS && abs > buckets[bucket]) {
                    buckets[bucket] = abs;
                    counts[bucket] = 1;
                }
                i += 2 * stepSamples;
                pts += (stepSamples * 1_000_000L) / (44100 * channels);
            }
        } catch (Exception ignored) {
        }
    }
}
