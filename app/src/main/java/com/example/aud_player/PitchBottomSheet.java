package com.example.aud_player;

import android.app.Dialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

public class PitchBottomSheet extends BottomSheetDialogFragment {

    public interface PitchListener {
        /** Pitch adjustment in semitones, from -12 through +12. */
        void onPitchChanged(float pitch);
        /** Current pitch adjustment in semitones. */
        float getCurrentPitch();
        void onSpeedChanged(float speed);
        float getCurrentSpeed();
        void onFormantChanged(float formant);
        float getCurrentFormant();
        void onBassChanged(int strength);
        int getCurrentBass();
        void onReverbChanged(int level);
        int getCurrentReverb();
        void onTrebleChanged(int strength);
        int getCurrentTreble();
        void onVocalClarityChanged(int strength);
        int getCurrentVocalClarity();
        void onEchoChanged(int level);
        int getCurrentEcho();
        void onDistortionChanged(int strength);
        int getCurrentDistortion();
        void onVibratoChanged(int depth);
        int getCurrentVibrato();
        void onVolumeBoostChanged(float boost);
        float getCurrentVolumeBoost();
        void onVoiceDepthChanged(int strength);
        int getCurrentVoiceDepth();
        void onRobotToggled(boolean enabled);
        boolean isRobotEnabled();
        void onNoiseReductionToggled(boolean enabled);
        boolean isNoiseReductionEnabled();
        void onAutoTuneToggled(boolean enabled);
        boolean isAutoTuneEnabled();
        void onEqPresetSelected(String presetName);
        /** Name of the voice EQ preset currently applied ({@code "None"} when none is). */
        String getCurrentEqPreset();
    }

    private PitchListener listener;

    /**
     * {@link VoicePresetStore#key} of the saved setting that was applied last, so
     * its chip stays highlighted across a list reload (save/delete). Null when
     * nothing was applied from the saved row in this sheet session.
     */
    private String appliedSavedKey;

    /**
     * Height of every chip in this sheet, in dp. Kept equal to the Reset All
     * button's height so the preset rows, the saved row and the reset action all
     * read as one size.
     */
    private static final int COMPACT_CHIP_HEIGHT_DP = 26;

    /** A named combination of voice-changer settings. */
    private static class VoicePreset {
        final String name;
        final float pitchSemitones;
        final float formant;
        final float speed;
        final int bass;      // 0-1000
        final int reverb;    // 0-1000

        VoicePreset(String name, float pitchSemitones, float formant, float speed, int bass, int reverb) {
            this.name = name;
            this.pitchSemitones = pitchSemitones;
            this.formant = formant;
            this.speed = speed;
            this.bass = bass;
            this.reverb = reverb;
        }
    }

    private static final VoicePreset[] VOICE_PRESETS = {
            new VoicePreset("None", 0.0f, 1.0f, 1.0f, 0, 0),
            new VoicePreset("Normal", 0.0f, 1.0f, 1.0f, 0, 0),
            new VoicePreset("Robot", 0.0f, 0.55f, 1.0f, 0, 0),
            new VoicePreset("Monster", -12.0f, 0.5f, 0.85f, 700, 150),
            new VoicePreset("Chipmunk", 12.0f, 1.8f, 1.25f, 0, 0),
            new VoicePreset("Old Man", -6.17f, 0.85f, 0.9f, 100, 0),
            new VoicePreset("Deep Male", -7.46f, 0.7f, 1.0f, 450, 0),
            new VoicePreset("Female", 8.14f, 1.25f, 1.0f, 0, 0),
            new VoicePreset("Child", 12.0f, 1.5f, 1.0f, 0, 0),
            new VoicePreset("Alien", 11.11f, 0.6f, 1.0f, 0, 250),
            new VoicePreset("Cave", -2.81f, 1.0f, 1.0f, 0, 900),
            new VoicePreset("Underwater", -4.98f, 0.8f, 0.85f, 300, 800),
            new VoicePreset("Radio", 1.65f, 0.9f, 1.0f, 0, 0),
            new VoicePreset("Telephone", 3.16f, 0.85f, 1.0f, 0, 0),
            new VoicePreset("Giant", -12.0f, 0.65f, 0.9f, 800, 100),
            new VoicePreset("Kid", 10.65f, 1.4f, 1.05f, 0, 0),
            new VoicePreset("Ghost", -3.86f, 1.1f, 0.8f, 0, 1000),
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

        SeekBar pitchSeekBar = setupSemitoneSlider(
                view.findViewById(R.id.pitch_slider_row),
                getString(R.string.pitch),
                listener != null ? listener.getCurrentPitch() : 0.0f,
                value -> {
                    if (listener != null) {
                        listener.onPitchChanged(value);
                    }
                }
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

        setupSignedPercentSlider(
                view.findViewById(R.id.treble_slider_row),
                getString(R.string.voice_treble),
                listener != null ? listener.getCurrentTreble() : 0,
                value -> {
                    if (listener != null) {
                        listener.onTrebleChanged(value);
                    }
                }
        );

        setupPercentSlider(
                view.findViewById(R.id.clarity_slider_row),
                getString(R.string.voice_clarity),
                listener != null ? listener.getCurrentVocalClarity() : 0,
                0,
                value -> {
                    if (listener != null) {
                        listener.onVocalClarityChanged(value);
                    }
                }
        );

        setupPercentSlider(
                view.findViewById(R.id.echo_slider_row),
                getString(R.string.voice_echo),
                listener != null ? listener.getCurrentEcho() : 0,
                0,
                value -> {
                    if (listener != null) {
                        listener.onEchoChanged(value);
                    }
                }
        );

        setupPercentSlider(
                view.findViewById(R.id.distortion_slider_row),
                getString(R.string.voice_distortion),
                listener != null ? listener.getCurrentDistortion() : 0,
                0,
                value -> {
                    if (listener != null) {
                        listener.onDistortionChanged(value);
                    }
                }
        );

        setupPercentSlider(
                view.findViewById(R.id.vibrato_slider_row),
                getString(R.string.voice_vibrato),
                listener != null ? listener.getCurrentVibrato() : 0,
                0,
                value -> {
                    if (listener != null) {
                        listener.onVibratoChanged(value);
                    }
                }
        );

        setupFactorSlider(
                view.findViewById(R.id.volume_boost_slider_row),
                getString(R.string.voice_volume_boost),
                "1.00x",
                "3.00x",
                100,
                300,
                listener != null ? listener.getCurrentVolumeBoost() : 1.0f,
                1.0f,
                value -> {
                    if (listener != null) {
                        listener.onVolumeBoostChanged(value);
                    }
                },
                value -> String.format("%.2fx", value)
        );

        setupSignedPercentSlider(
                view.findViewById(R.id.depth_slider_row),
                getString(R.string.voice_depth),
                listener != null ? listener.getCurrentVoiceDepth() : 0,
                value -> {
                    if (listener != null) {
                        listener.onVoiceDepthChanged(value);
                    }
                }
        );

        setupToggle(
                view.findViewById(R.id.robot_toggle_row),
                getString(R.string.voice_robot),
                listener != null && listener.isRobotEnabled(),
                value -> {
                    if (listener != null) {
                        listener.onRobotToggled(value);
                    }
                }
        );

        setupToggle(
                view.findViewById(R.id.noise_reduction_toggle_row),
                getString(R.string.voice_noise_reduction),
                listener != null && listener.isNoiseReductionEnabled(),
                value -> {
                    if (listener != null) {
                        listener.onNoiseReductionToggled(value);
                    }
                }
        );

        setupToggle(
                view.findViewById(R.id.autotune_toggle_row),
                getString(R.string.voice_autotune),
                listener != null && listener.isAutoTuneEnabled(),
                value -> {
                    if (listener != null) {
                        listener.onAutoTuneToggled(value);
                    }
                }
        );

        TextView pitchValue = view.findViewById(R.id.pitch_slider_row).findViewById(R.id.voice_slider_value);
        setupChip(view, R.id.pitch_chip_m12, -12.0f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_m9, -9.0f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_m6, -6.0f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_m3, -3.0f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_m2, -2.0f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_0, 0.0f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_p2, 2.0f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_p3, 3.0f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_p6, 6.0f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_p9, 9.0f, pitchSeekBar, pitchValue);
        setupChip(view, R.id.pitch_chip_p12, 12.0f, pitchSeekBar, pitchValue);

        setupVoicePresets(view);

        setupEqPresets(view);

        setupSavedVoiceSettings(view);

        View masterReset = view.findViewById(R.id.voice_master_reset);
        if (masterReset != null) {
            masterReset.setOnClickListener(v -> {
                VoicePreset defaults = VOICE_PRESETS[0]; // "None" — all defaults
                if (listener != null) {
                    listener.onPitchChanged(defaults.pitchSemitones);
                    listener.onFormantChanged(defaults.formant);
                    listener.onSpeedChanged(defaults.speed);
                    listener.onBassChanged(defaults.bass);
                    listener.onReverbChanged(defaults.reverb);
                    listener.onTrebleChanged(0);
                    listener.onVocalClarityChanged(0);
                    listener.onEchoChanged(0);
                    listener.onDistortionChanged(0);
                    listener.onVibratoChanged(0);
                    listener.onVolumeBoostChanged(1.0f);
                    listener.onVoiceDepthChanged(0);
                    listener.onRobotToggled(false);
                    listener.onNoiseReductionToggled(false);
                    listener.onAutoTuneToggled(false);
                    listener.onEqPresetSelected(VoicePresetStore.EQ_NONE);
                }
                syncVoiceSliders(view, defaults);
                syncNewVoiceSliders(view);
                clearChipSelection(view, R.id.voice_preset_row);
                clearChipSelection(view, R.id.eq_preset_row);
                clearChipSelection(view, R.id.voice_saved_row);
                appliedSavedKey = null;
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
            applyCompactChip(chip);
            chip.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPitchChanged(preset.pitchSemitones);
                    listener.onFormantChanged(preset.formant);
                    listener.onSpeedChanged(preset.speed);
                    listener.onBassChanged(preset.bass);
                    listener.onReverbChanged(preset.reverb);
                }
                syncVoiceSliders(view, preset);
                highlightSelectedChip(presetRow, chip);
                // A named preset no longer describes the state, so drop the
                // saved-setting highlight as well
                clearChipSelection(view, R.id.voice_saved_row);
                appliedSavedKey = null;
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
            applyCompactChip(chip);
            chip.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onEqPresetSelected(name);
                }
                highlightSelectedChip(presetRow, chip);
            });
            presetRow.addView(chip);
        }
    }

    /**
     * Wires the header's Save action and builds the chip row of the user's own
     * saved settings. The row lives in a HorizontalScrollView, so a long list
     * scrolls sideways instead of overflowing the sheet. Tapping a chip applies
     * that setting; long-pressing it deletes it.
     */
    private void setupSavedVoiceSettings(View view) {
        View saveButton = view.findViewById(R.id.voice_save_current);
        if (saveButton != null) {
            saveButton.setOnClickListener(v -> promptSaveCurrentVoiceSettings(view));
        }
        refreshSavedVoiceSettings(view);
    }

    /** Rebuilds the saved-setting chips from storage. */
    private void refreshSavedVoiceSettings(View view) {
        LinearLayout row = view.findViewById(R.id.voice_saved_row);
        if (row == null) {
            return;
        }
        row.removeAllViews();

        List<VoicePresetStore.SavedVoicePreset> presets = VoicePresetStore.load(requireContext());

        View emptyView = view.findViewById(R.id.voice_saved_empty);
        if (emptyView != null) {
            emptyView.setVisibility(presets.isEmpty() ? View.VISIBLE : View.GONE);
        }
        View scroll = view.findViewById(R.id.voice_saved_scroll);
        if (scroll != null) {
            scroll.setVisibility(presets.isEmpty() ? View.GONE : View.VISIBLE);
        }

        int margin = (int) (8 * getResources().getDisplayMetrics().density);
        for (VoicePresetStore.SavedVoicePreset preset : presets) {
            // Built exactly like the built-in preset chips above (not checkable,
            // selection driven by highlightSelectedChip), so a saved setting
            // looks and behaves like the presets it sits next to.
            Chip chip = new Chip(requireContext());
            chip.setText(preset.name);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(margin);
            chip.setLayoutParams(lp);
            applyCompactChip(chip);
            chip.setChecked(VoicePresetStore.key(preset).equals(appliedSavedKey));
            chip.setOnClickListener(v -> {
                applySavedVoicePreset(view, preset);
                highlightSelectedChip(row, chip);
            });
            chip.setOnLongClickListener(v -> {
                confirmDeleteSavedVoicePreset(view, preset);
                return true;
            });
            row.addView(chip);
        }
    }

    /** Asks for a name, then stores the settings currently in effect. */
    private void promptSaveCurrentVoiceSettings(View view) {
        View content = getLayoutInflater().inflate(R.layout.dialog_save_voice_setting, null);
        TextInputEditText input = content.findViewById(R.id.saveVoiceNameInput);
        String defaultName = "Setting " + (VoicePresetStore.load(requireContext()).size() + 1);
        input.setText(defaultName);
        // Select based on the field's actual length — the text may be shorter
        // than the default name if anything filtered it
        input.setSelection(input.getText().length());

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Save voice setting")
                .setView(content)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = input.getText() != null ? input.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(name)) {
                        name = defaultName;
                    }
                    saveCurrentVoiceSettings(view, name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveCurrentVoiceSettings(View view, String name) {
        VoicePresetStore.SavedVoicePreset preset = captureCurrentVoiceSettings();
        preset.name = name;
        if (!VoicePresetStore.add(requireContext(), preset)) {
            Toast.makeText(requireContext(),
                    "You can keep up to " + VoicePresetStore.MAX_PRESETS
                            + " settings — delete one first",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        refreshSavedVoiceSettings(view);
        Toast.makeText(requireContext(), "Saved \"" + name + "\"", Toast.LENGTH_SHORT).show();
    }

    /** Snapshots every control in the sheet into a new setting object. */
    private VoicePresetStore.SavedVoicePreset captureCurrentVoiceSettings() {
        VoicePresetStore.SavedVoicePreset preset = new VoicePresetStore.SavedVoicePreset();
        if (listener != null) {
            preset.pitch = listener.getCurrentPitch();
            preset.formant = listener.getCurrentFormant();
            preset.speed = listener.getCurrentSpeed();
            preset.bass = listener.getCurrentBass();
            preset.reverb = listener.getCurrentReverb();
            preset.treble = listener.getCurrentTreble();
            preset.clarity = listener.getCurrentVocalClarity();
            preset.echo = listener.getCurrentEcho();
            preset.distortion = listener.getCurrentDistortion();
            preset.vibrato = listener.getCurrentVibrato();
            preset.volumeBoost = listener.getCurrentVolumeBoost();
            preset.depth = listener.getCurrentVoiceDepth();
            preset.robot = listener.isRobotEnabled();
            preset.noiseReduction = listener.isNoiseReductionEnabled();
            preset.autoTune = listener.isAutoTuneEnabled();
            preset.eqPreset = listener.getCurrentEqPreset();
        }
        return preset;
    }

    /** Applies a saved setting to playback and mirrors it onto every control. */
    private void applySavedVoicePreset(View view, VoicePresetStore.SavedVoicePreset preset) {
        if (preset == null) {
            return;
        }

        if (listener != null) {
            listener.onPitchChanged(preset.pitch);
            listener.onFormantChanged(preset.formant);
            listener.onSpeedChanged(preset.speed);
            listener.onBassChanged(preset.bass);
            listener.onReverbChanged(preset.reverb);
            listener.onTrebleChanged(preset.treble);
            listener.onVocalClarityChanged(preset.clarity);
            listener.onEchoChanged(preset.echo);
            listener.onDistortionChanged(preset.distortion);
            listener.onVibratoChanged(preset.vibrato);
            listener.onVolumeBoostChanged(preset.volumeBoost);
            listener.onVoiceDepthChanged(preset.depth);
            listener.onRobotToggled(preset.robot);
            listener.onNoiseReductionToggled(preset.noiseReduction);
            listener.onAutoTuneToggled(preset.autoTune);
            listener.onEqPresetSelected(preset.eqPreset);
        }

        // Mirror the values onto the controls. Programmatic setProgress fires
        // onProgressChanged with fromUser=false, so nothing is applied twice.
        setSliderProgress(view, R.id.pitch_slider_row, preset.pitch);
        setSliderProgress(view, R.id.formant_slider_row, preset.formant);
        setSliderProgress(view, R.id.speed_slider_row, preset.speed);
        setSliderProgress(view, R.id.bass_slider_row, preset.bass);
        setSliderProgress(view, R.id.reverb_slider_row, preset.reverb);
        syncSignedSliderRow(view, R.id.treble_slider_row, preset.treble);
        syncPercentSliderRow(view, R.id.clarity_slider_row, preset.clarity);
        syncPercentSliderRow(view, R.id.echo_slider_row, preset.echo);
        syncPercentSliderRow(view, R.id.distortion_slider_row, preset.distortion);
        syncPercentSliderRow(view, R.id.vibrato_slider_row, preset.vibrato);
        syncFactorSliderRow(view, R.id.volume_boost_slider_row, preset.volumeBoost);
        syncSignedSliderRow(view, R.id.depth_slider_row, preset.depth);
        setToggleChecked(view, R.id.robot_toggle_row, preset.robot);
        setToggleChecked(view, R.id.noise_reduction_toggle_row, preset.noiseReduction);
        setToggleChecked(view, R.id.autotune_toggle_row, preset.autoTune);

        // The built-in preset chips (and the previous EQ chip) no longer
        // describe the state, so drop their highlight; the EQ chip that was
        // saved takes the highlight instead.
        clearChipSelection(view, R.id.voice_preset_row);
        highlightChipByText(view, R.id.eq_preset_row, preset.eqPreset);

        appliedSavedKey = VoicePresetStore.key(preset);
    }

    private void confirmDeleteSavedVoicePreset(View view, VoicePresetStore.SavedVoicePreset preset) {
        String name = TextUtils.isEmpty(preset.name) ? "this setting" : "\"" + preset.name + "\"";
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete setting?")
                .setMessage("Delete " + name + "? This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    VoicePresetStore.delete(requireContext(), preset);
                    if (VoicePresetStore.key(preset).equals(appliedSavedKey)) {
                        appliedSavedKey = null;
                    }
                    refreshSavedVoiceSettings(view);
                    Toast.makeText(requireContext(), "Setting deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Checks the preset chip whose text matches, if the row has one. */
    private void highlightChipByText(View view, int rowId, String text) {
        LinearLayout row = view.findViewById(rowId);
        if (row == null) {
            return;
        }
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof Chip && TextUtils.equals(((Chip) child).getText(), text)) {
                highlightSelectedChip(row, (Chip) child);
                return;
            }
        }
    }

    /** Updates the slider UI to reflect the preset values. */
    private void syncVoiceSliders(View view, VoicePreset preset) {
        setSliderProgress(view, R.id.pitch_slider_row, preset.pitchSemitones);
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
        if (rowId == R.id.pitch_slider_row) {
            float semitones = PitchProcessor.normalizeSemitones(value);
            progress = Math.round((semitones - PitchProcessor.MIN_SEMITONES) * 10.0f);
            valueView.setText(formatSemitones(semitones));
        } else if (min == 0 && max >= 1000) {
            // Percent rows (Bass/Reverb) use per-mille values.
            progress = (int) value;
            valueView.setText(formatPercent((int) value));
        } else {
            // Factor rows (Formant/Speed) use 100 progress units per factor.
            progress = Math.round(value * 100f);
            valueView.setText(String.format("%.2fx", value));
        }
        seekBar.setProgress(Math.max(min, Math.min(max, progress)));
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

    private interface BoolValueListener {
        void onValueChanged(boolean value);
    }

    private void setupToggle(View row, String label, boolean checked, BoolValueListener changeListener) {
        if (row == null) {
            return;
        }
        TextView labelView = row.findViewById(R.id.voice_toggle_label);
        SwitchCompat toggle = row.findViewById(R.id.voice_toggle_switch);
        if (labelView != null) {
            labelView.setText(label);
        }
        if (toggle != null) {
            toggle.setChecked(checked);
            toggle.setOnCheckedChangeListener((buttonView, isChecked) ->
                    changeListener.onValueChanged(isChecked));
        }
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

    private SeekBar setupSemitoneSlider(
            View row,
            String label,
            float currentSemitones,
            FloatValueListener changeListener) {
        TextView labelView = row.findViewById(R.id.voice_slider_label);
        TextView valueView = row.findViewById(R.id.voice_slider_value);
        TextView minView = row.findViewById(R.id.voice_slider_min);
        TextView maxView = row.findViewById(R.id.voice_slider_max);
        SeekBar seekBar = row.findViewById(R.id.voice_slider_seekbar);
        ImageView decreaseButton = row.findViewById(R.id.pitch_decrease_button);
        ImageView increaseButton = row.findViewById(R.id.pitch_increase_button);

        labelView.setText(label);
        minView.setText("-12 st");
        maxView.setText("+12 st");
        seekBar.setMin(0);
        seekBar.setMax(240);
        float initial = PitchProcessor.normalizeSemitones(currentSemitones);
        valueView.setText(formatSemitones(initial));
        seekBar.setProgress(Math.round((initial - PitchProcessor.MIN_SEMITONES) * 10.0f));
        decreaseButton.setVisibility(View.VISIBLE);
        increaseButton.setVisibility(View.VISIBLE);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float semitones = progress / 10.0f + PitchProcessor.MIN_SEMITONES;
                valueView.setText(formatSemitones(semitones));
                if (fromUser) {
                    changeListener.onValueChanged(semitones);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                changeListener.onValueChanged(
                        bar.getProgress() / 10.0f + PitchProcessor.MIN_SEMITONES);
            }
        });

        decreaseButton.setOnClickListener(v -> adjustSemitonePitch(seekBar, -1, changeListener));
        increaseButton.setOnClickListener(v -> adjustSemitonePitch(seekBar, 1, changeListener));

        View resetButton = row.findViewById(R.id.voice_slider_reset);
        if (resetButton != null) {
            resetButton.setOnClickListener(v -> {
                seekBar.setProgress(120);
                valueView.setText(formatSemitones(0.0f));
                changeListener.onValueChanged(0.0f);
            });
        }
        return seekBar;
    }

    private void adjustSemitonePitch(
            SeekBar seekBar, int progressDelta, FloatValueListener changeListener) {
        int nextProgress = Math.max(seekBar.getMin(),
                Math.min(seekBar.getMax(), seekBar.getProgress() + progressDelta));
        seekBar.setProgress(nextProgress);
        changeListener.onValueChanged(
                nextProgress / 10.0f + PitchProcessor.MIN_SEMITONES);
    }

    private static String formatSemitones(float semitones) {
        return String.format("%+.1f st", semitones);
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

    /**
     * Slider for effects with a signed range (-100%..+100%). Internally the value is
     * stored in per-mille units (-1000..1000, matching the 0..1000 percent sliders);
     * the seek bar maps 0..2000 with 1000 as the neutral center.
     */
    private void setupSignedPercentSlider(
            View row,
            String label,
            int currentValue,
            IntValueListener changeListener) {

        TextView labelView = row.findViewById(R.id.voice_slider_label);
        TextView valueView = row.findViewById(R.id.voice_slider_value);
        TextView minView = row.findViewById(R.id.voice_slider_min);
        TextView maxView = row.findViewById(R.id.voice_slider_max);
        SeekBar seekBar = row.findViewById(R.id.voice_slider_seekbar);

        labelView.setText(label);
        minView.setText("-100%");
        maxView.setText("+100%");
        int clamped = Math.max(-1000, Math.min(1000, currentValue));
        valueView.setText(formatSignedPercent(clamped));
        seekBar.setMin(0);
        seekBar.setMax(2000);
        seekBar.setProgress(clamped + 1000);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = progress - 1000;
                valueView.setText(formatSignedPercent(value));
                if (fromUser) {
                    changeListener.onValueChanged(value);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                changeListener.onValueChanged(bar.getProgress() - 1000);
            }
        });

        View resetButton = row.findViewById(R.id.voice_slider_reset);
        if (resetButton != null) {
            resetButton.setOnClickListener(v -> {
                seekBar.setProgress(1000);
                valueView.setText(formatSignedPercent(0));
                changeListener.onValueChanged(0);
            });
        }
    }

    private String formatSignedPercent(int strength) {
        int percent = Math.round(strength / 10f);
        if (percent == 0) {
            return "0%";
        }
        return (percent > 0 ? "+" : "") + percent + "%";
    }

    /** Updates the extra voice sliders (treble/clarity/echo/distortion/vibrato/depth/boost) to defaults. */
    private void syncNewVoiceSliders(View view) {
        syncPercentSliderRow(view, R.id.clarity_slider_row, 0);
        syncPercentSliderRow(view, R.id.echo_slider_row, 0);
        syncPercentSliderRow(view, R.id.distortion_slider_row, 0);
        syncPercentSliderRow(view, R.id.vibrato_slider_row, 0);
        syncSignedSliderRow(view, R.id.treble_slider_row, 0);
        syncSignedSliderRow(view, R.id.depth_slider_row, 0);
        syncFactorSliderRow(view, R.id.volume_boost_slider_row, 1.0f);
        setToggleChecked(view, R.id.robot_toggle_row, false);
        setToggleChecked(view, R.id.noise_reduction_toggle_row, false);
        setToggleChecked(view, R.id.autotune_toggle_row, false);
    }

    private void syncSignedSliderRow(View view, int rowId, int value) {
        View row = view.findViewById(rowId);
        if (row == null) {
            return;
        }
        SeekBar seekBar = row.findViewById(R.id.voice_slider_seekbar);
        TextView valueView = row.findViewById(R.id.voice_slider_value);
        if (seekBar != null) {
            seekBar.setProgress(Math.max(0, Math.min(2000, value + 1000)));
        }
        if (valueView != null) {
            valueView.setText(formatSignedPercent(value));
        }
    }

    private void syncFactorSliderRow(View view, int rowId, float value) {
        View row = view.findViewById(rowId);
        if (row == null) {
            return;
        }
        SeekBar seekBar = row.findViewById(R.id.voice_slider_seekbar);
        TextView valueView = row.findViewById(R.id.voice_slider_value);
        if (seekBar != null) {
            seekBar.setProgress(Math.max(100, Math.min(300, Math.round(value * 100f))));
        }
        if (valueView != null) {
            valueView.setText(String.format("%.2fx", value));
        }
    }

    private void setToggleChecked(View view, int rowId, boolean checked) {
        View row = view.findViewById(rowId);
        if (row == null) {
            return;
        }
        SwitchCompat toggle = row.findViewById(R.id.voice_toggle_switch);
        if (toggle != null) {
            toggle.setChecked(checked);
        }
    }

    private void syncPercentSliderRow(View view, int rowId, int value) {
        View row = view.findViewById(rowId);
        if (row == null) {
            return;
        }
        SeekBar seekBar = row.findViewById(R.id.voice_slider_seekbar);
        TextView valueView = row.findViewById(R.id.voice_slider_value);
        if (seekBar != null) {
            seekBar.setProgress(Math.max(0, Math.min(1000, value)));
        }
        if (valueView != null) {
            valueView.setText(formatPercent(value));
        }
    }


    private void setupChip(View root, int chipId, float pitchSemitones, SeekBar seekBar, TextView display) {
        Chip chip = root.findViewById(chipId);
        if (chip == null) {
            return;
        }
        applyCompactChip(chip);
        chip.setOnClickListener(v -> {
            int progress = Math.round((pitchSemitones - PitchProcessor.MIN_SEMITONES) * 10.0f);
            seekBar.setProgress(progress);
            display.setText(formatSemitones(pitchSemitones));
            if (listener != null) {
                listener.onPitchChanged(pitchSemitones);
            }
        });
    }

    /**
     * Sizes a chip to the density of this sheet. Material chips reserve a 48dp
     * touch target, a 32dp minimum height and 16dp of padding at each end, which
     * makes the preset rows read as oversized buttons. The view height is pinned
     * as well as the minimum, because a chip otherwise stretches to its own text
     * height instead of matching the Reset All button next to it. Call it after
     * the chip has its layout params, so the pinned height survives.
     */
    private void applyCompactChip(Chip chip) {
        float density = getResources().getDisplayMetrics().density;
        int heightPx = Math.round(COMPACT_CHIP_HEIGHT_DP * density);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipMinHeight(heightPx);
        chip.setMinHeight(heightPx);
        chip.setChipStartPadding(10 * density);
        chip.setChipEndPadding(10 * density);
        chip.setTextStartPadding(0f);
        chip.setTextEndPadding(0f);
        chip.setTextSize(12f);

        // Material pads the chip out to a 48dp touch target. With the height
        // pinned that padding survives inside the shorter view and pushes the
        // label past the bottom edge, so drop the vertical part of it. The
        // horizontal padding is kept so the label stays clear of the sides.
        chip.setPadding(chip.getPaddingLeft(), 0, chip.getPaddingRight(), 0);

        ViewGroup.LayoutParams params = chip.getLayoutParams();
        if (params != null) {
            params.height = heightPx;
            chip.setLayoutParams(params);
        }
    }

    @Override
    public int getTheme() {
        return com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (!(dialog instanceof BottomSheetDialog)) {
            return;
        }
        BottomSheetBehavior<?> behavior = ((BottomSheetDialog) dialog).getBehavior();
        behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState != BottomSheetBehavior.STATE_COLLAPSED
                        && newState != BottomSheetBehavior.STATE_HALF_EXPANDED) {
                    return;
                }
                // The sheet was slid back down: scroll the content back to the top
                // so the upper controls (Pitch slider, chips) are visible again.
                View root = getView();
                if (root == null) {
                    return;
                }
                View scroll = root.findViewById(R.id.voice_controls_scroll);
                if (scroll instanceof NestedScrollView) {
                    scroll.post(() -> ((NestedScrollView) scroll).smoothScrollTo(0, 0));
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
        });
    }
}
