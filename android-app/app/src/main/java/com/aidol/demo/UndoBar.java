package com.aidol.demo;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One bottom undo prompt at a time. Expiry or a newer prompt commits the shown action; 撤销 skips it. */
final class UndoBar {
    static final long DURATION_MILLIS = 4000;
    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable expire = this::commitShown;
    private View bar;
    private Runnable commit;

    UndoBar(Activity activity) { this.activity = activity; }

    /** commit runs when the prompt expires or is replaced; undo runs instead when the user taps 撤销. */
    void show(String message, Runnable undo, Runnable commit) {
        commitShown();
        if (activity.isFinishing() || activity.isDestroyed()) { if (commit != null) commit.run(); return; }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) { if (commit != null) commit.run(); return; }
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(4), dp(6), dp(4));
        row.setBackground(EditorUi.shape(activity, 0xee263445, 16, 0xee263445));
        row.setElevation(dp(6));
        row.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        TextView text = new TextView(activity);
        text.setText(message); text.setTextSize(14); text.setTextColor(Color.WHITE); text.setMaxLines(2);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView action = new TextView(activity);
        action.setText("撤销"); action.setTextSize(14); action.setTextColor(0xff9cc0ff);
        action.setTypeface(null, Typeface.BOLD); action.setGravity(Gravity.CENTER);
        action.setMinWidth(dp(64)); action.setMinHeight(dp(48));
        action.setContentDescription("撤销：" + message);
        action.setOnClickListener(v -> { if (bar != row) return; dismiss(); undo.run(); });
        row.addView(action, new LinearLayout.LayoutParams(-2, -2));
        WindowInsets insets = content.getRootWindowInsets();
        int system = insets == null ? 0 : insets.getSystemWindowInsetBottom();
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        // Sits just above the bottom navigation, which the activity pads above the system bar.
        position.setMargins(dp(16), 0, dp(16), system + dp(84));
        content.addView(row, position);
        bar = row; this.commit = commit;
        ui.postDelayed(expire, DURATION_MILLIS);
    }
    /** Lets a pending action proceed now (expiry, replacement or teardown). */
    void commitShown() {
        Runnable pending = commit;
        dismiss();
        if (pending != null) pending.run();
    }
    private void dismiss() {
        ui.removeCallbacks(expire);
        commit = null;
        if (bar != null && bar.getParent() instanceof ViewGroup) ((ViewGroup) bar.getParent()).removeView(bar);
        bar = null;
    }
    private int dp(float value) { return EditorUi.dp(activity, value); }
}
