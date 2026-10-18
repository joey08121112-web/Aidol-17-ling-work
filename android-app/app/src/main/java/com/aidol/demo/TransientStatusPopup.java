package com.aidol.demo;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** A silent, non-interactive status bubble with an exact three-second dismissal. */
final class TransientStatusPopup {
    private final Activity activity;
    private final java.util.function.Supplier<View> anchor;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView bubble;
    private final java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
    private String shown;
    private final Runnable hide = this::expire;
    TransientStatusPopup(Activity activity, java.util.function.Supplier<View> anchor) { this.activity = activity; this.anchor = anchor; }
    void show(String message) {
        if (bubble != null && bubble.isAttachedToWindow()) {
            if (!message.equals(shown) && !pending.contains(message)) pending.addLast(message);
            return;
        }
        present(message);
    }
    private void present(String message) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        View target = anchor.get();
        if (!(target instanceof ViewGroup) || !target.isAttachedToWindow()) return;
        removeBubble();
        shown = message;
        TextView text = new TextView(activity);
        float density = activity.getResources().getDisplayMetrics().density;
        int padding = Math.round(14 * density);
        text.setText(message); text.setTextSize(13); text.setTextColor(Color.WHITE);
        text.setPadding(padding, padding / 2, padding, padding / 2);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xee263445); background.setCornerRadius(18 * density);
        text.setBackground(background);
        text.setClickable(false); text.setFocusable(false); text.setElevation(5 * density);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        position.topMargin = Math.round(90 * density);
        // Keep the existing window's focus and brightness while showing this bubble.
        ((ViewGroup) target).addView(text, position);
        bubble = text;
        ui.removeCallbacks(hide);
        ui.postDelayed(hide, 3000);
    }
    private void expire() {
        removeBubble();
        shown = null;
        if (!pending.isEmpty()) present(pending.removeFirst());
    }
    private void removeBubble() {
        if (bubble != null && bubble.getParent() instanceof ViewGroup)
            ((ViewGroup) bubble.getParent()).removeView(bubble);
        bubble = null;
    }
    void dismiss() {
        ui.removeCallbacks(hide);
        pending.clear(); shown = null;
        removeBubble();
    }
}
