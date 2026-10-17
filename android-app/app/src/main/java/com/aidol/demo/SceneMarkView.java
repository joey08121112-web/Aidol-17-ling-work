package com.aidol.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Decorative native scene marks; no status, metrics or device capabilities implied. */
final class SceneMarkView extends View {
    private static final float[] WAVE_HEIGHTS = {8, 17, 25, 13, 6};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final int scene, accent;

    public SceneMarkView(Context context) { this(context, 1, 0xff7356a6); }

    SceneMarkView(Context context, int scene, int accent) {
        super(context);
        this.scene = scene;
        this.accent = accent;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth() / 80f, getHeight() / 80f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((accent & 0x00ffffff) | 0x0c000000);
        canvas.drawCircle(42, 40, 34, paint);
        if (scene == 1) {
            canvas.save(); canvas.rotate(-12, 40, 40);
            panel(canvas, 19, 15, 57, 61, 0xffe9e2f3);
            canvas.restore();
            panel(canvas, 27, 20, 64, 66, Color.WHITE);
            stroke(accent, 2);
            canvas.drawLine(35, 34, 54, 34, paint);
            canvas.drawLine(35, 42, 54, 42, paint);
            canvas.drawLine(35, 50, 46, 50, paint);
            paint.setStyle(Paint.Style.FILL); canvas.drawCircle(61, 20, 5, paint);
        } else if (scene == 2) {
            panel(canvas, 16, 21, 66, 62, 0xffe1eafb);
            panel(canvas, 21, 16, 61, 57, Color.WHITE);
            stroke(accent, 3);
            for (int i = 0; i < 5; i++) {
                float height = WAVE_HEIGHTS[i];
                canvas.drawLine(29 + i * 6, 37 - height / 2, 29 + i * 6, 37 + height / 2, paint);
            }
            paint.setStyle(Paint.Style.FILL); paint.setColor(accent); canvas.drawCircle(60, 59, 9, paint);
            stroke(Color.WHITE, 1.8f); canvas.drawLine(56, 59, 59, 62, paint); canvas.drawLine(59, 62, 64, 56, paint);
        } else if (scene == 3) {
            stroke(0xffcee3dc, 5);
            path.reset(); path.moveTo(18, 58); path.lineTo(32, 44); path.lineTo(50, 52); path.lineTo(64, 32); canvas.drawPath(path, paint);
            stroke(accent, 2);
            path.reset(); path.moveTo(47, 45); path.cubicTo(42, 38, 35, 31, 35, 24); path.cubicTo(35, 7, 59, 7, 59, 24); path.cubicTo(59, 31, 52, 38, 47, 45); canvas.drawPath(path, paint);
            canvas.drawCircle(47, 24, 4, paint);
            paint.setStyle(Paint.Style.FILL); canvas.drawCircle(18, 58, 4, paint);
        } else {
            stroke(0xffb9cae5, 1.5f); canvas.drawCircle(41, 40, 25, paint);
            canvas.save(); canvas.rotate(-25, 41, 40); rect.set(9, 28, 73, 52); canvas.drawOval(rect, paint); canvas.restore();
            paint.setStyle(Paint.Style.FILL); paint.setColor(accent); canvas.drawCircle(41, 40, 12, paint);
            paint.setColor(0xff9bb4dc); canvas.drawCircle(64, 25, 5, paint);
        }
        canvas.restore();
    }

    private void panel(Canvas canvas, float left, float top, float right, float bottom, int color) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(color);
        rect.set(left, top, right, bottom); canvas.drawRoundRect(rect, 7, 7, paint);
    }

    private void stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE); paint.setColor(color); paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }
}
