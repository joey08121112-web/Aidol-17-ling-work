package com.aidol.demo;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReplyPlaybackProgressTest {
    private static final class Listener implements ReplyPlayer.PlaybackListener {
        int started, finished;
        final List<Double> levels = new ArrayList<>();
        public void started() { started++; }
        public void level(double rms) { levels.add(rms); }
        public void finished() { finished++; }
    }
    @Test public void preparationAndSilentPrimerCannotSignalSpeech() {
        Listener listener = new Listener();
        ReplyPlayer.PlaybackProgress progress = new ReplyPlayer.PlaybackProgress(1200, listener);
        progress.advanced(1201); // No reply PCM has been written yet.
        progress.written(new byte[]{0, 64, 0, 64}, 0, 4);
        progress.advanced(1199); progress.advanced(1200);
        assertEquals(0, listener.started);
        assertTrue(listener.levels.isEmpty());
        progress.advanced(1201);
        assertEquals(1, listener.started);
        assertEquals(.5, listener.levels.get(0), .0001);
    }
    @Test public void levelsFollowTheHeardChunkInsteadOfTheLatestQueuedChunk() {
        Listener listener = new Listener();
        ReplyPlayer.PlaybackProgress progress = new ReplyPlayer.PlaybackProgress(1200, listener);
        progress.written(new byte[]{0, 64, 0, 64}, 0, 4);
        progress.written(new byte[]{0, 32, 0, 32}, 0, 4);
        progress.advanced(1201); progress.advanced(1203);
        assertEquals(.5, listener.levels.get(0), .0001);
        assertEquals(.25, listener.levels.get(1), .0001);
        assertEquals(1, listener.started);
        assertEquals(0, listener.finished);
        progress.finish(); progress.finish(); progress.advanced(1204);
        assertEquals(1, listener.finished);
        assertEquals(0, listener.levels.get(listener.levels.size() - 1), 0);
    }
    @Test public void cancelledOutputCannotEmitLateStartLevelOrCompletion() {
        Listener listener = new Listener();
        ReplyPlayer.PlaybackProgress progress = new ReplyPlayer.PlaybackProgress(1200, listener);
        progress.written(new byte[]{0, 64}, 0, 2);
        progress.cancel(); progress.advanced(1201); progress.finish();
        assertEquals(0, listener.started + listener.finished);
        assertTrue(listener.levels.isEmpty());
    }
}
