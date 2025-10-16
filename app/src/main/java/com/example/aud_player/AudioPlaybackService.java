package com.example.aud_player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import android.telephony.TelephonyManager;

public class AudioPlaybackService extends Service {
    private static final String TAG = "AudioPlaybackService";
    private static final String CHANNEL_ID = "AudioPlaybackChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_PLAY = "ACTION_PLAY";
    private static final String ACTION_PAUSE = "ACTION_PAUSE";
    private static final String ACTION_STOP = "ACTION_STOP";

    private MediaPlayer mediaPlayer;
    private MediaPlayer secondMediaPlayer;
    private final IBinder binder = new LocalBinder();
    private String currentTitle = "Audio Player";
    private boolean isPlaying = false;
    private boolean secondAudioActive = false;
    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private long timerEndTime = 0;
    private static final long TIMER_UPDATE_INTERVAL = 1000; // Update every second
    // Audio focus management for background playback
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean pausedByAudioFocusLoss = false;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = focusChange -> {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                try {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        pausedByAudioFocusLoss = true;
                        mediaPlayer.pause();
                    }
                    if (secondMediaPlayer != null && secondAudioActive && secondMediaPlayer.isPlaying()) {
                        secondMediaPlayer.pause();
                    }
                    isPlaying = false;
                    updateNotification();
                } catch (Exception e) {
                    Log.e(TAG, "Error handling audio focus loss", e);
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                try {
                    if (pausedByAudioFocusLoss) {
                        TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                        int callState = telephonyManager != null ? telephonyManager.getCallState() : TelephonyManager.CALL_STATE_IDLE;
                        if (callState == TelephonyManager.CALL_STATE_IDLE) {
                            if (mediaPlayer != null && !mediaPlayer.isPlaying()) mediaPlayer.start();
                            if (secondMediaPlayer != null && secondAudioActive && !secondMediaPlayer.isPlaying()) secondMediaPlayer.start();
                            isPlaying = true;
                            pausedByAudioFocusLoss = false;
                            updateNotification();
                        } else {
                            Log.d(TAG, "Call is ongoing, won't resume playback on focus gain");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling audio focus gain", e);
                }
                break;
        }
    };


    public static final int TIMER_ACTION_PAUSE = 0;
    public static final int TIMER_ACTION_CLOSE_APP = 1;

    private int timerAction = TIMER_ACTION_PAUSE;

    public class LocalBinder extends Binder {
        AudioPlaybackService getService() {
            return AudioPlaybackService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY:
                    if (mediaPlayer != null) {
                        if (!isPlaying) {
                            try {
                                if (requestAudioFocus()) {
                                    mediaPlayer.start();
                                }
                                if (secondMediaPlayer != null && secondAudioActive) {
                                    if (requestAudioFocus()) {
                                        secondMediaPlayer.start();
                                    }
                                }
                                isPlaying = true;
                                updateNotification();
                            } catch (IllegalStateException e) {
                                Log.e(TAG, "Error starting playback in service", e);
                            }
                        }
                    }
                    break;
                case ACTION_PAUSE:
                    if (mediaPlayer != null) {
                        if (isPlaying) {
                            try {
                                mediaPlayer.pause();
                                if (secondMediaPlayer != null && secondAudioActive) {
                                    secondMediaPlayer.pause();
                                }
                                isPlaying = false;
                                abandonAudioFocus();
                                updateNotification();
                            } catch (IllegalStateException e) {
                                Log.e(TAG, "Error pausing playback in service", e);
                            }
                        }
                    }
                    break;
                case ACTION_STOP:
                    try {
                        // First, immediately change state to not playing
                        isPlaying = false;
                        
                        // Send broadcast to close the app immediately
                        Intent closeAppIntent = new Intent("CLOSE_APP_COMMAND");
                        sendBroadcast(closeAppIntent);
                        
                        // Stop the service from foreground state
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_REMOVE);
                        } else {
                            stopForeground(true);
                        }
                        
                        // Cancel any pending timers
                        if (timerRunnable != null) {
                            timerHandler.removeCallbacks(timerRunnable);
                        }
                        
                        // Stop the players immediately on the main thread to avoid delays
                        if (mediaPlayer != null) {
                            if (mediaPlayer.isPlaying()) {
                                mediaPlayer.stop();
                            }
                        }
                        
                        if (secondMediaPlayer != null && secondAudioActive) {
                            if (secondMediaPlayer.isPlaying()) {
                                secondMediaPlayer.stop();
                            }
                        }
                        
                        // Stop the service directly - don't use handler delay
                        stopSelf();
                        abandonAudioFocus();
                        
                        // Return immediately - don't show notification again
                        return START_NOT_STICKY;
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping playback in service", e);
                    }
                    break;
            }
        }
        
        if (isPlaying) {
            startForeground(NOTIFICATION_ID, createNotification());
        }
        
        // Change this to NOT_STICKY for the stop case
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Used for audio playback controls");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    public void setMediaPlayers(MediaPlayer main, MediaPlayer second, String title) {
        this.mediaPlayer = main;
        this.secondMediaPlayer = second;
        this.currentTitle = title != null ? title : "Audio Player";
        this.isPlaying = main != null && main.isPlaying();
        this.secondAudioActive = second != null;
        updateNotification();
    }

    private void updateNotification() {
        NotificationManager notificationManager = 
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, createNotification());
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Create play/pause intent
        Intent playPauseIntent = new Intent(this, AudioPlaybackService.class);
        playPauseIntent.setAction(isPlaying ? ACTION_PAUSE : ACTION_PLAY);
        PendingIntent playPausePendingIntent = PendingIntent.getService(
            this, 1, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Create stop intent
        Intent stopIntent = new Intent(this, AudioPlaybackService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(isPlaying ? "Playing" : "Paused")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            // Add play/pause action
            .addAction(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play, 
                      isPlaying ? "Pause" : "Play", 
                      playPausePendingIntent)
            // Add stop action
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent);

        return builder.build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
            }
            if (secondMediaPlayer != null && secondAudioActive) {
                if (secondMediaPlayer.isPlaying()) {
                    secondMediaPlayer.stop();
                }
                secondMediaPlayer.release();
                secondMediaPlayer = null;
            }
            secondAudioActive = false;
            isPlaying = false;
            abandonAudioFocus();
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy", e);
        }
    }

    private boolean requestAudioFocus() {
        try {
            if (audioManager == null) return true;
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

    public void setTimer(long endTimeMillis, int action) {
        timerEndTime = endTimeMillis;
        timerAction = action;
        
        // Cancel any existing timer
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
        
        // Create new timer runnable
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                long timeLeft = timerEndTime - currentTime;
                
                if (timeLeft <= 0) {
                    // Timer finished
                    timerEndTime = 0;
                    
                    // Handle different timer actions
                    if (timerAction == TIMER_ACTION_CLOSE_APP) {
                        // For close app action, we need to fully stop the service
                        try {
                            // Stop playback
                            if (mediaPlayer != null) {
                                if (mediaPlayer.isPlaying()) {
                                    mediaPlayer.stop();
                                }
                            }
                            if (secondMediaPlayer != null && secondMediaPlayer.isPlaying()) {
                                secondMediaPlayer.stop();
                            }
                            
                            // Send broadcast to update UI with action
                            Intent finishedIntent = new Intent("TIMER_FINISHED");
                            finishedIntent.putExtra("TIMER_ACTION", timerAction);
                            sendBroadcast(finishedIntent);
                            
                            // Stop service and remove notification
                            stopForeground(true);
                            stopSelf();
                        } catch (Exception e) {
                            Log.e(TAG, "Error shutting down service on timer end", e);
                        }
                    } else {
                        // For pause action (default)
                        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                            mediaPlayer.pause();
                        }
                        if (secondMediaPlayer != null && secondMediaPlayer.isPlaying()) {
                            secondMediaPlayer.pause();
                        }
                        
                        // Send broadcast to update UI
                        Intent finishedIntent = new Intent("TIMER_FINISHED");
                        finishedIntent.putExtra("TIMER_ACTION", timerAction);
                        sendBroadcast(finishedIntent);
                        
                        // Update notification to show paused state
                        isPlaying = false;
                        updateNotification();
                    }
                } else {
                    // Timer still running, send update broadcast
                    Intent updateIntent = new Intent("TIMER_UPDATE");
                    updateIntent.putExtra("TIME_LEFT", timeLeft);
                    sendBroadcast(updateIntent);
                    
                    // Schedule next update
                    timerHandler.postDelayed(this, TIMER_UPDATE_INTERVAL);
                }
            }
        };
        
        // Start the timer
        timerHandler.post(timerRunnable);
    }

    public void cancelTimer() {
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
        timerEndTime = 0;
        
        // Send broadcast to update UI
        Intent finishedIntent = new Intent("TIMER_FINISHED");
        sendBroadcast(finishedIntent);
    }
} 