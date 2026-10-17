package com.aidol.demo;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.IOException;
import static org.junit.Assert.*;

public class WavAudioTest {
    private byte[] wav(int rate, int channels, boolean metadata) {
        byte[] pcm = {1,0,2,0,3,0,4,0};
        int extra = metadata ? 12 : 0;
        ByteBuffer b = ByteBuffer.allocate(44 + extra + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(b.capacity()-8).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)channels);
        b.putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16);
        if(metadata) b.put("JUNK".getBytes()).putInt(3).put(new byte[]{4,5,6,0});
        b.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }
    @Test public void acceptsProviderPcmAndSkipsPaddedMetadata() throws Exception {
        assertArrayEquals(new byte[]{1,0,2,0,3,0,4,0},WavAudio.pcm(wav(24000,1,false)));
        assertArrayEquals(new byte[]{1,0,2,0,3,0,4,0},WavAudio.pcm(wav(24000,1,true)));
    }
    @Test public void rejectsWrongRateChannelsAndTruncation() {
        assertThrows(IOException.class,()->WavAudio.pcm(wav(16000,1,false)));
        assertThrows(IOException.class,()->WavAudio.pcm(wav(24000,2,false)));
        byte[] truncated=java.util.Arrays.copyOf(wav(24000,1,false),48);
        assertThrows(IOException.class,()->WavAudio.pcm(truncated));
    }
    @Test public void rejectsOverflowChunkLengthsAndInvalidHeader() {
        byte[] bad=wav(24000,1,false); bad[40]=(byte)0xff;bad[41]=(byte)0xff;bad[42]=(byte)0xff;bad[43]=(byte)0xff;
        assertThrows(IOException.class,()->WavAudio.pcm(bad));
        assertThrows(IOException.class,()->WavAudio.pcm(new byte[64]));
    }
    @Test public void streamingHeaderStopsAtTheFirstPcmByte() throws Exception {
        java.io.InputStream in=new java.io.ByteArrayInputStream(wav(16000,1,true));
        assertEquals(8,WavAudio.openPcm(in,16000));
        assertEquals(1,in.read()); // Padded metadata was skipped; the reader continues with PCM.
        assertThrows(IOException.class,()->WavAudio.openPcm(new java.io.ByteArrayInputStream(wav(24000,1,false)),16000));
        assertThrows(IOException.class,()->WavAudio.openPcm(new java.io.ByteArrayInputStream(wav(16000,2,false)),16000));
        assertThrows(IOException.class,()->WavAudio.openPcm(new java.io.ByteArrayInputStream(java.util.Arrays.copyOf(wav(16000,1,false),30)),16000));
    }
}
