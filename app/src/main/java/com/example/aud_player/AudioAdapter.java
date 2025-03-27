package com.example.aud_player;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;

public class AudioAdapter extends RecyclerView.Adapter<AudioAdapter.AudioViewHolder> {

    private List<AudioFile> audioFiles;
    private OnItemClickListener listener;
    private OnOptionsItemClickListener optionsListener;

    public AudioAdapter(List<AudioFile> audioFiles) {
        this.audioFiles = audioFiles;
    }

    public interface OnItemClickListener {
        void onItemClick(AudioFile audioFile);
    }

    public interface OnOptionsItemClickListener {
        void onFileDetailsClick(AudioFile audioFile);
        void onRenameFileClick(AudioFile audioFile);
        void onDeleteFileClick(AudioFile audioFile);
        void onShareFileClick(AudioFile audioFile);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setOnOptionsItemClickListener(OnOptionsItemClickListener listener) {
        this.optionsListener = listener;
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
        
        // Setup three-dot menu click listener
        holder.optionsMenu.setOnClickListener(v -> {
            showPopupMenu(v, audioFile);
        });
    }

    private void showPopupMenu(View view, AudioFile audioFile) {
        PopupMenu popup = new PopupMenu(view.getContext(), view);
        popup.inflate(R.menu.file_options_menu);
        
        popup.setOnMenuItemClickListener(item -> {
            if (optionsListener == null) return false;
            
            int itemId = item.getItemId();
            if (itemId == R.id.menu_file_details) {
                optionsListener.onFileDetailsClick(audioFile);
                return true;
            } else if (itemId == R.id.menu_rename_file) {
                optionsListener.onRenameFileClick(audioFile);
                return true;
            } else if (itemId == R.id.menu_delete_file) {
                optionsListener.onDeleteFileClick(audioFile);
                return true;
            } else if (itemId == R.id.menu_share_file) {
                optionsListener.onShareFileClick(audioFile);
                return true;
            }
            return false;
        });
        
        popup.show();
    }

    @Override
    public int getItemCount() {
        return audioFiles.size();
    }

    static class AudioViewHolder extends RecyclerView.ViewHolder {
        TextView titleTextView;
        TextView durationTextView;
        ImageView optionsMenu;

        AudioViewHolder(View itemView) {
            super(itemView);
            titleTextView = itemView.findViewById(R.id.audioTitle);
            durationTextView = itemView.findViewById(R.id.audioDuration);
            optionsMenu = itemView.findViewById(R.id.fileOptionsMenu);
        }
    }
} 