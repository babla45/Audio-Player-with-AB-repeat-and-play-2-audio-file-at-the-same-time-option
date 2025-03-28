package com.example.aud_player;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
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
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

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

    private int currentSortOrder = SORT_BY_NAME_ASC; // Default sort order

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
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
            mixerIndicator = findViewById(R.id.mixerIndicator);
            
            // New view initializations for audio files list
            audioRecyclerView = findViewById(R.id.audioRecyclerView);
            emptyView = findViewById(R.id.emptyView);
            
            // Setup RecyclerView
            audioRecyclerView.setLayoutManager(new LinearLayoutManager(this));
            
            // Initialize search EditText and clear button
            searchEditText = findViewById(R.id.searchEditText);
            clearSearchButton = findViewById(R.id.clearSearchButton);
            
            // Initialize mixer toggle button
            mixerToggleButton = findViewById(R.id.mixerToggleButton);
            
            // Set default color to gray
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mixerToggleButton.setTextColor(getResources().getColor(android.R.color.darker_gray, null));
            } else {
                mixerToggleButton.setTextColor(getResources().getColor(android.R.color.darker_gray));
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
            if (mediaPlayer != null && selectedAudioUri != null) {
                try {
                    Intent serviceIntent = new Intent(this, AudioPlaybackService.class);
                    if (isPlaying) {
                        mediaPlayer.pause();
                        
                        // Also pause the second audio if it's active
                        if (secondMediaPlayer != null && secondAudioActive) {
                            secondMediaPlayer.pause();
                        }
                        
                        playPauseButton.setIconResource(R.drawable.ic_play);
                        isPlaying = false;
                        serviceIntent.setAction("ACTION_PAUSE");
                    } else {
                        mediaPlayer.start();
                        
                        // Also start the second audio if it's ready
                        if (secondMediaPlayer != null && secondAudioActive) {
                            secondMediaPlayer.start();
                        }
                        
                        playPauseButton.setIconResource(R.drawable.ic_pause);
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
        
        stopButton.setOnClickListener(v -> {
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                        
                        // Also stop the second audio if it's active
                        if (secondMediaPlayer != null && secondAudioActive) {
                            try {
                                if (secondMediaPlayer.isPlaying()) {
                                    secondMediaPlayer.stop();
                                }
                                // Prepare it again properly
                                secondMediaPlayer.reset();
                                secondMediaPlayer.setDataSource(getApplicationContext(), secondAudioUri);
                                secondMediaPlayer.prepareAsync();
                            } catch (Exception e) {
                                Log.e(TAG, "Error stopping/resetting second player", e);
                                // If reset fails, release and recreate
                                try {
                                    secondMediaPlayer.release();
                                    secondMediaPlayer = null;
                                    prepareSecondMediaPlayer();
                                } catch (Exception ex) {
                                    Log.e(TAG, "Error recreating second player", ex);
                                }
                            }
                        }
                        
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
                        
                        // Synchronize the second player position when user seeks
                        if (secondMediaPlayer != null && secondAudioActive) {
                            // Calculate relative position in second audio
                            float mainDuration = mediaPlayer.getDuration();
                            float secondDuration = secondMediaPlayer.getDuration();
                            float positionPercentage = progress / mainDuration;
                            int secondPosition = (int) (positionPercentage * secondDuration);
                            
                            // Seek second player to the relative position
                            secondMediaPlayer.seekTo(secondPosition);
                        }
                        
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
                    seekBar.setMax(mp.getDuration());
                    totalTimeText.setText(formatTime(mp.getDuration()));
                    currentTimeText.setText("0:00");
                    
                    // Check if we should auto-play
                    if (shouldAutoPlay) {
                        // Start playing automatically
                        mediaPlayer.start();
                        playPauseButton.setIconResource(R.drawable.ic_pause);
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
                        playPauseButton.setIconResource(R.drawable.ic_play);
                        isPlaying = false;
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
                playPauseButton.setIconResource(R.drawable.ic_play);
                isPlaying = false;
                shouldAutoPlay = false; // Reset flag on error
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
            shouldAutoPlay = false; // Reset flag on error
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            Log.e(TAG, "Media player error", e);
            Toast.makeText(this, "Error with media player", Toast.LENGTH_SHORT).show();
            shouldAutoPlay = false; // Reset flag on error
        }
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
        popup.getMenuInflater().inflate(R.menu.audio_player_menu, popup.getMenu());
        
        // Since we now show audio files directly, we can remove Browse Files options from menu
        MenuItem browseItem = popup.getMenu().findItem(R.id.menu_browse_files);
        if (browseItem != null) {
            browseItem.setVisible(false);
        }
        
        MenuItem browseSecondItem = popup.getMenu().findItem(R.id.mixer_browse_second);
        if (browseSecondItem != null) {
            browseSecondItem.setVisible(false);
        }
        
        // Check the current sort method
        Menu sortMenu = popup.getMenu().findItem(R.id.menu_sort).getSubMenu();
        if (sortMenu != null) {
            MenuItem itemToCheck = null;
            switch (currentSortOrder) {
                case SORT_BY_NAME_ASC:
                    itemToCheck = sortMenu.findItem(R.id.sort_name_asc);
                    break;
                case SORT_BY_NAME_DESC:
                    itemToCheck = sortMenu.findItem(R.id.sort_name_desc);
                    break;
                case SORT_BY_DURATION_ASC:
                    itemToCheck = sortMenu.findItem(R.id.sort_duration_asc);
                    break;
                case SORT_BY_DURATION_DESC:
                    itemToCheck = sortMenu.findItem(R.id.sort_duration_desc);
                    break;
                case SORT_BY_DATE_ASC:
                    itemToCheck = sortMenu.findItem(R.id.sort_date_asc);
                    break;
                case SORT_BY_DATE_DESC:
                    itemToCheck = sortMenu.findItem(R.id.sort_date_desc);
                    break;
                case SORT_BY_SIZE_ASC:
                    itemToCheck = sortMenu.findItem(R.id.sort_size_asc);
                    break;
                case SORT_BY_SIZE_DESC:
                    itemToCheck = sortMenu.findItem(R.id.sort_size_desc);
                    break;
            }
            if (itemToCheck != null) {
                itemToCheck.setChecked(true);
            }
        }
        
        popup.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            
            // Handle sort menu items
            if (itemId == R.id.sort_name_asc) {
                currentSortOrder = SORT_BY_NAME_ASC;
                sortAudioFiles();
                return true;
            } else if (itemId == R.id.sort_name_desc) {
                currentSortOrder = SORT_BY_NAME_DESC;
                sortAudioFiles();
                return true;
            } else if (itemId == R.id.sort_duration_asc) {
                currentSortOrder = SORT_BY_DURATION_ASC;
                sortAudioFiles();
                return true;
            } else if (itemId == R.id.sort_duration_desc) {
                currentSortOrder = SORT_BY_DURATION_DESC;
                sortAudioFiles();
                return true;
            } else if (itemId == R.id.sort_date_asc) {
                currentSortOrder = SORT_BY_DATE_ASC;
                sortAudioFiles();
                return true;
            } else if (itemId == R.id.sort_date_desc) {
                currentSortOrder = SORT_BY_DATE_DESC;
                sortAudioFiles();
                return true;
            } else if (itemId == R.id.sort_size_asc) {
                currentSortOrder = SORT_BY_SIZE_ASC;
                sortAudioFiles();
                return true;
            } else if (itemId == R.id.sort_size_desc) {
                currentSortOrder = SORT_BY_SIZE_DESC;
                sortAudioFiles();
                return true;
            }
            
            // Handle existing menu items
            // Cancel any existing timer
            if (sleepTimer != null) {
                sleepTimer.cancel();
                timerActive = false;
            }
            
            // Handle timer menu items
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
            
            // Handle Mixer menu items with new browse option
            else if (itemId == R.id.mixer_select_second) {
                selectSecondAudio();
                return true;
            } else if (itemId == R.id.mixer_browse_second) {
                browseAudioFiles(true);
                return true;
            } else if (itemId == R.id.mixer_clear_second) {
                clearSecondAudio();
                return true;
            } else if (itemId == R.id.mixer_balance) {
                showBalanceDialog();
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
    
    private void selectSecondAudio() {
        if (isPermissionGranted) {
            // First check if we have a valid first audio file
            if (selectedAudioUri == null) {
                Toast.makeText(this, "Please select a primary audio file first", 
                    Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Save current states before selection
            checkMediaPlayersState();
            
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            
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
        
        Toast.makeText(this, R.string.second_file_cleared, Toast.LENGTH_SHORT).show();
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
            .setTitle(R.string.balance_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.ok, (dialogInterface, i) -> {
                // Save the new volumes and apply them
                applyAudioVolumes(
                    firstAudioSeekBar.getProgress() / 100f,
                    secondAudioSeekBar.getProgress() / 100f
                );
                Toast.makeText(this, R.string.balance_saved, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.cancel, (dialogInterface, i) -> {
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
        // Initial load with default order
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DISPLAY_NAME  // Add DISPLAY_NAME to get the full filename with extension
        };

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        
        // Determine sort order for initial load
        String sortOrder;
        switch (currentSortOrder) {
            case SORT_BY_NAME_DESC:
                sortOrder = MediaStore.Audio.Media.TITLE + " DESC";
                break;
            case SORT_BY_DURATION_ASC:
                sortOrder = MediaStore.Audio.Media.DURATION + " ASC";
                break;
            case SORT_BY_DURATION_DESC:
                sortOrder = MediaStore.Audio.Media.DURATION + " DESC";
                break;
            case SORT_BY_DATE_ASC:
                sortOrder = MediaStore.Audio.Media.DATE_ADDED + " ASC";
                break;
            case SORT_BY_DATE_DESC:
                sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC";
                break;
            case SORT_BY_SIZE_ASC:
                sortOrder = MediaStore.Audio.Media.SIZE + " ASC";
                break;
            case SORT_BY_SIZE_DESC:
                sortOrder = MediaStore.Audio.Media.SIZE + " DESC";
                break;
            case SORT_BY_NAME_ASC:
            default:
                sortOrder = MediaStore.Audio.Media.TITLE + " ASC";
                break;
        }

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

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String title = cursor.getString(titleColumn);
                long duration = cursor.getLong(durationColumn);
                long size = cursor.getLong(sizeColumn);
                
                // Get the display name with extension
                String displayName = title;
                
                // Try to get the full filename with extension if available
                int displayNameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
                if (displayNameColumn != -1) {
                    String fullName = cursor.getString(displayNameColumn);
                    if (fullName != null && !fullName.isEmpty()) {
                        displayName = fullName;
                    }
                }
                
                // Make sure we're handling duration properly
                String durationFormatted;
                try {
                    durationFormatted = formatTime((int)duration);
                } catch (Exception e) {
                    // Fallback in case of formatting errors
                    durationFormatted = "0:00";
                    Log.e(TAG, "Error formatting duration: " + duration, e);
                }

                Uri contentUri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));

                // Use the display name with extension instead of just the title
                allAudioFiles.add(new AudioFile(displayName, durationFormatted, contentUri, id, size));
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
            playPauseButton.setIconResource(R.drawable.ic_pause);
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
                MediaStore.Audio.Media.SIZE  // Add SIZE to projection
        };

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String sortOrder = MediaStore.Audio.Media.DATE_ADDED + (descending ? " DESC" : " ASC");

        List<AudioFile> sortedFiles = new ArrayList<>();
        
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
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE); // Get size column

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                String title = cursor.getString(titleColumn);
                long duration = cursor.getLong(durationColumn);
                long size = cursor.getLong(sizeColumn); // Get the file size
                String durationFormatted = formatTime((int)duration);

                Uri contentUri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));

                // Add the size parameter to the constructor
                sortedFiles.add(new AudioFile(title, durationFormatted, contentUri, id, size));
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
            if (descending) {
                Toast.makeText(this, getString(R.string.sort_by_date_desc), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.sort_by_date_asc), Toast.LENGTH_SHORT).show();
            }
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
        
        // Toggle the text color with backward compatibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mixerToggleButton.setTextColor(
                mixerModeActive ? 
                getResources().getColor(android.R.color.holo_blue_dark, null) : 
                getResources().getColor(android.R.color.darker_gray, null));
        } else {
            // For older Android versions
            mixerToggleButton.setTextColor(
                mixerModeActive ? 
                getResources().getColor(android.R.color.holo_blue_dark) : 
                getResources().getColor(android.R.color.darker_gray));
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
                
                Log.d(TAG, "Found " + cursor.getCount() + " music files in query");
                
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String title = cursor.getString(titleColumn);
                    long duration = cursor.getLong(durationColumn);
                    long size = cursor.getLong(sizeColumn);
                    
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

                    AudioFile audioFile = new AudioFile(title, durationFormatted, contentUri, id, size);
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
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DISPLAY_NAME  // Add DISPLAY_NAME
            };

            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            
            // No sorting in the query - we'll sort in memory
            List<AudioFileDurationPair> durationInfoList = new ArrayList<>();
            
            Cursor cursor = getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    null);  // No sort order - to avoid MediaStore limitations
            
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                
                Log.d(TAG, "Found " + cursor.getCount() + " music files in query");
                
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String title = cursor.getString(titleColumn);
                    long duration = cursor.getLong(durationColumn);
                    long size = cursor.getLong(sizeColumn);
                    
                    // Get the display name with extension
                    String displayName = title;
                    int displayNameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
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
                        // Handle extremely long durations that might cause integer overflow
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
                    
                    // Updated constructor with size parameter
                    AudioFile audioFile = new AudioFile(displayName, durationFormatted, contentUri, id, size);
                    durationInfoList.add(new AudioFileDurationPair(audioFile, duration));
                    
                    // Log very long durations for debugging
                    if (duration > 10800000) { // > 3 hours
                        Log.d(TAG, "Long duration file found: " + title + 
                               " - Duration: " + duration + "ms (" + durationFormatted + ")");
                    }
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
                List<AudioFile> sortedFiles = new ArrayList<>();
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
}