package com.aidol.demo;

import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** A muted, local character layer. It owns no microphone, audio focus or session state. */
final class CompanionAvatarView extends FrameLayout implements TextureView.SurfaceTextureListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout picture;
    private final TextureView texture;
    private final ImageView poster;
    private final PhaseRing ring;
    private final TextView status;
    private final Rect visibleRect = new Rect();
    private final PowerManager power;
    private final KeyguardManager keyguard;
    private final ViewTreeObserver.OnPreDrawListener visibilityCheck = () -> {
        synchronizePlayback();
        return true;
    };
    private final ContentObserver motionObserver = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { synchronizePlayback(); }
    };
    private final BroadcastReceiver screenEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { synchronizePlayback(); }
    };

    private String mode = "life";
    private CompanionPhase phase = CompanionPhase.IDLE;
    private CompanionVisualPolicy.Asset asset = CompanionVisualPolicy.Asset.LIFE;
    private MediaPlayer player;
    private AlphaVideoRenderer renderer;
    private ViewTreeObserver observedTree;
    private Runnable prepareTimeout;
    private long generation, phaseStartedAt = SystemClock.uptimeMillis();
    private long rendererToken, closingRendererToken;
    private int resumePosition, posterResource, imageSize, labelHeight, indicatorHeight;
    private boolean hostVisible = true, attached, prepared, failed, restCompleted;
    private boolean frameReceived, playbackStarted;
    private boolean receiverRegistered, observerRegistered, synchronizing, rendererClosing;

    CompanionAvatarView(Context context) {
        super(context);
        power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(false);
        picture = new FrameLayout(context);
        picture.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        picture.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        texture = new TextureView(context);
        texture.setOpaque(false);
        texture.setSurfaceTextureListener(this);
        picture.addView(texture, new FrameLayout.LayoutParams(-1, -1));
        poster = new ImageView(context);
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        picture.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        ring = new PhaseRing(context);
        addView(picture);
        addView(ring);
        status = new TextView(context);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setSingleLine(true);
        status.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(status);
        updatePresentation();
        showPoster();
    }

    void show(String newMode, CompanionPhase newPhase) {
        String normalized = CompanionVisualPolicy.mode(newMode);
        CompanionPhase next = newPhase == null ? CompanionPhase.IDLE : newPhase;
        long started = mode.equals(normalized) && phase == next ? phaseStartedAt : SystemClock.uptimeMillis();
        show(normalized, next, started);
    }

    /** The activity supplies this timestamp so rebuilding a page cannot replay the closing gesture. */
    void show(String newMode, CompanionPhase newPhase, long startedAtUptime) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> show(newMode, newPhase, startedAtUptime));
            return;
        }
        String normalized = CompanionVisualPolicy.mode(newMode);
        CompanionPhase next = newPhase == null ? CompanionPhase.IDLE : newPhase;
        long started = Math.max(0, Math.min(SystemClock.uptimeMillis(), startedAtUptime));
        boolean changed = !mode.equals(normalized) || phase != next || phaseStartedAt != started;
        if (!changed) { synchronizePlayback(); return; }
        CompanionVisualPolicy.Asset nextAsset = CompanionVisualPolicy.asset(normalized, next);
        boolean newVisual = asset != nextAsset
            || CompanionVisualPolicy.playsVideo(mode, phase, true)
                != CompanionVisualPolicy.playsVideo(normalized, next, true);
        if (newVisual || (next == CompanionPhase.RESTING && phaseStartedAt != started)) {
            releasePlayer(false);
            resumePosition = 0;
            failed = false;
            restCompleted = false;
        } else if (player != null && !prepared) {
            // Even when the asset is shared, a pending callback belongs to the previous UI state.
            releasePlayer(false);
        }
        mode = normalized;
        phase = next;
        phaseStartedAt = started;
        asset = nextAsset;
        updatePresentation();
        if (newVisual || player == null) showPoster();
        synchronizePlayback();
    }

    void inputLevel(float level) { ring.inputLevel(level); }
    void outputLevel(float level) { ring.outputLevel(level); }

    void setHostVisible(boolean visible) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> setHostVisible(visible));
            return;
        }
        hostVisible = visible;
        synchronizePlayback();
    }

    CompanionPhase phase() { return phase; }
    String label() { return status.getText().toString(); }

    private void updatePresentation() {
        String label = CompanionVisualPolicy.label(mode, phase);
        status.setText(label);
        status.setTextColor(phase == CompanionPhase.ERROR ? 0xffa84e48 : 0xff5a6654);
        setContentDescription(ModeCopy.name(mode) + "，" + label);
        ring.state(mode, phase);
    }

    private boolean visibleToUser() {
        return attached && hostVisible && isShown() && getWindowVisibility() == VISIBLE
            && getLocalVisibleRect(visibleRect)
            && (power == null || power.isInteractive())
            && (keyguard == null || !keyguard.isKeyguardLocked());
    }

    private void synchronizePlayback() {
        if (synchronizing || picture == null) return;
        synchronizing = true;
        try {
            boolean visible = visibleToUser();
            boolean motion = ValueAnimator.areAnimatorsEnabled();
            ring.active(visible, motion);
            if (phase == CompanionPhase.RESTING && (!motion
                || CompanionVisualPolicy.restPosition(phaseStartedAt, SystemClock.uptimeMillis())
                    >= CompanionVisualPolicy.REST_DURATION_MILLIS)) restCompleted = true;
            if (!visible || !CompanionVisualPolicy.playsVideo(mode, phase, motion) || restCompleted) {
                releasePlayer(true);
                showPoster();
                return;
            }
            if (player == null && !rendererClosing && !failed && texture.isAvailable()) preparePlayer();
        } finally { synchronizing = false; }
    }

    private int videoResource() {
        switch (asset) {
            case COMMUTE: return R.raw.companion_commute_loop;
            case REST: return R.raw.companion_rest_once;
            default: return R.raw.companion_life_loop;
        }
    }

    private int currentPoster() {
        switch (asset) {
            case COMMUTE: return R.drawable.companion_commute_poster;
            case REST: return restCompleted ? R.drawable.companion_rest_end : R.drawable.companion_rest_poster;
            default: return R.drawable.companion_life_poster;
        }
    }

    private void showPoster() {
        int resource = currentPoster();
        if (posterResource != resource) {
            posterResource = resource;
            poster.setImageResource(resource);
        }
        poster.animate().cancel();
        poster.setAlpha(1f);
        // Transparent posters cannot mask an old decoded frame from the previous asset.
        // Keep a not-yet-created TextureView drawable so Android can create its surface.
        texture.setAlpha(texture.isAvailable() ? 0 : 1);
    }

    private boolean current(MediaPlayer candidate, long token) { return player == candidate && generation == token; }

    private void preparePlayer() {
        SurfaceTexture available = texture.getSurfaceTexture();
        if (available == null) return;
        showPoster();
        final long token = ++generation;
        final MediaPlayer candidate = new MediaPlayer();
        player = candidate;
        prepared = false;
        frameReceived = playbackStarted = false;
        rendererToken = token;
        prepareTimeout = () -> fail(candidate, token);
        main.postDelayed(prepareTimeout, 5000);
        renderer = new AlphaVideoRenderer(available, texture.getWidth(), texture.getHeight(), main,
            new AlphaVideoRenderer.Listener() {
                public void surfaceReady(Surface decoderSurface) {
                    if (current(candidate, token)) configurePlayer(candidate, token, decoderSurface);
                }
                public void firstFrame() {
                    if (!current(candidate, token)) return;
                    // A decoder may submit its first frame before onPrepared reaches the UI.
                    // Keep that event for this generation, but never reveal an unchecked clip.
                    frameReceived = true;
                    showVideo(candidate, token);
                }
                public void failed() { fail(candidate, token); }
                public void released() {
                    if (rendererClosing && closingRendererToken == token) {
                        rendererClosing = false;
                        synchronizePlayback();
                    }
                }
            });
    }

    private void configurePlayer(MediaPlayer candidate, long token, Surface decoderSurface) {
        try {
            candidate.setSurface(decoderSurface);
            // Assets contain video only. No AudioManager, focus request, wake lock or audio session is used.
            candidate.setVolume(0, 0);
            candidate.setLooping(CompanionVisualPolicy.loops(phase));
            try (AssetFileDescriptor descriptor = getResources().openRawResourceFd(videoResource())) {
                if (descriptor == null) throw new IllegalStateException("Missing companion video");
                candidate.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
            }
            candidate.setOnPreparedListener(ready -> {
                if (!current(ready, token)) return;
                // The alpha shader requires two matching 480-square halves. Never expose an
                // ordinary single-frame video through it, including stale review resources.
                if (ready.getVideoWidth() != 960 || ready.getVideoHeight() != 480) {
                    fail(ready, token);
                    return;
                }
                prepared = true;
                if (!visibleToUser() || !ValueAnimator.areAnimatorsEnabled()) { synchronizePlayback(); return; }
                long position = phase == CompanionPhase.RESTING
                    ? CompanionVisualPolicy.restPosition(phaseStartedAt, SystemClock.uptimeMillis()) : resumePosition;
                if (phase == CompanionPhase.RESTING && position >= CompanionVisualPolicy.REST_DURATION_MILLIS) {
                    restCompleted = true;
                    synchronizePlayback();
                    return;
                }
                if (position > 0) {
                    ready.setOnSeekCompleteListener(seeked -> {
                        if (current(seeked, token)) startPrepared(seeked, token);
                    });
                    try { ready.seekTo(position, MediaPlayer.SEEK_CLOSEST); }
                    catch (RuntimeException problem) { fail(candidate, token); }
                } else startPrepared(ready, token);
            });
            candidate.setOnCompletionListener(completed -> {
                if (!current(completed, token) || phase != CompanionPhase.RESTING) return;
                restCompleted = true;
                releasePlayer(false);
                showPoster();
            });
            candidate.setOnErrorListener((broken, what, extra) -> {
                fail(broken, token);
                return true;
            });
            candidate.prepareAsync();
        } catch (Exception problem) { fail(candidate, token); }
    }

    private void startPrepared(MediaPlayer candidate, long token) {
        if (!current(candidate, token)) return;
        if (!visibleToUser() || !ValueAnimator.areAnimatorsEnabled()) { synchronizePlayback(); return; }
        try {
            candidate.start();
            playbackStarted = true;
            showVideo(candidate, token);
        }
        catch (RuntimeException problem) { fail(candidate, token); }
    }

    private void showVideo(MediaPlayer candidate, long token) {
        if (!current(candidate, token) || !prepared || !playbackStarted || !frameReceived
            || !visibleToUser() || !ValueAnimator.areAnimatorsEnabled()) return;
        clearTimeout();
        poster.animate().cancel();
        // Swap in one UI frame: crossfading two transparent silhouettes creates
        // doubled faces/hands when a resumed clip differs from its still poster.
        poster.setAlpha(0f);
        texture.setAlpha(1);
    }

    private void fail(MediaPlayer candidate, long token) {
        if (!current(candidate, token)) return;
        failed = true;
        // A failed closing clip still represents an ended session, so use its quiet final poster.
        if (phase == CompanionPhase.RESTING) restCompleted = true;
        releasePlayer(false);
        showPoster();
    }

    private void clearTimeout() {
        if (prepareTimeout != null) main.removeCallbacks(prepareTimeout);
        prepareTimeout = null;
    }

    private void releasePlayer(boolean rememberPosition) {
        if (player == null && renderer == null) return;
        ++generation;
        clearTimeout();
        MediaPlayer old = player;
        player = null;
        if (old != null && rememberPosition && prepared && phase != CompanionPhase.RESTING) {
            try { resumePosition = old.getCurrentPosition(); }
            catch (RuntimeException ignored) { resumePosition = 0; }
        }
        prepared = false;
        frameReceived = playbackStarted = false;
        if (old != null) {
            old.setOnPreparedListener(null);
            old.setOnInfoListener(null);
            old.setOnCompletionListener(null);
            old.setOnSeekCompleteListener(null);
            old.setOnErrorListener(null);
            try { old.release(); } catch (RuntimeException ignored) { }
        }
        if (renderer != null) {
            AlphaVideoRenderer previous = renderer; renderer = null;
            rendererClosing = true; closingRendererToken = rendererToken;
            previous.close();
        }
        showPoster();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        failed = false;
        observedTree = getViewTreeObserver();
        observedTree.addOnPreDrawListener(visibilityCheck);
        try {
            getContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver);
            observerRegistered = true;
        } catch (RuntimeException ignored) { }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(screenEvents, filter, Context.RECEIVER_NOT_EXPORTED);
        else getContext().registerReceiver(screenEvents, filter);
        receiverRegistered = true;
        synchronizePlayback();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        if (observedTree != null && observedTree.isAlive()) observedTree.removeOnPreDrawListener(visibilityCheck);
        observedTree = null;
        if (receiverRegistered) { getContext().unregisterReceiver(screenEvents); receiverRegistered = false; }
        if (observerRegistered) { getContext().getContentResolver().unregisterContentObserver(motionObserver); observerRegistered = false; }
        releasePlayer(true);
        ring.active(false, false);
        poster.animate().cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        synchronizePlayback();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        synchronizePlayback();
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture available, int width, int height) { synchronizePlayback(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture available, int width, int height) {
        if (renderer != null) renderer.resize(width, height);
    }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture destroyed) {
        releasePlayer(true);
        texture.setAlpha(1);
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture updated) { }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(dp(180), widthMeasureSpec), height = resolveSize(dp(192), heightMeasureSpec);
        labelHeight = Math.min(dp(28), height);
        indicatorHeight = Math.min(dp(10), Math.max(0, height - labelHeight));
        imageSize = Math.max(0, Math.min(width - getPaddingLeft() - getPaddingRight(),
            height - getPaddingTop() - getPaddingBottom() - labelHeight - indicatorHeight));
        picture.measure(MeasureSpec.makeMeasureSpec(imageSize, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(imageSize, MeasureSpec.EXACTLY));
        ring.measure(MeasureSpec.makeMeasureSpec(imageSize, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(indicatorHeight, MeasureSpec.EXACTLY));
        status.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(labelHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int pictureLeft = (getWidth() - imageSize) / 2;
        int pictureTop = Math.max(getPaddingTop(), (getHeight() - imageSize - labelHeight - indicatorHeight) / 2);
        picture.layout(pictureLeft, pictureTop, pictureLeft + imageSize, pictureTop + imageSize);
        ring.layout(pictureLeft, pictureTop + imageSize, pictureLeft + imageSize, pictureTop + imageSize + indicatorHeight);
        status.layout(0, pictureTop + imageSize + indicatorHeight, getWidth(), pictureTop + imageSize + indicatorHeight + labelHeight);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class PhaseRing extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private CompanionPhase phase = CompanionPhase.IDLE;
        private int color = 0xff779f4e;
        private boolean active, motion;
        private volatile float input, output;
        private volatile long inputAt, outputAt;

        PhaseRing(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        void state(String mode, CompanionPhase next) {
            if (phase != next) { input = output = 0; inputAt = outputAt = 0; }
            phase = next;
            color = next == CompanionPhase.ERROR ? 0xffb45e56
                : "work".equals(mode) ? 0xff6c81ae : "commute".equals(mode) ? 0xffb49738 : 0xff779f4e;
            invalidate();
        }

        void active(boolean next, boolean animate) {
            if (active == next && motion == animate) return;
            active = next; motion = animate; invalidate();
        }

        void inputLevel(float value) {
            input = CompanionVisualPolicy.level(value); inputAt = SystemClock.uptimeMillis();
            if (CompanionVisualPolicy.inputMeter(phase)) postInvalidate();
        }

        void outputLevel(float value) {
            output = CompanionVisualPolicy.level(value); outputAt = SystemClock.uptimeMillis();
            if (CompanionVisualPolicy.outputMeter(phase)) postInvalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            long now = SystemClock.uptimeMillis();
            boolean inputPhase = CompanionVisualPolicy.inputMeter(phase);
            boolean outputPhase = CompanionVisualPolicy.outputMeter(phase);
            long signalAt = inputPhase ? inputAt : outputAt;
            float level = inputPhase ? input : outputPhase ? output : 0;
            level *= Math.max(0, 1 - Math.max(0, now - signalAt - 160) / 320f);
            float size = getWidth();
            float cx = getWidth() / 2f, baseline = getHeight() / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            if (CompanionVisualPolicy.progressing(phase)) {
                float beat = active && motion ? now % 1800 / 1800f * 6.283185f : 0;
                for (int i = 0; i < 3; i++) {
                    paint.setAlpha(70 + (int) (55 * (.5f + .5f * (float) Math.sin(beat - i * .8f))));
                    canvas.drawCircle(cx + (i - 1) * size * .035f, baseline, Math.min(size * .008f, getHeight() * .2f), paint);
                }
            } else if (inputPhase || outputPhase) {
                paint.setAlpha(125 + (int) (level * 50));
                float gap = size * .026f, barWidth = size * .009f;
                for (int i = 0; i < 5; i++) {
                    float barHeight = Math.min(getHeight() * .8f,
                        size * (.008f + level * (.016f + (2 - Math.abs(2 - i)) * .008f)));
                    float x = cx + (i - 2) * gap;
                    canvas.drawRoundRect(x - barWidth / 2, baseline - barHeight / 2,
                        x + barWidth / 2, baseline + barHeight / 2, barWidth, barWidth, paint);
                }
            }
            paint.setAlpha(255);
            if (active && motion && (CompanionVisualPolicy.progressing(phase) || level > .001f))
                postInvalidateDelayed(CompanionVisualPolicy.progressing(phase) ? 80 : 50);
        }
    }
}
