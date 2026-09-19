package com.example.aud_player;

/**
 * Share-sheet entry "Split audio": opens the audio editor on its Split tab with
 * the first shared song on the timeline (the remaining ones behave exactly as
 * they do for any other multi-song share).
 */
public class ShareAudioSplitActivity extends ShareEntryActivity {

    @Override
    protected Class<?> editorClass() {
        return AudioEditorActivity.class;
    }

    @Override
    protected String startMode() {
        return MODE_SPLIT;
    }
}
