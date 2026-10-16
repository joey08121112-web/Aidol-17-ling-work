/* Continuous resampling keeps phase across render quanta, avoiding drift at 44.1 kHz. */
class PCM16Processor extends AudioWorkletProcessor {
  constructor() {
    super(); this.samples = []; this.position = 0; this.frame = []; this.power = 0;
  }
  process(inputs) {
    const channel = inputs[0]?.[0];
    if (!channel) return true;
    this.samples.push(...channel);
    const step = sampleRate / 16000;
    while (this.position + 1 < this.samples.length) {
      const index = Math.floor(this.position), mix = this.position - index;
      const value = this.samples[index] * (1 - mix) + this.samples[index + 1] * mix;
      this.frame.push(Math.round(Math.max(-1, Math.min(1, value)) * (value < 0 ? 32768 : 32767)));
      this.power += value * value;
      this.position += step;
      if (this.frame.length === 640) {
        const buffer = new ArrayBuffer(1280), view = new DataView(buffer);
        this.frame.forEach((sample, i) => view.setInt16(i * 2, sample, true));
        this.port.postMessage({pcm: buffer, rms: Math.sqrt(this.power / 640)}, [buffer]);
        this.frame = []; this.power = 0;
      }
    }
    // Keep any skip beyond this quantum for the next input chunk.
    const consumed = Math.min(Math.floor(this.position), this.samples.length);
    this.samples.splice(0, consumed); this.position -= consumed;
    return true;
  }
}
registerProcessor('pcm16-processor', PCM16Processor);
