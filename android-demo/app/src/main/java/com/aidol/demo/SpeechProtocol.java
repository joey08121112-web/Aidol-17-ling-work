package com.aidol.demo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Same v3 wire protocol as app/providers/volc_speech.py; contains no credentials. */
public final class SpeechProtocol {
    public record Packet(int kind, int event, String session, byte[] payload, boolean last) {}
    private SpeechProtocol() {}
    public static byte[] asr(byte[] payload, boolean start, boolean last) throws IOException {
        if (!start && (payload.length & 1) != 0) throw new IOException("PCM16 长度不完整");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(payload); }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.write(new byte[]{0x11, (byte) (start ? 0x10 : last ? 0x22 : 0x20), (byte) (start ? 0x11 : 0x01), 0});
        out.writeInt(compressed.size()); out.write(compressed.toByteArray());
        return buffer.toByteArray();
    }
    public static byte[] tts(int event, String session, String json) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.write(new byte[]{0x11, 0x14, 0x10, 0}); out.writeInt(event);
        if (session != null) { byte[] id = session.getBytes(StandardCharsets.UTF_8); out.writeInt(id.length); out.write(id); }
        byte[] data = json.getBytes(StandardCharsets.UTF_8); out.writeInt(data.length); out.write(data);
        return buffer.toByteArray();
    }
    private static byte[] blob(ByteBuffer data) throws IOException {
        if (data.remaining() < 4) throw new IOException("语音帧缺少长度");
        int size = data.getInt();
        if (size < 0 || size > data.remaining()) throw new IOException("语音帧长度不正确");
        byte[] result = new byte[size]; data.get(result); return result;
    }
    public static Packet parse(byte[] bytes, boolean tts) throws IOException {
        try {
            if (bytes.length < 8 || ((bytes[0] & 255) >> 4) != 1) throw new IOException("语音帧版本不正确");
            int header = (bytes[0] & 15) * 4;
            if (header < 4 || header > bytes.length) throw new IOException("语音帧头长度不正确");
            int kind = (bytes[1] & 255) >> 4, flags = bytes[1] & 15, compression = bytes[2] & 15;
            ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN); data.position(header);
            if (kind == 15) throw new IOException((tts ? "TTS" : "ASR") + " 服务错误码 " + data.getInt());
            boolean last = false;
            int event = 0;
            String session = "";
            if (tts) {
                if ((kind != 9 && kind != 11) || (flags & 4) == 0) throw new IOException("TTS 事件头不正确");
                event = data.getInt();
                int firstSize = data.getInt(data.position());
                if (event >= 100 || firstSize != data.remaining() - 4) session = new String(blob(data), StandardCharsets.UTF_8);
            } else {
                if (kind != 9 || ((bytes[2] & 255) >> 4) != 1) throw new IOException("ASR 响应类型不正确");
                int sequence = 0;
                if ((flags & 1) != 0) sequence = data.getInt();
                else if (data.remaining() >= 8 && data.getInt(data.position() + 4) == data.remaining() - 8) sequence = data.getInt();
                last = (flags & 2) != 0 || sequence < 0;
            }
            byte[] payload = blob(data);
            if (data.hasRemaining()) throw new IOException("语音帧存在多余数据");
            if (compression == 1) {
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                    byte[] chunk = new byte[8192]; int count;
                    while ((count = gzip.read(chunk)) != -1) {
                        if (result.size() + count > 4 * 1024 * 1024) throw new IOException("语音帧解压后过大");
                        result.write(chunk, 0, count);
                    }
                }
                payload = result.toByteArray();
            } else if (compression != 0) throw new IOException("语音压缩格式不支持");
            if (tts && (event == 51 || event == 153)) throw new IOException("TTS 拒绝请求，事件 " + event);
            return new Packet(kind, event, session, payload, last);
        } catch (java.nio.BufferUnderflowException | IndexOutOfBoundsException e) { throw new IOException("语音帧被截断"); }
    }
}
