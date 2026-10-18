package com.aidol.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A generous tap target around a simple outlined/filled bookmark. */
final class MeetingBookmarkView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    MeetingBookmarkView(Context context, boolean pinned, String title) {
        super(context); setSelected(pinned); setFocusable(true); setClickable(true);
        setContentDescription((pinned ? "取消会议置顶：" : "置顶会议：") + title);
        setBackground(EditorUi.shape(context, pinned ? 0xfffff0f0 : 0xfff8faff, 14, pinned ? 0xfff2d8dc : 0xfff8faff));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density, x = getWidth() / 2f, y = getHeight() / 2f;
        Path path = new Path();
        path.moveTo(x - 8*d, y - 9*d); path.quadTo(x - 8*d, y - 11*d, x - 6*d, y - 11*d);
        path.lineTo(x + 6*d, y - 11*d); path.quadTo(x + 8*d, y - 11*d, x + 8*d, y - 9*d);
        path.lineTo(x + 8*d, y + 11*d); path.lineTo(x, y + 6*d); path.lineTo(x - 8*d, y + 11*d); path.close();
        paint.setColor(isSelected() ? 0xffda4c58 : EditorUi.MUTED);
        paint.setStyle(isSelected() ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f*d); paint.setStrokeJoin(Paint.Join.ROUND); canvas.drawPath(path, paint);
    }
}
