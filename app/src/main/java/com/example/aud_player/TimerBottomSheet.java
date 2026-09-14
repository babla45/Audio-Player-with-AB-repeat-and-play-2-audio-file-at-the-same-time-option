package com.example.aud_player;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;

public class TimerBottomSheet extends BottomSheetDialogFragment {

    public interface TimerListener {
        void onTimerSet(int minutes, int action);
        void onTimerCancel();
    }

    /** Gives the sheet the song currently open in the player, for "▶ Current". */
    public interface CurrentSongProvider {
        Uri getCurrentSongUri();
        String getCurrentSongTitle();
    }

    private TimerListener listener;
    private CurrentSongProvider currentSongProvider;
    private int selectedMinutes = 0;

    public void setCurrentSongProvider(CurrentSongProvider provider) {
        this.currentSongProvider = provider;
    }

    // Schedule tab state (lives only while the sheet is open; the armed
    // schedule itself is persisted by ScheduleTimerHelper)
    private Uri scheduledSongUri;
    private String scheduledSongTitle;

    private TextView sleepTab, scheduleTab, scheduleSongTitle, scheduleStatusText;
    private View sleepPane, schedulePane;
    private EditText hourInput, minuteInput;

    private final ActivityResultLauncher<Intent> songPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri != null) {
                    scheduledSongUri = uri;
                    scheduledSongTitle = displayNameOf(uri);
                    scheduleSongTitle.setText("🎵 " + scheduledSongTitle);
                }
            });

    private String displayNameOf(Uri uri) {
        String name = null;
        try (android.database.Cursor c = requireContext().getContentResolver().query(
                uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null) name = uri.getLastPathSegment();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
        }
        return name != null ? name : "Selected song";
    }

    public void setTimerListener(TimerListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_timer, container, false);

        // ===== Sleep tab (original behaviour, untouched) =====
        EditText customInput = view.findViewById(R.id.timer_custom_input);

        // Chip presets
        Chip chip15 = view.findViewById(R.id.timer_chip_15);
        Chip chip30 = view.findViewById(R.id.timer_chip_30);
        Chip chip45 = view.findViewById(R.id.timer_chip_45);
        Chip chip60 = view.findViewById(R.id.timer_chip_60);

        View.OnClickListener chipListener = v -> {
            if (v == chip15) { selectedMinutes = 15; customInput.setText("15"); }
            else if (v == chip30) { selectedMinutes = 30; customInput.setText("30"); }
            else if (v == chip45) { selectedMinutes = 45; customInput.setText("45"); }
            else if (v == chip60) { selectedMinutes = 60; customInput.setText("60"); }
        };

        chip15.setOnClickListener(chipListener);
        chip30.setOnClickListener(chipListener);
        chip45.setOnClickListener(chipListener);
        chip60.setOnClickListener(chipListener);

        // Set timer button
        view.findViewById(R.id.timer_set_btn).setOnClickListener(v -> {
            String inputText = customInput.getText().toString().trim();
            if (inputText.isEmpty()) {
                Toast.makeText(getContext(), "Enter minutes or select a preset", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                int minutes = (int) Float.parseFloat(inputText);
                if (minutes < 1 || minutes > 180) {
                    Toast.makeText(getContext(), "Enter 1-180 minutes", Toast.LENGTH_SHORT).show();
                    return;
                }

                RadioGroup actionGroup = view.findViewById(R.id.timer_action_group);
                int action = actionGroup.getCheckedRadioButtonId() == R.id.timer_action_close ? 1 : 0;

                if (listener != null) {
                    listener.onTimerSet(minutes, action);
                }
                dismiss();
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), "Invalid number", Toast.LENGTH_SHORT).show();
            }
        });

        // Cancel timer button
        view.findViewById(R.id.timer_cancel_btn).setOnClickListener(v -> {
            if (listener != null) {
                listener.onTimerCancel();
            }
            dismiss();
        });

        // ===== Schedule tab =====
        sleepTab = view.findViewById(R.id.timer_tab_sleep);
        scheduleTab = view.findViewById(R.id.timer_tab_schedule);
        sleepPane = view.findViewById(R.id.timer_sleep_pane);
        schedulePane = view.findViewById(R.id.timer_schedule_pane);
        scheduleSongTitle = view.findViewById(R.id.schedule_song_title);
        scheduleStatusText = view.findViewById(R.id.schedule_status_text);
        hourInput = view.findViewById(R.id.schedule_hour_input);
        minuteInput = view.findViewById(R.id.schedule_minute_input);

        // Mode toggle: ⏰ At time  ⇄  ⏳ After (x h y m z s from now)
        EditText durHour = view.findViewById(R.id.schedule_dur_hour);
        EditText durMin = view.findViewById(R.id.schedule_dur_min);
        EditText durSec = view.findViewById(R.id.schedule_dur_sec);
        View timeRow = view.findViewById(R.id.schedule_time_row);
        View durationRow = view.findViewById(R.id.schedule_duration_row);
        RadioGroup modeGroup = view.findViewById(R.id.schedule_mode_group);
        modeGroup.setOnCheckedChangeListener((g, checkedId) -> {
            boolean atTime = checkedId == R.id.schedule_mode_at;
            timeRow.setVisibility(atTime ? View.VISIBLE : View.GONE);
            durationRow.setVisibility(atTime ? View.GONE : View.VISIBLE);
        });

        sleepTab.setOnClickListener(v -> showTab(true));
        scheduleTab.setOnClickListener(v -> showTab(false));
        showTab(true);

        View.OnClickListener timeChip = v -> {
            String t = ((Chip) v).getText().toString(); // "HH:MM"
            String[] parts = t.split(":");
            hourInput.setText(parts[0]);
            minuteInput.setText(parts[1]);
        };
        view.findViewById(R.id.schedule_chip_1).setOnClickListener(timeChip);
        view.findViewById(R.id.schedule_chip_2).setOnClickListener(timeChip);
        view.findViewById(R.id.schedule_chip_3).setOnClickListener(timeChip);
        view.findViewById(R.id.schedule_chip_4).setOnClickListener(timeChip);

        view.findViewById(R.id.schedule_pick_song_btn).setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), AudioListActivity.class);
            songPicker.launch(intent);
        });

        // "▶ Current" = resume the CURRENTLY OPEN song from its paused
        // position (main + mixer second track together). Only meaningful in
        // After mode — a clock time can't know what is paused now.
        view.findViewById(R.id.schedule_current_song_btn).setOnClickListener(v -> {
            if (currentSongProvider == null
                    || currentSongProvider.getCurrentSongUri() == null) {
                Toast.makeText(getContext(), "No song is currently open", Toast.LENGTH_SHORT).show();
                return;
            }
            scheduledSongUri = null; // resume mode: no song picked
            scheduledSongTitle = "Resume: " + currentSongProvider.getCurrentSongTitle();
            scheduleSongTitle.setText("▶ " + scheduledSongTitle);
        });

        // Existing pending schedule, if any
        String pending = ScheduleTimerHelper.describe(requireContext());
        if (pending != null) {
            scheduleStatusText.setVisibility(View.VISIBLE);
            scheduleStatusText.setText("⏰ Scheduled: " + pending);
        }

        view.findViewById(R.id.schedule_set_btn).setOnClickListener(v -> {
            boolean atTime = modeGroup.getCheckedRadioButtonId() == R.id.schedule_mode_at;

            if (atTime) {
                Integer hour = parse(hourInput, 0, 23);
                Integer minute = parse(minuteInput, 0, 59);
                if (hour == null || minute == null) {
                    Toast.makeText(getContext(), "Enter a valid time (00:00–23:59)", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (scheduledSongUri == null) {
                    Toast.makeText(getContext(), "Choose a song first (🎵)", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean daily = ((android.widget.CheckBox) view.findViewById(R.id.schedule_daily_check)).isChecked();
                ScheduleTimerHelper.schedule(requireContext(), hour, minute,
                        daily, scheduledSongUri, scheduledSongTitle);
                Toast.makeText(getContext(), "Scheduled " + String.format("%02d:%02d", hour, minute)
                        + " • " + scheduledSongTitle + (daily ? " (daily)" : ""), Toast.LENGTH_LONG).show();
            } else {
                Integer h = parseOrZero(durHour, 0, 99);
                Integer m = parseOrZero(durMin, 0, 59);
                Integer s = parseOrZero(durSec, 0, 59);
                if (h == null || m == null || s == null || (h == 0 && m == 0 && s == 0)) {
                    Toast.makeText(getContext(), "Enter a delay (e.g. 00:05:30)", Toast.LENGTH_SHORT).show();
                    return;
                }
                long delayMs = (h * 3600L + m * 60L + s) * 1000L;
                if (scheduledSongUri == null) {
                    // ▶ Current was tapped: resume the paused playback
                    ScheduleTimerHelper.scheduleResume(requireContext(), delayMs);
                    Toast.makeText(getContext(), "Resume in "
                            + String.format("%02d:%02d:%02d", h, m, s)
                            + " • " + scheduledSongTitle.replaceFirst("^Resume: ", ""),
                            Toast.LENGTH_LONG).show();
                } else {
                    ScheduleTimerHelper.scheduleAfter(requireContext(), delayMs,
                            scheduledSongUri, scheduledSongTitle);
                    Toast.makeText(getContext(), "Plays in "
                            + String.format("%02d:%02d:%02d", h, m, s)
                            + " • " + scheduledSongTitle, Toast.LENGTH_LONG).show();
                }
            }
            dismiss();
        });

        view.findViewById(R.id.schedule_cancel_btn).setOnClickListener(v -> {
            if (!ScheduleTimerHelper.isScheduled(requireContext())) {
                Toast.makeText(getContext(), "No schedule set", Toast.LENGTH_SHORT).show();
                return;
            }
            ScheduleTimerHelper.cancel(requireContext());
            Toast.makeText(getContext(), "Schedule cancelled", Toast.LENGTH_SHORT).show();
            scheduleStatusText.setVisibility(View.GONE);
        });

        return view;
    }

    /** null when empty or out of [min,max]. */
    private Integer parse(EditText input, int min, int max) {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) return null;
        try {
            int v = Integer.parseInt(t);
            return (v >= min && v <= max) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Like {@link #parse} but an empty field means 0. */
    private Integer parseOrZero(EditText input, int min, int max) {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) return 0;
        return parse(input, min, max);
    }

    private void showTab(boolean sleep) {
        sleepPane.setVisibility(sleep ? View.VISIBLE : View.GONE);
        schedulePane.setVisibility(sleep ? View.GONE : View.VISIBLE);
        sleepTab.setTextColor(getResources().getColor(R.color.accent_primary, null));
        scheduleTab.setTextColor(getResources().getColor(
                sleep ? R.color.text_secondary : R.color.accent_primary, null));
    }

    @Override
    public int getTheme() {
        return com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog;
    }
}
