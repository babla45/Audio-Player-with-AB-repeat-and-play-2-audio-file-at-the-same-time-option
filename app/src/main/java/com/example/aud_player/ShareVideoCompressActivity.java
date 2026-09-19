package com.example.aud_player;

/**
 * Share-sheet entry "Compress video": opens the video editor on its Compress tab
 * with the shared videos already in that list.
 */
public class ShareVideoCompressActivity extends ShareEntryActivity {

    @Override
    protected Class<?> editorClass() {
        return VideoEditorActivity.class;
    }

    @Override
    protected String startMode() {
        return MODE_COMPRESS;
    }
}
