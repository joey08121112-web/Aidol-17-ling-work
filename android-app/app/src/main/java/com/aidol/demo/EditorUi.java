package com.aidol.demo;

import android.app.*;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

final class EditorUi {
    static final int INK = 0xff252b36, MUTED = 0xff697381, ACCENT = 0xff2868df, LINE = 0xffe3e8ef, RED = 0xffb42318;
    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    static GradientDrawable shape(Context c, int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(c, radius)); d.setStroke(dp(c, 1), stroke); return d;
    }
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    static TextView text(Context c, String value, int size, int color) {
        TextView v = new TextView(c); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setPadding(0, dp(c, 6), 0, dp(c, 6)); return v;
    }
    static EditText input(Context c, LinearLayout parent, String title, String value, boolean multi) {
        parent.addView(text(c, title, 12, MUTED)); EditText field = new EditText(c);
        field.setText(value); field.setTextSize(15); field.setTextColor(INK); field.setGravity(Gravity.TOP);
        field.setPadding(dp(c, 12), dp(c, 11), dp(c, 12), dp(c, 11));
        field.setBackground(shape(c, Color.WHITE, 14, LINE)); field.setSingleLine(!multi);
        if (multi) { field.setMinLines(2); field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); }
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2)); return field;
    }
    static TextView button(Context c, String value, boolean primary, Runnable action) {
        TextView v = text(c, value, 14, primary ? Color.WHITE : ACCENT); v.setGravity(Gravity.CENTER); v.setMinHeight(dp(c, 46));
        v.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10)); v.setBackground(shape(c, primary ? ACCENT : Color.WHITE, 16, primary ? ACCENT : LINE));
        v.setFocusable(true); v.setClickable(true); v.setOnClickListener(x -> action.run()); return v;
    }
    static Dialog dialog(Activity activity, String title, LinearLayout body, LinearLayout footer) {
        Dialog dialog = new Dialog(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = column(activity); root.setPadding(dp(activity, 20), dp(activity, 14), dp(activity, 20), dp(activity, 18));
        root.setBackground(shape(activity, 0xfff8faff, 24, LINE)); root.setClipToOutline(true);
        TextView heading = text(activity, title, 20, INK); heading.setTypeface(null, android.graphics.Typeface.BOLD); root.addView(heading);
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(false); scroll.setClipToPadding(false); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); root.addView(footer);
        dialog.setContentView(root); Window window = dialog.getWindow(); window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.setOnShowListener(d -> { root.setAlpha(0); root.setTranslationY(dp(activity, 14)); root.animate().alpha(1).translationY(0).setDuration(200).start(); });
        window.setLayout(activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 28), Math.round(activity.getResources().getDisplayMetrics().heightPixels * .86f));
        return dialog;
    }
}
