package de.monocles.chat.ui;

import android.content.Context;
import android.text.Layout;
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
    public void setMaxLines(int maxLines) {
        super.setMaxLines(maxLines);
        // Reset scroll on every maxLines change so recycled views never show a stale offset.
        scrollTo(0, 0);
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // Work around the Android/ConstraintLayout bug where a wrap_content TextView under
        // `layout_constrainedWidth` is resolved a sub-pixel too narrow, clipping the last glyph of a
        // one-line message onto a second line ("Wtf" -> "Wt" / "f"). Compute the text's full
        // single-line width and, only when it would fit on one line, re-measure wide enough to keep
        // it there. Multi-line text is left exactly as measured, so it still hugs its content.
        //
        // Skip EXACTLY: there the width is imposed (e.g. a media caption matched to the image width).
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            return;
        }
        final CharSequence text = getText();
        if (text == null || text.length() == 0) {
            return;
        }
        final float desired = Layout.getDesiredWidth(text, getPaint());
        final int cushion = Math.round(getResources().getDisplayMetrics().density * 2f);
        int needed =
                (int) Math.ceil(desired)
                        + getCompoundPaddingLeft()
                        + getCompoundPaddingRight()
                        + cushion;
        // Never widen past an explicit maxWidth: for a view with a fixed maxWidth (e.g. the reply
        // quote) long text must wrap AT that width — wrapping at the per-pass available width is
        // what makes a multi-line quote re-wrap and the card size flicker between measure passes.
        needed = Math.min(needed, getMaxWidth());
        final int available = MeasureSpec.getSize(widthMeasureSpec);
        if (needed <= available && needed > getMeasuredWidth()) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(needed, MeasureSpec.EXACTLY), heightMeasureSpec);
        }
    }
}
