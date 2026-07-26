package de.monocles.chat.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.R;

/**
 * Lays several photos of one message out as a single album.
 *
 * <p>The tiling is chosen by the number of tiles rather than by their aspect ratios, so the shape
 * of the album itself says how many photos arrived: two side by side, three as a dominant tile
 * with a stacked pair, four or more as a square of four (the last one then covers the rest).
 * Tiles are separated by a hairline gap and only the corners on the outside of the album are
 * rounded, so it reads as one object instead of a handful of cards.
 *
 * <p>Callers pass at most {@link #MAX_TILES} children.
 */
public class AlbumLayout extends ViewGroup {

    public static final int MAX_TILES = 4;

    /** Width of the dominant tile in the three-photo album, as a fraction of the album. */
    private static final float DOMINANT = 0.618f;

    private final int gap;
    private final List<Rect> tiles = new ArrayList<>();

    public AlbumLayout(final Context context) {
        this(context, null);
    }

    public AlbumLayout(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        this.gap = Math.round(2f * getResources().getDisplayMetrics().density);
    }

    /**
     * The corner treatment of the tile at {@code index} of an album of {@code count} tiles: a
     * corner is rounded only where it sits on a corner of the album as a whole. Kept next to the
     * tiling it belongs to, but applied by the caller so that measuring stays free of side
     * effects.
     */
    public static ShapeAppearanceModel shapeFor(
            final int index, final int count, final float radius) {
        final boolean topLeft;
        final boolean topRight;
        final boolean bottomLeft;
        final boolean bottomRight;
        if (count <= 1) {
            topLeft = topRight = bottomLeft = bottomRight = true;
        } else if (count == 2) {
            topLeft = bottomLeft = index == 0;
            topRight = bottomRight = index == 1;
        } else if (count == 3) {
            topLeft = bottomLeft = index == 0;
            topRight = index == 1;
            bottomRight = index == 2;
        } else {
            topLeft = index == 0;
            topRight = index == 1;
            bottomLeft = index == 2;
            bottomRight = index == 3;
        }
        return ShapeAppearanceModel.builder()
                .setTopLeftCorner(CornerFamily.ROUNDED, topLeft ? radius : 0f)
                .setTopRightCorner(CornerFamily.ROUNDED, topRight ? radius : 0f)
                .setBottomLeftCorner(CornerFamily.ROUNDED, bottomLeft ? radius : 0f)
                .setBottomRightCorner(CornerFamily.ROUNDED, bottomRight ? radius : 0f)
                .build();
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final int count = Math.min(getChildCount(), MAX_TILES);
        final int max = getResources().getDimensionPixelSize(R.dimen.image_preview_width);
        final int offered = MeasureSpec.getSize(widthMeasureSpec);
        final int width =
                MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || offered <= 0
                        ? max
                        : Math.min(offered, max);
        final int height = tile(count, width);
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (i >= tiles.size()) {
                child.setVisibility(GONE);
                continue;
            }
            final Rect rect = tiles.get(i);
            child.measure(
                    MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(
            final boolean changed, final int l, final int t, final int r, final int b) {
        for (int i = 0; i < getChildCount() && i < tiles.size(); i++) {
            final Rect rect = tiles.get(i);
            getChildAt(i).layout(rect.left, rect.top, rect.right, rect.bottom);
        }
    }

    /** Fills {@link #tiles} for the given number of photos and returns the album height. */
    private int tile(final int count, final int width) {
        tiles.clear();
        if (count <= 0 || width <= 0) {
            return 0;
        }
        final int half = (width - gap) / 2;
        if (count == 1) {
            final int height = Math.round(width * 0.75f);
            tiles.add(new Rect(0, 0, width, height));
            return height;
        }
        if (count == 2) {
            tiles.add(new Rect(0, 0, half, half));
            tiles.add(new Rect(half + gap, 0, width, half));
            return half;
        }
        if (count == 3) {
            // One dominant photo, the other two stacked beside it. The album keeps the height
            // of a two-photo one so consecutive albums line up in the conversation.
            final int dominant = Math.round((width - gap) * DOMINANT);
            final int top = (half - gap) / 2;
            tiles.add(new Rect(0, 0, dominant, half));
            tiles.add(new Rect(dominant + gap, 0, width, top));
            tiles.add(new Rect(dominant + gap, top + gap, width, half));
            return half;
        }
        for (int i = 0; i < MAX_TILES; i++) {
            final int left = (i % 2) == 0 ? 0 : half + gap;
            final int top = i < 2 ? 0 : half + gap;
            tiles.add(new Rect(left, top, left + half, top + half));
        }
        return half * 2 + gap;
    }
}
