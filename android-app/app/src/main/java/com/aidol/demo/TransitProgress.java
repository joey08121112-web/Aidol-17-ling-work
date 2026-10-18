package com.aidol.demo;

import org.json.*;
import java.util.*;

/** A frozen itinerary and explicit boarding clock. Timed estimates never pretend to be live train data. */
final class TransitProgress {
    private final JSONArray legs = new JSONArray();
    private int leg, station;
    private long boardedAt, lastPeriodic;
    private boolean warned;
    TransitProgress(JSONObject route, JSONObject saved) {
        JSONArray steps = route.optJSONArray("steps");
        if (steps != null) for (int i = 0; i < steps.length(); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s != null && "transit".equals(s.optString("type")) && s.optJSONArray("stations") != null && s.optJSONArray("stations").length() >= 2) legs.put(s);
        }
        if (saved != null) { leg = Math.max(0, saved.optInt("leg")); station = Math.max(0, saved.optInt("station")); boardedAt = saved.optLong("boarded_at"); warned = saved.optBoolean("warned"); lastPeriodic = saved.optLong("last_periodic"); }
    }
    boolean available() { return legs.length() > 0; }
    boolean waiting() { return boardedAt == 0 && leg < legs.length(); }
    String board() {
        if (leg >= legs.length()) return "本次公共交通路段已完成";
        if (boardedAt != 0) return "已记录上车时间，无需重复点击";
        boardedAt = System.currentTimeMillis(); lastPeriodic = boardedAt; return "已校准上车时间 · " + current().optString("line");
    }
    void autoBoard(long now, LocationSnapshot fix, boolean moving) {
        if (!waiting() || !moving || fix == null || !fix.usable(now, 45000)) return;
        JSONObject step = current(); JSONArray points = step.optJSONArray("station_points"), names = step.optJSONArray("stations");
        if (points == null || points.length() != names.length()) return;
        double[] point = gcj(fix.longitude, fix.latitude); double nearest = 250; int found = -1;
        for (int i = 0; i < points.length() - 1; i++) {
            JSONObject stop = points.optJSONObject(i); if (stop == null) continue;
            double distance = meters(point[0], point[1], stop.optDouble("longitude", Double.NaN), stop.optDouble("latitude", Double.NaN));
            if (distance < nearest) { nearest = distance; found = i; }
        }
        if (found >= 0) {
            station = found;
            boardedAt = now - Math.max(0, step.optLong("duration_seconds")) * 1000 * found / (names.length() - 1);
            lastPeriodic = now;
        }
    }
    private JSONObject current() { return legs.optJSONObject(leg); }
    String status() {
        if (!available()) return "等待公共交通站点信息";
        if (leg >= legs.length()) return "公共交通路段已完成";
        return (waiting() ? "等待定位识别上车，可点“我已上车”校准 · " : "站点提醒中 · ") + current().optString("line") + " · 在" + current().optString("arrival_stop") + (leg < legs.length() - 1 ? "换乘" : "下车");
    }
    JSONObject snapshot() {
        try { return new JSONObject().put("leg", leg).put("station", station).put("boarded_at", boardedAt).put("warned", warned).put("last_periodic", lastPeriodic); }
        catch (JSONException e) { return new JSONObject(); }
    }
    /** Only called after boarding. Fresh location advances monotonically; the timetable is fallback. */
    String update(long now, LocationSnapshot fix) {
        JSONObject step = current(); if (step == null || boardedAt == 0 || now < boardedAt) return null;
        JSONArray names = step.optJSONArray("stations"), points = step.optJSONArray("station_points"); int count = names.length();
        boolean located = false;
        if (fix != null && fix.usable(now, 45000) && points != null && points.length() == count) {
            double[] gcj = gcj(fix.longitude, fix.latitude);
            int nearest = -1; double best = 300;
            for (int i = station; i < count; i++) {
                JSONObject p = points.optJSONObject(i); if (p == null) continue;
                double distance = meters(gcj[0], gcj[1], p.optDouble("longitude", Double.NaN), p.optDouble("latitude", Double.NaN));
                if (distance < best) { best = distance; nearest = i; }
            }
            if (nearest >= station) { station = nearest; located = true; }
        }
        long duration = step.optLong("duration_seconds") * 1000;
        long elapsed = now - boardedAt;
        long target = duration > 0 ? duration * (count - 2) / (count - 1) : Long.MAX_VALUE;
        // Suppress an already passed timed reminder; late resume should not claim to be before the stop.
        boolean estimate = !located && duration > 0 && elapsed >= target && elapsed < duration + 90000;
        if (!warned && (located && station >= count - 2 || estimate)) {
            warned = true; lastPeriodic = now;
            return (located ? "定位提示：已接近" + names.optString(station) + "。" : "时间估算：预计已接近" + names.optString(count - 2) + "。")
                + (leg < legs.length() - 1 ? "请准备在" : located && station == count - 1 ? "请核对下车位置：" : "请准备下一站在") + step.optString("arrival_stop")
                + (leg < legs.length() - 1 ? "换乘。" : "下车。") + "请以车内报站为准。";
        }
        if (warned && (located && station == count - 1 || !located && duration > 0 && elapsed >= duration + 90000)) {
            leg++; station = 0; boardedAt = 0; warned = false;
            return leg < legs.length() ? "请留意换乘站。换乘上车后，在 App 点我已上车，校准下一段提醒。" : "本段提醒结束，请核对车内报站和下车位置。";
        }
        if (duration > 0 && elapsed > duration + 90000) { // missed while service paused
            leg++; station = 0; boardedAt = 0; warned = false;
            return "本段计划时间已过，请核对当前位置；换乘后重新确认上车。";
        }
        if (now - lastPeriodic >= 180000) {
            lastPeriodic = now;
            return (located ? "定位提示：接近" + names.optString(station) + "。" : "当前站点定位不足，正在按上车时间估算。")
                + "请留意" + step.optString("arrival_stop") + (leg < legs.length() - 1 ? "换乘站。" : "下车站。") + "以车内报站为准。";
        }
        return null;
    }
    static String testMessage() { return "测试站点提醒：预计下一站到达换乘站，请准备换乘。此为模拟测试，以车内报站为准。"; }
    static double meters(double x, double y, double a, double b) {
        double dy = Math.toRadians(b-y), dx = Math.toRadians(a-x);
        double h = Math.sin(dy/2)*Math.sin(dy/2)+Math.cos(Math.toRadians(y))*Math.cos(Math.toRadians(b))*Math.sin(dx/2)*Math.sin(dx/2);
        return 6371000 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
    }
    // Phone LocationManager reports WGS-84; AMap station coordinates are GCJ-02.
    static double[] gcj(double lon, double lat) {
        if (lon < 72.004 || lon > 137.8347 || lat < .8293 || lat > 55.8271) return new double[]{lon,lat};
        double x=lon-105,y=lat-35;
        double dLat=-100+2*x+3*y+.2*y*y+.1*x*y+.2*Math.sqrt(Math.abs(x));
        double dLon=300+x+2*y+.1*x*x+.1*x*y+.1*Math.sqrt(Math.abs(x));
        double common=(20*Math.sin(6*x*Math.PI)+20*Math.sin(2*x*Math.PI))*2/3;
        dLat+=common+(20*Math.sin(y*Math.PI)+40*Math.sin(y/3*Math.PI))*2/3+(160*Math.sin(y/12*Math.PI)+320*Math.sin(y*Math.PI/30))*2/3;
        dLon+=common+(20*Math.sin(x*Math.PI)+40*Math.sin(x/3*Math.PI))*2/3+(150*Math.sin(x/12*Math.PI)+300*Math.sin(x/30*Math.PI))*2/3;
        double rad=lat/180*Math.PI, sin=Math.sin(rad), magic=1-.00669342162296594323*sin*sin, root=Math.sqrt(magic);
        return new double[]{lon+dLon*180/(6378245/root*Math.cos(rad)*Math.PI),lat+dLat*180/((6378245*(1-.00669342162296594323))/(magic*root)*Math.PI)};
    }
}
