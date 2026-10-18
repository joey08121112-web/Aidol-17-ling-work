package com.aidol.demo;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeetingOrderTest {
    private JSONObject meeting(String id, String started, boolean pinned) throws Exception {
        return new JSONObject().put("id", id).put("started_at", started).put("pinned", pinned);
    }
    @Test public void pinsUseChronologicalOrderNotTapOrderAndLatestIgnoresPins() throws Exception {
        JSONObject old = meeting("old", "2030-01-01T08:00:00+08:00", true);
        JSONObject middle = meeting("middle", "2030-01-01T00:30:00Z", true);
        JSONObject latest = meeting("latest", "2030-01-01T02:00:00Z", false);
        JSONArray input = new JSONArray().put(old).put(latest).put(middle);
        JSONArray sorted = MeetingOrder.sorted(input);
        assertSame(middle, sorted.getJSONObject(0)); assertSame(old, sorted.getJSONObject(1)); assertSame(latest, sorted.getJSONObject(2));
        assertSame(latest, MeetingOrder.latest(sorted)); assertSame(old, input.getJSONObject(0));
        old.put("pinned", false); sorted = MeetingOrder.sorted(input);
        assertSame(middle, sorted.getJSONObject(0)); assertSame(latest, sorted.getJSONObject(1)); assertSame(old, sorted.getJSONObject(2));
    }
    @Test public void emptyHistoryHasNoLatestMeeting() { assertNull(MeetingOrder.latest(new JSONArray())); }
}
