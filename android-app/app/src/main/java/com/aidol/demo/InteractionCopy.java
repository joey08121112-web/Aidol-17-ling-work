package com.aidol.demo;

/** Copy describes the selected interaction, independently of the business mode. */
final class InteractionCopy {
    static String name(int selection) {
        switch (selection) {
            case VoiceInteraction.QUESTION: return "单句提问";
            case VoiceInteraction.CALL: return "AI 全双工通话";
            case VoiceInteraction.SCHEDULED: return "定时聆听";
            default: return "安静记录";
        }
    }
    static String start(int selection) {
        switch (selection) {
            case VoiceInteraction.CALL: return "接通 AI";
            case VoiceInteraction.SCHEDULED: return "开始定时聆听";
            case VoiceInteraction.NOTES: return "开始记录";
            default: return "开始提问";
        }
    }
    static String stop(int selection) {
        return selection == VoiceInteraction.CALL ? "挂断" : selection == VoiceInteraction.NOTES ? "结束并保存记录" : "停止";
    }
    static String ready(int selection) {
        switch (selection) {
            case VoiceInteraction.QUESTION: return "说完自动回答 · 本句完成后停止";
            case VoiceInteraction.CALL: return "持续聆听 · 说“小万”可打断 AI · 再次单击结束通话";
            case VoiceInteraction.SCHEDULED: return "定时分段聆听 · 每段结束后回复 · 停止后不再接续";
            default: return "持续录音转写 · 不执行语音指令 · 结束并保存";
        }
    }
    static String recording(int selection, boolean vad) {
        if (selection == VoiceInteraction.NOTES) return "正在录音转写 · 再次单击结束并保存";
        if (selection == VoiceInteraction.SCHEDULED) return vad ? "正在定时聆听 · 说完或到时提交" : "正在定时聆听 · 到时提交";
        if (selection == VoiceInteraction.CALL) return "正在听你说 · 停顿后自动回答 · 单击结束会话";
        return "正在听你说 · 停顿约 0.6 秒自动回答";
    }
    private InteractionCopy() {}
}
