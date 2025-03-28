package com.example.aud_player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;

public class AudioPlaybackService extends Service {
    private static final String TAG = "AudioPlaybackService";
    private static final String CHANNEL_ID = "AudioPlaybackChannel";
    private static final int NOTIFICATION_ID = 1;

    private MediaPlayer mediaPlayer;
    private MediaPlayer secondMediaPlayer;
    private final IBinder binder = new LocalBinder();
    private String currentTitle = "Audio Player";
    private boolean isPlaying = false;
    private boolean secondAudioActive = false;

    public class LocalBinder extends Binder {
        AudioPlaybackService getService() {
            return AudioPlaybackService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "ACTION_PLAY":
                    if (mediaPlayer != null) {
                        if (!isPlaying) {
                            try {
                                mediaPlayer.start();
                                if (secondMediaPlayer != null && secondAudioActive) {
                                    secondMediaPlayer.start();
                                }
                                isPlaying = true;
                                updateNotification();
                            } catch (IllegalStateException e) {
                                Log.e(TAG, "Error starting playback in service", e);
                            }
                        }
                    }
                    break;
                case "ACTION_PAUSE":
                    if (mediaPlayer != null) {
                        if (isPlaying) {
                            try {
                                mediaPlayer.pause();
                                if (secondMediaPlayer != null && secondAudioActive) {
                                    secondMediaPlayer.pause();
                                }
                                isPlaying = false;
                                updateNotification();
                            } catch (IllegalStateException e) {
                                Log.e(TAG, "Error pausing playback in service", e);
                            }
                        }
                    }
                    break;
            }
        }
        
        startForeground(NOTIFICATION_ID, createNotification());
        return START_STICKY;
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
        playPauseIntent.setAction(isPlaying ? "ACTION_PAUSE" : "ACTION_PLAY");
        PendingIntent playPausePendingIntent = PendingIntent.getService(
            this, 1, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(isPlaying ? "Playing" : "Paused")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play, 
                      isPlaying ? "Pause" : "Play", 
                      playPausePendingIntent);

        return builder.build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing media player", e);
            }
            mediaPlayer = null;
        }
        if (secondMediaPlayer != null) {
            try {
                if (secondMediaPlayer.isPlaying()) {
                    secondMediaPlayer.stop();
                }
                secondMediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing second media player", e);
            }
            secondMediaPlayer = null;
        }
    }
} 