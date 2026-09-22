package com.example.aud_player;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.Locale;

/**
 * Plays a processed audio file straight from its path.
 *
 * <p>In-app rather than handed to another player: the files live under
 * aud_player/, which the app already has direct access to, so this avoids both
 * a content-provider round trip and a URI-permission prompt — the same reason
 * {@link VideoPlayerActivity} exists for video.
 *
 * <p>The player is independent of the app's main playback, exactly like the
 * Audio Editor's preview player, so opening a processed file never disturbs
 * what the library is playing.
 */
public class AudioPlayerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "extra_audio_path";
    public static final String EXTRA_TITLE = "extra_audio_title";

    /** Playhead refresh interval (~2 updates a second is plenty for audio). */
    private static final long TICK_MS = 500;

    private MediaPlayer player;
    private SeekBar seekBar;
    private ImageButton playBtn;
    private TextView currentTimeText, totalTimeText;
    private View controls;
    private TextView errorText;

    /** true once the file is prepared and the player can be driven. */
    private boolean prepared = false;
    /** true while the user is dragging the thumb, so ticks don't fight it. */
    private boolean userSeeking = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio_player);

        seekBar = findViewById(R.id.playerSeekBar);
        playBtn = findViewById(R.id.playerPlayBtn);
        currentTimeText = findViewById(R.id.playerCurrentTime);
        totalTimeText = findViewById(R.id.playerTotalTime);
        controls = findViewById(R.id.playerControls);
        errorText = findViewById(R.id.playerErrorText);
        TextView title = findViewById(R.id.playerTitle);

        ImageButton backBtn = findViewById(R.id.playerBackBtn);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> finish());
        }

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String name = getIntent().getStringExtra(EXTRA_TITLE);
        if (name == null || name.isEmpty()) {
            name = path != null ? new File(path).getName() : "";
        }
        title.setText(name);

        if (path == null || !new File(path).exists()) {
            showError("This file can no longer be found.\nIt may have been renamed or deleted.");
            return;
        }

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) currentTimeText.setText(formatTime(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (prepared) {
                    player.seekTo(sb.getProgress());
                }
                updateProgress();
            }
        });

        playBtn.setOnClickListener(v -> togglePlayback());

        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(path);
            player.setOnPreparedListener(mp -> {
                prepared = true;
                seekBar.setMax(Math.max(1, mp.getDuration()));
                totalTimeText.setText(formatTime(mp.getDuration()));
                mp.start();
                updatePlayIcon();
                handler.removeCallbacks(ticker);
                handler.post(ticker);
            });
            player.setOnCompletionListener(mp -> {
                updatePlayIcon();
                updateProgress();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                showError("This file could not be played.\nIt may be damaged or in an unsupported format.");
                return true;
            });
            player.prepareAsync();
        } catch (Throwable t) {
            showError("This file could not be played.\nIt may be damaged or in an unsupported format.");
        }
    }

    private void togglePlayback() {
        if (!prepared || player == null) return;
        try {
            if (player.isPlaying()) {
                player.pause();
            } else {
                // Replay from the start once the file has run to the end.
                if (player.getCurrentPosition() >= player.getDuration() - 200) {
                    player.seekTo(0);
                }
                player.start();
            }
        } catch (Throwable ignored) {
            return;
        }
        updatePlayIcon();
    }

    private void updatePlayIcon() {
        if (playBtn == null) return;
        boolean playing = false;
        try {
            playing = prepared && player != null && player.isPlaying();
        } catch (Throwable ignored) {}
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void updateProgress() {
        if (!prepared || player == null || userSeeking) return;
        try {
            int pos = player.getCurrentPosition();
            seekBar.setProgress(pos);
            currentTimeText.setText(formatTime(pos));
        } catch (Throwable ignored) {}
    }

    private void showError(String message) {
        prepared = false;
        handler.removeCallbacks(ticker);
        if (controls != null) controls.setVisibility(View.GONE);
        if (errorText != null) {
            errorText.setText(message);
            errorText.setVisibility(View.VISIBLE);
        }
    }

    /** "m:ss", or "h:mm:ss" for anything past an hour. */
    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Don't keep playing behind another screen or with the app in the
        // background (this screen owns no foreground service). The position is
        // kept, so coming back resumes where it left off.
        try {
            if (prepared && player != null && player.isPlaying()) {
                player.pause();
                updatePlayIcon();
            }
        } catch (Throwable ignored) {}
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prepared) {
            updateProgress();
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        if (player != null) {
            try {
                player.release();
            } catch (Throwable ignored) {}
            player = null;
        }
        prepared = false;
        super.onDestroy();
    }
}
