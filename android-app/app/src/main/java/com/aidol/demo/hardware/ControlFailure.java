package com.aidol.demo.hardware;

/** Keeps the initiating failure distinct from a later SDK cleanup result. */
final class ControlFailure {
    enum Stage {
        OPEN("开启", "open"), LISTENER("监听", "listener"), CLOSE("关闭", "close");
        final String label, diagnostic;
        Stage(String label, String diagnostic) { this.label = label; this.diagnostic = diagnostic; }
    }
    private final Stage stage;
    private final int code;
    private final String detail;
    private final Integer status;
    private final ControlFailure cleanup;

    ControlFailure(Stage stage, int code, String message) {
        this(stage, code, safeDetail(message), responseStatus(message), null);
    }
    private ControlFailure(Stage stage, int code, String detail, Integer status, ControlFailure cleanup) {
        this.stage = stage; this.code = code; this.detail = detail; this.status = status; this.cleanup = cleanup;
    }
    ControlFailure withCleanup(int code, String message) {
        return new ControlFailure(stage, this.code, detail, status, new ControlFailure(Stage.CLOSE, code, message));
    }
    String summary() {
        String value = "触控" + stage.label + "未确认（代码 " + code + (detail.isEmpty() ? "" : " · " + detail) + "）";
        if (cleanup != null) value += "；清理结果：代码 " + cleanup.code
                + (cleanup.detail.isEmpty() ? "" : " · " + cleanup.detail);
        return value + "；请重新连接耳机";
    }
    String diagnostics() {
        return "controls_" + stage.diagnostic + "_" + code + (status == null ? "" : "_status_" + status)
                + (cleanup == null ? "" : " cleanup_" + cleanup.code
                + (cleanup.status == null ? "" : "_status_" + cleanup.status));
    }
    private static Integer responseStatus(String message) {
        if (message == null || !(message.startsWith("耳机拒绝开启测试上报") || message.startsWith("耳机拒绝关闭测试上报"))) return null;
        int marker = message.indexOf("status=");
        if (marker < 0) return null;
        String value = message.substring(marker + 7).split("[，,]", 2)[0];
        if (!value.matches("-?[0-9]{1,3}")) return null;
        int status = Integer.parseInt(value);
        return status >= -1 && status <= 255 ? status : null;
    }
    private static String safeDetail(String message) {
        Integer status = responseStatus(message);
        if (status != null) return (message.startsWith("耳机拒绝开启") ? "耳机拒绝开启测试上报" : "耳机拒绝关闭测试上报") + " status=" + status;
        if (message == null) return "";
        // Only documented, fixed SDK strings and local diagnostics may enter the UI.
        switch (message) {
            case "测试监听正在使用或处理命令，请稍后重试":
            case "正在反注册监听，请等待结果":
            case "上次上报状态未确认，请重连 Aura 耳机后重试":
            case "上报状态未确认，请重连 Aura 耳机":
            case "测试事件命令超时，请重连 Aura 耳机后重试":
            case "监听注册已取消":
            case "该监听未持有当前测试会话":
            case "请先连接 Aura 耳机":
            case "设备断开，测试监听已取消":
            case "设备切换，测试监听已取消":
            case "SDK 已销毁，测试监听已取消":
            case "应用等待开启确认超时":
            case "SDK 未确认当前监听":
            case "触控调用异常":
            case "应用等待关闭确认超时":
                return message;
            default: return "";
        }
    }
}
