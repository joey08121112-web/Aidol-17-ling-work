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
        for (LocalCue value : LocalCue.values()) {
            String cue = value.name().toLowerCase(java.util.Locale.ROOT);
            byte[] pcm = WavAudio.pcm(Files.readAllBytes(directory.resolve("cue_" + cue + ".wav")));
            assertTrue(cue + " must be audible and brief", pcm.length >= 4800 && pcm.length <= 19200);
            assertEquals(cue + " starts at silence", 0, sample(pcm, 0));
            assertEquals(cue + " ends at silence", 0, sample(pcm, pcm.length - 2));
            int previous = 0;
            for (int i = 0; i < pcm.length; i += 2) {
                int current = sample(pcm, i);
                assertTrue(cue + " avoids loud peaks", Math.abs(current) <= 4300);
                assertTrue(cue + " avoids abrupt sample jumps", Math.abs(current - previous) <= 1000);
                previous = current;
            }
            boolean audible = false;
            for (int i = 0; i < pcm.length; i += 2) {
                int sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
                if (Math.abs(sample) > 128) { audible = true; break; }
            }
            assertTrue(cue + " must contain a nonverbal tone", audible);
        }
    }
    private static int sample(byte[] pcm, int at) { return (short) ((pcm[at] & 255) | (pcm[at + 1] << 8)); }

    @Test public void modesHaveDistinctFixedEarconsAndOtherOperationsShareOneSound() throws Exception {
        Path directory = Paths.get("src/main/res/raw");
        if (!Files.isDirectory(directory)) directory = Paths.get("app/src/main/res/raw");
        byte[] life = Files.readAllBytes(directory.resolve("cue_life.wav"));
        byte[] work = Files.readAllBytes(directory.resolve("cue_work.wav"));
        byte[] commute = Files.readAllBytes(directory.resolve("cue_commute.wav"));
        assertFalse(java.util.Arrays.equals(life, work));
        assertFalse(java.util.Arrays.equals(life, commute));
        assertFalse(java.util.Arrays.equals(work, commute));
        byte[] general = Files.readAllBytes(directory.resolve("cue_ready.wav"));
        for (LocalCue cue : LocalCue.values()) {
            if (cue == LocalCue.LIFE || cue == LocalCue.WORK || cue == LocalCue.COMMUTE) continue;
            assertArrayEquals(cue.name(), general, Files.readAllBytes(directory.resolve("cue_" + cue.name().toLowerCase(java.util.Locale.ROOT) + ".wav")));
        }
    }

}
