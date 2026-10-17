package com.aidol.demo;

/** Frame-based endpoint detector; callback chunk sizes do not change timing.
 * Keeps the entire captured prefix. Requires sustained speech, then 800ms quiet.
 * This is an energy gate, not a neural VAD or a wake-word detector.
 */
final class SpeechEndpoint {
    private final double threshold;
    private final int quietLimit;
    private final int speechLimit;
    private int samples, totalSamples, voicedSamples, quietSamples;
    private double energy;
    private boolean heard, ended;
    SpeechEndpoint(double threshold) { this(threshold, 800, 200); }
    SpeechEndpoint(double threshold, int quietMillis) {
        this(threshold, quietMillis, 120);
    }
    SpeechEndpoint(double threshold, int quietMillis, int speechMillis) {
        this.threshold = Math.max(.005, Math.min(.2, threshold));
        quietLimit = Math.max(400, Math.min(1200, quietMillis)) * 16;
        speechLimit = Math.max(100, Math.min(300, speechMillis)) * 16;
    }
    boolean heardSpeech() { return heard; }
    boolean idle() { return !heard && voicedSamples == 0 && totalSamples >= 16000 * 45; }
    boolean accept(byte[] pcm) {
        if ((pcm.length & 1) != 0) throw new IllegalArgumentException("PCM16 requires whole samples");
        for (int i = 0; i < pcm.length && !ended; i += 2) {
            int value = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
            energy += (double) value * value; samples++; totalSamples++;
            if (samples == 320) { // 20ms at 16kHz
                double level = Math.sqrt(energy / samples) / 32768.0;
                // Hysteresis preserves quieter syllable tails after speech starts.
                if (level >= threshold * (heard ? .6 : 1)) {
                    voicedSamples += samples; quietSamples = 0;
                    if (voicedSamples >= speechLimit) heard = true;
                } else if (heard) quietSamples += samples;
                else voicedSamples = 0;
                ended = heard && totalSamples >= 11200 && quietSamples >= quietLimit;
                energy = 0; samples = 0;
            }
        }
        return ended;
    }
}
