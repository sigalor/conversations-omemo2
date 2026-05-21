package de.monocles.chat.ui;

import android.content.Context;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatTextView;

public class CollapsableTextView extends AppCompatTextView {
    public CollapsableTextView(Context context) {
        super(context);
    }

    public CollapsableTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CollapsableTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        if (getMaxLines() != Integer.MAX_VALUE) {
            if (l != 0 || t != 0) {
                scrollTo(0, 0);
            }
        }
        super.onScrollChanged(l, t, oldl, oldt);
    }
}
