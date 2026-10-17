package com.aidol.demo;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Decode bounded PCM frames; only the explicit success trailer means EOF. */
final class PcmFrameInputStream extends InputStream {
    private final DataInputStream source;
    private int remaining, total;
    private boolean ended;
    PcmFrameInputStream(InputStream source) { this.source = new DataInputStream(source); }
    public int read() throws IOException {
        byte[] value = new byte[1];
        return read(value, 0, 1) < 0 ? -1 : value[0] & 255;
    }
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (ended) return -1;
        if (remaining == 0) {
            remaining = source.readInt(); // throws on truncated stream
            if (remaining == 0) {
                if (total == 0) throw new IOException("语音为空");
                ended = true; return -1;
            }
            if (remaining < 0 || remaining > 24000 || (remaining & 1) != 0 || total + remaining > 6 * 1024 * 1024)
                throw new IOException("语音帧无效或超过上限");
            total += remaining;
        }
        int count = Math.min(length, remaining);
        source.readFully(bytes, offset, count);
        remaining -= count;
        return count;
    }
    public void close() throws IOException { source.close(); }
}
