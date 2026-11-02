package com.example.aud_player;

import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.content.SharedPreferences;
import android.util.Log;

/** Java-facing pitch controls for the existing MediaPlayer playback pipeline. */
final class PitchProcessor {
    static final float MIN_SEMITONES = -12.0f;
    static final float MAX_SEMITONES = 12.0f;
    private static final String PITCH_SEMITONES_KEY = "playback_pitch_semitones_v1";
    private static final String TAG = "PitchProcessor";

    private PitchProcessor() {}

    static double pitchRatioFromSemitones(double semitones) {
        if (!Double.isFinite(semitones)) {
            throw new IllegalArgumentException("Semitones must be finite");
        }
        double ratio = Math.pow(2.0, semitones / 12.0);
        if (!Double.isFinite(ratio) || ratio <= 0.0) {
            throw new IllegalArgumentException("Semitone value is outside the supported range");
        }
        return ratio;
    }

    static double semitonesFromPitchRatio(double pitchRatio) {
        if (!Double.isFinite(pitchRatio) || pitchRatio <= 0.0) {
            throw new IllegalArgumentException("Pitch ratio must be finite and positive");
        }
        return 12.0 * (Math.log(pitchRatio) / Math.log(2.0));
    }

    static float normalizeSemitones(float semitones) {
        if (!Float.isFinite(semitones)) {
            throw new IllegalArgumentException("Semitones must be finite");
        }
        return Math.max(MIN_SEMITONES, Math.min(MAX_SEMITONES, semitones));
    }

    static float loadPitchSemitones(SharedPreferences preferences) {
        if (preferences == null) {
            return 0.0f;
        }
        float semitones;
        if (preferences.getBoolean(PITCH_SEMITONES_KEY, false)) {
            semitones = preferences.getFloat("playback_pitch", 0.0f);
        } else {
            float oldPitchRatio = preferences.getFloat("playback_pitch", 1.0f);
            try {
                semitones = (float) semitonesFromPitchRatio(oldPitchRatio);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid saved pitch ratio; restoring unity pitch", e);
                semitones = 0.0f;
            }
        }
        try {
            semitones = normalizeSemitones(semitones);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid saved pitch; restoring unity pitch", e);
            semitones = 0.0f;
        }
        savePitchSemitones(preferences, semitones);
        return semitones;
    }

    static void savePitchSemitones(SharedPreferences preferences, float semitones) {
        if (preferences == null) {
            return;
        }
        float normalized = normalizeSemitones(semitones);
        preferences.edit()
                .putFloat("playback_pitch", normalized)
                .putBoolean(PITCH_SEMITONES_KEY, true)
                .apply();
    }

    /**
     * Changes only the player's pitch parameter; it deliberately leaves its
     * current speed and playback position untouched.
     */
    static boolean applyPitchSemitones(MediaPlayer player, float semitones) {
        if (player == null) {
            return false;
        }

        final float pitchRatio;
        try {
            pitchRatio = (float) pitchRatioFromSemitones(normalizeSemitones(semitones));
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Ignoring invalid pitch value", e);
            return false;
        }

        try {
            PlaybackParams params = player.getPlaybackParams();
            params.setPitch(pitchRatio);
            player.setPlaybackParams(params);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply pitch without changing playback speed", e);
            return false;
        }
    }
}
