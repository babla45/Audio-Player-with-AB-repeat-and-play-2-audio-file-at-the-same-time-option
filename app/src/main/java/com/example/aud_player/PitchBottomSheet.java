package com.example.aud_player;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;

public class PitchBottomSheet extends BottomSheetDialogFragment {

    public interface PitchListener {
        void onPitchChanged(float pitch);
        float getCurrentPitch();
    }

    private PitchListener listener;

    public void setPitchListener(PitchListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_pitch, container, false);

        TextView currentValue = view.findViewById(R.id.pitch_current_value);
        SeekBar seekBar = view.findViewById(R.id.pitch_seekbar);

        float currentPitch = listener != null ? listener.getCurrentPitch() : 1.0f;
        currentValue.setText(String.format("%.2fx", currentPitch));

        // SeekBar: 5-400 maps to 0.05x - 4.00x (step 0.01)
        int progress = Math.round(currentPitch * 100);
        seekBar.setMin(5);
        seekBar.setMax(400);
        seekBar.setProgress(Math.max(5, Math.min(400, progress)));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float pitch = progress / 100.0f;
                currentValue.setText(String.format("%.2fx", pitch));
                if (fromUser && listener != null) {
                    listener.onPitchChanged(pitch);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float pitch = seekBar.getProgress() / 100.0f;
                if (listener != null) {
                    listener.onPitchChanged(pitch);
                }
            }
        });

        // Quick preset chips
        setupChip(view, R.id.pitch_chip_025, 0.25f, seekBar, currentValue);
        setupChip(view, R.id.pitch_chip_050, 0.5f, seekBar, currentValue);
        setupChip(view, R.id.pitch_chip_075, 0.75f, seekBar, currentValue);
        setupChip(view, R.id.pitch_chip_085, 0.85f, seekBar, currentValue);
        setupChip(view, R.id.pitch_chip_100, 1.0f, seekBar, currentValue);
        setupChip(view, R.id.pitch_chip_125, 1.25f, seekBar, currentValue);
        setupChip(view, R.id.pitch_chip_150, 1.5f, seekBar, currentValue);
        setupChip(view, R.id.pitch_chip_200, 2.0f, seekBar, currentValue);
        setupChip(view, R.id.pitch_chip_250, 2.5f, seekBar, currentValue);
        setupChip(view, R.id.pitch_chip_300, 3.0f, seekBar, currentValue);
        setupChip(view, R.id.pitch_chip_400, 4.0f, seekBar, currentValue);


        return view;
    }

    private void setupChip(View root, int chipId, float pitch, SeekBar seekBar, TextView display) {
        Chip chip = root.findViewById(chipId);
        if (chip == null) return;
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
