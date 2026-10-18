package com.aidol.demo;

import java.util.ArrayList;
import java.util.List;
import okio.ByteString;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DuplexVoiceTest {
    @Test public void preparationCannotSignalListeningBeforeUserActivation() throws Exception {
        Listener listener = new Listener(); DuplexVoice call = new DuplexVoice(listener);
        call.onMessage(null, "{\"type\":\"prepared\",\"expires_in\":40}");
        assertEquals(0, listener.speech);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> ready = executor.submit(() -> {
                try { call.awaitReady(); } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertThrows(java.util.concurrent.TimeoutException.class, () -> ready.get(30, java.util.concurrent.TimeUnit.MILLISECONDS));
            call.cancel();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> ready.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(call.available());
        } finally { executor.shutdownNow(); }
    }
    static final class Listener implements DuplexVoice.Listener {
        int speech, listening, stopped, thinking, results;
        final List<LiveReplyAudio> audio = new ArrayList<>();
        public void speechStart() { speech++; }
        public void thinking() { thinking++; }
        public void listening() { listening++; }
        public void playbackStopped() { stopped++; }
        public void transcript(String text) {}
        public void reply(String text) {}
        public void playback(LiveReplyAudio value) { audio.add(value); }
        public void result(JSONObject result) { results++; }
    }
    @Test public void bufferingAndTextNeverPretendTheUserStartedSpeaking() {
        Listener listener = new Listener(); DuplexVoice call = new DuplexVoice(listener);
        call.onMessage(null, "{\"type\":\"thinking\",\"turn\":1}");
        call.onMessage(null, "{\"type\":\"reply.start\",\"sample_rate\":24000,\"turn\":1}");
        call.onMessage(null, "{\"type\":\"reply.text\",\"text\":\"answer\"}");
        assertEquals(1, listener.thinking);
        assertEquals(0, listener.speech);
        assertEquals(0, listener.listening);
        assertEquals(1, listener.audio.size());
    }
    @Test public void keywordOnlyInterruptionDoesNotGetStuckThinkingAtVadEnd() {
        Listener listener = new Listener(); DuplexVoice call = new DuplexVoice(listener);
        call.onMessage(null, "{\"type\":\"reply.interrupted\"}");
        call.onMessage(null, "{\"type\":\"input.speech_end\"}");
        assertEquals(1, listener.listening);
        assertEquals(0, listener.thinking);
    }
    @Test public void onlyCurrentDrainedAudioReturnsToListeningAndOnlyOnce() {
        Listener listener = new Listener(); DuplexVoice call = new DuplexVoice(listener);
        call.onMessage(null, "{\"type\":\"reply.start\",\"sample_rate\":24000,\"turn\":1}");
        LiveReplyAudio old = listener.audio.get(0);
        call.onMessage(null, "{\"type\":\"reply.start\",\"sample_rate\":24000,\"turn\":2}");
        LiveReplyAudio current = listener.audio.get(1);
        call.playbackDone(old);
        assertEquals(0, listener.listening);
        assertTrue(call.current(current));
        call.playbackDone(current);
        call.playbackDone(current);
        assertEquals(1, listener.listening);
        assertFalse(call.current(current));
    }
    @Test public void cancellationAndFailureDoNotEmitListeningOrReviveOutput() {
        Listener cancelled = new Listener(); DuplexVoice call = new DuplexVoice(cancelled);
        call.onMessage(null, "{\"type\":\"reply.start\",\"sample_rate\":24000}");
        LiveReplyAudio old = cancelled.audio.get(0);
        call.cancel(); call.playbackDone(old);
        call.onMessage(null, "{\"type\":\"input.speech_start\"}");
        assertEquals(0, cancelled.speech + cancelled.listening);
        Listener failed = new Listener(); DuplexVoice broken = new DuplexVoice(failed);
        broken.abort("lost");
        broken.onMessage(null, "{\"type\":\"reply.start\",\"sample_rate\":24000}");
        assertEquals(0, failed.speech + failed.listening + failed.audio.size());
    }
    @Test public void silentResultReturnsToListeningButAnOldResultDoesNotFinishANewerTurn() {
        Listener listener = new Listener(); DuplexVoice call = new DuplexVoice(listener);
        call.onMessage(null, "{\"type\":\"thinking\",\"turn\":1}");
        call.onMessage(null, "{\"type\":\"result\",\"turn\":1,\"result\":{\"speak\":false}}");
        assertEquals(1, listener.listening);
        call.onMessage(null, "{\"type\":\"thinking\",\"turn\":2}");
        call.onMessage(null, "{\"type\":\"result\",\"turn\":1,\"result\":{}}");
        assertEquals(1, listener.listening);
        call.onMessage(null, "{\"type\":\"result\",\"turn\":2,\"result\":{\"speak\":false}}");
        assertEquals(2, listener.listening);
        assertEquals(3, listener.results); // All saved receipts still reach the history.
    }
    @Test public void anOldTrailerCannotEndTheNewAudioStream() throws Exception {
        Listener listener = new Listener(); DuplexVoice call = new DuplexVoice(listener);
        call.onMessage(null, "{\"type\":\"reply.start\",\"sample_rate\":24000,\"turn\":2}");
        call.onMessage(null, "{\"type\":\"reply.done\",\"turn\":1}");
        call.onMessage(null, ByteString.of(new byte[]{4, 0}));
        assertTrue(call.current(listener.audio.get(0)));
        byte[] frame = new byte[2];
        assertEquals(2, listener.audio.get(0).read(frame));
        call.onMessage(null, "{\"type\":\"reply.done\",\"turn\":2}");
        assertEquals(-1, listener.audio.get(0).read());
    }
    @Test public void aNewThinkingTurnSurvivesThePreviousAudioDraining() {
        Listener listener = new Listener(); DuplexVoice call = new DuplexVoice(listener);
        call.onMessage(null, "{\"type\":\"reply.start\",\"sample_rate\":24000,\"turn\":1}");
        call.onMessage(null, "{\"type\":\"thinking\",\"turn\":2}");
        assertTrue(call.current(listener.audio.get(0))); // Playback may still be draining.
        assertFalse(call.currentPresentation(listener.audio.get(0))); // Its late UI events are stale.
        call.playbackDone(listener.audio.get(0));
        assertEquals(0, listener.listening);
        call.onMessage(null, "{\"type\":\"result\",\"turn\":2,\"result\":{\"speak\":false}}");
        assertEquals(1, listener.listening);
    }
    @Test public void bargeInDiscardsOldAudioAndAcceptsNewReplyWithinOneCall() throws Exception {
        Listener listener = new Listener(); DuplexVoice call = new DuplexVoice(listener);
        call.onMessage(null, "{\"type\":\"ready\",\"duplex\":true}"); call.awaitReady();
        call.onMessage(null, "{\"type\":\"reply.start\",\"sample_rate\":24000}");
        call.onMessage(null, ByteString.of(new byte[]{1,0,2,0}));
        call.onMessage(null, "{\"type\":\"input.speech_start\"}");
        assertEquals(-1, listener.audio.get(0).read());
        call.onMessage(null, ByteString.of(new byte[]{3,0})); // late old audio cannot revive playback
        call.onMessage(null, "{\"type\":\"reply.start\",\"sample_rate\":24000}");
        call.onMessage(null, ByteString.of(new byte[]{4,0}));
        byte[] frame = new byte[2]; assertEquals(2, listener.audio.get(1).read(frame)); assertEquals(4,frame[0]);
        call.onMessage(null, "{\"type\":\"reply.done\"}");
        assertEquals(-1, listener.audio.get(1).read());
        call.onMessage(null, "{\"type\":\"result\",\"result\":{}}");
        assertEquals(1, listener.results);
        call.onMessage(null, "{\"type\":\"call.done\"}"); call.awaitEnd();
    }
    @Test public void finishingAReplyDoesNotEndThePersistentCall() throws Exception {
        Listener listener = new Listener(); DuplexVoice call = new DuplexVoice(listener);
        call.onMessage(null, "{\"type\":\"ready\",\"duplex\":true}"); call.awaitReady();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> done = executor.submit(() -> { try { call.awaitEnd(); } catch(Exception e) { throw new RuntimeException(e); } });
            call.onMessage(null, "{\"type\":\"reply.start\",\"sample_rate\":24000}");
            call.onMessage(null, "{\"type\":\"reply.done\"}");
            assertThrows(java.util.concurrent.TimeoutException.class, () -> done.get(20, java.util.concurrent.TimeUnit.MILLISECONDS));
            call.onMessage(null, "{\"type\":\"call.done\"}"); done.get(1, java.util.concurrent.TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }
}
