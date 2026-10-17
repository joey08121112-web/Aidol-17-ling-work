package com.aidol.demo;

import java.time.ZoneId;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeetingPlaceTest {
    @Test public void pagesAndExportUseResolvedAddress() throws Exception {
        JSONObject meeting = new JSONObject().put("location", new JSONObject()
            .put("longitude", 116.4).put("latitude", 39.9).put("address", "北京市测试路1号"));
        assertEquals("开始地点：北京市测试路1号", MeetingPlace.label(meeting));
        String text = MeetingExport.markdown(meeting, ZoneId.of("Asia/Shanghai"));
        assertTrue(text.contains("- 开始地点：北京市测试路1号"));
        assertFalse(text.contains("116.4"));
        assertFalse(text.contains("39.9"));
    }

    @Test public void unresolvedCoordinatesNeverAppearInPagesOrExport() throws Exception {
        JSONObject meeting = new JSONObject().put("location", new JSONObject()
            .put("longitude", 116.4).put("latitude", 39.9).put("address", JSONObject.NULL));
        assertEquals("开始地点：地址暂未解析，稍后刷新", MeetingPlace.label(meeting));
        String text = MeetingExport.markdown(meeting, ZoneId.of("Asia/Shanghai"));
        assertFalse(text.contains("116.4"));
        assertFalse(text.contains("39.9"));
        assertFalse(text.contains("null"));
    }

    @Test public void absentLocationIsDifferentFromAnUnresolvedAddress() throws Exception {
        assertTrue(MeetingPlace.label(new JSONObject()).startsWith("地点：未获取"));
        assertEquals("开始地点：测试会议室", MeetingPlace.label(new JSONObject()
            .put("location", new JSONObject().put("address", "测试会议室"))));
    }

    @Test public void exhaustedRetriesAreShownWithoutCoordinatesOrAnEndlessRetryPromise() throws Exception {
        JSONObject meeting = new JSONObject().put("location", new JSONObject().put("longitude", 116.4).put("latitude", 39.9))
            .put("address_attempts", 4).put("address_retry_at", JSONObject.NULL);
        assertEquals("开始地点：地址暂未获取（已重试3次）", MeetingPlace.label(meeting));
        assertFalse(MeetingExport.markdown(meeting, ZoneId.of("Asia/Shanghai")).contains("116.4"));
        meeting.put("address_retry_at", 123456);
        assertFalse(MeetingPlace.label(meeting).contains("已重试3次"));
    }
    @Test public void cachedAddressAndObservationTimeSurviveExportWithoutCoordinates() throws Exception {
        JSONObject meeting = new JSONObject().put("location", new JSONObject()
            .put("longitude", 116.4).put("latitude", 39.9).put("address", "测试会议室")
            .put("cached", true).put("observed_at", "2026-10-18T01:00:00Z"));
        String label = MeetingPlace.label(meeting);
        assertTrue(label.contains("测试会议室（缓存定位 · "));
        assertTrue(label.contains(TimeFormat.format("2026-10-18T01:00:00Z")));
        String export = MeetingExport.markdown(meeting, ZoneId.of("Asia/Shanghai"));
        assertTrue(export.contains("缓存定位 · 2026-10-18 09:00:00"));
        assertFalse(export.contains("116.4")); assertFalse(export.contains("39.9"));
        meeting.getJSONObject("location").remove("address");
        assertTrue(MeetingPlace.label(meeting).contains("缓存定位"));
        meeting.put("address_attempts", 4).put("address_retry_at", JSONObject.NULL);
        assertTrue(MeetingPlace.label(meeting).contains("已重试3次"));
        assertTrue(MeetingPlace.label(meeting).contains("缓存定位"));
    }
}
