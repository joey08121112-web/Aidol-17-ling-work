package com.aidol.demo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

/** Decorative, locally rendered voice sculpture. No network assets or audio ownership. */
final class CompanionOrbView extends View {
    /** Full frame rate only while the voice level still moves; silence drops to a coarse idle rate. */
    static final long ACTIVE_FRAME_MILLIS = 40, IDLE_FRAME_MILLIS = 250, LEVEL_HOLD_MILLIS = 1500;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Rect visibleArea = new Rect();
    private RadialGradient halo, sphere;
    private float radius, cx, cy;
    float level;
    private float drawnLevel = Float.MIN_VALUE;
    private long levelMovedAt;

    CompanionOrbView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Full rate while reacting to a recent level change, coarse once idle. */
    static long frameDelayMillis(long sinceLevelChangeMillis) {
        return sinceLevelChangeMillis < LEVEL_HOLD_MILLIS ? ACTIVE_FRAME_MILLIS : IDLE_FRAME_MILLIS;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cx = w * .5f; cy = h * .48f; radius = Math.min(w, h) * .32f;
        if (radius <= 0) return;
        halo = new RadialGradient(cx, cy, radius * 1.65f,
            new int[]{0x286e9edc, 0x0c96b8e8, 0x0096b8e8}, null, Shader.TileMode.CLAMP);
        sphere = new RadialGradient(cx - radius * .35f, cy - radius * .45f, radius * 1.65f,
            new int[]{0xffffffff, 0xffeef2f8, 0xffb9c9df, 0xff637f9e},
            new float[]{0, .35f, .72f, 1}, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0) return;
        long now = android.os.SystemClock.uptimeMillis();
        // The meter updates level without invalidating; sampling it here is what keeps the loop reactive.
        if (level != drawnLevel) { drawnLevel = level; levelMovedAt = now; }
        boolean animate = ValueAnimator.areAnimatorsEnabled();
        float phase = animate ? (now % 18000) / 18000f * 6.283f : 0;
        float pulse = 1 + (float) Math.sin(phase) * .018f + level * .035f;
        canvas.save(); canvas.scale(pulse, pulse, cx, cy);
        paint.setStyle(Paint.Style.FILL); paint.setShader(halo);
        canvas.drawCircle(cx, cy, radius * 1.65f, paint);
        paint.setShader(sphere); canvas.drawCircle(cx, cy, radius, paint); paint.setShader(null);
        // Fine curved contours give the sphere depth without bitmap assets.
        canvas.save(); path.reset(); path.addCircle(cx, cy, radius, Path.Direction.CW); canvas.clipPath(path);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(getResources().getDisplayMetrics().density * .65f);
        for (int i = -6; i <= 6; i++) {
            float y = cy + i * radius / 6f;
            paint.setColor(i % 3 == 0 ? 0x65ffffff : 0x30ffffff);
            path.reset(); path.moveTo(cx - radius * 1.1f, y);
            path.cubicTo(cx - radius * .25f, y - radius * .42f + (float) Math.sin(phase) * radius * .08f,
                cx + radius * .35f, y + radius * .48f, cx + radius * 1.1f, y - radius * .05f);
            canvas.drawPath(path, paint);
        }
        canvas.restore();
        paint.setColor(0x80ffffff); paint.setStrokeWidth(getResources().getDisplayMetrics().density * .7f);
        canvas.drawCircle(cx, cy, radius, paint);
        canvas.restore();
        if (animate && isShown() && getWindowVisibility() == VISIBLE && getLocalVisibleRect(visibleArea))
            postInvalidateDelayed(frameDelayMillis(now - levelMovedAt));
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) invalidate();
    }
}
