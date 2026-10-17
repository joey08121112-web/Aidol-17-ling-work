package com.aidol.demo;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Fixed public product prompts; no text, account data or provider request is accepted. */
enum LocalCue {
    LIFE("生活模式", R.raw.cue_life),
    WORK("工作模式", R.raw.cue_work),
    COMMUTE("通勤模式", R.raw.cue_commute),
    START("开始录音", R.raw.cue_start),
    END("录音结束", R.raw.cue_end);

    final String label;
    private final int resource;
    LocalCue(String label, int resource) { this.label = label; this.resource = resource; }

    static LocalCue forMode(String mode) {
        if ("life".equals(mode)) return LIFE;
        if ("work".equals(mode)) return WORK;
        if ("commute".equals(mode)) return COMMUTE;
        throw new IllegalArgumentException("未知业务模式，不能播放操作提示");
    }

    byte[] pcm(Context context) throws IOException {
        try (InputStream source = context.getResources().openRawResource(resource);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int count; (count = source.read(buffer)) != -1;) {
                if (output.size() + count > 384_000) throw new IOException("操作提示音过长");
                output.write(buffer, 0, count);
            }
            return WavAudio.pcm(output.toByteArray());
        }
    }
}
