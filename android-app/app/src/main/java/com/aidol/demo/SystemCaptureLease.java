package com.aidol.demo;

import java.util.concurrent.atomic.AtomicReference;

/** Shared across SDK and AudioRecord; release only after device/route cleanup is confirmed. */
public final class SystemCaptureLease {
    private static final AtomicReference<Object> OWNER = new AtomicReference<>();
    private SystemCaptureLease() {}
    public static boolean acquire(Object owner) {
        if (owner == null) throw new IllegalArgumentException("capture owner is required");
        return OWNER.compareAndSet(null, owner);
    }
    public static void release(Object owner) { if (owner != null) OWNER.compareAndSet(owner, null); }
}
