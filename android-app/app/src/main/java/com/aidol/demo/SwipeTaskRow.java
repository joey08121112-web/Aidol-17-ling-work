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
    private Runnable touched = () -> {}, opened = () -> {};
    SwipeTaskRow(Context context) { this(context, 176); }
    SwipeTaskRow(Context context, int widthDp) { super(context); reveal = widthDp * getResources().getDisplayMetrics().density; slop = ViewConfiguration.get(context).getScaledTouchSlop(); }
    void foreground(View value) { card = value; card.setClickable(true); }
    /** touched: a gesture starts on this row; opened: its actions settle fully revealed. */
    void listeners(Runnable touched, Runnable opened) { this.touched = touched; this.opened = opened; }
    boolean isOpen() { return card != null && card.getTranslationX() < 0; }
    void close() { if (isOpen()) card.animate().translationX(0).setDuration(180).start(); }
    @Override public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touched.run();
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
            boolean open = card.getTranslationX() < -reveal * .4f;
            card.animate().translationX(open ? -reveal : 0).setDuration(200).start();
            if (open) opened.run();
            dragging = false; getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }
}
