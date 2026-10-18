package com.aidol.demo;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.function.IntConsumer;

/** Compact single-choice sheet: selection is visible before the explicit confirmation. */
final class RoundedChoiceDialog extends Dialog {
    private final Activity activity;
    private final LinearLayout[] options;
    private final TextView[] labels, marks;
    private int selected;
    RoundedChoiceDialog(Activity activity, String title, String note, String[] values, int initial,
                        String confirmLabel, IntConsumer confirm) {
        super(activity); this.activity = activity;
        if (values.length == 0 || initial < 0 || initial >= values.length) throw new IllegalArgumentException("Invalid choices");
        selected = initial; options = new LinearLayout[values.length]; labels = new TextView[values.length]; marks = new TextView[values.length];
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = EditorUi.column(activity); root.setPadding(dp(20), dp(18), dp(20), dp(18));
        root.setBackground(EditorUi.shape(activity, 0xfff8faff, 24, EditorUi.LINE)); root.setClipToOutline(true);
        TextView heading = EditorUi.text(activity, title, 20, EditorUi.INK); heading.setTypeface(null, Typeface.BOLD); root.addView(heading);
        if (!note.isEmpty()) root.addView(EditorUi.text(activity, note, 12, EditorUi.MUTED));
        LinearLayout list = EditorUi.column(activity);
        ScrollView scroller = new ScrollView(activity) {
            @Override protected void onMeasure(int width, int height) {
                int maximum = Math.round(activity.getResources().getDisplayMetrics().heightPixels * .52f);
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST));
            }
        };
        scroller.setFillViewport(false); scroller.setVerticalScrollBarEnabled(false); scroller.addView(list);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < values.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(14), dp(12), dp(14), dp(12)); row.setMinimumHeight(dp(56));
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2); spacing.setMargins(0, dp(5), 0, dp(5)); list.addView(row, spacing);
            TextView label = EditorUi.text(activity, values[i], 15, EditorUi.INK); label.setPadding(0, 0, dp(10), 0); row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            TextView mark = EditorUi.text(activity, "", 15, Color.WHITE); mark.setGravity(Gravity.CENTER); mark.setPadding(0, 0, 0, 0); row.addView(mark, new LinearLayout.LayoutParams(dp(24), dp(24)));
            options[i] = row; labels[i] = label; marks[i] = mark;
            row.setFocusable(true); row.setClickable(true); row.setOnClickListener(v -> select(index, true));
        }
        select(initial, false);
        TextView apply = EditorUi.button(activity, confirmLabel, true, () -> { int value = selected; dismiss(); confirm.accept(value); });
        LinearLayout.LayoutParams applySpace = new LinearLayout.LayoutParams(-1, -2); applySpace.topMargin = dp(12); root.addView(apply, applySpace);
        TextView cancel = EditorUi.button(activity, "取消", false, this::cancel); LinearLayout.LayoutParams cancelSpace = new LinearLayout.LayoutParams(-1, -2); cancelSpace.topMargin = dp(8); root.addView(cancel, cancelSpace);
        setContentView(root); getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        setOnShowListener(d -> {
            getWindow().setLayout(Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(28), dp(480)), ViewGroup.LayoutParams.WRAP_CONTENT);
            root.setAlpha(0); root.setTranslationY(dp(12)); root.animate().alpha(1).translationY(0).setDuration(180).start();
        });
    }
    int selectedIndex() { return selected; }
    View optionAt(int index) { return options[index]; }
    private int dp(float value) { return EditorUi.dp(activity, value); }
    private void select(int index, boolean animate) {
        selected = index;
        for (int i = 0; i < options.length; i++) {
            boolean active = i == index; options[i].setSelected(active);
            GradientDrawable background = EditorUi.shape(activity, active ? 0xffedf3ff : Color.WHITE, 16, active ? EditorUi.ACCENT : EditorUi.LINE);
            background.setStroke(dp(active ? 2 : 1), active ? EditorUi.ACCENT : EditorUi.LINE); options[i].setBackground(background);
            labels[i].setTextColor(active ? EditorUi.ACCENT : EditorUi.INK); labels[i].setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            marks[i].setText(active ? "✓" : ""); marks[i].setBackground(EditorUi.shape(activity, active ? EditorUi.ACCENT : Color.WHITE, 50, active ? EditorUi.ACCENT : EditorUi.LINE));
            options[i].setContentDescription(labels[i].getText() + (active ? "，已选择" : "，未选择"));
        }
        if (animate) { options[index].setScaleX(.985f); options[index].setScaleY(.985f); options[index].animate().scaleX(1).scaleY(1).setDuration(160).start(); }
    }
}
