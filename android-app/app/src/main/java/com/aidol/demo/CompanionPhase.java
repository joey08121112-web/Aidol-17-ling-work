package com.aidol.demo;

/** Presentation state, independent of the selected life/work/commute business mode. */
public enum CompanionPhase {
    IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, RECORDING, PAUSED, SAVING, STOPPING,
    SUMMARIZING, COMPLETE, RESTING, ERROR
}
