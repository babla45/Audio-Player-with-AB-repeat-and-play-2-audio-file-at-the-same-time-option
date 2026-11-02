package com.example.aud_player;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class AudioSplitRuntimeTest {

    @Test
    public void splitsPcmAudioOnDevice() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File input = new File(context.getCacheDir(), "audio_split_test.wav");
        writeSilentWav(input, 2, 44100);

        FFmpegAudioEditor editor = new FFmpegAudioEditor();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<List<File>> outputs = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        List<FFmpegAudioEditor.Segment> segments = new ArrayList<>();
        segments.add(new FFmpegAudioEditor.Segment(0, 1000));
        segments.add(new FFmpegAudioEditor.Segment(1000, 2000));

        editor.runJob(context, input, FFmpegAudioEditor.MODE_SEPARATE, segments,
                "runtime_test", new FFmpegAudioEditor.EditListener() {
                    @Override
                    public void onProgress(int percent) {
                    }

                    @Override
                    public void onSuccess(List<File> result) {
                        outputs.set(result);
                        finished.countDown();
                    }

                    @Override
                    public void onError(String message, boolean cancelled) {
                        failure.set(message);
                        finished.countDown();
                    }
                });

        assertTrue("Timed out waiting for split result", finished.await(90, TimeUnit.SECONDS));
        editor.release();
        assertTrue("Split failed: " + failure.get(), failure.get() == null);
        assertNotNull(outputs.get());
        assertTrue("Expected two split files", outputs.get().size() == 2);
        for (File output : outputs.get()) {
            assertTrue("Missing/empty split output: " + output, output.isFile() && output.length() > 44);
            output.delete();
        }
        input.delete();
    }

    private static void writeSilentWav(File file, int seconds, int sampleRate) throws Exception {
        int dataBytes = seconds * sampleRate * 2;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[] {'R', 'I', 'F', 'F'});
            writeInt(out, 36 + dataBytes);
            out.write(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            writeInt(out, 16);
            writeShort(out, 1);
            writeShort(out, 1);
            writeInt(out, sampleRate);
            writeInt(out, sampleRate * 2);
            writeShort(out, 2);
            writeShort(out, 16);
            out.write(new byte[] {'d', 'a', 't', 'a'});
            writeInt(out, dataBytes);
            byte[] silence = new byte[8192];
            for (int remaining = dataBytes; remaining > 0; remaining -= silence.length) {
                out.write(silence, 0, Math.min(remaining, silence.length));
            }
        }
    }

    private static void writeShort(FileOutputStream out, int value) throws Exception {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeInt(FileOutputStream out, int value) throws Exception {
        writeShort(out, value & 0xFFFF);
        writeShort(out, (value >>> 16) & 0xFFFF);
    }
}
