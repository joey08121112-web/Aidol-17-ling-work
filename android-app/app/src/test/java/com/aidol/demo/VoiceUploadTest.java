package com.aidol.demo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okio.ByteString;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native audio can precede final captions; only stream.done completes a turn. */
public class VoiceUploadTest {
    @Test public void nativeAudioPlaysBeforeFinalTextAndKeepsResult() throws Exception {
        List<String> captions = new ArrayList<>();
        VoiceUpload upload = new VoiceUpload(text -> {}, captions::add);
        upload.enableReply(true); upload.useQwenRealtime(true);
        upload.onMessage(null, "{\"type\":\"ready\"}");
        upload.awaitReady();
        upload.onMessage(null, "{\"type\":\"reply.start\",\"text\":\"\",\"sample_rate\":24000}");
        assertTrue(upload.awaitReply());
        upload.onMessage(null, ByteString.of(new byte[]{1, 0, 2, 0}));
        byte[] first = new byte[4];
        assertEquals(4, upload.replyAudio.read(first));
        upload.onMessage(null, "{\"type\":\"reply.text\",\"text\":\"我在\"}");
        upload.onMessage(null, "{\"type\":\"reply.text\",\"text\":\"我在，接着说\"}");
        upload.onMessage(null, "{\"type\":\"reply.done\"}");
        assertEquals(-1, upload.replyAudio.read());
        upload.onMessage(null, "{\"type\":\"result\",\"result\":{\"response\":\"我在，接着说\"}}");
        upload.onMessage(null, "{\"type\":\"stream.done\"}");
        assertEquals("我在，接着说", upload.awaitResult().getString("response"));
        assertEquals("我在，接着说", captions.get(captions.size() - 1));
    }

    @Test public void unexpectedCaptionsFailLegacyStream() {
        VoiceUpload upload = new VoiceUpload(text -> {});
        upload.enableReply(true);
        upload.onMessage(null, "{\"type\":\"reply.text\",\"text\":\"invalid\"}");
        assertThrows(IOException.class, upload::awaitResult);
    }

    @Test public void interruptionUnblocksPlaybackAndPreservesReceipt() throws Exception {
        VoiceUpload upload = new VoiceUpload(text -> {});
        upload.enableReply(true); upload.useQwenRealtime(true);
        upload.onMessage(null, "{\"type\":\"reply.start\",\"text\":\"\",\"sample_rate\":24000}");
        upload.interruptReply();
        assertEquals(-1, upload.replyAudio.read());
        upload.onMessage(null, "{\"type\":\"reply.interrupted\"}");
        upload.onMessage(null, "{\"type\":\"result\",\"result\":{\"voice_interrupted\":true}}");
        upload.onMessage(null, "{\"type\":\"stream.done\"}");
        assertTrue(upload.awaitResult().getBoolean("voice_interrupted"));
    }
    @Test public void cloudEndpointRequestsSdkDrainOnceWithoutEndingTheCall() throws Exception {
        java.util.concurrent.atomic.AtomicInteger stops = new java.util.concurrent.atomic.AtomicInteger();
        VoiceUpload upload = new VoiceUpload(text -> {});
        upload.useQwenRealtime(true); upload.onSpeechEnd(stops::incrementAndGet);
        upload.onMessage(null, "{\"type\":\"ready\"}"); upload.awaitReady();
        upload.onMessage(null, "{\"type\":\"input.speech_end\"}");
        upload.onMessage(null, "{\"type\":\"input.speech_end\"}");
        assertEquals(1, stops.get());
        upload.onMessage(null, "{\"type\":\"result\",\"result\":{\"speak\":true}}");
        assertTrue(upload.awaitResult().getBoolean("speak"));
    }
}
