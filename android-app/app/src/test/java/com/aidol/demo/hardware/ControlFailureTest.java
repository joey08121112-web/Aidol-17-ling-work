package com.aidol.demo.hardware;

import org.junit.Test;
import static org.junit.Assert.*;

public class ControlFailureTest {
    @Test public void cleanupBusyPreservesInitialEnableRejectionAndNumericStatus() {
        ControlFailure first = new ControlFailure(ControlFailure.Stage.OPEN, 2400, "耳机拒绝开启测试上报，status=3");
        ControlFailure result = first.withCleanup(2401, "正在反注册监听，请等待结果");
        assertEquals("controls_open_2400_status_3 cleanup_2401", result.diagnostics());
        assertTrue(result.summary().contains("触控开启未确认"));
        assertTrue(result.summary().contains("耳机拒绝开启测试上报 status=3"));
        assertTrue(result.summary().contains("清理结果：代码 2401 · 正在反注册监听，请等待结果"));
        assertFalse(result.summary().contains("触控关闭未确认"));
        assertEquals("controls_open_2400_status_3", first.diagnostics());
    }
    @Test public void cleanupFailureKeepsBothFirmwareResponses() {
        ControlFailure result = new ControlFailure(ControlFailure.Stage.OPEN, 2400, "耳机拒绝开启测试上报，status=1")
                .withCleanup(2400, "耳机拒绝关闭测试上报，status=2，请重连 Aura 耳机");
        assertEquals("controls_open_2400_status_1 cleanup_2400_status_2", result.diagnostics());
        assertTrue(result.summary().contains("开启测试上报 status=1"));
        assertTrue(result.summary().contains("关闭测试上报 status=2"));
    }
    @Test public void unknownSdkDetailsCannotLeakDeviceOrUserData() {
        String untrusted = "device=AA:BB:CC:DD:EE:FF unrelated private content status=7";
        ControlFailure result = new ControlFailure(ControlFailure.Stage.OPEN, 2400, untrusted).withCleanup(2401, untrusted);
        assertEquals("controls_open_2400 cleanup_2401", result.diagnostics());
        assertFalse(result.summary().contains("AA:BB"));
        assertFalse(result.summary().contains("private"));
        assertFalse(result.summary().contains("status="));
    }
    @Test public void malformedFirmwareStatusIsNotReportedAsEvidence() {
        for (String value : new String[]{"256", "-2", "999999999999", "1 token=secret"}) {
            ControlFailure result = new ControlFailure(ControlFailure.Stage.OPEN, 2400, "耳机拒绝开启测试上报，status=" + value);
            assertEquals("controls_open_2400", result.diagnostics());
            assertFalse(result.summary().contains("status="));
        }
    }
    @Test public void standaloneCloseFailureIsClearlyIdentified() {
        ControlFailure result = new ControlFailure(ControlFailure.Stage.CLOSE, 2401, "正在反注册监听，请等待结果");
        assertEquals("controls_close_2401", result.diagnostics());
        assertTrue(result.summary().contains("触控关闭未确认"));
        assertFalse(result.summary().contains("清理结果"));
    }
}
