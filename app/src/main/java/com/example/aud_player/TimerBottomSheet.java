package com.example.aud_player;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;

public class TimerBottomSheet extends BottomSheetDialogFragment {

    public interface TimerListener {
        void onTimerSet(int minutes, int action);
        void onTimerCancel();
    }

    private TimerListener listener;
    private int selectedMinutes = 0;

    public void setTimerListener(TimerListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_timer, container, false);

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

        return view;
    }

    @Override
    public int getTheme() {
        return com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog;
    }
}
