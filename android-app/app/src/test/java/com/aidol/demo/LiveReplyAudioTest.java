package com.aidol.demo;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveReplyAudioTest {
    @Test public void framesCanPlayBeforeSuccessfulTrailer() throws Exception {
        LiveReplyAudio stream = new LiveReplyAudio();
        stream.offer(new byte[]{1, 2, 3, 4});
        byte[] out = new byte[2];
        assertEquals(2, stream.read(out)); assertArrayEquals(new byte[]{1, 2}, out);
        assertEquals(2, stream.read(out)); assertArrayEquals(new byte[]{3, 4}, out);
        stream.finish(); assertEquals(-1, stream.read(out));
    }
    @Test public void failedTrailerCannotLookLikeNormalEof() throws Exception {
        LiveReplyAudio stream = new LiveReplyAudio();
        stream.offer(new byte[]{1, 2}); stream.fail(new IOException("truncated"));
        assertThrows(IOException.class, () -> stream.read(new byte[2]));
    }
    @Test public void interruptUnblocksBackpressureAndDiscardsLateFrames() throws Exception {
        LiveReplyAudio stream = new LiveReplyAudio();
        for (int i=0; i<16; i++) stream.offer(new byte[24000]);
        CountDownLatch entered = new CountDownLatch(1), done = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try { entered.countDown(); stream.offer(new byte[24000]); }
            catch (Exception e) { throw new AssertionError(e); }
            finally { done.countDown(); }
        });
        producer.start(); assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertFalse(done.await(50, TimeUnit.MILLISECONDS));
        stream.discard(); assertTrue(done.await(1, TimeUnit.SECONDS));
        stream.offer(new byte[24000]); assertEquals(-1, stream.read());
    }
    @Test public void rejectsOddOversizedAndPostTrailerAudio() throws Exception {
        LiveReplyAudio stream = new LiveReplyAudio();
        assertThrows(IOException.class, () -> stream.offer(new byte[3]));
        assertThrows(IOException.class, () -> stream.offer(new byte[24002]));
        stream.finish(); assertThrows(IOException.class, () -> stream.offer(new byte[2]));
    }
}
