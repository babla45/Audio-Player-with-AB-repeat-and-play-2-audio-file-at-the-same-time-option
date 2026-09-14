package com.example.aud_player;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;

/**
 * Persists and arms the Schedule Timer (the ⏰ tab of TimerBottomSheet).
 *
 * The schedule must fire even when the app is closed or in background, so
 * it is an AlarmManager alarm pointed at {@link ScheduleReceiver} — not a
 * Handler countdown like the sleep timer (which only has to outlive the
 * screen, and is already owned by the foreground service for that).
 *
 * One schedule exists at a time (same UX as the sleep timer's one timer):
 * setting a new one replaces the old, cancel clears it. State is kept in
 * prefs so the sheet can show the pending schedule and the BOOT_COMPLETED
 * receiver can re-arm a daily schedule after a reboot.
 */
public final class ScheduleTimerHelper {

    private static final String TAG = "ScheduleTimerHelper";
    static final String PREFS = "schedule_timer_prefs";
    private static final String KEY_HOUR = "hour";
    private static final String KEY_MINUTE = "minute";
    private static final String KEY_DAILY = "daily";
    private static final String KEY_URI = "uri";
    private static final String KEY_TITLE = "title";
    private static final String KEY_TRIGGER_AT = "trigger_at";
    private static final String KEY_MODE = "mode";
    /** "at" = clock time (can repeat daily), "after" = one-shot delay. */
    public static final String MODE_AT = "at";
    public static final String MODE_AFTER = "after";

    private ScheduleTimerHelper() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isScheduled(Context context) {
        return prefs(context).getLong(KEY_TRIGGER_AT, 0) > 0;
    }

    /** "07:30 • Song title (daily)" or null when nothing is scheduled. */
    public static String describe(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getLong(KEY_TRIGGER_AT, 0) <= 0) return null;
        if (MODE_AFTER.equals(p.getString(KEY_MODE, MODE_AT))) {
            long left = p.getLong(KEY_TRIGGER_AT, 0) - System.currentTimeMillis();
            if (left < 0) left = 0;
            long h = left / 3600000L, m = (left % 3600000L) / 60000L, s = (left % 60000L) / 1000L;
            return String.format(java.util.Locale.getDefault(), "in %02d:%02d:%02d", h, m, s)
                    + " • " + p.getString(KEY_TITLE, "");
        }
        return String.format(java.util.Locale.getDefault(), "%02d:%02d",
                p.getInt(KEY_HOUR, 0), p.getInt(KEY_MINUTE, 0))
                + " • " + p.getString(KEY_TITLE, "")
                + (p.getBoolean(KEY_DAILY, false) ? " (daily)" : "");
    }

    /**
     * Arm a schedule at the NEXT occurrence of hour:minute
     * (today when still in the future, otherwise tomorrow).
     */
    public static void schedule(Context context, int hour, int minute,
                                boolean daily, Uri uri, String title) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        long triggerAt = cal.getTimeInMillis();

        SharedPreferences p = prefs(context);
        p.edit()
                .putString(KEY_MODE, MODE_AT)
                .putInt(KEY_HOUR, hour)
                .putInt(KEY_MINUTE, minute)
                .putBoolean(KEY_DAILY, daily)
                .putString(KEY_URI, uri != null ? uri.toString() : null)
                .putString(KEY_TITLE, title != null ? title : "")
                .putLong(KEY_TRIGGER_AT, triggerAt)
                .apply();

        armAlarm(context, triggerAt);
    }

    /**
     * Arm a one-shot schedule that fires after the given delay
     * (e.g. "x hr y min z sec from now").
     */
    public static void scheduleAfter(Context context, long delayMs,
                                     Uri uri, String title) {
        long triggerAt = System.currentTimeMillis() + Math.max(1000L, delayMs);
        prefs(context).edit()
                .putString(KEY_MODE, MODE_AFTER)
                .putBoolean(KEY_DAILY, false)
                .putString(KEY_URI, uri != null ? uri.toString() : null)
                .putString(KEY_TITLE, title != null ? title : "")
                .putLong(KEY_TRIGGER_AT, triggerAt)
                .apply();
        armAlarm(context, triggerAt);
    }

    /** (Re)arm the system alarm from the stored prefs state. */
    static void armAlarm(Context context, long triggerAt) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pendingIntent(context));

        Intent i = new Intent(context, ScheduleReceiver.class);
        i.setAction(ScheduleReceiver.ACTION_SCHEDULE_FIRE);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_TRIGGER_AT, triggerAt).apply();

        PendingIntent pi = pendingIntent(context);
        // Exact when the platform allows it (Doze-exact wake-ups); fall back
        // gracefully — an inexact alarm still fires, just possibly minutes
        // late on heavily dozing devices.
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Exact alarm refused, falling back to inexact", e);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pendingIntent(context));
        prefs(context).edit().clear().apply();
    }

    /**
     * Re-arm after a reboot: alarms do not survive a restart. A daily
     * schedule always re-arms (next occurrence); a one-shot only when its
     * time has not passed yet while the device was off.
     */
    static void rescheduleFromBoot(Context context) {
        SharedPreferences p = prefs(context);
        long triggerAt = p.getLong(KEY_TRIGGER_AT, 0);
        if (triggerAt <= 0) return;
        boolean daily = p.getBoolean(KEY_DAILY, false);
        if (!daily && triggerAt <= System.currentTimeMillis()) {
            // missed one-shot (at-time or after) while powered off — drop it
            p.edit().clear().apply();
            return;
        }
        if (daily) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, p.getInt(KEY_HOUR, 0));
            cal.set(Calendar.MINUTE, p.getInt(KEY_MINUTE, 0));
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
            triggerAt = cal.getTimeInMillis();
        }
        armAlarm(context, triggerAt);
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent i = new Intent(context, ScheduleReceiver.class);
        i.setAction(ScheduleReceiver.ACTION_SCHEDULE_FIRE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, 4242, i, flags);
    }
}
