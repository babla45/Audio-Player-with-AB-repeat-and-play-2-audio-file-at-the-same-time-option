package com.example.aud_player;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

/**
 * Plays a processed video straight from its file path.
 * <p>Playback is in-app rather than handed to an external player: the files
 * live under aud_player/, which the app already has direct access to, so this
 * avoids both a content-provider round trip and any URI-permission prompt.
 */
public class VideoPlayerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "extra_video_path";
    public static final String EXTRA_TITLE = "extra_video_title";

    private VideoView videoView;
    private TextView errorText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

        videoView = findViewById(R.id.playerVideoView);
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
            showError("This video can no longer be found.\nIt may have been renamed or deleted.");
            return;
        }

        // Controls appear on tap and hide themselves, like the system player.
        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);

        videoView.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            videoView.setKeepScreenOn(true);
            videoView.start();
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            showError("This video could not be played.\nThe file may be damaged or in an unsupported format.");
            return true;
        });
        videoView.setOnCompletionListener(mp -> {
            // Stay on the last frame; tapping play restarts from the beginning.
            videoView.setKeepScreenOn(false);
        });

        videoView.setVideoPath(path);
    }

    private void showError(String message) {
        videoView.setVisibility(View.GONE);
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Don't keep playing audio behind another screen, and don't hold the
        // screen awake while we're not visible.
        if (videoView != null) {
            if (videoView.isPlaying()) {
                videoView.pause();
            }
            videoView.setKeepScreenOn(false);
        }
    }

    @Override
    protected void onDestroy() {
        if (videoView != null) {
            videoView.stopPlayback();
        }
        super.onDestroy();
    }
}
