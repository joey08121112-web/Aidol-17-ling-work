package com.aidol.demo;

import org.json.JSONObject;
import java.time.ZoneId;

/** Address-only presentation shared by meeting pages and exports. */
final class MeetingPlace {
    private MeetingPlace() {}

    static String label(JSONObject meeting) { return label(meeting, ZoneId.systemDefault()); }
    static String label(JSONObject meeting, ZoneId zone) {
        JSONObject location = meeting.optJSONObject("location");
        String cache = location != null && location.optBoolean("cached")
            ? "（缓存定位 · " + TimeFormat.format(location.optString("observed_at"), zone) + "）" : "";
        if (location != null && !location.isNull("address")) {
            String address = location.optString("address").trim();
            if (!address.isEmpty()) return "开始地点：" + address + cache;
        }
        if (location != null && location.has("longitude") && meeting.optInt("address_attempts") >= 4
                && meeting.isNull("address_retry_at")) return "开始地点：地址暂未获取（已重试3次）" + cache;
        return location != null && location.has("longitude") && location.has("latitude")
            ? "开始地点：地址暂未解析，稍后刷新" + cache
            : "地点：未获取（定位未授权或暂无定位）";
    }
}
