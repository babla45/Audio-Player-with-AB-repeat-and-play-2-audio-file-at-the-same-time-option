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

public class SpeedBottomSheet extends BottomSheetDialogFragment {

    public interface SpeedListener {
        void onSpeedChanged(float speed);
        float getCurrentSpeed();
    }

    private SpeedListener listener;

    public void setSpeedListener(SpeedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_speed, container, false);

        TextView currentValue = view.findViewById(R.id.speed_current_value);
        SeekBar seekBar = view.findViewById(R.id.speed_seekbar);

        float currentSpeed = listener != null ? listener.getCurrentSpeed() : 1.0f;
        currentValue.setText(String.format("%.2fx", currentSpeed));

        // SeekBar: 1-16 maps to 0.25x-4.0x (step 0.25)
        int progress = Math.round((currentSpeed - 0.25f) / 0.25f) + 1;
        seekBar.setProgress(Math.max(1, Math.min(16, progress)));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    float speed = 0.25f * progress;
                    speed = Math.max(0.25f, Math.min(4.0f, speed));
                    currentValue.setText(String.format("%.2fx", speed));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float speed = 0.25f * seekBar.getProgress();
                speed = Math.max(0.25f, Math.min(4.0f, speed));
                if (listener != null) {
                    listener.onSpeedChanged(speed);
                }
            }
        });

        // Quick preset chips
        setupChip(view, R.id.speed_chip_050, 0.5f, seekBar, currentValue);
        setupChip(view, R.id.speed_chip_075, 0.75f, seekBar, currentValue);
        setupChip(view, R.id.speed_chip_100, 1.0f, seekBar, currentValue);
        setupChip(view, R.id.speed_chip_125, 1.25f, seekBar, currentValue);
        setupChip(view, R.id.speed_chip_150, 1.5f, seekBar, currentValue);
        setupChip(view, R.id.speed_chip_200, 2.0f, seekBar, currentValue);

        return view;
    }

    private void setupChip(View root, int chipId, float speed, SeekBar seekBar, TextView display) {
        Chip chip = root.findViewById(chipId);
        chip.setOnClickListener(v -> {
            int progress = Math.round((speed - 0.25f) / 0.25f) + 1;
            seekBar.setProgress(progress);
            display.setText(String.format("%.2fx", speed));
            if (listener != null) {
                listener.onSpeedChanged(speed);
            }
        });
    }

    @Override
    public int getTheme() {
        return com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog;
    }
}
