package com.aidol.demo;

import org.json.JSONObject;

/** Address-only presentation shared by meeting pages and exports. */
final class MeetingPlace {
    private MeetingPlace() {}

    static String label(JSONObject meeting) {
        JSONObject location = meeting.optJSONObject("location");
        if (location != null && !location.isNull("address")) {
            String address = location.optString("address").trim();
            if (!address.isEmpty()) return "开始地点：" + address;
        }
        if (location != null && location.has("longitude") && meeting.optInt("address_attempts") >= 4
                && meeting.isNull("address_retry_at")) return "开始地点：地址暂未获取（已重试3次）";
        return location != null && location.has("longitude") && location.has("latitude")
            ? "开始地点：地址暂未解析，稍后刷新"
            : "地点：未获取（定位未授权或暂无定位）";
    }
}
