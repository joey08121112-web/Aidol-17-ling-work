package com.aidol.demo;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TransitProgressTest {
    private JSONObject route() throws Exception {
        JSONObject leg = new JSONObject().put("type", "transit").put("line", "测试地铁1号线").put("duration_seconds",600)
            .put("arrival_stop","换乘站").put("stations",new JSONArray().put("始发站").put("前一站").put("换乘站"))
            .put("station_points",new JSONArray().put(new JSONObject().put("longitude",0).put("latitude",0))
                .put(new JSONObject().put("longitude",.01).put("latitude",0)).put(new JSONObject().put("longitude",.02).put("latitude",0)));
        JSONObject second = new JSONObject(leg.toString()).put("arrival_stop","终点站").put("line","测试地铁2号线");
        return new JSONObject().put("steps",new JSONArray().put(leg).put(second));
    }
    private JSONObject boarded(long at) throws Exception { return new JSONObject().put("boarded_at",at).put("last_periodic",at); }
    @Test public void noReminderUntilBoardingAndNoClockResetOnDuplicateBoard() throws Exception {
        TransitProgress p = new TransitProgress(route(),null);
        assertNull(p.update(System.currentTimeMillis(),null)); assertTrue(p.waiting());
        p.board(); long first=p.snapshot().getLong("boarded_at");p.board();assertEquals(first,p.snapshot().getLong("boarded_at"));
    }
    @Test public void timedWarningIsEstimateAndDeduplicatedAfterRestore() throws Exception {
        long now=1000000;TransitProgress p=new TransitProgress(route(),boarded(now));
        String reminder=p.update(now+300000,null);assertTrue(reminder.startsWith("时间估算"));assertTrue(reminder.contains("换乘"));
        assertNull(p.update(now+301000,null));
        TransitProgress restored=new TransitProgress(route(),p.snapshot());assertNull(restored.update(now+302000,null));
        assertTrue(restored.update(now+691000,null).contains("换乘上车"));assertTrue(restored.waiting());assertNull(restored.update(now+999000,null));
    }
    @Test public void gpsPriorStationTriggersBeforeScheduleAndIgnoresStaleFix() throws Exception {
        long now=1000000;TransitProgress p=new TransitProgress(route(),boarded(now));
        assertNull(p.update(now+10000,new LocationSnapshot(.01,0,10,now-60000)));
        String message=p.update(now+10000,new LocationSnapshot(.01,0,10,now+10000));assertTrue(message.startsWith("定位提示"));assertTrue(message.contains("前一站"));
        assertNull(p.update(now+11000,new LocationSnapshot(0,0,10,now+11000)));
    }
    @Test public void periodicAndLateResumeNeverClaimLiveArrival() throws Exception {
        long now=1000000;TransitProgress p=new TransitProgress(route(),boarded(now));
        assertTrue(p.update(now+180000,null).contains("定位不足"));
        TransitProgress late=new TransitProgress(route(),boarded(now));assertTrue(late.update(now+900000,null).contains("计划时间已过"));
    }
    @Test public void noPlanAndCoordinateConversion() throws Exception {
        TransitProgress p=new TransitProgress(new JSONObject(),null);assertFalse(p.available());assertNull(p.update(1234,null));
        double[] converted=TransitProgress.gcj(116.397,39.908);assertTrue(converted[0]>116.40&&converted[0]<116.41);assertTrue(converted[1]>39.909);
        assertArrayEquals(new double[]{0,0},TransitProgress.gcj(0,0),.00001);
        assertTrue(TransitProgress.testMessage().contains("模拟测试"));
    }
    @Test public void automaticBoardingRequiresFreshMovingFixNearItinerary() throws Exception {
        long now=1000000; TransitProgress p=new TransitProgress(route(),null);
        p.autoBoard(now,new LocationSnapshot(.01,0,10,now),false); assertTrue(p.waiting());
        p.autoBoard(now,new LocationSnapshot(.01,0,10,now-60000),true); assertTrue(p.waiting());
        p.autoBoard(now,new LocationSnapshot(.01,0,10,now),true); assertFalse(p.waiting());
        assertTrue(p.update(now,new LocationSnapshot(.01,0,10,now)).contains("前一站"));
    }
}
