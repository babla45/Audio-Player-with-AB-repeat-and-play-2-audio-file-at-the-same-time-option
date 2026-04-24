package com.example.aud_player;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
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
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AudioAdapter extends RecyclerView.Adapter<AudioAdapter.AudioViewHolder> {

    private List<AudioFile> audioFiles;
    private OnItemClickListener listener;
    private OnOptionsItemClickListener optionsListener;
    
    // Add variables to track currently playing song
    private Uri currentlyPlayingUri = null;
    private int highlightColor;
    private int defaultColor;

    public AudioAdapter(List<AudioFile> audioFiles) {
        this.audioFiles = new ArrayList<>(audioFiles);
    }

    /**
     * Update the adapter's data with a new list of audio files
     * @param newList the new list of audio files to display
     */
    public void updateList(List<AudioFile> newList) {
        this.audioFiles.clear();
        this.audioFiles.addAll(newList);
        notifyDataSetChanged();
    }

    public interface OnItemClickListener {
        void onItemClick(AudioFile audioFile);
    }

    public interface OnOptionsItemClickListener {
        void onFileDetailsClick(AudioFile audioFile);
        void onRenameFileClick(AudioFile audioFile);
        void onDeleteFileClick(AudioFile audioFile);
        void onShareFileClick(AudioFile audioFile);
        void onAddToPlaylistClick(AudioFile audioFile);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setOnOptionsItemClickListener(OnOptionsItemClickListener listener) {
        this.optionsListener = listener;
    }
    
    /**
     * Set the currently playing audio URI to highlight it in the list
     * @param uri URI of the currently playing audio
     */
    public void setCurrentlyPlayingUri(Uri uri) {
        this.currentlyPlayingUri = uri;
        notifyDataSetChanged(); // Refresh all items to update highlight
    }

    @NonNull
    @Override
    public AudioViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_audio_file, parent, false);
        
        // Initialize colors
        Context context = parent.getContext();
        highlightColor = ContextCompat.getColor(context, R.color.colorPlayingHighlight);
        defaultColor = ContextCompat.getColor(context, R.color.colorCardBackground);
                
        return new AudioViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AudioViewHolder holder, int position) {
        AudioFile audioFile = audioFiles.get(position);
        holder.titleTextView.setText(audioFile.getTitle());
        
        // Combine duration and file size with a separator
        String durationAndSize = audioFile.getDuration() + " • " + audioFile.getFormattedSize();
        holder.durationTextView.setText(durationAndSize);
        
        // Highlight if this is the currently playing track
        boolean isCurrentlyPlaying = currentlyPlayingUri != null && currentlyPlayingUri.equals(audioFile.getUri());
        
        if (isCurrentlyPlaying) {
            // Show accent bar on the left edge
            if (holder.nowPlayingBar != null) {
                holder.nowPlayingBar.setVisibility(View.VISIBLE);
            }
            // Subtle accent surface background
            holder.itemContainer.setBackgroundColor(
                ContextCompat.getColor(holder.itemContainer.getContext(), R.color.accent_surface));
            // Show equalizer icon overlay
            if (holder.nowPlayingIcon != null) {
                holder.nowPlayingIcon.setVisibility(View.VISIBLE);
            }
            // Accent color for title
            holder.titleTextView.setTextColor(
                ContextCompat.getColor(holder.itemContainer.getContext(), R.color.accent_primary));
            holder.durationTextView.setTextColor(
                ContextCompat.getColor(holder.itemContainer.getContext(), R.color.text_secondary));
        } else {
            // Default state - use design system background
            if (holder.nowPlayingBar != null) {
                holder.nowPlayingBar.setVisibility(View.GONE);
            }
            holder.itemContainer.setBackgroundResource(R.drawable.bg_song_item);
            if (holder.nowPlayingIcon != null) {
                holder.nowPlayingIcon.setVisibility(View.GONE);
            }
            // Default text colors from design system
            holder.titleTextView.setTextColor(
                ContextCompat.getColor(holder.itemContainer.getContext(), R.color.text_primary));
            holder.durationTextView.setTextColor(
                ContextCompat.getColor(holder.itemContainer.getContext(), R.color.text_secondary));
        }
        
        holder.itemContainer.setOnClickListener(v -> {
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
            } else if (itemId == R.id.menu_add_to_playlist) {
                optionsListener.onAddToPlaylistClick(audioFile);
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
        View itemContainer;
        ImageView nowPlayingIcon;
        View nowPlayingBar;

        AudioViewHolder(View itemView) {
            super(itemView);
            titleTextView = itemView.findViewById(R.id.audioTitle);
            durationTextView = itemView.findViewById(R.id.audioDuration);
            optionsMenu = itemView.findViewById(R.id.fileOptionsMenu);
            itemContainer = itemView.findViewById(R.id.innerLayout);
            nowPlayingIcon = itemView.findViewById(R.id.nowPlayingIcon);
            nowPlayingBar = itemView.findViewById(R.id.nowPlayingBar);
        }
    }
}