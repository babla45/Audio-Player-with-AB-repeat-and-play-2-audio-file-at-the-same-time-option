package com.example.aud_player;

import android.media.MediaPlayer;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Process-wide registry of live MediaPlayers created by the app.
 *
 * Guarantees that when the mixer is OFF exactly one player can be audibly
 * playing, no matter how a stale player came to exist (a leaked broadcast
 * receiver on a closed activity, a stale prepare callback, an orphaned
 * background service instance, etc.). Every player the app creates is
 * registered here; starting a new main track sweeps all others away, and
 * closing the app sweeps everything away.
 */
public final class PlaybackRegistry {
    private static final String TAG = "PlaybackRegistry";
    private static final Set<MediaPlayer> activePlayers =
            Collections.synchronizedSet(new HashSet<>());

    private PlaybackRegistry() {
    }

    public static void register(MediaPlayer mp) {
        if (mp != null) {
            activePlayers.add(mp);
        }
    }

    public static void unregister(MediaPlayer mp) {
        if (mp != null) {
            activePlayers.remove(mp);
        }
    }

    /** Stop and release every registered player (used on app close). */
    public static void stopAll() {
        stopAllExcept(null, null);
    }

    /**
     * Stop and release every registered player except the keepers — the main
     * player that is about to play, and (when the mixer is on) the second
     * track. Players already released elsewhere are simply dropped.
     */
    public static void stopAllExcept(MediaPlayer keepMain, MediaPlayer keepSecond) {
        synchronized (activePlayers) {
            for (MediaPlayer mp : new HashSet<>(activePlayers)) {
                if (mp == null || mp == keepMain || mp == keepSecond) {
                    continue;
                }
                try {
                    if (mp.isPlaying()) {
                        mp.stop();
                    }
                } catch (Exception ignored) {
                    // Already released / in an error state — drop it anyway
                }
                try {
                    mp.release();
                } catch (Exception ignored) {
                }
                activePlayers.remove(mp);
                Log.w(TAG, "Stopped and released an orphaned MediaPlayer");
            }
        }
    }
}
