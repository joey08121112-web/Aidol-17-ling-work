package com.aidol.demo;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import static org.junit.Assert.*;

public class SpeechProtocolTest {
    private byte[] frame(int kindFlags, int encoding, Integer prefix, byte[] data) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.write(new byte[]{0x11, (byte) kindFlags, (byte) encoding, 0});
        if (prefix != null) out.writeInt(prefix);
        out.writeInt(data.length); out.write(data); return buffer.toByteArray();
    }
    @Test public void finalPcmUsesGzipAndNegativeFlag() throws Exception {
        byte[] packet = SpeechProtocol.asr(new byte[]{1, 0, -1, 127}, false, true);
        assertEquals(0x22, packet[1]);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(packet, 8, packet.length - 8))) {
            assertArrayEquals(new byte[]{1, 0, -1, 127}, gzip.readAllBytes());
        }
    }
    @Test public void parsesBothAsrSequenceVariants() throws Exception {
        byte[] json = "{\"result\":{\"text\":\"你好\"}}".getBytes(StandardCharsets.UTF_8);
        SpeechProtocol.Packet last = SpeechProtocol.parse(frame(0x93, 0x10, -1, json), false);
        assertTrue(last.last()); assertArrayEquals(json, last.payload());
        SpeechProtocol.Packet variant = SpeechProtocol.parse(frame(0x90, 0x10, -1, json), false);
        assertTrue(variant.last());
        assertFalse(SpeechProtocol.parse(frame(0x90, 0x10, null, json), false).last());
    }
    @Test public void parsesTtsConnectionWithoutIdAndSessionAudio() throws Exception {
        assertEquals(50, SpeechProtocol.parse(frame(0x94, 0x10, 50, "{}".getBytes()), true).event());
        byte[] audio = {1, 2, 3, 4};
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(buffer);
        out.write(new byte[]{0x11, (byte) 0xb4, 0, 0}); out.writeInt(352);
        out.writeInt(3); out.writeBytes("abc"); out.writeInt(audio.length); out.write(audio);
        SpeechProtocol.Packet packet = SpeechProtocol.parse(buffer.toByteArray(), true);
        assertEquals("abc", packet.session()); assertEquals(11, packet.kind()); assertArrayEquals(audio, packet.payload());
    }
    @Test public void rejectsTruncationAndTrailingBytes() throws Exception {
        byte[] good = frame(0x92, 0x10, null, "{}".getBytes());
        for (byte[] bad : new byte[][]{Arrays.copyOf(good, good.length - 1), Arrays.copyOf(good, good.length + 1)}) {
            assertThrows(java.io.IOException.class, () -> SpeechProtocol.parse(bad, false));
        }
    }
    @Test public void chunksPreserveSignedSamplesAndOrder() {
        short[] values = {-32768, -1, 0, 1, 32767};
        assertArrayEquals(new byte[]{0, -128, -1, -1, 0, 0, 1, 0, -1, 127}, AudioFrames.encode(values, values.length));
        byte[] pcm = new byte[6500]; for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        ArrayList<byte[]> parts = new ArrayList<>(); AudioFrames.split(pcm, parts::add);
        assertEquals(3, parts.size());
        ByteArrayOutputStream joined = new ByteArrayOutputStream(); parts.forEach(part -> joined.write(part, 0, part.length));
        assertArrayEquals(pcm, joined.toByteArray());
        assertThrows(IllegalArgumentException.class, () -> AudioFrames.split(new byte[3], parts::add));
    }
}
