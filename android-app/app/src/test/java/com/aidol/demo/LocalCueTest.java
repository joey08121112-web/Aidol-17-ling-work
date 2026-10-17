package com.aidol.demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocalCueTest {
    @Test public void modeAnnouncementsAcceptOnlyTheThreeFixedProductModes() {
        assertSame(LocalCue.LIFE, LocalCue.forMode("life"));
        assertSame(LocalCue.WORK, LocalCue.forMode("work"));
        assertSame(LocalCue.COMMUTE, LocalCue.forMode("commute"));
        for (String invalid : new String[] {null, "", "custom text", "work/private"}) {
            assertThrows(IllegalArgumentException.class, () -> LocalCue.forMode(invalid));
        }
    }

    @Test public void everyBundledCueIsShortNonSilentPcmCompatibleWithReplyPlayer() throws Exception {
        Path directory = Paths.get("src/main/res/raw");
        if (!Files.isDirectory(directory)) directory = Paths.get("app/src/main/res/raw");
        if (!Files.isDirectory(directory)) directory = Paths.get("android-app/app/src/main/res/raw");
        assertTrue("Run the tests from the Android project or module directory", Files.isDirectory(directory));
        for (String cue : new String[] {"life", "work", "commute", "start", "end"}) {
            byte[] pcm = WavAudio.pcm(Files.readAllBytes(directory.resolve("cue_" + cue + ".wav")));
            assertTrue(cue + " must be audible and brief", pcm.length >= 4800 && pcm.length <= 144000);
            boolean audible = false;
            for (int i = 0; i < pcm.length; i += 2) {
                int sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
                if (Math.abs(sample) > 128) { audible = true; break; }
            }
            assertTrue(cue + " must contain speech samples", audible);
        }
    }
}
