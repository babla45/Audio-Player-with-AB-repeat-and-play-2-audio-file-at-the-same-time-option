package com.example.aud_player;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;

public class PitchBottomSheet extends BottomSheetDialogFragment {

    public interface PitchListener {
        void onPitchChanged(float pitch);
        float getCurrentPitch();
        void onSpeedChanged(float speed);
        float getCurrentSpeed();
        void onFormantChanged(float formant);
        float getCurrentFormant();
        void onBassChanged(int strength);
        int getCurrentBass();
        void onReverbChanged(int level);
        int getCurrentReverb();
        void onEqPresetSelected(String presetName);
    }

    private PitchListener listener;

    /** A named combination of voice-changer settings. */
    private static class VoicePreset {
        final String name;
        final float pitch;
        final float formant;
        final float speed;
        final int bass;      // 0-1000
        final int reverb;    // 0-1000

        VoicePreset(String name, float pitch, float formant, float speed, int bass, int reverb) {
            this.name = name;
            this.pitch = pitch;
            this.formant = formant;
            this.speed = speed;
            this.bass = bass;
            this.reverb = reverb;
        }
    }

    private static final VoicePreset[] VOICE_PRESETS = {
            new VoicePreset("None", 1.0f, 1.0f, 1.0f, 0, 0),
            new VoicePreset("Normal", 1.0f, 1.0f, 1.0f, 0, 0),
            new VoicePreset("Robot", 1.0f, 0.55f, 1.0f, 0, 0),
            new VoicePreset("Monster", 0.45f, 0.5f, 0.85f, 700, 150),
            new VoicePreset("Chipmunk", 2.5f, 1.8f, 1.25f, 0, 0),
            new VoicePreset("Old Man", 0.7f, 0.85f, 0.9f, 100, 0),
            new VoicePreset("Deep Male", 0.65f, 0.7f, 1.0f, 450, 0),
            new VoicePreset("Female", 1.6f, 1.25f, 1.0f, 0, 0),
            new VoicePreset("Child", 2.0f, 1.5f, 1.0f, 0, 0),
            new VoicePreset("Alien", 1.9f, 0.6f, 1.0f, 0, 250),
            new VoicePreset("Cave", 0.85f, 1.0f, 1.0f, 0, 900),
            new VoicePreset("Underwater", 0.75f, 0.8f, 0.85f, 300, 800),
            new VoicePreset("Radio", 1.1f, 0.9f, 1.0f, 0, 0),
            new VoicePreset("Telephone", 1.2f, 0.85f, 1.0f, 0, 0),
            new VoicePreset("Giant", 0.5f, 0.65f, 0.9f, 800, 100),
            new VoicePreset("Kid", 1.85f, 1.4f, 1.05f, 0, 0),
            new VoicePreset("Ghost", 0.8f, 1.1f, 0.8f, 0, 1000),
    };

    private static final String[] EQ_PRESETS = {
            "None", "Male", "Female", "Child", "Deep", "Radio", "Flat"
    };

    public void setPitchListener(PitchListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_pitch, container, false);

        SeekBar pitchSeekBar = setupFactorSlider(
                view.findViewById(R.id.pitch_slider_row),
                getString(R.string.pitch),
                "0.05x",
                "4.00x",
                5,
                400,
                listener != null ? listener.getCurrentPitch() : 1.0f,
                1.0f,
                value -> {
                    if (listener != null) {
                        listener.onPitchChanged(value);
                    }
                },
                value -> String.format("%.2fx", value)
        );

        setupFactorSlider(
                view.findViewById(R.id.formant_slider_row),
                getString(R.string.voice_formant),
                "0.50x",
                "2.00x",
                50,
                200,
                listener != null ? listener.getCurrentFormant() : 1.0f,
                1.0f,
                value -> {
                    if (listener != null) {
                        listener.onFormantChanged(value);
                    }
                },
                value -> String.format("%.2fx", value)
        );

        setupFactorSlider(
                view.findViewById(R.id.speed_slider_row),
                getString(R.string.voice_speed),
                "0.25x",
                "4.00x",
                25,
                400,
                listener != null ? listener.getCurrentSpeed() : 1.0f,
                1.0f,
                value -> {
                    if (listener != null) {
                        listener.onSpeedChanged(value);
                    }
                },
                value -> String.format("%.2fx", value)
        );

        setupPercentSlider(
                view.findViewById(R.id.bass_slider_row),
                getString(R.string.voice_bass),
                listener != null ? listener.getCurrentBass() : 0,
                0,
                value -> {
                    if (listener != null) {
                        listener.onBassChanged(value);
                    }
                }
        );

        setupPercentSlider(
                view.findViewById(R.id.reverb_slider_row),
                getString(R.string.voice_reverb),
                listener != null ? listener.getCurrentReverb() : 0,
                0,
                value -> {
                    if (listener != null) {
                        listener.onReverbChanged(value);
                    }
                }
        );

        TextView pitchValue = view.findViewById(R.id.pitch_slider_row).findViewById(R.id.voice_slider_value);
        setupChip(view, R.id.pitch_chip_025, 0.25f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_050, 0.5f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_075, 0.75f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_085, 0.85f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_100, 1.0f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_125, 1.25f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_150, 1.5f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_200, 2.0f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_250, 2.5f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_300, 3.0f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_400, 4.0f, pitchSeekBar, pitchValue);

        setupVoicePresets(view);

        setupEqPresets(view);

        View masterReset = view.findViewById(R.id.voice_master_reset);
        if (masterReset != null) {
            masterReset.setOnClickListener(v -> {
                VoicePreset defaults = VOICE_PRESETS[0]; // "None" — all defaults
                if (listener != null) {
                    listener.onPitchChanged(defaults.pitch);
                    listener.onFormantChanged(defaults.formant);
                    listener.onSpeedChanged(defaults.speed);
                    listener.onBassChanged(defaults.bass);
                    listener.onReverbChanged(defaults.reverb);
                    listener.onEqPresetSelected("None");
                }
                syncVoiceSliders(view, defaults);
                clearChipSelection(view, R.id.voice_preset_row);
                clearChipSelection(view, R.id.eq_preset_row);
            });
        }

        return view;
    }

    private void clearChipSelection(View view, int rowId) {
        LinearLayout presetRow = view.findViewById(rowId);
        if (presetRow == null) {
            return;
        }
        for (int i = 0; i < presetRow.getChildCount(); i++) {
            View child = presetRow.getChildAt(i);
            if (child instanceof Chip) {
                ((Chip) child).setChecked(false);
            }
        }
    }

    private void setupVoicePresets(View view) {
        LinearLayout presetRow = view.findViewById(R.id.voice_preset_row);
        if (presetRow == null) {
            return;
        }

        for (VoicePreset preset : VOICE_PRESETS) {
            Chip chip = new Chip(requireContext());
            chip.setText(preset.name);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (8 * getResources().getDisplayMetrics().density));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPitchChanged(preset.pitch);
                    listener.onFormantChanged(preset.formant);
                    listener.onSpeedChanged(preset.speed);
                    listener.onBassChanged(preset.bass);
                    listener.onReverbChanged(preset.reverb);
                }
                syncVoiceSliders(view, preset);
                highlightSelectedChip(presetRow, chip);
            });
            presetRow.addView(chip);
        }
    }

    private void setupEqPresets(View view) {
        LinearLayout presetRow = view.findViewById(R.id.eq_preset_row);
        if (presetRow == null) {
            return;
        }

        for (String name : EQ_PRESETS) {
            Chip chip = new Chip(requireContext());
            chip.setText(name);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (8 * getResources().getDisplayMetrics().density));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onEqPresetSelected(name);
                }
                highlightSelectedChip(presetRow, chip);
            });
            presetRow.addView(chip);
        }
    }

    /** Updates the slider UI to reflect the preset values. */
    private void syncVoiceSliders(View view, VoicePreset preset) {
        setSliderProgress(view, R.id.pitch_slider_row, preset.pitch);
        setSliderProgress(view, R.id.formant_slider_row, preset.formant);
        setSliderProgress(view, R.id.speed_slider_row, preset.speed);
        setSliderProgress(view, R.id.bass_slider_row, preset.bass);
        setSliderProgress(view, R.id.reverb_slider_row, preset.reverb);
    }

    private void setSliderProgress(View view, int rowId, float value) {
        View row = view.findViewById(rowId);
        if (row == null) {
            return;
        }
        SeekBar seekBar = row.findViewById(R.id.voice_slider_seekbar);
        TextView valueView = row.findViewById(R.id.voice_slider_value);
        if (seekBar == null || valueView == null) {
            return;
        }
        int progress;
        int min;
        int max;
        try {
            min = seekBar.getMin();
        } catch (NoSuchMethodError e) {
            min = 0;
        }
        max = seekBar.getMax();
        if (max >= 100) {
            progress = Math.round(value * 100f);
        } else {
            progress = (int) value;
        }
        seekBar.setProgress(Math.max(min, Math.min(max, progress)));
        valueView.setText(max >= 100 ? String.format("%.2fx", value) : formatPercent((int) value));
    }

    private void highlightSelectedChip(LinearLayout presetRow, Chip selected) {
        for (int i = 0; i < presetRow.getChildCount(); i++) {
            View child = presetRow.getChildAt(i);
            if (child instanceof Chip) {
                ((Chip) child).setChecked(child == selected);
            }
        }
    }

    private interface ValueFormatter {
        String format(float value);
    }

    private interface FloatValueListener {
        void onValueChanged(float value);
    }

    private interface IntValueListener {
        void onValueChanged(int value);
    }

    private SeekBar setupFactorSlider(
            View row,
            String label,
            String minLabel,
            String maxLabel,
            int minProgress,
            int maxProgress,
            float currentValue,
            float defaultValue,
            FloatValueListener changeListener,
            ValueFormatter formatter) {

        TextView labelView = row.findViewById(R.id.voice_slider_label);
        TextView valueView = row.findViewById(R.id.voice_slider_value);
        TextView minView = row.findViewById(R.id.voice_slider_min);
        TextView maxView = row.findViewById(R.id.voice_slider_max);
        SeekBar seekBar = row.findViewById(R.id.voice_slider_seekbar);

        labelView.setText(label);
        minView.setText(minLabel);
        maxView.setText(maxLabel);
        valueView.setText(formatter.format(currentValue));

        seekBar.setMin(minProgress);
        seekBar.setMax(maxProgress);
        int progress = Math.round(currentValue * 100f);
        seekBar.setProgress(Math.max(minProgress, Math.min(maxProgress, progress)));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float value = progress / 100.0f;
                valueView.setText(formatter.format(value));
                if (fromUser) {
                    changeListener.onValueChanged(value);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                changeListener.onValueChanged(bar.getProgress() / 100.0f);
            }
        });

        View resetButton = row.findViewById(R.id.voice_slider_reset);
        if (resetButton != null) {
            resetButton.setOnClickListener(v -> {
                int defaultProgress = Math.round(defaultValue * 100f);
                seekBar.setProgress(Math.max(minProgress, Math.min(maxProgress, defaultProgress)));
                valueView.setText(formatter.format(defaultValue));
                changeListener.onValueChanged(defaultValue);
            });
        }

        return seekBar;
    }

    private void setupPercentSlider(
            View row,
            String label,
            int currentValue,
            int defaultValue,
            IntValueListener changeListener) {

        TextView labelView = row.findViewById(R.id.voice_slider_label);
        TextView valueView = row.findViewById(R.id.voice_slider_value);
        TextView minView = row.findViewById(R.id.voice_slider_min);
        TextView maxView = row.findViewById(R.id.voice_slider_max);
        SeekBar seekBar = row.findViewById(R.id.voice_slider_seekbar);

        labelView.setText(label);
        minView.setText("0%");
        maxView.setText("100%");
        int clamped = Math.max(0, Math.min(1000, currentValue));
        valueView.setText(formatPercent(clamped));
        seekBar.setMin(0);
        seekBar.setMax(1000);
        seekBar.setProgress(clamped);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                valueView.setText(formatPercent(progress));
                if (fromUser) {
                    changeListener.onValueChanged(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                changeListener.onValueChanged(bar.getProgress());
            }
        });

        View resetButton = row.findViewById(R.id.voice_slider_reset);
        if (resetButton != null) {
            int clampedDefault = Math.max(0, Math.min(1000, defaultValue));
            resetButton.setOnClickListener(v -> {
                seekBar.setProgress(clampedDefault);
                valueView.setText(formatPercent(clampedDefault));
                changeListener.onValueChanged(clampedDefault);
            });
        }
    }

    private String formatPercent(int strength) {
        return Math.round(strength / 10f) + "%";
    }

    private void setupChip(View root, int chipId, float pitch, SeekBar seekBar, TextView display) {
        Chip chip = root.findViewById(chipId);
        if (chip == null) {
            return;
        }
        chip.setOnClickListener(v -> {
            int progress = Math.round(pitch * 100);
            seekBar.setProgress(progress);
            display.setText(String.format("%.2fx", pitch));
            if (listener != null) {
                listener.onPitchChanged(pitch);
            }
        });
    }

    @Override
    public int getTheme() {
        return com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog;
    }
}
