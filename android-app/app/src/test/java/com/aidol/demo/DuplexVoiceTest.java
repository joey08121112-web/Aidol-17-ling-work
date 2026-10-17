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
        int speech, results;
        final List<LiveReplyAudio> audio = new ArrayList<>();
        public void speechStart() { speech++; }
        public void thinking() {}
        public void transcript(String text) {}
        public void reply(String text) {}
        public void playback(LiveReplyAudio value) { audio.add(value); }
        public void result(JSONObject result) { results++; }
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
