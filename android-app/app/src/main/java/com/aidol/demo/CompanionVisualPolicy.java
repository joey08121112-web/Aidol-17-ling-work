package com.aidol.demo;

/** Presentation only: these choices never start recording, playback or an account operation. */
final class CompanionVisualPolicy {
    enum Asset { LIFE, COMMUTE, REST }

    static final long REST_DURATION_MILLIS = 6042;

    private CompanionVisualPolicy() { }

    static String mode(String mode) {
        return "work".equals(mode) ? "work" : "commute".equals(mode) ? "commute" : "life";
    }

    static Asset asset(String mode, CompanionPhase phase) {
        if (phase == CompanionPhase.RESTING) return Asset.REST;
        return "commute".equals(mode) ? Asset.COMMUTE : Asset.LIFE;
    }

    static boolean playsVideo(String mode, CompanionPhase phase, boolean animationsEnabled) {
        if (!animationsEnabled) return false;
        if (phase == CompanionPhase.RESTING) return true;
        // Work stays awake and visually quiet even while saving or summarizing.
        return !"work".equals(mode) && phase != CompanionPhase.ERROR
            && phase != CompanionPhase.PAUSED && phase != CompanionPhase.STOPPING;
    }

    static boolean loops(CompanionPhase phase) { return phase != CompanionPhase.RESTING; }

    static boolean inputMeter(CompanionPhase phase) {
        return phase == CompanionPhase.LISTENING || phase == CompanionPhase.RECORDING;
    }

    static boolean outputMeter(CompanionPhase phase) { return phase == CompanionPhase.SPEAKING; }

    static boolean progressing(CompanionPhase phase) {
        return phase == CompanionPhase.CONNECTING || phase == CompanionPhase.THINKING
            || phase == CompanionPhase.SAVING || phase == CompanionPhase.SUMMARIZING;
    }

    static long restPosition(long startedAt, long now) {
        return Math.min(REST_DURATION_MILLIS, Math.max(0, now - startedAt));
    }

    static float level(float level) {
        return Float.isNaN(level) || Float.isInfinite(level) ? 0 : Math.max(0, Math.min(1, level));
    }

    static String label(String mode, CompanionPhase phase) {
        switch (phase) {
            case CONNECTING: return "正在连接";
            case LISTENING: return "正在聆听";
            case THINKING: return "正在思考";
            case SPEAKING: return "正在回复";
            case RECORDING: return "正在记录";
            case PAUSED: return "work".equals(mode) ? "记录已暂停" : "已暂停";
            case SAVING: return "正在保存";
            case SUMMARIZING: return "正在整理";
            case COMPLETE: return "已完成";
            case STOPPING: return "正在结束";
            case RESTING: return "会话已结束";
            case ERROR: return "暂时遇到问题";
            default: return "work".equals(mode) ? "准备记录" : "随时可以聊天";
        }
    }
}
