package com.aidol.demo;

/** Only explicit SDK observations may establish removal. Unknown is not unworn. */
enum WearState {
    BOTH("双耳已佩戴"), ONE("已确认一耳佩戴"), REMOVED("双耳已摘下"), UNKNOWN("佩戴状态待确认");
    final String label;
    WearState(String label) { this.label = label; }
    boolean worn() { return this == BOTH || this == ONE; }
    static WearState from(Boolean left, Boolean right) {
        if (Boolean.TRUE.equals(left) && Boolean.TRUE.equals(right)) return BOTH;
        if (Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right)) return ONE;
        if (Boolean.FALSE.equals(left) && Boolean.FALSE.equals(right)) return REMOVED;
        return UNKNOWN;
    }
}
