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

public class BoostBottomSheet extends BottomSheetDialogFragment {

    public interface BoostListener {
        void onBoostChanged(float boost);
        float getCurrentBoost();
    }

    private BoostListener listener;

    public void setBoostListener(BoostListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_boost, container, false);

        TextView currentValue = view.findViewById(R.id.boost_current_value);
        SeekBar seekBar = view.findViewById(R.id.boost_seekbar);

        // B027 FIX: Removed chips above 3.0x to match shared boost range.
        com.google.android.material.chip.Chip c150 = view.findViewById(R.id.boost_chip_150);
        com.google.android.material.chip.Chip c200 = view.findViewById(R.id.boost_chip_200);
        com.google.android.material.chip.Chip c250 = view.findViewById(R.id.boost_chip_250);
        com.google.android.material.chip.Chip c300 = view.findViewById(R.id.boost_chip_300);

        com.google.android.material.chip.Chip[] chips = new com.google.android.material.chip.Chip[] { c150, c200, c250, c300 };

        // Hide chips above the shared BOOST_MAX (3.0x)
        int[] hideChipIds = { R.id.boost_chip_350, R.id.boost_chip_400, R.id.boost_chip_450, R.id.boost_chip_500 };
        for (int id : hideChipIds) {
            View chipView = view.findViewById(id);
            if (chipView != null) chipView.setVisibility(View.GONE);
        }

        // B027 FIX: Use shared BOOST_MIN/BOOST_MAX constants (matching
        // MainActivity and AudioPlaybackService) so all UIs stay in sync.
        int seekMin = Math.round(MainActivity.BOOST_MIN * 100);
        int seekMax = Math.round(MainActivity.BOOST_MAX * 100);
        float currentBoost = listener != null ? listener.getCurrentBoost() : 1.0f;
        currentValue.setText(String.format("%.2fx", currentBoost));

        int progress = Math.round(currentBoost * 100);
        seekBar.setMin(seekMin);
        seekBar.setMax(seekMax);
        seekBar.setProgress(Math.max(seekMin, Math.min(seekMax, progress)));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float boost = progress / 100.0f;
                currentValue.setText(String.format("%.2fx", boost));
                if (fromUser && listener != null) {
                    listener.onBoostChanged(boost);
                }

                // update chip selection
                for (com.google.android.material.chip.Chip ch : chips) {
                    if (ch == null) continue;
                    float val = Float.parseFloat(ch.getText().toString().replace("x", ""));
                    ch.setChecked(Math.abs(val - boost) < 0.01f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float boost = seekBar.getProgress() / 100.0f;
                if (listener != null) listener.onBoostChanged(boost);
            }
        });

        // Chip click listeners: set seekbar and notify
        android.view.View.OnClickListener chipClick = v -> {
            if (!(v instanceof com.google.android.material.chip.Chip)) return;
            com.google.android.material.chip.Chip ch = (com.google.android.material.chip.Chip) v;
            try {
                float val = Float.parseFloat(ch.getText().toString().replace("x", ""));
                int p = Math.round(val * 100);
                seekBar.setProgress(p);
                currentValue.setText(String.format("%.2fx", val));
                if (listener != null) listener.onBoostChanged(val);
                // update visuals
                for (com.google.android.material.chip.Chip c : chips) if (c != null) c.setChecked(c == ch);
            } catch (Exception ignored) {}
        };

        for (com.google.android.material.chip.Chip ch : chips) {
            if (ch == null) continue;
            ch.setOnClickListener(chipClick);
        }

        // Initialize chip states based on current boost
        for (com.google.android.material.chip.Chip ch : chips) {
            if (ch == null) continue;
            try {
                float val = Float.parseFloat(ch.getText().toString().replace("x", ""));
                ch.setChecked(Math.abs(val - currentBoost) < 0.01f);
            } catch (Exception ignored) {}
        }

        return view;
    }

    @Override
    public int getTheme() {
        return com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog;
    }
}
