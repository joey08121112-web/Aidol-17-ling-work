/** Run with: node --test tests/audio-worklet.test.mjs (no npm dependencies). */
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const workletSource = readFileSync(new URL('../web/audio/pcm-worklet.js', import.meta.url), 'utf8');

function worklet(sampleRate) {
  let Processor;
  const messages = [];
  class AudioWorkletProcessor {
    constructor() {
      this.port = {postMessage(message, transfers) {
        assert.equal(transfers.length, 1);
        assert.equal(transfers[0], message.pcm, 'PCM is transferred, not copied into JSON');
        messages.push(message);
      }};
    }
  }
  vm.runInNewContext(workletSource, {
    sampleRate, AudioWorkletProcessor, ArrayBuffer, DataView,
    registerProcessor(name, implementation) {
      assert.equal(name, 'pcm16-processor');
      Processor = implementation;
    },
  }, {filename: 'pcm-worklet.js'});
  return {processor: new Processor(), messages};
}

function pcmSamples(messages) {
  return messages.flatMap(({pcm}) => {
    assert.equal(pcm.byteLength, 1280, 'each packet is 640 mono PCM16 samples = 40 ms at 16 kHz');
    const view = new DataView(pcm);
    return Array.from({length: 640}, (_, index) => view.getInt16(index * 2, true));
  });
}

function feed(processor, signal, chunkSizes) {
  let offset = 0, index = 0;
  while (offset < signal.length) {
    const size = chunkSizes[index++ % chunkSizes.length];
    assert.equal(processor.process([[signal.subarray(offset, offset + size)]]), true);
    offset += size;
  }
}

function quantize(value) {
  return Math.round(Math.max(-1, Math.min(1, value)) * (value < 0 ? 32768 : 32767));
}

for (const rate of [48000, 44100]) {
  test(`${rate} Hz preserves 16 kHz duration and phase across render quanta`, () => {
    // A ramp makes even a one-sample phase slip visible without aliasing ambiguity.
    const signal = Float32Array.from({length: rate}, (_, index) => -.7 + index / rate);
    const {processor, messages} = worklet(rate);
    feed(processor, signal, [128]);
    const samples = pcmSamples(messages);
    assert.equal(samples.length, 16000, 'one second must produce exactly 25 complete output packets');
    for (let output = 0; output < samples.length; output++) {
      const position = output * rate / 16000;
      const index = Math.floor(position), fraction = position - index;
      const expected = quantize(signal[index] * (1 - fraction) + signal[index + 1] * fraction);
      assert.ok(Math.abs(samples[output] - expected) <= 1,
        `phase drift at output ${output}: ${samples[output]} vs ${expected}`);
    }
  });

  test(`${rate} Hz output is independent of input chunk boundaries`, () => {
    const signal = Float32Array.from({length: rate * 2}, (_, index) => .5 * Math.sin(2 * Math.PI * 300 * index / rate));
    const fixed = worklet(rate), irregular = worklet(rate);
    feed(fixed.processor, signal, [128]);
    feed(irregular.processor, signal, [1, 2, 7, 127, 128, 129, 63]);
    const a = pcmSamples(fixed.messages), b = pcmSamples(irregular.messages);
    assert.equal(a.length, b.length);
    for (let index = 0; index < a.length; index++) {
      assert.ok(Math.abs(a[index] - b[index]) <= 1, `chunk-dependent sample at ${index}: ${a[index]} vs ${b[index]}`);
    }
  });
}

for (const [amplitude, expected] of [[0, 0], [.5, 16384], [-.5, -16384], [1, 32767], [-1, -32768], [2, 32767], [-2, -32768]]) {
  test(`PCM16 clamps amplitude ${amplitude} to ${expected} in little-endian order`, () => {
    const {processor, messages} = worklet(48000);
    feed(processor, new Float32Array(1920).fill(amplitude), [128]);
    assert.equal(messages.length, 1);
    assert.ok(pcmSamples(messages).every(sample => sample === expected));
    const bytes = new Uint8Array(messages[0].pcm);
    assert.equal(bytes[0], expected & 255);
    assert.equal(bytes[1], (expected >> 8) & 255);
  });
}

test('RMS reports acoustic energy and resets for each output frame', () => {
  const {processor, messages} = worklet(48000);
  const signal = new Float32Array(3840);
  signal.fill(.25, 0, 1920);
  feed(processor, signal, [128]);
  assert.equal(messages.length, 2);
  assert.ok(Math.abs(messages[0].rms - .25) < 1e-7);
  assert.equal(messages[1].rms, 0, 'previous frame energy must not bleed into silence');
});

test('absent microphone frames do not fabricate PCM or terminate worklet', () => {
  const {processor, messages} = worklet(44100);
  for (const input of [[], [[]], [[new Float32Array()]]]) assert.equal(processor.process(input), true);
  assert.equal(messages.length, 0);
});
