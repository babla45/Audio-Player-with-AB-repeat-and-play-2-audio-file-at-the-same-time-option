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

        float currentBoost = listener != null ? listener.getCurrentBoost() : 1.0f;
        currentValue.setText(String.format("%.2fx", currentBoost));

        int progress = Math.round(currentBoost * 100);
        seekBar.setMin(100);
        seekBar.setMax(500);
        seekBar.setProgress(Math.max(100, Math.min(500, progress)));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float boost = progress / 100.0f;
                currentValue.setText(String.format("%.2fx", boost));
                if (fromUser && listener != null) {
                    listener.onBoostChanged(boost);
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

        return view;
    }

    @Override
    public int getTheme() {
        return com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog;
    }
}
