package com.example.aud_player;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.collection.LruCache;

public class AudioAdapter extends RecyclerView.Adapter<AudioAdapter.AudioViewHolder> {

    // SharedPreferences key: when true, song rows show embedded album art
    // instead of the default music note icon (falls back to the icon if a
    // song has no embedded artwork)
    public static final String PREF_SONG_LIST_ALBUM_ART = "song_list_album_art";

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
    // Whether rows should prefer embedded album art over the music note icon
    // (default on; falls back to the icon for songs without artwork)
    private boolean showAlbumArt = true;
    // Background executor for reading embedded artwork off the main thread
    private final ExecutorService artExecutor = Executors.newSingleThreadExecutor();
    // LRU cache of loaded artwork keyed by audio URI.
    // Max ~50 entries (~50 × ~64KB ≈ 3MB); null value = no artwork for that URI.
    private final LruCache<Uri, Bitmap> artCache = new LruCache<Uri, Bitmap>(50) {
        @Override protected int sizeOf(Uri key, Bitmap value) { return 1; }
        @Override protected void entryRemoved(boolean evicted, Uri key, Bitmap oldValue, Bitmap newValue) {
            // Don't recycle — the ImageView may still reference it
        }
    };

    public AudioAdapter(List<AudioFile> audioFiles) {
        this.audioFiles = new ArrayList<>(audioFiles);
    }

    /**
     * Reads the user's song-list icon preference (album art vs default icon).
     */
    public static boolean shouldShowAlbumArt(Context context) {
        return context.getSharedPreferences("audio_player_prefs", Context.MODE_PRIVATE)
                .getBoolean(PREF_SONG_LIST_ALBUM_ART, true);
    }

    /**
     * Updates whether rows prefer embedded album art and refreshes the list.
     */
    public void setShowAlbumArt(boolean show) {
        if (this.showAlbumArt != show) {
            this.showAlbumArt = show;
            notifyDataSetChanged();
        }
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
        Uri oldUri = this.currentlyPlayingUri;
        this.currentlyPlayingUri = uri;
        // Only rebind the old and new highlighted rows instead of the entire list
        if (oldUri != null) {
            int oldPos = findPositionByUri(oldUri);
            if (oldPos >= 0) notifyItemChanged(oldPos);
        }
        if (uri != null) {
            int newPos = findPositionByUri(uri);
            if (newPos >= 0) notifyItemChanged(newPos);
        }
    }

    private int findPositionByUri(Uri uri) {
        for (int i = 0; i < audioFiles.size(); i++) {
            if (uri.equals(audioFiles.get(i).getUri())) return i;
        }
        return -1;
    }

    /**
     * Tell the adapter whether audio is actually playing or paused, so the
     * equalizer indicator animates only while playing.
     */
    public void setPlaybackActive(boolean active) {
        if (this.playbackActive != active) {
            this.playbackActive = active;
            // Only rebind the currently playing row — the equalizer
            // animation only affects that one item.
            if (currentlyPlayingUri != null) {
                int pos = findPositionByUri(currentlyPlayingUri);
                if (pos >= 0) notifyItemChanged(pos);
            }
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
        // Pick up the current album-art preference so every adapter instance
        // (main list, folder list, playlists) respects the setting
        showAlbumArt = shouldShowAlbumArt(context);

        return new AudioViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AudioViewHolder holder, int position) {
        AudioFile audioFile = audioFiles.get(position);
        holder.titleTextView.setText(buildHighlightedTitle(
                audioFile.getTitle(), holder.titleTextView.getContext()));
        bindRowIcon(holder, audioFile, position);
        
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
                ContextCompat.getColor(holder.itemContainer.getContext(), R.color.accent_primary2));
            // Duration/size stay static; the "Now Playing" tag scrolls beside them.
            // Text is repeated so it overflows the view, engaging the marquee.
            holder.durationTextView.setText(durationAndSize);
            // Long repeated text overflows the tag view, so the native
            // marquee scrolls it forever: "Now Playing  Now Playing  ..."
            holder.nowPlayingTag.setText(buildRepeatingTagText());
            holder.nowPlayingTag.setVisibility(View.VISIBLE);
        } else {
            // Default state - use design system background
            holder.itemContainer.setBackgroundResource(R.drawable.bg_song_item);
            showEqualizerIndicator(holder, false);
            // Default text colors from design system
            holder.titleTextView.setTextColor(
                ContextCompat.getColor(holder.itemContainer.getContext(), R.color.text_primary));
            holder.durationTextView.setTextColor(
                ContextCompat.getColor(holder.itemContainer.getContext(), R.color.text_secondary));
            holder.nowPlayingTag.setVisibility(View.GONE);
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
     * Shows the embedded album art in the row icon when the album-art
     * setting is on and the song actually has artwork; otherwise falls back
     * to the default music note icon. Artwork is read off the main thread
     * and cached per URI.
     */
    private void bindRowIcon(AudioViewHolder holder, AudioFile audioFile, int position) {
        // Reset recycled views first
        holder.albumArtIcon.setImageDrawable(null);
        holder.albumArtIcon.setVisibility(View.GONE);
        holder.albumArtIcon.setTag(null);

        if (!showAlbumArt) {
            holder.musicNoteIcon.setVisibility(View.VISIBLE);
            return;
        }

        Bitmap cached = artCache.get(audioFile.getUri());
        if (cached != null) {
            holder.musicNoteIcon.setVisibility(View.GONE);
            holder.albumArtIcon.setVisibility(View.VISIBLE);
            holder.albumArtIcon.setImageBitmap(cached);
            return;
        }

        // Show the default icon while artwork loads in the background
        holder.musicNoteIcon.setVisibility(View.VISIBLE);
        final Uri artUri = audioFile.getUri();
        final String tag = artUri.toString();
        holder.albumArtIcon.setTag(tag);
        artExecutor.execute(() -> {
            Bitmap art = loadEmbeddedArtwork(holder.albumArtIcon.getContext(), artUri);
            // LruCache.put throws on a null value — only cache when the
            // file actually has embedded artwork
            if (art != null) {
                artCache.put(artUri, art);
            }
            holder.albumArtIcon.post(() -> {
                // Only apply if this view is still showing the same song
                if (!tag.equals(String.valueOf(holder.albumArtIcon.getTag()))) {
                    return;
                }
                if (art != null) {
                    holder.musicNoteIcon.setVisibility(View.GONE);
                    holder.albumArtIcon.setVisibility(View.VISIBLE);
                    holder.albumArtIcon.setImageBitmap(art);
                }
                // No artwork: keep the default music note icon visible
            });
        });
    }

    /**
     * Extracts the embedded cover art bitmap for an audio URI, or null if
     * the file has none (or can't be read).
     */
    private Bitmap loadEmbeddedArtwork(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            try {
                retriever.setDataSource(context, uri);
            } catch (Exception e) {
                return null;
            }
            byte[] data = retriever.getEmbeddedPicture();
            if (data == null || data.length == 0) {
                return null;
            }
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Builds the marquee text: "Now Playing" repeated with spacing so the
     * string always overflows the tag view and scrolls indefinitely.
     */
    private String buildRepeatingTagText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("Now Playing     ");
        }
        return sb.toString();
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
        TextView nowPlayingTag;
        ImageView optionsMenu;
        View itemContainer;
        ImageView nowPlayingIcon;
        ImageView musicNoteIcon;
        ImageView albumArtIcon;

        AudioViewHolder(View itemView) {
            super(itemView);
            titleTextView = itemView.findViewById(R.id.audioTitle);
            durationTextView = itemView.findViewById(R.id.audioDuration);
            nowPlayingTag = itemView.findViewById(R.id.nowPlayingTag);
            optionsMenu = itemView.findViewById(R.id.fileOptionsMenu);
            itemContainer = itemView.findViewById(R.id.innerLayout);
            nowPlayingIcon = itemView.findViewById(R.id.nowPlayingIcon);
            musicNoteIcon = itemView.findViewById(R.id.musicNoteIcon);
            albumArtIcon = itemView.findViewById(R.id.albumArtIcon);
        }
    }
}