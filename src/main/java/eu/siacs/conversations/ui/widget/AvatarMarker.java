package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;

public class AvatarMarker extends Overlay {

    private final GeoPoint position;
    private final Bitmap markerBitmap;
    private final Point mapPoint = new Point();

    public AvatarMarker(Context context, Bitmap avatarBitmap, GeoPoint position) {
        this.position = position;
        this.markerBitmap = buildPin(context, avatarBitmap);
    }

    @Override
    public void draw(final Canvas c, final MapView view, final boolean shadow) {
        if (shadow || markerBitmap == null) return;
        view.getProjection().toPixels(position == null ? view.getMapCenter() : position, mapPoint);
        c.drawBitmap(markerBitmap,
                mapPoint.x - markerBitmap.getWidth() / 2f,
                mapPoint.y - markerBitmap.getHeight(),
                null);
    }

    private static Bitmap buildPin(Context context, Bitmap avatar) {
        final float d = context.getResources().getDisplayMetrics().density;

        final float avatarDiameter = 44 * d;
        final float whiteBorder = 3 * d;
        final float outerRadius = avatarDiameter / 2f + whiteBorder;
        final float tipHeight = 14 * d;
        final float tipHalfWidth = 5 * d;
        final float borderStroke = 1.5f * d;

        // Canvas width: 2 * outerRadius + extra for stroke
        final float extra = (float) Math.ceil(borderStroke);
        final int bmpWidth = (int) Math.ceil(2 * outerRadius + 2 * extra);
        final int bmpHeight = (int) Math.ceil(2 * outerRadius + tipHeight + 2 * extra);

        final float cx = bmpWidth / 2f;
        final float cy = outerRadius + extra;

        final Bitmap result = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(result);

        // --- Build the combined teardrop path (circle arc + tip triangle) ---
        // The tip junction angle (measured from straight-down direction):
        // halfAngle = atan(tipHalfWidth / outerRadius)
        final double halfAngleRad = Math.atan2(tipHalfWidth, outerRadius);
        final double halfAngleDeg = Math.toDegrees(halfAngleRad);
        // Junction Y from circle center:
        final float junctionDy = (float) (outerRadius * Math.cos(halfAngleRad));

        // In Android's arc system (0° = 3 o'clock, increasing clockwise):
        // Right junction is at 90° - halfAngleDeg (just right of straight-down)
        // Sweep counterclockwise (negative) by (360 - 2*halfAngleDeg) to reach left junction
        final float arcStart = (float) (90 - halfAngleDeg);
        final float arcSweep = (float) -(360 - 2 * halfAngleDeg);

        final RectF circleRect = new RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius);
        final Path pinPath = new Path();
        // Start at right junction
        pinPath.moveTo(cx + tipHalfWidth, cy + junctionDy);
        // Arc counterclockwise over the top to the left junction
        pinPath.arcTo(circleRect, arcStart, arcSweep, false);
        // Line to tip point
        pinPath.lineTo(cx, cy + outerRadius + tipHeight);
        // Close back to right junction
        pinPath.close();

        // --- Draw border stroke first (half will be covered by the white fill) ---
        final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(borderStroke * 2); // drawn centered; fill covers the inner half
        strokePaint.setColor(Color.argb(160, 60, 60, 60));
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPath(pinPath, strokePaint);

        // --- Draw white fill ---
        final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.WHITE);
        canvas.drawPath(pinPath, fillPaint);

        // --- Draw circular avatar clipped to inner radius ---
        final float innerRadius = outerRadius - whiteBorder;
        final int aSize = (int) avatarDiameter;
        final Bitmap avatarScaled = Bitmap.createScaledBitmap(avatar, aSize, aSize, true);

        final Bitmap circularAvatar = Bitmap.createBitmap(aSize, aSize, Bitmap.Config.ARGB_8888);
        final Canvas ac = new Canvas(circularAvatar);
        final Paint clipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ac.drawCircle(aSize / 2f, aSize / 2f, innerRadius, clipPaint);
        clipPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        ac.drawBitmap(avatarScaled, 0, 0, clipPaint);

        canvas.drawBitmap(circularAvatar, cx - aSize / 2f, cy - aSize / 2f, null);

        avatarScaled.recycle();
        circularAvatar.recycle();

        return result;
    }
}
