package com.example.aud_player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;
import android.content.ComponentName;

import android.telephony.TelephonyManager;
import android.graphics.Bitmap;
import android.media.audiofx.LoudnessEnhancer;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.Color;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioPlaybackService extends Service {
    private static final String TAG = "AudioPlaybackService";
    private static final String CHANNEL_ID = "AudioPlaybackChannel_v2";
    private static final String OLD_CHANNEL_ID = "AudioPlaybackChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_PLAY = "ACTION_PLAY";
    private static final String ACTION_PAUSE = "ACTION_PAUSE";
    private static final String ACTION_STOP = "ACTION_STOP";
    private static final String ACTION_NEXT = "ACTION_NEXT";
    private static final String ACTION_PREV = "ACTION_PREV";
    /** Schedule Timer: play the given Uri on a service-owned player. */
    public static final String ACTION_SCHEDULED_PLAY = "ACTION_SCHEDULED_PLAY";
    /** Schedule Timer: resume the player's paused playback. */
    public static final String ACTION_SCHEDULED_RESUME = "ACTION_SCHEDULED_RESUME";
    public static final String PREF_NOTIFICATION_ALBUM_ART = "notification_mini_player_album_art";

    // Shared boost range — must match MainActivity's BOOST_MIN / BOOST_MAX
    // so the slider, service, presets and voice-changer all agree.
    public static final float BOOST_MIN = 1.0f;
    public static final float BOOST_MAX = 3.0f;

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
    private boolean noisyReceiverRegistered = false;
    private boolean isForegroundStarted = false;
    // True once this instance has been told to stop (ACTION_STOP, or the sleep
    // timer's close-app action). A stopped instance is on its way out, so it
    // must never post its notification again — see updateNotification().
    private boolean serviceStopped = false;
    private float currentPitch = 0.0f;
    private float currentBoost = 1.0f;
    private LoudnessEnhancer loudnessEnhancerMain;
    private LoudnessEnhancer loudnessEnhancerSecond;
    private MediaSessionCompat mediaSession;
    private PlaybackStateCompat.Builder playbackStateBuilder;
    private Bitmap notificationArtwork;
    private Bitmap currentTrackArtwork;
    private Uri currentTrackArtworkUri;
    private volatile int artworkLoadVersion;
    private boolean showNotificationAlbumArt = true;
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Cached PendingIntents — recreated only on first use or after service restart.
    // Prevents rebuilding identical PendingIntent objects on every notification update.
    private PendingIntent cachedContentIntent;
    private PendingIntent cachedPrevIntent;
    private PendingIntent cachedNextIntent;
    private PendingIntent cachedStopIntent;
    private boolean pendingIntentsNeedRebuild = true;
    private final BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                try {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        mediaPlayer.pause();
                    }
                    if (secondMediaPlayer != null && secondAudioActive && secondMediaPlayer.isPlaying()) {
                        secondMediaPlayer.pause();
                    }
                    isPlaying = false;
                    abandonAudioFocus();
                    updateNotification();
                    // Notify UI to reflect paused state
                    try {
                        Intent pausedIntent = new Intent("PLAYBACK_PAUSED");
                        sendLocalBroadcast(pausedIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to broadcast paused state", e);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling becoming noisy", e);
                }
            }
        }
    };
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = focusChange -> {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                try {
                    if (!isAllowAudioMixEnabled() && mediaPlayer != null && mediaPlayer.isPlaying()) {
                        pausedByAudioFocusLoss = true;
                        mediaPlayer.pause();
                    }
                    if (!isAllowAudioMixEnabled() && secondMediaPlayer != null && secondAudioActive && secondMediaPlayer.isPlaying()) {
                        secondMediaPlayer.pause();
                    }
                    if (!isAllowAudioMixEnabled()) {
                        isPlaying = false;
                        unregisterBecomingNoisy(); // No audio playing → unregister
                        updateNotification();
                        // Notify UI to reflect paused state
                        try {
                            sendLocalBroadcast(new Intent("PLAYBACK_PAUSED"));
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to broadcast paused state", e);
                        }
                    }
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
                            if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                                try { mediaPlayer.start(); } catch (IllegalStateException e) {
                                    Log.w(TAG, "Failed to resume on focus gain", e);
                                }
                            }
                            if (secondMediaPlayer != null && secondAudioActive && !secondMediaPlayer.isPlaying()) {
                                try { secondMediaPlayer.start(); } catch (IllegalStateException e) {
                                    Log.w(TAG, "Failed to resume second on focus gain", e);
                                }
                            }
                            isPlaying = true;
                            pausedByAudioFocusLoss = false;
                            registerBecomingNoisy(); // re-register since playback resumed
                            updateNotification();
                            // Notify UI to reflect resumed state
                            try {
                                sendLocalBroadcast(new Intent("PLAYBACK_RESUMED"));
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to broadcast resumed state", e);
                            }
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

    private boolean isAllowAudioMixEnabled() {
        try {
            return getSharedPreferences("audio_player_prefs", MODE_PRIVATE)
                    .getBoolean("allow_audio_mix", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Apply updated audio-mix preference immediately without requiring app restart.
     */
    public void onAudioMixPreferenceChanged(boolean enabled) {
        try {
            if (enabled) {
                // In mix mode we should not hold exclusive focus.
                abandonAudioFocus();
            } else if ((mediaPlayer != null && mediaPlayer.isPlaying())
                    || (secondMediaPlayer != null && secondAudioActive && secondMediaPlayer.isPlaying())) {
                // In non-mix mode, re-acquire focus right away while playback is active.
                requestAudioFocus();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply audio mix preference change", e);
        }
    }


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
        setupMediaSession();
        try {
            currentPitch = PitchProcessor.loadPitchSemitones(
                    getSharedPreferences("audio_player_prefs", MODE_PRIVATE));
        } catch (Exception ignored) {}
        try {
            currentBoost = getSharedPreferences("audio_player_prefs", MODE_PRIVATE)
                    .getFloat("volume_boost_factor", 1.0f);
        } catch (Exception ignored) {}
        showNotificationAlbumArt = getSharedPreferences("audio_player_prefs", MODE_PRIVATE)
                .getBoolean(PREF_NOTIFICATION_ALBUM_ART, true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A stop command silences this service's notification for good; every
        // other command (play/pause/next/…) revives it. The stop path below
        // still calls startForeground() once more and then removes what it
        // posted (stopForeground) — but from that point on updateNotification()
        // must not post anything again, or the mini-player outlives the
        // service and nothing can ever remove it (see updateNotification).
        serviceStopped = intent != null && ACTION_STOP.equals(intent.getAction());

        // A service that is already foregrounded does not need an identical
        // notification repost. New services still promote immediately.
        if (!isForegroundStarted) {
            try {
                startForeground(NOTIFICATION_ID, createNotification());
                isForegroundStarted = true;
            } catch (Exception e) {
                Log.e(TAG, "startForeground failed in onStartCommand", e);
            }
        }

        String action = (intent != null) ? intent.getAction() : null;

        if (action != null) {
            switch (action) {
                case ACTION_PLAY:
                    if (mediaPlayer != null) {
                        if (!isPlaying) {
                            try {
                                if (requestAudioFocus()) {
                                    mediaPlayer.start();
                                    if (secondMediaPlayer != null && secondAudioActive) {
                                        try {
                                            // Don't restart a second track that already
                                            // played to its end — start() would loop it
                                            // back from 0:00 over the new song
                                            boolean finished = secondMediaPlayer.getCurrentPosition()
                                                    >= secondMediaPlayer.getDuration() - 50;
                                            if (!finished) {
                                                secondMediaPlayer.start();
                                            }
                                        } catch (Exception ignored) {
                                            // Stale second player — resume the main track anyway
                                        }
                                    }
                                    isPlaying = true;
                                    registerBecomingNoisy();
                                    updateMediaSessionMetadata();
                                    // Re-post foreground notification now that isPlaying changed
                                    startForeground(NOTIFICATION_ID, createNotification());
                                }
                            } catch (IllegalStateException e) {
                                Log.e(TAG, "Error starting playback in service", e);
                            }
                        }
                    }
                    // Notify UI to reflect resumed state (e.g. mini player button)
                    try {
                        sendLocalBroadcast(new Intent("PLAYBACK_RESUMED"));
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to broadcast resumed state", e);
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
                                unregisterBecomingNoisy(); // No audio playing → unregister
                                // Re-post foreground notification now that isPlaying changed
                                startForeground(NOTIFICATION_ID, createNotification());
                            } catch (IllegalStateException e) {
                                Log.e(TAG, "Error pausing playback in service", e);
                            }
                        }
                    }
                    // Notify UI to reflect paused state (e.g. mini player button)
                    try {
                        sendLocalBroadcast(new Intent("PLAYBACK_PAUSED"));
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to broadcast paused state", e);
                    }
                    break;
                case ACTION_NEXT:
                    try {
                        // Always stop the current track BEFORE requesting the next
                        // one — this guarantees the previous song can never keep
                        // playing under the new one, even if the UI-side handler
                        // is slow, duplicated, or missing after an app restart.
                        if (mediaPlayer != null) {
                            try {
                                if (mediaPlayer.isPlaying()) {
                                    mediaPlayer.stop();
                                }
                            } catch (Exception ignored) {}
                        }
                        if (!secondAudioActive && secondMediaPlayer != null) {
                            try {
                                if (secondMediaPlayer.isPlaying()) {
                                    secondMediaPlayer.stop();
                                }
                            } catch (Exception ignored) {}
                        }
                        Intent nextIntent = new Intent("MEDIA_NEXT");
                        sendLocalBroadcast(nextIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "Error broadcasting next", e);
                    }
                    break;
                case ACTION_PREV:
                    try {
                        // Same as ACTION_NEXT: stop the current track first so a
                        // stale/lagging handler can never leave two songs playing.
                        if (mediaPlayer != null) {
                            try {
                                if (mediaPlayer.isPlaying()) {
                                    mediaPlayer.stop();
                                }
                            } catch (Exception ignored) {}
                        }
                        if (!secondAudioActive && secondMediaPlayer != null) {
                            try {
                                if (secondMediaPlayer.isPlaying()) {
                                    secondMediaPlayer.stop();
                                }
                            } catch (Exception ignored) {}
                        }
                        Intent prevIntent = new Intent("MEDIA_PREV");
                        sendLocalBroadcast(prevIntent);
                    } catch (Exception e) {
                        Log.e(TAG, "Error broadcasting prev", e);
                    }
                    break;
                case ACTION_SCHEDULED_PLAY:
                    playScheduled(intent);
                    break;
                case ACTION_SCHEDULED_RESUME:
                    resumeScheduled();
                    break;
                case ACTION_STOP:
                    try {
                        // First, immediately change state to not playing
                        isPlaying = false;
                        
                        // Send broadcast to close the app immediately
                        Intent closeAppIntent = new Intent("CLOSE_APP_COMMAND");
                        sendLocalBroadcast(closeAppIntent);

                        // Stop the service from foreground state
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_REMOVE);
                        } else {
                            stopForeground(true);
                        }
                        isForegroundStarted = false;

                        // Cancel any pending timers
                        if (timerRunnable != null) {
                            timerHandler.removeCallbacks(timerRunnable);
                        }
                        
                        // Stop both players unconditionally — a stale secondAudioActive
                        // flag must never leave a track playing after the app closes.
                        // Each player is guarded so a dead reference can't abort cleanup.
                        if (mediaPlayer != null) {
                            try {
                                if (mediaPlayer.isPlaying()) {
                                    mediaPlayer.stop();
                                }
                            } catch (Exception ignored) {}
                        }

                        if (secondMediaPlayer != null) {
                            try {
                                if (secondMediaPlayer.isPlaying()) {
                                    secondMediaPlayer.stop();
                                }
                            } catch (Exception ignored) {}
                        }

                        // Kill EVERY remaining player in the process — including
                        // orphans this service has no reference to (e.g. created
                        // by a stale activity instance after a close → reopen
                        // cycle). Nothing may keep playing after the app closes.
                        try {
                            PlaybackRegistry.stopAll();
                        } catch (Exception ignored) {}

                        // Stop the service directly - don't use handler delay.
                        // serviceStopped (set on entry to onStartCommand) keeps
                        // the notification from being re-posted while the
                        // activity finishes and unbinds.
                        serviceStopped = true;
                        stopSelf();
                        abandonAudioFocus();
                        unregisterBecomingNoisy();
                        
                        // Return immediately - don't show notification again
                        return START_NOT_STICKY;
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping playback in service", e);
                    }
                    break;
            }
        }

        updatePlaybackState();
        return START_NOT_STICKY;
    }

    private void setupMediaSession() {
        try {
            ComponentName mediaButtonReceiver = new ComponentName(this, androidx.media.session.MediaButtonReceiver.class);
            mediaSession = new MediaSessionCompat(this, TAG, mediaButtonReceiver, null);
            mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

            mediaSession.setCallback(new MediaSessionCompat.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                    try {
                        if (mediaButtonIntent != null && Intent.ACTION_MEDIA_BUTTON.equals(mediaButtonIntent.getAction())) {
                            KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                            if (event != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                                int keyCode = event.getKeyCode();
                                if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                                    Intent i = new Intent(AudioPlaybackService.this, AudioPlaybackService.class);
                                    i.setAction(isPlaying ? ACTION_PAUSE : ACTION_PLAY);
                                    startServiceCompat(i);
                                    return true;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                    return super.onMediaButtonEvent(mediaButtonIntent);
                }
                @Override
                public void onPlay() {
                    Intent i = new Intent(AudioPlaybackService.this, AudioPlaybackService.class);
                    i.setAction(ACTION_PLAY);
                    startServiceCompat(i);
                }

                @Override
                public void onPause() {
                    Intent i = new Intent(AudioPlaybackService.this, AudioPlaybackService.class);
                    i.setAction(ACTION_PAUSE);
                    startServiceCompat(i);
                }

                @Override
                public void onSkipToNext() {
                    Intent i = new Intent(AudioPlaybackService.this, AudioPlaybackService.class);
                    i.setAction(ACTION_NEXT);
                    startServiceCompat(i);
                }

                @Override
                public void onSkipToPrevious() {
                    Intent i = new Intent(AudioPlaybackService.this, AudioPlaybackService.class);
                    i.setAction(ACTION_PREV);
                    startServiceCompat(i);
                }

                @Override
                public void onSeekTo(long pos) {
                    seekToPosition(pos);
                }

                @Override
                public void onCustomAction(String action, android.os.Bundle extras) {
                    if (ACTION_STOP.equals(action)) {
                        Intent i = new Intent(AudioPlaybackService.this, AudioPlaybackService.class);
                        i.setAction(ACTION_STOP);
                        startServiceCompat(i);
                    }
                }
            });

            playbackStateBuilder = new PlaybackStateCompat.Builder()
                    .setActions(
                            PlaybackStateCompat.ACTION_PLAY |
                            PlaybackStateCompat.ACTION_PAUSE |
                            PlaybackStateCompat.ACTION_PLAY_PAUSE |
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                            PlaybackStateCompat.ACTION_STOP |
                            PlaybackStateCompat.ACTION_SEEK_TO
                    )
                    .addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                            ACTION_STOP, "Stop", R.drawable.ic_stop).build())
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1f);
            mediaSession.setPlaybackState(playbackStateBuilder.build());
            mediaSession.setActive(true);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up MediaSession", e);
        }
    }

    private int lastPlaybackState = -1;
    private long lastPlaybackPosition = -1;

    private void updatePlaybackState() {
        if (mediaSession == null || playbackStateBuilder == null) return;
        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        long position = 0;
        try { position = mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0; } catch (Exception ignored) {}
        // Skip update if neither state nor position changed meaningfully
        if (state == lastPlaybackState && Math.abs(position - lastPlaybackPosition) < 1000) return;
        lastPlaybackState = state;
        lastPlaybackPosition = position;
        try {
            mediaSession.setPlaybackState(
                    playbackStateBuilder
                            .setActions(
                                    PlaybackStateCompat.ACTION_PLAY |
                                    PlaybackStateCompat.ACTION_PAUSE |
                                    PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                    PlaybackStateCompat.ACTION_STOP |
                                    PlaybackStateCompat.ACTION_SEEK_TO
                            )
                            .setState(state, position, 1f)
                            .build()
            );
        } catch (Exception ignored) {}
    }

    /**
     * Public hook so the Activity can refresh the MediaSession playback state
     * (and thus the notification's seekbar position) after the app-side UI
     * seekbar is moved. Slightly delayed because MediaPlayer.seekTo() is async
     * and getCurrentPosition() may still report the old value immediately.
     */
    public void notifyAppSeek() {
        if (appSeekHandler == null) {
            appSeekHandler = new Handler(Looper.getMainLooper());
        }
        appSeekHandler.removeCallbacks(applyAppSeek);
        // Post twice: a quick refresh and one after the seek has settled
        appSeekHandler.postDelayed(applyAppSeek, 100);
        appSeekHandler.postDelayed(applyAppSeek, 400);
    }

    private final Runnable applyAppSeek = new Runnable() {
        @Override
        public void run() {
            updatePlaybackState();
        }
    };
    private Handler appSeekHandler;

    private void seekToPosition(long pos) {
        if (mediaPlayer == null) {
            return;
        }
        try {
            int duration = mediaPlayer.getDuration();
            int target = (int) Math.max(0, pos);
            if (duration > 0) {
                target = Math.min(target, duration);
            }
            mediaPlayer.seekTo(target);
            if (secondMediaPlayer != null && secondAudioActive && duration > 0) {
                try {
                    int secondDuration = secondMediaPlayer.getDuration();
                    int secondPosition = (int) ((target / (float) duration) * secondDuration);
                    secondMediaPlayer.seekTo(secondPosition);
                } catch (Exception e) {
                    Log.e(TAG, "Error syncing second player after seek", e);
                }
            }
            updatePlaybackState();
            Intent seekIntent = new Intent("PLAYBACK_SEEKED");
            seekIntent.putExtra("position", target);
            sendLocalBroadcast(seekIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error seeking from media session", e);
        }
    }

    /**
     * Update the MediaSession metadata with the current track title and duration.
     * On Android 11 (API 30) and below, the system's media notification relies on
     * MediaSession metadata to render properly. Without it the notification may
     * appear blank or not show at all on some devices (e.g. Itel Vision 3).
     */
    private void updateMediaSessionMetadata() {
        if (mediaSession == null) return;
        try {
            long duration = (mediaPlayer != null) ? mediaPlayer.getDuration() : 0;
            Bitmap art = getNotificationArtwork();
            MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "AudPlayer")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
            if (art != null) {
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
            }

            mediaSession.setMetadata(metadataBuilder.build());
        } catch (Exception e) {
            Log.e(TAG, "Error updating media session metadata", e);
        }
    }

    private void startServiceCompat(Intent i) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForegroundService(i);
            } catch (Exception e) {
                Log.e(TAG, "startForegroundService failed", e);
                try { startService(i); } catch (Exception ignored) {}
            }
        } else {
            try { startService(i); } catch (Exception ignored) {}
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);

            // Delete old channel so its stale IMPORTANCE_LOW setting doesn't persist
            manager.deleteNotificationChannel(OLD_CHANNEL_ID);

            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Used for audio playback controls");
            channel.setShowBadge(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            // Disable sound for this channel so play/pause doesn't beep
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
        }
    }

    public void setMediaPlayers(MediaPlayer main, MediaPlayer second, String title) {
        setMediaPlayers(main, second, title, null);
    }

    public void setMediaPlayers(MediaPlayer main, MediaPlayer second, String title, Uri artworkUri) {
        // release any previous loudness enhancers before switching players
        try { releaseLoudnessEnhancers(); } catch (Exception ignored) {}

        this.mediaPlayer = main;
        this.secondMediaPlayer = second;
        this.currentTitle = title != null ? title
                : (main == null && second == null ? this.currentTitle : "Audio Player");
        this.isPlaying = main != null && main.isPlaying();
        this.secondAudioActive = second != null;
        if (artworkUri != null) {
            loadTrackArtwork(artworkUri);
        }
        if (this.isPlaying) {
            registerBecomingNoisy();
        } else {
            unregisterBecomingNoisy();
        }
        // Update media session metadata so the notification renders properly on Android 11
        updateMediaSessionMetadata();
        // If the service has already been started as foreground, use startForeground
        // to ensure the notification stays visible on all Android versions (including 11).
        // If only bound (not started), use notify() as fallback — startForeground would crash.
        if (isForegroundStarted) {
            try {
                startForeground(NOTIFICATION_ID, createNotification());
            } catch (Exception e) {
                Log.e(TAG, "startForeground failed in setMediaPlayers", e);
                updateNotification();
            }
        } else {
            updateNotification();
        }
        // Apply saved pitch to any passed MediaPlayers
        applyPitchToPlayers();
        // Initialize loudness enhancers for players and apply saved boost
        try {
            if (mediaPlayer != null) initLoudnessEnhancerForPlayer(mediaPlayer, false);
            if (secondMediaPlayer != null) initLoudnessEnhancerForPlayer(secondMediaPlayer, true);
            applyBoostToEnhancers();
        } catch (Exception e) {
            Log.w(TAG, "LoudnessEnhancer initialization failed", e);
            // setVolume cannot exceed 1.0, so it can never amplify — leave the
            // players at full volume instead of attenuating them.
            try {
                if (mediaPlayer != null) mediaPlayer.setVolume(1.0f, 1.0f);
                if (secondMediaPlayer != null) secondMediaPlayer.setVolume(1.0f, 1.0f);
            } catch (Exception ignored) {}
        }
        updatePlaybackState();
    }

    private void initLoudnessEnhancerForPlayer(MediaPlayer mp, boolean isSecond) {
        if (mp == null) return;
        try {
            int session = mp.getAudioSessionId();
            if (session <= 0) return;
            LoudnessEnhancer le = new LoudnessEnhancer(session);
            le.setEnabled(false);
            if (isSecond) {
                loudnessEnhancerSecond = le;
            } else {
                loudnessEnhancerMain = le;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to create LoudnessEnhancer", e);
        }
    }

    /**
     * Detaches the old players while a new track is being prepared without
     * briefly replacing the visible notification title with a placeholder.
     */
    public void clearMediaPlayersForTrackSwitch() {
        try { releaseLoudnessEnhancers(); } catch (Exception ignored) {}
        mediaPlayer = null;
        secondMediaPlayer = null;
        isPlaying = false;
        secondAudioActive = false;
        currentTrackArtworkUri = null;
        currentTrackArtwork = null;
        artworkLoadVersion++;
        unregisterBecomingNoisy();
        updatePlaybackState();
    }

    public void refreshNotificationArtworkSetting() {
        showNotificationAlbumArt = getSharedPreferences("audio_player_prefs", MODE_PRIVATE)
                .getBoolean(PREF_NOTIFICATION_ALBUM_ART, true);
        if (showNotificationAlbumArt && currentTrackArtwork == null && currentTrackArtworkUri != null) {
            loadTrackArtwork(currentTrackArtworkUri);
        } else {
            updateMediaSessionMetadata();
            updateNotification();
        }
    }

    private void loadTrackArtwork(Uri uri) {
        if (uri == null || uri.equals(currentTrackArtworkUri)) {
            return;
        }
        currentTrackArtworkUri = uri;
        currentTrackArtwork = null;
        int loadVersion = ++artworkLoadVersion;
        if (!showNotificationAlbumArt) {
            return;
        }

        artworkExecutor.execute(() -> {
            if (loadVersion != artworkLoadVersion) {
                return;
            }
            Bitmap artwork = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, uri);
                byte[] embeddedPicture = retriever.getEmbeddedPicture();
                if (embeddedPicture != null && embeddedPicture.length > 0) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(embeddedPicture, 0, embeddedPicture.length, bounds);
                    int largestDimension = Math.max(bounds.outWidth, bounds.outHeight);
                    int sampleSize = 1;
                    while (largestDimension / sampleSize > 512) {
                        sampleSize *= 2;
                    }
                    BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
                    decodeOptions.inSampleSize = sampleSize;
                    Bitmap decoded = BitmapFactory.decodeByteArray(
                            embeddedPicture, 0, embeddedPicture.length, decodeOptions);
                    if (decoded != null) {
                        int maxDimension = Math.max(decoded.getWidth(), decoded.getHeight());
                        if (maxDimension > 512) {
                            float scale = 512f / maxDimension;
                            artwork = Bitmap.createScaledBitmap(
                                    decoded,
                                    Math.max(1, Math.round(decoded.getWidth() * scale)),
                                    Math.max(1, Math.round(decoded.getHeight() * scale)),
                                    true);
                            if (artwork != decoded) {
                                decoded.recycle();
                            }
                        } else {
                            artwork = decoded;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Unable to load embedded notification artwork", e);
            } finally {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.w(TAG, "Unable to release artwork metadata retriever", e);
                }
            }

            Bitmap loadedArtwork = artwork;
            mainHandler.post(() -> {
                if (loadVersion != artworkLoadVersion || !uri.equals(currentTrackArtworkUri)) {
                    if (loadedArtwork != null && !loadedArtwork.isRecycled()) {
                        loadedArtwork.recycle();
                    }
                    return;
                }
                currentTrackArtwork = loadedArtwork;
                updateMediaSessionMetadata();
                updateNotification();
            });
        });
    }

    private Bitmap getNotificationArtwork() {
        if (showNotificationAlbumArt
                && currentTrackArtwork != null
                && !currentTrackArtwork.isRecycled()) {
            return currentTrackArtwork;
        }
        return getOrCreateNotificationArtwork();
    }

    private void applyBoostToEnhancers() {
        try {
            // convert boost factor to millibels (100 * dB). dB = 20*log10(factor)
            float factor = Math.max(0.01f, currentBoost);
            double db = 20.0 * Math.log10(factor);
            int millibels = (int) Math.round(db * 100.0);
            // clamp to safe range
            if (millibels > 2000) millibels = 2000;
            if (millibels < -1000) millibels = -1000;

            if (loudnessEnhancerMain != null) {
                try {
                    loudnessEnhancerMain.setTargetGain(millibels);
                    loudnessEnhancerMain.setEnabled(millibels != 0);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to apply gain to main LoudnessEnhancer", e);
                }
            }

            if (loudnessEnhancerSecond != null) {
                try {
                    loudnessEnhancerSecond.setTargetGain(millibels);
                    loudnessEnhancerSecond.setEnabled(millibels != 0);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to apply gain to second LoudnessEnhancer", e);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error computing/applying loudness boost", e);
        }
    }

    private void releaseLoudnessEnhancers() {
        try {
            if (loudnessEnhancerMain != null) {
                try { loudnessEnhancerMain.release(); } catch (Exception ignored) {}
                loudnessEnhancerMain = null;
            }
        } catch (Exception ignored) {}
        try {
            if (loudnessEnhancerSecond != null) {
                try { loudnessEnhancerSecond.release(); } catch (Exception ignored) {}
                loudnessEnhancerSecond = null;
            }
        } catch (Exception ignored) {}
    }

    private void applyPitchToPlayers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                currentPitch = PitchProcessor.normalizeSemitones(currentPitch);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid saved pitch; restoring unity pitch", e);
                currentPitch = 0.0f;
            }
            PitchProcessor.applyPitchSemitones(mediaPlayer, currentPitch);
            PitchProcessor.applyPitchSemitones(secondMediaPlayer, currentPitch);
        }
    }

    public void setPitch(float pitch) {
        try {
            currentPitch = PitchProcessor.normalizeSemitones(pitch);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Ignoring invalid pitch value", e);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PitchProcessor.applyPitchSemitones(mediaPlayer, currentPitch);
            PitchProcessor.applyPitchSemitones(secondMediaPlayer, currentPitch);
        }

        try {
            PitchProcessor.savePitchSemitones(
                    getSharedPreferences("audio_player_prefs", MODE_PRIVATE), currentPitch);
        } catch (Exception e) {
            Log.e(TAG, "Failed to persist pitch", e);
        }
    }

    public void setBoost(float boost) {
        try {
            if (boost <= 0f) boost = 1.0f;
            // Use shared BOOST_MIN/BOOST_MAX so the service, MainActivity
            // slider, presets and voice-changer all clamp to the same range.
            boost = Math.max(BOOST_MIN, Math.min(BOOST_MAX, boost));
            currentBoost = boost;

            // Persist preference
            try {
                getSharedPreferences("audio_player_prefs", MODE_PRIVATE)
                        .edit().putFloat("volume_boost_factor", currentBoost).apply();
            } catch (Exception ignored) {}

            // Apply best-effort: Android MediaPlayer.setVolume expects 0..1.0, so we scale down
            // Use LoudnessEnhancer where available to apply actual gain in millibels.
            try {
                applyBoostToEnhancers();
            } catch (Exception e) {
                Log.w(TAG, "LoudnessEnhancer apply failed, falling back to setVolume", e);
                // setVolume cannot exceed 1.0, so it can never amplify — leave the
                // players at full volume instead of attenuating them.
                try {
                    if (mediaPlayer != null) mediaPlayer.setVolume(1.0f, 1.0f);
                    if (secondMediaPlayer != null) secondMediaPlayer.setVolume(1.0f, 1.0f);
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to apply boost to players", ex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set boost", e);
        }
    }

    public float getCurrentBoost() {
        return currentBoost;
    }

    public float getCurrentPitch() {
        return currentPitch;
    }

    /**
     * Called from the Activity right after startForegroundService() to immediately
     * promote this service to foreground. This ensures the notification appears
     * on Android 11 and below without waiting for onStartCommand to run.
     */
    public void ensureForeground() {
        if (!isForegroundStarted) {
            try {
                startForeground(NOTIFICATION_ID, createNotification());
                isForegroundStarted = true;
            } catch (Exception e) {
                Log.e(TAG, "ensureForeground failed", e);
            }
        }
    }

    private void updateNotification() {
        // A stopped service owns no notification. Posting here is what used to
        // resurrect the mini-player during app exit: ACTION_STOP removes the
        // notification, MainActivity then finishes and its onDestroy() calls
        // setMediaPlayers(null, null, null) — which lands on this method, and
        // re-posted a notification whose service was already gone. Nothing
        // could dismiss it afterwards, so it sat in the shade forever.
        if (!serviceStopped) {
            NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.notify(NOTIFICATION_ID, createNotification());
        }
        updatePlaybackState();
    }

    /**
     * Rebuild cached PendingIntents. Called once on first notification creation
     * and again if the service is restarted (to pick up fresh request codes).
     */
    private void ensurePendingIntents() {
        if (!pendingIntentsNeedRebuild) return;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        Intent content = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        cachedContentIntent = PendingIntent.getActivity(this, 0, content, flags);

        Intent prev = new Intent(this, AudioPlaybackService.class).setAction(ACTION_PREV);
        cachedPrevIntent = PendingIntent.getService(this, 3, prev, flags);

        Intent next = new Intent(this, AudioPlaybackService.class).setAction(ACTION_NEXT);
        cachedNextIntent = PendingIntent.getService(this, 4, next, flags);

        Intent stop = new Intent(this, AudioPlaybackService.class).setAction(ACTION_STOP);
        cachedStopIntent = PendingIntent.getService(this, 2, stop, flags);

        pendingIntentsNeedRebuild = false;
    }

    private Notification createNotification() {
        ensurePendingIntents();

        // Play/pause intent changes with isPlaying — always rebuild this one
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        Intent playPauseIntent = new Intent(this, AudioPlaybackService.class)
                .setAction(isPlaying ? ACTION_PAUSE : ACTION_PLAY);
        PendingIntent playPausePendingIntent = PendingIntent.getService(
            this, 1, playPauseIntent, flags
        );

        // Use ic_music_note as small icon — it's a proper 24dp monochrome vector.
        // ic_launcher_foreground is 108dp with gradients and renders invisible on
        // many Android 11 devices (including Itel Vision 3).
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(isPlaying ? "Now playing" : "Playback paused")
            .setSubText("AudPlayer Mini Player")
            .setSmallIcon(R.drawable.ic_music_note)
            .setLargeIcon(getNotificationArtwork())
            .setContentIntent(cachedContentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setColor(0xFF7C4DFF)
            .setColorized(true)
            // Add prev, play/pause, next
            .addAction(android.R.drawable.ic_media_previous, "Previous", cachedPrevIntent)
            .addAction(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play,
                      isPlaying ? "Pause" : "Play",
                      playPausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", cachedNextIntent)
            // Add stop
            .addAction(R.drawable.ic_stop, "Stop", cachedStopIntent);



        if (mediaSession != null) {
            builder.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }

        return builder.build();
    }

    private Bitmap getOrCreateNotificationArtwork() {
        if (notificationArtwork != null && !notificationArtwork.isRecycled()) {
            return notificationArtwork;
        }

        final int size = 256;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Shader gradient = new LinearGradient(
                0, 0, size, size,
                new int[]{0xFF7C4DFF, 0xFF00BCD4, 0xFFFF6F91},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP
        );
        backgroundPaint.setShader(gradient);
        canvas.drawRect(0, 0, size, size, backgroundPaint);

        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(0x33FFFFFF);
        canvas.drawCircle(size / 2f, size / 2f, size * 0.35f, circlePaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD));
        textPaint.setTextSize(64f);
        float textY = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f);
        canvas.drawText("BABLA", size / 2f, textY, textPaint);

        notificationArtwork = bitmap;
        return notificationArtwork;
    }

    // Expose current state so the Activity can restore UI after it was killed.
    public MediaPlayer getMediaPlayer() {
        return mediaPlayer;
    }

    public MediaPlayer getSecondMediaPlayer() {
        return secondMediaPlayer;
    }

    public String getCurrentTitle() {
        return currentTitle;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isSecondAudioActive() {
        return secondAudioActive;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // The mini-player notification belongs to this service and must never
        // outlive it, however this instance ended (stop command, stopService()
        // fallback, system kill, low memory). An orphaned notification can
        // neither be dismissed by its buttons nor removed by anything else.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            isForegroundStarted = false;
            serviceStopped = true;
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.cancel(NOTIFICATION_ID);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error removing notification in onDestroy", e);
        }
        if (scheduledFallback != null) {
            scheduleHandler.removeCallbacks(scheduledFallback);
            scheduledFallback = null;
        }
        try {
            // The activity owns these player instances (they are passed in via
            // setMediaPlayers). Releasing them here would leave a still-open
            // activity with a dead MediaPlayer that throws on every call, so
            // only stop them and detach; the activity releases them itself.
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                    }
                } catch (Exception ignored) {}
                mediaPlayer = null;
            }
            if (secondMediaPlayer != null) {
                try {
                    if (secondMediaPlayer.isPlaying()) {
                        secondMediaPlayer.stop();
                    }
                } catch (Exception ignored) {}
                secondMediaPlayer = null;
            }
            secondAudioActive = false;
            isPlaying = false;
            if (scheduledPlayer != null) {
                try { scheduledPlayer.release(); } catch (Exception ignored) {}
                scheduledPlayer = null;
            }
            // Also kill any orphaned players that were never handed to this
            // service, so tearing the service down can never leave audio running.
            try {
                PlaybackRegistry.stopAll();
            } catch (Exception ignored) {}
            abandonAudioFocus();
            if (mediaSession != null) {
                mediaSession.setActive(false);
                mediaSession.release();
                mediaSession = null;
            }
            if (notificationArtwork != null && !notificationArtwork.isRecycled()) {
                notificationArtwork.recycle();
                notificationArtwork = null;
            }
            artworkLoadVersion++;
            artworkExecutor.shutdownNow();
            if (currentTrackArtwork != null && !currentTrackArtwork.isRecycled()) {
                currentTrackArtwork.recycle();
                currentTrackArtwork = null;
            }
            // Release loudness enhancers
            try { releaseLoudnessEnhancers(); } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy", e);
        }
    }

    private boolean requestAudioFocus() {
        try {
            if (isAllowAudioMixEnabled()) return true;
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

    /**
     * Send an explicit broadcast targeted to this app's package.
     * On Android 14+, receivers registered with RECEIVER_NOT_EXPORTED only receive
     * broadcasts that are explicitly targeted to the app's package.
     */
    private void sendLocalBroadcast(Intent intent) {
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void registerBecomingNoisy() {
        if (!noisyReceiverRegistered) {
            try {
                IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(becomingNoisyReceiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    registerReceiver(becomingNoisyReceiver, filter);
                }
                noisyReceiverRegistered = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to register becoming noisy receiver", e);
            }
        }
    }

    private void unregisterBecomingNoisy() {
        if (noisyReceiverRegistered) {
            try {
                unregisterReceiver(becomingNoisyReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister becoming noisy receiver", e);
            } finally {
                noisyReceiverRegistered = false;
            }
        }
    }

    /** Remaining sleep-timer time in ms; 0 when no timer is running. */
    public long getRemainingTimerTime() {
        long left = timerEndTime - System.currentTimeMillis();
        return Math.max(0, left);
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

                            // Also kill any orphaned players this service doesn't
                            // know about so nothing survives the app closing.
                            try {
                                PlaybackRegistry.stopAll();
                            } catch (Exception ignored) {}

                            // Send broadcast to update UI with action
                            Intent finishedIntent = new Intent("TIMER_FINISHED");
                            finishedIntent.putExtra("TIMER_ACTION", timerAction);
                            sendLocalBroadcast(finishedIntent);

                            // Stop service and remove notification. Marked
                            // stopped first so nothing can re-post it while
                            // the app tears down (see updateNotification).
                            serviceStopped = true;
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
                        sendLocalBroadcast(finishedIntent);

                        // Update notification to show paused state
                        isPlaying = false;
                        updateNotification();
                    }
                } else {
                    // Timer still running, send update broadcast
                    Intent updateIntent = new Intent("TIMER_UPDATE");
                    updateIntent.putExtra("TIME_LEFT", timeLeft);
                    sendLocalBroadcast(updateIntent);

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
        sendLocalBroadcast(finishedIntent);
    }

    /**
     * Called by the activity when mixer mode is toggled. Keeps the service's copy of
     * the second-track state in sync so notification/media-session actions never
     * restart a second track the user deactivated.
     */
    public void setSecondAudioActive(boolean active) {
        this.secondAudioActive = active;
        if (!active && secondMediaPlayer != null) {
            try {
                if (secondMediaPlayer.isPlaying()) {
                    secondMediaPlayer.pause();
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Replaces the service's reference to the second player (e.g. after the activity
     * re-created it for a new second track) without touching playback state.
     */
    public void setSecondMediaPlayer(MediaPlayer second) {
        this.secondMediaPlayer = second;
    }

    // ---- Schedule Timer (⏰ tab of TimerBottomSheet) ----

    /** Player owned by the service itself (schedule fires with no activity). */
    private MediaPlayer scheduledPlayer;
    private MediaPlayer.OnCompletionListener scheduledCompletion;
    // Scheduled-fire handoff to a live activity (see playScheduled)
    private final Handler scheduleHandler = new Handler(Looper.getMainLooper());
    private Runnable scheduledFallback;

    /**
     * Schedule Timer "resume" fired: continue playback from the paused
     * position — the main song and, if it was active, the mixer's second
     * track together. A live activity is preferred (it owns the players and
     * all UI wiring); it claims via claimScheduledPlayback(). With the app
     * closed, this service's own player references are resumed instead
     * (they were only paused, never released, while the notification sat in
     * the shade).
     */
    private void resumeScheduled() {
        if (scheduledFallback != null) scheduleHandler.removeCallbacks(scheduledFallback);
        scheduledFallback = () -> {
            scheduledFallback = null;
            // No live activity claimed it — resume this service's players.
            try {
                if (mediaPlayer != null && requestAudioFocus()) {
                    if (!mediaPlayer.isPlaying()) {
                        mediaPlayer.start();
                    }
                    isPlaying = true;
                    registerBecomingNoisy();
                    updateMediaSessionMetadata();
                    startForeground(NOTIFICATION_ID, createNotification());
                    updatePlaybackState();
                    sendLocalBroadcast(new Intent("PLAYBACK_RESUMED"));
                    Log.i(TAG, "Scheduled resume (service): " + currentTitle);
                }
            } catch (Exception e) {
                Log.e(TAG, "Scheduled resume failed", e);
            }
        };
        scheduleHandler.postDelayed(scheduledFallback, 1500);
        sendLocalBroadcast(new Intent("SCHEDULED_RESUME"));
    }

    /**
     * A live activity claims the scheduled song — cancel the service-owned
     * fallback; the activity plays it through its normal playback path
     * (which keeps the mixer, seekbar and notification consistent).
     */
    public void claimScheduledPlayback() {
        if (scheduledFallback != null) {
            scheduleHandler.removeCallbacks(scheduledFallback);
            scheduledFallback = null;
        }
    }

    /**
     * Play the scheduled song. A live activity is preferred (it owns the
     * primary player, mixer state and all UI wiring): we broadcast
     * SCHEDULED_PLAY and give it ~1.5s to claim the request. Only when no
     * activity claims it (app closed/killed) do we fall back to a
     * service-owned MediaPlayer — the alarm started this service via
     * startForegroundService, so we are already foreground with the
     * mini-player notification either way.
     */
    private void playScheduled(Intent intent) {
        android.net.Uri uri = intent.getData();
        if (uri == null) return;
        loadTrackArtwork(uri);
        String title = intent.getStringExtra("title");
        if (title == null || title.isEmpty()) title = "Scheduled song";
        final String songTitle = title;
        final android.net.Uri songUri = uri;

        // Offer the song to a live activity first.
        if (scheduledFallback != null) scheduleHandler.removeCallbacks(scheduledFallback);
        scheduledFallback = () -> {
            scheduledFallback = null;
            playScheduledOnServicePlayer(songUri, songTitle);
        };
        scheduleHandler.postDelayed(scheduledFallback, 1500);
        Intent req = new Intent("SCHEDULED_PLAY");
        req.putExtra("uri", uri.toString());
        req.putExtra("title", songTitle);
        sendLocalBroadcast(req);
    }

    /** No live activity — play on a player the service owns itself. */
    private void playScheduledOnServicePlayer(android.net.Uri uri, String songTitle) {

        // Stop everything else — the scheduled song takes over exclusively.
        try { PlaybackRegistry.stopAll(); } catch (Exception ignored) {}
        if (scheduledPlayer != null) {
            try { scheduledPlayer.release(); } catch (Exception ignored) {}
            scheduledPlayer = null;
        }
        mediaPlayer = null;      // activity-owned reference is dead now
        secondMediaPlayer = null;
        secondAudioActive = false;

        try {
            MediaPlayer mp = new MediaPlayer();
            PlaybackRegistry.register(mp);
            try {
                mp.setDataSource(this, uri);
            } catch (Exception e) {
                // Same descriptor fallback the activity uses (some devices
                // fail on plain content URIs for certain encodings).
                try (android.content.res.AssetFileDescriptor afd =
                             getContentResolver().openAssetFileDescriptor(uri, "r")) {
                    if (afd == null) throw e;
                    mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                }
            }
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setOnPreparedListener(player -> {
                try {
                    if (requestAudioFocus()) {
                        currentTitle = songTitle;
                        this.mediaPlayer = player; // the shared notification player
                        isPlaying = true;
                        player.start();
                        registerBecomingNoisy();
                        updateMediaSessionMetadata();
                        startForeground(NOTIFICATION_ID, createNotification());
                        updatePlaybackState();
                        Log.i(TAG, "Scheduled playback started: " + songTitle);
                        // Tell a live activity UI to adopt this song/player
                        // (otherwise it keeps showing the old, now-stopped
                        // song as paused while the scheduled one plays).
                        Intent resumed = new Intent("PLAYBACK_RESUMED");
                        resumed.putExtra("uri", uri.toString());
                        resumed.putExtra("title", songTitle);
                        sendLocalBroadcast(resumed);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Scheduled playback failed to start", ex);
                }
            });
            scheduledCompletion = player -> {
                // One song, no queue — end of the scheduled song just stops.
                try {
                    isPlaying = false;
                    player.pause();
                    abandonAudioFocus();
                    unregisterBecomingNoisy();
                    updateNotification();
                    sendLocalBroadcast(new Intent("PLAYBACK_PAUSED"));
                } catch (Exception ignored) {}
            };
            mp.setOnCompletionListener(scheduledCompletion);
            scheduledPlayer = mp;
            mp.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare scheduled playback", e);
        }
    }
}
