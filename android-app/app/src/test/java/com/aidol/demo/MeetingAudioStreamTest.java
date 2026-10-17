package com.aidol.demo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeetingAudioStreamTest {
    private static final long B = MeetingAudioStream.BYTES_PER_MS;

    /** One fake recording of lengthMs; byte i of the timeline is (i % 251). */
    private static final class Server implements MeetingAudioStream.Windows {
        final long lengthMs; final long missingFromMs; final int rate;
        final List<String> requests = new ArrayList<>();
        Server(long lengthMs) { this(lengthMs, Long.MAX_VALUE, 16000); }
        Server(long lengthMs, long missingFromMs, int rate) { this.lengthMs = lengthMs; this.missingFromMs = missingFromMs; this.rate = rate; }
        @Override public InputStream open(long startMs, long durationMs) {
            requests.add(startMs + "+" + durationMs);
            if (startMs >= missingFromMs) return null;
            long end = Math.min(startMs + durationMs, lengthMs);
            return new ByteArrayInputStream(wav(timeline(startMs * B, end * B), rate));
        }
    }
    static byte[] timeline(long from, long to) {
        byte[] pcm = new byte[(int) Math.max(0, to - from)];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) ((from + i) % 251);
        return pcm;
    }
    static byte[] wav(byte[] pcm, int rate) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(b.capacity() - 8).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1);
        b.putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[777];
        for (int n; (n = in.read(buffer)) >= 0;) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    @Test public void windowMathCapsAtTenMinutesAndTheKnownEnd() {
        assertEquals(600_000, MeetingAudioStream.windowDuration(0, 0, MeetingAudioStream.WINDOW_MS));
        assertEquals(600_000, MeetingAudioStream.windowDuration(60_000, 3_600_000, MeetingAudioStream.WINDOW_MS));
        assertEquals(45_000, MeetingAudioStream.windowDuration(1_200_000, 1_245_000, MeetingAudioStream.WINDOW_MS));
        assertEquals(100, MeetingAudioStream.windowDuration(1_244_990, 1_245_000, MeetingAudioStream.WINDOW_MS)); // Server minimum.
        assertEquals(600_000, MeetingAudioStream.nextWindowStart(0, 600_000, 600_000 * B, 0));
        assertEquals(-1, MeetingAudioStream.nextWindowStart(0, 600_000, 599_999 * B, 0)); // Trimmed: last window.
        assertEquals(-1, MeetingAudioStream.nextWindowStart(0, 600_000, 600_000 * B, 600_000));
    }

    @Test public void streamsConsecutiveWindowsFromAnyStartUntilTheTrimmedTail() throws Exception {
        Server server = new Server(330);
        MeetingAudioStream stream = new MeetingAudioStream(server, 50, 0, 100, Runnable::run);
        assertArrayEquals(timeline(50 * B, 330 * B), readAll(stream));
        // The short third window ends playback without asking past the recording.
        assertEquals(Arrays.asList("50+100", "150+100", "250+100"), server.requests);
        assertEquals(280 * B, stream.delivered());
    }

    @Test public void aKnownEndCutsTheLastRequestAndStopsThere() throws Exception {
        Server server = new Server(330);
        assertArrayEquals(timeline(0, 330 * B), readAll(new MeetingAudioStream(server, 0, 330, 100, Runnable::run)));
        assertEquals(Arrays.asList("0+100", "100+100", "200+100", "300+100"), server.requests);
        assertEquals(-1, new MeetingAudioStream(server, 330, 330, 100, Runnable::run).read(new byte[4], 0, 4));
    }

    @Test public void prefetchesTheNextWindowBeforeTheCurrentOneRunsOut() throws Exception {
        Server server = new Server(1000);
        MeetingAudioStream stream = new MeetingAudioStream(server, 0, 0, 100, Runnable::run);
        assertEquals(10, stream.read(new byte[10], 0, 10));
        // Each 100 ms test window is under the 10 s prefetch margin, so its successor is requested now.
        assertEquals(Arrays.asList("0+100", "100+100"), server.requests);
        stream.close();
    }

    @Test public void missingAudioFailsAtTheStartAndEndsCleanlyLater() throws Exception {
        assertThrows(IOException.class, () -> readAll(new MeetingAudioStream(new Server(1000, 0, 16000), 0, 0, 100, Runnable::run)));
        // Retention can delete the recording mid-playback: stop at what was already heard.
        Server server = new Server(1000, 200, 16000);
        assertArrayEquals(timeline(0, 200 * B), readAll(new MeetingAudioStream(server, 0, 0, 100, Runnable::run)));
    }

    @Test public void rejectsAWindowInAnotherSampleRate() {
        Server server = new Server(1000, Long.MAX_VALUE, 24000);
        assertThrows(IOException.class, () -> readAll(new MeetingAudioStream(server, 0, 0, 100, Runnable::run)));
    }

    @Test public void positionFollowsPlayedFramesButNeverPassesDeliveredAudio() {
        assertEquals(61_000, MeetingAudioStream.positionMs(60_000, 16_000, 10 * 32_000));
        assertEquals(60_500, MeetingAudioStream.positionMs(60_000, 16_000, 500 * B)); // Tail silence is not audio.
        assertEquals(60_000, MeetingAudioStream.positionMs(60_000, -5, 0));
    }
}
