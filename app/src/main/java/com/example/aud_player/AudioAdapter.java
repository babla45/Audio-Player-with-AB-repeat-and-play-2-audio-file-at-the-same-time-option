package com.example.aud_player;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
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
    // Whether audio is actually playing (vs paused) — drives the equalizer animation
    private boolean playbackActive = false;
    // Current search query: matched words are highlighted yellow in titles
    private String searchQuery = "";
    // Subsequence mode: highlight each query character's in-order match
    // instead of the whole-word match
    private boolean subsequenceMode = false;

    public AudioAdapter(List<AudioFile> audioFiles) {
        this.audioFiles = new ArrayList<>(audioFiles);
    }

    /**
     * Sets the active search query for yellow highlighting of matched words
     * in song titles. Empty/null disables highlighting.
     */
    public void setSearchQuery(String query) {
        setSearchQuery(query, this.subsequenceMode);
    }

    public void setSearchQuery(String query, boolean subsequenceMode) {
        this.searchQuery = query != null ? query.toLowerCase() : "";
        this.subsequenceMode = subsequenceMode;
        notifyDataSetChanged();
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

    /**
     * Tell the adapter whether audio is actually playing or paused, so the
     * equalizer indicator animates only while playing.
     */
    public void setPlaybackActive(boolean active) {
        if (this.playbackActive != active) {
            this.playbackActive = active;
            notifyDataSetChanged();
        }
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
        holder.titleTextView.setText(buildHighlightedTitle(
                audioFile.getTitle(), holder.titleTextView.getContext()));
        
        // Combine duration and file size with a separator
        String durationAndSize = audioFile.getDuration() + " • " + audioFile.getFormattedSize();
        holder.durationTextView.setText(durationAndSize);
        
        // Highlight if this is the currently playing track
        boolean isCurrentlyPlaying = currentlyPlayingUri != null && currentlyPlayingUri.equals(audioFile.getUri());

        if (isCurrentlyPlaying) {
            // Gradient background for the currently playing row (keeps rounded corners + ripple)
            holder.itemContainer.setBackgroundResource(R.drawable.bg_song_item_playing);
            // Animated equalizer overlay: dancing bars while playing, dimmed static bars when paused
            showEqualizerIndicator(holder, true);
            // Accent color for title
            holder.titleTextView.setTextColor(
                ContextCompat.getColor(holder.itemContainer.getContext(), R.color.accent_primary));
            // Append an accent "Now Playing" tag to the duration/size line
            String base = durationAndSize;
            String tag = "  •  Now Playing";
            SpannableString span = new SpannableString(base + tag);
            int accent = ContextCompat.getColor(holder.itemContainer.getContext(), R.color.accent_primary);
            span.setSpan(new ForegroundColorSpan(accent), base.length(), span.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new StyleSpan(Typeface.BOLD), base.length(), span.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            holder.durationTextView.setText(span);
        } else {
            // Default state - use design system background
            holder.itemContainer.setBackgroundResource(R.drawable.bg_song_item);
            showEqualizerIndicator(holder, false);
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

    /**
     * Builds the title text with the parts matching the active search query
     * highlighted in yellow. With no query, returns the plain title.
     * In subsequence mode, highlights each query character at its in-order
     * match position (same walk as AudioFile.matchesSubsequence).
     */
    private CharSequence buildHighlightedTitle(String title, Context context) {
        if (searchQuery == null || searchQuery.isEmpty() || title == null) {
            return title;
        }
        String lowerTitle = title.toLowerCase();
        SpannableString span = new SpannableString(title);
        int highlightColor = ContextCompat.getColor(
                context, android.R.color.holo_orange_light);

        if (subsequenceMode) {
            // Walk the title once, matching query chars in order — mirrors
            // AudioFile.isSubsequenceOf so highlights match the filtering.
            int titleIndex = 0;
            for (char c : searchQuery.toCharArray()) {
                titleIndex = lowerTitle.indexOf(c, titleIndex);
                if (titleIndex < 0) {
                    break;
                }
                span.setSpan(new ForegroundColorSpan(highlightColor),
                        titleIndex, titleIndex + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                titleIndex++;
            }
        } else {
            // Find every occurrence of the query (case-insensitive) and color it
            int index = lowerTitle.indexOf(searchQuery);
            while (index >= 0) {
                span.setSpan(new ForegroundColorSpan(highlightColor),
                        index, index + searchQuery.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                index = lowerTitle.indexOf(searchQuery, index + searchQuery.length());
            }
        }
        return span;
    }

    /**
     * Shows/hides the equalizer indicator on the row's icon. While audio is
     * actually playing the bars animate; when paused they stay static and dimmed.
     */
    private void showEqualizerIndicator(AudioViewHolder holder, boolean isCurrent) {
        if (holder.nowPlayingIcon == null) return;
        // Stop any running frame animation before switching state
        Drawable current = holder.nowPlayingIcon.getDrawable();
        if (current instanceof AnimationDrawable) {
            ((AnimationDrawable) current).stop();
        }
        if (!isCurrent) {
            holder.nowPlayingIcon.setVisibility(View.GONE);
            return;
        }
        holder.nowPlayingIcon.setVisibility(View.VISIBLE);
        if (playbackActive) {
            holder.nowPlayingIcon.setImageResource(R.drawable.ic_equalizer_animated);
            holder.nowPlayingIcon.setAlpha(1.0f);
            Drawable d = holder.nowPlayingIcon.getDrawable();
            if (d instanceof AnimationDrawable) {
                ((AnimationDrawable) d).start();
            }
        } else {
            holder.nowPlayingIcon.setImageResource(R.drawable.ic_equalizer_static);
            holder.nowPlayingIcon.setAlpha(0.6f);
        }
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

        AudioViewHolder(View itemView) {
            super(itemView);
            titleTextView = itemView.findViewById(R.id.audioTitle);
            durationTextView = itemView.findViewById(R.id.audioDuration);
            optionsMenu = itemView.findViewById(R.id.fileOptionsMenu);
            itemContainer = itemView.findViewById(R.id.innerLayout);
            nowPlayingIcon = itemView.findViewById(R.id.nowPlayingIcon);
        }
    }
}