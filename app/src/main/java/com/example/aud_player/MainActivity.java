package com.example.aud_player;

import static android.telephony.TelephonyManager.*;
import static android.telephony.TelephonyManager.EXTRA_STATE_RINGING;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.telephony.TelephonyManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.PlaybackParams;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.EnvironmentalReverb;
import android.media.audiofx.NoiseSuppressor;
import android.media.audiofx.Virtualizer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.app.Dialog;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.RadioGroup;
import android.text.InputType;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.view.Gravity;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

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
import java.util.Locale;
import java.util.Random;
import android.widget.RadioButton;
import android.content.DialogInterface;
import android.os.Environment;
import android.content.ContentValues;
import androidx.documentfile.provider.DocumentFile;
import android.app.PendingIntent;
import android.content.IntentSender;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.view.Window;
import android.view.WindowManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AudioPlayerApp";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REQUEST_BROWSE_AUDIO = 1001;
    private static final int REQUEST_BROWSE_SECOND_AUDIO = 1002;
    private static final int REQUEST_WRITE_PERMISSION = 2001;
    private static final int REQUEST_DELETE_PERMISSION = 2002;
    private static final int REQUEST_SAF_EDIT = 2003;
    private AudioFile pendingRenameFile;
    private String pendingRenameNewName;
    private AudioFile pendingDeleteFile;

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
    private static final int TIMER_ACTION_END_OF_SONG = 2; // New action for end of song
    private int timerAction = TIMER_ACTION_CLOSE_APP; // Default is close app

    private static final int PLAYBACK_MODE_REPEAT_CURRENT = 0;
    private static final int PLAYBACK_MODE_NEXT_IN_LIST = 1;
    private static final int PLAYBACK_MODE_RANDOM = 2;
    private static final float MORE_BUTTON_SWIPE_THRESHOLD_DP = 22f;
    private static final String PREFS_MIXER_PRESETS_KEY = "saved_mixer_presets_v1";
    // Allow swipe gesture only from the small lower area (bottom ~18% of screen).
    private static final float BOTTOM_SWIPE_START_REGION_RATIO = 0.82f;
    private float bottomSwipeStartX = 0f;
    private float bottomSwipeStartY = 0f;
    private boolean bottomSwipeHandled = false;

    private int currentPlaybackMode = PLAYBACK_MODE_NEXT_IN_LIST; // Default is list play
    private Uri lastPlayedUri = null; // Track last played song for random mode

    // Random-mode playback history: the actual order songs were played in, so
    // Next/Previous move through the sequence instead of re-randomizing.
    // randomHistoryPosition points at the currently playing entry.
    private static final int RANDOM_HISTORY_MAX_SIZE = 1000;
    private final List<Uri> randomHistory = new ArrayList<>();
    private int randomHistoryPosition = -1;

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
    private FolderAdapter folderAdapter;
    private boolean folderViewEnabled = false;
    private String currentFolderName = null; // non-null while browsing a folder's songs
    private static final String PREF_FOLDER_VIEW = "folder_view_enabled";

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

    private static final String PREFS_NAME = "audio_player_prefs";
    private static final String PREF_NOW_PLAYING_URI = "now_playing_uri";
    private static final String PREF_NOW_PLAYING_TITLE = "now_playing_title";
    private static final String PREF_ALLOW_AUDIO_MIX = "allow_audio_mix";
    private static final String PREF_AUTO_SLIDE_TO_CURRENT = "auto_slide_to_current_song";
    private static final String PREF_THEME_MODE = "theme_mode";
    private static final String PREF_MINI_PLAYER_THEME = "mini_player_theme";
    private static final String PREF_CUSTOM_APP_COLOR = "custom_app_color";
    private static final String PREF_CUSTOM_MINI_PLAYER_COLOR = "custom_mini_player_color";
    private static final String PREF_RESET_SPEED = "reset_button_speed";
    private static final String PREF_RESET_PITCH = "reset_button_pitch";
    private static final String PREF_RESET_EQUALIZER = "reset_button_equalizer";
    private static final String PREF_RESET_BOOST = "reset_button_boost";
    private static final String PREF_VOICE_FORMANT = "voice_formant";
    private static final String PREF_VOICE_BASS = "voice_bass";
    private static final String PREF_VOICE_REVERB = "voice_reverb";
    private static final String PREF_VOICE_TREBLE = "voice_treble";
    private static final String PREF_VOICE_CLARITY = "voice_clarity";
    private static final String PREF_VOICE_ECHO = "voice_echo";
    private static final String PREF_VOICE_DISTORTION = "voice_distortion";
    private static final String PREF_VOICE_VIBRATO = "voice_vibrato";
    private static final String PREF_VOICE_DEPTH = "voice_depth";
    private static final String PREF_VOICE_ROBOT = "voice_robot";
    private static final String PREF_VOICE_NOISE_REDUCTION = "voice_noise_reduction";
    private static final String PREF_VOICE_AUTOTUNE = "voice_autotune";
    private static final int THEME_MODE_WHITE = 0;
    private static final int THEME_MODE_BLUISH_BLACK = 1;
    private static final int THEME_MODE_MILKY = 2;
    private static final int THEME_MODE_CUSTOM = 3;
    private static final int MINI_PLAYER_THEME_CURRENT = 0;
    private static final int MINI_PLAYER_THEME_WHITE = 1;
    private static final int MINI_PLAYER_THEME_CUSTOM = 2;
    private static final int DEFAULT_CUSTOM_APP_COLOR = 0xFF5C719A;
    private static final int DEFAULT_CUSTOM_MINI_PLAYER_COLOR = 0xFF4A5D87;

    // Add these constants near the top of your MainActivity class
    private static final int SEEK_FORWARD_MS = 10000; // 10 seconds
    private static final int SEEK_BACKWARD_MS = 10000; // 10 seconds

    // Add these UI elements as class members
    private View seekBackwardButton;
    private View seekForwardButton;
    private TextView seekBackwardSecondsText;
    private TextView seekForwardSecondsText;

    // New redesigned UI elements
    private LinearLayout miniPlayerBar;
    private LinearLayout expandedPlayerControls;
    private TextView miniPlayerTitle;
    private TextView miniPlayerSubtitle;
    private ImageButton miniPlayPauseBtn;
    private SeekBar miniProgressBar;
    private boolean miniProgressSeeking;
    private BottomNavigationView bottomNavigation;
    private boolean playerExpanded = false;

    // Change these from constants to instance variables
    private int seekForwardMs = 10000; // Default 10 seconds
    private int seekBackwardMs = 10000; // Default 10 seconds

    // Add this variable declaration with your other media player variables
    private MediaPlayer mediaPlayer = null;
    private Equalizer equalizer = null;
    private BassBoost bassBoost = null;
    private Virtualizer virtualizer = null;
    private EnvironmentalReverb environmentalReverb = null;
    // Echo uses a separate reverb instance so it renders independently of the
    // Reverb control. Treble/Clarity/Distortion are applied as ADDITIVE OFFSETS on
    // top of the main equalizer's band levels (voiceFxBandOffsets + eqBaseBandLevels).
    // A second Equalizer instance on the same session is NOT used: it takes control
    // of the session's equalizer engine away from the main equalizer, which made
    // EQ presets stop affecting the audio.
    private EnvironmentalReverb echoReverb = null;
    private NoiseSuppressor noiseSuppressor = null;
    private short[] voiceFxBandOffsets = null;
    private short[] eqBaseBandLevels = null;
    private int equalizerSessionId = -1;
    private int[] bandSeekIds = null;

    // Add these missing variables
    private Uri selectedAudioUri = null;
    private boolean isPermissionGranted = false;
    private boolean isPlaying = false;
    
    // Call handling variables
    private boolean wasPlayingBeforeCall = false;
    private PhoneStateReceiver phoneStateReceiver;

    // Audio focus handling
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean pausedByAudioFocusLoss = false;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = focusChange -> {
        // When the playback service is bound it owns the audio focus and the
        // shared MediaPlayer. Reacting here as well would fight the service:
        // its focus re-request on a notification resume delivers a LOSS to
        // this listener, which would pause the player the service just started.
        // Real interruptions are handled by the service's own listener, which
        // broadcasts PLAYBACK_PAUSED/RESUMED back to this UI.
        if (serviceBound && audioService != null) {
            return;
        }
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // Permanent loss of audio focus: pause playback
                if (!isAllowAudioMixEnabled() && mediaPlayer != null && mediaPlayer.isPlaying()) {
                    pausedByAudioFocusLoss = true;
                    pausePlayback();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Transient loss (e.g., call): pause
                if (!isAllowAudioMixEnabled() && mediaPlayer != null && mediaPlayer.isPlaying()) {
                    pausedByAudioFocusLoss = true;
                    pausePlayback();
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                // Regained focus: resume only if we paused due to focus
                if (pausedByAudioFocusLoss) {
                    pausedByAudioFocusLoss = false;
                    resumePlayback();
                }
                break;
        }
    };

    // Make sure these are properly declared in your class
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable runnable;
    // Guards async MediaPlayer callbacks so stale events from a previous
    // quick tap do not interrupt the newly selected song.
    private int prepareRequestVersion = 0;
    // Some devices/providers intermittently fail on first prepare for a URI.
    // Track one short auto-retry per URI to avoid forcing the user to tap twice.
    private Uri lastAutoRetriedUri = null;
    private long lastAutoRetryAtMs = 0L;

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
    private float currentPitch = 1.0f;
    private float currentFormant = 1.0f;
    private int voiceBassStrength = 0;
    private int voiceReverbLevel = 0;
    private int voiceTrebleLevel = 0;      // -1000..1000 per-mille (-100%..+100%)
    private int voiceClarityLevel = 0;     // 0..1000 per-mille
    private int voiceEchoLevel = 0;        // 0..1000 per-mille
    private int voiceDistortionLevel = 0;  // 0..1000 per-mille
    private int voiceVibratoDepth = 0;     // 0..1000 per-mille
    private long voiceVibratoStartMs = 0L; // vibrato phase reference
    private int voiceDepthLevel = 0;       // -1000..1000 per-mille (thinner..deeper)
    private boolean voiceRobotEnabled = false;
    private boolean voiceNoiseReductionEnabled = false;
    private boolean voiceAutoTuneEnabled = false;
    private float currentBoost = 1.0f;

    // Add PlaylistDatabaseHelper as a class field
    private PlaylistDatabaseHelper playlistDbHelper;
    private String currentPlaylistId = null;
    private List<AudioFile> currentPlaylistSongs = new ArrayList<>();
    private int currentPlaylistIndex = -1;

    // Add UI elements
    private LinearLayout playlistInfoContainer;
    private TextView playlistNameText;
    private Button showAllSongsButton;
    private boolean inPlaylistView = false;
    private Playlist currentPlaylist = null;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioPlaybackService.LocalBinder binder = (AudioPlaybackService.LocalBinder) service;
            audioService = binder.getService();
            serviceBound = true;

            // If we were killed (e.g. swiped away) while the foreground service kept playing,
            // restore the UI by adopting the already-playing MediaPlayer from the service.
            syncFromServiceToUi();

            // Normal flow: pass the current MediaPlayer instances to the service with title
            if (mediaPlayer != null && secondMediaPlayer != null) {
                String currentTitle = fileNameText != null ? fileNameText.getText().toString() : "Audio Player";
                // Only hand over the second player if it's actually active, so the
                // service's secondAudioActive flag mirrors the true mixer state
                audioService.setMediaPlayers(mediaPlayer, secondAudioActive ? secondMediaPlayer : null, currentTitle);
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
                        updateMiniPlayer();
                        saveNowPlayingPrefs();

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

    private static class SavedMixerPreset {
        String name;
        String primaryUri;
        String secondaryUri;
        float firstVolume;
        float secondVolume;
        boolean useIndividualSpeeds;
        float globalSpeed;
        float primarySpeed;
        float secondarySpeed;
    }

    private BroadcastReceiver playbackStoppedReceiver;
    private BroadcastReceiver timerUpdateReceiver;

    // Add a new class variable for the receiver
    private BroadcastReceiver closeAppReceiver;
    private BroadcastReceiver mediaControlsReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applySavedThemeMode();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize database helper
        playlistDbHelper = new PlaylistDatabaseHelper(this);

        // Load seek settings from SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int forwardSeconds = prefs.getInt("seek_forward_seconds", 10); // Default 10 sec
        int backwardSeconds = prefs.getInt("seek_backward_seconds", 10); // Default 10 sec
        seekForwardMs = forwardSeconds * 1000;
        seekBackwardMs = backwardSeconds * 1000;
        // Load saved pitch
        currentPitch = prefs.getFloat("playback_pitch", 1.0f);
        currentFormant = prefs.getFloat(PREF_VOICE_FORMANT, 1.0f);
        voiceBassStrength = prefs.getInt(PREF_VOICE_BASS, 0);
        voiceReverbLevel = prefs.getInt(PREF_VOICE_REVERB, 0);
        voiceTrebleLevel = prefs.getInt(PREF_VOICE_TREBLE, 0);
        voiceClarityLevel = prefs.getInt(PREF_VOICE_CLARITY, 0);
        voiceEchoLevel = prefs.getInt(PREF_VOICE_ECHO, 0);
        voiceDistortionLevel = prefs.getInt(PREF_VOICE_DISTORTION, 0);
        voiceVibratoDepth = prefs.getInt(PREF_VOICE_VIBRATO, 0);
        voiceDepthLevel = prefs.getInt(PREF_VOICE_DEPTH, 0);
        voiceRobotEnabled = prefs.getBoolean(PREF_VOICE_ROBOT, false);
        voiceNoiseReductionEnabled = prefs.getBoolean(PREF_VOICE_NOISE_REDUCTION, false);
        voiceAutoTuneEnabled = prefs.getBoolean(PREF_VOICE_AUTOTUNE, false);
        // Load saved boost
        currentBoost = prefs.getFloat("volume_boost_factor", 1.0f);
        // Load saved folder view preference
        folderViewEnabled = prefs.getBoolean(PREF_FOLDER_VIEW, false);

        // Load saved playback mode
        loadPlaybackMode();

        // Initialize views
        initializeViews();
        applyCustomThemeOverlays();
        
        // Audio focus
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Initialize phone state receiver for call handling
        phoneStateReceiver = new PhoneStateReceiver();
        IntentFilter filter = new IntentFilter(ACTION_PHONE_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(phoneStateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(phoneStateReceiver, filter);
        }

        // Check mixer button - add this debug code
        if (mixerToggleButton != null) {
            Log.d(TAG, "Mixer button initialized successfully");
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

        // Handle startup intents such as "open this playlist".
        handleIntent(getIntent());

        // Best-effort restore of last known now-playing metadata (UI will fully sync once bound).
        restoreNowPlayingPrefsToUi();

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
        ContextCompat.registerReceiver(this, playbackStoppedReceiver, new IntentFilter("PLAYBACK_STOPPED"), ContextCompat.RECEIVER_NOT_EXPORTED);

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
        ContextCompat.registerReceiver(this, closeAppReceiver, new IntentFilter("CLOSE_APP_COMMAND"), ContextCompat.RECEIVER_NOT_EXPORTED);

        // Register media controls (next/prev) receiver
        mediaControlsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("MEDIA_NEXT".equals(action)) {
                    playNextFromContext();
                } else if ("MEDIA_PREV".equals(action)) {
                    playPreviousFromContext();
                } else if ("PLAYBACK_SEEKED".equals(action)) {
                    int position = intent.getIntExtra("position", -1);
                    applyExternalSeek(position);
                }
            }
        };
        IntentFilter mediaFilter = new IntentFilter();
        mediaFilter.addAction("MEDIA_NEXT");
        mediaFilter.addAction("MEDIA_PREV");
        mediaFilter.addAction("PLAYBACK_SEEKED");
        ContextCompat.registerReceiver(this, mediaControlsReceiver, mediaFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        // Update UI when playback is paused/resumed outside the UI (notification,
        // media session button, headphones unplugged, audio focus changes)
        BroadcastReceiver pausedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("PLAYBACK_PAUSED".equals(intent.getAction())) {
                    isPlaying = false;
                    safeSetImageResource(playPauseButton, R.drawable.ic_play_improved);
                } else if ("PLAYBACK_RESUMED".equals(intent.getAction())) {
                    isPlaying = true;
                    safeSetImageResource(playPauseButton, R.drawable.ic_pause_improved);
                }
                // Keep the mini player (icon, time, progress) in sync too
                updateMiniPlayer();
            }
        };
        IntentFilter pausedFilter = new IntentFilter();
        pausedFilter.addAction("PLAYBACK_PAUSED");
        pausedFilter.addAction("PLAYBACK_RESUMED");
        ContextCompat.registerReceiver(this, pausedReceiver, pausedFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void saveNowPlayingPrefs() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(PREF_NOW_PLAYING_URI, selectedAudioUri != null ? selectedAudioUri.toString() : null);
            editor.putString(PREF_NOW_PLAYING_TITLE, fileNameText != null ? fileNameText.getText().toString() : null);
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save now playing prefs", e);
        }
    }

    private void restoreNowPlayingPrefsToUi() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String uriStr = prefs.getString(PREF_NOW_PLAYING_URI, null);
            String title = prefs.getString(PREF_NOW_PLAYING_TITLE, null);

            if (selectedAudioUri == null && uriStr != null) {
                try {
                    selectedAudioUri = Uri.parse(uriStr);
                } catch (Exception ignored) {}
            }

            if (fileNameText != null) {
                String current = fileNameText.getText() != null ? fileNameText.getText().toString() : "";
                if ((current == null || current.isEmpty() || "No file selected".equalsIgnoreCase(current)) && title != null && !title.isEmpty()) {
                    fileNameText.setText(title);
                }
            }
            updateMiniPlayer();
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore now playing prefs", e);
        }
    }

    private void syncFromServiceToUi() {
        if (!serviceBound || audioService == null) return;

        try {
            MediaPlayer svcMain = audioService.getMediaPlayer();
            MediaPlayer svcSecond = audioService.getSecondMediaPlayer();

            if (mediaPlayer == null && svcMain != null) {
                try {
                    // Throws IllegalStateException if the instance was released
                    svcMain.getAudioSessionId();
                    mediaPlayer = svcMain;
                } catch (IllegalStateException e) {
                    Log.w(TAG, "Service player is released, will create a fresh one");
                }
            }
            if (secondMediaPlayer == null && svcSecond != null) {
                try {
                    svcSecond.getAudioSessionId();
                    secondMediaPlayer = svcSecond;
                } catch (IllegalStateException ignored) {
                }
            }

            if (svcMain != null) {
                isPlaying = audioService.isPlaying();

                String titleFromSvc = audioService.getCurrentTitle();
                if (fileNameText != null) {
                    String current = fileNameText.getText() != null ? fileNameText.getText().toString() : "";
                    if ((current == null || current.isEmpty() || current.contains("No song")) &&
                            titleFromSvc != null && !titleFromSvc.isEmpty()) {
                        fileNameText.setText(titleFromSvc);
                    }
                }

                restoreNowPlayingPrefsToUi();

                int duration = 0;
                int pos = 0;
                try { duration = svcMain.getDuration(); } catch (Exception ignored) {}
                try { pos = svcMain.getCurrentPosition(); } catch (Exception ignored) {}

                if (seekBar != null && duration > 0) seekBar.setMax(duration);
                if (seekBar != null && pos >= 0) seekBar.setProgress(pos);
                if (duration > 0) updateTimeText(pos, duration);

                safeSetImageResource(playPauseButton, isPlaying ? R.drawable.ic_pause_improved : R.drawable.ic_play_improved);
                updateMiniPlayer();

                if (isPlaying) {
                    updateSeekBar();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync from service to UI", e);
        }
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

            // Seek and prev/next buttons
            ImageButton prevButton = findViewById(R.id.prevButton);
            seekBackwardButton = findViewById(R.id.seekBackwardButton);
            seekForwardButton = findViewById(R.id.seekForwardButton);
            seekBackwardSecondsText = findViewById(R.id.seekBackwardSecondsText);
            seekForwardSecondsText = findViewById(R.id.seekForwardSecondsText);
            ImageButton nextButton = findViewById(R.id.nextButton);

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
            mixerToggleButton.setTextColor(getResources().getColor(R.color.text_tertiary, null));

            // Log successful initialization
            Log.d(TAG, "Views initialized successfully");

            updateSeekSkipButtonLabels();

            // Initialize playlist view elements
            playlistInfoContainer = findViewById(R.id.playlistInfoContainer);
            playlistNameText = findViewById(R.id.playlistNameText);
            showAllSongsButton = findViewById(R.id.showAllSongsButton);

            // Wire prev/next actions
            prevButton.setOnClickListener(v -> playPreviousFromContext());
            nextButton.setOnClickListener(v -> playNextFromContext());

            // ═══ NEW REDESIGNED UI ELEMENTS ═══

            // Mini player
            miniPlayerBar = findViewById(R.id.miniPlayerBar);
            miniPlayerTitle = findViewById(R.id.miniPlayerTitle);
            miniPlayerSubtitle = findViewById(R.id.miniPlayerSubtitle);
            miniPlayPauseBtn = findViewById(R.id.miniPlayPauseBtn);
            miniProgressBar = findViewById(R.id.miniProgressBar);
            expandedPlayerControls = findViewById(R.id.expandedPlayerControls);
            setupMiniProgressSeeking();
            applyMiniPlayerTheme();

            // App bar menu button (top-right dots)
            ImageButton appBarMenuButton = findViewById(R.id.appBarMenuButton);
            if (appBarMenuButton != null) {
                appBarMenuButton.setOnClickListener(v -> showBottomSheetMenu());
                setupMoreButtonSwipeGesture(appBarMenuButton);
            }

            // Mini player click to expand
            if (miniPlayerBar != null) {
                miniPlayerBar.setOnClickListener(v -> togglePlayerExpansion());
            }

            // Gesture zones:
            // - Red area (mini player): swipe up/down to expand/collapse now-playing.
            // - Green area (bottom nav): swipe up to open More options.
            setupMiniPlayerSwipeGesture(miniPlayerBar);

            // Mini play/pause
            if (miniPlayPauseBtn != null) {
                miniPlayPauseBtn.setOnClickListener(v -> {
                    if (playPauseButton != null) playPauseButton.performClick();
                });
            }

            // Music icon in mini player: slide the song list to the currently playing song
            View miniMusicIcon = findViewById(R.id.miniMusicIconContainer);
            if (miniMusicIcon != null) {
                miniMusicIcon.setOnClickListener(v -> slideToCurrentlyPlayingSong());
            }

            // Expand/collapse buttons
            ImageView expandBtn = findViewById(R.id.expandPlayerBtn);
            if (expandBtn != null) {
                expandBtn.setOnClickListener(v -> togglePlayerExpansion());
            }

            // Bottom Navigation
            bottomNavigation = findViewById(R.id.bottomNavigation);
            setupBottomNavSwipeGesture(bottomNavigation);
            if (bottomNavigation != null) {
                // Treat bottom nav as quick actions, not persistent tabs.
                // This prevents any item (e.g., More) from staying checked/gray.
                bottomNavigation.getMenu().setGroupCheckable(0, false, true);
                bottomNavigation.setOnItemSelectedListener(item -> {
                    int id = item.getItemId();
                    if (id == R.id.nav_library) {
                        // Toggle between folder view and list view
                        toggleFolderView();
                        updateLibraryNavItem();
                        return false;
                    } else if (id == R.id.nav_playlists) {
                        // Open playlists
                        Intent intent = new Intent(this, PlaylistActivity.class);
                        startActivity(intent);
                        return false;
                    } else if (id == R.id.nav_mixer) {
                        showMixerOptionsDialog();
                        return false;
                    } else if (id == R.id.nav_more) {
                        showBottomSheetMenu();
                        return false;
                    }
                    return false;
                });
                // Prevent first item (Library) from staying selected by default.
                clearBottomNavSelectionSoon();
                // Reflect the saved folder/list view mode in the nav item
                updateLibraryNavItem();
            }

            // Start with player collapsed
            if (expandedPlayerControls != null) {
                expandedPlayerControls.setVisibility(View.GONE);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error initializing views", e);
            Toast.makeText(this, "Error initializing app", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearBottomNavSelectionSoon2() {
        if (bottomNavigation == null) return;
        bottomNavigation.postDelayed(() -> {
            try {
                Menu menu = bottomNavigation.getMenu();
                for (int i = 0; i < menu.size(); i++) {
                    menu.getItem(i).setChecked(false);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to clear bottom nav selection", e);
            }
        }, 0);
    }

    private void clearBottomNavSelectionSoon() {
        if (bottomNavigation == null) return;

        bottomNavigation.post(() -> {
            try {
                Menu menu = bottomNavigation.getMenu();

                // IMPORTANT: allow no selection temporarily
                menu.setGroupCheckable(0, true, false);

                for (int i = 0; i < menu.size(); i++) {
                    menu.getItem(i).setChecked(false);
                }

                // re-enable single selection mode
                menu.setGroupCheckable(0, true, true);

            } catch (Exception e) {
                Log.e(TAG, "Failed to clear bottom nav selection", e);
            }
        });
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
                    if (isPlaying) {
                        mediaPlayer.pause();

                        // Also pause the second audio if it's active
                        if (secondMediaPlayer != null && secondAudioActive) {
                            secondMediaPlayer.pause();
                        }

                        // Use the new improved icons
                        safeSetImageResource(playPauseButton, R.drawable.ic_play_improved);
                        isPlaying = false;
                        startPlaybackService("ACTION_PAUSE");
                    } else {
                        mediaPlayer.start();

                        // Also start the second audio if it's ready (unless it already
                        // played to its end — restarting would loop it from 0:00)
                        startSecondTrackIfNotFinished();

                        // Use the new improved icons
                        safeSetImageResource(playPauseButton, R.drawable.ic_pause_improved);
                        isPlaying = true;
                        updateSeekBar();
                        startPlaybackService("ACTION_PLAY");
                    }

                    // Update service with current state
                    if (serviceBound && audioService != null) {
                        audioService.setMediaPlayers(mediaPlayer, secondAudioActive ? secondMediaPlayer : null,
                                fileNameText.getText().toString());
                    }

                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error with play/pause", e);
                    shouldAutoPlay = true;
                    prepareMediaPlayer();
                }
            } else if (selectedAudioUri != null) {
                // App can be relaunched with restored now-playing metadata but no active MediaPlayer instance.
                // In that case, rebuild and prepare the player so play works from mini player/main button.
                try {
                    shouldAutoPlay = true;
                    initMediaPlayer();
                    prepareMediaPlayer();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to restore playback from saved selection", e);
                    shouldAutoPlay = false;
                    Toast.makeText(this, "Unable to resume this file", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "No song selected", Toast.LENGTH_SHORT).show();
            }
        });

        // Add the menu button listener — now opens bottom sheet
        menuButton.setOnClickListener(v -> showBottomSheetMenu());
        setupMoreButtonSwipeGesture(menuButton);

        // Hook seek/prev/next if views are ready
        try {
            ImageButton prevButton = findViewById(R.id.prevButton);
            ImageButton nextButton = findViewById(R.id.nextButton);
            if (prevButton != null) prevButton.setOnClickListener(v -> playPreviousFromContext());
            if (nextButton != null) nextButton.setOnClickListener(v -> playNextFromContext());
        } catch (Exception ignored) {}

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

        // Setup show all songs button
        showAllSongsButton.setOnClickListener(v -> {
            toggleToAllSongsView();
        });
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // All files access (MANAGE_EXTERNAL_STORAGE) enables direct write to
            // MediaStore for rename/delete, including SD card, without the
            // system per-file consent dialog.
            if (!isAllFilesAccessGranted()) {
                requestAllFilesAccess();
            }
        }
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

    /**
     * True when the user has granted All files access (MANAGE_EXTERNAL_STORAGE)
     * via system settings. With this, MediaStore updates/deletes (including on
     * SD card) succeed without per-file consent prompts.
     */
    private boolean isAllFilesAccessGranted() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && Environment.isExternalStorageManager();
    }

    /**
     * Opens the system screen where the user can grant All files access.
     * Safe to call repeatedly: if the screen is unavailable we silently fall
     * back to regular runtime permissions.
     */
    private void requestAllFilesAccess() {
        if (allFilesAccessAskedOnce) {
            return; // Don't nag on every check; user can grant from app settings
        }
        allFilesAccessAskedOnce = true;
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            Toast.makeText(this,
                    "Allow 'All files access' so rename and delete work everywhere",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ex) {
                Log.w(TAG, "Unable to open all-files access settings", ex);
            }
        }
    }

    private boolean allFilesAccessAskedOnce = false;

    private void pickAudioFile() {
        // ACTION_GET_CONTENT lets any app that can supply audio participate,
        // including third-party file managers (which don't need to implement
        // the SAF document-provider API that ACTION_OPEN_DOCUMENT requires).
        Intent getContent = new Intent(Intent.ACTION_GET_CONTENT);
        getContent.addCategory(Intent.CATEGORY_OPENABLE);
        getContent.setType("audio/*");

        // Also offer the system document picker explicitly, since it supports
        // persistable URI grants that survive restarts.
        Intent openDocument = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        openDocument.addCategory(Intent.CATEGORY_OPENABLE);
        openDocument.setType("audio/*");
        openDocument.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        Intent chooser = Intent.createChooser(getContent, "Select audio file");
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { openDocument });

        try {
            audioPickerLauncher.launch(chooser);
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
        if (selectedAudioUri == null) {
            return;
        }

        try {
            final int requestVersion = ++prepareRequestVersion;
            final Uri requestUri = selectedAudioUri;

            // Request audio focus before preparing to play
            requestAudioFocus();

            if (mediaPlayer != null) {
                try {
                    mediaPlayer.reset();
                } catch (IllegalStateException e) {
                    // The instance was released elsewhere (e.g. service teardown)
                    // and can never be used again. Replace it with a fresh player
                    // so future songs still play instead of failing until the app
                    // is force-closed.
                    Log.w(TAG, "Stale MediaPlayer instance, creating a new one", e);
                    mediaPlayer = new MediaPlayer();
                }
            } else {
                mediaPlayer = new MediaPlayer();
            }

            // If the second track isn't active (mixer off / cleared), make sure a
            // leftover second track from an earlier session is silenced so it can't
            // play under the new song
            if ((!mixerModeActive || !secondAudioActive) && secondMediaPlayer != null) {
                try {
                    if (secondMediaPlayer.isPlaying()) {
                        secondMediaPlayer.pause();
                    }
                } catch (Exception ignored) {}
            }

            // Update the adapter to highlight the current track
            if (audioAdapter != null) {
                audioAdapter.setCurrentlyPlayingUri(selectedAudioUri);
            }
            autoScrollToCurrentSongIfEnabled();

            // Set data source from URI (with descriptor fallback for tricky formats,
            // e.g. some .wav files that fail via the plain content URI path)
            setDataSourceCompat(mediaPlayer, requestUri, requestVersion);

            // Set listeners before preparing
            mediaPlayer.setOnPreparedListener(mp -> {
                try {
                    if (requestVersion != prepareRequestVersion || !requestUri.equals(selectedAudioUri)) {
                        Log.d(TAG, "Ignoring stale onPrepared callback for previous audio selection");
                        return;
                    }
                    // Open audio effect session first to request control from system
                    openAudioEffectSession(mp.getAudioSessionId());
                    ensureEqualizerInitialized();

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
                        mp.start();
                        safeSetImageResource(playPauseButton, R.drawable.ic_pause_improved);
                        isPlaying = true;
                        updateSeekBar();
                        startSecondTrackIfMixerReady();
                        lastAutoRetriedUri = null;
                        lastAutoRetryAtMs = 0L;

                        // Start the service
                        startPlaybackService("ACTION_PLAY");

                        // Update service with media players
                        if (serviceBound && audioService != null) {
                            audioService.setMediaPlayers(mediaPlayer, secondAudioActive ? secondMediaPlayer : null,
                                    fileNameText.getText().toString());
                        }

                        // Reset the flag after use
                        shouldAutoPlay = false;
                    } else {
                        // Standard behavior - don't auto-play
                        safeSetImageResource(playPauseButton, R.drawable.ic_play_improved);
                        isPlaying = false;
                    }

                    // Apply playback speed and saved pitch if available
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        applyPlaybackParams(mediaPlayer, currentPlaybackSpeed, currentPitch);
                        Log.d(TAG, "Applied saved playback speed/pitch to primary audio: "
                                + currentPlaybackSpeed + "/" + currentPitch);
                    }
                    applyVoiceBass(voiceBassStrength);
                    applyVoiceReverb(voiceReverbLevel);
                    applyVoiceEcho(voiceEchoLevel);
                    applyVoiceFxEqualizer();
                    applyVoiceNoiseReduction(false);
                    startVoiceVibratoTicks();
                } catch (Exception e) {
                    Log.e(TAG, "Error in onPrepared", e);
                }
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                if (requestVersion != prepareRequestVersion || !requestUri.equals(selectedAudioUri)) {
                    Log.w(TAG, "Ignoring stale MediaPlayer error callback: what=" + what + ", extra=" + extra);
                    return true;
                }

                if (shouldAttemptPrepareRetry(requestUri)) {
                    Log.w(TAG, "Transient MediaPlayer error, auto-retrying once: what=" + what + ", extra=" + extra);
                    markPrepareRetry(requestUri);
                    releaseMediaPlayer();
                    initMediaPlayer();
                    shouldAutoPlay = true;
                    handler.postDelayed(this::prepareMediaPlayer, 140);
                    return true;
                }

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

    /**
     * Sets the data source with a descriptor fallback. Some devices (notably
     * Android 11 with certain .wav encodings) fail to prepare when the source
     * is set from a plain MediaStore content URI; opening an
     * AssetFileDescriptor and using its FileDescriptor instead lets the
     * underlying extractor read the file directly and succeeds.
     */
    private void setDataSourceCompat(MediaPlayer player, Uri uri, int requestVersion) throws IOException {
        try {
            player.setDataSource(getApplicationContext(), uri);
        } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException e) {
            Log.w(TAG, "Primary setDataSource failed for " + uri + ", retrying via file descriptor", e);
            try (android.content.res.AssetFileDescriptor afd =
                         getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (afd == null) {
                    throw new IOException("openAssetFileDescriptor returned null for " + uri);
                }
                player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            }
        }
    }

    private boolean shouldAttemptPrepareRetry(Uri uri) {
        if (uri == null) return false;
        if (!uri.equals(selectedAudioUri)) return false;
        if (lastAutoRetriedUri == null || !uri.equals(lastAutoRetriedUri)) return true;
        return (System.currentTimeMillis() - lastAutoRetryAtMs) > 3000L;
    }

    private void markPrepareRetry(Uri uri) {
        lastAutoRetriedUri = uri;
        lastAutoRetryAtMs = System.currentTimeMillis();
    }

    private void handleSongCompletion() {
        // Keep A-B repeat authoritative even when MediaPlayer triggers completion
        // (e.g. when point B is close to the end of the track).
        if (abRepeatActive && pointA != -1 && pointB != -1 && pointB > pointA && mediaPlayer != null) {
            try {
                mediaPlayer.seekTo(pointA);
                mediaPlayer.start();
                isPlaying = true;
                safeSetImageResource(playPauseButton, R.drawable.ic_pause_improved);
                updateMiniPlayer();

                if (secondMediaPlayer != null && secondAudioActive) {
                    float mainDuration = mediaPlayer.getDuration();
                    float secondDuration = secondMediaPlayer.getDuration();
                    if (mainDuration > 0 && secondDuration > 0) {
                        float aPercentage = pointA / mainDuration;
                        int secondPositionA = (int) (aPercentage * secondDuration);
                        secondMediaPlayer.seekTo(secondPositionA);
                    }
                    if (!secondMediaPlayer.isPlaying()) {
                        secondMediaPlayer.start();
                    }
                }
                updateSeekBar();
                return;
            } catch (Exception e) {
                Log.e(TAG, "Error looping A-B repeat on completion", e);
            }
        }

        // If we're playing a playlist, handle playlist navigation
        if (currentPlaylistId != null && !currentPlaylistSongs.isEmpty()) {
            if (currentPlaybackMode == PLAYBACK_MODE_REPEAT_CURRENT) {
                // Restart the current song
                if (mediaPlayer != null) {
                    mediaPlayer.seekTo(0);
                    mediaPlayer.start();
                }
            } else if (currentPlaybackMode == PLAYBACK_MODE_NEXT_IN_LIST) {
                // Go to the next song from current context (search results, playlist, or all songs)
                List<AudioFile> playlistContextList = getPlaybackContextList();
                int currentIndex = getCurrentSongIndexInList(playlistContextList);
                Log.d(TAG, "Playlist Play Next: contextList.size()=" + 
                      (playlistContextList != null ? playlistContextList.size() : "null") + ", currentIndex=" + currentIndex);
                if (currentIndex != -1 && currentIndex + 1 < playlistContextList.size()) {
                    // Play the next song from current context
                    Log.d(TAG, "Playing next song from context at index " + (currentIndex + 1));
                    onAudioFileSelected(playlistContextList.get(currentIndex + 1));
                } else if (!playlistContextList.isEmpty()) {
                    // End of context, loop back to the beginning
                    Log.d(TAG, "Looping back to first song from context");
                    onAudioFileSelected(playlistContextList.get(0));
                } else {
                    Log.d(TAG, "No songs available in current context");
                }
            } else if (currentPlaybackMode == PLAYBACK_MODE_RANDOM) {
                // Pick a random song from current context (search results, playlist, or all songs)
                List<AudioFile> randomPlaylistContextList = getPlaybackContextList();
                if (!randomPlaylistContextList.isEmpty()) {
                    anchorRandomHistoryToCurrentSong();
                    int randomIndex;
                    do {
                        randomIndex = new java.util.Random().nextInt(randomPlaylistContextList.size());
                    } while (randomPlaylistContextList.size() > 1 &&
                             randomPlaylistContextList.get(randomIndex).getUri().equals(selectedAudioUri));

                    selectedAudioUri = randomPlaylistContextList.get(randomIndex).getUri();
                    syncRandomHistory(selectedAudioUri);
                shouldAutoPlay = true;
                prepareMediaPlayer();

                // Update adapter to highlight the current song
                if (audioAdapter != null) {
                    audioAdapter.setCurrentlyPlayingUri(selectedAudioUri);
                    }
                }
            }
        } else {
            // Default handling for non-playlist playback
            switch (currentPlaybackMode) {
                case PLAYBACK_MODE_REPEAT_CURRENT:
                    // Restart the current song
                    if (mediaPlayer != null) {
                        mediaPlayer.seekTo(0);
                        mediaPlayer.start();
                    }
                    break;

                case PLAYBACK_MODE_NEXT_IN_LIST:
                    // Play the next song from current context (search results, playlist, or all songs)
                    List<AudioFile> contextList = getPlaybackContextList();
                    int currentIndex = getCurrentSongIndexInList(contextList);
                    Log.d(TAG, "Play Next: contextList.size()=" + 
                          (contextList != null ? contextList.size() : "null") + ", currentIndex=" + currentIndex);
                    if (currentIndex != -1 && currentIndex + 1 < contextList.size()) {
                        Log.d(TAG, "Playing next song at index " + (currentIndex + 1));
                        onAudioFileSelected(contextList.get(currentIndex + 1));
                    } else if (!contextList.isEmpty()) {
                        Log.d(TAG, "Looping back to first song");
                        // Loop back to the first song if at the end
                        onAudioFileSelected(contextList.get(0));
                    } else {
                        Log.d(TAG, "No songs available in current context");
                    }
                    break;

                case PLAYBACK_MODE_RANDOM:
                    // Play a random song from current context (search results, playlist, or all songs)
                    List<AudioFile> randomContextList = getPlaybackContextList();
                    if (!randomContextList.isEmpty()) {
                        anchorRandomHistoryToCurrentSong();
                        int randomIndex;
                        do {
                            randomIndex = new java.util.Random().nextInt(randomContextList.size());
                        } while (randomContextList.size() > 1 &&
                                 randomContextList.get(randomIndex).getUri().equals(selectedAudioUri));
                        onAudioFileSelected(randomContextList.get(randomIndex));
                    }
                    break;
            }
        }
    }

    private void playNextFromContext() {
        try {
            List<AudioFile> list = getPlaybackContextList();
            int idx = getCurrentSongIndexInList(list);
            if (idx != -1 && list != null && !list.isEmpty()) {
                int next;
                if (currentPlaybackMode == PLAYBACK_MODE_RANDOM) {
                    if (list.size() == 1) return;
                    anchorRandomHistoryToCurrentSong();
                    // Move forward through the existing random history — do NOT
                    // generate a new random song while forward history remains.
                    AudioFile historyFile = getRandomHistoryNeighbor(+1, list);
                    if (historyFile != null) {
                        onAudioFileSelected(historyFile);
                        return;
                    }
                    // End of history reached: pick a new random song
                    Random r = new Random();
                    int candidate = idx;
                    for (int tries = 0; tries < 5 && candidate == idx; tries++) {
                        candidate = r.nextInt(list.size());
                    }
                    next = candidate == idx ? (idx + 1) % list.size() : candidate;
                } else {
                    next = (idx + 1) % list.size();
                }
                onAudioFileSelected(list.get(next));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing next", e);
        }
    }

    private void playPreviousFromContext() {
        try {
            List<AudioFile> list = getPlaybackContextList();
            int idx = getCurrentSongIndexInList(list);
            if (idx != -1 && list != null && !list.isEmpty()) {
                int prev;
                if (currentPlaybackMode == PLAYBACK_MODE_RANDOM) {
                    if (list.size() == 1) return;
                    anchorRandomHistoryToCurrentSong();
                    // Move backward through the existing random history — do NOT
                    // generate a new random song.
                    AudioFile historyFile = getRandomHistoryNeighbor(-1, list);
                    if (historyFile != null) {
                        onAudioFileSelected(historyFile);
                        return;
                    }
                    // At the very start of the random history: restart the song
                    if (mediaPlayer != null) {
                        try {
                            mediaPlayer.seekTo(0);
                            if (mediaPlayer.isPlaying()) {
                                mediaPlayer.start();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error restarting song at start of random history", e);
                        }
                    }
                    return;
                } else {
                    prev = (idx - 1 + list.size()) % list.size();
                }
                onAudioFileSelected(list.get(prev));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing previous", e);
        }
    }

    /**
     * Returns the next (+1) or previous (-1) song in the random playback history
     * relative to the current position, or null when there is no entry in that
     * direction (or the entry's file can no longer be found).
     */
    private AudioFile getRandomHistoryNeighbor(int direction, List<AudioFile> list) {
        int target = randomHistoryPosition + direction;
        if (randomHistoryPosition < 0 || target < 0 || target >= randomHistory.size()) {
            return null;
        }
        return findAudioFileByUri(randomHistory.get(target), list);
    }

    private AudioFile findAudioFileByUri(Uri uri, List<AudioFile> list) {
        if (uri == null) {
            return null;
        }
        if (list != null) {
            for (AudioFile file : list) {
                if (uri.toString().equals(file.getUri().toString())) {
                    return file;
                }
            }
        }
        // Fall back to the full library in case the song left the current
        // context (e.g. a different folder/search filter is being viewed).
        if (allAudioFiles != null) {
            for (AudioFile file : allAudioFiles) {
                if (uri.toString().equals(file.getUri().toString())) {
                    return file;
                }
            }
        }
        return null;
    }

    /**
     * Makes sure the random history position matches the currently playing song.
     * Needed after a mode switch or manual song selection while outside the
     * history sequence.
     */
    private void anchorRandomHistoryToCurrentSong() {
        boolean anchored = randomHistoryPosition >= 0
                && randomHistoryPosition < randomHistory.size()
                && selectedAudioUri != null
                && randomHistory.get(randomHistoryPosition).equals(selectedAudioUri);
        if (!anchored) {
            syncRandomHistory(selectedAudioUri);
        }
    }

    /**
     * Records a song change in the random history: steps forward/backward when
     * the song matches an adjacent entry (history navigation), otherwise drops
     * any forward entries and appends the song as the newest in the sequence.
     */
    private void syncRandomHistory(Uri uri) {
        if (uri == null) {
            return;
        }
        // Already the song at the current position
        if (randomHistoryPosition >= 0 && randomHistoryPosition < randomHistory.size()
                && uri.equals(randomHistory.get(randomHistoryPosition))) {
            return;
        }
        // Forward navigation within existing history
        if (randomHistoryPosition >= 0 && randomHistoryPosition + 1 < randomHistory.size()
                && uri.equals(randomHistory.get(randomHistoryPosition + 1))) {
            randomHistoryPosition++;
            return;
        }
        // Backward navigation within existing history
        if (randomHistoryPosition > 0
                && uri.equals(randomHistory.get(randomHistoryPosition - 1))) {
            randomHistoryPosition--;
            return;
        }
        // A new song in the sequence: cut stale forward entries and append
        if (randomHistoryPosition < randomHistory.size() - 1) {
            randomHistory.subList(randomHistoryPosition + 1, randomHistory.size()).clear();
        }
        randomHistory.add(uri);
        randomHistoryPosition = randomHistory.size() - 1;
        // Keep the history bounded
        if (randomHistory.size() > RANDOM_HISTORY_MAX_SIZE) {
            randomHistory.remove(0);
            randomHistoryPosition--;
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

    private int getCurrentSongIndexInList(List<AudioFile> list) {
        if (selectedAudioUri == null || list == null || list.isEmpty()) {
            return -1;
        }

        for (int i = 0; i < list.size(); i++) {
            if (selectedAudioUri.toString().equals(list.get(i).getUri().toString())) {
                return i;
            }
        }
        return -1;
    }
    
    /**
     * Get the appropriate list for playback modes based on current context
     * - If searching: use filteredAudioFiles (search results)
     * - If in playlist view: use currentPlaylistSongs
     * - Otherwise: use allAudioFiles
     */
    private List<AudioFile> getPlaybackContextList() {
        // If we're in playlist view, use playlist songs
        if (inPlaylistView && currentPlaylistSongs != null && !currentPlaylistSongs.isEmpty()) {
            return currentPlaylistSongs;
        }
        
        // If we're searching (have search text), use filtered results
        if (searchEditText != null && !TextUtils.isEmpty(searchEditText.getText())) {
            return filteredAudioFiles;
        }
        
        // Otherwise, use all songs
        return allAudioFiles;
    }
    
    /**
     * Save the current playback mode to SharedPreferences
     */
    private void savePlaybackMode() {
        SharedPreferences prefs = getSharedPreferences("audio_player_prefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("playback_mode", currentPlaybackMode);
        editor.apply();
        Log.d(TAG, "Saved playback mode: " + currentPlaybackMode);
    }
    
    /**
     * Load the saved playback mode from SharedPreferences
     */
    private void loadPlaybackMode() {
        SharedPreferences prefs = getSharedPreferences("audio_player_prefs", MODE_PRIVATE);
        currentPlaybackMode = prefs.getInt("playback_mode", PLAYBACK_MODE_NEXT_IN_LIST); // Default to "Play Next in List"
        Log.d(TAG, "Loaded playback mode: " + currentPlaybackMode);
    }

    private void applyExternalSeek(int position) {
        if (position < 0 || mediaPlayer == null) {
            return;
        }
        try {
            int duration = mediaPlayer.getDuration();
            if (seekBar != null) {
                if (duration > 0) {
                    seekBar.setMax(duration);
                }
                seekBar.setProgress(position);
            }
            updateTimeText(position, duration);
            updateMiniPlayer();
        } catch (Exception e) {
            Log.e(TAG, "Error applying notification seek to UI", e);
        }
    }

    private void updateSeekBar() {
        if (mediaPlayer != null) {
            try {
                int currentPosition = mediaPlayer.getCurrentPosition();

                // Check if A-B repeat is active and we need to loop
                if (abRepeatActive && pointA != -1 && pointB != -1 && pointB > pointA) {
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

                // Update mini player
                updateMiniPlayer();

                if (mediaPlayer.isPlaying()) {
                    runnable = () -> updateSeekBar();
                    // Keep A-B repeat responsive for short loop ranges.
                    handler.postDelayed(runnable, 100);
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
                closeAudioEffectSession(mediaPlayer.getAudioSessionId());
                releaseEqualizerEffects();
                mediaPlayer.release();
                mediaPlayer = null;
            } catch (Exception e) {
                Log.e(TAG, "Error releasing media player", e);
            }
        }
    }

    private void showPopupMenu(View v) {
        // Legacy method - redirect to new bottom sheet menu
        showBottomSheetMenu();
    }

    private void showBottomSheetMenu() {
        if (isBottomSheetMenuVisible()) {
            return;
        }

        MenuBottomSheet menuSheet = new MenuBottomSheet();
        menuSheet.setMenuListener(new MenuBottomSheet.MenuListener() {
            @Override
            public void onSortClicked() {
                showSortBottomSheet();
            }

            @Override
            public void onTimerClicked() {
                showTimerBottomSheet();
            }

            @Override
            public void onABRepeatClicked() {
                showABRepeatBottomSheet();
            }

            @Override
            public void onPlaybackModeClicked(View anchorView) {
                showPlaybackModePopup(anchorView);
            }

            @Override
            public void onSpeedClicked() {
                showSpeedBottomSheet();
            }

            @Override
            public void onPitchClicked() {
                showPitchBottomSheet();
            }

            @Override
            public void onBoostClicked() {
                showBoostBottomSheet();
            }

            @Override
            public void onEqualizerClicked() {
                showEqualizerPanel();
            }

            @Override
            public void onSettingsClicked() {
                showOtherSettingsDialog();
            }

            @Override
            public void onRefreshClicked() {
                refreshAudioFiles();
            }

            @Override
            public void onExitAppClicked() {
                exitAppCompletely();
            }

            @Override
            public void onAddToPlaylistClicked() {
                showAddToPlaylistDialog();
            }

            @Override
            public void onBrowseClicked() {
                if (isPermissionGranted) {
                    pickAudioFile();
                } else {
                    checkPermissions();
                    Toast.makeText(MainActivity.this,
                            "Permission required to access files", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onMixerToggleClicked() {
                toggleMixerMode();
            }

            @Override
            public void onResetClicked() {
                resetAudioSettings();
            }

            @Override
            public boolean hasSongSelected() {
                return selectedAudioUri != null;
            }

            @Override
            public boolean isMixerEnabled() {
                return mixerModeActive;
            }

            @Override
            public float getCurrentSpeed() {
                return currentPlaybackSpeed;
            }

            @Override
            public int getCurrentPlaybackMode() {
                return currentPlaybackMode;
            }
        });
        menuSheet.show(getSupportFragmentManager(), "MenuBottomSheet");
    }

    private void showPlaybackModePopup(View anchorView) {
        if (anchorView == null) {
            return;
        }

        PopupMenu popupMenu = new PopupMenu(this, anchorView);
        popupMenu.getMenu().add(0, PLAYBACK_MODE_REPEAT_CURRENT, 0, "Repeat Current Song");
        popupMenu.getMenu().add(0, PLAYBACK_MODE_NEXT_IN_LIST, 1, "Play Next in List");
        popupMenu.getMenu().add(0, PLAYBACK_MODE_RANDOM, 2, "Random Play");
        popupMenu.getMenu().setGroupCheckable(0, true, true);

        if (currentPlaybackMode >= PLAYBACK_MODE_REPEAT_CURRENT
                && currentPlaybackMode <= PLAYBACK_MODE_RANDOM) {
            popupMenu.getMenu().findItem(currentPlaybackMode).setChecked(true);
        }

        popupMenu.setOnMenuItemClickListener(item -> {
            applyPlaybackMode(item.getItemId());
            return true;
        });
        popupMenu.show();
    }

    private void applyPlaybackMode(int selectedMode) {
        if (selectedMode < PLAYBACK_MODE_REPEAT_CURRENT || selectedMode > PLAYBACK_MODE_RANDOM) {
            return;
        }

        currentPlaybackMode = selectedMode;
        savePlaybackMode();

        String[] modeOptions = {"Repeat Current Song", "Play Next in List", "Random Play"};
        Toast.makeText(this, "Mode: " + modeOptions[currentPlaybackMode], Toast.LENGTH_SHORT).show();
    }

    private void setupMoreButtonSwipeGesture(View moreButton) {
        if (moreButton == null) {
            return;
        }

        final float swipeThresholdPx = MORE_BUTTON_SWIPE_THRESHOLD_DP
                * getResources().getDisplayMetrics().density;
        final float[] downY = new float[1];

        moreButton.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = event.getRawY();
                    break;
                case MotionEvent.ACTION_UP:
                    float deltaY = event.getRawY() - downY[0];
                    if (Math.abs(deltaY) >= swipeThresholdPx) {
                        if (deltaY < 0) {
                            showBottomSheetMenu();
                        } else {
                            dismissBottomSheetMenu();
                        }
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private void setupBottomPanelSwipeGesture(View playerPanel) {
        if (playerPanel == null) {
            return;
        }

        final float swipeThresholdPx = MORE_BUTTON_SWIPE_THRESHOLD_DP
                * getResources().getDisplayMetrics().density;
        final float[] downY = new float[1];

        playerPanel.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = event.getRawY();
                    break;
                case MotionEvent.ACTION_UP:
                    float deltaY = event.getRawY() - downY[0];
                    if (Math.abs(deltaY) >= swipeThresholdPx) {
                        if (deltaY < 0) {
                            showBottomSheetMenu();
                        } else {
                            dismissBottomSheetMenu();
                        }
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private void setupMiniPlayerSwipeGesture(View target) {
        if (target == null) {
            return;
        }

        final float swipeThresholdPx = MORE_BUTTON_SWIPE_THRESHOLD_DP
                * getResources().getDisplayMetrics().density;
        final float[] downX = new float[1];
        final float[] downY = new float[1];

        target.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    downY[0] = event.getRawY();
                    return false;
                case MotionEvent.ACTION_UP:
                    float deltaX = event.getRawX() - downX[0];
                    float deltaY = event.getRawY() - downY[0];
                    boolean verticalSwipe = Math.abs(deltaY) > Math.abs(deltaX) * 1.15f;
                    if (verticalSwipe && Math.abs(deltaY) >= swipeThresholdPx) {
                        if (deltaY < 0) {
                            setPlayerExpanded(true);
                        } else {
                            setPlayerExpanded(false);
                        }
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        });
    }

    private void setupBottomNavSwipeGesture(View target) {
        if (target == null) {
            return;
        }

        final float swipeThresholdPx = MORE_BUTTON_SWIPE_THRESHOLD_DP
                * getResources().getDisplayMetrics().density;
        final float[] downX = new float[1];
        final float[] downY = new float[1];

        target.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    downY[0] = event.getRawY();
                    return false;
                case MotionEvent.ACTION_UP:
                    float deltaX = event.getRawX() - downX[0];
                    float deltaY = event.getRawY() - downY[0];
                    boolean verticalSwipe = Math.abs(deltaY) > Math.abs(deltaX) * 1.15f;
                    if (verticalSwipe && deltaY <= -swipeThresholdPx) {
                        showBottomSheetMenu();
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        });
    }

    private boolean isBottomSheetMenuVisible() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("MenuBottomSheet");
        return fragment != null && fragment.isVisible();
    }

    private void dismissBottomSheetMenu() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag("MenuBottomSheet");
        if (fragment instanceof MenuBottomSheet && fragment.isVisible()) {
            ((MenuBottomSheet) fragment).dismiss();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev != null && !isBottomSheetMenuVisible() && bottomNavigation != null) {
            final float swipeThresholdPx = MORE_BUTTON_SWIPE_THRESHOLD_DP
                    * getResources().getDisplayMetrics().density;
            int[] navLocation = new int[2];
            bottomNavigation.getLocationOnScreen(navLocation);
            int navTop = navLocation[1];
            int navBottom = navTop + bottomNavigation.getHeight();
            int navLeft = navLocation[0];
            int navRight = navLeft + bottomNavigation.getWidth();

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    bottomSwipeStartX = ev.getRawX();
                    bottomSwipeStartY = ev.getRawY();
                    bottomSwipeHandled = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    float deltaX = ev.getRawX() - bottomSwipeStartX;
                    float deltaY = ev.getRawY() - bottomSwipeStartY;

                    boolean startedInBottomNav =
                            bottomSwipeStartX >= navLeft && bottomSwipeStartX <= navRight
                                    && bottomSwipeStartY >= navTop && bottomSwipeStartY <= navBottom;
                    boolean isVerticalSwipe = Math.abs(deltaY) > Math.abs(deltaX) * 1.15f;

                    if (!bottomSwipeHandled && startedInBottomNav && isVerticalSwipe
                            && deltaY <= -swipeThresholdPx) {
                        showBottomSheetMenu();
                        bottomSwipeHandled = true;
                    }
                    if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                        bottomSwipeHandled = false;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    bottomSwipeHandled = false;
                    break;
                default:
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void showSortBottomSheet() {
        SortBottomSheet sortSheet = new SortBottomSheet();
        sortSheet.setSortListener(new SortBottomSheet.SortListener() {
            @Override
            public void onSortSelected(int sortOrder) {
                currentSortOrder = sortOrder;
                sortAudioFiles();
                audioAdapter.notifyDataSetChanged();
            }

            @Override
            public int getCurrentSortOrder() {
                return currentSortOrder;
            }
        });
        sortSheet.show(getSupportFragmentManager(), "SortBottomSheet");
    }

    private void showTimerBottomSheet() {
        TimerBottomSheet timerSheet = new TimerBottomSheet();
        timerSheet.setTimerListener(new TimerBottomSheet.TimerListener() {
            @Override
            public void onTimerSet(int minutes, int action) {
                timerAction = action;
                setSleepTimer(minutes);
            }

            @Override
            public void onTimerCancel() {
                cancelSleepTimer();
            }
        });
        timerSheet.show(getSupportFragmentManager(), "TimerBottomSheet");
    }

    private void showABRepeatBottomSheet() {
        ABRepeatBottomSheet abSheet = new ABRepeatBottomSheet();
        abSheet.setABRepeatListener(new ABRepeatBottomSheet.ABRepeatListener() {
            @Override
            public void onSetPointA() { setPointA(); }

            @Override
            public void onSetPointB() { setPointB(); }

            @Override
            public void onClearABPoints() { clearABPoints(); }

            @Override
            public void onToggleABRepeat() {
                if (abRepeatActive) {
                    abRepeatActive = false;
                    updateABRepeatIndicator();
                } else {
                    enableABRepeat();
                }
            }

            @Override
            public int getPointA() { return pointA; }

            @Override
            public int getPointB() { return pointB; }

            @Override
            public boolean isABRepeatActive() { return abRepeatActive; }

            @Override
            public int getCurrentPosition() {
                try {
                    return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
                } catch (Exception e) {
                    return 0;
                }
            }

            @Override
            public int getDuration() {
                try {
                    return mediaPlayer != null ? mediaPlayer.getDuration() : 0;
                } catch (Exception e) {
                    return 0;
                }
            }

            @Override
            public void onSeekTo(int position) {
                if (mediaPlayer != null) {
                    try {
                        mediaPlayer.seekTo(position);
                        updateSeekBar();
                    } catch (Exception e) {
                        Log.e(TAG, "Error seeking in A-B sheet", e);
                    }
                }
            }

            @Override
            public void onPlayFrom(int position) {
                if (mediaPlayer == null) {
                    Toast.makeText(MainActivity.this, "Please select an audio file first", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    mediaPlayer.seekTo(position);
                    resumePlayback();
                    updateSeekBar();
                } catch (Exception e) {
                    Log.e(TAG, "Error playing from A-B point", e);
                }
            }

            @Override
            public void onNudgePointA(int deltaMs) { nudgeABPoint(true, deltaMs); }

            @Override
            public void onNudgePointB(int deltaMs) { nudgeABPoint(false, deltaMs); }

            @Override
            public void onSetPointAAt(int positionMs) { setABPointManually(true, positionMs); }

            @Override
            public void onSetPointBAt(int positionMs) { setABPointManually(false, positionMs); }

            @Override
            public void onMovePointA(int positionMs) { moveABPoint(true, positionMs); }

            @Override
            public void onMovePointB(int positionMs) { moveABPoint(false, positionMs); }
        });
        abSheet.show(getSupportFragmentManager(), "ABRepeatBottomSheet");
    }

    private void showSpeedBottomSheet() {
        SpeedBottomSheet speedSheet = new SpeedBottomSheet();
        speedSheet.setSpeedListener(new SpeedBottomSheet.SpeedListener() {
            @Override
            public void onSpeedChanged(float speed) {
                setPlaybackSpeed(speed);
            }

            @Override
            public float getCurrentSpeed() {
                return currentPlaybackSpeed;
            }
        });
        speedSheet.show(getSupportFragmentManager(), "SpeedBottomSheet");
    }

    private void showPitchBottomSheet() {
        PitchBottomSheet pitchSheet = new PitchBottomSheet();
        pitchSheet.setPitchListener(new PitchBottomSheet.PitchListener() {
            @Override
            public void onPitchChanged(float pitch) {
                currentPitch = pitch;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putFloat("playback_pitch", currentPitch).apply();

                if (serviceBound && audioService != null) {
                    audioService.setPitch(pitch);
                    if (Math.abs(currentFormant - 1.0f) > 0.001f || voiceAutoTuneEnabled) {
                        applyVoiceSettingsToActivePlayers();
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && mediaPlayer != null) {
                    float speed = secondAudioActive && useIndividualPlaybackSpeeds
                            ? primaryPlaybackSpeed
                            : currentPlaybackSpeed;
                    applyPlaybackParams(mediaPlayer, speed, pitch);
                }
            }

            @Override
            public float getCurrentPitch() {
                if (serviceBound && audioService != null) {
                    return audioService.getCurrentPitch();
                }
                return currentPitch;
            }

            @Override
            public void onSpeedChanged(float speed) {
                setPlaybackSpeed(speed);
            }

            @Override
            public float getCurrentSpeed() {
                return currentPlaybackSpeed;
            }

            @Override
            public void onFormantChanged(float formant) {
                currentFormant = Math.max(0.5f, Math.min(2.0f, formant));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putFloat(PREF_VOICE_FORMANT, currentFormant).apply();
                applyVoiceSettingsToActivePlayers();
            }

            @Override
            public float getCurrentFormant() {
                return currentFormant;
            }

            @Override
            public void onBassChanged(int strength) {
                voiceBassStrength = Math.max(0, Math.min(1000, strength));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putInt(PREF_VOICE_BASS, voiceBassStrength).apply();
                applyVoiceBass(voiceBassStrength);
            }

            @Override
            public int getCurrentBass() {
                if (bassBoost != null) {
                    try {
                        return bassBoost.getRoundedStrength();
                    } catch (Exception ignored) {}
                }
                return voiceBassStrength;
            }

            @Override
            public void onReverbChanged(int level) {
                voiceReverbLevel = Math.max(0, Math.min(1000, level));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putInt(PREF_VOICE_REVERB, voiceReverbLevel).apply();
                applyVoiceReverb(voiceReverbLevel);
            }

            @Override
            public int getCurrentReverb() {
                return voiceReverbLevel;
            }

            @Override
            public void onTrebleChanged(int strength) {
                voiceTrebleLevel = Math.max(-1000, Math.min(1000, strength));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putInt(PREF_VOICE_TREBLE, voiceTrebleLevel).apply();
                applyVoiceFxEqualizer();
            }

            @Override
            public int getCurrentTreble() {
                return voiceTrebleLevel;
            }

            @Override
            public void onVocalClarityChanged(int strength) {
                voiceClarityLevel = Math.max(0, Math.min(1000, strength));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putInt(PREF_VOICE_CLARITY, voiceClarityLevel).apply();
                applyVoiceFxEqualizer();
            }

            @Override
            public int getCurrentVocalClarity() {
                return voiceClarityLevel;
            }

            @Override
            public void onEchoChanged(int level) {
                voiceEchoLevel = Math.max(0, Math.min(1000, level));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putInt(PREF_VOICE_ECHO, voiceEchoLevel).apply();
                applyVoiceEcho(voiceEchoLevel);
            }

            @Override
            public int getCurrentEcho() {
                return voiceEchoLevel;
            }

            @Override
            public void onDistortionChanged(int strength) {
                voiceDistortionLevel = Math.max(0, Math.min(1000, strength));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putInt(PREF_VOICE_DISTORTION, voiceDistortionLevel).apply();
                applyVoiceFxEqualizer();
            }

            @Override
            public int getCurrentDistortion() {
                return voiceDistortionLevel;
            }

            @Override
            public void onVibratoChanged(int depth) {
                voiceVibratoDepth = Math.max(0, Math.min(1000, depth));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putInt(PREF_VOICE_VIBRATO, voiceVibratoDepth).apply();
                if (voiceVibratoDepth > 0) {
                    startVoiceVibratoTicks();
                } else {
                    stopVoiceVibratoTicks();
                }
            }

            @Override
            public int getCurrentVibrato() {
                return voiceVibratoDepth;
            }

            @Override
            public void onVolumeBoostChanged(float boost) {
                // Same pipeline as the Boost bottom sheet (service LoudnessEnhancer),
                // constrained here to the voice changer's 1x..3x range.
                currentBoost = Math.max(1.0f, Math.min(3.0f, boost));
                try {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit().putFloat("volume_boost_factor", currentBoost).apply();
                } catch (Exception ignored) {}
                if (serviceBound && audioService != null) {
                    audioService.setBoost(currentBoost);
                }
            }

            @Override
            public float getCurrentVolumeBoost() {
                if (serviceBound && audioService != null) {
                    return audioService.getCurrentBoost();
                }
                return currentBoost;
            }

            @Override
            public void onVoiceDepthChanged(int strength) {
                voiceDepthLevel = Math.max(-1000, Math.min(1000, strength));
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putInt(PREF_VOICE_DEPTH, voiceDepthLevel).apply();
                applyVoiceFxEqualizer();
            }

            @Override
            public int getCurrentVoiceDepth() {
                return voiceDepthLevel;
            }

            @Override
            public void onRobotToggled(boolean enabled) {
                voiceRobotEnabled = enabled;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putBoolean(PREF_VOICE_ROBOT, voiceRobotEnabled).apply();
                applyVoiceFxEqualizer();
            }

            @Override
            public boolean isRobotEnabled() {
                return voiceRobotEnabled;
            }

            @Override
            public void onNoiseReductionToggled(boolean enabled) {
                voiceNoiseReductionEnabled = enabled;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putBoolean(PREF_VOICE_NOISE_REDUCTION,
                                voiceNoiseReductionEnabled).apply();
                applyVoiceNoiseReduction(true);
            }

            @Override
            public boolean isNoiseReductionEnabled() {
                return voiceNoiseReductionEnabled;
            }

            @Override
            public void onAutoTuneToggled(boolean enabled) {
                voiceAutoTuneEnabled = enabled;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putBoolean(PREF_VOICE_AUTOTUNE, voiceAutoTuneEnabled).apply();
                if (voiceAutoTuneEnabled) {
                    // Lock pitch to exact semitones (also stops the vibrato loop,
                    // since quantization would flatten its modulation)
                    stopVoiceVibratoTicks();
                } else {
                    applyVoiceSettingsToActivePlayers();
                    if (voiceVibratoDepth > 0) {
                        startVoiceVibratoTicks();
                    }
                }
            }

            @Override
            public boolean isAutoTuneEnabled() {
                return voiceAutoTuneEnabled;
            }

            @Override
            public void onEqPresetSelected(String presetName) {
                applyVoiceEqPreset(presetName);
            }
        });

        pitchSheet.show(getSupportFragmentManager(), "PitchBottomSheet");
    }

    private void showBoostBottomSheet() {
        BoostBottomSheet boostSheet = new BoostBottomSheet();
        boostSheet.setBoostListener(new BoostBottomSheet.BoostListener() {
            @Override
            public void onBoostChanged(float boost) {
                // Keep local state and preference in sync
                currentBoost = boost;
                try {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit().putFloat("volume_boost_factor", boost).apply();
                } catch (Exception ignored) {}

                // Real amplification is done by the service via LoudnessEnhancer
                // attached to the players' audio sessions. Do NOT scale
                // MediaPlayer.setVolume here — it caps at 1.0 and used to cancel
                // out the enhancer's gain (boost made playback quieter, not louder).
                if (serviceBound && audioService != null) {
                    audioService.setBoost(boost);
                }
            }

            @Override
            public float getCurrentBoost() {
                if (serviceBound && audioService != null) return audioService.getCurrentBoost();
                return currentBoost;
            }
        });

        boostSheet.show(getSupportFragmentManager(), "BoostBottomSheet");
    }

    private void showMixerOptionsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_mixer_options, null);
        SwitchCompat mixerModeSwitch = dialogView.findViewById(R.id.mixerModeSwitch);
        TextView mixerStatusText = dialogView.findViewById(R.id.mixerStatusText);
        TextView mixerSecondTrackText = dialogView.findViewById(R.id.mixerSecondTrackText);
        Button selectSecondButton = dialogView.findViewById(R.id.mixerSelectSecondButton);
        Button balanceButton = dialogView.findViewById(R.id.mixerBalanceButton);
        Button syncButton = dialogView.findViewById(R.id.mixerSyncButton);
        Button toggleIndividualSpeedButton = dialogView.findViewById(R.id.mixerToggleIndividualSpeedButton);
        LinearLayout individualSpeedRow = dialogView.findViewById(R.id.mixerIndividualSpeedRow);
        Button primarySpeedButton = dialogView.findViewById(R.id.mixerPrimarySpeedButton);
        Button secondarySpeedButton = dialogView.findViewById(R.id.mixerSecondarySpeedButton);
        Button clearSecondButton = dialogView.findViewById(R.id.mixerClearSecondButton);
        Button savedMixerButton = dialogView.findViewById(R.id.mixerSavedButton);

        final Runnable updateUiState = () -> {
            mixerModeSwitch.setChecked(mixerModeActive);

            String secondTrackName = "Not selected";
            TextView secondFileNameText = findViewById(R.id.secondFileNameText);
            if (secondAudioActive && secondFileNameText != null && !TextUtils.isEmpty(secondFileNameText.getText())) {
                secondTrackName = secondFileNameText.getText().toString();
            }

            if (!mixerModeActive) {
                mixerStatusText.setText("OFF");
                mixerStatusText.setTextColor(getResources().getColor(R.color.text_tertiary, null));
            } else if (!secondAudioActive) {
                mixerStatusText.setText("ON · No 2nd track");
                mixerStatusText.setTextColor(getResources().getColor(R.color.warning, null));
            } else {
                mixerStatusText.setText("ON · Ready");
                mixerStatusText.setTextColor(getResources().getColor(R.color.success, null));
            }

            mixerSecondTrackText.setText(secondTrackName);
            mixerSecondTrackText.setTextColor(getResources().getColor(
                    secondAudioActive ? R.color.text_primary : R.color.text_tertiary, null));

            boolean hasSecondTrack = secondAudioActive;
            balanceButton.setEnabled(hasSecondTrack);
            syncButton.setEnabled(hasSecondTrack);
            clearSecondButton.setEnabled(hasSecondTrack);
            toggleIndividualSpeedButton.setEnabled(hasSecondTrack);
            toggleIndividualSpeedButton.setText(useIndividualPlaybackSpeeds
                    ? "Disable Individual Speeds"
                    : "Enable Individual Speeds");

            boolean showSpeedButtons = hasSecondTrack && useIndividualPlaybackSpeeds;
            individualSpeedRow.setVisibility(showSpeedButtons ? View.VISIBLE : View.GONE);
        };

        updateUiState.run();

        // Fullscreen window with a blurred screenshot behind the panel
        Dialog dialog = showBlurredPanelDialog(dialogView,
                Gravity.END | Gravity.BOTTOM, 0.82f);

        mixerModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked != mixerModeActive) {
                toggleMixerMode();
                updateUiState.run();
            }
        });

        selectSecondButton.setOnClickListener(v -> {
            dialog.dismiss();
            selectSecondAudio();
        });

        balanceButton.setOnClickListener(v -> {
            if (!secondAudioActive) {
                Toast.makeText(this, "Select second audio first", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            showBalanceDialog();
        });

        syncButton.setOnClickListener(v -> {
            if (!secondAudioActive) {
                Toast.makeText(this, "Select second audio first", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            showPositionSyncDialog();
        });

        toggleIndividualSpeedButton.setOnClickListener(v -> {
            if (!secondAudioActive) {
                Toast.makeText(this, "Select second audio first", Toast.LENGTH_SHORT).show();
                return;
            }
            toggleIndividualPlaybackSpeeds();
            updateUiState.run();
        });

        primarySpeedButton.setOnClickListener(v -> {
            dialog.dismiss();
            showIndividualSpeedDialog(true);
        });

        secondarySpeedButton.setOnClickListener(v -> {
            dialog.dismiss();
            showIndividualSpeedDialog(false);
        });

        clearSecondButton.setOnClickListener(v -> {
            if (!secondAudioActive) {
                return;
            }
            clearSecondAudio();
            updateUiState.run();
        });

        savedMixerButton.setOnClickListener(v -> showSavedMixerDialog());

        // The blurred-panel helper handles the window setup (fullscreen, transparent,
        // tap-outside-to-close); nothing else to configure here.
    }

    /**
     * Shows a panel over a blurred snapshot of the current screen (used by the
     * Mixer and Saved Mixer windows). The dialog window is fullscreen and
     * transparent; a blurred screenshot of the app plus a light scrim fill it,
     * and the panel content is placed on top. Tapping the blurred area or
     * pressing back dismisses the dialog. Falls back to a plain dark scrim if
     * the screenshot can't be captured.
     *
     * @param panelGravity  where the panel sits inside the blurred backdrop
     * @param widthFraction 0..1 — share of the screen width the panel uses
     *                      (values >= 0.99 mean match parent with side margins)
     */
    private Dialog showBlurredPanelDialog(View content, int panelGravity, float widthFraction) {
        FrameLayout root = new FrameLayout(this);

        Bitmap blur = createBlurredBackground();
        ImageView blurredView = null;
        if (blur != null) {
            blurredView = new ImageView(this);
            blurredView.setImageBitmap(blur);
            blurredView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(blurredView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        // Light scrim so the panel pops off the blur; doubles as the fallback
        // backdrop when no screenshot could be captured
        View scrim = new View(this);
        scrim.setBackgroundColor(blur != null ? 0x40000000 : 0xB3000000);
        root.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams cardLp;
        if (widthFraction >= 0.99f) {
            int margin = (int) (16 * getResources().getDisplayMetrics().density);
            cardLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    panelGravity);
            cardLp.setMargins(margin, margin, margin, margin);
        } else {
            int width = (int) (getResources().getDisplayMetrics().widthPixels * widthFraction);
            cardLp = new FrameLayout.LayoutParams(width,
                    FrameLayout.LayoutParams.WRAP_CONTENT, panelGravity);
        }
        // Consume taps on the panel itself so they never fall through to the scrim
        content.setClickable(true);
        root.addView(content, cardLp);

        Dialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .create();

        Window window = dialog.getWindow();
        if (window != null) {
            // Transparent + no system dim before show, so the blank alert layout
            // never flashes; our own scrim darkens the backdrop instead
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();

        // AlertDialog reinstalls its own (blank) content during show(), so our
        // content only sticks if it is set AFTERWARDS
        dialog.setContentView(root);

        if (window != null) {
            // Must be set after show() to take effect
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }

        View.OnClickListener dismiss = v -> dialog.dismiss();
        if (blurredView != null) {
            blurredView.setOnClickListener(dismiss);
        }
        scrim.setOnClickListener(dismiss);
        return dialog;
    }

    /**
     * Captures the activity's content, downsamples it and blurs it for use as a
     * dialog backdrop. Returns null if the capture fails; callers fall back to
     * a plain dim layer.
     */
    private Bitmap createBlurredBackground() {
        try {
            View decor = getWindow().getDecorView();
            if (decor == null || decor.getWidth() == 0 || decor.getHeight() == 0) {
                return null;
            }

            Bitmap shot = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(shot);
            decor.draw(canvas);

            // Downsample heavily first: the blur runs on the small image (fast)
            // and gets stretched back up to full size (which smooths it further)
            float scale = 0.15f;
            int dw = Math.max(1, (int) (shot.getWidth() * scale));
            int dh = Math.max(1, (int) (shot.getHeight() * scale));
            Bitmap small = Bitmap.createScaledBitmap(shot, dw, dh, true);
            shot.recycle();

            Bitmap blurred = boxBlurBitmap(small, 4);
            small.recycle();
            return blurred;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create blurred background", e);
            return null;
        }
    }

    /** Blurs a small bitmap with three box-blur passes — approximates a gaussian. */
    private Bitmap boxBlurBitmap(Bitmap src, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        for (int pass = 0; pass < 3; pass++) {
            pixels = boxBlurAxis(pixels, w, h, radius, true);
            pixels = boxBlurAxis(pixels, w, h, radius, false);
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    /** One sliding-window box blur pass over one axis (horizontal or vertical). */
    private int[] boxBlurAxis(int[] pix, int w, int h, int r, boolean horizontal) {
        int[] out = new int[pix.length];
        int outer = horizontal ? h : w;
        int inner = horizontal ? w : h;
        int step = horizontal ? 1 : w;

        for (int o = 0; o < outer; o++) {
            int base = horizontal ? o * w : o;
            int sumR = 0, sumG = 0, sumB = 0;
            int div = 2 * r + 1;

            // Initial window [-r .. r], clamped at the edges
            for (int i = -r; i <= r; i++) {
                int p = pix[base + clampIndex(i, 0, inner - 1) * step];
                sumR += (p >> 16) & 0xFF;
                sumG += (p >> 8) & 0xFF;
                sumB += p & 0xFF;
            }

            for (int i = 0; i < inner; i++) {
                out[base + i * step] = 0xFF000000
                        | ((sumR / div) << 16) | ((sumG / div) << 8) | (sumB / div);
                int leaving = pix[base + clampIndex(i - r, 0, inner - 1) * step];
                int entering = pix[base + clampIndex(i + r + 1, 0, inner - 1) * step];
                sumR += ((entering >> 16) & 0xFF) - ((leaving >> 16) & 0xFF);
                sumG += ((entering >> 8) & 0xFF) - ((leaving >> 8) & 0xFF);
                sumB += (entering & 0xFF) - (leaving & 0xFF);
            }
        }
        return out;
    }

    private int clampIndex(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private void showEqualizerPanel() {
        if (mediaPlayer == null) {
            Toast.makeText(this, "Play a song first to open Equalizer", Toast.LENGTH_SHORT).show();
            return;
        }

        int audioSessionId = mediaPlayer.getAudioSessionId();
        openAudioEffectSession(audioSessionId);

        if (!ensureEqualizerInitialized() || equalizer == null) {
            Toast.makeText(this, "Unable to initialize equalizer", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            short[] bandRange = equalizer.getBandLevelRange();
            short minLevel = bandRange[0];
            short maxLevel = bandRange[1];

            ScrollView scrollView = new ScrollView(this);
            LinearLayout container = new LinearLayout(this);
            container.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (16 * getResources().getDisplayMetrics().density);
            container.setPadding(padding, padding, padding, padding);
            scrollView.addView(container);

            TextView titleInfo = new TextView(this);
            titleInfo.setText("Adjust bands for current track");
            titleInfo.setTextSize(14);
            container.addView(titleInfo);

            // Horizontal, scrollable preset row with multiple presets
            HorizontalScrollView presetScroll = new HorizontalScrollView(this);
            presetScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout presetRow = new LinearLayout(this);
            presetRow.setOrientation(LinearLayout.HORIZONTAL);
            presetRow.setPadding(0, padding / 2, 0, padding / 2);

            String[] morePresets = new String[]{"Rock", "Soft", "Pop", "Jazz", "Classical", "Dance", "HipHop", "Blues", "Electronic", "Acoustic", "Flat"};
            for (String p : morePresets) {
                final String presetName = p;
                Button b = new Button(this);
                b.setText(presetName);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(padding/6, 0, padding/6, 0);
                b.setLayoutParams(lp);
                b.setOnClickListener(v -> {
                    if ("Flat".equals(presetName)) {
                        resetEqualizerLevels();
                    } else {
                        applyCustomEqualizerPreset(presetName);
                    }
                    syncEqualizerSeekBars(scrollView);
                });
                presetRow.addView(b);
            }

            presetScroll.addView(presetRow);
            container.addView(presetScroll);

            if (equalizer.getNumberOfPresets() > 0) {
                Button devicePresetBtn = new Button(this);
                devicePresetBtn.setText("Device Preset");
                devicePresetBtn.setOnClickListener(v -> showDevicePresetDialog());
                container.addView(devicePresetBtn);
            }

            short bands = equalizer.getNumberOfBands();
            bandSeekIds = new int[bands];
            for (short band = 0; band < bands; band++) {
                int centerHz = equalizer.getCenterFreq(band) / 1000;
                short currentLevel = getEqBaseBandLevel(band);

                TextView bandLabel = new TextView(this);
                bandLabel.setText(String.format(Locale.getDefault(), "Band %d (%d Hz)", band + 1, centerHz));
                bandLabel.setPadding(0, padding / 2, 0, padding / 4);
                container.addView(bandLabel);

                SeekBar bandSeek = new SeekBar(this);
                bandSeek.setMax(maxLevel - minLevel);
                bandSeek.setProgress(currentLevel - minLevel);
                int seekId = View.generateViewId();
                bandSeek.setId(seekId);
                bandSeekIds[band] = seekId;
                short targetBand = band;
                bandSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser && equalizer != null) {
                            try {
                                equalizer.setEnabled(true);
                                setEqBandLevel(targetBand, (short) (progress + minLevel));
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to set equalizer band level", e);
                            }
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
                container.addView(bandSeek);
            }

            TextView bassLabel = new TextView(this);
            bassLabel.setText("Bass Boost");
            bassLabel.setPadding(0, padding, 0, padding / 4);
            container.addView(bassLabel);

            SeekBar bassSeek = new SeekBar(this);
            bassSeek.setMax(1000);
            bassSeek.setProgress(bassBoost != null ? bassBoost.getRoundedStrength() : 0);
            bassSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && bassBoost != null) {
                        try {
                            bassBoost.setStrength((short) progress);
                            bassBoost.setEnabled(progress > 0);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set bass boost", e);
                        }
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            container.addView(bassSeek);

            TextView virtualizerLabel = new TextView(this);
            virtualizerLabel.setText("Virtualizer");
            virtualizerLabel.setPadding(0, padding, 0, padding / 4);
            container.addView(virtualizerLabel);

            SeekBar virtualizerSeek = new SeekBar(this);
            virtualizerSeek.setMax(1000);
            virtualizerSeek.setProgress(virtualizer != null ? virtualizer.getRoundedStrength() : 0);
            virtualizerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && virtualizer != null) {
                        try {
                            virtualizer.setStrength((short) progress);
                            virtualizer.setEnabled(progress > 0);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set virtualizer", e);
                        }
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            container.addView(virtualizerSeek);

            new AlertDialog.Builder(this)
                    .setTitle("Equalizer")
                    .setView(scrollView)
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Reset", (dialog, which) -> {
                        resetEqualizerLevels();
                        syncEqualizerSeekBars(scrollView);
                    })
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to open equalizer panel", e);
            Toast.makeText(this, "Unable to open equalizer", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAudioEffectSession(int audioSessionId) {
        if (audioSessionId <= 0) {
            return;
        }
        try {
            Intent openIntent = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            openIntent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
            openIntent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
            sendBroadcast(openIntent);
        } catch (Exception e) {
            Log.w(TAG, "Failed to broadcast open audio effect session", e);
        }
    }

    private void closeAudioEffectSession(int audioSessionId) {
        if (audioSessionId <= 0) {
            return;
        }
        try {
            Intent closeIntent = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            closeIntent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
            closeIntent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
            sendBroadcast(closeIntent);
        } catch (Exception e) {
            Log.w(TAG, "Failed to broadcast close audio effect session", e);
        }
    }

    private boolean ensureEqualizerInitialized() {
        if (mediaPlayer == null) {
            return false;
        }

        try {
            int sessionId = mediaPlayer.getAudioSessionId();
            if (sessionId <= 0) {
                return false;
            }

            if (equalizer != null && equalizerSessionId == sessionId) {
                return true;
            }

            releaseEqualizerEffects();
            // Use non-zero priority to improve compatibility on devices that reserve
            // audio effect control for system-level equalizers (e.g., some MIUI builds).
            equalizer = new Equalizer(1, sessionId);
            equalizer.setEnabled(true);
            Log.d(TAG, "Equalizer init: session=" + sessionId + ", bands=" + equalizer.getNumberOfBands());
            if (!equalizer.hasControl()) {
                Log.w(TAG, "Equalizer initialized without control for session " + sessionId);
            }
            bassBoost = new BassBoost(1, sessionId);
            bassBoost.setEnabled(true);
            virtualizer = new Virtualizer(1, sessionId);
            virtualizer.setEnabled(true);
            environmentalReverb = new EnvironmentalReverb(0, sessionId);
            environmentalReverb.setEnabled(false);
            try {
                echoReverb = new EnvironmentalReverb(0, sessionId);
                echoReverb.setEnabled(false);
            } catch (Exception e) {
                Log.w(TAG, "Echo reverb unavailable", e);
                echoReverb = null;
            }
            // Noise suppressor for the Noise Reduction toggle; not supported on
            // every device/playback session — handled gracefully when absent.
            try {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionId);
                }
            } catch (Exception e) {
                Log.w(TAG, "Noise suppressor unavailable", e);
                noiseSuppressor = null;
            }

            equalizerSessionId = sessionId;
            initVoiceFxBandState();
            applyVoiceBass(voiceBassStrength);
            applyVoiceReverb(voiceReverbLevel);
            applyVoiceEcho(voiceEchoLevel);
            applyVoiceFxEqualizer();
            applyVoiceNoiseReduction(false);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Unable to initialize audio effects", e);
            releaseEqualizerEffects();
            return false;
        }
    }

    private void resetEqualizerLevels() {
        if (equalizer == null) {
            return;
        }

        try {
            short bands = equalizer.getNumberOfBands();
            for (short band = 0; band < bands; band++) {
                setEqBandLevel(band, (short) 0);
            }
            if (bassBoost != null) {
                bassBoost.setStrength((short) 0);
                bassBoost.setEnabled(false);
            }
            if (virtualizer != null) {
                virtualizer.setStrength((short) 0);
                virtualizer.setEnabled(false);
            }
            Toast.makeText(this, "Equalizer reset", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to reset equalizer", e);
        }
    }

    /** Same as {@link #resetEqualizerLevels()} but without a toast; used by preset selection. */
    private void resetEqualizerLevelsQuiet() {
        if (equalizer == null) {
            return;
        }

        try {
            short bands = equalizer.getNumberOfBands();
            for (short band = 0; band < bands; band++) {
                setEqBandLevel(band, (short) 0);
            }
            if (bassBoost != null) {
                bassBoost.setStrength((short) 0);
                bassBoost.setEnabled(false);
            }
            if (virtualizer != null) {
                virtualizer.setStrength((short) 0);
                virtualizer.setEnabled(false);
            }
            Toast.makeText(this, "None EQ preset applied", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to reset equalizer", e);
        }
    }

    private void resetAudioSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean resetSpeed = prefs.getBoolean(PREF_RESET_SPEED, true);
        boolean resetPitch = prefs.getBoolean(PREF_RESET_PITCH, true);
        boolean resetEqualizer = prefs.getBoolean(PREF_RESET_EQUALIZER, true);
        boolean resetBoost = prefs.getBoolean(PREF_RESET_BOOST, true);

        if (!resetSpeed && !resetPitch && !resetEqualizer && !resetBoost) {
            Toast.makeText(this, R.string.reset_nothing_selected, Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> resetItems = new ArrayList<>();

        if (resetSpeed) {
            currentPlaybackSpeed = 1.0f;
            primaryPlaybackSpeed = 1.0f;
            secondaryPlaybackSpeed = 1.0f;
            resetItems.add(getString(R.string.reset_option_speed));
        }

        if (resetPitch) {
            currentPitch = 1.0f;
            if (serviceBound && audioService != null) {
                audioService.setPitch(1.0f);
            }
            prefs.edit().putFloat("playback_pitch", 1.0f).apply();
            resetItems.add(getString(R.string.reset_option_pitch));
        }

        if (resetSpeed || resetPitch) {
            applyResetPlaybackParams(resetSpeed, resetPitch);
        }

        if (resetBoost) {
            currentBoost = 1.0f;
            if (serviceBound && audioService != null) {
                audioService.setBoost(1.0f);
            } else {
                prefs.edit().putFloat("volume_boost_factor", 1.0f).apply();
            }
            try {
                if (mediaPlayer != null) {
                    mediaPlayer.setVolume(1.0f, 1.0f);
                }
                if (secondMediaPlayer != null) {
                    secondMediaPlayer.setVolume(1.0f, 1.0f);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error resetting volume boost", e);
            }
            resetItems.add(getString(R.string.reset_option_boost));
        }

        if (resetEqualizer && ensureEqualizerInitialized() && equalizer != null) {
            try {
                short bands = equalizer.getNumberOfBands();
                for (short band = 0; band < bands; band++) {
                    setEqBandLevel(band, (short) 0);
                }
                if (bassBoost != null) {
                    bassBoost.setStrength((short) 0);
                    bassBoost.setEnabled(false);
                }
                if (virtualizer != null) {
                    virtualizer.setStrength((short) 0);
                    virtualizer.setEnabled(false);
                }
                resetItems.add(getString(R.string.reset_option_equalizer));
            } catch (Exception e) {
                Log.e(TAG, "Failed to reset equalizer during audio reset", e);
            }
        }

        if (!resetItems.isEmpty()) {
            Toast.makeText(this,
                    getString(R.string.reset_to_default, TextUtils.join(", ", resetItems)),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void applyResetPlaybackParams(boolean resetSpeed, boolean resetPitch) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        float pitchToApply = resetPitch ? 1.0f : currentPitch;

        if (mediaPlayer != null) {
            float speedToApply = resetSpeed
                    ? 1.0f
                    : (secondAudioActive && useIndividualPlaybackSpeeds
                    ? primaryPlaybackSpeed
                    : currentPlaybackSpeed);
            applyPlaybackParams(mediaPlayer, speedToApply, pitchToApply);
        }

        if (secondMediaPlayer != null && secondAudioActive) {
            float speedToApply = resetSpeed
                    ? 1.0f
                    : (useIndividualPlaybackSpeeds ? secondaryPlaybackSpeed : currentPlaybackSpeed);
            applyPlaybackParams(secondMediaPlayer, speedToApply, pitchToApply);
        }
    }

    private void applyPlaybackParams(MediaPlayer player, float speed, float pitch) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || player == null) {
            return;
        }
        try {
            float formant = currentFormant > 0f ? currentFormant : 1.0f;
            float finalPitch = pitch * formant;
            if (voiceAutoTuneEnabled && finalPitch > 0f) {
                // Auto-tune: constrain the playback pitch to the nearest exact
                // semitone of the equal-tempered scale (2^(n/12))
                double semitones = Math.round(12.0 * (Math.log(finalPitch) / Math.log(2)));
                finalPitch = (float) Math.pow(2.0, semitones / 12.0);
            }
            PlaybackParams params = new PlaybackParams();
            params.setSpeed(speed / formant);
            params.setPitch(finalPitch);
            player.setPlaybackParams(params);
        } catch (Exception e) {
            Log.e(TAG, "Error applying playback params", e);
        }
    }

    private void applyVoiceSettingsToActivePlayers() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        if (mediaPlayer != null) {
            float speed = secondAudioActive && useIndividualPlaybackSpeeds
                    ? primaryPlaybackSpeed
                    : currentPlaybackSpeed;
            applyPlaybackParams(mediaPlayer, speed, currentPitch);
        }
        if (secondMediaPlayer != null && secondAudioActive) {
            float speed = useIndividualPlaybackSpeeds
                    ? secondaryPlaybackSpeed
                    : currentPlaybackSpeed;
            applyPlaybackParams(secondMediaPlayer, speed, currentPitch);
        }
    }

    private void applyVoiceBass(int strength) {
        voiceBassStrength = Math.max(0, Math.min(1000, strength));
        if (!ensureEqualizerInitialized() || bassBoost == null) {
            return;
        }
        try {
            short value = (short) voiceBassStrength;
            bassBoost.setStrength(value);
            bassBoost.setEnabled(value > 0);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply voice bass", e);
        }
    }

    private void applyVoiceReverb(int level) {
        voiceReverbLevel = Math.max(0, Math.min(1000, level));
        if (!ensureEqualizerInitialized() || environmentalReverb == null) {
            return;
        }
        try {
            if (voiceReverbLevel <= 0) {
                environmentalReverb.setEnabled(false);
                return;
            }
            EnvironmentalReverb.Settings settings = new EnvironmentalReverb.Settings();
            settings.roomLevel = (short) (-9000 + (voiceReverbLevel * 5000 / 1000));
            settings.reverbLevel = (short) (-9000 + (voiceReverbLevel * 9000 / 1000));
            settings.decayTime = 400 + voiceReverbLevel;
            settings.reflectionsLevel = (short) (-9000 + (voiceReverbLevel * 7000 / 1000));
            environmentalReverb.setProperties(settings);
            environmentalReverb.setEnabled(true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply voice reverb", e);
        }
    }

    /**
     * Echo: a dedicated EnvironmentalReverb configured for discrete, delayed
     * reflections (low diffusion/density, short decay) so it sounds like distinct
     * repeats rather than the smooth room tail of the Reverb control.
     */
    private void applyVoiceEcho(int level) {
        voiceEchoLevel = Math.max(0, Math.min(1000, level));
        if (!ensureEqualizerInitialized() || echoReverb == null) {
            return;
        }
        try {
            if (voiceEchoLevel <= 0) {
                echoReverb.setEnabled(false);
                return;
            }
            EnvironmentalReverb.Settings settings = new EnvironmentalReverb.Settings();
            settings.roomLevel = (short) (-9000 + (voiceEchoLevel * 4000 / 1000));
            settings.reflectionsLevel = (short) (-9000 + (voiceEchoLevel * 8500 / 1000));
            settings.reflectionsDelay = 90 + voiceEchoLevel / 5; // 90..290 ms
            settings.reverbLevel = (short) (-9000 + (voiceEchoLevel * 5000 / 1000));
            settings.reverbDelay = 20;
            settings.decayTime = 200 + voiceEchoLevel; // 200..1200 ms
            settings.diffusion = (short) 0; // discrete repeats, not a wash
            settings.density = (short) 0;
            echoReverb.setProperties(settings);
            echoReverb.setEnabled(true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply voice echo", e);
        }
    }

    /**
     * Recomputes the voice-FX band offsets from the treble, vocal clarity and
     * distortion settings and re-applies them on top of the main equalizer's base
     * levels. A single equalizer instance is used on purpose: a second Equalizer on
     * the same audio session takes over the effect engine and silently disables the
     * main equalizer (which is why EQ presets stopped working).
     */
    private void applyVoiceFxEqualizer() {
        if (!ensureEqualizerInitialized() || equalizer == null) {
            return;
        }
        try {
            if (voiceFxBandOffsets == null || eqBaseBandLevels == null) {
                initVoiceFxBandState();
            }
            if (voiceFxBandOffsets == null || eqBaseBandLevels == null) {
                return;
            }
            short bands = equalizer.getNumberOfBands();
            short[] range = equalizer.getBandLevelRange();
            short max = range[1];
            short min = range[0];

            float treble = voiceTrebleLevel / 1000f;         // -1..1
            float clarity = voiceClarityLevel / 1000f;       // 0..1
            float distortion = voiceDistortionLevel / 1000f; // 0..1
            float depth = voiceDepthLevel / 1000f;           // -1..1 (thin..deep)

            for (short band = 0; band < bands && band < voiceFxBandOffsets.length; band++) {
                int freqHz = equalizer.getCenterFreq(band) / 1000; // mHz -> Hz
                float offset = 0f;

                // Treble: boost or cut the high frequencies
                if (freqHz >= 4000) {
                    offset += treble * max * 0.8f;
                } else if (freqHz >= 2000) {
                    offset += treble * max * 0.4f;
                }

                // Vocal clarity: lift the speech band, trim the muddy lows
                if (freqHz >= 800 && freqHz <= 4500) {
                    offset += clarity * max * 0.55f;
                } else if (freqHz <= 250) {
                    offset -= clarity * max * 0.2f;
                }

                // Voice depth: positive deepens (bigger lows, softer highs),
                // negative thins the voice out (cut lows, lift highs)
                if (freqHz <= 250) {
                    offset += depth * max * 0.5f;
                } else if (freqHz >= 4000) {
                    offset -= depth * max * 0.3f;
                }

                // Robot: fixed metallic comb for a synthetic timbre
                if (voiceRobotEnabled) {
                    float sign = (band % 2 == 0) ? 1f : -1f;
                    offset += sign * max * 0.45f;
                    if (freqHz >= 1500 && freqHz <= 5000) {
                        offset += max * 0.25f;
                    }
                    if (freqHz <= 200) {
                        offset -= Math.abs(min) * 0.35f;
                    }
                }

                // Distortion: alternating boost/cut comb for a metallic, aggressive
                // tone, plus presence push and low cut to make it harsher
                if (distortion > 0f) {
                    float sign = (band % 2 == 0) ? 1f : -1f;
                    offset += sign * distortion * max * 0.55f;
                    if (freqHz >= 1500 && freqHz <= 6000) {
                        offset += distortion * max * 0.35f;
                    }
                    if (freqHz <= 200) {
                        offset -= distortion * Math.abs(min) * 0.4f;
                    }
                }

                voiceFxBandOffsets[band] = (short) Math.max(min, Math.min(max, Math.round(offset)));
                short total = (short) Math.max(min, Math.min(max,
                        eqBaseBandLevels[band] + voiceFxBandOffsets[band]));
                equalizer.setBandLevel(band, total);
            }
            equalizer.setEnabled(true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply voice FX equalizer", e);
        }
    }

    /** Sizes the voice-FX band state and seeds base levels from the equalizer. */
    private void initVoiceFxBandState() {
        if (equalizer == null) {
            voiceFxBandOffsets = null;
            eqBaseBandLevels = null;
            return;
        }
        try {
            short bands = equalizer.getNumberOfBands();
            voiceFxBandOffsets = new short[bands];
            eqBaseBandLevels = new short[bands];
            for (short band = 0; band < bands; band++) {
                eqBaseBandLevels[band] = equalizer.getBandLevel(band);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to init voice FX band state", e);
            voiceFxBandOffsets = null;
            eqBaseBandLevels = null;
        }
    }

    /**
     * Sets a base band level on the main equalizer while keeping the additive
     * voice-FX offsets (treble/clarity/distortion) on top. Every code path that
     * writes EQ band levels (presets, panel sliders, resets) must use this.
     */
    private void setEqBandLevel(short band, short level) {
        if (equalizer == null) {
            return;
        }
        try {
            if (eqBaseBandLevels != null && band < eqBaseBandLevels.length) {
                eqBaseBandLevels[band] = level;
            }
            short offset = (voiceFxBandOffsets != null && band < voiceFxBandOffsets.length)
                    ? voiceFxBandOffsets[band] : 0;
            short[] range = equalizer.getBandLevelRange();
            short total = (short) Math.max(range[0], Math.min(range[1], level + offset));
            equalizer.setBandLevel(band, total);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set equalizer band level", e);
        }
    }

    /**
     * Adopts the equalizer's current band levels as the new base (e.g. after a
     * device preset overwrote them via usePreset) and re-applies the FX offsets.
     */
    private void syncEqBaseFromCurrentLevels() {
        if (equalizer == null) {
            return;
        }
        try {
            if (eqBaseBandLevels == null) {
                initVoiceFxBandState();
                return;
            }
            short bands = equalizer.getNumberOfBands();
            short[] range = equalizer.getBandLevelRange();
            for (short band = 0; band < bands && band < eqBaseBandLevels.length; band++) {
                eqBaseBandLevels[band] = equalizer.getBandLevel(band);
                short offset = (voiceFxBandOffsets != null && band < voiceFxBandOffsets.length)
                        ? voiceFxBandOffsets[band] : 0;
                short total = (short) Math.max(range[0], Math.min(range[1],
                        eqBaseBandLevels[band] + offset));
                equalizer.setBandLevel(band, total);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync equalizer base levels", e);
        }
    }

    /** The base band level without the voice-FX offset (for panel UI display). */
    private short getEqBaseBandLevel(short band) {
        if (eqBaseBandLevels != null && band < eqBaseBandLevels.length) {
            return eqBaseBandLevels[band];
        }
        try {
            return equalizer != null ? equalizer.getBandLevel(band) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Noise Reduction via the framework NoiseSuppressor. Many devices do not
     * support it on media playback sessions, so absence is handled gracefully.
     */
    private void applyVoiceNoiseReduction(boolean showError) {
        if (mediaPlayer == null) {
            return; // no session yet; re-applied when playback starts
        }
        if (!ensureEqualizerInitialized()) {
            return;
        }
        if (noiseSuppressor == null) {
            if (showError && voiceNoiseReductionEnabled) {
                Toast.makeText(this, "Noise reduction is not supported on this device",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        try {
            noiseSuppressor.setEnabled(voiceNoiseReductionEnabled);
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply noise reduction", e);
        }
    }

    /**
     * Vibrato: periodically modulates the playback pitch up and down (about 5.5 Hz,
     * up to roughly a semitone at full depth) by re-applying playback params on the
     * active players. The tick loop keeps running while the depth is above zero but
     * only touches players that are actually playing.
     */
    private final Runnable voiceVibratoRunnable = new Runnable() {
        @Override
        public void run() {
            if (voiceVibratoDepth <= 0) {
                return;
            }
            float depth = voiceVibratoDepth / 1000f;
            double elapsedSec = (System.currentTimeMillis() - voiceVibratoStartMs) / 1000.0;
            float modulation = 1f + 0.06f * depth
                    * (float) Math.sin(2.0 * Math.PI * 5.5 * elapsedSec);
            applyVoiceSettingsWithPitchModulation(modulation);
            handler.postDelayed(this, 50);
        }
    };

    private void startVoiceVibratoTicks() {
        if (voiceVibratoDepth <= 0 || voiceAutoTuneEnabled) {
            // Auto-tune constrains pitch to exact semitones, which flattens the
            // vibrato modulation — so vibrato is suspended while auto-tune is on.
            return;
        }
        voiceVibratoStartMs = System.currentTimeMillis();
        handler.removeCallbacks(voiceVibratoRunnable);
        handler.post(voiceVibratoRunnable);
    }

    private void stopVoiceVibratoTicks() {
        handler.removeCallbacks(voiceVibratoRunnable);
        // Restore the unmodulated pitch on the active players
        applyVoiceSettingsToActivePlayers();
    }

    /** Like {@link #applyVoiceSettingsToActivePlayers()} but with an extra pitch multiplier. */
    private void applyVoiceSettingsWithPitchModulation(float pitchMod) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        if (mediaPlayer != null && isPlayerActuallyPlaying(mediaPlayer)) {
            float speed = secondAudioActive && useIndividualPlaybackSpeeds
                    ? primaryPlaybackSpeed
                    : currentPlaybackSpeed;
            applyPlaybackParams(mediaPlayer, speed, currentPitch * pitchMod);
        }
        if (secondMediaPlayer != null && secondAudioActive && isPlayerActuallyPlaying(secondMediaPlayer)) {
            float speed = useIndividualPlaybackSpeeds
                    ? secondaryPlaybackSpeed
                    : currentPlaybackSpeed;
            applyPlaybackParams(secondMediaPlayer, speed, currentPitch * pitchMod);
        }
    }

    private boolean isPlayerActuallyPlaying(MediaPlayer player) {
        try {
            return player != null && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private void applyCustomEqualizerPreset(String presetName) {
        if (equalizer == null) {
            return;
        }

        try {
            short bands = equalizer.getNumberOfBands();
            short[] range = equalizer.getBandLevelRange();
            short max = range[1];
            short min = range[0];
            short boost = (short) (max * 0.6f);
            short lightBoost = (short) (max * 0.35f);
            short cut = (short) (min * 0.35f);

            for (short band = 0; band < bands; band++) {
                setEqBandLevel(band, (short) 0);
            }

            for (short band = 0; band < bands; band++) {
                float pos = bands == 1 ? 0f : (float) band / (float) (bands - 1);
                short level = 0;
                switch (presetName) {
                    case "Rock":
                        if (pos < 0.2f) level = lightBoost;
                        else if (pos > 0.7f) level = boost;
                        else if (pos > 0.45f && pos < 0.65f) level = cut;
                        break;
                    case "Soft":
                        if (pos < 0.3f) level = (short) (lightBoost * 0.5f);
                        else if (pos > 0.6f) level = (short) (lightBoost * 0.4f);
                        break;
                    case "Pop":
                        if (pos < 0.2f || pos > 0.75f) level = lightBoost;
                        else if (pos > 0.4f && pos < 0.6f) level = cut;
                        break;
                    case "Jazz":
                        if (pos < 0.2f) level = (short) (lightBoost * 0.7f);
                        else if (pos > 0.35f && pos < 0.65f) level = (short) (lightBoost * 0.6f);
                        else if (pos > 0.8f) level = (short) (lightBoost * 0.5f);
                        break;
                    default:
                        level = 0;
                        break;
                }
                if (level > max) level = max;
                if (level < min) level = min;
                setEqBandLevel(band, level);
            }
            equalizer.setEnabled(true);
            Toast.makeText(this, presetName + " preset applied", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply preset: " + presetName, e);
        }
    }

    /**
     * Applies a voice-oriented EQ preset to the equalizer attached to the current
     * audio session. Curves are shaped by band position (0 = lowest frequency band).
     */
    private void applyVoiceEqPreset(String presetName) {
        if ("None".equals(presetName)) {
            resetEqualizerLevelsQuiet();
            return;
        }
        if (!ensureEqualizerInitialized() || equalizer == null) {
            Toast.makeText(this, "Play a song first to use EQ presets", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            short bands = equalizer.getNumberOfBands();
            short[] range = equalizer.getBandLevelRange();
            short max = range[1];
            short min = range[0];
            short boost = (short) (max * 0.6f);
            short lightBoost = (short) (max * 0.35f);
            short cut = (short) (min * 0.35f);
            short deepCut = (short) (min * 0.55f);

            for (short band = 0; band < bands; band++) {
                float pos = bands == 1 ? 0f : (float) band / (float) (bands - 1);
                short level = 0;
                switch (presetName) {
                    case "Male":
                        // Emphasize low-mids, tame highest bands
                        if (pos < 0.35f) level = boost;
                        else if (pos > 0.7f) level = cut;
                        break;
                    case "Female":
                        // Emphasize mids/high-mids, cut lows slightly
                        if (pos > 0.35f && pos < 0.8f) level = boost;
                        else if (pos < 0.15f) level = cut;
                        break;
                    case "Child":
                        // Bright: boost highs, cut lows
                        if (pos > 0.5f) level = boost;
                        else if (pos < 0.2f) level = cut;
                        break;
                    case "Deep":
                        // Heavy low end
                        if (pos < 0.25f) level = boost;
                        else if (pos > 0.6f) level = deepCut;
                        break;
                    case "Radio":
                        // Narrow band: cut lows and highs, boost mids
                        if (pos > 0.3f && pos < 0.7f) level = lightBoost;
                        else level = cut;
                        break;
                    case "Flat":
                    default:
                        level = 0;
                        break;
                }
                if (level > max) level = max;
                if (level < min) level = min;
                setEqBandLevel(band, level);
            }
            equalizer.setEnabled(true);
            Toast.makeText(this, presetName + " EQ preset applied", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply voice EQ preset: " + presetName, e);
        }
    }

    private void showDevicePresetDialog() {
        if (equalizer == null) {
            return;
        }
        try {
            short presetCount = equalizer.getNumberOfPresets();
            if (presetCount <= 0) {
                return;
            }
            String[] presetNames = new String[presetCount];
            for (short i = 0; i < presetCount; i++) {
                presetNames[i] = equalizer.getPresetName(i);
            }

            new AlertDialog.Builder(this)
                    .setTitle("Device Presets")
                    .setItems(presetNames, (dialog, which) -> {
                        try {
                            equalizer.usePreset((short) which);
                            equalizer.setEnabled(true);
                            // The device preset overwrote the band levels; adopt them
                            // as the new base and re-apply the voice-FX offsets on top.
                            syncEqBaseFromCurrentLevels();
                            Toast.makeText(this, "Preset: " + presetNames[which], Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to apply device preset", e);
                        }
                    })
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show device presets", e);
        }
    }

    private void syncEqualizerSeekBars(View rootView) {
        if (equalizer == null || bandSeekIds == null) {
            return;
        }
        try {
            short[] bandRange = equalizer.getBandLevelRange();
            short minLevel = bandRange[0];
            short bands = equalizer.getNumberOfBands();
            for (short band = 0; band < bands; band++) {
                if (band >= bandSeekIds.length) {
                    break;
                }
                View view = rootView.findViewById(bandSeekIds[band]);
                if (view instanceof SeekBar) {
                    short level = getEqBaseBandLevel(band);
                    ((SeekBar) view).setProgress(level - minLevel);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync equalizer sliders", e);
        }
    }

    private void releaseEqualizerEffects() {
        try {
            if (equalizer != null) {
                equalizer.release();
                equalizer = null;
            }
            if (bassBoost != null) {
                bassBoost.release();
                bassBoost = null;
            }
            if (virtualizer != null) {
                virtualizer.release();
                virtualizer = null;
            }
            if (environmentalReverb != null) {
                environmentalReverb.release();
                environmentalReverb = null;
            }
            if (echoReverb != null) {
                echoReverb.release();
                echoReverb = null;
            }
            if (noiseSuppressor != null) {
                noiseSuppressor.release();
                noiseSuppressor = null;
            }
            voiceFxBandOffsets = null;
            eqBaseBandLevels = null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to release audio effects", e);
        } finally {
            equalizerSessionId = -1;
        }
    }

    private void togglePlayerExpansion() {
        setPlayerExpanded(!playerExpanded);
    }

    private void setupMiniProgressSeeking() {
        if (miniProgressBar == null) {
            return;
        }
        miniProgressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || mediaPlayer == null) {
                    return;
                }
                try {
                    mediaPlayer.seekTo(progress);
                    int duration = mediaPlayer.getDuration();
                    updateTimeText(progress, duration);
                    if (seekBar != null) {
                        if (duration > 0 && seekBar.getMax() != duration) {
                            seekBar.setMax(duration);
                        }
                        seekBar.setProgress(progress);
                    }
                    if (miniPlayerSubtitle != null && currentTimeText != null && totalTimeText != null) {
                        miniPlayerSubtitle.setText(
                                currentTimeText.getText().toString() + " / " + totalTimeText.getText().toString());
                    }
                    if (secondMediaPlayer != null && secondAudioActive) {
                        syncSecondPlayerPosition();
                    }
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error seeking from mini player", e);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                miniProgressSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                miniProgressSeeking = false;
            }
        });
        miniProgressBar.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private void setPlayerExpanded(boolean expanded) {
        playerExpanded = expanded;
        if (expandedPlayerControls != null) {
            expandedPlayerControls.setVisibility(playerExpanded ? View.VISIBLE : View.GONE);
        }
        // Only one progress bar at a time: hide the mini bar while expanded
        if (miniProgressBar != null) {
            miniProgressBar.setVisibility(playerExpanded ? View.GONE : View.VISIBLE);
        }
        // Rotate the expand arrow
        ImageView expandBtn = findViewById(R.id.expandPlayerBtn);
        if (expandBtn != null) {
            expandBtn.setRotation(playerExpanded ? 0 : 180);
        }
    }

    private void updateMiniPlayer() {
        if (miniPlayerTitle != null && fileNameText != null) {
            String title = fileNameText.getText().toString();
            miniPlayerTitle.setText(title.isEmpty() ? "No song selected" : title);
        }
        if (miniPlayerSubtitle != null && currentTimeText != null && totalTimeText != null) {
            String sub = currentTimeText.getText().toString() + " / " + totalTimeText.getText().toString();
            miniPlayerSubtitle.setText(sub);
        }
        if (miniPlayPauseBtn != null) {
            miniPlayPauseBtn.setImageResource(isPlaying ? R.drawable.ic_pause_improved : R.drawable.ic_play_improved);
        }
        if (miniProgressBar != null && mediaPlayer != null && !miniProgressSeeking) {
            try {
                int duration = mediaPlayer.getDuration();
                int position = mediaPlayer.getCurrentPosition();
                if (duration > 0) {
                    if (miniProgressBar.getMax() != duration) {
                        miniProgressBar.setMax(duration);
                    }
                    miniProgressBar.setProgress(position);
                }
            } catch (Exception ignored) {}
        }
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
                    applyPlaybackParams(mediaPlayer, speed, currentPitch);
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
                    applyPlaybackParams(secondMediaPlayer, speed, currentPitch);
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
                //Toast.makeText(this, "Playback speed: " + String.format("%.2fx", speed), Toast.LENGTH_SHORT).show();
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) params.setPitch(currentPitch);
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) params.setPitch(currentPitch);
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
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this);
        builder.setTitle(title);

        // Create a simple layout with a seekbar and text display
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad / 2, pad, pad / 4);

        TextView speedLabel = new TextView(this);
        speedLabel.setText(String.format("%.2fx", currentSpeed));
        speedLabel.setGravity(Gravity.CENTER);
        speedLabel.setTextSize(26);
        speedLabel.setTypeface(null, Typeface.BOLD);
        speedLabel.setTextColor(getResources().getColor(R.color.accent_primary, null));
        layout.addView(speedLabel);

        SeekBar speedSeekBar = new SeekBar(this);
        speedSeekBar.setMax(375);
        int initialProgress = (int)((currentSpeed - 0.25f) * 100);
        speedSeekBar.setProgress(initialProgress);
        speedSeekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.accent_primary, null)));
        speedSeekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.accent_primary, null)));
        speedSeekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.surface_600, null)));
        LinearLayout.LayoutParams sbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbParams.topMargin = pad / 2;
        speedSeekBar.setLayoutParams(sbParams);
        layout.addView(speedSeekBar);

        TextView rangeHint = new TextView(this);
        rangeHint.setText("0.25x – 4.00x");
        rangeHint.setGravity(Gravity.CENTER);
        rangeHint.setTextSize(11);
        rangeHint.setTextColor(getResources().getColor(R.color.text_tertiary, null));
        layout.addView(rangeHint);

        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float speed = 0.25f + (progress / 100.0f);
                if (speed > 4.0f) speed = 4.0f;
                speedLabel.setText(String.format("%.2fx", speed));

                // Preview the speed change if possible
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && fromUser) {
                        try {
                        PlaybackParams params = new PlaybackParams();
                        params.setSpeed(speed);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) params.setPitch(currentPitch);

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
                applyPlaybackParams(mediaPlayer, speed, currentPitch);
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
                applyPlaybackParams(secondMediaPlayer, speed, currentPitch);
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

        // Create common timer options
        LinearLayout optionsLayout = dialogView.findViewById(R.id.timer_quick_options);
        int[] standardMinutes = {5, 15, 30, 45, 60};

        for (int mins : standardMinutes) {
            Button optionButton = new Button(this);
            optionButton.setText(mins + " min");
            optionButton.setOnClickListener(v -> {
                minutesInput.setText(String.valueOf(mins));
            });

            // Add some margin between buttons
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 8, 0);
            optionButton.setLayoutParams(params);

            optionsLayout.addView(optionButton);
        }

        // Add "end of song" button
        Button endOfSongButton = new Button(this);
        endOfSongButton.setText("End of song");
        endOfSongButton.setOnClickListener(v -> {
            // Dismiss the dialog and set up end of song timer
            setupEndOfSongTimer();
            if (dialogView.getParent() instanceof DialogInterface) {
                ((DialogInterface) dialogView.getParent()).dismiss();
            }
        });

        // Add some margin between buttons
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 8, 0);
        endOfSongButton.setLayoutParams(params);

        optionsLayout.addView(endOfSongButton);

        // Add radio buttons for timer action
        RadioGroup actionGroup = dialogView.findViewById(R.id.timer_action_group);

        // Set the previously selected option (if dialog is reopened)
        if (timerAction == TIMER_ACTION_PAUSE) {
            actionGroup.check(R.id.radio_pause);
        } else if (timerAction == TIMER_ACTION_CLOSE_APP) {
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
                    int selectedId = actionGroup.getCheckedRadioButtonId();
                    if (selectedId == R.id.radio_pause) {
                        timerAction = TIMER_ACTION_PAUSE;
                    } else if (selectedId == R.id.radio_close) {
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
     * Sets up a timer that will stop playback at the end of the current song
     */
    private void setupEndOfSongTimer() {
        if (mediaPlayer == null || !mediaPlayer.isPlaying()) {
            Toast.makeText(this, "No audio is currently playing", Toast.LENGTH_SHORT).show();
            return;
        }

        timerActive = true;

        // Save a reference to the standard completion handler
        MediaPlayer.OnCompletionListener originalCompletionListener = mp -> handleSongCompletion();

        // Create a one-time completion listener
        mediaPlayer.setOnCompletionListener(mp -> {
            // Pause playback instead of continuing to next song
            if (mediaPlayer != null) {
                mediaPlayer.pause();
                safeSetImageResource(playPauseButton, R.drawable.ic_play_improved);
                isPlaying = false;
            }

            // If there's a second player, pause it too
            if (secondMediaPlayer != null && secondAudioActive && secondMediaPlayer.isPlaying()) {
                secondMediaPlayer.pause();
            }

            timerActive = false;
            timerIndicator.setVisibility(View.GONE);
            Toast.makeText(this, "Sleep timer: end of song reached", Toast.LENGTH_SHORT).show();

            // Reset to original completion behavior
            if (mediaPlayer != null) {
                mediaPlayer.setOnCompletionListener(originalCompletionListener);
            }
        });

        // Display a message
        String timeText = "Until end of song";
        timerIndicator.setText(timeText);
        timerIndicator.setVisibility(View.VISIBLE);

        Toast.makeText(this, "Timer will stop at end of current song", Toast.LENGTH_SHORT).show();
    }

    private void cancelSleepTimer() {
        if (sleepTimer != null) {
            sleepTimer.cancel();
            sleepTimer = null;
        }
        if (audioService != null) {
            audioService.cancelTimer();
        }
        timerActive = false;
        timerIndicator.setVisibility(View.GONE);
        Toast.makeText(this, "Sleep timer cancelled", Toast.LENGTH_SHORT).show();
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
                ContextCompat.registerReceiver(this, timerUpdateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
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
        // Make the timer indicator visible and update the time display
        if (millisUntilFinished > 0) {
            // Format time as mm:ss
            int minutes = (int) (millisUntilFinished / 1000) / 60;
            int seconds = (int) (millisUntilFinished / 1000) % 60;
            String timeText = String.format(Locale.getDefault(), "%02d:%02d remaining", minutes, seconds);

            timerIndicator.setText(timeText);
            timerIndicator.setVisibility(View.VISIBLE);
        } else {
            // Hide the timer indicator when timer is not active
            timerIndicator.setVisibility(View.GONE);
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
            // (with descriptor fallback for formats that fail via the URI path)
            setDataSourceCompat(secondMediaPlayer, secondAudioUri, 0);

            // Set listeners with careful error handling
            secondMediaPlayer.setOnPreparedListener(mp -> {
                secondAudioActive = true;

                // Keep the service's reference and flag in sync — the service may
                // still hold the previous (possibly released) second player instance
                if (serviceBound && audioService != null) {
                    audioService.setSecondMediaPlayer(secondMediaPlayer);
                    audioService.setSecondAudioActive(true);
                }

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
                // Drop the service's now-dead reference
                if (serviceBound && audioService != null) {
                    audioService.setSecondMediaPlayer(null);
                    audioService.setSecondAudioActive(false);
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

    private void startSecondTrackIfMixerReady() {
        if (mediaPlayer == null || secondMediaPlayer == null || !secondAudioActive) {
            return;
        }

        try {
            if (mediaPlayer.isPlaying() && !secondMediaPlayer.isPlaying()) {
                syncSecondPlayerPosition();
                secondMediaPlayer.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting second track for mixer", e);
        }
    }

    /**
     * Starts the second (mixer) track unless it already played to its end —
     * calling start() on a finished MediaPlayer would loop it back to 0:00.
     */
    private void startSecondTrackIfNotFinished() {
        if (secondMediaPlayer == null || !secondAudioActive) {
            return;
        }
        try {
            boolean finished = secondMediaPlayer.getCurrentPosition()
                    >= secondMediaPlayer.getDuration() - 50;
            if (!finished) {
                secondMediaPlayer.start();
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Remove the auto-pause code to allow background playback
    }

    @Override
    protected void onDestroy() {
        // Unregister phone state receiver
        if (phoneStateReceiver != null) {
            try {
                unregisterReceiver(phoneStateReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering phone state receiver", e);
            }
        }
        
        // Release audio focus
        abandonAudioFocus();

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
            handler.removeCallbacks(voiceVibratoRunnable);
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

        if (mediaPlayer != null) {
            closeAudioEffectSession(mediaPlayer.getAudioSessionId());
        }
        releaseEqualizerEffects();

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

        // Handle consent results for rename/delete
        if (requestCode == REQUEST_WRITE_PERMISSION && resultCode == RESULT_OK && pendingRenameFile != null) {
            String name = pendingRenameNewName;
            AudioFile file = pendingRenameFile;
            pendingRenameFile = null;
            pendingRenameNewName = null;
            renameFile(file, name);
            return;
        } else if (requestCode == REQUEST_DELETE_PERMISSION && resultCode == RESULT_OK && pendingDeleteFile != null) {
            AudioFile file = pendingDeleteFile;
            pendingDeleteFile = null;
            deleteFile(file);
            return;
        } else if (requestCode == REQUEST_SAF_EDIT && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                try {
                    getContentResolver().takePersistableUriPermission(data.getData(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to persist SAF permission", e);
                }
            }
            if (pendingRenameFile != null) {
                String name = pendingRenameNewName;
                AudioFile file = pendingRenameFile;
                pendingRenameFile = null;
                pendingRenameNewName = null;
                renameFile(file, name);
                return;
            }
            if (pendingDeleteFile != null) {
                AudioFile file = pendingDeleteFile;
                pendingDeleteFile = null;
                deleteFile(file);
                return;
            }
        }

        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();

            if (requestCode == REQUEST_BROWSE_AUDIO) {
                // Handle primary audio selection
                selectedAudioUri = uri;
                String fileName = getFileNameFromUri(uri);
                fileNameText.setText(fileName);
                updateMiniPlayer();

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
                MediaStore.Audio.Media.DATE_ADDED,  // Make sure DATE_ADDED is included
                MediaStore.Audio.Media.BUCKET_DISPLAY_NAME  // Folder (bucket) name
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
            // BUCKET_DISPLAY_NAME may be absent on some devices/providers
            int bucketColumn = cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME);

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

                String folderName = bucketColumn != -1 ? cursor.getString(bucketColumn) : null;

                String durationFormatted = formatTime((int)duration);
                Uri contentUri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));

                AudioFile audioFile = new AudioFile(displayName, durationFormatted, contentUri, id, size, dateAdded);
                audioFile.setFolderName(folderName);
                allAudioFiles.add(audioFile);
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
        if (currentPlaybackMode == PLAYBACK_MODE_RANDOM) {
            syncRandomHistory(selectedAudioUri);
        }
        String fileName = audioFile.getTitle();
        fileNameText.setText(fileName);
        updateMiniPlayer();
        saveNowPlayingPrefs();

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
        // Update the highlight in the adapter
        if (audioAdapter != null && audioFile != null) {
            audioAdapter.setCurrentlyPlayingUri(audioFile.getUri());
        }
        autoScrollToCurrentSongIfEnabled();
    }

    private void onSecondAudioSelected(AudioFile audioFile) {
        secondAudioUri = audioFile.getUri();
        
        // Update the second file name text
        TextView secondFileNameText = findViewById(R.id.secondFileNameText);
        secondFileNameText.setText(audioFile.getTitle());
        
        // Rest of the method remains the same
        prepareSecondMediaPlayer();
        
        secondAudioActive = true;
        updateMixerIndicator();
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
                // If in playlist view, we need to update current playlist index
                if (inPlaylistView) {
                    for (int i = 0; i < currentPlaylistSongs.size(); i++) {
                        if (currentPlaylistSongs.get(i).getUri().equals(audioFile.getUri())) {
                            currentPlaylistIndex = i;
                            break;
                        }
                    }
                }

                // Play the selected audio
                onAudioFileSelected(audioFile);
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

            @Override
            public void onAddToPlaylistClick(AudioFile audioFile) {
                showAddToPlaylistDialog(audioFile);
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
        TextView mimeText = view.findViewById(R.id.detail_mime);
        TextView folderText = view.findViewById(R.id.detail_folder);
        TextView dateAddedText = view.findViewById(R.id.detail_date_added);
        TextView pathText = view.findViewById(R.id.detail_path);

        // Set the details
        titleText.setText(audioFile.getTitle());
        durationText.setText(audioFile.getDuration());
        sizeText.setText(audioFile.getFormattedSize());

        // Extra metadata straight from MediaStore
        String mimeType = queryAudioMetadata(audioFile.getUri(), MediaStore.Audio.Media.MIME_TYPE);
        mimeText.setText(!TextUtils.isEmpty(mimeType) ? mimeType : "audio/*");
        String bucket = queryAudioMetadata(audioFile.getUri(), MediaStore.Audio.Media.BUCKET_DISPLAY_NAME);
        folderText.setText(!TextUtils.isEmpty(bucket) ? bucket : getString(R.string.unknown_folder));
        dateAddedText.setText(formatDateAdded(audioFile.getDateAdded()));

        final String displayPath = getReadablePathFromUri(audioFile.getUri());
        pathText.setText(displayPath);

        // Tap the path to copy it to the clipboard
        pathText.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip =
                        android.content.ClipData.newPlainText("File path", displayPath);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, R.string.path_copied, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to copy path to clipboard", e);
            }
        });

        builder.setView(view);
        builder.setPositiveButton(R.string.ok, null);
        builder.show();
    }

    /**
     * Reads a single metadata column for the given content URI from MediaStore.
     * Returns null when the column is missing or the query fails.
     */
    private String queryAudioMetadata(Uri uri, String column) {
        if (uri == null) {
            return null;
        }
        try {
            Cursor c = getContentResolver().query(uri, new String[] { column }, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex(column);
                        if (idx != -1) {
                            return c.getString(idx);
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query " + column + " for " + uri, e);
        }
        return null;
    }

    /**
     * Formats a MediaStore DATE_ADDED value (seconds since epoch) as a
     * human-readable local date, e.g. "Sep 3, 2026 14:05".
     */
    private String formatDateAdded(long dateAddedSeconds) {
        if (dateAddedSeconds <= 0) {
            return getString(R.string.unknown_date);
        }
        try {
            java.text.DateFormat dateFmt =
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
            java.text.DateFormat timeFmt =
                    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT);
            long millis = dateAddedSeconds * 1000L;
            return dateFmt.format(new java.util.Date(millis))
                    + " " + timeFmt.format(new java.util.Date(millis));
        } catch (Exception e) {
            return String.valueOf(dateAddedSeconds);
        }
    }

    // Derive a readable path from a content/file URI for display in the details dialog
    private String getReadablePathFromUri(Uri uri) {
        try {
            if (uri == null) return "";
            String scheme = uri.getScheme();
            if ("file".equalsIgnoreCase(scheme)) {
                return uri.getPath();
            }

            if ("content".equalsIgnoreCase(scheme)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    String[] projection = new String[] {
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.VOLUME_NAME
                    };
                    Cursor c = getContentResolver().query(uri, projection, null, null, null);
                    if (c != null) {
                        try {
                            if (c.moveToFirst()) {
                                int rpIdx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
                                int dnIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                                int vnIdx = c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME);
                                String rel = rpIdx != -1 ? c.getString(rpIdx) : null;
                                String name = dnIdx != -1 ? c.getString(dnIdx) : null;
                                String volume = vnIdx != -1 ? c.getString(vnIdx) : null;
                                if (!TextUtils.isEmpty(rel) && !TextUtils.isEmpty(name)) {
                                    // Build a full-looking path for display: /storage/<volume>/<RELATIVE_PATH><DISPLAY_NAME>
                                    String base;
                                    if ("external_primary".equalsIgnoreCase(volume) || "primary".equalsIgnoreCase(volume)) {
                                        base = Environment.getExternalStorageDirectory() != null
                                                ? Environment.getExternalStorageDirectory().getAbsolutePath()
                                                : "/storage/emulated/0";
                                    } else if (!TextUtils.isEmpty(volume)) {
                                        base = "/storage/" + volume;
                                    } else {
                                        base = "/storage/emulated/0";
                                    }
                                    if (!base.endsWith("/")) base = base + "/";
                                    return base + rel + name;
                                }
                                if (!TextUtils.isEmpty(name)) {
                                    return name;
                                }
                            }
                        } finally {
                            c.close();
                        }
                    }
                } else {
                    String[] projection = new String[] { MediaStore.MediaColumns.DATA };
                    Cursor c = getContentResolver().query(uri, projection, null, null, null);
                    if (c != null) {
                        try {
                            if (c.moveToFirst()) {
                                int dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA);
                                if (dataIdx != -1) {
                                    String absPath = c.getString(dataIdx);
                                    if (!TextUtils.isEmpty(absPath)) return absPath;
                                }
                            }
                        } finally {
                            c.close();
                        }
                    }
                }

                // Fallback: show the URI last segment or whole URI
                String last = uri.getLastPathSegment();
                return !TextUtils.isEmpty(last) ? last : uri.toString();
            }

            // Unknown scheme fallback
            return uri.toString();
        } catch (Exception e) {
            Log.w(TAG, "Failed to derive readable path from URI", e);
            return uri != null ? uri.toString() : "";
        }
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
        try {
            if (audioFile == null || audioFile.getUri() == null || TextUtils.isEmpty(newName)) return;

            // Preserve file extension if user omitted it
            String currentTitle = audioFile.getTitle();
            String extension = "";
            int dot = currentTitle.lastIndexOf('.');
            if (dot != -1) {
                extension = currentTitle.substring(dot); // includes dot
            }
            if (!newName.contains(".") && !TextUtils.isEmpty(extension)) {
                newName = newName + extension;
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, newName);
            int rows;
            try {
                rows = getContentResolver().update(audioFile.getUri(), values, null, null);
            } catch (Exception se) {
                // MediaStore refused (common for SD card without All files access);
                // fall through to the direct file-path rename below.
                Log.w(TAG, "MediaStore update refused for rename, trying direct file path", se);
                rows = 0;
            }
            if (rows > 0) {
                Toast.makeText(this, R.string.file_renamed, Toast.LENGTH_SHORT).show();
                refreshAudioFiles();
            } else {
                // Direct file-path rename first (works with All files access,
                // including SD card), then SAF as a last resort.
                if (renameDirectFile(audioFile, newName)) {
                    Toast.makeText(this, R.string.file_renamed, Toast.LENGTH_SHORT).show();
                    // refresh happens after the media scanner reindexes (see renameDirectFile)
                } else if (trySafRename(audioFile.getUri(), newName)) {
                    Toast.makeText(this, R.string.file_renamed, Toast.LENGTH_SHORT).show();
                    refreshAudioFiles();
                } else {
                    Toast.makeText(this, R.string.error_renaming_file, Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error renaming file", e);
            Toast.makeText(this, R.string.error_renaming_file, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Renames the file directly on disk via its real path. Because a raw
     * renameTo() can fail on SD-card/FUSE mounts, falls back to copy+delete.
     * After the on-disk rename, triggers a media rescan of both the old and
     * new paths so the MediaStore index reflects the change (otherwise the
     * song list keeps showing the old name). The list is refreshed once the
     * scan completes.
     */
    private boolean renameDirectFile(AudioFile audioFile, String newName) {
        try {
            String filePath = getRealFilePath(audioFile.getUri());
            if (filePath == null) {
                return false;
            }
            java.io.File file = new java.io.File(filePath);
            if (!file.exists()) {
                return false;
            }
            java.io.File target = new java.io.File(file.getParentFile(), newName);
            if (target.exists()) {
                // Don't clobber an existing file with the same name
                return false;
            }
            boolean renamed;
            try {
                renamed = file.renameTo(target);
            } catch (Exception e) {
                Log.w(TAG, "renameTo threw, trying copy+delete", e);
                renamed = false;
            }
            if (!renamed) {
                // Some SD-card/FUSE mounts refuse renameTo; copy+delete instead
                renamed = copyThenDelete(file, target);
            }
            if (!renamed) {
                return false;
            }

            // Best effort: update the existing row's display name
            try {
                ContentValues nameValues = new ContentValues();
                nameValues.put(MediaStore.MediaColumns.DISPLAY_NAME, newName);
                getContentResolver().update(audioFile.getUri(), nameValues, null, null);
            } catch (Exception e) {
                Log.w(TAG, "MediaStore display name not updated after direct rename", e);
            }

            // Rescan old + new paths so the MediaStore index matches the disk
            final String oldPath = filePath;
            final String newPath = target.getAbsolutePath();
            try {
                android.media.MediaScannerConnection.scanFile(this,
                        new String[] { oldPath, newPath }, null,
                        (path, uri) -> {
                            Log.d(TAG, "Media scan finished for " + path);
                            handler.post(this::refreshAudioFiles);
                        });
            } catch (Exception e) {
                Log.w(TAG, "Media scan failed; refreshing list anyway", e);
                refreshAudioFiles();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Direct file rename failed", e);
            return false;
        }
    }

    /**
     * Copies file content to target then removes the source. Used as the
     * fallback when File.renameTo() refuses to operate (some SD-card mounts).
     */
    private boolean copyThenDelete(java.io.File source, java.io.File target) {
        java.io.FileInputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(source);
            out = new java.io.FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
            in.close();
            out.close();
            in = null;
            out = null;
            return source.delete();
        } catch (Exception e) {
            Log.w(TAG, "Copy+delete rename failed for " + source, e);
            // Clean up partial copy so we don't leave duplicates
            try {
                if (out != null) out.close();
                if (in != null) in.close();
            } catch (Exception ignored) {}
            try {
                if (target.exists()) target.delete();
            } catch (Exception ignored) {}
            return false;
        }
    }

    /**
     * Resolves a MediaStore content URI to a real absolute file path when
     * possible, using DATA on older APIs and RELATIVE_PATH/DISPLAY_NAME on Q+.
     * Returns null when the path can't be resolved.
     */
    private String getRealFilePath(Uri uri) {
        try {
            if (uri == null) {
                return null;
            }
            String scheme = uri.getScheme();
            if ("file".equalsIgnoreCase(scheme)) {
                return uri.getPath();
            }
            if (!"content".equalsIgnoreCase(scheme)) {
                return null;
            }
            // DATA is still returned by MediaStore queries even on Q+ in practice
            try (Cursor c = getContentResolver().query(uri,
                    new String[] { MediaStore.MediaColumns.DATA }, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(MediaStore.MediaColumns.DATA);
                    if (idx != -1) {
                        String path = c.getString(idx);
                        if (!TextUtils.isEmpty(path)) {
                            return path;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve real file path for " + uri, e);
        }
        return null;
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
        try {
            if (audioFile == null || audioFile.getUri() == null) return;

            // Direct file-path delete first: with All files access this works
            // for SD card too, and avoids the system consent dialog entirely.
            String filePath = getRealFilePath(audioFile.getUri());
            if (filePath != null) {
                java.io.File file = new java.io.File(filePath);
                if (file.exists() && file.delete()) {
                    // Remove from MediaStore index as well
                    try {
                        getContentResolver().delete(audioFile.getUri(), null, null);
                    } catch (Exception ignored) {}
                    Toast.makeText(this, R.string.file_deleted, Toast.LENGTH_SHORT).show();
                    refreshAudioFiles();
                    return;
                }
            }

            int rows = getContentResolver().delete(audioFile.getUri(), null, null);
            if (rows > 0) {
                Toast.makeText(this, R.string.file_deleted, Toast.LENGTH_SHORT).show();
                refreshAudioFiles();
            } else {
                if (trySafDelete(audioFile.getUri())) {
                    Toast.makeText(this, R.string.file_deleted, Toast.LENGTH_SHORT).show();
                    refreshAudioFiles();
                } else {
                    Toast.makeText(this, R.string.error_deleting_file, Toast.LENGTH_SHORT).show();
                }
            }
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException deleting file", se);
            // Only fall back to the system consent flow when All files access
            // is genuinely missing; with it granted this branch is not reached.
            if (!isAllFilesAccessGranted()) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), java.util.Collections.singletonList(audioFile.getUri()));
                        pendingDeleteFile = audioFile;
                        startIntentSenderForResult(pi.getIntentSender(), REQUEST_DELETE_PERMISSION, null, 0, 0, 0);
                        return;
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Use SAF to request write permission then delete
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setDataAndType(audioFile.getUri(), "audio/*");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        pendingDeleteFile = audioFile;
                        startActivityForResult(intent, REQUEST_SAF_EDIT);
                        return;
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to request edit permissions for delete", ex);
                }
            }
            Toast.makeText(this, R.string.permission_required_for_action, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Error deleting file", e);
            Toast.makeText(this, R.string.error_deleting_file, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareAudioFile(AudioFile audioFile) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("audio/*");
        shareIntent.putExtra(Intent.EXTRA_STREAM, audioFile.getUri());
        startActivity(Intent.createChooser(shareIntent, "Share audio file"));
    }

    private boolean trySafRename(Uri uri, String newName) {
        try {
            DocumentFile doc = DocumentFile.fromSingleUri(this, uri);
            if (doc != null && doc.canWrite()) {
                return doc.renameTo(newName);
            }
        } catch (Exception e) {
            Log.e(TAG, "SAF rename failed", e);
        }
        return false;
    }

    private boolean trySafDelete(Uri uri) {
        try {
            DocumentFile doc = DocumentFile.fromSingleUri(this, uri);
            if (doc != null && doc.canWrite()) {
                return doc.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "SAF delete failed", e);
        }
        return false;
    }

    // Update the updateAudioFilesList method to use the new setupAudioAdapter method
    private void updateAudioFilesList() {
        if (folderViewEnabled) {
            showFolderView();
            return;
        }

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

            // Reuse existing adapter if possible, otherwise create a new one
            if (audioAdapter != null && audioRecyclerView.getAdapter() == audioAdapter) {
                audioAdapter.updateList(filteredAudioFiles);
            } else {
                setupAudioAdapter();
            }
            autoScrollToCurrentSongIfEnabled();
        }
    }

    /**
     * Toggles the song list between a flat list view and a folder-grouped view.
     */
    private void toggleFolderView() {
        folderViewEnabled = !folderViewEnabled;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(PREF_FOLDER_VIEW, folderViewEnabled).apply();
        if (folderViewEnabled) {
            // Entering folder view clears any open folder
            currentFolderName = null;
            // Restore the full library so folders group everything
            filteredAudioFiles.clear();
            filteredAudioFiles.addAll(allAudioFiles);
            if (searchEditText != null && !TextUtils.isEmpty(searchEditText.getText())) {
                filterAudioFiles(searchEditText.getText().toString());
            }
        } else {
            // Restore the flat list adapter
            if (audioAdapter != null) {
                audioAdapter.updateList(filteredAudioFiles);
            } else {
                setupAudioAdapter();
            }
            if (audioRecyclerView.getAdapter() != audioAdapter) {
                setupAudioAdapter();
            }
        }
        updateAudioFilesList();
        Toast.makeText(this,
                folderViewEnabled ? "Folder view" : "List view",
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Keeps the bottom-nav Library slot reflecting the current view mode:
     * shows "Folders" + folder icon in list view (tap to switch to folders),
     * "List View" + list icon while in folder view (tap to switch back).
     */
    private void updateLibraryNavItem() {
        if (bottomNavigation == null) {
            return;
        }
        MenuItem libraryItem = bottomNavigation.getMenu().findItem(R.id.nav_library);
        if (libraryItem == null) {
            return;
        }
        if (folderViewEnabled) {
            libraryItem.setIcon(R.drawable.ic_list);
            libraryItem.setTitle("List View");
        } else {
            libraryItem.setIcon(R.drawable.ic_folder);
            libraryItem.setTitle("Folders");
        }
    }

    /**
     * Replaces the song list with folder rows grouped from the current filtered songs.
     */
    private void showFolderView() {
        if (filteredAudioFiles.isEmpty()) {
            audioRecyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            if (!allAudioFiles.isEmpty() && !TextUtils.isEmpty(searchEditText.getText())) {
                emptyView.setText(R.string.no_matching_files);
            } else {
                emptyView.setText(R.string.no_audio_files);
            }
            return;
        }

        audioRecyclerView.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);

        if (folderAdapter == null) {
            folderAdapter = new FolderAdapter();
            folderAdapter.setOnFolderClickListener(folder -> openFolder(folder));
        }
        if (audioRecyclerView.getAdapter() != folderAdapter) {
            audioRecyclerView.setAdapter(folderAdapter);
        }
        folderAdapter.updateFolders(filteredAudioFiles);
    }

    /**
     * Opens a folder from folder view: scopes the list to its songs. Use the
     * system back button to return to the folders.
     */
    private void openFolder(FolderAdapter.Folder folder) {
        currentFolderName = folder.name;
        filteredAudioFiles.clear();
        filteredAudioFiles.addAll(folder.songs);
        folderViewEnabled = false;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(PREF_FOLDER_VIEW, false).apply();
        setupAudioAdapter();
        audioAdapter.updateList(filteredAudioFiles);
        updateLibraryNavItem();
    }

    /**
     * Returns from a folder's song list back to folder view (or the full list
     * when returning from the empty folder state).
     */
    private void closeCurrentFolder() {
        currentFolderName = null;
        // Restore the full library (folder view groups from filteredAudioFiles)
        filteredAudioFiles.clear();
        filteredAudioFiles.addAll(allAudioFiles);
        if (searchEditText != null && !TextUtils.isEmpty(searchEditText.getText())) {
            filterAudioFiles(searchEditText.getText().toString());
        }
        folderViewEnabled = true;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(PREF_FOLDER_VIEW, true).apply();
        showFolderView();
        updateLibraryNavItem();
    }



    // Add a new method to sort audio files
    private void sortAudioFiles() {
        // First check if we're in playlist view mode
        if (inPlaylistView && currentPlaylistSongs != null && !currentPlaylistSongs.isEmpty()) {
            try {
                // Apply sort to currentPlaylistSongs instead of allAudioFiles
                Comparator<AudioFile> comparator = null;

                switch (currentSortOrder) {
                    case SORT_BY_NAME_ASC:
                        comparator = (a1, a2) -> a1.getTitle().compareToIgnoreCase(a2.getTitle());
                        Toast.makeText(this, getString(R.string.sort_by_name_asc), Toast.LENGTH_SHORT).show();
                        break;

                    case SORT_BY_NAME_DESC:
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
                    currentPlaylistSongs.sort(comparator);

                    // Update the filtered list
                    filteredAudioFiles.clear();
                    filteredAudioFiles.addAll(currentPlaylistSongs);

                    // Apply search filter if active
                    if (searchEditText != null && !TextUtils.isEmpty(searchEditText.getText())) {
                        filterAudioFiles(searchEditText.getText().toString());
                    } else {
                        updateAudioFilesList();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sorting playlist songs", e);
                Toast.makeText(this, "Error sorting playlist files", Toast.LENGTH_SHORT).show();
            }

            return; // Exit here, we've handled the playlist sort
        }

        // Handle regular mode (not in playlist view)
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
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DISPLAY_NAME
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
                int displayNameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String title = cursor.getString(titleColumn);
                    long duration = cursor.getLong(durationColumn);
                    long size = cursor.getLong(sizeColumn);
                    long dateAdded = cursor.getLong(dateAddedColumn);  // Get dateAdded from cursor

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

                    sortedFiles.add(new AudioFile(displayName, durationFormatted, contentUri, id, size, dateAdded));
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
        if (query == null) query = "";

        // Clear the filtered list
        filteredAudioFiles.clear();

        // Only filter within the playlist songs if in playlist view
        List<AudioFile> sourceList = inPlaylistView ? currentPlaylistSongs : allAudioFiles;

        if (sourceList == null || sourceList.isEmpty()) {
            // Handle empty list case
            updateAudioFilesList();
            return;
        }

        String lowerQuery = query.toLowerCase(Locale.ROOT);

        // Filter based on the query - matches against both display name and metadata title
        for (AudioFile audioFile : sourceList) {
            if (audioFile.matchesSearch(lowerQuery)) {
                filteredAudioFiles.add(audioFile);
            }
        }

        // Update the UI
        updateAudioFilesList();
    }

    // Add the toggleMixerMode method
    private void toggleMixerMode() {
        mixerModeActive = !mixerModeActive;
        
        // Update UI elements
        mixerToggleButton.setTextColor(mixerModeActive ? 
                getResources().getColor(R.color.player_seekbar_progress) : 
                getResources().getColor(android.R.color.darker_gray));
        
        // Show/hide second file name layout
        findViewById(R.id.secondFileNameLayout).setVisibility(mixerModeActive ? View.VISIBLE : View.GONE);
        
        // Update the mixer indicator
        updateMixerIndicator();
        
        // If mixer mode is deactivated, stop second audio playback
        if (!mixerModeActive && secondMediaPlayer != null) {
            secondMediaPlayer.pause();
            secondAudioActive = false;
            // Keep the service's copy of the state in sync, so notification and
            // media-session actions never restart the deactivated second track
            if (serviceBound && audioService != null) {
                audioService.setSecondAudioActive(false);
            }
        }
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
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DISPLAY_NAME
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
                int displayNameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);

                Log.d(TAG, "Found " + cursor.getCount() + " music files in query");

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

                    // Track the largest file for debugging
                    if (size > largestSize) {
                        largestSize = size;
                        largestFileName = displayName;
                    }

                    // Log large files for debugging (files larger than 100MB)
                    if (size > 100 * 1024 * 1024) {
                        Log.d(TAG, "Large file found: " + displayName + " - Size: " +
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

                    AudioFile audioFile = new AudioFile(displayName, durationFormatted, contentUri, id, size, dateAdded);
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
            startPlaybackService("ACTION_PLAY");
            // ... existing playback code ...
        }
    }

    private void startPlaybackService(String action) {
        Intent serviceIntent = new Intent(this, AudioPlaybackService.class);
        serviceIntent.setAction(action);

        // Ensure notification permission is requested before starting a foreground service on 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Immediately promote to foreground via the bound reference so the notification
        // appears right away on Android 11 and below (before onStartCommand runs).
        if (serviceBound && audioService != null) {
            audioService.ensureForeground();
        }
    }

    private void exitAppCompletely() {
        // Do exactly what the notification's Stop button does: send ACTION_STOP
        // to the playback service. The service then stops both players, cancels
        // sleep timers, removes the notification, abandons audio focus, stops
        // itself, and broadcasts CLOSE_APP_COMMAND — whose receiver in this
        // activity calls finishAndRemoveTask(). No local shortcuts that could
        // kill the service mid-cleanup or leave the notification behind.
        try {
            Intent stopIntent = new Intent(this, AudioPlaybackService.class);
            stopIntent.setAction("ACTION_STOP");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(stopIntent);
            } else {
                startService(stopIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending stop command during app exit", e);
            // Fallback: the service isn't running — close the app directly.
            try {
                stopService(new Intent(this, AudioPlaybackService.class));
            } catch (Exception ignored) {}
            try {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
                if (secondMediaPlayer != null && secondMediaPlayer.isPlaying()) {
                    secondMediaPlayer.pause();
                }
            } catch (Exception ignored) {}
            isPlaying = false;
            closeAppNow();
            return;
        }

        // Safety net: if CLOSE_APP_COMMAND never arrives (e.g. the service was
        // already dead), close the app ourselves so Exit always works.
        handler.postDelayed(this::closeAppNow, 750);
    }

    /**
     * Final teardown used when the app closes itself (fallback path): unbind
     * from the service and remove the app from recents.
     */
    private void closeAppNow() {
        try {
            if (!isFinishing()) {
                if (serviceBound) {
                    unbindService(serviceConnection);
                    serviceBound = false;
                }
                finishAffinity();
                finishAndRemoveTask();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error while closing app", e);
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
                        setDataSourceCompat(secondMediaPlayer, secondAudioUri, 0);
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

            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error during seek operation", e);
        }
    }

    // Add this method after showBalanceDialog() to create a position sync dialog
    private void showPositionSyncDialog() {
        if (mediaPlayer == null || secondMediaPlayer == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.position_sync_title);

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_position_sync, null);
        
        SeekBar primarySeekBar = dialogView.findViewById(R.id.primaryPositionSeekBar);
        SeekBar secondarySeekBar = dialogView.findViewById(R.id.secondaryPositionSeekBar);
        TextView primaryTimeText = dialogView.findViewById(R.id.primaryPositionText);
        TextView secondaryTimeText = dialogView.findViewById(R.id.secondaryPositionText);
        
        // Set max values based on duration
        int primaryDuration = mediaPlayer.getDuration();
        int secondaryDuration = secondMediaPlayer.getDuration();
        
        primarySeekBar.setMax(primaryDuration);
        secondarySeekBar.setMax(secondaryDuration);
        
        // Set current positions
        int primaryPosition = mediaPlayer.getCurrentPosition();
        int secondaryPosition = secondMediaPlayer.getCurrentPosition();
        primarySeekBar.setProgress(primaryPosition);
        secondarySeekBar.setProgress(secondaryPosition);
        
        // Set initial time texts
        primaryTimeText.setText(formatTime(primaryPosition));
        secondaryTimeText.setText(formatTime(secondaryPosition));
        
        // Handle primary seekbar position changes - apply immediately
        primarySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (mediaPlayer != null) {
                    // Update time text
                    primaryTimeText.setText(formatTime(progress));
                    if (fromUser) {
                        // Apply change immediately while sliding
                        mediaPlayer.seekTo(progress);
                    }
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        // Handle secondary seekbar position changes - apply immediately
        secondarySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (secondMediaPlayer != null) {
                    // Update time text
                    secondaryTimeText.setText(formatTime(progress));
                    if (fromUser) {
                        // Apply change immediately while sliding
                        secondMediaPlayer.seekTo(progress);
                    }
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        builder.setView(dialogView);
        builder.setPositiveButton(R.string.ok, null);
        builder.show();
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
            } else if (itemId == R.id.settings_audio_focus) {
                showAudioFocusSettingsDialog();
                return true;
            } else if (itemId == R.id.settings_auto_slide_current) {
                showAutoSlideSettingsDialog();
                return true;
            } else if (itemId == R.id.settings_theme) {
                showThemeSettingsDialog();
                return true;
            } else if (itemId == R.id.settings_reset_options) {
                showResetOptionsSettingsDialog();
                return true;
            } else if (itemId == R.id.settings_how_to_use) {
                showHowToUseDialog();
                return true;
            }
            return false;
        });

        popup.show();
    }

    private void showResetOptionsSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_reset_options, null);
        CheckBox speedCheck = dialogView.findViewById(R.id.check_reset_speed);
        CheckBox pitchCheck = dialogView.findViewById(R.id.check_reset_pitch);
        CheckBox equalizerCheck = dialogView.findViewById(R.id.check_reset_equalizer);
        CheckBox boostCheck = dialogView.findViewById(R.id.check_reset_boost);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        speedCheck.setChecked(prefs.getBoolean(PREF_RESET_SPEED, true));
        pitchCheck.setChecked(prefs.getBoolean(PREF_RESET_PITCH, true));
        equalizerCheck.setChecked(prefs.getBoolean(PREF_RESET_EQUALIZER, true));
        boostCheck.setChecked(prefs.getBoolean(PREF_RESET_BOOST, true));

        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_options_settings)
                .setView(dialogView)
                .setPositiveButton(R.string.save, (d, which) -> {
                    prefs.edit()
                            .putBoolean(PREF_RESET_SPEED, speedCheck.isChecked())
                            .putBoolean(PREF_RESET_PITCH, pitchCheck.isChecked())
                            .putBoolean(PREF_RESET_EQUALIZER, equalizerCheck.isChecked())
                            .putBoolean(PREF_RESET_BOOST, boostCheck.isChecked())
                            .apply();
                    Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void applySavedThemeMode() {
        int savedThemeMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_THEME_MODE, THEME_MODE_BLUISH_BLACK);
        if (savedThemeMode < THEME_MODE_WHITE || savedThemeMode > THEME_MODE_CUSTOM) {
            savedThemeMode = THEME_MODE_BLUISH_BLACK;
        }
        if (savedThemeMode == THEME_MODE_BLUISH_BLACK) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }

    private void showThemeSettingsDialog() {
        final String[] appLabels = {"White", "Bluish Black", "Milky", "Custom"};
        final int[] appValues = {
                THEME_MODE_WHITE, THEME_MODE_BLUISH_BLACK, THEME_MODE_MILKY, THEME_MODE_CUSTOM
        };
        final String[] miniLabels = {"Bluish Gradient", "White", "Custom"};
        final int[] miniValues = {
                MINI_PLAYER_THEME_CURRENT, MINI_PLAYER_THEME_WHITE, MINI_PLAYER_THEME_CUSTOM
        };

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedThemeMode = prefs
                .getInt(PREF_THEME_MODE, THEME_MODE_BLUISH_BLACK);
        if (savedThemeMode < THEME_MODE_WHITE || savedThemeMode > THEME_MODE_CUSTOM) {
            savedThemeMode = THEME_MODE_BLUISH_BLACK;
        }
        int savedMiniTheme = prefs.getInt(PREF_MINI_PLAYER_THEME, MINI_PLAYER_THEME_CURRENT);
        if (savedMiniTheme < MINI_PLAYER_THEME_CURRENT || savedMiniTheme > MINI_PLAYER_THEME_CUSTOM) {
            savedMiniTheme = MINI_PLAYER_THEME_CURRENT;
        }
        final int currentThemeMode = savedThemeMode;
        final int currentMiniTheme = savedMiniTheme;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dpToPx(16);
        container.setPadding(pad, pad, pad, 0);

        TextView appHeader = new TextView(this);
        appHeader.setText("App Theme");
        appHeader.setTextSize(16f);
        container.addView(appHeader);

        RadioGroup appGroup = new RadioGroup(this);
        appGroup.setOrientation(RadioGroup.VERTICAL);
        int checkedAppId = View.NO_ID;
        for (int i = 0; i < appLabels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(appLabels[i]);
            rb.setTag(appValues[i]);
            appGroup.addView(rb);
            if (appValues[i] == savedThemeMode) {
                checkedAppId = rb.getId();
            }
        }
        if (checkedAppId != View.NO_ID) {
            appGroup.check(checkedAppId);
        }
        container.addView(appGroup);

        TextView miniHeader = new TextView(this);
        miniHeader.setText("Mini Player Theme");
        miniHeader.setTextSize(16f);
        miniHeader.setPadding(0, (int) dpToPx(12), 0, 0);
        container.addView(miniHeader);

        RadioGroup miniGroup = new RadioGroup(this);
        miniGroup.setOrientation(RadioGroup.VERTICAL);
        int checkedMiniId = View.NO_ID;
        for (int i = 0; i < miniLabels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(miniLabels[i]);
            rb.setTag(miniValues[i]);
            miniGroup.addView(rb);
            if (miniValues[i] == savedMiniTheme) {
                checkedMiniId = rb.getId();
            }
        }
        if (checkedMiniId != View.NO_ID) {
            miniGroup.check(checkedMiniId);
        }
        container.addView(miniGroup);

        TextView helper = new TextView(this);
        helper.setText("Tip: Custom lets you choose any color with live preview.");
        helper.setPadding(0, (int) dpToPx(12), 0, (int) dpToPx(4));
        helper.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        container.addView(helper);

        new AlertDialog.Builder(this)
                .setTitle("Theme")
                .setView(container)
                .setPositiveButton("Apply", (dialog, which) -> {
                    int checkedApp = appGroup.getCheckedRadioButtonId();
                    int checkedMini = miniGroup.getCheckedRadioButtonId();
                    if (checkedApp == View.NO_ID || checkedMini == View.NO_ID) {
                        return;
                    }
                    RadioButton appButton = appGroup.findViewById(checkedApp);
                    RadioButton miniButton = miniGroup.findViewById(checkedMini);
                    if (appButton == null || miniButton == null) {
                        return;
                    }
                    int newThemeMode = (int) appButton.getTag();
                    int newMiniTheme = (int) miniButton.getTag();
                    Runnable applySelection = () -> applyThemeSelection(
                            prefs,
                            newThemeMode,
                            newMiniTheme,
                            currentThemeMode,
                            currentMiniTheme
                    );

                    if (newThemeMode == THEME_MODE_CUSTOM) {
                        showRgbColorPickerDialog(
                                "Custom App Color",
                                getSavedCustomAppColor(),
                                this::applyCustomAppColorPreview,
                                selectedAppColor -> {
                            prefs.edit().putInt(PREF_CUSTOM_APP_COLOR, selectedAppColor).apply();
                            if (newMiniTheme == MINI_PLAYER_THEME_CUSTOM) {
                                showRgbColorPickerDialog(
                                        "Custom Mini Player Color",
                                        getSavedCustomMiniPlayerColor(),
                                        this::applyCustomMiniPlayerColorPreview,
                                        selectedMiniColor -> {
                                    prefs.edit().putInt(PREF_CUSTOM_MINI_PLAYER_COLOR, selectedMiniColor).apply();
                                    applySelection.run();
                                        },
                                        this::restoreThemePreview
                                );
                            } else {
                                applySelection.run();
                            }
                                },
                                this::restoreThemePreview
                        );
                    } else if (newMiniTheme == MINI_PLAYER_THEME_CUSTOM) {
                        showRgbColorPickerDialog(
                                "Custom Mini Player Color",
                                getSavedCustomMiniPlayerColor(),
                                this::applyCustomMiniPlayerColorPreview,
                                selectedMiniColor -> {
                            prefs.edit().putInt(PREF_CUSTOM_MINI_PLAYER_COLOR, selectedMiniColor).apply();
                            applySelection.run();
                                },
                                this::restoreThemePreview
                        );
                    } else {
                        applySelection.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyThemeSelection(SharedPreferences prefs, int newThemeMode, int newMiniTheme,
                                     int currentThemeMode, int currentMiniTheme) {
        prefs.edit()
                .putInt(PREF_THEME_MODE, newThemeMode)
                .putInt(PREF_MINI_PLAYER_THEME, newMiniTheme)
                .apply();

        if (newThemeMode == THEME_MODE_BLUISH_BLACK) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }

        if (newThemeMode != currentThemeMode) {
            recreate();
            return;
        }

        if (newMiniTheme != currentMiniTheme || newMiniTheme == MINI_PLAYER_THEME_CUSTOM) {
            applyMiniPlayerTheme();
        }
        applyCustomThemeOverlays();
    }

    private boolean isMilkyThemeActive() {
        int mode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_THEME_MODE, THEME_MODE_BLUISH_BLACK);
        return mode == THEME_MODE_MILKY;
    }

    private boolean isCustomAppThemeActive() {
        int mode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_THEME_MODE, THEME_MODE_BLUISH_BLACK);
        return mode == THEME_MODE_CUSTOM;
    }

    private int getSavedCustomAppColor() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_CUSTOM_APP_COLOR, DEFAULT_CUSTOM_APP_COLOR);
    }

    private int getSavedCustomMiniPlayerColor() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_CUSTOM_MINI_PLAYER_COLOR, DEFAULT_CUSTOM_MINI_PLAYER_COLOR);
    }

    private void applyCustomThemeOverlays() {
        int backgroundColor;
        int panelColor;
        int navColor;
        int searchColor;
        if (isMilkyThemeActive()) {
            backgroundColor = ContextCompat.getColor(this, R.color.milky_background);
            panelColor = ContextCompat.getColor(this, R.color.milky_surface);
            navColor = ContextCompat.getColor(this, R.color.milky_bottom_nav_bg);
            searchColor = ContextCompat.getColor(this, R.color.milky_search_bg);
        } else if (isCustomAppThemeActive()) {
            int base = getSavedCustomAppColor();
            backgroundColor = lightenColor(base, 0.80f);
            panelColor = lightenColor(base, 0.70f);
            navColor = lightenColor(base, 0.74f);
            searchColor = lightenColor(base, 0.64f);
        } else {
            return;
        }

        View contentRoot = findViewById(android.R.id.content);
        if (contentRoot instanceof ViewGroup) {
            ViewGroup rootGroup = (ViewGroup) contentRoot;
            if (rootGroup.getChildCount() > 0) {
                View rootLayout = rootGroup.getChildAt(0);
                rootLayout.setBackgroundColor(backgroundColor);
            }
        }

        View playerPanel = findViewById(R.id.playerPanel);
        if (playerPanel != null) {
            playerPanel.setBackgroundColor(panelColor);
        }

        if (bottomNavigation != null) {
            bottomNavigation.setBackgroundColor(navColor);
        }

        if (searchEditText != null && searchEditText.getParent() instanceof View) {
            View searchContainer = (View) searchEditText.getParent();
            Drawable searchBg = searchContainer.getBackground();
            if (searchBg != null) {
                searchBg = searchBg.mutate();
                searchBg.setTint(searchColor);
                searchContainer.setBackground(searchBg);
            }
        }
    }

    private void applyBaseThemeDefaults() {
        int backgroundColor = ContextCompat.getColor(this, R.color.background);
        int panelColor = ContextCompat.getColor(this, R.color.player_panel_bg);
        int navColor = ContextCompat.getColor(this, R.color.bottom_nav_bg);
        int searchColor = ContextCompat.getColor(this, R.color.search_bar_bg);

        View contentRoot = findViewById(android.R.id.content);
        if (contentRoot instanceof ViewGroup) {
            ViewGroup rootGroup = (ViewGroup) contentRoot;
            if (rootGroup.getChildCount() > 0) {
                View rootLayout = rootGroup.getChildAt(0);
                rootLayout.setBackgroundColor(backgroundColor);
            }
        }

        View playerPanel = findViewById(R.id.playerPanel);
        if (playerPanel != null) {
            playerPanel.setBackgroundColor(panelColor);
        }

        if (bottomNavigation != null) {
            bottomNavigation.setBackgroundColor(navColor);
        }

        if (searchEditText != null && searchEditText.getParent() instanceof View) {
            View searchContainer = (View) searchEditText.getParent();
            Drawable searchBg = searchContainer.getBackground();
            if (searchBg != null) {
                searchBg = searchBg.mutate();
                searchBg.setTint(searchColor);
                searchContainer.setBackground(searchBg);
            }
        }
    }

    private void applyMiniPlayerTheme() {
        if (miniPlayerBar == null) return;

        int miniTheme = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_MINI_PLAYER_THEME, MINI_PLAYER_THEME_CURRENT);
        if (miniTheme < MINI_PLAYER_THEME_CURRENT || miniTheme > MINI_PLAYER_THEME_CUSTOM) {
            miniTheme = MINI_PLAYER_THEME_CURRENT;
        }

        ImageView expandBtn = findViewById(R.id.expandPlayerBtn);
        if (miniTheme == MINI_PLAYER_THEME_WHITE) {
            miniPlayerBar.setBackgroundResource(R.drawable.bg_mini_player_white);
            miniPlayerBar.setElevation(dpToPx(6));

            if (miniPlayerTitle != null) {
                miniPlayerTitle.setTextColor(ContextCompat.getColor(this, R.color.mini_player_text_primary));
            }
            if (miniPlayerSubtitle != null) {
                miniPlayerSubtitle.setTextColor(ContextCompat.getColor(this, R.color.mini_player_text_secondary));
            }
            if (miniPlayPauseBtn != null) {
                miniPlayPauseBtn.setColorFilter(ContextCompat.getColor(this, R.color.accent_primary));
            }
            if (expandBtn != null) {
                expandBtn.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary));
            }
        } else if (miniTheme == MINI_PLAYER_THEME_CUSTOM) {
            applyMiniPlayerCustomGradient(getSavedCustomMiniPlayerColor(), expandBtn);
        } else {
            if (isMilkyThemeActive()) {
                miniPlayerBar.setBackgroundResource(R.drawable.bg_mini_player_milky_bluish_dark);
            } else {
                miniPlayerBar.setBackgroundResource(R.drawable.gradient_accent);
            }
            miniPlayerBar.setElevation(dpToPx(2));

            if (miniPlayerTitle != null) {
                miniPlayerTitle.setTextColor(ContextCompat.getColor(this, R.color.white));
            }
            if (miniPlayerSubtitle != null) {
                miniPlayerSubtitle.setTextColor(ContextCompat.getColor(this, R.color.white));
            }
            if (miniPlayPauseBtn != null) {
                miniPlayPauseBtn.setColorFilter(ContextCompat.getColor(this, R.color.white));
            }
            if (expandBtn != null) {
                expandBtn.setColorFilter(ContextCompat.getColor(this, R.color.white));
            }
        }
    }

    private interface ColorSelectionCallback {
        void onColorSelected(int color);
    }

    private interface ColorPreviewCallback {
        void onColorPreview(int color);
    }

    private void showRgbColorPickerDialog(String title, int initialColor,
                                          ColorPreviewCallback previewCallback,
                                          ColorSelectionCallback applyCallback,
                                          Runnable cancelCallback) {
        final float[] hsv = new float[3];
        Color.colorToHSV(initialColor, hsv);
        final int[] alpha = {Color.alpha(initialColor)};
        final boolean[] programmaticUpdate = {false};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) dpToPx(16);
        root.setPadding(padding, padding, padding, 0);

        View satValPanel = new View(this);
        LinearLayout.LayoutParams svParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) dpToPx(180)
        );
        svParams.bottomMargin = (int) dpToPx(12);
        satValPanel.setLayoutParams(svParams);
        satValPanel.setOnTouchListener((v, event) -> {
            if (v.getWidth() <= 0 || v.getHeight() <= 0) return false;
            float x = Math.max(0f, Math.min(event.getX(), v.getWidth()));
            float y = Math.max(0f, Math.min(event.getY(), v.getHeight()));
            hsv[1] = x / (float) v.getWidth();
            hsv[2] = 1f - (y / (float) v.getHeight());
            refreshPickerUi(satValPanel, hsv, alpha[0], null, null, null, null, null, programmaticUpdate, previewCallback);
            return true;
        });
        root.addView(satValPanel);

        SeekBar hueSeek = new SeekBar(this);
        hueSeek.setMax(360);
        hueSeek.setProgress(Math.round(hsv[0]));
        root.addView(hueSeek);

        SeekBar alphaSeek = new SeekBar(this);
        alphaSeek.setMax(100);
        alphaSeek.setProgress(Math.round(alpha[0] * 100f / 255f));
        root.addView(alphaSeek);

        LinearLayout fieldsRow = new LinearLayout(this);
        fieldsRow.setOrientation(LinearLayout.HORIZONTAL);
        fieldsRow.setWeightSum(5f);
        fieldsRow.setPadding(0, (int) dpToPx(8), 0, 0);
        EditText rField = createColorField("R", fieldsRow);
        EditText gField = createColorField("G", fieldsRow);
        EditText bField = createColorField("B", fieldsRow);
        EditText aField = createColorField("A%", fieldsRow);
        EditText hexField = createColorField("Hex", fieldsRow);
        root.addView(fieldsRow);

        TextView hexInfo = new TextView(this);
        hexInfo.setPadding(0, (int) dpToPx(8), 0, (int) dpToPx(8));
        root.addView(hexInfo);

        LinearLayout swatchRow = new LinearLayout(this);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        swatchRow.setPadding(0, 0, 0, (int) dpToPx(8));
        root.addView(swatchRow);

        int[] swatches = {
                0xFFFF5252, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7,
                0xFF3F51B5, 0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4,
                0xFF4CAF50, 0xFF8BC34A, 0xFFCDDC39, 0xFFFFEB3B,
                0xFFFF9800, 0xFFFF5722
        };
        for (int swatchColor : swatches) {
            View swatch = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) dpToPx(20), (int) dpToPx(20));
            lp.rightMargin = (int) dpToPx(6);
            swatch.setLayoutParams(lp);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(swatchColor);
            swatch.setBackground(circle);
            swatch.setOnClickListener(v -> {
                Color.colorToHSV(swatchColor, hsv);
                alpha[0] = 255;
                hueSeek.setProgress(Math.round(hsv[0]));
                alphaSeek.setProgress(100);
                refreshPickerUi(satValPanel, hsv, alpha[0], rField, gField, bField, aField, hexField, programmaticUpdate, previewCallback);
                hexInfo.setText(String.format(Locale.US, "Selected: #%08X", buildColor(alpha[0], hsv)));
            });
            swatchRow.addView(swatch);
        }

        hueSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                hsv[0] = progress;
                refreshPickerUi(satValPanel, hsv, alpha[0], rField, gField, bField, aField, hexField, programmaticUpdate, previewCallback);
                hexInfo.setText(String.format(Locale.US, "Selected: #%08X", buildColor(alpha[0], hsv)));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        alphaSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                alpha[0] = Math.round(progress * 255f / 100f);
                refreshPickerUi(satValPanel, hsv, alpha[0], rField, gField, bField, aField, hexField, programmaticUpdate, previewCallback);
                hexInfo.setText(String.format(Locale.US, "Selected: #%08X", buildColor(alpha[0], hsv)));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        TextWatcher fieldWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (programmaticUpdate[0]) return;
                Integer r = parseIntOrNull(rField.getText().toString());
                Integer g = parseIntOrNull(gField.getText().toString());
                Integer b = parseIntOrNull(bField.getText().toString());
                Integer a = parseIntOrNull(aField.getText().toString());
                if (r == null || g == null || b == null || a == null) return;
                int rr = clampColorValue(r);
                int gg = clampColorValue(g);
                int bb = clampColorValue(b);
                int aaPercent = Math.max(0, Math.min(100, a));
                alpha[0] = Math.round(aaPercent * 255f / 100f);
                Color.colorToHSV(Color.rgb(rr, gg, bb), hsv);
                hueSeek.setProgress(Math.round(hsv[0]));
                alphaSeek.setProgress(aaPercent);
                refreshPickerUi(satValPanel, hsv, alpha[0], rField, gField, bField, aField, hexField, programmaticUpdate, previewCallback);
                hexInfo.setText(String.format(Locale.US, "Selected: #%08X", buildColor(alpha[0], hsv)));
            }
        };
        rField.addTextChangedListener(fieldWatcher);
        gField.addTextChangedListener(fieldWatcher);
        bField.addTextChangedListener(fieldWatcher);
        aField.addTextChangedListener(fieldWatcher);

        hexField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (programmaticUpdate[0]) return;
                Integer parsed = parseHexColorOrNull(s.toString());
                if (parsed == null) return;
                alpha[0] = Color.alpha(parsed);
                Color.colorToHSV(parsed, hsv);
                hueSeek.setProgress(Math.round(hsv[0]));
                alphaSeek.setProgress(Math.round(alpha[0] * 100f / 255f));
                refreshPickerUi(satValPanel, hsv, alpha[0], rField, gField, bField, aField, hexField, programmaticUpdate, previewCallback);
                hexInfo.setText(String.format(Locale.US, "Selected: #%08X", buildColor(alpha[0], hsv)));
            }
        });

        programmaticUpdate[0] = true;
        refreshPickerUi(satValPanel, hsv, alpha[0], rField, gField, bField, aField, hexField, programmaticUpdate, previewCallback);
        hexInfo.setText(String.format(Locale.US, "Selected: #%08X", buildColor(alpha[0], hsv)));
        programmaticUpdate[0] = false;

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(root)
                .setPositiveButton("Apply", (dialog, which) ->
                        applyCallback.onColorSelected(buildColor(alpha[0], hsv)))
                .setNegativeButton("Cancel", (dialog, which) -> {
                    if (cancelCallback != null) {
                        cancelCallback.run();
                    }
                })
                .show();
    }

    private EditText createColorField(String hint, LinearLayout parent) {
        EditText field = new EditText(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = (int) dpToPx(6);
        field.setLayoutParams(params);
        field.setHint(hint);
        if ("Hex".equals(hint)) {
            field.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else {
            field.setInputType(InputType.TYPE_CLASS_NUMBER);
        }
        parent.addView(field);
        return field;
    }

    private void refreshPickerUi(View satValPanel, float[] hsv, int alpha,
                                 EditText rField, EditText gField, EditText bField, EditText aField, EditText hexField,
                                 boolean[] programmaticUpdate,
                                 ColorPreviewCallback previewCallback) {
        int color = buildColor(alpha, hsv);
        updateSatValPanel(satValPanel, hsv[0]);
        if (programmaticUpdate != null) {
            programmaticUpdate[0] = true;
        }
        if (rField != null) rField.setText(String.valueOf(Color.red(color)));
        if (gField != null) gField.setText(String.valueOf(Color.green(color)));
        if (bField != null) bField.setText(String.valueOf(Color.blue(color)));
        if (aField != null) aField.setText(String.valueOf(Math.round(alpha * 100f / 255f)));
        if (hexField != null) hexField.setText(String.format(Locale.US, "%08X", color));
        if (programmaticUpdate != null) {
            programmaticUpdate[0] = false;
        }
        if (previewCallback != null) {
            previewCallback.onColorPreview(color);
        }
    }

    private void updateSatValPanel(View panel, float hue) {
        int hueColor = Color.HSVToColor(new float[]{hue, 1f, 1f});
        GradientDrawable satGradient = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.WHITE, hueColor}
        );
        GradientDrawable valGradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00FFFFFF, 0xFF000000}
        );
        panel.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[]{satGradient, valGradient}));
    }

    private int buildColor(int alpha, float[] hsv) {
        return Color.HSVToColor(alpha, hsv);
    }

    private Integer parseIntOrNull(String value) {
        if (TextUtils.isEmpty(value)) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer parseHexColorOrNull(String value) {
        if (TextUtils.isEmpty(value)) return null;
        String clean = value.trim().replace("#", "");
        if (clean.length() != 6 && clean.length() != 8) return null;
        try {
            long parsed = Long.parseLong(clean, 16);
            if (clean.length() == 6) {
                return (int) (0xFF000000L | parsed);
            }
            return (int) parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void applyCustomAppColorPreview(int selectedColor) {
        int backgroundColor = lightenColor(selectedColor, 0.80f);
        int panelColor = lightenColor(selectedColor, 0.70f);
        int navColor = lightenColor(selectedColor, 0.74f);
        int searchColor = lightenColor(selectedColor, 0.64f);

        View contentRoot = findViewById(android.R.id.content);
        if (contentRoot instanceof ViewGroup) {
            ViewGroup rootGroup = (ViewGroup) contentRoot;
            if (rootGroup.getChildCount() > 0) {
                View rootLayout = rootGroup.getChildAt(0);
                rootLayout.setBackgroundColor(backgroundColor);
            }
        }

        View playerPanel = findViewById(R.id.playerPanel);
        if (playerPanel != null) {
            playerPanel.setBackgroundColor(panelColor);
        }

        if (bottomNavigation != null) {
            bottomNavigation.setBackgroundColor(navColor);
        }

        if (searchEditText != null && searchEditText.getParent() instanceof View) {
            View searchContainer = (View) searchEditText.getParent();
            Drawable searchBg = searchContainer.getBackground();
            if (searchBg != null) {
                searchBg = searchBg.mutate();
                searchBg.setTint(searchColor);
                searchContainer.setBackground(searchBg);
            }
        }
    }

    private void applyCustomMiniPlayerColorPreview(int selectedColor) {
        ImageView expandBtn = findViewById(R.id.expandPlayerBtn);
        applyMiniPlayerCustomGradient(selectedColor, expandBtn);
    }

    private void applyMiniPlayerCustomGradient(int startColor, ImageView expandBtn) {
        if (miniPlayerBar == null) return;

        int end = darkenColor(startColor, 0.22f);
        GradientDrawable gradientDrawable = new GradientDrawable(
                GradientDrawable.Orientation.BL_TR,
                new int[]{startColor, end}
        );
        gradientDrawable.setCornerRadius(dpToPx(8));
        miniPlayerBar.setBackground(gradientDrawable);
        miniPlayerBar.setElevation(dpToPx(2));

        int contentColor = isColorDark(startColor) ? Color.WHITE : Color.BLACK;
        if (miniPlayerTitle != null) {
            miniPlayerTitle.setTextColor(contentColor);
        }
        if (miniPlayerSubtitle != null) {
            miniPlayerSubtitle.setTextColor(contentColor);
        }
        if (miniPlayPauseBtn != null) {
            miniPlayPauseBtn.setColorFilter(contentColor);
        }
        if (expandBtn != null) {
            expandBtn.setColorFilter(contentColor);
        }
    }

    private void restoreThemePreview() {
        applyBaseThemeDefaults();
        applyCustomThemeOverlays();
        applyMiniPlayerTheme();
    }

    private int lightenColor(int color, float ratio) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        r += Math.round((255 - r) * ratio);
        g += Math.round((255 - g) * ratio);
        b += Math.round((255 - b) * ratio);
        return Color.rgb(clampColorValue(r), clampColorValue(g), clampColorValue(b));
    }

    private int darkenColor(int color, float ratio) {
        int r = Math.round(Color.red(color) * (1f - ratio));
        int g = Math.round(Color.green(color) * (1f - ratio));
        int b = Math.round(Color.blue(color) * (1f - ratio));
        return Color.rgb(clampColorValue(r), clampColorValue(g), clampColorValue(b));
    }

    private int clampColorValue(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private boolean isColorDark(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255d;
        return luminance < 0.55d;
    }

    private float dpToPx(int dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private boolean isAllowAudioMixEnabled() {
        try {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(PREF_ALLOW_AUDIO_MIX, false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showAudioFocusSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_audio_focus, null);
        Switch allowMixSwitch = dialogView.findViewById(R.id.switch_allow_mix);
        allowMixSwitch.setChecked(isAllowAudioMixEnabled());

        new AlertDialog.Builder(this)
                .setTitle("Audio focus")
                .setView(dialogView)
                .setPositiveButton("Save", (d, which) -> {
                    boolean enabled = allowMixSwitch.isChecked();
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREF_ALLOW_AUDIO_MIX, enabled)
                            .apply();
                    applyAudioMixPreferenceImmediately(enabled);
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyAudioMixPreferenceImmediately(boolean enabled) {
        try {
            if (enabled) {
                // Mix mode should not hold exclusive focus.
                abandonAudioFocus();
            } else if ((mediaPlayer != null && mediaPlayer.isPlaying())
                    || (secondMediaPlayer != null && secondAudioActive && secondMediaPlayer.isPlaying())) {
                // Non-mix mode should re-acquire focus while currently playing.
                requestAudioFocus();
            }

            if (serviceBound && audioService != null) {
                audioService.onAudioMixPreferenceChanged(enabled);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply audio mix preference change", e);
        }
    }

    private boolean isAutoSlideToCurrentSongEnabled() {
        try {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(PREF_AUTO_SLIDE_TO_CURRENT, false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showAutoSlideSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_auto_slide_current, null);
        Switch autoSlideSwitch = dialogView.findViewById(R.id.switch_auto_slide_current);
        autoSlideSwitch.setChecked(isAutoSlideToCurrentSongEnabled());

        new AlertDialog.Builder(this)
                .setTitle("List behavior")
                .setView(dialogView)
                .setPositiveButton("Save", (d, which) -> {
                    boolean enabled = autoSlideSwitch.isChecked();
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREF_AUTO_SLIDE_TO_CURRENT, enabled)
                            .apply();
                    if (enabled) {
                        autoScrollToCurrentSongIfEnabled();
                    }
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showHowToUseDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_how_to_use, null);
        new AlertDialog.Builder(this)
                .setTitle("How to use AudPlayer")
                .setView(dialogView)
                .setPositiveButton("OK", null)
                .show();
    }

    private void updateSeekSkipButtonLabels() {
        int backwardSeconds = Math.max(1, seekBackwardMs / 1000);
        int forwardSeconds = Math.max(1, seekForwardMs / 1000);
        applySeekSkipLabel(seekBackwardSecondsText, seekBackwardButton, backwardSeconds, true);
        applySeekSkipLabel(seekForwardSecondsText, seekForwardButton, forwardSeconds, false);
    }

    private void applySeekSkipLabel(TextView label, View button, int seconds, boolean backward) {
        if (label != null) {
            label.setText(formatSeekSecondsLabel(seconds));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, seconds >= 100 ? 7f : 8f);
        }
        if (button != null) {
            String direction = backward ? getString(R.string.seek_backward) : getString(R.string.seek_forward);
            button.setContentDescription(direction + " " + seconds + " seconds");
        }
    }

    private String formatSeekSecondsLabel(int seconds) {
        return seconds >= 100 ? String.valueOf(seconds) : seconds + "s";
    }

    private void autoScrollToCurrentSongIfEnabled() {
        if (!isAutoSlideToCurrentSongEnabled()) return;
        if (audioRecyclerView == null || audioAdapter == null || filteredAudioFiles == null || filteredAudioFiles.isEmpty()) {
            return;
        }

        int currentIndex = getCurrentSongIndexInList(filteredAudioFiles);
        if (currentIndex < 0) return;

        RecyclerView.LayoutManager lm = audioRecyclerView.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            ((LinearLayoutManager) lm).scrollToPositionWithOffset(currentIndex, 120);
        } else {
            audioRecyclerView.scrollToPosition(currentIndex);
        }
    }

    /**
     * Scrolls the song list to the currently playing song. Leaves folder view
     * (or an opened folder) first so the flat song list is visible.
     */
    private void slideToCurrentlyPlayingSong() {
        if (selectedAudioUri == null) {
            Toast.makeText(this, "No song is playing", Toast.LENGTH_SHORT).show();
            return;
        }

        // Leave folder view / an opened folder so the song list is showing
        if (folderViewEnabled || currentFolderName != null) {
            folderViewEnabled = false;
            currentFolderName = null;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(PREF_FOLDER_VIEW, false).apply();
            filteredAudioFiles.clear();
            filteredAudioFiles.addAll(allAudioFiles);
            if (searchEditText != null && !TextUtils.isEmpty(searchEditText.getText())) {
                filterAudioFiles(searchEditText.getText().toString());
            }
            if (audioAdapter == null || audioRecyclerView.getAdapter() != audioAdapter) {
                setupAudioAdapter();
            }
            audioAdapter.updateList(filteredAudioFiles);
            updateAudioFilesList();
            updateLibraryNavItem();
        }

        if (audioRecyclerView == null || audioAdapter == null || filteredAudioFiles.isEmpty()) {
            Toast.makeText(this, "No songs in the list", Toast.LENGTH_SHORT).show();
            return;
        }

        int currentIndex = getCurrentSongIndexInList(filteredAudioFiles);
        if (currentIndex < 0) {
            Toast.makeText(this, "Current song is not in this list", Toast.LENGTH_SHORT).show();
            return;
        }

        RecyclerView.LayoutManager lm = audioRecyclerView.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            ((LinearLayoutManager) lm).scrollToPositionWithOffset(currentIndex, 120);
        } else {
            audioRecyclerView.scrollToPosition(currentIndex);
        }
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
                        updateSeekSkipButtonLabels();

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
                // Sync mini player play/pause button if the main one is updated
                if (button == playPauseButton && miniPlayPauseBtn != null) {
                    miniPlayPauseBtn.setImageResource(resId);
                }
                // Keep the song list's equalizer indicator animating only while
                // actually playing (pause icon shown == playback active).
                if (button == playPauseButton && audioAdapter != null) {
                    audioAdapter.setPlaybackActive(resId == R.drawable.ic_pause_improved);
                }
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
            // ACTION_GET_CONTENT so third-party file managers can supply audio too
            Intent getContent = new Intent(Intent.ACTION_GET_CONTENT);
            getContent.addCategory(Intent.CATEGORY_OPENABLE);
            getContent.setType("audio/*");

            Intent openDocument = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            openDocument.addCategory(Intent.CATEGORY_OPENABLE);
            openDocument.setType("audio/*");

            Intent chooser = Intent.createChooser(getContent, "Select second audio file");
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { openDocument });

            try {
                // Use the second launcher explicitly for the second file
                secondAudioPickerLauncher.launch(chooser);
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
        TextView firstVolumeLabel = dialogView.findViewById(R.id.firstAudioVolumeText);
        TextView secondVolumeLabel = dialogView.findViewById(R.id.secondAudioVolumeText);

        // Set initial values based on current volume
        firstAudioSeekBar.setProgress((int)(firstAudioVolume * 100));
        secondAudioSeekBar.setProgress((int)(secondAudioVolume * 100));
        if (firstVolumeLabel != null) firstVolumeLabel.setText(firstAudioSeekBar.getProgress() + "%");
        if (secondVolumeLabel != null) secondVolumeLabel.setText(secondAudioSeekBar.getProgress() + "%");

        // Show each track's name under its label ("..." when it doesn't fit)
        TextView firstTitleText = dialogView.findViewById(R.id.firstAudioTitleText);
        TextView secondTitleText = dialogView.findViewById(R.id.secondAudioTitleText);
        if (firstTitleText != null) {
            firstTitleText.setText(selectedAudioUri != null
                    ? getFileNameFromUri(selectedAudioUri) : "Primary track");
        }
        if (secondTitleText != null) {
            secondTitleText.setText(secondAudioUri != null
                    ? getFileNameFromUri(secondAudioUri) : "Secondary track");
        }

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
                if (firstVolumeLabel != null) firstVolumeLabel.setText(progress + "%");
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
                if (secondVolumeLabel != null) secondVolumeLabel.setText(progress + "%");
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
        // Release second media player
        if (secondMediaPlayer != null) {
            secondMediaPlayer.release();
            secondMediaPlayer = null;
        }
        
        // Reset secondary file name text
        TextView secondFileNameText = findViewById(R.id.secondFileNameText);
        secondFileNameText.setText("");
        
        // Clear URI and reset state
        secondAudioUri = null;
        secondAudioActive = false;

        // Drop the service's stale reference so it can never act on a released player
        if (serviceBound && audioService != null) {
            audioService.setSecondMediaPlayer(null);
            audioService.setSecondAudioActive(false);
        }

        // Update mixer indicator
        updateMixerIndicator();
        
        Toast.makeText(this, "Second_audio_cleared", Toast.LENGTH_SHORT).show();
    }

    private void showSavedMixerDialog() {
        List<SavedMixerPreset> presets = loadSavedMixerPresets();

        View content = getLayoutInflater().inflate(R.layout.dialog_saved_mixer, null);
        LinearLayout listContainer = content.findViewById(R.id.savedMixerList);
        View emptyState = content.findViewById(R.id.savedMixerEmptyState);
        TextView countText = content.findViewById(R.id.savedMixerCountText);

        // Fullscreen window with a blurred screenshot behind the panel
        Dialog dialog = showBlurredPanelDialog(content, Gravity.CENTER, 1.0f);

        // Reopen the dialog so the list reflects a save/delete
        Runnable refreshDialog = () -> {
            dialog.dismiss();
            showSavedMixerDialog();
        };

        if (presets.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            countText.setText("0 saved");
        } else {
            emptyState.setVisibility(View.GONE);
            countText.setText(presets.size() + " saved");
            for (int i = 0; i < presets.size(); i++) {
                SavedMixerPreset preset = presets.get(i);
                listContainer.addView(createSavedMixerItemView(preset, i + 1, dialog, refreshDialog));
            }
        }

        content.findViewById(R.id.savedMixerSaveButton).setOnClickListener(v ->
                promptSaveCurrentMix(refreshDialog));
    }

    private View createSavedMixerItemView(SavedMixerPreset preset, int index, Dialog hostDialog,
                                          Runnable refreshDialog) {
        View item = getLayoutInflater().inflate(R.layout.item_saved_mixer, null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = (int) (10 * getResources().getDisplayMetrics().density);
        item.setLayoutParams(params);

        String firstSongName = "Unknown primary track";
        String secondSongName = "Unknown secondary track";
        try {
            if (!TextUtils.isEmpty(preset.primaryUri)) {
                firstSongName = getFileNameFromUri(Uri.parse(preset.primaryUri));
            }
            if (!TextUtils.isEmpty(preset.secondaryUri)) {
                secondSongName = getFileNameFromUri(Uri.parse(preset.secondaryUri));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse saved mixer track uri", e);
        }

        TextView nameView = item.findViewById(R.id.savedMixerName);
        TextView track1View = item.findViewById(R.id.savedMixerTrack1);
        TextView track2View = item.findViewById(R.id.savedMixerTrack2);
        TextView metaView = item.findViewById(R.id.savedMixerMeta);

        nameView.setText(preset.name != null && !preset.name.trim().isEmpty()
                ? preset.name : "Mixer " + index);
        track1View.setText(firstSongName);
        track2View.setText(secondSongName);

        // Balance + speed summary line, e.g. "50% · 50%  •  1.00x"
        String volumes = Math.round(preset.firstVolume * 100) + "% · "
                + Math.round(preset.secondVolume * 100) + "%";
        String speed = preset.useIndividualSpeeds
                ? String.format(Locale.US, "%.2fx / %.2fx", preset.primarySpeed, preset.secondarySpeed)
                : String.format(Locale.US, "%.2fx", preset.globalSpeed);
        metaView.setText(volumes + "  •  " + speed);

        // Tap anywhere on the card to play the mix, then close the list.
        // The delete button consumes its own tap, so it never triggers playback.
        item.setOnClickListener(v -> {
            applySavedMixerPreset(preset);
            hostDialog.dismiss();
        });
        item.findViewById(R.id.savedMixerDelete).setOnClickListener(v ->
                confirmDeleteMixerPreset(preset, refreshDialog));

        return item;
    }

    /** Asks for confirmation, removes the preset, then refreshes the Saved Mixer list. */
    private void confirmDeleteMixerPreset(SavedMixerPreset preset, Runnable refreshDialog) {
        String name = preset.name != null && !preset.name.trim().isEmpty()
                ? preset.name : "this mixer";
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Delete mixer?")
                .setMessage("Delete \"" + name + "\"? This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    deleteSavedMixerPreset(preset);
                    if (refreshDialog != null) {
                        refreshDialog.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Removes a preset from storage, matching on name and both track URIs. */
    private void deleteSavedMixerPreset(SavedMixerPreset preset) {
        List<SavedMixerPreset> presets = loadSavedMixerPresets();
        presets.removeIf(p -> TextUtils.equals(p.name, preset.name)
                && TextUtils.equals(p.primaryUri, preset.primaryUri)
                && TextUtils.equals(p.secondaryUri, preset.secondaryUri));
        persistSavedMixerPresets(presets);
        Toast.makeText(this, "Mixer deleted", Toast.LENGTH_SHORT).show();
    }

    private void promptSaveCurrentMix() {
        promptSaveCurrentMix(null);
    }

    private void promptSaveCurrentMix(Runnable onSaved) {
        if (selectedAudioUri == null || secondAudioUri == null) {
            Toast.makeText(this, "Select primary and second audio first", Toast.LENGTH_SHORT).show();
            return;
        }

        String primaryName = getFileNameFromUri(selectedAudioUri);
        String secondaryName = getFileNameFromUri(secondAudioUri);
        String defaultName = "Mix: " + primaryName + " + " + secondaryName;

        View content = getLayoutInflater().inflate(R.layout.dialog_save_mixer, null);
        com.google.android.material.textfield.TextInputEditText input =
                content.findViewById(R.id.saveMixerNameInput);
        input.setText(defaultName);
        // Select based on the field's actual length — the text may be shorter
        // than the default name if anything filtered it
        input.setSelection(input.getText().length());

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Save Mixer")
                .setView(content)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = input.getText() != null ? input.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(name)) {
                        name = defaultName;
                    }
                    saveCurrentMixerPreset(name);
                    if (onSaved != null) {
                        onSaved.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveCurrentMixerPreset(String name) {
        if (selectedAudioUri == null || secondAudioUri == null) {
            Toast.makeText(this, "Select both tracks before saving", Toast.LENGTH_SHORT).show();
            return;
        }

        SavedMixerPreset preset = new SavedMixerPreset();
        preset.name = name;
        preset.primaryUri = selectedAudioUri.toString();
        preset.secondaryUri = secondAudioUri.toString();
        preset.firstVolume = firstAudioVolume;
        preset.secondVolume = secondAudioVolume;
        preset.useIndividualSpeeds = useIndividualPlaybackSpeeds;
        preset.globalSpeed = currentPlaybackSpeed;
        preset.primarySpeed = primaryPlaybackSpeed;
        preset.secondarySpeed = secondaryPlaybackSpeed;

        List<SavedMixerPreset> presets = loadSavedMixerPresets();
        presets.add(0, preset);
        if (presets.size() > 20) {
            presets = presets.subList(0, 20);
        }
        persistSavedMixerPresets(presets);
        Toast.makeText(this, "Mixer saved", Toast.LENGTH_SHORT).show();
    }

    private void applySavedMixerPreset(SavedMixerPreset preset) {
        if (preset == null || TextUtils.isEmpty(preset.primaryUri) || TextUtils.isEmpty(preset.secondaryUri)) {
            Toast.makeText(this, "Saved mixer is invalid", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            selectedAudioUri = Uri.parse(preset.primaryUri);
            secondAudioUri = Uri.parse(preset.secondaryUri);
        } catch (Exception e) {
            Toast.makeText(this, "Unable to load saved mixer", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!mixerModeActive) {
            toggleMixerMode();
        }

        currentPlaybackSpeed = preset.globalSpeed;
        useIndividualPlaybackSpeeds = preset.useIndividualSpeeds;
        primaryPlaybackSpeed = preset.primarySpeed;
        secondaryPlaybackSpeed = preset.secondarySpeed;

        fileNameText.setText(getFileNameFromUri(selectedAudioUri));
        TextView secondFileNameText = findViewById(R.id.secondFileNameText);
        if (secondFileNameText != null) {
            secondFileNameText.setText(getFileNameFromUri(secondAudioUri));
        }
        updateMiniPlayer();

        shouldAutoPlay = true;
        prepareMediaPlayer();
        prepareSecondMediaPlayer();
        secondAudioActive = true;

        applyAudioVolumes(preset.firstVolume, preset.secondVolume);

        if (useIndividualPlaybackSpeeds) {
            applyPrimarySpeed(primaryPlaybackSpeed);
            applySecondarySpeed(secondaryPlaybackSpeed);
        } else {
            setPlaybackSpeed(currentPlaybackSpeed);
        }

        updateMixerIndicator();
        
    }

    private List<SavedMixerPreset> loadSavedMixerPresets() {
        List<SavedMixerPreset> presets = new ArrayList<>();
        SharedPreferences prefs = getSharedPreferences("audio_player_prefs", MODE_PRIVATE);
        String raw = prefs.getString(PREFS_MIXER_PRESETS_KEY, "[]");

        // Primary format: JSON array of presets.
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                appendPresetFromJsonObject(presets, array.optJSONObject(i));
            }
            return presets;
        } catch (JSONException ignored) {
            // Legacy/invalid format handling below.
        }

        // Backward compatibility: old single-object format.
        try {
            JSONObject obj = new JSONObject(raw);
            appendPresetFromJsonObject(presets, obj);
            // Migrate to the new array format so next saves keep multiple entries.
            persistSavedMixerPresets(presets);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse saved mixer presets", e);
        }
        return presets;
    }

    private void appendPresetFromJsonObject(List<SavedMixerPreset> presets, JSONObject obj) {
        if (obj == null) {
            return;
        }

        SavedMixerPreset preset = new SavedMixerPreset();
        preset.name = obj.optString("name", "Saved Mix");
        preset.primaryUri = obj.optString("primaryUri", "");
        preset.secondaryUri = obj.optString("secondaryUri", "");
        preset.firstVolume = (float) obj.optDouble("firstVolume", 1.0);
        preset.secondVolume = (float) obj.optDouble("secondVolume", 1.0);
        preset.useIndividualSpeeds = obj.optBoolean("useIndividualSpeeds", false);
        preset.globalSpeed = (float) obj.optDouble("globalSpeed", 1.0);
        preset.primarySpeed = (float) obj.optDouble("primarySpeed", 1.0);
        preset.secondarySpeed = (float) obj.optDouble("secondarySpeed", 1.0);

        if (!TextUtils.isEmpty(preset.primaryUri) && !TextUtils.isEmpty(preset.secondaryUri)) {
            presets.add(preset);
        }
    }

    private void persistSavedMixerPresets(List<SavedMixerPreset> presets) {
        JSONArray array = new JSONArray();
        for (SavedMixerPreset preset : presets) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("name", preset.name);
                obj.put("primaryUri", preset.primaryUri);
                obj.put("secondaryUri", preset.secondaryUri);
                obj.put("firstVolume", preset.firstVolume);
                obj.put("secondVolume", preset.secondVolume);
                obj.put("useIndividualSpeeds", preset.useIndividualSpeeds);
                obj.put("globalSpeed", preset.globalSpeed);
                obj.put("primarySpeed", preset.primarySpeed);
                obj.put("secondarySpeed", preset.secondarySpeed);
                array.put(obj);
            } catch (JSONException e) {
                Log.e(TAG, "Failed to save mixer preset item", e);
            }
        }

        SharedPreferences prefs = getSharedPreferences("audio_player_prefs", MODE_PRIVATE);
        prefs.edit().putString(PREFS_MIXER_PRESETS_KEY, array.toString()).apply();
    }

    private void setPointA() {
        if (mediaPlayer == null) {
            Toast.makeText(this, "Please select an audio file first", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int currentPosition = mediaPlayer.getCurrentPosition();
            pointA = currentPosition;

            // If a previously set B is now invalid, clear B and disable repeat.
            if (pointB != -1 && pointA >= pointB) {
                pointB = -1;
                abRepeatActive = false;
            }
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
            if (pointA == -1) {
                Toast.makeText(this, "Set Point A first", Toast.LENGTH_SHORT).show();
                return;
            }

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

            if (pointA != -1 && pointB != -1) {
                Toast.makeText(this, "Points set. Tap Enable to start A-B repeat", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting point B", e);
            Toast.makeText(this, "Error setting point B", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Fine-tunes Point A or Point B by deltaMs (fractional steps supported, e.g. 100ms),
     * applying the same validity rules as setting the point manually.
     */
    private void nudgeABPoint(boolean isPointA, int deltaMs) {
        if (mediaPlayer == null) {
            Toast.makeText(this, "Please select an audio file first", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int duration = mediaPlayer.getDuration();
            int maxPoint = Math.max(0, duration - 1);

            if (isPointA) {
                if (pointA == -1) {
                    Toast.makeText(this, "Set Point A first", Toast.LENGTH_SHORT).show();
                    return;
                }
                int newA = Math.min(Math.max(pointA + deltaMs, 0), maxPoint);
                // If A would reach/pass an existing B, clear B and disable repeat (same as setPointA).
                if (pointB != -1 && newA >= pointB) {
                    pointA = newA;
                    pointB = -1;
                    abRepeatActive = false;
                    updateABRepeatIndicator();
                    Toast.makeText(this, "Point A: " + formatTimePrecise(pointA) + " (Point B cleared)", Toast.LENGTH_SHORT).show();
                    return;
                }
                pointA = newA;
                updateABRepeatIndicator();
                Toast.makeText(this, "Point A: " + formatTimePrecise(pointA), Toast.LENGTH_SHORT).show();
            } else {
                if (pointB == -1) {
                    Toast.makeText(this, "Set Point B first", Toast.LENGTH_SHORT).show();
                    return;
                }
                int newB = pointB + deltaMs;
                if (pointA != -1 && newB <= pointA) {
                    Toast.makeText(this, "Point B must be after Point A", Toast.LENGTH_SHORT).show();
                    return;
                }
                pointB = Math.min(newB, maxPoint);
                updateABRepeatIndicator();
                Toast.makeText(this, "Point B: " + formatTimePrecise(pointB), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error nudging A-B point", e);
        }
    }

    /** Like formatTime but keeps one decimal (e.g. "1:23.4") so fractional fine-tuning steps are visible. */
    private String formatTimePrecise(int milliseconds) {
        return formatTime(milliseconds) + "." + ((Math.abs(milliseconds) % 1000) / 100);
    }

    /**
     * Sets Point A or Point B to an exact position typed by the user,
     * applying the same validity rules as the Set buttons.
     */
    private void setABPointManually(boolean isPointA, int positionMs) {
        if (mediaPlayer == null) {
            Toast.makeText(this, "Please select an audio file first", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int duration = mediaPlayer.getDuration();
            int maxPoint = Math.max(0, duration - 1);
            int newMs = Math.min(Math.max(positionMs, 0), maxPoint);

            if (isPointA) {
                // If A would reach/pass an existing B, clear B and disable repeat (same as setPointA).
                if (pointB != -1 && newMs >= pointB) {
                    pointA = newMs;
                    pointB = -1;
                    abRepeatActive = false;
                    updateABRepeatIndicator();
                    Toast.makeText(this, "Point A set: " + formatTimePrecise(pointA) + " (Point B cleared)", Toast.LENGTH_SHORT).show();
                    return;
                }
                pointA = newMs;
                updateABRepeatIndicator();
                Toast.makeText(this, "Point A set: " + formatTimePrecise(pointA), Toast.LENGTH_SHORT).show();
            } else {
                if (pointA == -1) {
                    Toast.makeText(this, "Set Point A first", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (newMs <= pointA) {
                    Toast.makeText(this, "Point B must be after Point A", Toast.LENGTH_SHORT).show();
                    return;
                }
                pointB = newMs;
                updateABRepeatIndicator();
                Toast.makeText(this, "Point B set: " + formatTimePrecise(pointB), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting A-B point manually", e);
        }
    }

    /**
     * Live point adjustment from the range slider: silent (no toasts on every
     * drag tick) and clamped so Point A always stays before Point B.
     */
    private void moveABPoint(boolean isPointA, int positionMs) {
        if (mediaPlayer == null) {
            return;
        }

        try {
            int duration = mediaPlayer.getDuration();
            int maxPoint = Math.max(0, duration - 1);
            int newMs = Math.min(Math.max(positionMs, 0), maxPoint);

            if (isPointA) {
                if (pointB != -1 && newMs >= pointB) {
                    newMs = Math.max(0, pointB - 200);
                }
                pointA = newMs;
            } else {
                if (pointA != -1 && newMs <= pointA) {
                    newMs = Math.min(maxPoint, pointA + 200);
                }
                pointB = newMs;
            }
            updateABRepeatIndicator();
        } catch (Exception e) {
            Log.e(TAG, "Error moving A-B point", e);
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

            // Start loop from A immediately for predictable behavior.
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.seekTo(pointA);
                    if (!mediaPlayer.isPlaying() && isPlaying) {
                        mediaPlayer.start();
                    }
                    updateSeekBar();
                } catch (Exception e) {
                    Log.e(TAG, "Error enabling A-B repeat", e);
                }
            }
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

    /**
     * Show dialog to add current song to a playlist
     */
    private void showAddToPlaylistDialog() {
        if (selectedAudioUri == null) {
            Toast.makeText(this, "No song selected", Toast.LENGTH_SHORT).show();
            return;
        }
        AudioFile currentAudio = new AudioFile(
                fileNameText.getText().toString(),
                totalTimeText.getText().toString(),
                selectedAudioUri,
                0,
                0,
                System.currentTimeMillis()
        );
        showAddToPlaylistDialog(currentAudio);
    }

    private void showAddToPlaylistDialog(AudioFile targetAudioFile) {
        if (targetAudioFile == null || targetAudioFile.getUri() == null) {
            Toast.makeText(this, "No song selected", Toast.LENGTH_SHORT).show();
            return;
        }

        // Get all playlists
        List<Playlist> playlists = playlistDbHelper.getAllPlaylists();

        if (playlists.isEmpty()) {
            // No playlists exist, show create playlist dialog
            showCreatePlaylistDialog();
            return;
        }

        // Create list of playlist names
        String[] playlistNames = new String[playlists.size() + 1]; // +1 for "Create new playlist" option
        for (int i = 0; i < playlists.size(); i++) {
            playlistNames[i] = playlists.get(i).getName();
        }
        playlistNames[playlists.size()] = "Create new playlist";

        // Show playlist selection dialog
        new AlertDialog.Builder(this)
                .setTitle("Add to Playlist")
                .setItems(playlistNames, (dialog, which) -> {
                    if (which == playlists.size()) {
                        // Create new playlist option selected
                        showCreatePlaylistDialog(targetAudioFile);
                    } else {
                        // Add to selected playlist
                        Playlist selectedPlaylist = playlists.get(which);
                        addSongToPlaylist(selectedPlaylist, targetAudioFile);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Show dialog to create a new playlist and add the current song
     */
    private void showCreatePlaylistDialog() {
        if (selectedAudioUri == null) {
            Toast.makeText(this, "No song selected", Toast.LENGTH_SHORT).show();
            return;
        }
        AudioFile currentAudio = new AudioFile(
                fileNameText.getText().toString(),
                totalTimeText.getText().toString(),
                selectedAudioUri,
                0,
                0,
                System.currentTimeMillis()
        );
        showCreatePlaylistDialog(currentAudio);
    }

    private void showCreatePlaylistDialog(AudioFile targetAudioFile) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Create Playlist");

        // Set up the input
        final EditText input = new EditText(this);
        input.setHint("Playlist Name");
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton("Create", (dialog, which) -> {
            String playlistName = input.getText().toString().trim();
            if (!TextUtils.isEmpty(playlistName)) {
                Playlist newPlaylist = new Playlist(playlistName);
                playlistDbHelper.createPlaylist(newPlaylist);

                // If a song is selected, add it to the new playlist
                if (targetAudioFile != null && targetAudioFile.getUri() != null) {
                    addSongToPlaylist(newPlaylist, targetAudioFile);
                }

                Toast.makeText(this, "Playlist created", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Playlist name cannot be empty", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    /**
     * Add the currently playing song to a playlist
     */
    private void addCurrentSongToPlaylist(Playlist playlist) {
        if (selectedAudioUri == null) {
            return;
        }

        // Get audio file info from the currently playing song
        String title = fileNameText.getText().toString();
        String duration = totalTimeText.getText().toString();
        long fileSize = 0;
        long dateAdded = System.currentTimeMillis();

        // Create audio file object
        AudioFile audioFile = new AudioFile(title, duration, selectedAudioUri, 0, fileSize, dateAdded);
        addSongToPlaylist(playlist, audioFile);
    }

    private void addSongToPlaylist(Playlist playlist, AudioFile audioFile) {
        if (audioFile == null || audioFile.getUri() == null) {
            return;
        }

        // Add to playlist
        long result = playlistDbHelper.addSongToPlaylist(playlist.getId(), audioFile);

        if (result != -1) {
            Toast.makeText(this, "Added to " + playlist.getName(), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Song already in playlist", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /**
     * Handle incoming intents, including playlist playback
     */
    private void handleIntent(Intent intent) {
        if (intent != null) {
            // Check if we should play a playlist
            currentPlaylistId = intent.getStringExtra("PLAYLIST_ID");

            if (currentPlaylistId != null) {
                // Load the playlist songs
                currentPlaylistSongs = playlistDbHelper.getPlaylistSongs(currentPlaylistId);

                // Get the URI to play from the intent data or the first song if PLAY_ENTIRE_PLAYLIST is true
                Uri uriToPlay = intent.getData();

                if (uriToPlay != null) {
                    // Find the index of this song in the playlist
                    for (int i = 0; i < currentPlaylistSongs.size(); i++) {
                        if (currentPlaylistSongs.get(i).getUri().equals(uriToPlay)) {
                            currentPlaylistIndex = i;
                            break;
                        }
                    }
                } else if (intent.getBooleanExtra("PLAY_ENTIRE_PLAYLIST", false) && !currentPlaylistSongs.isEmpty()) {
                    // Start playing from the first song
                    uriToPlay = currentPlaylistSongs.get(0).getUri();
                    currentPlaylistIndex = 0;
                }

                // Optionally open just the playlist songs list without auto-playing.
                if (intent.getBooleanExtra("SHOW_PLAYLIST_ONLY", false)) {
                    showPlaylistSongs();
                    return;
                }

                if (uriToPlay != null) {
                    selectedAudioUri = uriToPlay;
                    shouldAutoPlay = true;
                    prepareMediaPlayer();

                    // Show only songs from this playlist
                    showPlaylistSongs();
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        clearBottomNavSelectionSoon();

        // If we're returning from another activity and in playlist view,
        // reload the playlist in case songs were added/removed
        if (inPlaylistView && currentPlaylistId != null) {
            currentPlaylistSongs = playlistDbHelper.getPlaylistSongs(currentPlaylistId);
            showPlaylistSongs();
        }

        // Resume the vibrato modulation loop if it was saved enabled but the
        // tick chain is not running (e.g. activity recreated while service played).
        if (voiceVibratoDepth > 0) {
            startVoiceVibratoTicks();
        }
    }

    @Override
    public void onBackPressed() {
        // Leaving a folder's song list returns to folder view instead of exiting
        if (currentFolderName != null) {
            closeCurrentFolder();
            return;
        }
        super.onBackPressed();
    }

    /**
     * Toggle to view all songs from playlist view
     */
    private void toggleToAllSongsView() {
        inPlaylistView = false;
        playlistInfoContainer.setVisibility(View.GONE);

        // Switch back to showing all songs
        filteredAudioFiles.clear();
        filteredAudioFiles.addAll(allAudioFiles);

        // Apply current sort order
        sortAudioFiles();

        // Apply any active search filter
        if (searchEditText != null && !TextUtils.isEmpty(searchEditText.getText())) {
            filterAudioFiles(searchEditText.getText().toString());
        } else {
            updateAudioFilesList();
        }

        Toast.makeText(this, R.string.showing_all_songs, Toast.LENGTH_SHORT).show();
    }

    /**
     * Show only songs from the current playlist
     */
    private void showPlaylistSongs() {
        if (currentPlaylistId == null || currentPlaylistSongs == null || currentPlaylistSongs.isEmpty()) {
            return;
        }

        inPlaylistView = true;

        // Get playlist details
        currentPlaylist = playlistDbHelper.getPlaylist(currentPlaylistId);

        if (currentPlaylist != null) {
            // Show playlist info
            playlistNameText.setText(currentPlaylist.getName());
            playlistInfoContainer.setVisibility(View.VISIBLE);
            showAllSongsButton.setText(R.string.all_songs);

            // Update the list to show only playlist songs
            filteredAudioFiles.clear();
            filteredAudioFiles.addAll(currentPlaylistSongs);
            updateAudioFilesList();

            // Show a toast with the playlist name
            Toast.makeText(this,
                    getString(R.string.showing_playlist, currentPlaylist.getName()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void updateMixerIndicator() {
        if (!mixerModeActive || secondMediaPlayer == null) {
            mixerIndicator.setVisibility(View.GONE);
            return;
        }
        
        mixerIndicator.setVisibility(View.VISIBLE);
        String mixerText = String.format("Vol 1: %.0f%% | Vol 2: %.0f%%", 
                firstAudioVolume * 100, secondAudioVolume * 100);
        mixerIndicator.setText(mixerText);
    }

    // Phone state receiver for handling incoming calls
    private class PhoneStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_PHONE_STATE_CHANGED)) {
                String state = intent.getStringExtra(EXTRA_STATE);
                Log.d(TAG, "Phone state changed: " + state);
                
                if (state != null) {
                    if (state.equals(EXTRA_STATE_RINGING)) {
                        // Incoming call - pause music
                        handleIncomingCall();
                    } else if (state.equals(EXTRA_STATE_OFFHOOK)) {
                        // Call answered - ensure music is paused
                        handleCallAnswered();
                    } else if (state.equals(EXTRA_STATE_IDLE)) {
                        // Call ended - resume music if it was playing before
                        handleCallEnded();
                    }
                }
            }
        }
    }
    
    private void handleIncomingCall() {
        Log.d(TAG, "Incoming call detected - pausing music");
        if (isPlaying && mediaPlayer != null) {
            wasPlayingBeforeCall = true;
            pausePlayback();
        } else {
            wasPlayingBeforeCall = false;
        }
    }
    
    private void handleCallAnswered() {
        Log.d(TAG, "Call answered - ensuring music is paused");
        if (isPlaying && mediaPlayer != null) {
            wasPlayingBeforeCall = true;
            pausePlayback();
        }
    }
    
    private void handleCallEnded() {
        Log.d(TAG, "Call ended - resuming music if it was playing before");
        if (wasPlayingBeforeCall && mediaPlayer != null) {
            resumePlayback();
        }
        wasPlayingBeforeCall = false;
    }
    
    private void pausePlayback() {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                isPlaying = false;
                safeSetImageResource(playPauseButton, R.drawable.ic_play_improved);
                Log.d(TAG, "Music paused due to call");
            }
            
            // Also pause second player if active
            if (secondMediaPlayer != null && secondAudioActive && secondMediaPlayer.isPlaying()) {
                secondMediaPlayer.pause();
                Log.d(TAG, "Second audio paused due to call");
            }

            // Abandon audio focus when paused due to external interruption
            abandonAudioFocus();
        } catch (Exception e) {
            Log.e(TAG, "Error pausing playback during call", e);
        }
    }
    
    private void resumePlayback() {
        try {
            if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                if (!requestAudioFocus()) {
                    Log.w(TAG, "Audio focus not granted, not resuming");
                    return;
                }
                mediaPlayer.start();
                isPlaying = true;
                safeSetImageResource(playPauseButton, R.drawable.ic_pause_improved);
                Log.d(TAG, "Music resumed after call");
            }
            
            // Also resume second player if it was active (skip if it finished —
            // restarting would loop it back to 0:00)
            startSecondTrackIfNotFinished();
        } catch (Exception e) {
            Log.e(TAG, "Error resuming playback after call", e);
        }
    }

    private boolean requestAudioFocus() {
        try {
            if (isAllowAudioMixEnabled()) return true;
            if (audioManager == null) return true; // fallback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setOnAudioFocusChangeListener(audioFocusChangeListener)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .build();
                }
                int res = audioManager.requestAudioFocus(audioFocusRequest);
                return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            } else {
                int res = audioManager.requestAudioFocus(audioFocusChangeListener,
                        AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
        } catch (Exception e) {
            Log.e(TAG, "requestAudioFocus error", e);
            return true;
        }
    }

    private void abandonAudioFocus() {
        try {
            if (audioManager == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest != null) audioManager.abandonAudioFocusRequest(audioFocusRequest);
            } else {
                audioManager.abandonAudioFocus(audioFocusChangeListener);
            }
        } catch (Exception e) {
            Log.e(TAG, "abandonAudioFocus error", e);
        }
    }
}