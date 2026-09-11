package com.example.aud_player;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Extracts a coarse waveform (RMS per bucket) from an audio URI by
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
    /**
     * Post a partial waveform after every decoded chunk of this audio
     * length — the first chunk paints almost immediately instead of
     * waiting for the whole song to decode.
     */
    private static final long CHUNK_US = 25_000_000L; // 25s per chunk
    /** First chunk is smaller so the very first paint is as fast as possible. */
    private static final long FIRST_CHUNK_US = 15_000_000L; // 15s

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
                result = decode(app, uri, callback);
            } catch (Throwable t) {
                Log.w(TAG, "waveform extraction failed: " + t.getMessage());
            }
            if (cancelled.get()) return;
            final float[] data = result;
            main.post(() -> callback.onWaveformReady(data));
        });
    }

    /** Raw per-bucket accumulators (absolute PCM scale, 0..32767). */
    private static final class Acc {
        final double[] energy = new double[BUCKETS]; // sum of squares
        final int[] count = new int[BUCKETS];
    }

    private float[] decode(Context context, Uri uri, Callback callback) throws Exception {
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
            // Real stream parameters — the pts advance per sample depends on
            // them; assuming 44.1kHz stereo made the waveform drift/shift on
            // any other file (start appeared delayed).
            int sampleRate = 44100;
            int channels = 2;
            try {
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            } catch (Exception ignored) {
            }
            try {
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            } catch (Exception ignored) {
            }

            Acc acc = new Acc();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int deadLoops = 0;
            long nextChunkUs = FIRST_CHUNK_US; // first small chunk: fast paint

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
                            accumulate(outBuf, info, durationUs,
                                    sampleRate, channels, acc);
                        }
                    }
                    // incremental update: post what is decoded so far
                    // (small first chunk, then 25s chunks) — stable values
                    // (no re-normalization), so already-drawn parts never
                    // jump or flicker
                    if (info.presentationTimeUs >= nextChunkUs
                            && !cancelled.get()) {
                        nextChunkUs = info.presentationTimeUs + CHUNK_US;
                        postPartial(callback, acc);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // decoded stream's real parameters override the container's
                    MediaFormat of = codec.getOutputFormat();
                    try {
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    } catch (Exception ignored) {
                    }
                    try {
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    } catch (Exception ignored) {
                    }
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone) {
                        // give the decoder a moment; loop guard above catches hangs
                    }
                }
            }

            return buildWaveform(acc);
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

    /** Deliver the waveform decoded so far (main thread, cancel-safe). */
    private void postPartial(Callback callback, Acc acc) {
        final float[] partial = buildWaveform(acc);
        main.post(() -> {
            if (!cancelled.get()) callback.onWaveformReady(partial);
        });
    }

    /**
     * Add one output buffer's PCM samples into the right time buckets,
     * accumulating energy (sum of squares) per bucket for an RMS display.
     * RMS is used instead of a pure peak because heavily compressed music
     * peaks at full scale everywhere, which drew as a solid filled block.
     */
    private static void accumulate(ByteBuffer buf, MediaCodec.BufferInfo info,
                                   long durationUs, int sampleRate, int channels,
                                   Acc acc) {
        int pos = info.offset;
        int limit = pos + info.size;
        long pts = info.presentationTimeUs;
        if (channels < 1) channels = 1;
        if (sampleRate < 1) sampleRate = 44100;
        try {
            buf.order(ByteOrder.LITTLE_ENDIAN);
            // sample a subset for speed on long files (RMS is still accurate)
            final int stepSamples = 8 * channels; // 8 frames
            // real duration of one step: frames * 1e6 / sampleRate
            final double stepUs = (stepSamples / (double) channels)
                    * 1_000_000d / sampleRate;
            int i = pos;
            while (i + 1 < limit) {
                short s = buf.getShort(i);
                double abs = Math.abs(s);
                double frac = pts / (double) durationUs;
                int bucket = (int) (frac * (BUCKETS - 1));
                if (bucket >= 0 && bucket < BUCKETS) {
                    acc.energy[bucket] += abs * abs;
                    acc.count[bucket]++;
                }
                i += 2 * stepSamples;
                pts += stepUs;
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Turn the raw accumulators into the display waveform: RMS per bucket,
     * scaled with headroom (x1.6, clamped to 1) so loud parts reach near the
     * top while quiet parts stay visibly lower, then lightly smoothed so
     * single loud samples don't spike hard. No global normalization — the
     * loudness relationship between tracks is preserved.
     */
    private static float[] buildWaveform(Acc acc) {
        float[] values = new float[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) {
            if (acc.count[i] == 0) continue;
            double rms = Math.sqrt(acc.energy[i] / acc.count[i]) / 32767d;
            values[i] = (float) Math.min(1d, rms * 1.6d);
        }
        float[] smoothed = new float[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) {
            float a = values[Math.max(0, i - 1)];
            float b = values[i];
            float c = values[Math.min(BUCKETS - 1, i + 1)];
            smoothed[i] = (a + 2 * b + c) / 4f;
        }
        return smoothed;
    }
}
