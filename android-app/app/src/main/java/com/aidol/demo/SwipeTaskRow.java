package com.aidol.demo;

import android.content.Context;
import android.view.*;
import android.widget.FrameLayout;

/** Horizontal reveal leaves vertical scrolling and checkbox clicks to their normal owners. */
final class SwipeTaskRow extends FrameLayout {
    private View card;
    private float startX, startY, startOffset;
    private boolean dragging;
    private final float reveal;
    private final int slop;
    SwipeTaskRow(Context context) { super(context); reveal = 88 * getResources().getDisplayMetrics().density; slop = ViewConfiguration.get(context).getScaledTouchSlop(); }
    void foreground(View value) { card = value; }
    @Override public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            startX = e.getX(); startY = e.getY(); startOffset = card.getTranslationX(); dragging = false;
            card.animate().cancel(); getParent().requestDisallowInterceptTouchEvent(true);
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float dx = e.getX() - startX, dy = e.getY() - startY;
            if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) getParent().requestDisallowInterceptTouchEvent(false);
            if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.4f && (dx < 0 || startOffset < 0)) {
                dragging = true; getParent().requestDisallowInterceptTouchEvent(true); return true;
            }
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) getParent().requestDisallowInterceptTouchEvent(false);
        return false;
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent e) {
        if (!dragging) return super.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE) card.setTranslationX(Math.max(-reveal, Math.min(0, startOffset + e.getX() - startX)));
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            card.animate().translationX(card.getTranslationX() < -reveal * .4f ? -reveal : 0).setDuration(200).start();
            dragging = false; getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }
}
