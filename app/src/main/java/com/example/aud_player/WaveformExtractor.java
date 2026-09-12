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
 *
 * Long files (an hour of lecture, a DJ set) get a fast SPARSE OVERVIEW pass
 * first: a short window every few seconds is probed by seeking, so the whole
 * timeline shows a real waveform within a couple of seconds instead of
 * filling in over a sequential decode that grows linearly with length. The
 * exact pass then runs as before and overwrites every bucket it decodes —
 * the estimates never mask real data. Files shorter than
 * {@link #OVERVIEW_MIN_US} skip the overview entirely and behave exactly as
 * they always did.
 */
public final class WaveformExtractor {

    private static final String TAG = "WaveformExtractor";
    /** Minimum bucket count — the original resolution (short files). */
    public static final int BUCKETS = 600;
    /**
     * Bucket ceiling for long files. 3000 doubles + ints is ~36KB per
     * source, and 600 buckets spread over a two-hour file would be one
     * bucket per 12 seconds — far too coarse to split against.
     */
    private static final int MAX_BUCKETS = 3000;
    /** One bucket per this many ms of audio, clamped to the range above. */
    private static final long BUCKET_MS = 500L;
    private static final long TIMEOUT_US = 10_000L;
    /**
     * Post a partial waveform after every decoded chunk of this audio
     * length — the first chunk paints almost immediately instead of
     * waiting for the whole song to decode.
     */
    private static final long CHUNK_US = 25_000_000L; // 25s per chunk
    /** First chunk is smaller so the very first paint is as fast as possible. */
    private static final long FIRST_CHUNK_US = 15_000_000L; // 15s
    /**
     * Files at least this long get the sparse overview pass. Below it a
     * sequential decode finishes in about the time the seeking would take,
     * so the overview would only add work — the exact pass is faster there.
     */
    private static final long OVERVIEW_MIN_US = 12 * 60_000_000L; // 12 min
    /** Bounded work: at most this many probe windows (each costs a seek). */
    private static final int OVERVIEW_MAX_WINDOWS = 200;
    /** Audio decoded per probe window. */
    private static final long OVERVIEW_WINDOW_US = 120_000L; // 120ms
    /**
     * Wall-clock budget for the whole overview pass. A decoder that stalls
     * after a seek must not turn "show me the shape quickly" into a slow
     * scan — whatever has been probed by then is kept.
     */
    private static final long OVERVIEW_BUDGET_MS = 8_000L;
    /** Give up on one probe window after this many empty output polls (~1s). */
    private static final int OVERVIEW_MAX_IDLE = 100;
    /**
     * Cap on decoded samples examined per file, so the aggregation loop
     * cannot grow with length: the stride widens on long files. RMS is
     * unaffected by sampling a subset evenly across each bucket.
     */
    private static final long MAX_SAMPLED_FRAMES = 4_000_000L;

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

    /**
     * Bucket count for a duration: the classic 600 up to ~5 minutes (so
     * short songs decode byte-for-byte as before), then one bucket per
     * {@link #BUCKET_MS} up to {@link #MAX_BUCKETS}.
     */
    public static int bucketsFor(long durationMs) {
        long b = Math.max(1L, durationMs) / BUCKET_MS;
        if (b <= BUCKETS) return BUCKETS;
        return (int) Math.min(MAX_BUCKETS, b);
    }

    /** Raw per-bucket accumulators (absolute PCM scale, 0..32767). */
    private static final class Acc {
        final double[] energy; // sum of squares
        final int[] count;

        Acc(int buckets) {
            energy = new double[buckets];
            count = new int[buckets];
        }
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

            int buckets = bucketsFor(durationUs / 1000);
            Acc acc = new Acc(buckets);

            // Long file: paint the WHOLE timeline from sparse probes before
            // the exact pass starts filling in (see the class comment).
            float[] sparse = null;
            if (durationUs >= OVERVIEW_MIN_US) {
                sparse = overview(extractor, codec, durationUs, buckets, callback);
                // rewind for the exact pass (a null overview changes nothing)
                try {
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    codec.flush();
                } catch (Throwable t) {
                    Log.w(TAG, "rewind after overview failed: " + t.getMessage());
                }
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int deadLoops = 0;
            long nextChunkUs = FIRST_CHUNK_US; // first small chunk: fast paint
            // Widen the sampling stride on long files so the aggregation
            // loop stays bounded (see MAX_SAMPLED_FRAMES).
            int frameStride = (int) Math.max(8L,
                    (durationUs / 1_000_000L) * (long) sampleRate / MAX_SAMPLED_FRAMES);

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
                                    sampleRate, channels, frameStride, acc);
                        }
                    }
                    // incremental update: post what is decoded so far
                    // (small first chunk, then 25s chunks) — stable values
                    // (no re-normalization), so already-drawn parts never
                    // jump or flicker
                    if (info.presentationTimeUs >= nextChunkUs
                            && !cancelled.get()) {
                        nextChunkUs = info.presentationTimeUs + CHUNK_US;
                        postPartial(callback, acc, sparse);
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

            return buildWaveform(acc, sparse);
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

    /**
     * Fast sparse overview for long files: probe a short window every
     * (duration / windows) of audio by seeking, so a whole-hour timeline
     * shows real audio within seconds instead of staying placeholder until
     * a sequential decode finishes. Each probe's RMS fills every bucket its
     * span covers. Returns null when nothing could be probed — the caller
     * then simply runs the exact pass on its own.
     */
    private float[] overview(MediaExtractor extractor, MediaCodec codec,
                             long durationUs, int buckets, Callback callback) {
        // At most one probe per second of audio, capped: the work (one seek
        // plus 120ms of decode each) must not grow with the file length.
        int windows = (int) Math.min(OVERVIEW_MAX_WINDOWS,
                Math.max(2L, durationUs / 1_000_000L));
        if (windows < 2) return null;
        float[] sparse = new float[buckets];
        long spanUs = Math.max(1L, durationUs / windows);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int filled = 0;
        long deadline = System.currentTimeMillis() + OVERVIEW_BUDGET_MS;
        try {
            for (int k = 0; k < windows && !cancelled.get(); k++) {
                if (System.currentTimeMillis() > deadline) break;
                long targetUs = k * spanUs;
                long endUs = targetUs + OVERVIEW_WINDOW_US;
                extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                codec.flush();

                double energy = 0;
                long count = 0;
                boolean eosFlag = false;
                boolean inputDone = false;
                int idle = 0;
                while (!eosFlag && !cancelled.get() && idle < OVERVIEW_MAX_IDLE
                        && System.currentTimeMillis() <= deadline) {
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
                                codec.queueInputBuffer(inIdx, 0, size,
                                        extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                    int outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (outIdx >= 0) {
                        idle = 0;
                        long pts = info.presentationTimeUs;
                        // the decoder replays pre-roll from the sync frame:
                        // only samples at/after the probe position count
                        if (info.size > 0 && pts >= targetUs && pts < endUs) {
                            ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                            if (outBuf != null) {
                                outBuf.order(ByteOrder.LITTLE_ENDIAN);
                                int pos = info.offset;
                                int limit = pos + info.size;
                                for (int i = pos; i + 1 < limit; i += 2) {
                                    short s = outBuf.getShort(i);
                                    energy += (double) s * s;
                                    count++;
                                }
                            }
                        }
                        eosFlag = (info.flags
                                & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        codec.releaseOutputBuffer(outIdx, false);
                        if (!eosFlag && pts >= endUs) break; // probe window done
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        idle = 0;
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        idle++;
                    }
                }

                if (count > 0) {
                    double rms = Math.sqrt(energy / count) / 32767d;
                    float v = (float) Math.min(1d, rms * 1.6d);
                    int b0 = bucketOf(targetUs, durationUs, buckets);
                    int b1 = bucketOf(targetUs + spanUs, durationUs, buckets);
                    for (int b = b0; b <= b1 && b < buckets; b++) sparse[b] = v;
                    filled++;
                }
                // show the probe results as they arrive (left to right)
                if (filled > 0 && (k % 24) == 23) postSparse(callback, sparse);
            }
        } catch (Throwable t) {
            // any seek/decode complaint: keep whatever was probed, the exact
            // pass that follows is the authority anyway
            Log.w(TAG, "overview pass stopped: " + t.getMessage());
        }
        return filled > 0 ? sparse : null;
    }

    private static int bucketOf(long ptsUs, long durationUs, int buckets) {
        int b = (int) (ptsUs * (buckets - 1) / Math.max(1L, durationUs));
        return Math.max(0, Math.min(buckets - 1, b));
    }

    /** Deliver the waveform decoded so far (main thread, cancel-safe). */
    private void postPartial(Callback callback, Acc acc, float[] sparse) {
        final float[] partial = buildWaveform(acc, sparse);
        main.post(() -> {
            if (!cancelled.get()) callback.onWaveformReady(partial);
        });
    }

    /** Deliver a sparse overview (main thread, cancel-safe). */
    private void postSparse(Callback callback, float[] sparse) {
        final float[] snapshot = sparse.clone();
        main.post(() -> {
            if (!cancelled.get()) callback.onWaveformReady(snapshot);
        });
    }

    /**
     * Add one output buffer's PCM samples into the right time buckets,
     * accumulating energy (sum of squares) per bucket for an RMS display.
     * RMS is used instead of a pure peak because heavily compressed music
     * peaks at full scale everywhere, which drew as a solid filled block.
     *
     * The bucket is carried incrementally (a position advanced by a
     * precomputed step) instead of being re-derived with a division for
     * every sample — on a long file that was tens of millions of divisions.
     */
    private static void accumulate(ByteBuffer buf, MediaCodec.BufferInfo info,
                                   long durationUs, int sampleRate, int channels,
                                   int frameStride, Acc acc) {
        int pos = info.offset;
        int limit = pos + info.size;
        long pts = info.presentationTimeUs;
        if (channels < 1) channels = 1;
        if (sampleRate < 1) sampleRate = 44100;
        try {
            buf.order(ByteOrder.LITTLE_ENDIAN);
            // sample a subset for speed on long files (RMS is still accurate)
            final int stepSamples = frameStride * channels; // in shorts
            // real duration of one step: frames * 1e6 / sampleRate
            final double stepUs = frameStride * 1_000_000d / sampleRate;
            final int buckets = acc.energy.length;
            final double bucketsPerUs = (buckets - 1) / (double) Math.max(1L, durationUs);
            double bucketPos = pts * bucketsPerUs;
            final double bucketStep = stepUs * bucketsPerUs;
            int i = pos;
            while (i + 1 < limit) {
                short s = buf.getShort(i);
                int bucket = (int) bucketPos;
                if (bucket >= 0 && bucket < buckets) {
                    acc.energy[bucket] += (double) s * s;
                    acc.count[bucket]++;
                }
                i += 2 * stepSamples;
                bucketPos += bucketStep;
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
     *
     * A bucket the exact pass has not reached yet keeps its SPARSE OVERVIEW
     * estimate (when there is one) instead of dropping to zero, so the
     * overview waveform stays on screen while the exact pass refines it.
     */
    private static float[] buildWaveform(Acc acc, float[] sparse) {
        final int n = acc.energy.length;
        float[] values = new float[n];
        for (int i = 0; i < n; i++) {
            if (acc.count[i] > 0) {
                double rms = Math.sqrt(acc.energy[i] / acc.count[i]) / 32767d;
                values[i] = (float) Math.min(1d, rms * 1.6d);
            } else if (sparse != null) {
                values[i] = sparse[i];
            }
        }
        float[] smoothed = new float[n];
        for (int i = 0; i < n; i++) {
            float a = values[Math.max(0, i - 1)];
            float b = values[i];
            float c = values[Math.min(n - 1, i + 1)];
            smoothed[i] = (a + 2 * b + c) / 4f;
        }
        return smoothed;
    }
}
