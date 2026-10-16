(async function () {
  const log = [];
  const $ = s => document.querySelector(s);
  const wait = ms => new Promise(r => setTimeout(r, ms));
  let fails = 0;
  const check = (name, cond, extra) => {
    if (!cond) fails += 1;
    log.push("  [" + (cond ? "PASS" : "FAIL") + "] " + name + (extra !== undefined ? "   " + extra : ""));
  };
  const resetLocal = () => {
    sendChain = Promise.resolve();
    audioSendGeneration = 0;
    inflightAudioFrames = 0;
    droppedAudioFrames = 0;
    lastAudioDropLogAt = 0;
    lastAudioErrorLogAt = Date.now();
  };

  /* 模拟「连接建立后服务端一直不响应」。
     必须尊重 init.signal，否则 AbortSignal.timeout 对它无效。 */
  function hangingFetch(url, init) {
    return new Promise((resolve, reject) => {
      const fail = () => {
        const e = new Error("signal timed out");
        e.name = "TimeoutError";
        reject(e);
      };
      const signal = init && init.signal;
      if (!signal) return;
      if (signal.aborted) return fail();
      signal.addEventListener("abort", fail);
    });
  }

  const savedSession = sessionId;
  const frame = new Uint8Array(640);
  sessionId = "gate-release-test";

  // ---------- A) 拥塞时放弃旧链，新帧立刻继续发 ----------
  log.push("A) 拥塞处理：放弃旧链");
  resetLocal();
  window.fetch = hangingFetch;
  for (let i = 0; i < 30; i += 1) queueAudio(frame);
  await wait(50);

  check("旧链的 25 帧被记为丢弃", droppedAudioFrames === MAX_INFLIGHT_AUDIO_FRAMES,
    "dropped=" + droppedAudioFrames);
  check("重置后新帧立刻继续发（不再卡在 25）",
    inflightAudioFrames === 30 - MAX_INFLIGHT_AUDIO_FRAMES,
    "inflight=" + inflightAudioFrames + "（期望 " + (30 - MAX_INFLIGHT_AUDIO_FRAMES) + "）");
  check("代际号已推进", audioSendGeneration === 1, "generation=" + audioSendGeneration);
  check("拥塞提示已写入消息区", /上行拥塞/.test($("#messages").textContent));

  // ---------- B) 闸门自愈：在途帧超时后必须自己退回去 ----------
  // 这是整套背压的关键：闸门退不回来，麦克风就永久哑了。
  // 发送链是串行的，N 帧要逐个超时，所以等待时长按 N × 单帧超时算。
  const probe = 3;
  log.push("B) 自愈：放 " + probe + " 帧让它逐个超时（" + probe + " × " + AUDIO_POST_TIMEOUT_MS + "ms）");
  resetLocal();
  window.fetch = hangingFetch;
  for (let i = 0; i < probe; i += 1) queueAudio(frame);
  await wait(40);
  check("探测帧都在途", inflightAudioFrames === probe, "inflight=" + inflightAudioFrames);

  await wait(probe * AUDIO_POST_TIMEOUT_MS + 900);
  check("闸门自动复位到 0（不会永久卡死）", inflightAudioFrames === 0,
    "inflight=" + inflightAudioFrames);

  // ---------- C) 复位后上行必须能重新工作 ----------
  log.push("C) 复位后上行恢复");
  let sent = 0;
  window.fetch = function (url) {
    if (String(url).includes("/api/audio")) {
      sent += 1;
      return Promise.resolve(new Response(JSON.stringify({ ok: true }), {
        status: 200, headers: { "Content-Type": "application/json" },
      }));
    }
    return Promise.resolve(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
  };
  resetLocal();
  for (let i = 0; i < 10; i += 1) queueAudio(frame);
  await wait(400);
  check("10 帧全部发出", sent === 10, "实际发出 " + sent + " 帧");
  check("正常状态下不丢帧", droppedAudioFrames === 0, "dropped=" + droppedAudioFrames);
  check("闸门回落到 0", inflightAudioFrames === 0, "inflight=" + inflightAudioFrames);

  // ---------- D) 持续拥塞不会雪崩 ----------
  log.push("D) 持续拥塞下的稳定性（连塞 100 帧）");
  resetLocal();
  window.fetch = hangingFetch;
  for (let i = 0; i < 100; i += 1) queueAudio(frame);
  await wait(80);
  check("在途数始终受控", inflightAudioFrames <= MAX_INFLIGHT_AUDIO_FRAMES,
    "inflight=" + inflightAudioFrames);
  check("每满 25 帧重置一次（3~4 次）",
    audioSendGeneration >= 3 && audioSendGeneration <= 4,
    "generation=" + audioSendGeneration);
  check("丢弃量被累计记录", droppedAudioFrames === 75, "dropped=" + droppedAudioFrames);

  // 收尾
  resetLocal();
  sessionId = savedSession;

  log.push("");
  log.push(fails === 0 ? "==> 全部通过" : "==> 有 " + fails + " 项未通过");
  return log.join("\n");
})()
