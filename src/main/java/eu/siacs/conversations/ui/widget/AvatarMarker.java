package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;

import java.lang.ref.WeakReference;

public class AvatarMarker extends Overlay implements Drawable.Callback {

    private final GeoPoint position;
    private final Drawable avatarDrawable;

    private final Path pinPath;
    private final Path clipPath;
    private final Paint strokePaint;
    private final Paint fillPaint;
    private final int bmpWidth;
    private final int bmpHeight;
    private final float cx;
    private final float cy;
    private final float innerRadius;

    private final Point mapPoint = new Point();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WeakReference<MapView> mapViewRef;

    public AvatarMarker(Context context, Drawable avatarDrawable, GeoPoint position) {
        this.position = position;
        this.avatarDrawable = avatarDrawable;

        final float d = context.getResources().getDisplayMetrics().density;
        final float avatarDiameter = 44 * d;
        final float whiteBorder = 3 * d;
        final float outerRadius = avatarDiameter / 2f + whiteBorder;
        final float tipHeight = 14 * d;
        final float tipHalfWidth = 5 * d;
        final float borderStroke = 1.5f * d;
        final float extra = (float) Math.ceil(borderStroke);

        bmpWidth = (int) Math.ceil(2 * outerRadius + 2 * extra);
        bmpHeight = (int) Math.ceil(2 * outerRadius + tipHeight + 2 * extra);
        cx = bmpWidth / 2f;
        cy = outerRadius + extra;
        innerRadius = outerRadius - whiteBorder;

        // Combined teardrop path: arc over the top + tip lines
        final double halfAngleRad = Math.atan2(tipHalfWidth, outerRadius);
        final double halfAngleDeg = Math.toDegrees(halfAngleRad);
        final float junctionDy = (float) (outerRadius * Math.cos(halfAngleRad));
        final float arcStart = (float) (90 - halfAngleDeg);
        final float arcSweep = (float) -(360 - 2 * halfAngleDeg);
        final RectF circleRect = new RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius);

        pinPath = new Path();
        pinPath.moveTo(cx + tipHalfWidth, cy + junctionDy);
        pinPath.arcTo(circleRect, arcStart, arcSweep, false);
        pinPath.lineTo(cx, cy + outerRadius + tipHeight);
        pinPath.close();

        clipPath = new Path();
        clipPath.addCircle(cx, cy, innerRadius, Path.Direction.CW);

        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(borderStroke * 2); // fill will cover inner half
        strokePaint.setColor(Color.argb(160, 60, 60, 60));
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.WHITE);

        avatarDrawable.setCallback(this);
        if (avatarDrawable instanceof Animatable) {
            ((Animatable) avatarDrawable).start();
        }
    }

    @Override
    public void draw(final Canvas c, final MapView view, final boolean shadow) {
        if (shadow) return;
        if (mapViewRef == null || mapViewRef.get() == null) {
            mapViewRef = new WeakReference<>(view);
        }

        view.getProjection().toPixels(position == null ? view.getMapCenter() : position, mapPoint);

        c.save();
        c.translate(mapPoint.x - bmpWidth / 2f, mapPoint.y - bmpHeight);

        // Stroke first so fill covers its inner half, leaving only the outer border visible
        c.drawPath(pinPath, strokePaint);
        c.drawPath(pinPath, fillPaint);

        c.save();
        c.clipPath(clipPath);
        avatarDrawable.setBounds(
                (int) (cx - innerRadius), (int) (cy - innerRadius),
                (int) (cx + innerRadius), (int) (cy + innerRadius));
        avatarDrawable.draw(c);
        c.restore();

        c.restore();
    }

    @Override
    public void onDetach(MapView mapView) {
        avatarDrawable.setCallback(null);
        if (avatarDrawable instanceof Animatable) {
            ((Animatable) avatarDrawable).stop();
        }
        mapViewRef = null;
    }

    // --- Drawable.Callback ---

    @Override
    public void invalidateDrawable(@NonNull Drawable who) {
        final MapView mv = mapViewRef != null ? mapViewRef.get() : null;
        if (mv != null) mv.postInvalidate();
    }

    @Override
    public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
        handler.postAtTime(what, who, when);
    }

    @Override
    public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
        handler.removeCallbacksAndMessages(who);
    }
}
