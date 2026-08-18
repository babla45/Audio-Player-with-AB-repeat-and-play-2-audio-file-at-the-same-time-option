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

        // SeekBar: 25-400 maps to 0.25x-4.00x (step 0.01)
        int progress = Math.round(currentSpeed * 100f);
        int min = 25; // 0.25x
        int max = 400; // 4.00x
        try {
            seekBar.setMin(min);
        } catch (NoSuchMethodError ignored) {}
        seekBar.setMax(max);
        seekBar.setProgress(Math.max(min, Math.min(max, progress)));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float speed = progress / 100.0f;
                speed = Math.max(0.25f, Math.min(4.0f, speed));
                currentValue.setText(String.format("%.2fx", speed));
                if (fromUser && listener != null) {
                    listener.onSpeedChanged(speed);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float speed = seekBar.getProgress() / 100.0f;
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
            int progress = Math.round(speed * 100f);
            // Clamp to seekbar bounds
            int max = seekBar.getMax();
            int min = 25;
            try { min = seekBar.getMin(); } catch (NoSuchMethodError ignored) {}
            progress = Math.max(min, Math.min(max, progress));
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
