package com.aidol.demo;

/** Canonical business-mode names shared by UI and local voice cues. */
final class ModeCopy {
    static final String LIFE = "生活模式", WORK = "工作模式", COMMUTE = "通勤模式";
    static String name(String mode) {
        if ("life".equals(mode)) return LIFE;
        if ("work".equals(mode)) return WORK;
        if ("commute".equals(mode)) return COMMUTE;
        return "未知模式";
    }
    private ModeCopy() {}
}
