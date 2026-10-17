package com.aidol.demo;

import org.junit.Test;
import static org.junit.Assert.*;

public class ModeCopyTest {
    @Test public void screenAndLocalCueUseExactlyTheSameModeNames() {
        for (String mode : new String[]{"life", "work", "commute"}) {
            assertEquals(ModeCopy.name(mode), LocalCue.forMode(mode).label);
        }
        assertEquals("未知模式", ModeCopy.name("other"));
    }
}
