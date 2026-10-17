package com.aidol.demo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class CapturePromptSequenceTest {
    @Test public void microphoneWaitsUntilTheEntireStartPromptCompletes() throws Exception {
        CapturePromptSequence sequence = new CapturePromptSequence(() -> false);
        CountDownLatch playing = new CountDownLatch(1), completed = new CountDownLatch(1);
        AtomicBoolean microphone = new AtomicBoolean();
        FutureTask<Void> task = new FutureTask<>(() -> {
            sequence.start(() -> { playing.countDown(); assertTrue(completed.await(2, TimeUnit.SECONDS)); },
                () -> microphone.set(true));
            return null;
        });
        new Thread(task, "start-prompt-order-test").start();
        assertTrue(playing.await(2, TimeUnit.SECONDS)); assertFalse(microphone.get());
        completed.countDown(); task.get(2, TimeUnit.SECONDS); assertTrue(microphone.get());
    }

    @Test public void cancellationDuringStartPromptNeverOpensTheMicrophone() {
        AtomicBoolean cancelled = new AtomicBoolean(), microphone = new AtomicBoolean();
        CapturePromptSequence sequence = new CapturePromptSequence(cancelled::get);
        assertThrows(InterruptedException.class,
            () -> sequence.start(() -> cancelled.set(true), () -> microphone.set(true)));
        assertFalse(microphone.get());
    }

    @Test public void failedStartPromptNeverOpensTheMicrophone() {
        CapturePromptSequence sequence = new CapturePromptSequence(() -> false);
        AtomicBoolean microphone = new AtomicBoolean();
        assertThrows(IOException.class,
            () -> sequence.start(() -> { throw new IOException("目标耳机不可用"); }, () -> microphone.set(true)));
        assertFalse(microphone.get());
    }

    @Test public void endPromptCannotRunBeforeTheCaptureAcknowledgesDrain() throws Exception {
        CaptureTurn turn = new CaptureTurn(60);
        turn.recording(0); turn.append(new byte[] {1, 0}, 1); turn.requestStop(2);
        AtomicBoolean endPrompt = new AtomicBoolean();
        CapturePromptSequence sequence = new CapturePromptSequence(() -> false);
        assertThrows(IOException.class,
            () -> sequence.finish(turn::finish, () -> endPrompt.set(true), ignored -> fail("not a prompt failure")));
        assertFalse(endPrompt.get());
    }

    @Test public void endPromptFollowsTheFinalTailSnapshotAndNeverEntersCapturedAudio() throws Exception {
        CaptureTurn turn = new CaptureTurn(60);
        turn.recording(0); turn.append(new byte[] {1, 0}, 1); turn.requestStop(2);
        turn.append(new byte[] {2, 0}, 3); turn.drained(4);
        List<String> events = new ArrayList<>();
        CapturePromptSequence sequence = new CapturePromptSequence(() -> false);
        byte[] pcm = sequence.finish(() -> { byte[] audio = turn.finish(); events.add("drained"); return audio; }, () -> {
            assertEquals(CaptureTurn.Phase.ENDED, turn.phase());
            assertFalse(turn.append(new byte[] {9, 0}, 5));
            events.add("end prompt");
        }, ignored -> fail("end prompt should succeed"));
        assertEquals(Arrays.asList("drained", "end prompt"), events);
        assertArrayEquals(new byte[] {1, 0, 2, 0}, pcm);
    }

    @Test public void failedEndPromptRetainsFinishedAudioForTheBusinessRequest() throws Exception {
        CapturePromptSequence sequence = new CapturePromptSequence(() -> false);
        byte[] complete = {1, 0, 2, 0};
        AtomicInteger warnings = new AtomicInteger();
        byte[] returned = sequence.finish(() -> complete, () -> { throw new IOException("输出断开"); },
            error -> { assertEquals("输出断开", error.getMessage()); warnings.incrementAndGet(); });
        assertSame(complete, returned); assertEquals(1, warnings.get());
    }

    @Test public void cancellationDuringEndPromptDoesNotReturnAudioForUpload() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger uploads = new AtomicInteger(), warnings = new AtomicInteger();
        CapturePromptSequence sequence = new CapturePromptSequence(cancelled::get);
        assertThrows(InterruptedException.class, () -> {
            sequence.finish(() -> new byte[] {1, 0}, () -> { cancelled.set(true); throw new IOException("已断连"); },
                error -> warnings.incrementAndGet());
            uploads.incrementAndGet();
        });
        assertEquals(0, uploads.get()); assertEquals(0, warnings.get());
    }

    @Test public void interruptedEndPromptNeverContinuesTheBusinessRequest() {
        CapturePromptSequence sequence = new CapturePromptSequence(() -> false);
        assertThrows(InterruptedException.class, () -> sequence.finish(() -> new byte[] {1, 0},
            () -> { throw new InterruptedException(); }, error -> fail("interruption must propagate")));
    }
}
