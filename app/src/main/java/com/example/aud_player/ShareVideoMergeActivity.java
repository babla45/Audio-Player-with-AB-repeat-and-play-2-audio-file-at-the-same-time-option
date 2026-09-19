package com.example.aud_player;

/**
 * Share-sheet entry "Merge videos": opens the video editor on its Merge tab
 * with the shared videos already in the list.
 */
public class ShareVideoMergeActivity extends ShareEntryActivity {

    @Override
    protected Class<?> editorClass() {
        return VideoEditorActivity.class;
    }

    @Override
    protected String startMode() {
        return MODE_MERGE;
    }
}
