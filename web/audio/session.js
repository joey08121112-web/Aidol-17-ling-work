import {BrowserHeadset} from './hardware.js';

/** Each start owns its resources, including while permission prompts are pending. */
export class AudioSession {
  constructor(onEvent, onLevel) {
    this.onEvent = onEvent; this.onLevel = onLevel; this.hardware = new BrowserHeadset();
    this.sources = new Set(); this.speechContexts = new Set();
    this.running = false; this.generation = 0; this.speechGeneration = 0;
    this.session = null; this.ready = false; this.draining = null;
  }
  isCurrent(session) { return !!session && this.running && this.session === session && !session.closed; }

  async dispose(session, graceful = false) {
    if (!session) return this.draining;
    // A late getUserMedia result must also be stopped after an earlier disposal.
    session.stream?.getTracks().forEach(track => { track.onended = null; track.stop(); });
    session.stream = null;
    if (session.closed) return session.disposal;
    session.closed = true;
    clearTimeout(session.timer);
    if (session.processor) session.processor.port.onmessage = null;
    try { session.input?.disconnect(); } catch {}
    try { session.processor?.disconnect(); } catch {}
    let socketClosed = Promise.resolve();
    if (session.ws) {
      const ws = session.ws;
      ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null;
      if (graceful && ws.readyState === WebSocket.OPEN) {
        // Keep the transport alive for final ASR/SQLite writes, but never play
        // late audio or forward callbacks into a replacement session's UI.
        socketClosed = new Promise(resolve => {
          let finished = false;
          const finish = () => {
            if (finished) return;
            finished = true; clearTimeout(timer);
            ws.onclose = ws.onerror = null;
            try { if (ws.readyState < 2) ws.close(); } catch {}
            resolve();
          };
          // Backend bounds: ASR final 10s + memory drain 10s + queue close 1s.
          const timer = setTimeout(finish, 25000);
          ws.onclose = finish; ws.onerror = finish;
          try { ws.send(JSON.stringify({type:'stop'})); } catch { finish(); }
        });
        this.draining = socketClosed;
        socketClosed.then(() => { if (this.draining === socketClosed) this.draining = null; });
      } else {
        try { if (ws.readyState < 2) ws.close(); } catch {}
      }
    }
    for (const source of session.sources) {
      try { source.stop(); } catch {}
      this.sources.delete(source);
    }
    session.sources.clear();
    session.disposal = (async () => {
      try { if (session.context && session.context.state !== 'closed') await session.context.close(); } catch {}
      await socketClosed;
    })();
    return session.disposal;
  }

  async start({input, output, token, duration = 3600, threshold = .025, vad = true}) {
    if (this.running) return;
    const session = {id: ++this.generation, sources: new Set(), ready: false, nextPlay: 0, vadFrames: 0, above: false};
    this.session = session; this.running = true;
    const stale = async () => {
      if (this.isCurrent(session)) return false;
      await this.dispose(session); return true;
    };
    try {
      // A stopped server session still owns the single-user audio lock until
      // final transcription is saved. Do not open the next mic/socket early.
      if (this.draining) {
        await this.draining;
        if (await stale()) return;
      }
      session.context = new AudioContext();
      await session.context.resume();
      if (await stale()) return;
      await this.hardware.setOutput(session.context, output);
      if (await stale()) return;
      session.stream = await this.hardware.capture(input);
      if (await stale()) return;
      session.stream.getTracks().forEach(track => { track.onended = () => {
        if (this.isCurrent(session)) { this.onEvent({type:'error', message:'耳机或麦克风已断开'}); this.stop(); }
      }; });
      await session.context.audioWorklet.addModule('/audio/pcm-worklet.js');
      if (await stale()) return;
      session.processor = new AudioWorkletNode(session.context, 'pcm16-processor');
      session.input = session.context.createMediaStreamSource(session.stream);
      session.input.connect(session.processor);
      // Silent output keeps the worklet scheduled without microphone feedback.
      session.processor.connect(session.context.destination);
      const ws = session.ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/audio`);
      ws.binaryType = 'arraybuffer';
      ws.onopen = () => {
        if (this.isCurrent(session)) ws.send(JSON.stringify({type:'start', token, duration}));
      };
      ws.onmessage = event => {
        if (!this.isCurrent(session)) return;
        if (event.data instanceof ArrayBuffer) return this.play(event.data);
        let data;
        try { data = JSON.parse(event.data); } catch {
          this.onEvent({type:'error', message:'语音服务返回了无效数据'}); this.stop(); return;
        }
        if (data.type === 'ready') session.ready = this.ready = true;
        if (data.type === 'interrupt') this.interrupt();
        this.onEvent(data);
      };
      ws.onerror = () => {
        if (this.isCurrent(session)) this.onEvent({type:'error', message:'语音 WebSocket 连接失败'});
      };
      ws.onclose = () => { if (this.isCurrent(session)) this.stop(); };
      session.processor.port.onmessage = ({data}) => {
        if (!this.isCurrent(session)) return;
        this.onLevel(data.rms);
        if (vad && data.rms > threshold) session.vadFrames++; else session.vadFrames = 0;
        if (session.vadFrames >= 3 && !session.above) {
          session.above = true; this.interrupt(); this.onEvent({type:'vad.speech'});
        }
        if (!session.vadFrames) session.above = false;
        if (session.ready && ws.readyState === WebSocket.OPEN) {
          if (ws.bufferedAmount > 128000) {
            this.onEvent({type:'error', message:'网络上传积压，已停止采集，避免延迟不断累积'});
            this.stop({graceful:false}); return;
          }
          ws.send(data.pcm);
        }
      };
      session.timer = setTimeout(() => { if (this.isCurrent(session)) this.stop(); }, duration * 1000);
    } catch (error) {
      if (this.isCurrent(session)) { await this.stop(); throw error; }
      await this.dispose(session);
    }
  }

  play(pcm) {
    const session = this.session, context = session?.context;
    if (!this.isCurrent(session) || !context || !pcm.byteLength || pcm.byteLength % 2 || this.muted) return;
    const view = new DataView(pcm), buffer = context.createBuffer(1, pcm.byteLength / 2, 24000);
    const target = buffer.getChannelData(0);
    for (let i = 0; i < target.length; i++) target[i] = view.getInt16(i * 2, true) / 32768;
    if (session.nextPlay - context.currentTime > 5) this.interrupt();
    const source = context.createBufferSource(); source.buffer = buffer; source.connect(context.destination);
    const when = Math.max(context.currentTime + .025, session.nextPlay);
    session.nextPlay = when + buffer.duration;
    this.sources.add(source); session.sources.add(source);
    source.onended = () => { this.sources.delete(source); session.sources.delete(source); };
    source.start(when);
  }

  /** Standalone WAV/TTS playback uses the selected headset without opening its mic. */
  async speakWav(wav, output = '') {
    this.interrupt();
    if (this.muted) return;
    const generation = this.speechGeneration;
    const context = new AudioContext(); this.speechContexts.add(context);
    const close = async () => {
      this.speechContexts.delete(context);
      try { if (context.state !== 'closed') await context.close(); } catch {}
    };
    const stale = () => generation !== this.speechGeneration || this.muted;
    try {
      await context.resume();
      if (stale()) return await close();
      await this.hardware.setOutput(context, output);
      if (stale()) return await close();
      const bytes = typeof wav.arrayBuffer === 'function' ? await wav.arrayBuffer() : wav;
      const buffer = await context.decodeAudioData(bytes.slice(0));
      if (stale()) return await close();
      const source = context.createBufferSource(); source.buffer = buffer; source.connect(context.destination);
      this.sources.add(source);
      source.onended = () => { this.sources.delete(source); close(); };
      source.start();
    } catch (error) {
      await close();
      if (!stale()) throw error;
    }
  }

  interrupt() {
    ++this.speechGeneration;
    for (const source of this.sources) { try { source.stop(); } catch {} }
    this.sources.clear();
    if (this.session) { this.session.sources.clear(); this.session.nextPlay = 0; }
    for (const context of this.speechContexts) {
      try { if (context.state !== 'closed') Promise.resolve(context.close()).catch(() => {}); } catch {}
    }
    this.speechContexts.clear();
  }

  async stop({graceful = true} = {}) {
    const session = this.session;
    const wasActive = this.running || !!session;
    this.running = false; this.ready = false; this.session = null; ++this.generation;
    this.interrupt();
    // Notify before awaiting closure so an old stop cannot reset a newer session's UI.
    if (wasActive) { this.onLevel(0); this.onEvent({type:'closed'}); }
    const disposal = this.dispose(session, graceful);
    // Also cover a replacement start cancelled while the older socket drains.
    const draining = this.draining;
    await Promise.all([disposal, draining]);
  }
}
