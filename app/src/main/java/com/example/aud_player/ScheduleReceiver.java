package com.example.aud_player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

/**
 * Fires the Schedule Timer. The alarm's PendingIntent carries no per-fire
 * extras (it must stay identical so a new schedule replaces the old one);
 * this receiver reads the current schedule from
 * {@link ScheduleTimerHelper}'s prefs instead.
 *
 * On fire it re-arms a daily schedule for tomorrow, then hands the song to
 * {@link AudioPlaybackService} via startForegroundService() — that works
 * whether or not the app/process is alive, and reuses the existing
 * foreground-notification playback system.
 */
public class ScheduleReceiver extends BroadcastReceiver {

    private static final String TAG = "ScheduleReceiver";
    static final String ACTION_SCHEDULE_FIRE = "com.example.aud_player.SCHEDULE_FIRE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // Alarms die with the reboot — a daily schedule must come back.
            ScheduleTimerHelper.rescheduleFromBoot(context);
            return;
        }
        if (!ACTION_SCHEDULE_FIRE.equals(action)) return;

        SharedPreferences p = context.getSharedPreferences(
                ScheduleTimerHelper.PREFS, Context.MODE_PRIVATE);
        boolean resume = p.getBoolean("resume", false);
        String uriStr = p.getString("uri", null);
        String title = p.getString("title", "");
        boolean daily = p.getBoolean("daily", false);
        if (daily) {
            ScheduleTimerHelper.rescheduleFromBoot(context); // re-arm tomorrow
        } else {
            p.edit().remove("trigger_at").apply(); // one-shot consumed
        }
        if (resume) {
            // Resume the player's paused position (main + mixer second track).
            try {
                Intent svc = new Intent(context, AudioPlaybackService.class);
                svc.setAction(AudioPlaybackService.ACTION_SCHEDULED_RESUME);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start scheduled resume", e);
            }
            return;
        }
        if (uriStr == null) {
            Log.w(TAG, "Schedule fired with no song — cancelled");
            return;
        }

        try {
            Intent svc = new Intent(context, AudioPlaybackService.class);
            svc.setAction(AudioPlaybackService.ACTION_SCHEDULED_PLAY);
            svc.setData(Uri.parse(uriStr));
            svc.putExtra("title", title);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start scheduled playback", e);
        }
    }
}
