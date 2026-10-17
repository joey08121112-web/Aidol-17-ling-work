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
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Rect visibleArea = new Rect();
    private RadialGradient halo, sphere;
    private float radius, cx, cy;
    float level;

    CompanionOrbView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cx = w * .5f; cy = h * .49f; radius = Math.min(w, h) * .32f;
        if (radius <= 0) return;
        halo = new RadialGradient(cx, cy, radius * 1.65f,
            new int[]{0x505bc49b, 0x183e8c70, 0x003e8c70}, null, Shader.TileMode.CLAMP);
        sphere = new RadialGradient(cx - radius * .35f, cy - radius * .45f, radius * 1.65f,
            new int[]{0xffe7f7c9, 0xffa9d9b7, 0xff4f9a82, 0xff173f36},
            new float[]{0, .32f, .65f, 1}, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0) return;
        boolean animate = ValueAnimator.areAnimatorsEnabled();
        float phase = animate ? (android.os.SystemClock.uptimeMillis() % 18000) / 18000f * 6.283f : 0;
        float pulse = 1 + (float) Math.sin(phase) * .018f + level * .035f;
        canvas.save(); canvas.scale(pulse, pulse, cx, cy);
        paint.setStyle(Paint.Style.FILL); paint.setShader(halo);
        canvas.drawCircle(cx, cy, radius * 1.65f, paint);
        paint.setShader(sphere); canvas.drawCircle(cx, cy, radius, paint); paint.setShader(null);
        // Fine curved contours give the sphere depth without bitmap assets.
        canvas.save(); path.reset(); path.addCircle(cx, cy, radius, Path.Direction.CW); canvas.clipPath(path);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(getResources().getDisplayMetrics().density * .65f);
        for (int i = -12; i <= 12; i++) {
            float y = cy + i * radius / 12f;
            paint.setColor(i % 3 == 0 ? 0x55f2ffe1 : 0x28e9ffde);
            path.reset(); path.moveTo(cx - radius * 1.1f, y);
            path.cubicTo(cx - radius * .25f, y - radius * .42f + (float) Math.sin(phase) * radius * .08f,
                cx + radius * .35f, y + radius * .48f, cx + radius * 1.1f, y - radius * .05f);
            canvas.drawPath(path, paint);
        }
        canvas.restore();
        paint.setColor(0x65d6f5c8); paint.setStrokeWidth(1);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(0x385d9e85);
        canvas.drawOval(cx - radius * 1.32f, cy - radius * 1.18f, cx + radius * 1.32f, cy + radius * 1.18f, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xffd7ecb8);
        canvas.drawCircle(cx + radius * 1.2f, cy - radius * .5f, radius * .027f, paint);
        canvas.restore();
        if (animate && isShown() && getWindowVisibility() == VISIBLE && getLocalVisibleRect(visibleArea)) postInvalidateDelayed(40);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) invalidate();
    }
}
