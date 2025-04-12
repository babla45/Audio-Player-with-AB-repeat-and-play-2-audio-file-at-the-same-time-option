package com.example.aud_player;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.RadioGroup;
import android.text.InputType;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.view.Gravity;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.content.SharedPreferences;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AudioPlayerApp";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REQUEST_BROWSE_AUDIO = 1001;
    private static final int REQUEST_BROWSE_SECOND_AUDIO = 1002;
    
    private static final int SORT_BY_NAME_ASC = 0;
    private static final int SORT_BY_NAME_DESC = 1;
    private static final int SORT_BY_DURATION_ASC = 2;
    private static final int SORT_BY_DURATION_DESC = 3;
    private static final int SORT_BY_DATE_ASC = 4;
    private static final int SORT_BY_DATE_DESC = 5;
    private static final int SORT_BY_SIZE_ASC = 6;
    private static final int SORT_BY_SIZE_DESC = 7;
    
    private static final int TIMER_ACTION_PAUSE = 0;
    private static final int TIMER_ACTION_CLOSE_APP = 1;
    private int timerAction = TIMER_ACTION_PAUSE; // Default is pause
    
    private static final int PLAYBACK_MODE_REPEAT_CURRENT = 0;
    private static final int PLAYBACK_MODE_NEXT_IN_LIST = 1;
    private static final int PLAYBACK_MODE_RANDOM = 2;
    
    private int currentPlaybackMode = PLAYBACK_MODE_NEXT_IN_LIST; // Default is list play
    
    private Button selectButton;
    private TextView fileNameText, currentTimeText, totalTimeText;
    private SeekBar seekBar;
    private ImageButton playPauseButton;
    private MaterialButton menuButton;
    private TextView timerIndicator;
    private int pointA = -1;
    private int pointB = -1;
    private boolean abRepeatActive = false;
    private TextView abRepeatIndicator;
    private MediaPlayer secondMediaPlayer = null;
    private Uri secondAudioUri = null;
    private boolean secondAudioActive = false;
    private TextView mixerIndicator;
    private float firstAudioVolume = 1.0f;
    private float secondAudioVolume = 1.0f;

    private RecyclerView audioRecyclerView;
    private TextView emptyView;
    private List<AudioFile> audioFiles = new ArrayList<>();
    private AudioAdapter audioAdapter;

    private int currentSortOrder = SORT_BY_DATE_DESC; // Change from SORT_BY_NAME_ASC to SORT_BY_DATE_DESC

    // Add these instance variables
    private EditText searchEditText;
    private List<AudioFile> allAudioFiles = new ArrayList<>(); // Store all files
    private List<AudioFile> filteredAudioFiles = new ArrayList<>(); // Store filtered results

    // Add this as a class variable
    private ImageView clearSearchButton;

    // Add these as class variables
    private TextView mixerToggleButton;
    private boolean mixerModeActive = false;

    private AudioPlaybackService audioService;
    private boolean serviceBound = false;

    // Add these constants near the top of your MainActivity class
    private static final int SEEK_FORWARD_MS = 10000; // 10 seconds
    private static final int SEEK_BACKWARD_MS = 10000; // 10 seconds

    // Add these UI elements as class members
    private ImageButton seekBackwardButton;
    private ImageButton seekForwardButton;

    // Add this as a class member at the top of your class with other UI elements
    private ImageButton syncPositionButton;

    // Change these from constants to instance variables
    private int seekForwardMs = 10000; // Default 10 seconds
    private int seekBackwardMs = 10000; // Default 10 seconds

    // Add this variable declaration with your other media player variables
    private MediaPlayer mediaPlayer = null;

    // Add these missing variables
    private Uri selectedAudioUri = null;
    private boolean isPermissionGranted = false;
    private boolean isPlaying = false;

    // Make sure these are properly declared in your class
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable runnable;

    // Add these declarations with your other class variables
    private CountDownTimer sleepTimer;
    private boolean timerActive = false;

    // Add a field to track the current playback speed
    private float currentPlaybackSpeed = 1.0f;
    private static final float MIN_PLAYBACK_SPEED = 0.25f;
    private static final float MAX_PLAYBACK_SPEED = 4.0f;
    private static final float PLAYBACK_SPEED_STEP = 0.25f;

    // Add variables to track individual playback speeds
    private float primaryPlaybackSpeed = 1.0f;
    private float secondaryPlaybackSpeed = 1.0f;
    private boolean useIndividualPlaybackSpeeds = false;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioPlaybackService.LocalBinder binder = (AudioPlaybackService.LocalBinder) service;
            audioService = binder.getService();
            serviceBound = true;
            
            // Pass the current MediaPlayer instances to the service with title
            if (mediaPlayer != null && secondMediaPlayer != null) {
                String currentTitle = fileNameText != null ? fileNameText.getText().toString() : "Audio Player";
                audioService.setMediaPlayers(mediaPlayer, secondMediaPlayer, currentTitle);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

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
                    // Load audio files after permission is granted
                    loadAudioFiles();
                } else {
                    Toast.makeText(this, "Permission denied. Cannot access audio files.", 
                                   Toast.LENGTH_LONG).show();
                }
            });

    // Create a second activity result launcher for the second audio file
    private final ActivityResultLauncher<Intent> secondAudioPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        secondAudioUri = uri;
                        String fileName = getFileNameFromUri(uri);
                        
                        // Display toast with selected file
                        Toast.makeText(this, 
                            getString(R.string.second_file_selected, fileName), 
                            Toast.LENGTH_SHORT).show();
                        
                        // Take persistent permission for this URI
                        try {
                            getContentResolver().takePersistableUriPermission(uri, 
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException e) {
                            Log.e(TAG, "Failed to take persistable URI permission", e);
                        }
                        
                        // Prepare the second media player
                        prepareSecondMediaPlayer();
                        
                        // Show mixer indicator
                        if (mixerIndicator != null) {
                            mixerIndicator.setVisibility(View.VISIBLE);
                        }
                    }
                }
            }
    );

    // Add this as a class field
    private boolean shouldAutoPlay = false;

    private BroadcastReceiver playbackStoppedReceiver;
    private BroadcastReceiver timerUpdateReceiver;

    // Add a new class variable for the receiver
    private BroadcastReceiver closeAppReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Load seek settings from SharedPreferences
        SharedPreferences prefs = getSharedPreferences("audio_player_prefs", MODE_PRIVATE);
        int forwardSeconds = prefs.getInt("seek_forward_seconds", 10); // Default 10 sec
        int backwardSeconds = prefs.getInt("seek_backward_seconds", 10); // Default 10 sec
        seekForwardMs = forwardSeconds * 1000;
        seekBackwardMs = backwardSeconds * 1000;
        
        // Initialize views
        initializeViews();
        
        // Check mixer button - add this debug code
        if (mixerToggleButton != null) {
            Log.d(TAG, "Mixer button initialized successfully");
            // Force visibility
            mixerToggleButton.setVisibility(View.VISIBLE);
        } else {
            Log.e(TAG, "Failed to initialize mixer button");
        }
        
        // Setup listeners
        setupListeners();
        
        // Check for permissions
        checkPermissions();
        
        // Load audio files if permission granted
        if (isPermissionGranted) {
            loadAudioFiles();
        }

        // Bind to the service
        Intent serviceIntent = new Intent(this, AudioPlaybackService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // Register broadcast receiver for playback stopped
        playbackStoppedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("PLAYBACK_STOPPED".equals(intent.getAction())) {
                    // Update UI when playback is stopped from notification
                    updateUIForPlaybackStopped();
                }
            }
        };
        registerReceiver(playbackStoppedReceiver, new IntentFilter("PLAYBACK_STOPPED"));

        // Register broadcast receiver for app close command
        closeAppReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("CLOSE_APP_COMMAND".equals(intent.getAction())) {
                    // Close the app completely
                    finishAndRemoveTask();
                }
            }
        };
        registerReceiver(closeAppReceiver, new IntentFilter("CLOSE_APP_COMMAND"));
    }
    
    private void initializeViews() {
        try {
            // Core player elements
            selectButton = findViewById(R.id.selectButton);
            fileNameText = findViewById(R.id.fileNameText);
            seekBar = findViewById(R.id.seekBar);
            currentTimeText = findViewById(R.id.currentTimeText);
            totalTimeText = findViewById(R.id.totalTimeText);
            playPauseButton = findViewById(R.id.playPauseButton);
            menuButton = findViewById(R.id.menuButton);
            
            // Indicators
            timerIndicator = findViewById(R.id.timerIndicator);
            abRepeatIndicator = findViewById(R.id.abRepeatIndicator);
            mixerIndicator = findViewById(R.id.mixerIndicator);
            
            // Seek buttons
            seekBackwardButton = findViewById(R.id.seekBackwardButton);
            seekForwardButton = findViewById(R.id.seekForwardButton);
            
            // RecyclerView elements
            audioRecyclerView = findViewById(R.id.audioRecyclerView);
            emptyView = findViewById(R.id.emptyView);
            
            // Setup RecyclerView
            audioRecyclerView.setLayoutManager(new LinearLayoutManager(this));
            
            // Search elements
            searchEditText = findViewById(R.id.searchEditText);
            clearSearchButton = findViewById(R.id.clearSearchButton);
            
            // Mixer toggle
            mixerToggleButton = findViewById(R.id.mixerToggleButton);
            
            // Set default color for mixer toggle
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mixerToggleButton.setTextColor(getResources().getColor(android.R.color.darker_gray, null));
            } else {
                mixerToggleButton.setTextColor(getResources().getColor(android.R.color.darker_gray));
            }
            
            // Sync position button
            syncPositionButton = findViewById(R.id.syncPositionButton);
            
            // Log successful initialization
            Log.d(TAG, "Views initialized successfully");
            
            // In your initializeViews() method, add this line after initializing the seekBackwardButton:
            if (seekBackwardButton != null) {
                try {
                    seekBackwardButton.setImageResource(R.drawable.ic_backward_simple);
                } catch (Exception e) {
                    Log.e(TAG, "Error setting backward button image", e);
                    // Fallback to a simpler icon if available
                    seekBackwardButton.setImageResource(android.R.drawable.ic_media_previous);
                }
            }
            
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
            animateButtonPress(v);
            if (mediaPlayer != null && selectedAudioUri != null) {
                try {
                    Intent serviceIntent = new Intent(this, AudioPlaybackService.class);
                    if (isPlaying) {
                        mediaPlayer.pause();
                        
                        // Also pause the second audio if it's active
                        if (secondMediaPlayer != null && secondAudioActive) {
                            secondMediaPlayer.pause();
                        }
                        
                        // Use the new improved icons
                        safeSetImageResource(playPauseButton, R.drawable.ic_play_improved);
                        isPlaying = false;
                        serviceIntent.setAction("ACTION_PAUSE");
                    } else {
                        mediaPlayer.start();
                        
                        // Also start the second audio if it's ready
                        if (secondMediaPlayer != null && secondAudioActive) {
                            secondMediaPlayer.start();
                        }
                        
                        // Use the new improved icons
                        safeSetImageResource(playPauseButton, R.drawable.ic_pause_improved);
                        isPlaying = true;
                        updateSeekBar();
                        serviceIntent.setAction("ACTION_PLAY");
                    }
                    
                    // Update service with current state
                    if (serviceBound && audioService != null) {
                        audioService.setMediaPlayers(mediaPlayer, secondMediaPlayer, 
                            fileNameText.getText().toString());
                    }
                    
                    // Start or update the service
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                    
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error with play/pause", e);
                    prepareMediaPlayer();
                }
            }
        });
        
        // Add the menu button listener
        menuButton.setOnClickListener(v -> showPopupMenu(v));
        
        // Add search functionality with clear button toggle
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // Not needed
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Update clear button visibility
                clearSearchButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                
                // Filter audio files based on search query
                filterAudioFiles(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Not needed
            }
        });
        
        // Add click listener for clear button
        clearSearchButton.setOnClickListener(v -> {
            // Clear the search text
            searchEditText.setText("");
            
            // Hide keyboard
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(searchEditText.getWindowToken(), 0);
        });
        
        // Add "enter" key listener for search
        searchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // Hide keyboard
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                return true;
            }
            return false;
        });

        // Add mixer toggle button listener
        mixerToggleButton.setOnClickListener(v -> toggleMixerMode());
        
        // Add seek backward button listener
        seekBackwardButton.setOnClickListener(v -> {
            animateButtonPress(v);
            seekRelative(-seekBackwardMs);
        });
        
        // Add seek forward button listener
        seekForwardButton.setOnClickListener(v -> {
            animateButtonPress(v);
            seekRelative(seekForwardMs);
        });

        // Set click listener for sync position button
        syncPositionButton.setOnClickListener(v -> showPositionSyncDialog());

        // Inside setupListeners() method, add this code to properly handle seekbar interactions:
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    try {
                        // Log user interaction for debugging
                        Log.d(TAG, "User seeking to position: " + progress);
                        
                        // Apply the seek position to the media player
                        mediaPlayer.seekTo(progress);
                        
                        // Update time display
                        updateTimeText(progress, mediaPlayer.getDuration());
                        
                        // If we have a second player active, sync its position too
                        if (secondMediaPlayer != null && secondAudioActive) {
                            syncSecondPlayerPosition();
                        }
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Error seeking media player", e);
                    }
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // Optionally pause the player while seeking
                // If you want to pause while seeking, uncomment these lines:
                /*
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    // Don't change the play/pause button here as we'll resume playback after seeking
                }
                */
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Optionally resume playback after seeking
                // If you paused during onStartTrackingTouch, resume here:
                /*
                if (mediaPlayer != null && isPlaying) {
                    mediaPlayer.start();
                }
                */
            }
        });
    }
    
    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Check both READ_MEDIA_AUDIO and POST_NOTIFICATIONS permissions
            boolean hasAudioPermission = ContextCompat.checkSelfPermission(this, 
                Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
            boolean hasNotificationPermission = ContextCompat.checkSelfPermission(this, 
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;

            if (!hasAudioPermission) {
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO);
            }
            if (!hasNotificationPermission) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
            
            isPermissionGranted = hasAudioPermission; // We mainly care about audio permission for playback
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
            // Try to get the filename from content resolver first
            String filename = null;
            
            // For content URIs, try to get the actual filename
            if (uri.getScheme().equals("content")) {
                Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        filename = cursor.getString(nameIndex);
                    }
                    cursor.close();
                }
            }
            
            // If we couldn't get the filename from content resolver, use the last path segment
            if (filename == null) {
                filename = uri.getLastPathSegment();
                if (filename != null && filename.contains("/")) {
                    filename = filename.substring(filename.lastIndexOf("/") + 1);
                }
            }
            
            return filename != null ? filename : "Unknown File";
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
                    // Set the seekbar maximum to the total duration
                    int duration = mp.getDuration();
                    seekBar.setMax(duration);
                    
                    // Log for debugging
                    Log.d(TAG, "Media duration: " + duration + "ms, seekbar max set");
                    
                    totalTimeText.setText(formatTime(duration));
                    currentTimeText.setText("0:00");
                    
                    // Check if we should auto-play
                    if (shouldAutoPlay) {
                        // Start playing automatically
                        mediaPlayer.start();
                        safeSetImageResource(playPauseButton, R.drawable.ic_pause_improved);
                        isPlaying = true;
                        updateSeekBar();
                        
                        // Start the service
                        Intent serviceIntent = new Intent(this, AudioPlaybackService.class);
                        serviceIntent.setAction("ACTION_PLAY");
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent);
                        } else {
                            startService(serviceIntent);
                        }
                        
                        // Update service with media players
                        if (serviceBound && audioService != null) {
                            audioService.setMediaPlayers(mediaPlayer, secondMediaPlayer, 
                                fileNameText.getText().toString());
                        }
                        
                        // Reset the flag after use
                        shouldAutoPlay = false;
                    } else {
                        // Standard behavior - don't auto-play
                        safeSetImageResource(playPauseButton, R.drawable.ic_play_improved);
                        isPlaying = false;
                    }
                    
                    // Apply playback speed if not default
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Math.abs(currentPlaybackSpeed - 1.0f) > 0.01f) {
                        try {
                            PlaybackParams params = new PlaybackParams();
                            params.setSpeed(currentPlaybackSpeed);
                            mediaPlayer.setPlaybackParams(params);
                            Log.d(TAG, "Applied saved playback speed to primary audio: " + currentPlaybackSpeed);
                        } catch (Exception e) {
                            Log.e(TAG, "Error applying saved playback speed to primary audio", e);
                        }
                    }
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
                safeSetImageResource(playPauseButton, R.drawable.ic_play_improved);
                isPlaying = false;
                shouldAutoPlay = false; // Reset flag on error
                return true; // Error handled
            });
            
            mediaPlayer.setOnCompletionListener(mp -> {
                handleSongCompletion();
            });
            
            mediaPlayer.prepareAsync();
            
        } catch (IOException e) {
            Log.e(TAG, "Error preparing media player", e);
            Toast.makeText(this, "Error loading audio file", Toast.LENGTH_SHORT).show();
            shouldAutoPlay = false; // Reset flag on error
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            Log.e(TAG, "Media player error", e);
            Toast.makeText(this, "Error with media player", Toast.LENGTH_SHORT).show();
            shouldAutoPlay = false; // Reset flag on error
        }
    }
    
    private void handleSongCompletion() {
        switch (currentPlaybackMode) {
            case PLAYBACK_MODE_REPEAT_CURRENT:
                // Restart the current song
                if (mediaPlayer != null) {
                    mediaPlayer.seekTo(0);
                    mediaPlayer.start();
                }
                break;
                
            case PLAYBACK_MODE_NEXT_IN_LIST:
                // Play the next song in the list
                int currentIndex = getCurrentSongIndex();
                if (currentIndex != -1 && currentIndex + 1 < audioFiles.size()) {
                    onAudioFileSelected(audioFiles.get(currentIndex + 1));
                } else if (!audioFiles.isEmpty()) {
                    // Loop back to the first song if at the end
                    onAudioFileSelected(audioFiles.get(0));
                }
                break;
                
            case PLAYBACK_MODE_RANDOM:
                // Play a random song from the list
                if (!audioFiles.isEmpty()) {
                    int randomIndex = new java.util.Random().nextInt(audioFiles.size());
                    onAudioFileSelected(audioFiles.get(randomIndex));
                }
                break;
        }
    }

    private int getCurrentSongIndex() {
        if (selectedAudioUri == null || audioFiles.isEmpty()) {
            return -1;
        }
        
        for (int i = 0; i < audioFiles.size(); i++) {
            if (selectedAudioUri.toString().equals(audioFiles.get(i).getUri().toString())) {
                return i;
            }
        }
        return -1;
    }
    
    private void updateSeekBar() {
        if (mediaPlayer != null) {
            try {
                int currentPosition = mediaPlayer.getCurrentPosition();
                
                // Check if A-B repeat is active and we need to loop
                if (abRepeatActive && pointA != -1 && pointB != -1) {
                    if (currentPosition >= pointB) {
                        // Reached point B, loop back to point A
                        mediaPlayer.seekTo(pointA);
                        currentPosition = pointA;
                        
                        // Also seek second player if active
                        if (secondMediaPlayer != null && secondAudioActive) {
                            float mainDuration = mediaPlayer.getDuration();
                            float secondDuration = secondMediaPlayer.getDuration();
                            float aPercentage = pointA / mainDuration;
                            int secondPositionA = (int) (aPercentage * secondDuration);
                            secondMediaPlayer.seekTo(secondPositionA);
                        }
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
        // Convert to positive value in case of overflow
        long positiveMillis = milliseconds < 0 ? Integer.MAX_VALUE : (long) milliseconds;
        
        long hours = TimeUnit.MILLISECONDS.toHours(positiveMillis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(positiveMillis) - 
                      TimeUnit.HOURS.toMinutes(hours);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(positiveMillis) - 
                      TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(positiveMillis));
        
        // Format with hours if needed
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%d:%02d", minutes, seconds);
        }
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
        
        // Create menu items programmatically
        Menu menu = popup.getMenu();
        
        // Add your existing menu items
        menu.add(0, 1, 0, "Sort Audio Files");
        menu.add(0, 2, 0, "Sleep Timer");
        menu.add(0, 3, 0, "A-B Repeat");
        
        // Create Audio Mixer submenu
        SubMenu mixerMenu = menu.addSubMenu("Audio Mixer");
        mixerMenu.add(0, 41, 0, "Mix with Second Audio");
        if (secondAudioActive) {
            mixerMenu.add(0, 42, 0, "Adjust Audio Balance");
            mixerMenu.add(0, 43, 0, "Clear Second Audio");
            mixerMenu.add(0, 44, 0, useIndividualPlaybackSpeeds ? 
                "Use Global Playback Speed" : "Use Individual Playback Speeds");
            if (useIndividualPlaybackSpeeds) {
                mixerMenu.add(0, 45, 0, "Set Primary Audio Speed (" + String.format("%.2fx", primaryPlaybackSpeed) + ")");
                mixerMenu.add(0, 46, 0, "Set Secondary Audio Speed (" + String.format("%.2fx", secondaryPlaybackSpeed) + ")");
            }
        }
        
        menu.add(0, 5, 0, "Settings");
        menu.add(0, 6, 0, "Refresh List");
        
        // Add playback mode submenu
        SubMenu playbackModeMenu = menu.addSubMenu("Playback Mode");
        playbackModeMenu.add(0, 100, 0, "Repeat Current Song").setCheckable(true)
                .setChecked(currentPlaybackMode == PLAYBACK_MODE_REPEAT_CURRENT);
        playbackModeMenu.add(0, 101, 0, "Play Next in List").setCheckable(true)
                .setChecked(currentPlaybackMode == PLAYBACK_MODE_NEXT_IN_LIST);
        playbackModeMenu.add(0, 102, 0, "Random Play").setCheckable(true)
                .setChecked(currentPlaybackMode == PLAYBACK_MODE_RANDOM);
        
        // Add playback speed submenu
        SubMenu playbackSpeedMenu = menu.addSubMenu("Playback Speed");
        
        // Add speed options from 0.25x to 4.0x with 0.25 intervals
        for (float speed = MIN_PLAYBACK_SPEED; speed <= MAX_PLAYBACK_SPEED; speed += PLAYBACK_SPEED_STEP) {
            // Format speed with 2 decimal places if needed
            String speedText = speed == (int)speed ? String.format("%.0fx", speed) : String.format("%.2fx", speed);
            
            // Add menu item and mark current speed as checked
            // Use a different approach for menu item IDs to accommodate the wider range
            int menuItemId = 200 + Math.round(speed * 100);
            playbackSpeedMenu.add(0, menuItemId, 0, speedText).setCheckable(true)
                .setChecked(Math.abs(currentPlaybackSpeed - speed) < 0.01f);
        }
        
        popup.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == 1) {
                showSortMenu(v);
                return true;
            } else if (itemId == 2) {
                showCustomTimerDialog();
                return true;
            } else if (itemId == 3) {
                showABRepeatMenu(v);
                return true;
            } else if (itemId == 41) {
                selectSecondAudio();
                return true;
            } else if (itemId == 42) {
                showBalanceDialog();
                return true;
            } else if (itemId == 43) {
                clearSecondAudio();
                return true;
            } else if (itemId == 44) {
                toggleIndividualPlaybackSpeeds();
                return true;
            } else if (itemId == 45) {
                showIndividualSpeedDialog(true);  // For primary audio
                return true;
            } else if (itemId == 46) {
                showIndividualSpeedDialog(false);  // For secondary audio
                return true;
            } else if (itemId == 5) {
                showOtherSettingsDialog();
                return true;
            } else if (itemId == 6) {
                refreshAudioFiles();
                return true;
            } else if (itemId == 100) {
                currentPlaybackMode = PLAYBACK_MODE_REPEAT_CURRENT;
                Toast.makeText(this, "Mode: Repeat Current Song", Toast.LENGTH_SHORT).show();
                return true;
            } else if (itemId == 101) {
                currentPlaybackMode = PLAYBACK_MODE_NEXT_IN_LIST;
                Toast.makeText(this, "Mode: Play Next in List", Toast.LENGTH_SHORT).show();
                return true;
            } else if (itemId == 102) {
                currentPlaybackMode = PLAYBACK_MODE_RANDOM;
                Toast.makeText(this, "Mode: Random Play", Toast.LENGTH_SHORT).show();
                return true;
            } else if (itemId >= 225 && itemId <= 600) {
                // Handle playback speed change (ID range covers 0.25x to 4.0x)
                float selectedSpeed = (itemId - 200) / 100.0f;
                setPlaybackSpeed(selectedSpeed);
                return true;
            }
            return false;
        });
        
        popup.show();
    }
    
    private void setPlaybackSpeed(float speed) {
        if (speed < MIN_PLAYBACK_SPEED) speed = MIN_PLAYBACK_SPEED;
        if (speed > MAX_PLAYBACK_SPEED) speed = MAX_PLAYBACK_SPEED;
        
        // Update the stored global speed
        currentPlaybackSpeed = speed;
        
        // If using individual speeds in mix mode, don't apply to both players
        if (secondAudioActive && useIndividualPlaybackSpeeds) {
            Toast.makeText(this, "Using individual playback speeds - global speed stored for future use", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Use the PlaybackParams API if available (Android 6.0+)
            boolean speedApplied = false;
            
            // Apply to primary player if active
            if (mediaPlayer != null) {
                try {
                    PlaybackParams params = new PlaybackParams();
                    params.setSpeed(speed);
                    mediaPlayer.setPlaybackParams(params);
                    speedApplied = true;
                    Log.d(TAG, "Applied speed " + speed + "x to primary audio");
                    
                    // Update individual speed if active
                    if (useIndividualPlaybackSpeeds) {
                        primaryPlaybackSpeed = speed;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error setting playback speed on primary audio", e);
                }
            }
            
            // Apply to secondary player if active (and not using individual speeds)
            if (secondMediaPlayer != null && secondAudioActive && !useIndividualPlaybackSpeeds) {
                try {
                    PlaybackParams params = new PlaybackParams();
                    params.setSpeed(speed);
                    secondMediaPlayer.setPlaybackParams(params);
                    speedApplied = true;
                    Log.d(TAG, "Applied speed " + speed + "x to secondary audio");
                    
                    // Update individual speed if active
                    if (useIndividualPlaybackSpeeds) {
                        secondaryPlaybackSpeed = speed;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error setting playback speed on secondary audio", e);
                }
            }
            
            // Show feedback if at least one player was updated
            if (speedApplied) {
                // Display toast with the new speed
                Toast.makeText(this, "Playback speed: " + String.format("%.2fx", speed), Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Playback speed set to " + speed);
            } else {
                // Still update the preference even if no player is active
                Toast.makeText(this, "Playback speed will be " + String.format("%.2fx", speed) + 
                    " when audio starts", Toast.LENGTH_SHORT).show();
            }
        } else {
            // Show message that this feature requires Android 6.0+
            Toast.makeText(this, "Playback speed control requires Android 6.0 or higher", 
                Toast.LENGTH_LONG).show();
        }
    }
    
    private void toggleIndividualPlaybackSpeeds() {
        useIndividualPlaybackSpeeds = !useIndividualPlaybackSpeeds;
        
        if (useIndividualPlaybackSpeeds) {
            // Initialize individual speeds to the current global speed
            primaryPlaybackSpeed = currentPlaybackSpeed;
            secondaryPlaybackSpeed = currentPlaybackSpeed;
            
            // Show toast and immediately open the primary speed dialog
            Toast.makeText(this, "Individual playback speeds enabled", Toast.LENGTH_SHORT).show();
            
            // Apply current speeds to respective players
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (mediaPlayer != null) {
                    try {
                        PlaybackParams params = new PlaybackParams();
                        params.setSpeed(primaryPlaybackSpeed);
                        mediaPlayer.setPlaybackParams(params);
                        Log.d(TAG, "Applied initial primary speed: " + primaryPlaybackSpeed);
                    } catch (Exception e) {
                        Log.e(TAG, "Error setting primary playback speed", e);
                    }
                }
                
                if (secondMediaPlayer != null && secondAudioActive) {
                    try {
                        PlaybackParams params = new PlaybackParams();
                        params.setSpeed(secondaryPlaybackSpeed);
                        secondMediaPlayer.setPlaybackParams(params);
                        Log.d(TAG, "Applied initial secondary speed: " + secondaryPlaybackSpeed);
                    } catch (Exception e) {
                        Log.e(TAG, "Error setting secondary playback speed", e);
                    }
                }
            }
            
            // Immediately show the primary speed dialog
            showIndividualSpeedDialog(true);
        } else {
            // Switch back to global speed mode
            Toast.makeText(this, "Global playback speed restored", Toast.LENGTH_SHORT).show();
            
            // Apply the global speed to both players
            setPlaybackSpeed(currentPlaybackSpeed);
        }
    }

    private void showIndividualSpeedDialog(boolean isPrimary) {
        final String title = isPrimary ? "Primary Audio Speed" : "Secondary Audio Speed";
        final float currentSpeed = isPrimary ? primaryPlaybackSpeed : secondaryPlaybackSpeed;
        
        // Create a dialog with a slider for adjusting speed
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        
        // Create a simple layout with a seekbar and text display
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);
        
        TextView speedLabel = new TextView(this);
        speedLabel.setText(String.format("Current: %.2fx", currentSpeed));
        speedLabel.setGravity(Gravity.CENTER);
        speedLabel.setTextSize(18);
        layout.addView(speedLabel);
        
        SeekBar speedSeekBar = new SeekBar(this);
        // Map 0.25x-4.0x to progress values (0-375)
        speedSeekBar.setMax(375);
        int initialProgress = (int)((currentSpeed - 0.25f) * 100);
        speedSeekBar.setProgress(initialProgress);
        layout.addView(speedSeekBar);
        
        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float speed = 0.25f + (progress / 100.0f);
                if (speed > 4.0f) speed = 4.0f;
                speedLabel.setText(String.format("Current: %.2fx", speed));
                
                // Preview the speed change if possible
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && fromUser) {
                    try {
                        PlaybackParams params = new PlaybackParams();
                        params.setSpeed(speed);
                        
                        if (isPrimary && mediaPlayer != null) {
                            mediaPlayer.setPlaybackParams(params);
                            Log.d(TAG, "Previewing primary speed: " + speed);
                        } else if (!isPrimary && secondMediaPlayer != null && secondAudioActive) {
                            secondMediaPlayer.setPlaybackParams(params);
                            Log.d(TAG, "Previewing secondary speed: " + speed);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error previewing playback speed", e);
                    }
                }
            }
            
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        builder.setView(layout);
        
        // Option to open the other dialog
        if (secondMediaPlayer != null && secondAudioActive) {
            builder.setNeutralButton(isPrimary ? "Set Secondary Speed" : "Set Primary Speed", 
                (dialog, which) -> {
                    // First apply the current setting
                    float speed = 0.25f + (speedSeekBar.getProgress() / 100.0f);
                    if (speed > 4.0f) speed = 4.0f;
                    
                    if (isPrimary) {
                        primaryPlaybackSpeed = speed;
                        applyPrimarySpeed(speed);
                        // Then open the other dialog
                        showIndividualSpeedDialog(false);
                    } else {
                        secondaryPlaybackSpeed = speed;
                        applySecondarySpeed(speed);
                        // Then open the other dialog
                        showIndividualSpeedDialog(true);
                    }
                });
        }
        
        builder.setPositiveButton("Set", (dialog, which) -> {
            float speed = 0.25f + (speedSeekBar.getProgress() / 100.0f);
            if (speed > 4.0f) speed = 4.0f;
            
            if (isPrimary) {
                primaryPlaybackSpeed = speed;
                applyPrimarySpeed(speed);
            } else {
                secondaryPlaybackSpeed = speed;
                applySecondarySpeed(speed);
            }
        });
        
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            // Restore original speed
            if (isPrimary) {
                applyPrimarySpeed(primaryPlaybackSpeed);
            } else {
                applySecondarySpeed(secondaryPlaybackSpeed);
            }
        });
        
        builder.show();
    }

    // Helper methods to apply speeds with proper error handling
    private void applyPrimarySpeed(float speed) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && mediaPlayer != null) {
            try {
                PlaybackParams params = new PlaybackParams();
                params.setSpeed(speed);
                mediaPlayer.setPlaybackParams(params);
                Log.d(TAG, "Applied primary speed: " + speed);
                Toast.makeText(this, "Primary audio speed set to " + String.format("%.2fx", speed), 
                    Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Error applying primary playback speed", e);
                Toast.makeText(this, "Error setting primary speed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void applySecondarySpeed(float speed) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && 
            secondMediaPlayer != null && secondAudioActive) {
            try {
                PlaybackParams params = new PlaybackParams();
                params.setSpeed(speed);
                secondMediaPlayer.setPlaybackParams(params);
                Log.d(TAG, "Applied secondary speed: " + speed);
                Toast.makeText(this, "Secondary audio speed set to " + String.format("%.2fx", speed), 
                    Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Error applying secondary playback speed", e);
                Toast.makeText(this, "Error setting secondary speed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showCustomTimerDialog() {
        // Inflate the custom layout
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_custom_timer, null);
        EditText minutesInput = dialogView.findViewById(R.id.timer_minutes);
        
        // Configure input to accept decimal values
        minutesInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        
        // Add radio buttons for timer action
        RadioGroup actionGroup = dialogView.findViewById(R.id.timer_action_group);
        
        // Set the previously selected option (if dialog is reopened)
        if (timerAction == TIMER_ACTION_PAUSE) {
            actionGroup.check(R.id.radio_pause);
        } else {
            actionGroup.check(R.id.radio_close);
        }
        
        // Create and show the dialog
        new AlertDialog.Builder(this)
            .setTitle("Set Sleep Timer")
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                // Get the user input
                String minutesStr = minutesInput.getText().toString();
                
                // Get the selected action
                if (actionGroup.getCheckedRadioButtonId() == R.id.radio_pause) {
                    timerAction = TIMER_ACTION_PAUSE;
                } else {
                    timerAction = TIMER_ACTION_CLOSE_APP;
                }
                
                if (!TextUtils.isEmpty(minutesStr)) {
                    try {
                        // Parse as float instead of int to support decimal values
                        float minutes = Float.parseFloat(minutesStr);
                        
                        // Validate the input (0.1-180 minutes is a reasonable range)
                        if (minutes >= 0.1f && minutes <= 180f) {
                            setSleepTimer(minutes);
                        } else {
                            Toast.makeText(MainActivity.this, 
                                "Invalid time value. Please enter a value between 0.1 and 180 minutes.", Toast.LENGTH_SHORT).show();
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(MainActivity.this, 
                            "Invalid time format. Please enter a valid number.", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .show();
    }
    
    /**
     * Sets a sleep timer for the specified number of minutes
     * @param minutes The duration of the timer in minutes
     */
    private void setSleepTimer(float minutes) {
        // Convert minutes to milliseconds
        long milliseconds = (long)(minutes * 60 * 1000L);
        long endTimeMillis = System.currentTimeMillis() + milliseconds;
        
        // Set the timer in the service to ensure it works in background
        if (serviceBound && audioService != null) {
            audioService.setTimer(endTimeMillis, timerAction);
            
            // Register a broadcast receiver for timer updates
            if (timerUpdateReceiver == null) {
                timerUpdateReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if ("TIMER_UPDATE".equals(intent.getAction())) {
                            long timeLeft = intent.getLongExtra("TIME_LEFT", 0);
                            updateTimerDisplay(timeLeft);
                        } else if ("TIMER_FINISHED".equals(intent.getAction())) {
                            timerActive = false;
                            int action = intent.getIntExtra("TIMER_ACTION", TIMER_ACTION_PAUSE);
                            
                            if (action == TIMER_ACTION_CLOSE_APP) {
                                // Make sure service is fully stopped before finishing
                                if (serviceBound && audioService != null) {
                                    try {
                                        unbindService(serviceConnection);
                                        serviceBound = false;
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error unbinding service before app close", e);
                                    }
                                }
                                // Close the app
                                finishAndRemoveTask();
                            } else {
                                // Pause the playback (default behavior)
                                runOnUiThread(() -> {
                                    timerIndicator.setVisibility(View.GONE);
                                    safeSetImageResource(playPauseButton, R.drawable.ic_play_improved);
                                    isPlaying = false;
                                });
                            }
                        }
                    }
                };
                
                IntentFilter filter = new IntentFilter();
                filter.addAction("TIMER_UPDATE");
                filter.addAction("TIMER_FINISHED");
                registerReceiver(timerUpdateReceiver, filter);
            }
        } else {
            // Fallback to the old timer implementation if service not bound
            startLegacyTimer(minutes);
        }
        
        timerActive = true;
        // Show initial timer display
        updateTimerDisplay(milliseconds);
        
        // Show toast with the selected action
        String actionMsg = timerAction == TIMER_ACTION_PAUSE ? 
            "Timer will pause playback" : "Timer will close app";
        
        Toast.makeText(this, "Timer set for " + 
            (minutes == Math.floor(minutes) ? String.valueOf((int)minutes) : String.valueOf(minutes)) + 
            " minutes: " + actionMsg, Toast.LENGTH_SHORT).show();
    }
    
    private void startLegacyTimer(float minutes) {
        if (sleepTimer != null) {
            sleepTimer.cancel();
        }
        
        long milliseconds = (long)(minutes * 60 * 1000L);
        
        sleepTimer = new CountDownTimer(milliseconds, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                updateTimerDisplay(millisUntilFinished);
            }
            
            @Override
            public void onFinish() {
                timerActive = false;
                
                // Perform action based on timer setting
                if (timerAction == TIMER_ACTION_CLOSE_APP) {
                    // Close the app
                    finishAndRemoveTask();
                } else {
                    // Pause playback (default)
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        mediaPlayer.pause();
                        safeSetImageResource(playPauseButton, R.drawable.ic_play_improved);
                        isPlaying = false;
                    }
                    
                    if (secondMediaPlayer != null && secondAudioActive && secondMediaPlayer.isPlaying()) {
                        secondMediaPlayer.pause();
                    }
                    
                    timerIndicator.setVisibility(View.GONE);
                }
            }
        };
        
        sleepTimer.start();
    }
    
    private void updateTimerDisplay(long millisUntilFinished) {
        // ... existing code ...
    }

    private void prepareSecondMediaPlayer() {
        if (secondAudioUri == null) return;
        
        // Log the initial state before any changes
        Log.d(TAG, "Before preparing second player:");
        checkMediaPlayersState();
        
        // Save the state of the first player
        boolean wasPlaying = false;
        int firstPosition = 0;
        if (mediaPlayer != null && selectedAudioUri != null) {
            try {
                wasPlaying = mediaPlayer.isPlaying();
                firstPosition = mediaPlayer.getCurrentPosition();
                Log.d(TAG, "Saved first player state: playing=" + wasPlaying + ", position=" + firstPosition);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error checking first player state", e);
            }
        }
        
        // Release existing second player if needed
        if (secondMediaPlayer != null) {
            try {
                secondMediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing second media player", e);
            }
            secondMediaPlayer = null;
        }
        
        // Create new second media player - use a completely separate instance creation
        secondMediaPlayer = new MediaPlayer();
        
        try {
            // Use a completely separate preparation path for the second player
            secondMediaPlayer.setDataSource(getApplicationContext(), secondAudioUri);
            
            // Set listeners with careful error handling
            secondMediaPlayer.setOnPreparedListener(mp -> {
                secondAudioActive = true;
                
                // Set volume based on saved balance
                secondMediaPlayer.setVolume(secondAudioVolume, secondAudioVolume);
                
                // Apply the current playback speed to the second player to match the first
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Math.abs(currentPlaybackSpeed - 1.0f) > 0.01f) {
                    try {
                        PlaybackParams params = new PlaybackParams();
                        params.setSpeed(currentPlaybackSpeed);
                        secondMediaPlayer.setPlaybackParams(params);
                        Log.d(TAG, "Applied playback speed " + currentPlaybackSpeed + "x to second audio");
                    } catch (Exception e) {
                        Log.e(TAG, "Error applying playback speed to second audio", e);
                    }
                }
                
                // Log the state after second player is prepared
                Log.d(TAG, "Second player prepared:");
                checkMediaPlayersState();
                
                // Start playing the second audio if the first one is playing
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    try {
                        // Start second audio at position relative to first audio
                        syncSecondPlayerPosition();
                        secondMediaPlayer.start();
                    } catch (Exception e) {
                        Log.e(TAG, "Error starting second audio", e);
                    }
                }

                if (syncPositionButton != null) {
                    syncPositionButton.setVisibility(View.VISIBLE);
                }
            });
            
            // Error listener with improved error handling
            secondMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Second MediaPlayer error: " + what + ", " + extra);
                Toast.makeText(MainActivity.this, 
                    "Error with second audio file", Toast.LENGTH_SHORT).show();
                
                secondAudioActive = false;
                if (secondMediaPlayer != null) {
                    try {
                        secondMediaPlayer.release();
                    } catch (Exception e) {
                        Log.e(TAG, "Error releasing second player after error", e);
                    }
                    secondMediaPlayer = null;
                }
                return true;
            });
            
            // Prepare the player asynchronously
            secondMediaPlayer.prepareAsync();
            
        } catch (Exception e) {
            Log.e(TAG, "Error preparing second media player", e);
            Toast.makeText(this, "Error loading second audio file", Toast.LENGTH_SHORT).show();
            secondAudioActive = false;
        }
        
        // Verify first player state is preserved
        if (mediaPlayer != null && selectedAudioUri != null) {
            try {
                boolean isStillPlaying = mediaPlayer.isPlaying();
                if (wasPlaying && !isStillPlaying) {
                    Log.d(TAG, "First player stopped playing during second player prep, restarting");
                    mediaPlayer.start();
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error checking first player state after second player prep", e);
                // First player may have been reset - try to restore it
                prepareMediaPlayer();
                if (wasPlaying) {
                    try {
                        mediaPlayer.seekTo(firstPosition);
                        mediaPlayer.start();
                    } catch (Exception ex) {
                        Log.e(TAG, "Failed to restore first player", ex);
                    }
                }
            }
        }
    }
    
    private void syncSecondPlayerPosition() {
        if (mediaPlayer == null || secondMediaPlayer == null || !secondAudioActive) {
            return;
        }
        
        try {
            float mainDuration = mediaPlayer.getDuration();
            float mainPosition = mediaPlayer.getCurrentPosition();
            float positionPercentage = mainPosition / mainDuration;
            
            float secondDuration = secondMediaPlayer.getDuration();
            int secondPosition = (int) (positionPercentage * secondDuration);
            
            secondMediaPlayer.seekTo(secondPosition);
        } catch (Exception e) {
            Log.e(TAG, "Error syncing second player position", e);
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // Remove the auto-pause code to allow background playback
    }
    
    @Override
    protected void onDestroy() {
        // Unbind from the service
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        
        // Only release MediaPlayer if it's not playing
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            releaseMediaPlayer();
        }
        
        // Only release second MediaPlayer if it's not playing
        if (secondMediaPlayer != null && !secondMediaPlayer.isPlaying()) {
            try {
                secondMediaPlayer.release();
                secondMediaPlayer = null;
            } catch (Exception e) {
                Log.e(TAG, "Error releasing second media player", e);
            }
        }
        
        if (handler != null) {
            handler.removeCallbacks(runnable);
        }
        
        // Cancel any active timer
        if (sleepTimer != null) {
            sleepTimer.cancel();
        }
        
        // Unregister the broadcast receivers
        if (playbackStoppedReceiver != null) {
            unregisterReceiver(playbackStoppedReceiver);
        }
        
        // Unregister the timer update receiver
        if (timerUpdateReceiver != null) {
            try {
                unregisterReceiver(timerUpdateReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering timer receiver", e);
            }
        }
        
        // Unregister the close app receiver
        if (closeAppReceiver != null) {
            try {
                unregisterReceiver(closeAppReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering close app receiver", e);
            }
        }
        
        super.onDestroy();
    }

    // Add this helper method to check both MediaPlayer instances
    private void checkMediaPlayersState() {
        // Log the state of both MediaPlayers
        Log.d(TAG, "MediaPlayer 1 state: " + 
            (mediaPlayer == null ? "null" : 
                (selectedAudioUri == null ? "no URI" : 
                    (mediaPlayer.isPlaying() ? "playing" : "prepared"))));
            
        Log.d(TAG, "MediaPlayer 2 state: " + 
            (secondMediaPlayer == null ? "null" : 
                (secondAudioUri == null ? "no URI" : 
                    (secondAudioActive ? "active" : "inactive"))));
    }

    // Add this method to launch the AudioListActivity
    private void browseAudioFiles(boolean selectSecondFile) {
        if (isPermissionGranted) {
            // For second file selection, ensure first file is selected
            if (selectSecondFile && selectedAudioUri == null) {
                Toast.makeText(this, "Please select a primary audio file first", 
                    Toast.LENGTH_SHORT).show();
                return;
            }
            
            Intent intent = new Intent(this, AudioListActivity.class);
            intent.putExtra("select_second_file", selectSecondFile);
            startActivityForResult(intent, selectSecondFile ? REQUEST_BROWSE_SECOND_AUDIO : REQUEST_BROWSE_AUDIO);
        } else {
            checkPermissions();
            Toast.makeText(this, "Permission required to access files", Toast.LENGTH_SHORT).show();
        }
    }

    // Override onActivityResult to handle returning from AudioListActivity
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            
            if (requestCode == REQUEST_BROWSE_AUDIO) {
                // Handle primary audio selection
                selectedAudioUri = uri;
                String fileName = getFileNameFromUri(uri);
                fileNameText.setText(fileName);
                
                // Take persistent permission
                try {
                    getContentResolver().takePersistableUriPermission(uri, 
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    Log.e(TAG, "Failed to take persistable URI permission", e);
                }
                
                prepareMediaPlayer();
                
            } else if (requestCode == REQUEST_BROWSE_SECOND_AUDIO) {
                // Handle second audio selection
                secondAudioUri = uri;
                String fileName = getFileNameFromUri(uri);
                
                // Take persistent permission
                try {
                    getContentResolver().takePersistableUriPermission(uri, 
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    Log.e(TAG, "Failed to take persistable URI permission", e);
                }
                
                Toast.makeText(this, getString(R.string.second_file_selected, fileName), 
                    Toast.LENGTH_SHORT).show();
                
                // Prepare the second media player
                prepareSecondMediaPlayer();
                
                // Show mixer indicator
                if (mixerIndicator != null) {
                    mixerIndicator.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    private void loadAudioFiles() {
        // Initial load with default order (newest first)
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED  // Make sure DATE_ADDED is included
        };

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        
        // Always sort by date descending by default
        String sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC";

        Cursor cursor = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder);

        if (cursor != null) {
            allAudioFiles.clear(); // Clear the master list
            
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
            int dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
            int displayNameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String title = cursor.getString(titleColumn);
                long duration = cursor.getLong(durationColumn);
                long size = cursor.getLong(sizeColumn);
                long dateAdded = cursor.getLong(dateAddedColumn);
                
                // Get the display name with extension
                String displayName = title;
                if (displayNameColumn != -1) {
                    String fullName = cursor.getString(displayNameColumn);
                    if (fullName != null && !fullName.isEmpty()) {
                        displayName = fullName;
                    }
                }
                
                String durationFormatted = formatTime((int)duration);
                Uri contentUri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));

                allAudioFiles.add(new AudioFile(displayName, durationFormatted, contentUri, id, size, dateAdded));
            }
            cursor.close();
            
            // Apply any current search filter
            if (searchEditText != null && !TextUtils.isEmpty(searchEditText.getText())) {
                filterAudioFiles(searchEditText.getText().toString());
            } else {
                // No filter, show all files
                filteredAudioFiles.clear();
                filteredAudioFiles.addAll(allAudioFiles);
                updateAudioFilesList();
            }
        }
    }

    private void onAudioFileSelected(AudioFile audioFile) {
        // Handle primary audio selection
        selectedAudioUri = audioFile.getUri();
        String fileName = audioFile.getTitle();
        fileNameText.setText(fileName);
        
        // Set flag to auto-play after preparation
        shouldAutoPlay = true;
        
        // Take persistent permission
        try {
            getContentResolver().takePersistableUriPermission(selectedAudioUri, 
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to take persistable URI permission", e);
        }
        
        prepareMediaPlayer();
        
        // Highlight the selected item
        highlightSelectedAudio(audioFile);
    }

    private void highlightSelectedAudio(AudioFile audioFile) {
        // This would require additional code in your adapter to track the selected item
        // For a simple approach, you can just show a toast
//        Toast.makeText(this, "Now playing: " + audioFile.getTitle(), Toast.LENGTH_SHORT).show();
    }

    public void onSecondAudioSelected(AudioFile audioFile) {
        // Make sure primary audio is selected first
        if (selectedAudioUri == null) {
            Toast.makeText(this, "Please select a primary audio file first", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Check if trying to use the same file for both primary and secondary
        if (selectedAudioUri.equals(audioFile.getUri())) {
            Toast.makeText(this, "Cannot use the same file for both primary and secondary audio", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Handle second audio selection
        secondAudioUri = audioFile.getUri();
        String fileName = audioFile.getTitle();
        
        // Take persistent permission
        try {
            getContentResolver().takePersistableUriPermission(secondAudioUri, 
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to take persistable URI permission", e);
        }
        
        Toast.makeText(this, getString(R.string.second_file_selected, fileName), 
            Toast.LENGTH_SHORT).show();
        
        // Prepare the second media player
        prepareSecondMediaPlayer();
        
        // If primary audio is not playing, start it with the second audio
        if (mediaPlayer != null && !mediaPlayer.isPlaying() && shouldAutoPlay) {
            mediaPlayer.start();
            safeSetImageResource(playPauseButton, R.drawable.ic_pause_improved);
            isPlaying = true;
            updateSeekBar();
            shouldAutoPlay = false;
        }
        
        // Show mixer indicator
        if (mixerIndicator != null) {
            mixerIndicator.setVisibility(View.VISIBLE);
        }
    }

    private void showAudioSelectionDialog(AudioFile audioFile) {
        // If mixer mode is active, directly select as secondary audio
        if (mixerModeActive) {
            onSecondAudioSelected(audioFile);
            return;
        }
        
        // Otherwise, show dialog as before for normal mode
        String[] options;
        if (selectedAudioUri == null) {
            // No primary audio set yet, only show primary option
            options = new String[]{"Play as primary audio"};
        } else {
            // Primary audio is set, show both options
            options = new String[]{"Play as primary audio", "Set as second audio (mixer)"};
        }
        
        new AlertDialog.Builder(this)
            .setTitle("Select Audio Option")
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    // Primary audio
                    onAudioFileSelected(audioFile);
                } else if (which == 1) {
                    // Secondary audio
                    onSecondAudioSelected(audioFile);
                }
            })
            .show();
    }

    private void setupAudioAdapter() {
        audioAdapter = new AudioAdapter(filteredAudioFiles);
        
        // Set the click listener
        audioAdapter.setOnItemClickListener(audioFile -> {
            // Use the mixer mode to determine behavior
            if (mixerModeActive) {
                onSecondAudioSelected(audioFile);
            } else {
                // If not in mixer mode, either play directly or show dialog
                if (secondAudioUri != null) {
                    showAudioSelectionDialog(audioFile);
                } else {
                    onAudioFileSelected(audioFile);
                }
            }
        });
        
        // Set the options menu listener
        audioAdapter.setOnOptionsItemClickListener(new AudioAdapter.OnOptionsItemClickListener() {
            @Override
            public void onFileDetailsClick(AudioFile audioFile) {
                showFileDetailsDialog(audioFile);
            }

            @Override
            public void onRenameFileClick(AudioFile audioFile) {
                showRenameFileDialog(audioFile);
            }

            @Override
            public void onDeleteFileClick(AudioFile audioFile) {
                showDeleteConfirmationDialog(audioFile);
            }

            @Override
            public void onShareFileClick(AudioFile audioFile) {
                shareAudioFile(audioFile);
            }
        });
        
        audioRecyclerView.setAdapter(audioAdapter);
    }

    // Add the implementation for the file options methods
    private void showFileDetailsDialog(AudioFile audioFile) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.file_details_title);
        
        // Create the details view
        View view = getLayoutInflater().inflate(R.layout.dialog_file_details, null);
        TextView titleText = view.findViewById(R.id.detail_title);
        TextView durationText = view.findViewById(R.id.detail_duration);
        TextView sizeText = view.findViewById(R.id.detail_size);
        TextView pathText = view.findViewById(R.id.detail_path);
        
        // Set the details
        titleText.setText(audioFile.getTitle());
        durationText.setText(audioFile.getDuration());
        sizeText.setText(audioFile.getFormattedSize());
        pathText.setText(audioFile.getUri().toString());
        
        builder.setView(view);
        builder.setPositiveButton(R.string.ok, null);
        builder.show();
    }

    private void showRenameFileDialog(AudioFile audioFile) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.rename_file_title);
        
        // Create the input field
        final EditText input = new EditText(this);
        input.setText(audioFile.getTitle());
        builder.setView(input);
        
        // Add action buttons
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                renameFile(audioFile, newName);
            }
        });
        builder.setNegativeButton(R.string.cancel, null);
        
        builder.show();
    }

    private void renameFile(AudioFile audioFile, String newName) {
        // This is a placeholder - actual implementation would depend on your requirements
        // In a real app, you'd use MediaStore or ContentResolver to update the file name
        Toast.makeText(this, R.string.file_renamed, Toast.LENGTH_SHORT).show();
        
        // After successful rename, refresh the audio files list
        refreshAudioFiles();
    }

    private void showDeleteConfirmationDialog(AudioFile audioFile) {
        new AlertDialog.Builder(this)
            .setTitle("Delete File")
            .setMessage("Are you sure you want to delete " + audioFile.getTitle() + "?")
            .setPositiveButton("Delete", (dialog, which) -> {
                deleteFile(audioFile);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void deleteFile(AudioFile audioFile) {
        // This is a placeholder - actual implementation would depend on your requirements
        // In a real app, you'd use MediaStore or ContentResolver to delete the file
        Toast.makeText(this, R.string.file_deleted, Toast.LENGTH_SHORT).show();
        
        // After successful delete, refresh the audio files list
        refreshAudioFiles();
    }

    private void shareAudioFile(AudioFile audioFile) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("audio/*");
        shareIntent.putExtra(Intent.EXTRA_STREAM, audioFile.getUri());
        startActivity(Intent.createChooser(shareIntent, "Share audio file"));
    }

    // Update the updateAudioFilesList method to use the new setupAudioAdapter method
    private void updateAudioFilesList() {
        // Show empty view if no filtered files
        if (filteredAudioFiles.isEmpty()) {
            audioRecyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            
            // Update empty view text based on whether we're filtering or not
            if (!allAudioFiles.isEmpty() && !TextUtils.isEmpty(searchEditText.getText())) {
                emptyView.setText(R.string.no_matching_files);
            } else {
                emptyView.setText(R.string.no_audio_files);
            }
        } else {
            audioRecyclerView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
            
            // Setup adapter with filtered files
            setupAudioAdapter();
        }
    }

    // Add a new method to sort audio files
    private void sortAudioFiles() {
        if (allAudioFiles == null || allAudioFiles.isEmpty()) {
            return;
        }
        
        try {
            // Sort both lists based on current sort order
            Comparator<AudioFile> comparator = null;
            
            switch (currentSortOrder) {
                case SORT_BY_NAME_ASC:
                    // Sort by name (A-Z)
                    comparator = (a1, a2) -> a1.getTitle().compareToIgnoreCase(a2.getTitle());
                    Toast.makeText(this, getString(R.string.sort_by_name_asc), Toast.LENGTH_SHORT).show();
                    break;
                
                case SORT_BY_NAME_DESC:
                    // Sort by name (Z-A)
                    comparator = (a1, a2) -> a2.getTitle().compareToIgnoreCase(a1.getTitle());
                    Toast.makeText(this, getString(R.string.sort_by_name_desc), Toast.LENGTH_SHORT).show();
                    break;
                
                case SORT_BY_DURATION_ASC:
                case SORT_BY_DURATION_DESC:
                    // Use our new method for duration sorting
                    sortByDuration(currentSortOrder == SORT_BY_DURATION_DESC);
                    return; // Exit early, as sortByDuration handles everything
                
                case SORT_BY_DATE_ASC:
                case SORT_BY_DATE_DESC:
                    // For date sorting, we need to query MediaStore again
                    sortByDate(currentSortOrder == SORT_BY_DATE_DESC);
                    return; // Exit early, as sortByDate handles the update
                
                case SORT_BY_SIZE_ASC:
                case SORT_BY_SIZE_DESC:
                    // For size sorting, query MediaStore again
                    sortBySize(currentSortOrder == SORT_BY_SIZE_DESC);
                    return; // Exit early, as sortBySize handles the update
            }
            
            if (comparator != null) {
                // Apply the sort comparator to both lists
                allAudioFiles.sort(comparator);
                
                // Update filtered list based on current search
                if (searchEditText != null && !TextUtils.isEmpty(searchEditText.getText())) {
                    filterAudioFiles(searchEditText.getText().toString());
                } else {
                    // No filter active, update filtered list directly
                    filteredAudioFiles.clear();
                    filteredAudioFiles.addAll(allAudioFiles);
                    // Update the UI
                    updateAudioFilesList();
                }
            }
        } catch (Exception e) {
            // Log any errors that might be happening during sorting
            Log.e(TAG, "Error during sort operation", e);
            Toast.makeText(this, "Error sorting files", Toast.LENGTH_SHORT).show();
        }
    }

    // Fix parseDuration to better handle large durations
    private long parseDuration(String durationStr) {
        try {
            // Format can be "m:ss" or "h:mm:ss"
            String[] parts = durationStr.split(":");
            
            if (parts.length == 2) {
                long minutes = Long.parseLong(parts[0]);
                long seconds = Long.parseLong(parts[1]);
                return (minutes * 60L) + seconds;
            } else if (parts.length == 3) {
                // Handle "h:mm:ss" format
                long hours = Long.parseLong(parts[0]);
                long minutes = Long.parseLong(parts[1]);
                long seconds = Long.parseLong(parts[2]);
                return (hours * 3600L) + (minutes * 60L) + seconds;
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing duration: " + durationStr, e);
        }
        return 0;
    }

    // Add method to sort by date (needs to query MediaStore again)
    private void sortByDate(boolean descending) {
        // Query for all audio files including date added
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.SIZE
        };

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String sortOrder = MediaStore.Audio.Media.DATE_ADDED + (descending ? " DESC" : " ASC");

        List<AudioFile> sortedFiles = new ArrayList<>();
        
        try {
            Cursor cursor = getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    sortOrder);

            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                int dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String title = cursor.getString(titleColumn);
                    long duration = cursor.getLong(durationColumn);
                    long size = cursor.getLong(sizeColumn);
                    long dateAdded = cursor.getLong(dateAddedColumn);  // Get dateAdded from cursor
                    
                    String durationFormatted = formatTime((int)duration);
                    Uri contentUri = Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));

                    sortedFiles.add(new AudioFile(title, durationFormatted, contentUri, id, size, dateAdded));
                }
                cursor.close();
                
                // Replace the current lists with the sorted list
                allAudioFiles.clear();
                allAudioFiles.addAll(sortedFiles);
                
                // Update filtered list based on current search
                if (searchEditText != null && !TextUtils.isEmpty(searchEditText.getText())) {
                    filterAudioFiles(searchEditText.getText().toString());
                } else {
                    // No search filter, update filtered list with all
                    filteredAudioFiles.clear();
                    filteredAudioFiles.addAll(allAudioFiles);
                    // Update the UI
                    updateAudioFilesList();
                }
                
                // Show appropriate toast
                Toast.makeText(this, getString(descending ? 
                    R.string.sort_by_date_desc : R.string.sort_by_date_asc), 
                    Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during date sorting", e);
            Toast.makeText(this, "Error sorting by date", Toast.LENGTH_SHORT).show();
        }
    }

    // Add the filterAudioFiles method
    private void filterAudioFiles(String query) {
        filteredAudioFiles.clear();
        
        if (TextUtils.isEmpty(query)) {
            // If query is empty, show all files
            filteredAudioFiles.addAll(allAudioFiles);
        } else {
            // Convert query to lowercase for case-insensitive search
            String lowerCaseQuery = query.toLowerCase();
            
            // Filter files that contain the query in their title
            for (AudioFile file : allAudioFiles) {
                if (file.getTitle().toLowerCase().contains(lowerCaseQuery)) {
                    filteredAudioFiles.add(file);
                }
            }
        }
        
        // Update the adapter with filtered results
        updateAudioFilesList();
    }

    // Add the toggleMixerMode method
    private void toggleMixerMode() {
        // Check if primary audio is selected before allowing mixer mode
        if (!mixerModeActive && selectedAudioUri == null) {
            Toast.makeText(this, "Please select a primary audio file first", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Toggle the mixer mode
        mixerModeActive = !mixerModeActive;
        
        // Toggle the text color with a more attractive design
        if (mixerModeActive) {
            mixerToggleButton.setTextColor(getResources().getColor(R.color.player_primary_button_bg));
            mixerToggleButton.setBackgroundResource(R.drawable.rounded_button_background);
            mixerToggleButton.setPadding(16, 8, 16, 8);
        } else {
            mixerToggleButton.setTextColor(getResources().getColor(android.R.color.darker_gray));
            mixerToggleButton.setBackground(null);
            mixerToggleButton.setPadding(8, 8, 8, 8);
        }
        
        // Show a toast to indicate the current mode
        Toast.makeText(this, 
            getString(mixerModeActive ? R.string.mixer_mode_on : R.string.mixer_mode_off), 
            Toast.LENGTH_SHORT).show();
    }

    // Update the sortBySize method to ensure it shows all files
    private void sortBySize(boolean descending) {
        try {
            // Log operation for debugging
            Log.d(TAG, "Sorting by size " + (descending ? "DESC" : "ASC"));
            
            // Query ALL audio files with their sizes directly from MediaStore
            String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED
            };

            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            
            // No sort order in query to prevent MediaStore limitations
            List<AudioFileSizePair> sizeInfoList = new ArrayList<>();
            
            Cursor cursor = getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    null);  // No sort order - we'll sort in memory
            
            long largestSize = 0;
            String largestFileName = "";
            
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                int dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                
                Log.d(TAG, "Found " + cursor.getCount() + " music files in query");
                
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String title = cursor.getString(titleColumn);
                    long duration = cursor.getLong(durationColumn);
                    long size = cursor.getLong(sizeColumn);
                    long dateAdded = cursor.getLong(dateAddedColumn);
                    
                    // Track the largest file for debugging
                    if (size > largestSize) {
                        largestSize = size;
                        largestFileName = title;
                    }
                    
                    // Log large files for debugging (files larger than 100MB)
                    if (size > 100 * 1024 * 1024) {
                        Log.d(TAG, "Large file found: " + title + " - Size: " + 
                               (size / (1024 * 1024)) + " MB");
                    }
                    
                    String durationFormatted;
                    try {
                        durationFormatted = formatTime((int)duration);
                    } catch (Exception e) {
                        if (duration > Integer.MAX_VALUE) {
                            long hours = duration / 3600000;
                            long minutes = (duration % 3600000) / 60000;
                            long seconds = (duration % 60000) / 1000;
                            durationFormatted = String.format("%d:%02d:%02d", hours, minutes, seconds);
                        } else {
                            durationFormatted = ">999 min";
                        }
                    }

                    Uri contentUri = Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));

                    AudioFile audioFile = new AudioFile(title, durationFormatted, contentUri, id, size, dateAdded);
                    sizeInfoList.add(new AudioFileSizePair(audioFile, size));
                }
                cursor.close();
                
                // Log the largest file found
                Log.d(TAG, "Largest file detected: " + largestFileName + " - Size: " + 
                       (largestSize / (1024 * 1024)) + " MB");
                
                // Sort in memory using Java's comparator with explicit long comparison
                sizeInfoList.sort((pair1, pair2) -> {
                    // Ensure we use Long.compare for proper comparison of large values
                    if (descending) {
                        return Long.compare(pair2.size, pair1.size);
                    } else {
                        return Long.compare(pair1.size, pair2.size);
                    }
                });
                
                // Log top 5 files after sorting to verify
                for (int i = 0; i < Math.min(5, sizeInfoList.size()); i++) {
                    AudioFileSizePair pair = sizeInfoList.get(i);
                    Log.d(TAG, "Top " + (i+1) + " file by size: " + pair.audioFile.getTitle() + 
                           " - Size: " + (pair.size / (1024 * 1024)) + " MB");
                }
                
                // Extract sorted AudioFile objects
                List<AudioFile> sortedFiles = new ArrayList<>();
                for (AudioFileSizePair pair : sizeInfoList) {
                    sortedFiles.add(pair.audioFile);
                }
                
                // Replace the current lists with the sorted list
                allAudioFiles.clear();
                allAudioFiles.addAll(sortedFiles);
                
                // Update filtered list based on current search
                if (searchEditText != null && !TextUtils.isEmpty(searchEditText.getText())) {
                    filterAudioFiles(searchEditText.getText().toString());
                } else {
                    // No search filter, update filtered list with all
                    filteredAudioFiles.clear();
                    filteredAudioFiles.addAll(allAudioFiles);
                    // Update the UI
                    updateAudioFilesList();
                }
                
                // Show appropriate toast
                Toast.makeText(this, getString(descending ? 
                    R.string.sort_by_size_desc : R.string.sort_by_size_asc), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during size sorting", e);
            Toast.makeText(this, "Error sorting by size", Toast.LENGTH_SHORT).show();
        }
    }

    // Add a helper class to associate AudioFile with its size for sorting
    private static class AudioFileSizePair {
        AudioFile audioFile;
        long size;
        
        AudioFileSizePair(AudioFile audioFile, long size) {
            this.audioFile = audioFile;
            this.size = size;
        }
    }

    // Update the sortByDuration method for better handling of very long files
    private void sortByDuration(boolean descending) {
        try {
            Log.d(TAG, "Sorting by duration " + (descending ? "DESC" : "ASC"));
            
            String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED,  // Include DATE_ADDED in projection
                    MediaStore.Audio.Media.DISPLAY_NAME
            };

            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            
            List<AudioFileDurationPair> durationInfoList = new ArrayList<>();
            List<AudioFile> sortedFiles = new ArrayList<>();  // Add this line
            
            Cursor cursor = getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    null);
            
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                int dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                int displayNameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
                
                Log.d(TAG, "Found " + cursor.getCount() + " music files in query");
                
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String title = cursor.getString(titleColumn);
                    long duration = cursor.getLong(durationColumn);
                    long size = cursor.getLong(sizeColumn);
                    long dateAdded = cursor.getLong(dateAddedColumn);  // Get dateAdded from cursor
                    
                    String displayName = title;
                    if (displayNameColumn != -1) {
                        String fullName = cursor.getString(displayNameColumn);
                        if (fullName != null && !fullName.isEmpty()) {
                            displayName = fullName;
                        }
                    }
                    
                    Uri contentUri = Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                    
                    String durationFormatted;
                    try {
                        durationFormatted = formatTime((int)duration);
                    } catch (Exception e) {
                        if (duration > Integer.MAX_VALUE) {
                            long hours = duration / 3600000;
                            long minutes = (duration % 3600000) / 60000;
                            long seconds = (duration % 60000) / 1000;
                            durationFormatted = String.format("%d:%02d:%02d", hours, minutes, seconds);
                        } else {
                            durationFormatted = ">999 min";
                        }
                        Log.e(TAG, "Error formatting long duration: " + duration, e);
                    }
                    
                    AudioFile audioFile = new AudioFile(displayName, durationFormatted, contentUri, id, size, dateAdded);
                    durationInfoList.add(new AudioFileDurationPair(audioFile, duration));
                }
                cursor.close();
                
                // Sort in memory using Java's comparator
                durationInfoList.sort((pair1, pair2) -> {
                    if (descending) {
                        return Long.compare(pair2.duration, pair1.duration);
                    } else {
                        return Long.compare(pair1.duration, pair2.duration);
                    }
                });
                
                // Extract sorted AudioFile objects
                for (AudioFileDurationPair pair : durationInfoList) {
                    sortedFiles.add(pair.audioFile);
                    
                    // Log the top 10 files if descending (longest first)
                    if (descending && sortedFiles.size() <= 10) {
                        Log.d(TAG, "Top duration file " + sortedFiles.size() + ": " + 
                               pair.audioFile.getTitle() + " - " + 
                               pair.audioFile.getDuration() + " (" + pair.duration + "ms)");
                    }
                }
                
                // Replace the current lists with the sorted list
                allAudioFiles.clear();
                allAudioFiles.addAll(sortedFiles);
                
                // Update filtered list based on current search
                if (searchEditText != null && !TextUtils.isEmpty(searchEditText.getText())) {
                    filterAudioFiles(searchEditText.getText().toString());
                } else {
                    // No search filter, update filtered list with all
                    filteredAudioFiles.clear();
                    filteredAudioFiles.addAll(allAudioFiles);
                    // Update the UI
                    updateAudioFilesList();
                }
                
                // Show appropriate toast
                Toast.makeText(this, getString(descending ? 
                    R.string.sort_by_duration_desc : R.string.sort_by_duration_asc), 
                    Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during duration sorting", e);
            Toast.makeText(this, "Error sorting by duration", Toast.LENGTH_SHORT).show();
        }
    }

    // Add a helper class for duration sorting
    private static class AudioFileDurationPair {
        AudioFile audioFile;
        long duration;
        
        AudioFileDurationPair(AudioFile audioFile, long duration) {
            this.audioFile = audioFile;
            this.duration = duration;
        }
    }

    /**
     * Refreshes the audio files list after operations like rename or delete
     */
    private void refreshAudioFiles() {
        // Simply reload all audio files from the MediaStore
        loadAudioFiles();
        
        // Log the refresh operation
        Log.d(TAG, "Audio files list refreshed");
        
        // If we're in a search, make sure to reapply the filter
        if (searchEditText != null && !TextUtils.isEmpty(searchEditText.getText())) {
            filterAudioFiles(searchEditText.getText().toString());
        }
    }

    private void startPlayback() {
        if (mediaPlayer != null) {
            // Check for notification permission on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                    return;
                }
            }

            // Start the service for background playback
            Intent serviceIntent = new Intent(this, AudioPlaybackService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            
            // ... existing playback code ...
        }
    }

    private void updateUIForPlaybackStopped() {
        runOnUiThread(() -> {
            safeSetImageResource(playPauseButton, R.drawable.ic_play_improved);
            isPlaying = false;
            seekBar.setProgress(0);
            currentTimeText.setText("0:00");

            // Reset MediaPlayer states
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.reset();
                    prepareMediaPlayer();
                } catch (Exception e) {
                    Log.e(TAG, "Error resetting media player", e);
                }
            }

            // Reset second MediaPlayer if active
            if (secondMediaPlayer != null) {
                try {
                    secondMediaPlayer.reset();
                    if (secondAudioUri != null) {
                        secondMediaPlayer.setDataSource(getApplicationContext(), secondAudioUri);
                        secondMediaPlayer.prepareAsync();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error resetting second media player", e);
                }
            }
        });
    }

    // Add this method to handle seeking in both primary and secondary tracks
    private void seekRelative(int offsetMs) {
        if (mediaPlayer == null || !mediaPlayer.isPlaying()) {
            return;
        }
        
        try {
            // Get current position of primary track
            int currentPosition = mediaPlayer.getCurrentPosition();
            int duration = mediaPlayer.getDuration();
            
            // Calculate new position with bounds checking
            int newPosition = Math.max(0, Math.min(duration, currentPosition + offsetMs));
            
            // Seek primary track
            mediaPlayer.seekTo(newPosition);
            
            // Update seek bar and time display
            seekBar.setProgress(newPosition);
            updateTimeText(newPosition, duration);
            
            // If second track is active, synchronize it
            if (secondMediaPlayer != null && secondAudioActive) {
                syncSecondPlayerPosition();
                
                // Show toast with track info
                String primaryFile = getFileNameFromUri(selectedAudioUri);
                String secondaryFile = getFileNameFromUri(secondAudioUri);
                if (primaryFile.length() > 20) primaryFile = primaryFile.substring(0, 17) + "...";
                if (secondaryFile.length() > 20) secondaryFile = secondaryFile.substring(0, 17) + "...";
                
                String direction = offsetMs > 0 ? "forward" : "backward";
                int seconds = Math.abs(offsetMs) / 1000;
                
                Toast.makeText(this, String.format("Seeking %s %ds\n▶ %s\n▶ %s", 
                    direction, seconds, primaryFile, secondaryFile), 
                    Toast.LENGTH_SHORT).show();
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error during seek operation", e);
        }
    }

    // Add this method after showBalanceDialog() to create a position sync dialog
    private void showPositionSyncDialog() {
        if (secondAudioUri == null || secondMediaPlayer == null) {
            Toast.makeText(this, "Please select a second audio file first", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Inflate the custom layout
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_position_sync, null);
        
        // Find views in the dialog
        TextView primaryTitleText = dialogView.findViewById(R.id.primaryTrackTitle);
        TextView secondaryTitleText = dialogView.findViewById(R.id.secondaryTrackTitle);
        SeekBar primarySeekBar = dialogView.findViewById(R.id.primaryPositionSeekBar);
        SeekBar secondarySeekBar = dialogView.findViewById(R.id.secondaryPositionSeekBar);
        TextView primaryTimeText = dialogView.findViewById(R.id.primaryPositionText);
        TextView secondaryTimeText = dialogView.findViewById(R.id.secondaryPositionText);
        
        // Set track titles
        String primaryTitle = getFileNameFromUri(selectedAudioUri);
        String secondaryTitle = getFileNameFromUri(secondAudioUri);
        
        if (primaryTitle.length() > 30) primaryTitle = primaryTitle.substring(0, 27) + "...";
        if (secondaryTitle.length() > 30) secondaryTitle = secondaryTitle.substring(0, 27) + "...";
        
        primaryTitleText.setText(primaryTitle);
        secondaryTitleText.setText(secondaryTitle);
        
        // Get current positions and durations
        int primaryPos = mediaPlayer.getCurrentPosition();
        int primaryDur = mediaPlayer.getDuration();
        int secondaryPos = secondMediaPlayer.getCurrentPosition();
        int secondaryDur = secondMediaPlayer.getDuration();
        
        // Set up the SeekBars
        primarySeekBar.setMax(primaryDur);
        primarySeekBar.setProgress(primaryPos);
        
        secondarySeekBar.setMax(secondaryDur);
        secondarySeekBar.setProgress(secondaryPos);
        
        // Update time displays initially
        primaryTimeText.setText(formatTime(primaryPos) + " / " + formatTime(primaryDur));
        secondaryTimeText.setText(formatTime(secondaryPos) + " / " + formatTime(secondaryDur));
        
        // Set up listeners for real-time updates
        primarySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    primaryTimeText.setText(formatTime(progress) + " / " + formatTime(primaryDur));
                    // Optional preview: mediaPlayer.seekTo(progress);
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        secondarySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    secondaryTimeText.setText(formatTime(progress) + " / " + formatTime(secondaryDur));
                    // Optional preview: secondMediaPlayer.seekTo(progress);
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        // Create and show the dialog
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.position_sync_title)
            .setView(dialogView)
            .setPositiveButton(R.string.apply, (dialogInterface, i) -> {
                // Apply the positions when user clicks Apply
                int newPrimaryPos = primarySeekBar.getProgress();
                int newSecondaryPos = secondarySeekBar.getProgress();
                
                // Apply new positions
                mediaPlayer.seekTo(newPrimaryPos);
                secondMediaPlayer.seekTo(newSecondaryPos);
                
                // Update UI
                seekBar.setProgress(newPrimaryPos);
                updateTimeText(newPrimaryPos, primaryDur);
                
                // Show confirmation toast
                Toast.makeText(this, R.string.positions_synced, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.cancel, null)
            .create();
        
        dialog.show();
    }

    // Add a method to show settings dialog
    private void showOtherSettingsDialog() {
        // Create submenu for settings
        PopupMenu popup = new PopupMenu(this, menuButton);
        popup.getMenuInflater().inflate(R.menu.settings_menu, popup.getMenu());
        
        popup.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.settings_seek_time) {
                showSeekTimeSettingsDialog();
                return true;
            }
            return false;
        });
        
        popup.show();
    }

    private void showSeekTimeSettingsDialog() {
        // Inflate the custom layout
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_seek_settings, null);
        
        // Get references to the EditText fields
        EditText forwardSeekEdit = dialogView.findViewById(R.id.forward_seek_seconds);
        EditText backwardSeekEdit = dialogView.findViewById(R.id.backward_seek_seconds);
        
        // Set current values
        forwardSeekEdit.setText(String.valueOf(seekForwardMs / 1000));
        backwardSeekEdit.setText(String.valueOf(seekBackwardMs / 1000));
        
        // Create and show the dialog
        new AlertDialog.Builder(this)
            .setTitle("Seek Time Settings")
            .setView(dialogView)
            .setPositiveButton("Save", (dialog, which) -> {
                // Get and validate input values
                try {
                    int forwardSeconds = Integer.parseInt(forwardSeekEdit.getText().toString().trim());
                    int backwardSeconds = Integer.parseInt(backwardSeekEdit.getText().toString().trim());
                    
                    // Validate range (1-300 seconds is reasonable)
                    if (forwardSeconds < 1 || forwardSeconds > 300 || 
                        backwardSeconds < 1 || backwardSeconds > 300) {
                        Toast.makeText(this, "Please enter values between 1 and 300 seconds", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    // Save the values
                    seekForwardMs = forwardSeconds * 1000;
                    seekBackwardMs = backwardSeconds * 1000;
                    
                    // Save to SharedPreferences
                    getSharedPreferences("audio_player_prefs", MODE_PRIVATE)
                        .edit()
                        .putInt("seek_forward_seconds", forwardSeconds)
                        .putInt("seek_backward_seconds", backwardSeconds)
                        .apply();
                    
                    Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
                    
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Please enter valid numbers", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // Add this method to your MainActivity
    private void animateButtonPress(View button) {
        button.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(100)
            .withEndAction(() -> 
                button.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start())
            .start();
    }

    // Add this helper method
    private void safeSetImageResource(ImageButton button, int resId) {
        if (button != null) {
            try {
                button.setImageResource(resId);
            } catch (Exception e) {
                Log.e(TAG, "Error setting image resource", e);
            }
        }
    }

    private void showSortMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        Menu menu = popup.getMenu();
        
        menu.add(0, 1, 0, "Name (A-Z)").setCheckable(true)
            .setChecked(currentSortOrder == SORT_BY_NAME_ASC);
        menu.add(0, 2, 0, "Name (Z-A)").setCheckable(true)
            .setChecked(currentSortOrder == SORT_BY_NAME_DESC);
        menu.add(0, 3, 0, "Duration (Shortest First)").setCheckable(true)
            .setChecked(currentSortOrder == SORT_BY_DURATION_ASC);
        menu.add(0, 4, 0, "Duration (Longest First)").setCheckable(true)
            .setChecked(currentSortOrder == SORT_BY_DURATION_DESC);
        menu.add(0, 5, 0, "Date (Oldest First)").setCheckable(true)
            .setChecked(currentSortOrder == SORT_BY_DATE_ASC);
        menu.add(0, 6, 0, "Date (Newest First)").setCheckable(true)
            .setChecked(currentSortOrder == SORT_BY_DATE_DESC);
        menu.add(0, 7, 0, "Size (Smallest First)").setCheckable(true)
            .setChecked(currentSortOrder == SORT_BY_SIZE_ASC);
        menu.add(0, 8, 0, "Size (Largest First)").setCheckable(true)
            .setChecked(currentSortOrder == SORT_BY_SIZE_DESC);

        popup.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            
            if (itemId == 1) {
                currentSortOrder = SORT_BY_NAME_ASC;
            } else if (itemId == 2) {
                currentSortOrder = SORT_BY_NAME_DESC;
            } else if (itemId == 3) {
                currentSortOrder = SORT_BY_DURATION_ASC;
            } else if (itemId == 4) {
                currentSortOrder = SORT_BY_DURATION_DESC;
            } else if (itemId == 5) {
                currentSortOrder = SORT_BY_DATE_ASC;
            } else if (itemId == 6) {
                currentSortOrder = SORT_BY_DATE_DESC;
            } else if (itemId == 7) {
                currentSortOrder = SORT_BY_SIZE_ASC;
            } else if (itemId == 8) {
                currentSortOrder = SORT_BY_SIZE_DESC;
            }
            
            sortAudioFiles();
            audioAdapter.notifyDataSetChanged();
            return true;
        });

        popup.show();
    }

    private void showABRepeatMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        Menu menu = popup.getMenu();
        
        menu.add(0, 1, 0, "Set Point A");
        menu.add(0, 2, 0, "Set Point B");
        menu.add(0, 3, 0, "Clear A-B Points");
        menu.add(0, 4, 0, abRepeatActive ? "Disable A-B Repeat" : "Enable A-B Repeat");

        popup.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            
            if (itemId == 1) {
                setPointA();
                return true;
            } else if (itemId == 2) {
                setPointB();
                return true;
            } else if (itemId == 3) {
                clearABPoints();
                return true;
            } else if (itemId == 4) {
                if (abRepeatActive) {
                    abRepeatActive = false;
                    updateABRepeatIndicator();
                } else {
                    enableABRepeat();
                }
                return true;
            }
            
            return false;
        });

        popup.show();
    }

    // Method implementations to fix unresolved references
    
    private void selectSecondAudio() {
        if (isPermissionGranted) {
            // Create intent to browse for audio file
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            
            try {
                // Use the second launcher explicitly for the second file
                secondAudioPickerLauncher.launch(intent);
            } catch (Exception e) {
                Log.e(TAG, "Error launching second audio picker", e);
                Toast.makeText(this, "Error opening file picker", Toast.LENGTH_SHORT).show();
            }
        } else {
            checkPermissions();
            Toast.makeText(this, "Permission required to access files", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showBalanceDialog() {
        if (secondAudioUri == null || secondMediaPlayer == null) {
            Toast.makeText(this, "Please select a second audio file first", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Inflate the custom layout
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_audio_balance, null);
        
        SeekBar firstAudioSeekBar = dialogView.findViewById(R.id.firstAudioVolumeSeekBar);
        SeekBar secondAudioSeekBar = dialogView.findViewById(R.id.secondAudioVolumeSeekBar);
        
        // Set initial values based on current volume
        firstAudioSeekBar.setProgress((int)(firstAudioVolume * 100));
        secondAudioSeekBar.setProgress((int)(secondAudioVolume * 100));
        
        // Create method to apply volume immediately
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Audio Balance")
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
                // Save the new volumes and apply them
                applyAudioVolumes(
                    firstAudioSeekBar.getProgress() / 100f,
                    secondAudioSeekBar.getProgress() / 100f
                );
                Toast.makeText(this, "Balance saved", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, (dialogInterface, i) -> {
                // Restore original volumes on cancel
                applyAudioVolumes(firstAudioVolume, secondAudioVolume);
            })
            .create();
        
        // Set real-time volume adjustment as user moves the seekbars
        firstAudioSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    float volume = progress / 100f;
                    mediaPlayer.setVolume(volume, volume);
                }
            }
            
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        secondAudioSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && secondMediaPlayer != null) {
                    float volume = progress / 100f;
                    secondMediaPlayer.setVolume(volume, volume);
                }
            }
            
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        dialog.show();
    }
    
    private void clearSecondAudio() {
        if (secondMediaPlayer != null) {
            try {
                if (secondMediaPlayer.isPlaying()) {
                    secondMediaPlayer.stop();
                }
                secondMediaPlayer.release();
                secondMediaPlayer = null;
            } catch (Exception e) {
                Log.e(TAG, "Error clearing second audio", e);
            }
        }
        
        secondAudioUri = null;
        secondAudioActive = false;
        
        // Turn off mixer mode when second audio is cleared
        mixerModeActive = false;
        
        // Reset color to gray
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mixerToggleButton.setTextColor(getResources().getColor(android.R.color.darker_gray, null));
        } else {
            mixerToggleButton.setTextColor(getResources().getColor(android.R.color.darker_gray));
        }
        
        // Hide the mixer indicator
        if (mixerIndicator != null) {
            mixerIndicator.setVisibility(View.GONE);
        }
        
        Toast.makeText(this, "Second audio cleared", Toast.LENGTH_SHORT).show();

        if (syncPositionButton != null) {
            syncPositionButton.setVisibility(View.GONE);
        }
    }
    
    private void setPointA() {
        if (mediaPlayer == null) {
            Toast.makeText(this, "Please select an audio file first", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            int currentPosition = mediaPlayer.getCurrentPosition();
            pointA = currentPosition;
            String pointATime = formatTime(pointA);
            
            // Log point setting
            Log.d(TAG, "Set A-B repeat point A: " + pointATime + " (" + pointA + "ms)");
            
            // Update UI
            Toast.makeText(this, "Point A set: " + pointATime, Toast.LENGTH_SHORT).show();
            updateABRepeatIndicator();
        } catch (Exception e) {
            Log.e(TAG, "Error setting point A", e);
            Toast.makeText(this, "Error setting point A", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void setPointB() {
        if (mediaPlayer == null) {
            Toast.makeText(this, "Please select an audio file first", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            int currentPosition = mediaPlayer.getCurrentPosition();
            
            // Ensure point B is after point A
            if (pointA != -1 && currentPosition <= pointA) {
                Toast.makeText(this, "Point B must be after Point A", Toast.LENGTH_SHORT).show();
                return;
            }
            
            pointB = currentPosition;
            String pointBTime = formatTime(pointB);
            
            // Log point setting
            Log.d(TAG, "Set A-B repeat point B: " + pointBTime + " (" + pointB + "ms)");
            
            // Update UI
            Toast.makeText(this, "Point B set: " + pointBTime, Toast.LENGTH_SHORT).show();
            updateABRepeatIndicator();
            
            // If both points are set, enable A-B repeat
            if (pointA != -1 && pointB != -1) {
                enableABRepeat();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting point B", e);
            Toast.makeText(this, "Error setting point B", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void clearABPoints() {
        pointA = -1;
        pointB = -1;
        abRepeatActive = false;
        
        updateABRepeatIndicator();
        
        Toast.makeText(this, "A-B repeat cleared", Toast.LENGTH_SHORT).show();
    }
    
    private void updateABRepeatIndicator() {
        if (abRepeatIndicator != null) {
            if (pointA != -1 || pointB != -1 || abRepeatActive) {
                String indicatorText = "A-B: ";
                indicatorText += (pointA != -1) ? formatTime(pointA) : "--:--";
                indicatorText += " to ";
                indicatorText += (pointB != -1) ? formatTime(pointB) : "--:--";
                
                abRepeatIndicator.setText(indicatorText);
                abRepeatIndicator.setVisibility(View.VISIBLE);
            } else {
                abRepeatIndicator.setVisibility(View.GONE);
            }
        }
    }
    
    private void enableABRepeat() {
        if (pointA != -1 && pointB != -1) {
            abRepeatActive = true;
            updateABRepeatIndicator();
            Toast.makeText(this, "A-B repeat active", Toast.LENGTH_SHORT).show();
        }
    }
    
    // Method to apply volumes to both audio players
    private void applyAudioVolumes(float firstVolume, float secondVolume) {
        // Store the values
        firstAudioVolume = firstVolume;
        secondAudioVolume = secondVolume;
        
        // Apply to players
        try {
            if (mediaPlayer != null) {
                mediaPlayer.setVolume(firstAudioVolume, firstAudioVolume);
            }
            
            if (secondMediaPlayer != null && secondAudioActive) {
                secondMediaPlayer.setVolume(secondAudioVolume, secondAudioVolume);
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error setting audio volumes", e);
        }
    }
}