package com.aidol.demo;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Keeps prompts outside capture, including cancellation while a prompt is playing. */
final class CapturePromptSequence {
    interface Action { void run() throws Exception; }
    interface Snapshot { byte[] finish() throws Exception; }
    private final BooleanSupplier cancelled;

    CapturePromptSequence(BooleanSupplier cancelled) { this.cancelled = cancelled; }

    void start(Action startPrompt, Action startCapture) throws Exception {
        check(); startPrompt.run(); check(); startCapture.run();
    }

    byte[] finish(Snapshot drainCapture, Action endPrompt, Consumer<Exception> warning) throws Exception {
        check(); byte[] pcm = drainCapture.finish(); check();
        try { endPrompt.run(); }
        catch (InterruptedException interrupted) { throw interrupted; }
        catch (Exception failedPrompt) { check(); warning.accept(failedPrompt); }
        check(); return pcm;
    }

    private void check() throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException("会话已取消");
    }
}
