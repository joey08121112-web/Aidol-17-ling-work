package com.aidol.demo;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Fixed nonverbal product earcons; labels are display-only. No speech or provider request. */
enum LocalCue {
    LIFE(ModeCopy.LIFE, R.raw.cue_life),
    WORK(ModeCopy.WORK, R.raw.cue_work),
    COMMUTE(ModeCopy.COMMUTE, R.raw.cue_commute),
    START("开始录音", R.raw.cue_start),
    END("录音结束", R.raw.cue_end),
    BUSY("请稍等", R.raw.cue_busy),
    DESTINATION("请先选目的地", R.raw.cue_destination),
    LOCATION("请先定位", R.raw.cue_location),
    SETTINGS("请检查设置", R.raw.cue_settings),
    PASSWORD("密码已更新", R.raw.cue_password),
    EXPORTED("已导出", R.raw.cue_exported),
    NOTIFICATIONS("请开启通知", R.raw.cue_notifications),
    CALL("请先结束通话", R.raw.cue_call),
    CONNECT("请先连接耳机", R.raw.cue_connect),
    RETRY("请重试", R.raw.cue_retry),
    PERMISSION("请检查权限", R.raw.cue_permission),
    WEAR("请戴好耳机", R.raw.cue_wear),
    COPIED("已复制", R.raw.cue_copied),
    AUDIO("音频已切换", R.raw.cue_audio),
    TOUCH("已申请触控联动", R.raw.cue_touch),
    UNTOUCH("触控已关闭", R.raw.cue_untouch),
    BROWSER("无法打开网页", R.raw.cue_browser),
    BATTERY("耳机电量低", R.raw.cue_battery),
    READY("耳机已佩戴", R.raw.cue_ready);

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
