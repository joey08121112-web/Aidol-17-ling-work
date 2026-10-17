package com.aidol.demo.hardware;

/** Product policy for initial registration; does not interpret firmware response statuses. */
final class ControlWearGate {
    private long connection;
    private boolean queried;
    private Boolean left, right;

    void reset(long connection) {
        this.connection = connection; queried = false; left = right = null;
    }
    boolean observe(long connection, boolean hasPosition, Boolean left, Boolean right) {
        if (connection != this.connection || !hasPosition) return false;
        this.left = left; this.right = right;
        return true;
    }
    boolean claimQuery(long connection) {
        if (connection != this.connection || queried) return false;
        queried = true; return true;
    }
    boolean worn() { return Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right); }
    boolean bothOut() { return Boolean.FALSE.equals(left) && Boolean.FALSE.equals(right); }
}
