package com.aidol.demo;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeetingLocationTest {
    private static LocationSnapshot point(long age) {
        return new LocationSnapshot(116.4, 39.9, 30, System.currentTimeMillis() - age);
    }
    private static final class Source implements MeetingStartLocation.Source {
        boolean visible; int requests; LocationSnapshot saved, fresh;
        Source(boolean visible, LocationSnapshot saved, LocationSnapshot fresh) {
            this.visible = visible; this.saved = saved; this.fresh = fresh;
        }
        public boolean foreground() { return visible; }
        public LocationSnapshot cached() { return saved; }
        public LocationSnapshot acquire() { requests++; return fresh; }
    }
    @Test public void backgroundUsesOlderCacheWithoutRequestingLocation() throws Exception {
        Source source = new Source(false, point(3_600_000), point(0));
        JSONObject result = MeetingStartLocation.capture(source);
        assertTrue(result.getBoolean("cached"));
        assertEquals(source.saved.observedAt, LocationSnapshot.parse(result.toString()).observedAt);
        assertEquals(0, source.requests);
    }
    @Test public void absentOrExpiredBackgroundCacheDoesNotRequestLocation() {
        for (LocationSnapshot cache : new LocationSnapshot[]{null, point(LocationSnapshot.CACHE_MS + 1)}) {
            Source source = new Source(false, cache, point(0));
            assertNull(MeetingStartLocation.capture(source)); assertEquals(0, source.requests);
        }
    }
    @Test public void foregroundRefreshesOldCacheAndMarksAcquiredPosition() throws Exception {
        Source source = new Source(true, point(180_000), point(0));
        assertFalse(MeetingStartLocation.capture(source).getBoolean("cached")); assertEquals(1, source.requests);
    }
    @Test public void foregroundTimeoutKeepsOriginalObservationTime() throws Exception {
        Source source = new Source(true, point(180_000), null);
        JSONObject result = MeetingStartLocation.capture(source);
        assertTrue(result.getBoolean("cached")); assertEquals(1, source.requests);
        assertEquals(source.saved.observedAt, LocationSnapshot.parse(result.toString()).observedAt);
    }
    @Test public void foregroundRecentCacheNeedsNoNewRequest() throws Exception {
        Source source = new Source(true, point(1000), null);
        assertTrue(MeetingStartLocation.capture(source).getBoolean("cached")); assertEquals(0, source.requests);
    }
    @Test public void failedFixWithoutValidFallbackReturnsNoPosition() {
        Source source = new Source(true, point(LocationSnapshot.CACHE_MS + 1), point(180_000));
        assertNull(MeetingStartLocation.capture(source)); assertEquals(1, source.requests);
    }
    @Test public void invalidAndFuturePositionsAreRejected() {
        long now = System.currentTimeMillis();
        for (LocationSnapshot value : new LocationSnapshot[]{
                new LocationSnapshot(Double.NaN, 39, 20, now), new LocationSnapshot(181, 39, 20, now),
                new LocationSnapshot(116, -91, 20, now), new LocationSnapshot(116, 39, 201, now),
                new LocationSnapshot(116, 39, -1, now), new LocationSnapshot(116, 39, 20, now + 1)})
            assertFalse(value.usable(now, LocationSnapshot.CACHE_MS));
        assertTrue(new LocationSnapshot(116, 39, 20, now - LocationSnapshot.CACHE_MS).usable(now, LocationSnapshot.CACHE_MS));
        assertFalse(new LocationSnapshot(116, 39, 20, now - LocationSnapshot.CACHE_MS - 1).usable(now, LocationSnapshot.CACHE_MS));
        assertNull(LocationSnapshot.parse("{}"));
    }
    @Test public void lateOldFixNeverReplacesNewerCache() {
        LocationSnapshot old = point(100_000), recent = point(1000);
        assertSame(recent, LocationSnapshot.newest(recent, old, System.currentTimeMillis()));
        assertSame(recent, LocationSnapshot.newest(old, recent, System.currentTimeMillis()));
    }
}
