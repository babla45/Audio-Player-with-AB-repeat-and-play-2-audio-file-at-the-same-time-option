package com.example.aud_player;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible share-sheet entry: turns one chooser entry into one editor task.
 *
 * <p>The share sheet used to offer a single generic "Audio Editor" / "Video
 * Editor" target that guessed the task from the file count (one file = Split,
 * several = Merge). It now offers one entry per task — "Merge audio", "Split
 * audio", "Merge videos", "Split video", "Compress video" — and Android
 * de-duplicates the chooser by activity, so every task needs its own (tiny)
 * activity. Everything they share lives here: read the incoming share, forward
 * it to the right editor with the requested mode, and finish without ever
 * drawing anything.
 *
 * <p>The original action and extras are forwarded unchanged, so the editors'
 * existing EXTRA_STREAM reading (one Uri or a list, Uri or plain path) is the
 * exact code that ran when they were the share target themselves — only the
 * tab they open on is now stated instead of guessed. The URI grant flags and
 * the ClipData are carried over too, so a content:// handed over by a file
 * manager stays readable in the editor.
 */
public abstract class ShareEntryActivity extends Activity {

    /**
     * Which editor tab to open, one of {@link #MODE_MERGE} / {@link #MODE_SPLIT}
     * / {@link #MODE_COMPRESS}. Read by AudioEditorActivity and
     * VideoEditorActivity; a mode an editor does not know is simply ignored and
     * leaves its previous behaviour in place.
     */
    public static final String EXTRA_START_MODE = "extra_start_mode";

    public static final String MODE_MERGE = "merge";
    public static final String MODE_SPLIT = "split";
    /** Video editor only — the audio editor has no compress path. */
    public static final String MODE_COMPRESS = "compress";

    /** URI permissions worth carrying over to the editor. */
    private static final int GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

    /** The editor to open ("Merge audio" → AudioEditorActivity, …). */
    protected abstract Class<?> editorClass();

    /** The task the editor should open on — one of the MODE_* values. */
    protected abstract String startMode();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent shared = getIntent();
        if (!carriesFiles(shared)) {
            // Nothing to hand over. This entry has no UI of its own, so say so
            // rather than opening an editor on nothing (which would show a
            // blank editor, or close itself again on the audio side).
            Toast.makeText(this, R.string.share_no_file, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Intent editor = new Intent(this, editorClass());
        editor.setAction(shared.getAction());
        editor.setData(shared.getData());
        editor.setType(shared.getType());
        editor.putExtras(shared);
        if (shared.getClipData() != null) editor.setClipData(shared.getClipData());
        editor.addFlags(shared.getFlags() & GRANT_FLAGS);
        editor.putExtra(EXTRA_START_MODE, startMode());
        startActivity(editor);
        finish();
    }

    /**
     * Does the intent carry at least one stream? The editors do the real
     * parsing; this only decides whether opening them is worth it.
     */
    private boolean carriesFiles(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action)
                && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return false;
        }
        if (intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
            return true;
        }
        return intent.getExtras() != null
                && intent.getExtras().get(Intent.EXTRA_STREAM) != null;
    }
}
