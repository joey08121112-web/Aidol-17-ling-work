package com.aidol.demo;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;

public class PcmFrameInputStreamTest {
    @Test public void requiresTrailerAndRejectsTruncation() throws Exception {
        byte[] valid = {0,0,0,4,1,0,2,0,0,0,0,0};
        PcmFrameInputStream stream = new PcmFrameInputStream(new ByteArrayInputStream(valid));
        byte[] data = new byte[4096];
        assertEquals(4, stream.read(data)); assertEquals(1, data[0]); assertEquals(-1, stream.read(data));
        PcmFrameInputStream truncated = new PcmFrameInputStream(new ByteArrayInputStream(new byte[]{0,0,0,2,1,0}));
        assertEquals(2, truncated.read(data));
        assertThrows(EOFException.class, () -> truncated.read(data));
    }
    @Test public void rejectsOddOversizedAndEmptyAudio() {
        for (byte[] frame : new byte[][]{{0,0,0,1}, {0,1,0,0}, {0,0,0,0}, {-1,-1,-1,-1}}) {
            PcmFrameInputStream stream = new PcmFrameInputStream(new ByteArrayInputStream(frame));
            assertThrows(IOException.class, () -> stream.read(new byte[4096]));
        }
    }
}
