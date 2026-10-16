/** Browser resource lifecycle tests using Node's built-in VM and fake Web APIs. */
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../web/audio/session.js', import.meta.url), 'utf8')
  .replace(/^import .*;\s*/m, '').replace('export class AudioSession', 'globalThis.AudioSession = class AudioSession');
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
};
const stream = () => {
  const track = {stopped: false, stop() { this.stopped = true; }};
  return {track, getTracks: () => [track]};
};
async function until(condition) {
  for (let index = 0; index < 50 && !condition(); index++) await Promise.resolve();
  assert.ok(condition(), 'expected asynchronous checkpoint was reached');
}

function harness(plans = []) {
  const contexts = [], sockets = [], events = [], levels = [], timers = new Map();
  let timerId = 0;
  class AudioContext {
    constructor() {
      this.plan = plans[contexts.length] || {}; contexts.push(this);
      this.state = 'running'; this.destination = {}; this.currentTime = 0; this.sources = [];
      this.audioWorklet = {addModule: () => this.plan.module?.promise || Promise.resolve()};
    }
    resume() { return this.plan.resume?.promise || Promise.resolve(); }
    async close() { this.state = 'closed'; if (this.plan.close) await this.plan.close.promise; }
    createMediaStreamSource() { return {connect() {}, disconnect() {}}; }
    createBuffer(channels, length, rate) { return {duration: length / rate, getChannelData: () => new Float32Array(length)}; }
    decodeAudioData() { return this.plan.decode?.promise || Promise.resolve({duration: .2}); }
    createBufferSource() {
      const item = {connect() {}, start() { this.started = true; }, stop() { this.stopped = true; this.onended?.(); }};
      this.sources.push(item); return item;
    }
  }
  class WebSocket {
    static OPEN = 1;
    constructor() { this.readyState = 0; this.bufferedAmount = 0; this.sent = []; sockets.push(this); }
    send(data) { this.sent.push(data); }
    close() { this.readyState = 3; }
  }
  class BrowserHeadset {
    async setOutput(context, output) { context.output = output; }
    async capture() { return stream(); }
  }
  const sandbox = {AudioContext, WebSocket, BrowserHeadset, ArrayBuffer, DataView,
    AudioWorkletNode: class { constructor() { this.port = {}; } connect() {} disconnect() {} },
    location: {protocol: 'http:', host: 'localhost:8000'},
    setTimeout(fn, delay) { fn.delay = delay; timers.set(++timerId, fn); return timerId; },
    clearTimeout(id) { timers.delete(id); },
  };
  vm.runInNewContext(source, sandbox, {filename: 'session.js'});
  const audio = new sandbox.AudioSession(event => events.push(event), level => levels.push(level));
  return {audio, contexts, sockets, events, levels, timers};
}

test('late permission result stops only its own microphone after a new session starts', async () => {
  const h = harness(), first = deferred(), oldStream = stream(), newStream = stream();
  let captures = 0;
  h.audio.hardware.capture = () => ++captures === 1 ? first.promise : Promise.resolve(newStream);
  const oldStart = h.audio.start({token: 'old'});
  await until(() => captures === 1);
  await h.audio.stop();
  await h.audio.start({token: 'new'});
  first.resolve(oldStream); await oldStart;
  assert.ok(oldStream.track.stopped);
  assert.equal(newStream.track.stopped, false);
  assert.equal(h.contexts[0].state, 'closed');
  assert.equal(h.contexts[1].state, 'running');
  assert.equal(h.audio.running, true);
  assert.equal(h.sockets.length, 1);
  h.sockets[0].onopen();
  assert.equal(JSON.parse(h.sockets[0].sent[0]).token, 'new');
  await h.audio.stop();
});

test('stop during AudioContext.resume never later requests microphone permission', async () => {
  const resume = deferred(), h = harness([{resume}]);
  let captures = 0;
  h.audio.hardware.capture = () => { captures++; return Promise.resolve(stream()); };
  const start = h.audio.start({});
  await h.audio.stop(); resume.resolve(); await start;
  assert.equal(captures, 0);
  assert.equal(h.sockets.length, 0);
  assert.equal(h.contexts[0].state, 'closed');
});

test('old worklet load rejection cannot stop a newer session', async () => {
  const module = deferred(), h = harness([{module}]);
  const oldStart = h.audio.start({});
  await until(() => !!h.audio.session.stream);
  await h.audio.stop();
  await h.audio.start({});
  module.reject(new Error('old context was closed')); await oldStart;
  assert.equal(h.audio.running, true);
  assert.equal(h.contexts[1].state, 'running');
  assert.equal(h.events.filter(event => event.type === 'closed').length, 1);
  await h.audio.stop();
});

test('slow old context closure cannot emit closed after a new session starts', async () => {
  const close = deferred(), h = harness([{close}]);
  await h.audio.start({});
  const stopping = h.audio.stop();
  assert.equal(h.events.filter(event => event.type === 'closed').length, 1);
  await h.audio.start({});
  close.resolve(); await stopping;
  assert.equal(h.events.filter(event => event.type === 'closed').length, 1);
  assert.ok(h.audio.running);
  await h.audio.stop();
});

test('callbacks from a stale WebSocket cannot affect its replacement', async () => {
  const h = harness();
  await h.audio.start({});
  const old = h.sockets[0];
  const callbacks = {open: old.onopen, close: old.onclose, message: old.onmessage, error: old.onerror};
  await h.audio.stop(); await h.audio.start({});
  callbacks.open(); callbacks.close(); callbacks.error(); callbacks.message({data: '{"type":"ready"}'});
  assert.equal(h.audio.running, true);
  assert.equal(h.audio.ready, false);
  assert.equal(old.sent.length, 0);
  assert.equal(h.sockets[1].sent.length, 0);
  assert.equal(h.events.filter(event => event.type === 'error').length, 0);
  await h.audio.stop();
});

test('WAV playback selects headset and interruption leaves recording resources alive', async () => {
  const h = harness();
  await h.audio.start({});
  const mic = h.audio.session.stream;
  await h.audio.speakWav(new ArrayBuffer(44), 'viaim-output');
  assert.equal(h.contexts[1].output, 'viaim-output');
  assert.equal(h.contexts[1].sources[0].started, true);
  h.audio.interrupt();
  assert.equal(h.contexts[1].state, 'closed');
  assert.equal(h.contexts[1].sources[0].stopped, true);
  assert.equal(h.contexts[0].state, 'running');
  assert.equal(mic.track.stopped, false);
  await h.audio.stop();
});

test('stop during WAV decoding prevents late playback and releases its context', async () => {
  const decode = deferred(), h = harness([{decode}]);
  const speaking = h.audio.speakWav(new ArrayBuffer(44));
  await until(() => h.contexts[0]?.output === '');
  await h.audio.stop(); decode.resolve({duration: .2}); await speaking;
  assert.equal(h.contexts[0].state, 'closed');
  assert.equal(h.contexts[0].sources.length, 0);
  assert.equal(h.audio.speechContexts.size, 0);
});

test('work mode prevents standalone TTS and malformed PCM playback', async () => {
  const h = harness();
  h.audio.muted = true;
  await h.audio.speakWav(new ArrayBuffer(44));
  assert.equal(h.contexts.length, 0);
  await h.audio.start({});
  h.audio.play(new ArrayBuffer(0)); h.audio.play(new ArrayBuffer(1)); h.audio.play(new ArrayBuffer(2));
  assert.equal(h.contexts[0].sources.length, 0);
  await h.audio.stop();
});

test('upload backpressure stops capture and clears session timer', async () => {
  const h = harness();
  await h.audio.start({});
  const session = h.audio.session, mic = session.stream, socket = h.sockets[0];
  socket.readyState = 1; socket.onmessage({data: '{"type":"ready"}'}); socket.bufferedAmount = 128001;
  session.processor.port.onmessage({data: {pcm: new ArrayBuffer(1280), rms: 0}});
  assert.equal(h.audio.running, false);
  assert.ok(mic.track.stopped);
  assert.equal(h.timers.size, 0);
  assert.ok(h.events.some(event => event.type === 'error'));
});

test('stop releases mic and playback immediately but waits for server final transcription', async () => {
  const h = harness();
  await h.audio.start({});
  const session = h.audio.session, mic = session.stream, socket = h.sockets[0];
  socket.readyState = 1; socket.onopen();
  socket.onmessage({data: '{"type":"ready"}'});
  h.audio.play(new ArrayBuffer(1280));
  const source = h.contexts[0].sources[0];
  let settled = false;
  const stopping = h.audio.stop().then(() => { settled = true; });
  await Promise.resolve();
  assert.ok(mic.track.stopped);
  assert.ok(source.stopped);
  assert.equal(h.contexts[0].state, 'closed');
  assert.equal(h.audio.running, false);
  assert.equal(socket.readyState, 1, 'transport must survive to receive the ASR final');
  assert.equal(JSON.parse(socket.sent.at(-1)).type, 'stop');
  assert.equal(socket.onmessage, null, 'drain audio cannot restart playback');
  assert.equal(settled, false);
  assert.equal(h.events.filter(event => event.type === 'closed').length, 1);
  socket.readyState = 3; socket.onclose();
  await stopping;
  assert.equal(settled, true);
  assert.equal(h.timers.size, 0);
});

test('quick restart waits for old server lock and ignores old socket callbacks', async () => {
  const h = harness();
  await h.audio.start({});
  const old = h.sockets[0]; old.readyState = 1; old.onopen();
  const oldMessage = old.onmessage;
  const stopping = h.audio.stop();
  const drainClose = old.onclose;
  const restarting = h.audio.start({token: 'replacement'});
  await Promise.resolve();
  assert.equal(h.contexts.length, 1);
  assert.equal(h.sockets.length, 1, 'no second WS until old server has drained');
  old.readyState = 3; drainClose();
  await stopping; await restarting;
  assert.equal(h.sockets.length, 2);
  assert.equal(h.contexts[1].state, 'running');
  oldMessage({data: '{"type":"ready"}'}); drainClose();
  assert.equal(h.audio.ready, false);
  assert.equal(h.audio.running, true);
  assert.equal(h.events.filter(event => event.type === 'closed').length, 1);
  await h.audio.stop();
});

test('drain timeout is bounded and repeated stop still waits for the same server', async () => {
  const h = harness();
  await h.audio.start({});
  const socket = h.sockets[0]; socket.readyState = 1; socket.onopen();
  const stopping = h.audio.stop();
  let secondSettled = false;
  const again = h.audio.stop().then(() => { secondSettled = true; });
  await Promise.resolve();
  assert.equal(secondSettled, false);
  assert.equal(h.timers.size, 1);
  const timeout = [...h.timers.values()][0];
  assert.equal(timeout.delay, 25000);
  timeout(); await stopping; await again;
  assert.equal(socket.readyState, 3);
  assert.equal(h.timers.size, 0);
  assert.equal(h.events.filter(event => event.type === 'closed').length, 1);
});

test('cancelled restart cannot open a mic after old transcription finishes', async () => {
  const h = harness();
  await h.audio.start({});
  const socket = h.sockets[0]; socket.readyState = 1; socket.onopen();
  const stopping = h.audio.stop();
  const restarting = h.audio.start({});
  let cancelled = false;
  const cancelling = h.audio.stop().then(() => { cancelled = true; });
  await Promise.resolve();
  assert.equal(cancelled, false, 'data-clear callers must wait for the older drain too');
  socket.readyState = 3; socket.onclose();
  await stopping; await restarting; await cancelling;
  assert.equal(h.contexts.length, 1);
  assert.equal(h.sockets.length, 1);
  assert.equal(h.audio.running, false);
});

test('failed stop control frame closes transport without leaving a drain timer', async () => {
  const h = harness();
  await h.audio.start({});
  const socket = h.sockets[0]; socket.readyState = 1;
  socket.send = () => { throw new Error('transport unavailable'); };
  await h.audio.stop();
  assert.equal(socket.readyState, 3);
  assert.equal(h.timers.size, 0);
  assert.equal(h.audio.draining, null);
});
