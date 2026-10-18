package com.aidol.demo;

import java.net.URI;
import java.net.URLDecoder;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AmapNavigationTest {
    private JSONObject point(String name, double longitude, double latitude) throws Exception {
        return new JSONObject().put("name", name).put("longitude", longitude).put("latitude", latitude).put("id", "local-uuid");
    }
    private JSONObject journey(String originMode, String transport) throws Exception {
        return new JSONObject().put("active", true).put("origin_mode", originMode).put("transport", transport)
            .put("origin", point("起点广场", 116.397, 39.908))
            .put("destination", point("终点公园", 116.319, 39.999));
    }
    private Map<String, String> query(String uri) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : URI.create(uri).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], "UTF-8");
            assertNull("Duplicate query parameter: " + key, params.put(key, URLDecoder.decode(parts[1], "UTF-8")));
        }
        return params;
    }
    private void rejected(JSONObject value) {
        try { AmapNavigation.create(value, true); fail("Invalid journey accepted"); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
    }

    @Test public void allModesPreserveFixedOriginAndUseCurrentPositionForGps() throws Exception {
        String[] methods = {"driving", "transit", "walking", "bicycling"};
        String[] webModes = {"car", "bus", "walk", "ride"};
        for (String mode : new String[] {"gps", "fixed"}) {
            for (int index = 0; index < methods.length; index++) {
                JSONObject value = journey(mode, methods[index]);
                String before = value.toString();
                AmapNavigation.Plan plan = AmapNavigation.create(value, true);
                assertEquals(before, value.toString());
                assertEquals(methods[index], plan.transport);
                Map<String, String> route = query(plan.routeUri), web = query(plan.webUri);
                assertEquals(Integer.toString(index), route.get("t"));
                assertEquals("116.319", route.get("dlon")); assertEquals("39.999", route.get("dlat"));
                assertEquals("终点公园", route.get("dname")); assertEquals("0", route.get("dev"));
                assertEquals("Aidol", route.get("sourceApplication"));
                assertEquals(webModes[index], web.get("mode")); assertEquals("0", web.get("callnative"));
                assertEquals("116.319,39.999,终点公园", web.get("to")); assertEquals("Aidol", web.get("src"));
                assertEquals("https", URI.create(plan.webUri).getScheme());
                assertEquals("uri.amap.com", URI.create(plan.webUri).getHost());
                assertFalse(route.containsKey("sid")); assertFalse(route.containsKey("did"));
                assertFalse(route.containsKey("style")); assertFalse(route.containsKey("m"));
                assertFalse(web.containsKey("coordinate"));
                if ("fixed".equals(mode)) {
                    assertFalse(plan.direct); assertEquals(plan.routeUri, plan.appUri);
                    assertEquals("116.397", route.get("slon")); assertEquals("39.908", route.get("slat"));
                    assertEquals("起点广场", route.get("sname")); assertEquals("116.397,39.908,起点广场", web.get("from"));
                } else {
                    assertFalse(route.containsKey("slat")); assertFalse(route.containsKey("slon"));
                    assertFalse(route.containsKey("sname")); assertFalse(web.containsKey("from"));
                    assertEquals(index != 1, plan.direct);
                }
            }
        }
    }

    @Test public void gpsDrivingDirectNavigationUsesNativeDefaults() throws Exception {
        AmapNavigation.Plan plan = AmapNavigation.create(journey("gps", "driving"), false);
        Map<String, String> params = query(plan.appUri);
        assertTrue(plan.direct); assertTrue(plan.appUri.startsWith("androidamap://navi?"));
        assertEquals("0", params.get("style")); assertEquals("0", params.get("dev"));
        assertEquals("116.319", params.get("lon")); assertEquals("39.999", params.get("lat"));
        assertEquals("终点公园", params.get("poiname")); assertEquals("Aidol", params.get("sourceApplication"));
        assertEquals(6, params.size());
    }

    @Test public void supportedGpsWalkingAndBicyclingLaunchTheirNavigationFeatures() throws Exception {
        AmapNavigation.Plan walking = AmapNavigation.create(journey("gps", "walking"), true);
        AmapNavigation.Plan bicycling = AmapNavigation.create(journey("gps", "bicycling"), true);
        assertTrue(walking.appUri.startsWith("amapuri://openFeature?"));
        assertEquals("OnFootNavi", query(walking.appUri).get("featureName"));
        assertFalse(query(walking.appUri).containsKey("rideType"));
        assertEquals("OnRideNavi", query(bicycling.appUri).get("featureName"));
        assertEquals("bike", query(bicycling.appUri).get("rideType"));
        assertEquals("0", query(bicycling.appUri).get("dev"));
    }

    @Test public void oldOrUnknownVersionsFallBackToRoutePlanningForWalkingAndBicycling() throws Exception {
        for (String method : new String[] {"walking", "bicycling", "transit"}) {
            AmapNavigation.Plan plan = AmapNavigation.create(journey("gps", method), false);
            assertFalse(plan.direct); assertEquals(plan.routeUri, plan.appUri);
            assertTrue(plan.appUri.startsWith("amapuri://route/plan/?"));
        }
    }

    @Test public void gpsIgnoresStaleOrMalformedStoredOrigin() throws Exception {
        JSONObject value = journey("gps", "driving").put("origin", new JSONObject().put("longitude", "invalid"));
        AmapNavigation.Plan plan = AmapNavigation.create(value, true);
        assertFalse(query(plan.routeUri).containsKey("slat")); assertFalse(query(plan.webUri).containsKey("from"));
        value.put("origin", JSONObject.NULL);
        assertEquals(plan.appUri, AmapNavigation.create(value, true).appUri);
    }

    @Test public void absentOrBlankNamesUseReadableDefaults() throws Exception {
        JSONObject value = journey("fixed", "transit");
        value.getJSONObject("origin").remove("name"); value.getJSONObject("destination").put("name", " \t ");
        AmapNavigation.Plan plan = AmapNavigation.create(value, true);
        assertEquals("起点", query(plan.routeUri).get("sname")); assertEquals("目的地", query(plan.routeUri).get("dname"));
    }

    @Test public void specialCharactersCannotInjectParametersOrBreakWebPointSeparators() throws Exception {
        String name = "家,东门，二层 &t=99/#? + 🏠";
        JSONObject value = journey("fixed", "walking");
        value.getJSONObject("destination").put("name", name); value.getJSONObject("origin").put("name", name);
        AmapNavigation.Plan plan = AmapNavigation.create(value, true);
        Map<String, String> route = query(plan.routeUri), web = query(plan.webUri);
        assertEquals(name, route.get("dname")); assertEquals(name, route.get("sname"));
        assertEquals("2", route.get("t")); assertEquals(9, route.size()); assertEquals(5, web.size());
        assertEquals("116.319,39.999," + name.replace(',', ' ').replace('，', ' '), web.get("to"));
        assertNull(URI.create(plan.routeUri).getFragment()); assertNull(URI.create(plan.webUri).getFragment());
        assertFalse(plan.routeUri.contains(" ")); assertFalse(plan.routeUri.contains("🏠"));
    }

    @Test public void endedMissingOrInvalidJourneyStateCannotNavigate() throws Exception {
        rejected(null); rejected(new JSONObject());
        rejected(journey("gps", "driving").put("active", false));
        rejected(journey("gps", "driving").put("active", "true"));
        rejected(journey("unknown", "driving")); rejected(journey("gps", "flying"));
        JSONObject value = journey("gps", "driving"); value.remove("destination"); rejected(value);
        rejected(journey("fixed", "driving").put("origin", JSONObject.NULL));
    }

    @Test public void illegalDestinationAndFixedOriginCoordinatesCannotNavigate() throws Exception {
        for (String point : new String[] {"origin", "destination"}) {
            for (Object[] invalid : new Object[][] {{"longitude", 180.1}, {"longitude", -180.1}, {"latitude", 90.1},
                    {"latitude", -90.1}, {"latitude", "NaN"}, {"longitude", "Infinity"}, {"latitude", JSONObject.NULL},
                    {"longitude", "116.319"}, {"latitude", true}}) {
                JSONObject value = journey("fixed", "walking");
                value.getJSONObject(point).put((String) invalid[0], invalid[1]); rejected(value);
            }
            JSONObject value = journey("fixed", "walking"); value.getJSONObject(point).remove("longitude"); rejected(value);
        }
    }

    @Test public void geographicBoundsAndZeroCoordinatesRemainValid() throws Exception {
        JSONObject value = journey("fixed", "walking").put("origin", point("origin", -180, -90)).put("destination", point("destination", 180, 90));
        assertEquals("180.0", query(AmapNavigation.create(value, true).routeUri).get("dlon"));
        value.put("destination", point("zero", 0, 0));
        assertEquals("0.0", query(AmapNavigation.create(value, true).routeUri).get("dlat"));
    }

    @Test public void nonFiniteNumberObjectsAreRejectedBeforeLinkEncoding() throws Exception {
        Number notANumber = new Number() {
            public int intValue() { return 0; }
            public long longValue() { return 0; }
            public float floatValue() { return Float.NaN; }
            public double doubleValue() { return Double.NaN; }
        };
        for (Number value : new Number[] {new BigDecimal("1e400"), new BigDecimal("-1e400"), notANumber}) {
            JSONObject trip = journey("gps", "driving");
            trip.getJSONObject("destination").put("longitude", value);
            rejected(trip);
        }
    }

    @Test public void featureVersionGateSupportsNumericPrefixesAt826AndLater() {
        for (String value : new String[] {"8.26.0", "8.26", "8.26.1 beta", "8.26.0.1234", "08.026.000", "8.27.0", "9.0.0", " 15.04.2 "})
            assertTrue(value, AmapNavigation.supportsFeatureNavigation(value));
        for (String value : new String[] {null, "", "unknown", "v8.26.0", "8.25.99", "8", "7.99.99", "-1.0", "9999999999999999999999999.0"})
            assertFalse(value, AmapNavigation.supportsFeatureNavigation(value));
    }
}
