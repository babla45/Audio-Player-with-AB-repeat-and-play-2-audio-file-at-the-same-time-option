package com.example.aud_player;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AudioPlayerApp";
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    private Button selectButton;
    private TextView fileNameText, currentTimeText, totalTimeText;
    private SeekBar seekBar;
    private MaterialButton playPauseButton, stopButton, menuButton;
    
    private MediaPlayer mediaPlayer = null;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable runnable;
    private Uri selectedAudioUri = null;
    private boolean isPermissionGranted = false;
    private boolean isPlaying = false;
    private CountDownTimer sleepTimer;
    private boolean timerActive = false;
    private TextView timerIndicator;
    private int pointA = -1;
    private int pointB = -1;
    private boolean abRepeatActive = false;
    private TextView abRepeatIndicator;

    // Activity result launcher for file picking
    private final ActivityResultLauncher<Intent> audioPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        selectedAudioUri = uri;
                        String fileName = getFileNameFromUri(uri);
                        fileNameText.setText(fileName);
                        
                        // Take persistent permission for this URI
                        try {
                            getContentResolver().takePersistableUriPermission(uri, 
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException e) {
                            Log.e(TAG, "Failed to take persistable URI permission", e);
                        }
                        
                        prepareMediaPlayer();
                    }
                }
            }
    );

    // Permission request launcher
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                isPermissionGranted = isGranted;
                if (isGranted) {
                    Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Permission denied. Cannot access audio files.", 
                                   Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize views
        initializeViews();
        
        // Setup listeners
        setupListeners();
        
        // Check for permissions
        checkPermissions();
    }
    
    private void initializeViews() {
        try {
            selectButton = findViewById(R.id.selectButton);
            fileNameText = findViewById(R.id.fileNameText);
            seekBar = findViewById(R.id.seekBar);
            currentTimeText = findViewById(R.id.currentTimeText);
            totalTimeText = findViewById(R.id.totalTimeText);
            playPauseButton = findViewById(R.id.playPauseButton);
            stopButton = findViewById(R.id.stopButton);
            menuButton = findViewById(R.id.menuButton);
            timerIndicator = findViewById(R.id.timerIndicator);
            abRepeatIndicator = findViewById(R.id.abRepeatIndicator);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing views", e);
            Toast.makeText(this, "Error initializing app", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void setupListeners() {
        selectButton.setOnClickListener(v -> {
            if (isPermissionGranted) {
                pickAudioFile();
            } else {
                checkPermissions();
                Toast.makeText(this, "Permission required to access files", 
                               Toast.LENGTH_SHORT).show();
            }
        });
        
        playPauseButton.setOnClickListener(v -> {
            if (mediaPlayer != null && selectedAudioUri != null) {
                try {
                    if (isPlaying) {
                        mediaPlayer.pause();
                        playPauseButton.setIconResource(R.drawable.ic_play);
                        isPlaying = false;
                    } else {
                        mediaPlayer.start();
                        playPauseButton.setIconResource(R.drawable.ic_pause);
                        isPlaying = true;
                        updateSeekBar();
                    }
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error with play/pause", e);
                    prepareMediaPlayer();
                }
            }
        });
        
        stopButton.setOnClickListener(v -> {
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                        playPauseButton.setIconResource(R.drawable.ic_play);
                        isPlaying = false;
                    }
                    mediaPlayer.reset();
                    seekBar.setProgress(0);
                    currentTimeText.setText("0:00");
                    
                    prepareMediaPlayer();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error stopping media player", e);
                    releaseMediaPlayer();
                    if (selectedAudioUri != null) {
                        initMediaPlayer();
                        prepareMediaPlayer();
                    }
                }
            }
        });
        
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    try {
                        mediaPlayer.seekTo(progress);
                        updateTimeText(progress, mediaPlayer.getDuration());
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Error seeking media player", e);
                    }
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        // Add the menu button listener
        menuButton.setOnClickListener(v -> showPopupMenu(v));
    }
    
    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // For Android 13+ use READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) 
                    == PackageManager.PERMISSION_GRANTED) {
                isPermissionGranted = true;
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
            // For older versions use READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    == PackageManager.PERMISSION_GRANTED) {
                isPermissionGranted = true;
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
    }
    
    private void pickAudioFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        
        try {
            audioPickerLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error launching audio picker", e);
            Toast.makeText(this, "Error opening file picker", Toast.LENGTH_SHORT).show();
        }
    }
    
    private String getFileNameFromUri(Uri uri) {
        try {
            String fileName = uri.getLastPathSegment();
            if (fileName != null && fileName.contains("/")) {
                fileName = fileName.substring(fileName.lastIndexOf("/") + 1);
            }
            return fileName != null ? fileName : "Unknown File";
        } catch (Exception e) {
            Log.e(TAG, "Error getting filename", e);
            return "Unknown File";
        }
    }
    
    private void initMediaPlayer() {
        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
        }
    }
    
    private void prepareMediaPlayer() {
        if (selectedAudioUri == null) return;
        
        // Initialize media player if null
        initMediaPlayer();
        
        try {
            mediaPlayer.reset();
            mediaPlayer.setDataSource(getApplicationContext(), selectedAudioUri);
            
            // Set listeners before preparing
            mediaPlayer.setOnPreparedListener(mp -> {
                try {
                    seekBar.setMax(mp.getDuration());
                    totalTimeText.setText(formatTime(mp.getDuration()));
                    currentTimeText.setText("0:00");
                    playPauseButton.setIconResource(R.drawable.ic_play);
                    isPlaying = false;
                } catch (Exception e) {
                    Log.e(TAG, "Error in onPrepared", e);
                }
            });
            
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                Toast.makeText(MainActivity.this, 
                    "Error playing this file", Toast.LENGTH_SHORT).show();
                releaseMediaPlayer();
                initMediaPlayer();
                playPauseButton.setIconResource(R.drawable.ic_play);
                isPlaying = false;
                return true; // Error handled
            });
            
            mediaPlayer.setOnCompletionListener(mp -> {
                try {
                    if (abRepeatActive) {
                        // Do nothing, as we'll handle this in the position checking
                    } else {
                        seekBar.setProgress(0);
                        currentTimeText.setText("0:00");
                        handler.removeCallbacks(runnable);
                        playPauseButton.setIconResource(R.drawable.ic_play);
                        isPlaying = false;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in onCompletion", e);
                }
            });
            
            mediaPlayer.prepareAsync();
            
        } catch (IOException e) {
            Log.e(TAG, "Error preparing media player", e);
            Toast.makeText(this, "Error loading audio file", Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            Log.e(TAG, "Media player error", e);
            Toast.makeText(this, "Error with media player", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void updateSeekBar() {
        if (mediaPlayer != null) {
            try {
                int currentPosition = mediaPlayer.getCurrentPosition();
                
                if (abRepeatActive && pointA != -1 && pointB != -1) {
                    if (currentPosition >= pointB) {
                        mediaPlayer.seekTo(pointA);
                        currentPosition = pointA;
                    }
                }
                
                seekBar.setProgress(currentPosition);
                updateTimeText(currentPosition, mediaPlayer.getDuration());
                
                if (mediaPlayer.isPlaying()) {
                    runnable = () -> updateSeekBar();
                    handler.postDelayed(runnable, 1000);
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error updating seek bar", e);
            }
        }
    }
    
    private void updateTimeText(int currentPosition, int duration) {
        currentTimeText.setText(formatTime(currentPosition));
        totalTimeText.setText(formatTime(duration));
    }
    
    private String formatTime(int milliseconds) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) - 
                      TimeUnit.MINUTES.toSeconds(minutes);
        return String.format("%d:%02d", minutes, seconds);
    }
    
    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
                mediaPlayer = null;
            } catch (Exception e) {
                Log.e(TAG, "Error releasing media player", e);
            }
        }
    }
    
    private void showPopupMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenuInflater().inflate(R.menu.audio_player_menu, popup.getMenu());
        
        // If a timer is already active, check the appropriate item
        if (timerActive) {
            MenuItem currentTimer = null;
            // Find the right menu item to check based on your current timer
            // This would need actual implementation based on how you track the timer
        }
        
        popup.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            
            // Cancel any existing timer
            if (sleepTimer != null) {
                sleepTimer.cancel();
                timerActive = false;
            }
            
            // Set new timer based on selection
            if (itemId == R.id.timer_15) {
                setSleepTimer(15);
                return true;
            } else if (itemId == R.id.timer_30) {
                setSleepTimer(30);
                return true;
            } else if (itemId == R.id.timer_45) {
                setSleepTimer(45);
                return true;
            } else if (itemId == R.id.timer_60) {
                setSleepTimer(60);
                return true;
            } else if (itemId == R.id.timer_off) {
                Toast.makeText(MainActivity.this, R.string.timer_canceled, Toast.LENGTH_SHORT).show();
                return true;
            } else if (itemId == R.id.timer_custom) {
                showCustomTimerDialog();
                return true;
            }
            
            // Handle A-B Repeat menu items
            else if (itemId == R.id.ab_set_point_a) {
                setPointA();
                return true;
            } else if (itemId == R.id.ab_set_point_b) {
                setPointB();
                return true;
            } else if (itemId == R.id.ab_clear_points) {
                clearABPoints();
                return true;
            }
            
            return false;
        });
        
        popup.show();
    }
    
    private void showCustomTimerDialog() {
        // Inflate the custom layout
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_custom_timer, null);
        EditText minutesInput = dialogView.findViewById(R.id.timer_minutes);
        
        // Create and show the dialog
        new AlertDialog.Builder(this)
            .setTitle(R.string.timer_custom_title)
            .setView(dialogView)
            .setPositiveButton(R.string.ok, (dialog, which) -> {
                // Get the user input
                String minutesStr = minutesInput.getText().toString();
                
                if (!TextUtils.isEmpty(minutesStr)) {
                    try {
                        int minutes = Integer.parseInt(minutesStr);
                        
                        // Validate the input (1-180 minutes is a reasonable range)
                        if (minutes > 0 && minutes <= 180) {
                            setSleepTimer(minutes);
                        } else {
                            Toast.makeText(MainActivity.this, 
                                R.string.error_invalid_time, Toast.LENGTH_SHORT).show();
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(MainActivity.this, 
                            R.string.error_invalid_time, Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .create()
            .show();
    }
    
    private void setSleepTimer(int minutes) {
        // Convert minutes to milliseconds
        long milliseconds = minutes * 60 * 1000L;
        
        sleepTimer = new CountDownTimer(milliseconds, 60000) { // Update every minute
            @Override
            public void onTick(long millisUntilFinished) {
                // Calculate remaining time
                int minutesRemaining = (int) (millisUntilFinished / 60000);
                int secondsRemaining = (int) ((millisUntilFinished % 60000) / 1000);
                
                // Format and display the time
                String timeString = String.format("⏱ Timer: %02d:%02d", minutesRemaining, secondsRemaining);
                
                runOnUiThread(() -> {
                    timerIndicator.setText(timeString);
                    timerIndicator.setVisibility(View.VISIBLE);
                });
            }
            
            @Override
            public void onFinish() {
                // Stop playback when timer ends
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    playPauseButton.setIconResource(R.drawable.ic_play);
                    isPlaying = false;
                }
                timerActive = false;
                
                // Show a toast that the timer has finished
                Toast.makeText(MainActivity.this, 
                    "Sleep timer ended", Toast.LENGTH_SHORT).show();
                
                // Hide the timer indicator
                runOnUiThread(() -> {
                    timerIndicator.setVisibility(View.GONE);
                });
            }
        }.start();
        
        timerActive = true;
        Toast.makeText(this, getString(R.string.timer_set_custom, minutes), Toast.LENGTH_SHORT).show();
    }
    
    private void setPointA() {
        if (mediaPlayer != null && selectedAudioUri != null) {
            try {
                pointA = mediaPlayer.getCurrentPosition();
                String pointATime = formatTime(pointA);
                Toast.makeText(this, getString(R.string.point_a_set, pointATime), Toast.LENGTH_SHORT).show();
                
                // If point B is set and is before point A, clear point B
                if (pointB != -1 && pointB <= pointA) {
                    pointB = -1;
                    abRepeatActive = false;
                }
                
                // If both points are set, enable A-B repeat
                if (pointB != -1) {
                    enableABRepeat();
                }
                
                // Update indicator
                updateABRepeatIndicator();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error setting point A", e);
            }
        }
    }
    
    private void setPointB() {
        if (mediaPlayer != null && selectedAudioUri != null) {
            try {
                int currentPosition = mediaPlayer.getCurrentPosition();
                
                // Make sure point A is set and point B is after point A
                if (pointA == -1) {
                    Toast.makeText(this, "Set point A first", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                if (currentPosition <= pointA) {
                    Toast.makeText(this, R.string.point_b_before_a, Toast.LENGTH_SHORT).show();
                    return;
                }
                
                pointB = currentPosition;
                String pointBTime = formatTime(pointB);
                Toast.makeText(this, getString(R.string.point_b_set, pointBTime), Toast.LENGTH_SHORT).show();
                
                // Enable A-B repeat
                enableABRepeat();
                
                // Update indicator
                updateABRepeatIndicator();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error setting point B", e);
            }
        }
    }
    
    private void clearABPoints() {
        pointA = -1;
        pointB = -1;
        abRepeatActive = false;
        
        // Hide the indicator
        if (abRepeatIndicator != null) {
            abRepeatIndicator.setVisibility(View.GONE);
        }
        
        Toast.makeText(this, R.string.ab_repeat_cleared, Toast.LENGTH_SHORT).show();
    }
    
    private void enableABRepeat() {
        if (pointA != -1 && pointB != -1 && pointB > pointA) {
            abRepeatActive = true;
            Toast.makeText(this, R.string.ab_repeat_active, Toast.LENGTH_SHORT).show();
        }
    }
    
    private void updateABRepeatIndicator() {
        if (abRepeatIndicator != null) {
            if (pointA != -1) {
                String text = "A: " + formatTime(pointA);
                if (pointB != -1) {
                    text += " - B: " + formatTime(pointB);
                }
                abRepeatIndicator.setText(text);
                abRepeatIndicator.setVisibility(View.VISIBLE);
            } else {
                abRepeatIndicator.setVisibility(View.GONE);
            }
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            playPauseButton.setIconResource(R.drawable.ic_play);
            isPlaying = false;
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseMediaPlayer();
        if (handler != null) {
            handler.removeCallbacks(runnable);
        }
        
        // Cancel any active timer
        if (sleepTimer != null) {
            sleepTimer.cancel();
        }
    }
}