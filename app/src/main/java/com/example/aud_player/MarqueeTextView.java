package com.example.aud_player;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;

/**
 * TextView whose native marquee scroll runs continuously, like an HTML
 * <marquee> tag, without needing focus or selection. The text must be long
 * enough to overflow the view for the scroll to engage, so the adapter sets
 * a repeated "Now Playing" string.
 */
public class MarqueeTextView extends androidx.appcompat.widget.AppCompatTextView {

    public MarqueeTextView(Context context) {
        super(context);
        init();
    }

    public MarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.MARQUEE);
        setMarqueeRepeatLimit(-1); // marquee_forever
    }

    @Override
    public boolean isSelected() {
        // TextView only runs the marquee while "selected" — always report
        // selected so the scroll never stops
        return true;
    }
}
