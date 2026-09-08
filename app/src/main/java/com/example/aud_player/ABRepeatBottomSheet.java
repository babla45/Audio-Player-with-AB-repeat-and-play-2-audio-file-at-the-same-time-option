package com.example.aud_player;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class ABRepeatBottomSheet extends BottomSheetDialogFragment {

    private static final long PROGRESS_UPDATE_INTERVAL_MS = 250L;
    private static final String PREFS_NAME = "audio_player_prefs";
    private static final String KEY_NUDGE_STEP_SECONDS = "ab_nudge_step_seconds";
    private static final float DEFAULT_NUDGE_STEP_SECONDS = 1.0f;
    /** How far apart the two range-slider handles must stay, in milliseconds. */
    private static final float RANGE_MIN_SEPARATION_MS = 200f;

    public interface ABRepeatListener {
        void onSetPointA();
        void onSetPointB();
        void onClearABPoints();
        void onToggleABRepeat();
        int getPointA();
        int getPointB();
        boolean isABRepeatActive();
        int getCurrentPosition();
        int getDuration();
        void onSeekTo(int position);
        void onPlayFrom(int position);
        void onNudgePointA(int deltaMs);
        void onNudgePointB(int deltaMs);
        void onSetPointAAt(int positionMs);
        void onSetPointBAt(int positionMs);
        void onMovePointA(int positionMs);
        void onMovePointB(int positionMs);
        void onTogglePlayPause();
        boolean isPlaying();
    }

    private ABRepeatListener listener;

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            refreshProgress();
            if (listener != null) {
                progressHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS);
            }
        }
    };

    private TextView statusBadge;
    private TextView pointAValue;
    private TextView pointBValue;
    private TextView currentTime;
    private TextView totalTime;
    private SeekBar progressSeekbar;
    private com.google.android.material.slider.RangeSlider rangeSlider;
    private TextView rangeHint;
    private MaterialButton toggleBtn;
    private ImageView nudgeAMinus;
    private ImageView nudgeAPlus;
    private ImageView nudgeBMinus;
    private ImageView nudgeBPlus;
    private boolean userDragging = false;
    private boolean rangeDragging = false;
    private boolean syncingRange = false;

    /** Fine-tuning step for the (-)/(+) buttons, in milliseconds (supports fractional seconds). */
    private int nudgeStepMs = Math.round(DEFAULT_NUDGE_STEP_SECONDS * 1000);
    /** Inline step input in the header: [−] [1] [s] [+]. */
    private EditText stepInput;

    /** Seek step for the ± position nudge row under the progress bar, ms. */
    private static final float DEFAULT_POSITION_NUDGE_STEP_MS = 3000f;
    private int positionNudgeStepMs = (int) DEFAULT_POSITION_NUDGE_STEP_MS;
    /** Editable "3s" step label; tapping it opens the keyboard. */
    private EditText positionStepInput;
    /** Small play/pause toggle at the right of the nudge row. */
    private ImageView playPauseBtn;

    public void setABRepeatListener(ABRepeatListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_ab_repeat, container, false);

        statusBadge = view.findViewById(R.id.ab_status_badge);
        pointAValue = view.findViewById(R.id.ab_point_a_value);
        pointBValue = view.findViewById(R.id.ab_point_b_value);
        currentTime = view.findViewById(R.id.ab_current_time);
        totalTime = view.findViewById(R.id.ab_total_time);
        progressSeekbar = view.findViewById(R.id.ab_progress_seekbar);
        rangeSlider = view.findViewById(R.id.ab_range_slider);
        rangeHint = view.findViewById(R.id.ab_range_hint);
        toggleBtn = view.findViewById(R.id.ab_toggle_btn);
        nudgeAMinus = view.findViewById(R.id.ab_nudge_a_minus);
        nudgeAPlus = view.findViewById(R.id.ab_nudge_a_plus);
        nudgeBMinus = view.findViewById(R.id.ab_nudge_b_minus);
        nudgeBPlus = view.findViewById(R.id.ab_nudge_b_plus);
        stepInput = view.findViewById(R.id.ab_step_input);

        loadNudgeStep();

        view.findViewById(R.id.ab_set_a_btn).setOnClickListener(v -> {
            if (listener != null) {
                listener.onSetPointA();
            }
            refreshPointViews();
        });

        view.findViewById(R.id.ab_set_b_btn).setOnClickListener(v -> {
            if (listener != null) {
                listener.onSetPointB();
            }
            refreshPointViews();
        });

        view.findViewById(R.id.ab_clear_btn).setOnClickListener(v -> {
            if (listener != null) listener.onClearABPoints();
            dismiss();
        });

        toggleBtn.setOnClickListener(v -> {
            if (listener != null) listener.onToggleABRepeat();
            dismiss();
        });

        setupInlineStepControl(view);
        setupPositionNudgeButtons(view);

        // Tapping a timestamp opens a dialog to type the time manually
        pointAValue.setOnClickListener(v -> showEditPointDialog(true));
        pointBValue.setOnClickListener(v -> showEditPointDialog(false));

        setupNudgeButton(nudgeAMinus, -nudgeStepMs, true);
        setupNudgeButton(nudgeAPlus, nudgeStepMs, true);
        setupNudgeButton(nudgeBMinus, -nudgeStepMs, false);
        setupNudgeButton(nudgeBPlus, nudgeStepMs, false);

        setupPlayFromButton(view, R.id.ab_play_a, true);
        setupPlayFromButton(view, R.id.ab_play_b, false);

        setupProgressSeekbar();
        setupRangeSlider();

        refreshPointViews();
        refreshProgress();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Keep the time label and progress bar in sync while the sheet is open
        progressHandler.removeCallbacks(progressUpdater);
        progressHandler.post(progressUpdater);
    }

    @Override
    public void onPause() {
        super.onPause();
        progressHandler.removeCallbacks(progressUpdater);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        progressHandler.removeCallbacks(progressUpdater);
    }

    /** Slidable playback position bar: seeks live while dragging, time label follows the thumb. */
    private void setupProgressSeekbar() {
        progressSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    if (currentTime != null) {
                        currentTime.setText(formatTime(progress));
                    }
                    // Apply the new position immediately as the user drags
                    if (listener != null) {
                        listener.onSeekTo(progress);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userDragging = false;
            }
        });
    }

    /** (-)/(+) fine-tuning buttons that nudge a point by the configured step. */
    private void setupNudgeButton(ImageView button, int deltaMs, boolean isPointA) {
        if (button == null) {
            return;
        }
        button.setOnClickListener(v -> {
            if (listener == null) {
                return;
            }
            if (isPointA) {
                listener.onNudgePointA(deltaMs);
            } else {
                listener.onNudgePointB(deltaMs);
            }
            refreshPointViews();
        });
    }

    /** Green play buttons: seek to Point A/B and start playback from there. */
    private void setupPlayFromButton(View root, int buttonId, boolean isPointA) {
        ImageView button = root.findViewById(buttonId);
        if (button == null) {
            return;
        }
        button.setOnClickListener(v -> {
            if (listener == null) {
                return;
            }
            int point = isPointA ? listener.getPointA() : listener.getPointB();
            if (point >= 0) {
                listener.onPlayFrom(point);
                refreshProgress();
            }
        });
    }

    /**
     * Inline fine-tune step control in the sheet header: [−] [input] [s] [+].
     * Typing a value (or tapping −/+) applies immediately — no popup dialog.
     * The −/+ buttons step by 0.1s below 1s and 0.5s/1s above, quick to reach
     * common steps; typed values accept any decimal between 0.1 and 60.
     */
    private void setupInlineStepControl(View root) {
        ImageView minus = root.findViewById(R.id.ab_step_minus);
        ImageView plus = root.findViewById(R.id.ab_step_plus);

        if (stepInput != null) {
            stepInput.setText(formatStepSeconds(nudgeStepMs));
            stepInput.setOnEditorActionListener((v, actionId, event) -> {
                // Done (✓) key or the Enter key: apply the value, then
                // dismiss the keyboard and leave the field.
                boolean done = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE;
                boolean enterKey = event != null
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && (event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                            || event.getKeyCode() == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER);
                if (done || enterKey) {
                    applyStepFromInput();
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                    v.clearFocus();
                    return true;
                }
                return false;
            });
            stepInput.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    applyStepFromInput();
                }
            });
        }

        if (minus != null) {
            minus.setOnClickListener(v -> {
                float next = stepDown(nudgeStepMs / 1000f);
                if (next > 0f) {
                    applyNudgeStep(next);
                    syncStepInput();
                }
            });
        }
        if (plus != null) {
            plus.setOnClickListener(v -> {
                applyNudgeStep(stepUp(nudgeStepMs / 1000f));
                syncStepInput();
            });
        }
    }

    /**
     * ± seek-step row under the progress bar: [−] … [3s] … [+] [▶].
     * The −/+ buttons seek the playback position by the step; the centered
     * "3s" is an EditText, so tapping it opens the keyboard to type a custom
     * step (0.1–60s). The right-most button toggles play/pause.
     */
    private void setupPositionNudgeButtons(View root) {
        ImageView minus = root.findViewById(R.id.ab_position_minus);
        ImageView plus = root.findViewById(R.id.ab_position_plus);
        positionStepInput = root.findViewById(R.id.ab_position_step_label);
        playPauseBtn = root.findViewById(R.id.ab_play_pause);

        if (minus != null) {
            minus.setOnClickListener(v -> seekBy(-positionNudgeStepMs));
        }
        if (plus != null) {
            plus.setOnClickListener(v -> seekBy(positionNudgeStepMs));
        }

        if (positionStepInput != null) {
            positionStepInput.setText(formatStepSeconds(positionNudgeStepMs));
            positionStepInput.setOnEditorActionListener((v, actionId, event) -> {
                boolean done = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE;
                boolean enterKey = event != null
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && (event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                            || event.getKeyCode() == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER);
                if (done || enterKey) {
                    applyPositionStepFromInput();
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                    v.clearFocus();
                    return true;
                }
                return false;
            });
            positionStepInput.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    applyPositionStepFromInput();
                }
            });
        }

        if (playPauseBtn != null) {
            playPauseBtn.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onTogglePlayPause();
                    syncPlayPauseIcon();
                }
            });
            syncPlayPauseIcon();
        }
    }

    /** Seeks the playback position by deltaMs, clamped to the song bounds. */
    private void seekBy(int deltaMs) {
        if (listener == null) {
            return;
        }
        int duration = Math.max(0, listener.getDuration());
        int position = Math.max(0, listener.getCurrentPosition());
        int target = Math.min(Math.max(position + deltaMs, 0), Math.max(0, duration - 1));
        listener.onSeekTo(target);
        refreshProgress();
    }

    /** Reads the "3s" input and applies it as the seek step (clamped 0.1–60s). */
    private void applyPositionStepFromInput() {
        if (positionStepInput == null) {
            return;
        }
        CharSequence text = positionStepInput.getText();
        float seconds = 0f;
        if (text != null) {
            try {
                seconds = Float.parseFloat(text.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (seconds > 0f && seconds <= 60f) {
            positionNudgeStepMs = Math.round(seconds * 1000f);
        }
        positionStepInput.setText(formatStepSeconds(positionNudgeStepMs));
    }

    /** Play/pause icon: pause (accent) while playing, play (success) when paused. */
    private void syncPlayPauseIcon() {
        if (playPauseBtn == null) {
            return;
        }
        boolean playing = listener != null && listener.isPlaying();
        playPauseBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playPauseBtn.setColorFilter(
                getResources().getColor(playing ? R.color.accent_primary : R.color.success, null));
    }

    /** Reads the inline input and applies it as the new step (clamped 0.1–60s). */    private void applyStepFromInput() {
        if (stepInput == null) {
            return;
        }
        CharSequence text = stepInput.getText();
        float seconds = 0f;
        if (text != null) {
            try {
                seconds = Float.parseFloat(text.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (seconds <= 0f || seconds > 60f) {
            Context context = getContext();
            if (context != null) {
                android.widget.Toast.makeText(context,
                        "Step must be 0.1–60 seconds", android.widget.Toast.LENGTH_SHORT).show();
            }
            syncStepInput(); // restore the last valid value
            return;
        }
        saveNudgeStep(seconds);
    }

    /** Keeps the visible input in sync after −/+ changes. */
    private void syncStepInput() {
        if (stepInput != null) {
            stepInput.setText(formatStepSeconds(nudgeStepMs));
        }
    }

    private float stepDown(float currentSeconds) {
        // Nice stepping: 0.1 increments under 1s, 0.5 under 5s, then 1s
        if (currentSeconds > 5f) {
            return Math.max(5f, currentSeconds - 1f);
        }
        if (currentSeconds > 1f) {
            return Math.max(1f, currentSeconds - 0.5f);
        }
        return Math.max(0.1f, currentSeconds - 0.1f);
    }

    private float stepUp(float currentSeconds) {
        if (currentSeconds < 1f) {
            return Math.min(1f, currentSeconds + 0.1f);
        }
        if (currentSeconds < 5f) {
            return Math.min(5f, currentSeconds + 0.5f);
        }
        return Math.min(60f, currentSeconds + 1f);
    }

    /** Dual-handle slider that adjusts Point A and Point B by dragging, applied live. */
    private void setupRangeSlider() {
        if (rangeSlider == null) {
            return;
        }
        // Tooltip above each handle while dragging
        rangeSlider.setLabelFormatter(value -> formatTime((int) value));

        rangeSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser || syncingRange || listener == null) {
                return;
            }
            java.util.List<Float> values = rangeSlider.getValues();
            int newA = Math.round(values.get(0));
            int newB = Math.round(values.get(1));
            listener.onMovePointA(newA);
            listener.onMovePointB(newB);
            // Update labels live while dragging
            pointAValue.setText(formatTimePrecise(newA));
            pointBValue.setText(formatTimePrecise(newB));
        });

        rangeSlider.addOnSliderTouchListener(
                new com.google.android.material.slider.RangeSlider.OnSliderTouchListener() {
                    @Override
                    public void onStartTrackingTouch(com.google.android.material.slider.RangeSlider slider) {
                        rangeDragging = true;
                    }

                    @Override
                    public void onStopTrackingTouch(com.google.android.material.slider.RangeSlider slider) {
                        rangeDragging = false;
                        refreshPointViews();
                    }
                });
    }

    /**
     * Mirrors the current points onto the range slider. Unset points show their
     * handles at the song start/end, so the slider can be used to set the points
     * by sliding even before Set A / Set B were ever tapped.
     */
    private void syncRangeSlider() {
        if (listener == null || rangeSlider == null) {
            return;
        }
        int duration = Math.max(0, listener.getDuration());
        int pointA = listener.getPointA();
        int pointB = listener.getPointB();
        boolean playable = duration > 0;
        boolean bothSet = pointA >= 0 && pointB >= 0;

        rangeSlider.setEnabled(playable);
        rangeSlider.setAlpha(playable ? 1f : 0.4f);
        if (rangeHint != null) {
            // Hint only while points are still unset; slider works either way
            rangeHint.setVisibility(playable && !bothSet ? View.VISIBLE : View.GONE);
        }
        if (playable && !rangeDragging) {
            syncingRange = true;
            try {
                // An unset point parks its handle at the song start/end
                int displayA = pointA >= 0 ? pointA : 0;
                int displayB = pointB >= 0 ? pointB : duration;
                // Configure the full range before the values; a stepSize is not used
                // because it must divide the range exactly (song lengths won't).
                rangeSlider.setValueFrom(0f);
                rangeSlider.setValueTo((float) duration);
                // Keep the handles apart, but never more than the actual A-B gap
                float separation = Math.min(RANGE_MIN_SEPARATION_MS,
                        Math.max(1f, (float) (displayB - displayA)));
                rangeSlider.setMinSeparation(separation);
                rangeSlider.setValues((float) displayA, (float) displayB);
            } finally {
                syncingRange = false;
            }
        }
    }

    /** Dialog for typing a Point A/B time manually, e.g. "1:23.4", "2:05", "45.5". */
    private void showEditPointDialog(boolean isPointA) {
        Context context = getContext();
        if (context == null) {
            return;
        }

        TextInputLayout inputLayout = new TextInputLayout(context);
        inputLayout.setHint("Time (m:ss or m:ss.d, or just seconds)");
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setPadding(
                (int) (16 * getResources().getDisplayMetrics().density),
                (int) (8 * getResources().getDisplayMetrics().density),
                (int) (16 * getResources().getDisplayMetrics().density),
                0);

        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        int currentPoint = isPointA ? (listener != null ? listener.getPointA() : -1)
                                    : (listener != null ? listener.getPointB() : -1);
        if (currentPoint >= 0) {
            input.setText(formatTimePrecise(currentPoint));
            input.setSelection(input.getText() != null ? input.getText().length() : 0);
        }
        inputLayout.addView(input);

        new MaterialAlertDialogBuilder(context)
                .setTitle(isPointA ? "Edit Point A" : "Edit Point B")
                .setView(inputLayout)
                .setPositiveButton("Save", (dialog, which) -> {
                    CharSequence text = input.getText();
                    int positionMs = -1;
                    if (text != null) {
                        positionMs = parseTimeToMs(text.toString());
                    }
                    if (positionMs < 0) {
                        android.widget.Toast.makeText(context,
                                "Invalid time. Use m:ss, m:ss.d or seconds", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (listener != null) {
                        if (isPointA) {
                            listener.onSetPointAAt(positionMs);
                        } else {
                            listener.onSetPointBAt(positionMs);
                        }
                    }
                    refreshPointViews();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Parses a time string into milliseconds. Accepts "ss", "ss.d", "mm:ss(.d)"
     * and "hh:mm:ss(.d)". Returns -1 when the input is invalid.
     */
    private int parseTimeToMs(String input) {
        if (input == null) {
            return -1;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return -1;
        }
        String[] parts = trimmed.split(":");
        if (parts.length > 3) {
            return -1;
        }
        int hours = 0;
        int minutes = 0;
        double seconds;
        try {
            if (parts.length == 1) {
                seconds = Double.parseDouble(parts[0]);
            } else if (parts.length == 2) {
                minutes = Integer.parseInt(parts[0]);
                seconds = Double.parseDouble(parts[1]);
            } else {
                hours = Integer.parseInt(parts[0]);
                minutes = Integer.parseInt(parts[1]);
                seconds = Double.parseDouble(parts[2]);
            }
        } catch (NumberFormatException e) {
            return -1;
        }
        if (hours < 0 || minutes < 0 || seconds < 0 || Double.isNaN(seconds)) {
            return -1;
        }
        double totalSeconds = (hours * 3600d) + (minutes * 60d) + seconds;
        return (int) Math.round(totalSeconds * 1000d);
    }

    private void loadNudgeStep() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        float seconds = prefs.getFloat(KEY_NUDGE_STEP_SECONDS, DEFAULT_NUDGE_STEP_SECONDS);
        if (seconds <= 0f || seconds > 60f) {
            seconds = DEFAULT_NUDGE_STEP_SECONDS;
        }
        applyNudgeStep(seconds);
    }

    private void saveNudgeStep(float seconds) {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putFloat(KEY_NUDGE_STEP_SECONDS, seconds).apply();
        applyNudgeStep(seconds);
    }

    private void applyNudgeStep(float seconds) {
        nudgeStepMs = Math.round(seconds * 1000f);
        setupNudgeButton(nudgeAMinus, -nudgeStepMs, true);
        setupNudgeButton(nudgeAPlus, nudgeStepMs, true);
        setupNudgeButton(nudgeBMinus, -nudgeStepMs, false);
        setupNudgeButton(nudgeBPlus, nudgeStepMs, false);
        String stepLabel = formatStepSeconds(nudgeStepMs) + "s";
        if (nudgeAMinus != null) nudgeAMinus.setContentDescription("Point A minus " + stepLabel);
        if (nudgeAPlus != null) nudgeAPlus.setContentDescription("Point A plus " + stepLabel);
        if (nudgeBMinus != null) nudgeBMinus.setContentDescription("Point B minus " + stepLabel);
        if (nudgeBPlus != null) nudgeBPlus.setContentDescription("Point B plus " + stepLabel);
    }

    private String formatStepSeconds(int stepMs) {
        float seconds = stepMs / 1000f;
        if (seconds == Math.floor(seconds)) {
            return String.valueOf((int) seconds);
        }
        return String.format("%.2f", seconds).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** Refreshes the point labels, status badge and toggle button text. */
    private void refreshPointViews() {
        if (listener == null) {
            return;
        }
        boolean active = listener.isABRepeatActive();
        statusBadge.setText(active ? "ON" : "OFF");
        statusBadge.setTextColor(getResources().getColor(active ? R.color.success : R.color.text_tertiary, null));
        toggleBtn.setText(active ? "Disable" : "Enable");

        int pointA = listener.getPointA();
        int pointB = listener.getPointB();
        // One decimal so fractional fine-tuning steps (e.g. 0.1s) stay visible
        pointAValue.setText(pointA >= 0 ? formatTimePrecise(pointA) : "--:--");
        pointBValue.setText(pointB >= 0 ? formatTimePrecise(pointB) : "--:--");

        syncRangeSlider();
    }

    /** Pulls the current position/duration and updates the time labels and slider. */
    private void refreshProgress() {
        if (listener == null) {
            return;
        }
        syncPlayPauseIcon();
        int position = Math.max(0, listener.getCurrentPosition());
        int duration = Math.max(0, listener.getDuration());
        if (duration > 0) {
            progressSeekbar.setMax(duration);
            if (!userDragging) {
                progressSeekbar.setProgress(Math.min(position, duration));
            }
        }
        if (currentTime != null) {
            currentTime.setText(formatTime(userDragging ? progressSeekbar.getProgress() : position));
        }
        if (totalTime != null) {
            totalTime.setText(duration > 0 ? formatTime(duration) : "--:--");
        }
        // Keep the range handles in sync with any external point changes
        syncRangeSlider();
    }

    private String formatTime(int milliseconds) {
        int seconds = (milliseconds / 1000) % 60;
        int minutes = (milliseconds / (1000 * 60)) % 60;
        int hours = milliseconds / (1000 * 60 * 60);
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    /** Same as formatTime but keeps one decimal, e.g. "1:23.4". */
    private String formatTimePrecise(int milliseconds) {
        String base = formatTime(milliseconds);
        int tenths = (Math.abs(milliseconds) % 1000) / 100;
        return base + "." + tenths;
    }

    @Override
    public int getTheme() {
        return com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog;
    }
}
