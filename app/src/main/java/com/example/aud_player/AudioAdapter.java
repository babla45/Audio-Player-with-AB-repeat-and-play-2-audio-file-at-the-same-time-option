package com.example.aud_player;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class AudioAdapter extends RecyclerView.Adapter<AudioAdapter.AudioViewHolder> {

    private List<AudioFile> audioFiles;
    private OnItemClickListener listener;

    public AudioAdapter(List<AudioFile> audioFiles) {
        this.audioFiles = audioFiles;
    }

    public interface OnItemClickListener {
        void onItemClick(AudioFile audioFile);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public AudioViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_audio_file, parent, false);
        return new AudioViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AudioViewHolder holder, int position) {
        AudioFile audioFile = audioFiles.get(position);
        holder.titleTextView.setText(audioFile.getTitle());
        
        // Combine duration and file size with a separator
        String durationAndSize = audioFile.getDuration() + " • " + audioFile.getFormattedSize();
        holder.durationTextView.setText(durationAndSize);
        
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(audioFile);
            }
        });
    }

    @Override
    public int getItemCount() {
        return audioFiles.size();
    }

    static class AudioViewHolder extends RecyclerView.ViewHolder {
        TextView titleTextView;
        TextView durationTextView;

        AudioViewHolder(View itemView) {
            super(itemView);
            titleTextView = itemView.findViewById(R.id.audioTitle);
            durationTextView = itemView.findViewById(R.id.audioDuration);
        }
    }
} 