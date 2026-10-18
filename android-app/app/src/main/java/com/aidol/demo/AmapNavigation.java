package com.aidol.demo;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** AMap links use saved GCJ-02 points and leave routing preferences to the map app. */
final class AmapNavigation {
    static final String PACKAGE = "com.autonavi.minimap";
    private static final Pattern VERSION = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?.*$");

    static final class Plan {
        final String appUri, routeUri, webUri, transport;
        final boolean direct;

        private Plan(String appUri, String routeUri, String webUri, String transport, boolean direct) {
            this.appUri = appUri; this.routeUri = routeUri; this.webUri = webUri;
            this.transport = transport; this.direct = direct;
        }
    }

    private static final class Point {
        final String longitude, latitude, name;
        Point(JSONObject value, String fallback) {
            if (value == null) throw new IllegalArgumentException("缺少导航地址");
            longitude = coordinate(value, "longitude", 180);
            latitude = coordinate(value, "latitude", 90);
            Object label = value.opt("name");
            name = label instanceof String && !((String) label).trim().isEmpty() ? ((String) label).trim() : fallback;
        }
        String tuple() { return longitude + "," + latitude + "," + name.replace(',', ' ').replace('，', ' '); }
    }

    static Plan create(JSONObject journey, boolean featureNavigation) {
        if (journey == null || !Boolean.TRUE.equals(journey.opt("active")))
            throw new IllegalArgumentException("请先开始通勤");
        String originMode = journey.optString("origin_mode", "");
        if (!"gps".equals(originMode) && !"fixed".equals(originMode))
            throw new IllegalArgumentException("起点类型无效");
        String transport = journey.optString("transport", "");
        String routeType, webMode;
        switch (transport) {
            case "driving": routeType = "0"; webMode = "car"; break;
            case "transit": routeType = "1"; webMode = "bus"; break;
            case "walking": routeType = "2"; webMode = "walk"; break;
            case "bicycling": routeType = "3"; webMode = "ride"; break;
            default: throw new IllegalArgumentException("出行方式无效");
        }
        Point destination = new Point(journey.optJSONObject("destination"), "目的地");
        // For GPS trips the external navigator obtains a fresh device location itself.
        Point origin = "fixed".equals(originMode) ? new Point(journey.optJSONObject("origin"), "起点") : null;
        Map<String, String> route = new LinkedHashMap<>();
        route.put("sourceApplication", "Aidol");
        if (origin != null) {
            route.put("slat", origin.latitude); route.put("slon", origin.longitude); route.put("sname", origin.name);
        }
        route.put("dlat", destination.latitude); route.put("dlon", destination.longitude); route.put("dname", destination.name);
        route.put("dev", "0"); route.put("t", routeType);
        String routeUri = uri("amapuri://route/plan/", route);
        Map<String, String> web = new LinkedHashMap<>();
        if (origin != null) web.put("from", origin.tuple());
        web.put("to", destination.tuple()); web.put("mode", webMode);
        web.put("src", "Aidol"); web.put("callnative", "0");
        String webUri = uri("https://uri.amap.com/navigation", web);
        String appUri = routeUri;
        boolean direct = false;
        if (origin == null && "driving".equals(transport)) {
            Map<String, String> navigation = new LinkedHashMap<>();
            navigation.put("sourceApplication", "Aidol"); navigation.put("poiname", destination.name);
            navigation.put("lat", destination.latitude); navigation.put("lon", destination.longitude);
            navigation.put("dev", "0"); navigation.put("style", "0");
            appUri = uri("androidamap://navi", navigation); direct = true;
        } else if (origin == null && featureNavigation && ("walking".equals(transport) || "bicycling".equals(transport))) {
            Map<String, String> navigation = new LinkedHashMap<>();
            navigation.put("featureName", "walking".equals(transport) ? "OnFootNavi" : "OnRideNavi");
            navigation.put("lat", destination.latitude); navigation.put("lon", destination.longitude);
            navigation.put("dev", "0"); navigation.put("sourceApplication", "Aidol");
            if ("bicycling".equals(transport)) navigation.put("rideType", "bike");
            appUri = uri("amapuri://openFeature", navigation); direct = true;
        }
        return new Plan(appUri, routeUri, webUri, transport, direct);
    }

    static boolean supportsFeatureNavigation(String version) {
        if (version == null) return false;
        Matcher match = VERSION.matcher(version.trim());
        if (!match.matches()) return false;
        try {
            long major = Long.parseLong(match.group(1));
            long minor = match.group(2) == null ? 0 : Long.parseLong(match.group(2));
            // Parse the patch too, so malformed/overflowed numeric components fail closed.
            if (match.group(3) != null) Long.parseLong(match.group(3));
            return major > 8 || (major == 8 && minor >= 26);
        } catch (NumberFormatException invalid) { return false; }
    }

    private static String coordinate(JSONObject value, String key, double limit) {
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException("导航坐标无效");
        double number = ((Number) raw).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number < -limit || number > limit)
            throw new IllegalArgumentException("导航坐标无效");
        return Double.toString(number);
    }

    private static String uri(String base, Map<String, String> params) {
        StringBuilder value = new StringBuilder(base).append('?');
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (value.charAt(value.length() - 1) != '?') value.append('&');
            value.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return value.toString();
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A"); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    private AmapNavigation() {}
}
