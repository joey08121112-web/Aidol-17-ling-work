package com.aidol.demo;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated offline character review. Never signs in, records or contacts a provider. */
public final class AvatarReviewInstrumentation extends Instrumentation {
    private volatile MainActivity activity;
    private File output;
    private boolean postersOnly;
    private String clipOnly = "";
    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        postersOnly = args != null && "true".equals(args.getString("posters_only"));
        clipOnly = args == null ? "" : args.getString("clip_only", "");
        start();
    }
    @Override public void callActivityOnCreate(Activity created, Bundle state) {
        super.callActivityOnCreate(created, state);
        if (created instanceof MainActivity) activity = (MainActivity)created;
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); int outcome = Activity.RESULT_CANCELED;
        try {
            check(getTargetContext().getPackageName().endsWith(".avatartest"), "Use the isolated aidolVisualReview + aidolAvatarReview build");
            DevelopmentSettings settings = new DevelopmentSettings(getTargetContext());
            check(new ApiClient(getTargetContext(), settings.serverUrl()).vault.token().isEmpty(), "Review app must have no account");
            getTargetContext().getSharedPreferences("com.aidol.demo.MainActivity", Context.MODE_PRIVATE).edit()
                .putBoolean("auto_bluetooth_asked", true).putBoolean("startup_location_asked", true).commit();
            // Some device instrumentation waits forever for an animated activity to become idle.
            // Observe its lifecycle and layout instead of using startActivitySync's unbounded idle wait.
            // MIUI may reject an app-origin background launch even during instrumentation.
            // The shell starts the same target activity under this instrumentation process.
            try (android.os.ParcelFileDescriptor launch = getUiAutomation().executeShellCommand(
                    "am start -n " + getTargetContext().getPackageName() + "/com.aidol.demo.MainActivity");
                 java.io.InputStream stream = new android.os.ParcelFileDescriptor.AutoCloseInputStream(launch)) {
                byte[] buffer = new byte[1024];
                while (stream.read(buffer) != -1) { }
            }
            await(() -> activity != null, 10000, "Activity did not start");
            output = new File(getTargetContext().getExternalFilesDir(null), "avatar-review");
            check(output.isDirectory() || output.mkdirs(), "Cannot create review directory");
            main(() -> {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                set("state", fixture("commute".equals(clipOnly) ? "commute" : "life")); set("tab", 0); invoke("showApp");
            });
            CompanionAvatarView life = avatar("orb");
            if (!clipOnly.isEmpty()) {
                await(() -> playing(life), 8000, "Transparent video did not render");
                main(() -> { checkSilent(life); checkTransparent(life, clipOnly); });
                capture("gl-" + clipOnly);
                main(() -> ((View)field(life, "picture")).setBackgroundColor(0xffe9f1fb));
                SystemClock.sleep(1000); capture("gl-" + clipOnly + "-blue");
                main(() -> { life.setHostVisible(false); check(field(life, "player") == null, "Hidden video retained decoder"); });
                main(() -> life.setHostVisible(true));
                await(() -> playing(life), 8000, "Transparent video did not resume");
                result.putString("stream", "PASS: " + clipOnly + " transparent video rendered with zero-alpha corners, opaque character, no sound track, and hide/resume.");
                outcome = Activity.RESULT_OK;
            } else if (postersOnly) {
                await(() -> (Boolean)field(life, "failed"), 8000, "Mismatched video did not safely fall back");
                main(() -> check(field(life, "player") == null
                    && ((ImageView)field(life, "poster")).getAlpha() == 1, "Invalid video exposed a frame"));
                main(() -> check(((ImageView)field(life, "poster")).getDrawable().getAlpha() == 255,
                    "Poster drawable must remain opaque within its own transparent silhouette"));
                capture("poster-life");
                main(() -> { set("state", fixture("commute")); invoke("renderModeSelection"); });
                capture("poster-commute");
                main(() -> { set("state", fixture("work")); invoke("renderModeSelection"); invoke("switchTab", new Class<?>[]{int.class}, 2); });
                capture("poster-work");
                result.putString("stream", "PASS: ordinary video rejected before playback, transparent poster fallback and three-mode layouts captured. Offline fixtures only.");
                outcome = Activity.RESULT_OK;
            } else {
            await(() -> playing(life), 8000, "Life video did not render");
            main(() -> { checkSilent(life); checkTransparent(life, "life"); }); capture("01-life-idle");
            main(() -> ((View)field(life, "picture")).setBackgroundColor(0xffe9f1fb));
            capture("01-life-on-blue");
            main(() -> ((View)field(life, "picture")).setBackgroundColor(Color.TRANSPARENT));
            phase(CompanionPhase.LISTENING);
            main(() -> life.inputLevel(.7f)); capture("02-life-listening");
            phase(CompanionPhase.THINKING); capture("03-life-thinking");
            phase(CompanionPhase.SPEAKING);
            main(() -> life.outputLevel(.8f)); capture("04-life-speaking");
            main(() -> invoke("updateLifeKeyboard", new Class<?>[]{boolean.class}, true));
            main(() -> check(field(life, "player") == null && life.getVisibility() == View.GONE, "Keyboard did not release video"));
            main(() -> invoke("updateLifeKeyboard", new Class<?>[]{boolean.class}, false));
            await(() -> playing(life), 8000, "Video did not resume after keyboard");
            main(() -> { life.setHostVisible(false); check(field(life, "player") == null, "Hidden host retained decoder"); });
            main(() -> life.setHostVisible(true));
            await(() -> playing(life), 8000, "Host visibility did not restore video");
            phase(CompanionPhase.RESTING);
            await(() -> playing(life), 8000, "Closing video did not render");
            main(() -> checkTransparent(life, "rest"));
            SystemClock.sleep(2200); capture("05-ending-gesture");
            await(() -> field(life, "player") == null, 8000, "Closing video did not complete");
            main(() -> check((Integer)field(life, "posterResource") == R.drawable.companion_rest_end,
                "Closing video did not hold final poster"));
            // Restore an already-ended gesture after a full page rebuild; it must not lie down again.
            main(() -> {
                invoke("setAvatarPhase", new Class<?>[]{CompanionPhase.class}, CompanionPhase.RESTING);
                set("avatarPhaseStartedAt", SystemClock.uptimeMillis() - 10000);
                invoke("markTabsDirty"); invoke("renderTab");
            });
            CompanionAvatarView resting = avatar("orb");
            main(() -> {
                check(resting.phase() == CompanionPhase.RESTING, "Rebuild lost resting state");
                check(field(resting, "player") == null, "Rebuild replayed completed rest clip");
                check((Integer)field(resting, "posterResource") == R.drawable.companion_rest_end, "Missing resting final frame");
            }); capture("05-ended");
            phase(CompanionPhase.LISTENING);
            await(() -> playing(resting), 8000, "New call did not wake character");
            main(() -> {
                set("state", fixture("commute")); invoke("renderModeSelection");
            });
            CompanionAvatarView commute = avatar("orb");
            await(() -> playing(commute), 8000, "Commute video did not render");
            main(() -> { checkSilent(commute); checkTransparent(commute, "commute"); }); capture("06-commute");
            // Rapid replacements must leave the latest mode/phase, including delayed prepare callbacks.
            main(() -> {
                commute.show("life", CompanionPhase.RESTING);
                commute.show("commute", CompanionPhase.THINKING);
                commute.show("commute", CompanionPhase.LISTENING);
            });
            await(() -> playing(commute), 8000, "Rapid replacement failed");
            main(() -> check(commute.phase() == CompanionPhase.LISTENING
                && commute.getContentDescription().toString().contains("通勤"), "Stale prepare changed latest state"));
            main(() -> { set("state", fixture("work")); invoke("renderModeSelection"); invoke("switchTab", new Class<?>[]{int.class}, 2); });
            CompanionAvatarView work = avatar("workAvatar");
            phase(CompanionPhase.RECORDING);
            main(() -> { check(field(work, "player") == null, "Work must keep a quiet poster"); work.inputLevel(.6f); });
            capture("07-work-recording");
            phase(CompanionPhase.PAUSED);
            main(() -> check(work.label().contains("暂停"), "Pause label missing")); capture("08-work-paused");
            phase(CompanionPhase.SAVING);
            main(() -> {
                JSONObject state = (JSONObject)get("state");
                state.put("work_meetings", new JSONArray().put(new JSONObject().put("id", "offline-avatar-meeting")
                    .put("status", "saved").put("transcription_status", "ready").put("summary_status", "pending")));
                set("avatarMeetingId", "offline-avatar-meeting"); invoke("updateWorkAvatar");
                check(work.phase() == CompanionPhase.SUMMARIZING, "Saved audio claimed summary complete");
            }); capture("09-work-summarizing");
            main(() -> {
                JSONObject meeting = ((JSONObject)get("state")).getJSONArray("work_meetings").getJSONObject(0);
                meeting.put("summary_status", "ready"); invoke("updateWorkAvatar");
                check(work.phase() == CompanionPhase.COMPLETE, "Summary completion was not applied");
                meeting.put("status", "partial"); invoke("updateWorkAvatar");
                check(work.phase() == CompanionPhase.ERROR, "Partial recording looked complete");
                check(get("session") == null, "Visual review unexpectedly opened a session");
                check(((ApiClient)get("api")).vault.token().isEmpty(), "Visual review unexpectedly signed in");
            }); capture("10-work-error");
            result.putString("stream", "PASS: transparent H264 RGB/alpha video rendered with transparent corners and opaque character pixels; no audio track; life/commute phases; hidden/keyboard release and resume; stale prepare isolation; closing playback and completed-rest rebuild; work recording/pause/save/summary/partial states. Offline UI fixtures only, no physical microphone or AI audio test. Screenshots: " + output);
            outcome = Activity.RESULT_OK;
            }
        } catch (Throwable error) { result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); }
        finally { if (activity != null) try { main(() -> activity.finish()); } catch (Exception ignored) {} }
        finish(outcome, result);
    }
    private JSONObject fixture(String mode) throws Exception {
        JSONArray conversation = new JSONArray()
            .put(new JSONObject().put("speaker", "user").put("text", "今天想慢下来，去附近走走。"))
            .put(new JSONObject().put("speaker", "assistant").put("text", "那就留一点时间给自己。想去公园散散步，还是找一家安静的咖啡馆？"));
        return new JSONObject().put("settings", new JSONObject().put("mode", mode)).put("agent", new JSONObject().put("mode", mode))
            .put("capabilities", new JSONObject()).put("transcripts", conversation).put("work_meetings", new JSONArray());
    }
    private void phase(CompanionPhase phase) throws Exception {
        main(() -> invoke("setAvatarPhase", new Class<?>[]{CompanionPhase.class}, phase));
        main(() -> {});
    }
    private CompanionAvatarView avatar(String name) throws Exception { return (CompanionAvatarView)get(name); }
    private boolean playing(CompanionAvatarView avatar) throws Exception {
        AtomicReference<Boolean> value = new AtomicReference<>(false);
        main(() -> { MediaPlayer player = (MediaPlayer)field(avatar, "player");
            value.set(player != null && (Boolean)field(avatar, "prepared") && player.isPlaying()
                && ((ImageView)field(avatar, "poster")).getAlpha() < .1f); });
        return value.get();
    }
    private void checkSilent(CompanionAvatarView avatar) throws Exception {
        for (MediaPlayer.TrackInfo track : ((MediaPlayer)field(avatar, "player")).getTrackInfo())
            check(track.getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO, "Character video contains an audio track");
    }
    private void checkTransparent(CompanionAvatarView avatar, String name) throws Exception {
        Bitmap bitmap = ((TextureView)field(avatar, "texture")).getBitmap();
        check(bitmap != null, name + " rendered texture is unavailable");
        try {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            for (int x : new int[]{2, width - 3}) for (int y : new int[]{2, height - 3})
                check(Color.alpha(bitmap.getPixel(x, y)) < 12, name + " corner is not transparent");
            int opaque = 0, samples = 0;
            for (int y = 0; y < height; y += 3) for (int x = 0; x < width; x += 3) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 245) opaque++;
                samples++;
            }
            check(opaque > samples / 12, name + " character is missing or translucent");
            try (FileOutputStream stream = new FileOutputStream(new File(output, name + "-texture.png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
        } finally { bitmap.recycle(); }
    }
    private interface Checked { void run() throws Exception; }
    private interface Condition { boolean get() throws Exception; }
    private void main(Checked action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try { action.run(); } catch (Throwable error) { failure.set(error); }
            finally { completed.countDown(); }
        });
        check(completed.await(10, TimeUnit.SECONDS), "UI thread did not respond within 10 seconds");
        if (failure.get() != null) throw new Exception(failure.get());
    }
    private void await(Condition condition, long timeout, String failure) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) { if (condition.get()) return; SystemClock.sleep(80); }
        throw new AssertionError(failure);
    }
    private void capture(String name) throws Exception {
        main(() -> {}); SystemClock.sleep(180);
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        check(bitmap != null, "Screenshot unavailable");
        try (FileOutputStream stream = new FileOutputStream(new File(output, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        finally { bitmap.recycle(); }
        android.util.Log.i("AvatarReview", "Captured " + name);
    }
    private static Object field(Object target, String name) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private Object get(String name) throws Exception { return field(activity, name); }
    private void set(String name, Object value) throws Exception { Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(activity, value); }
    private void invoke(String name) throws Exception { invoke(name, new Class<?>[]{}); }
    private void invoke(String name, Class<?>[] types, Object... args) throws Exception { Method m = MainActivity.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(activity, args); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
