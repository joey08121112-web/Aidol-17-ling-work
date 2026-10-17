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
}
