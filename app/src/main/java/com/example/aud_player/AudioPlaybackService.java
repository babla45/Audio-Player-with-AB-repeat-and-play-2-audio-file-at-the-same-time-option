package com.example.aud_player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v4.media.MediaMetadataCompat;

import java.io.IOException;

public class AudioPlaybackService extends Service {
    private static final String TAG = "AudioPlaybackService";
    private static final String CHANNEL_ID = "audio_playback_channel";
    private static final int NOTIFICATION_ID = 1;
    
    // Actions for controlling playback from notification
    public static final String ACTION_PLAY = "com.example.aud_player.ACTION_PLAY";
    public static final String ACTION_PAUSE = "com.example.aud_player.ACTION_PAUSE";
    public static final String ACTION_STOP = "com.example.aud_player.ACTION_STOP";
    public static final String ACTION_NEXT = "com.example.aud_player.ACTION_NEXT";
    public static final String ACTION_PREVIOUS = "com.example.aud_player.ACTION_PREVIOUS";

    private MediaPlayer mediaPlayer;
    private MediaPlayer secondMediaPlayer;
    private MediaSessionCompat mediaSession;
    private final IBinder binder = new LocalBinder();
    private Uri currentAudioUri;
    private Uri secondAudioUri;
    private boolean isPlaying = false;
    private String currentAudioTitle = "Unknown";
    private Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean isPausedByFocusLoss = false;
    private float firstAudioVolume = 1.0f;
    private float secondAudioVolume = 1.0f;
    private int pointA = -1;
    private int pointB = -1;
    private boolean abRepeatActive = false;
    private Runnable abRepeatRunnable;

    // Interface for callbacks to activity
    public interface OnPlaybackChangeListener {
        void onPlaybackStateChanged(boolean isPlaying);
        void onProgressChanged(int progress, int duration);
        void onCompletion();
    }

    private OnPlaybackChangeListener playbackChangeListener;

    public void setOnPlaybackChangeListener(OnPlaybackChangeListener listener) {
        this.playbackChangeListener = listener;
    }

    // Binder class for activity to bind to service
    public class LocalBinder extends Binder {
        AudioPlaybackService getService() {
            return AudioPlaybackService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        
        // Create notification channel for Android O and above
        createNotificationChannel();
        
        // Initialize AudioManager
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        
        // Initialize MediaSession
        mediaSession = new MediaSessionCompat(this, "AudioPlaybackService");
        mediaSession.setActive(true);
        
        // Set media session callbacks
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onStop() {
                stop();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) pos);
            }
        });
        
        // Register broadcast receiver for notification actions
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_PLAY);
        intentFilter.addAction(ACTION_PAUSE);
        intentFilter.addAction(ACTION_STOP);
        intentFilter.addAction(ACTION_NEXT);
        intentFilter.addAction(ACTION_PREVIOUS);
        registerReceiver(notificationReceiver, intentFilter);
    }

    private BroadcastReceiver notificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case ACTION_PLAY:
                        play();
                        break;
                    case ACTION_PAUSE:
                        pause();
                        break;
                    case ACTION_STOP:
                        stop();
                        break;
                    case ACTION_NEXT:
                        // Implement if you have playlist functionality
                        break;
                    case ACTION_PREVIOUS:
                        // Implement if you have playlist functionality
                        break;
                }
            }
        }
    };

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Audio Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Channel for audio playback controls");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start the service as a foreground service
        startForeground(NOTIFICATION_ID, createNotification());
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (mediaPlayer != null) {
            stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        
        if (secondMediaPlayer != null) {
            secondMediaPlayer.release();
            secondMediaPlayer = null;
        }
        
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        
        try {
            unregisterReceiver(notificationReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver not registered
            Log.e(TAG, "Receiver not registered", e);
        }
        
        super.onDestroy();
    }

    private Notification createNotification() {
        // Create main activity intent
        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openAppIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        // Create action intents
        PendingIntent playPauseIntent;
        int playPauseIcon;
        
        if (isPlaying) {
            Intent pauseIntent = new Intent(ACTION_PAUSE);
            playPauseIntent = PendingIntent.getBroadcast(this, 1, pauseIntent, 
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            playPauseIcon = R.drawable.ic_pause;
        } else {
            Intent playIntent = new Intent(ACTION_PLAY);
            playPauseIntent = PendingIntent.getBroadcast(this, 2, playIntent, 
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            playPauseIcon = R.drawable.ic_play;
        }
        
        Intent stopIntent = new Intent(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(this, 3, stopIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        // Build the notification
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle(currentAudioTitle)
                .setContentText("Now Playing")
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(playPauseIcon, isPlaying ? "Pause" : "Play", playPauseIntent)
                .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1))
                .setOngoing(isPlaying);
        
        return builder.build();
    }

    private void updateNotification() {
        NotificationManager notificationManager = 
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, createNotification());
        }
    }

    public void setAudioFile(Uri uri, String title) {
        currentAudioUri = uri;
        currentAudioTitle = title != null ? title : "Unknown";
        
        prepareMediaPlayer();
        updateNotification();
    }

    public void setSecondAudioFile(Uri uri) {
        secondAudioUri = uri;
        prepareSecondMediaPlayer();
    }

    private void prepareMediaPlayer() {
        if (currentAudioUri == null) return;
        
        try {
            if (mediaPlayer != null) {
                mediaPlayer.reset();
            } else {
                mediaPlayer = new MediaPlayer();
                // Use wake lock to allow playback when screen is off
                mediaPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
                
                mediaPlayer.setOnCompletionListener(mp -> {
                    isPlaying = false;
                    updatePlaybackState();
                    updateNotification();
                    if (playbackChangeListener != null) {
                        playbackChangeListener.onCompletion();
                    }
                });
                
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                    return false;
                });
            }
            
            mediaPlayer.setDataSource(this, currentAudioUri);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> {
                updateMediaSessionMetadata();
                if (playbackChangeListener != null) {
                    playbackChangeListener.onProgressChanged(0, mediaPlayer.getDuration());
                }
            });
            
        } catch (IOException e) {
            Log.e(TAG, "Error preparing media player", e);
            Toast.makeText(this, "Error preparing audio: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void prepareSecondMediaPlayer() {
        if (secondAudioUri == null) return;
        
        try {
            if (secondMediaPlayer != null) {
                secondMediaPlayer.reset();
            } else {
                secondMediaPlayer = new MediaPlayer();
                secondMediaPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            }
            
            secondMediaPlayer.setDataSource(this, secondAudioUri);
            secondMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            
            secondMediaPlayer.prepareAsync();
            secondMediaPlayer.setOnPreparedListener(mp -> {
                secondMediaPlayer.setVolume(secondAudioVolume, secondAudioVolume);
            });
            
        } catch (IOException e) {
            Log.e(TAG, "Error preparing second media player", e);
            Toast.makeText(this, "Error preparing second audio: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void play() {
        if (mediaPlayer == null || currentAudioUri == null) return;
        
        if (!requestAudioFocus()) {
            return;
        }
        
        try {
            if (!mediaPlayer.isPlaying()) {
                mediaPlayer.start();
                isPlaying = true;
                
                // Start secondary audio if available
                if (secondMediaPlayer != null && secondAudioUri != null && !secondMediaPlayer.isPlaying()) {
                    secondMediaPlayer.start();
                }
                
                // Start progress updates
                startProgressUpdates();
                
                // Start A-B repeat if active
                if (abRepeatActive && pointA >= 0 && pointB > pointA) {
                    startABRepeatLoop();
                }
                
                // Update UI
                if (playbackChangeListener != null) {
                    playbackChangeListener.onPlaybackStateChanged(true);
                }
                
                // Update media session state
                updatePlaybackState();
                
                // Update notification
                updateNotification();
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error playing media", e);
            Toast.makeText(this, "Error playing audio", Toast.LENGTH_SHORT).show();
        }
    }

    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
            
            // Pause secondary audio if playing
            if (secondMediaPlayer != null && secondMediaPlayer.isPlaying()) {
                secondMediaPlayer.pause();
            }
            
            // Remove AB repeat callbacks
            if (abRepeatRunnable != null) {
                handler.removeCallbacks(abRepeatRunnable);
            }
            
            // Update UI
            if (playbackChangeListener != null) {
                playbackChangeListener.onPlaybackStateChanged(false);
            }
            
            // Update media session state
            updatePlaybackState();
            
            // Update notification
            updateNotification();
        }
    }

    public void stop() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.reset();
            isPlaying = false;
            
            // Stop secondary audio
            if (secondMediaPlayer != null) {
                if (secondMediaPlayer.isPlaying()) {
                    secondMediaPlayer.stop();
                }
                secondMediaPlayer.reset();
            }
            
            // Remove AB repeat callbacks
            if (abRepeatRunnable != null) {
                handler.removeCallbacks(abRepeatRunnable);
            }
            
            // Update UI
            if (playbackChangeListener != null) {
                playbackChangeListener.onPlaybackStateChanged(false);
                playbackChangeListener.onProgressChanged(0, 0);
            }
            
            // Update media session state
            updatePlaybackState();
            
            // Update notification
            updateNotification();
            
            // Release audio focus
            abandonAudioFocus();
            
            // Stop foreground service
            stopForeground(true);
            stopSelf();
        }
    }

    public void seekTo(int position) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(position);
            
            // If secondary audio is playing, seek it to same position
            if (secondMediaPlayer != null && secondMediaPlayer.isPlaying()) {
                secondMediaPlayer.seekTo(position);
            }
            
            updatePlaybackState();
        }
    }

    private void startProgressUpdates() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && isPlaying) {
                    try {
                        int currentPosition = mediaPlayer.getCurrentPosition();
                        int duration = mediaPlayer.getDuration();
                        
                        if (playbackChangeListener != null) {
                            playbackChangeListener.onProgressChanged(currentPosition, duration);
                        }
                        
                        handler.postDelayed(this, 1000);
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Error getting player position", e);
                    }
                }
            }
        });
    }

    private void updatePlaybackState() {
        if (mediaSession == null || mediaPlayer == null) return;
        
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder();
        
        int state;
        long position = 0;
        
        if (isPlaying) {
            state = PlaybackStateCompat.STATE_PLAYING;
            if (mediaPlayer != null) {
                try {
                    position = mediaPlayer.getCurrentPosition();
                } catch (IllegalStateException e) {
                    position = 0;
                }
            }
        } else {
            state = PlaybackStateCompat.STATE_PAUSED;
            if (mediaPlayer != null) {
                try {
                    position = mediaPlayer.getCurrentPosition();
                } catch (IllegalStateException e) {
                    position = 0;
                }
            }
        }
        
        stateBuilder.setState(state, position, 1.0f);
        stateBuilder.setActions(PlaybackStateCompat.ACTION_PLAY | 
                              PlaybackStateCompat.ACTION_PAUSE |
                              PlaybackStateCompat.ACTION_SEEK_TO |
                              PlaybackStateCompat.ACTION_STOP);
        
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void updateMediaSessionMetadata() {
        if (mediaSession == null || mediaPlayer == null) return;
        
        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();
        
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentAudioTitle);
        
        try {
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mediaPlayer.getDuration());
        } catch (IllegalStateException e) {
            Log.e(TAG, "Error getting duration for metadata", e);
        }
        
        mediaSession.setMetadata(metadataBuilder.build());
    }

    private boolean requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .build();
            
            int result = audioManager.requestAudioFocus(audioFocusRequest);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            int result = audioManager.requestAudioFocus(audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
    }

    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    // Permanent loss of audio focus
                    isPausedByFocusLoss = true;
                    pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // Temporary loss of audio focus
                    isPausedByFocusLoss = isPlaying;
                    pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // Lower the volume
                    if (mediaPlayer != null) {
                        mediaPlayer.setVolume(0.3f * firstAudioVolume, 0.3f * firstAudioVolume);
                    }
                    if (secondMediaPlayer != null) {
                        secondMediaPlayer.setVolume(0.3f * secondAudioVolume, 0.3f * secondAudioVolume);
                    }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    // Resume playback
                    if (isPausedByFocusLoss) {
                        play();
                        isPausedByFocusLoss = false;
                    }
                    // Restore volume
                    if (mediaPlayer != null) {
                        mediaPlayer.setVolume(firstAudioVolume, firstAudioVolume);
                    }
                    if (secondMediaPlayer != null) {
                        secondMediaPlayer.setVolume(secondAudioVolume, secondAudioVolume);
                    }
                    break;
            }
        }
    };

    public void setVolume(float volume) {
        if (mediaPlayer != null) {
            firstAudioVolume = volume;
            mediaPlayer.setVolume(volume, volume);
        }
    }

    public void setSecondVolume(float volume) {
        if (secondMediaPlayer != null) {
            secondAudioVolume = volume;
            secondMediaPlayer.setVolume(volume, volume);
        }
    }

    public void setABPoints(int pointA, int pointB) {
        this.pointA = pointA;
        this.pointB = pointB;
        this.abRepeatActive = (pointA >= 0 && pointB > pointA);
        
        if (isPlaying && abRepeatActive) {
            startABRepeatLoop();
        }
    }

    public void clearABPoints() {
        this.pointA = -1;
        this.pointB = -1;
        this.abRepeatActive = false;
        
        if (abRepeatRunnable != null) {
            handler.removeCallbacks(abRepeatRunnable);
            abRepeatRunnable = null;
        }
    }

    private void startABRepeatLoop() {
        if (abRepeatRunnable != null) {
            handler.removeCallbacks(abRepeatRunnable);
        }
        
        abRepeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && isPlaying && abRepeatActive) {
                    int currentPosition = mediaPlayer.getCurrentPosition();
                    
                    if (currentPosition >= pointB) {
                        mediaPlayer.seekTo(pointA);
                        if (secondMediaPlayer != null && secondMediaPlayer.isPlaying()) {
                            secondMediaPlayer.seekTo(pointA);
                        }
                    }
                    
                    handler.postDelayed(this, 50); // Check frequently
                }
            }
        };
        
        handler.post(abRepeatRunnable);
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public int getCurrentPosition() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        return 0;
    }

    public int getDuration() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getDuration();
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        return 0;
    }
} 