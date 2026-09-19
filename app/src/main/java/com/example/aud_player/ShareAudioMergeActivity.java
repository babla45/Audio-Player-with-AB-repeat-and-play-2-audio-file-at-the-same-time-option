package com.example.aud_player;

/**
 * Share-sheet entry "Merge audio": opens the audio editor on its Merge tab with
 * the shared songs already in the list. A single shared song lands there too
 * (merging needs one more, which the tab says).
 */
public class ShareAudioMergeActivity extends ShareEntryActivity {

    @Override
    protected Class<?> editorClass() {
        return AudioEditorActivity.class;
    }

    @Override
    protected String startMode() {
        return MODE_MERGE;
    }
}
