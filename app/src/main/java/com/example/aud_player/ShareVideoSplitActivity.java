package com.example.aud_player;

/**
 * Share-sheet entry "Split video": opens the video editor on its Split tab with
 * the first shared video on the timeline.
 */
public class ShareVideoSplitActivity extends ShareEntryActivity {

    @Override
    protected Class<?> editorClass() {
        return VideoEditorActivity.class;
    }

    @Override
    protected String startMode() {
        return MODE_SPLIT;
    }
}
