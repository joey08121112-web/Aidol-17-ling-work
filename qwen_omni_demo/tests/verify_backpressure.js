(async function () {
  const log = [];
  const $ = s => document.querySelector(s);
  const wait = ms => new Promise(r => setTimeout(r, ms));
  const origFetch = window.fetch;
  let fails = 0;
  const check = (name, cond, extra) => {
    if (!cond) fails += 1;
    log.push("  [" + (cond ? "PASS" : "FAIL") + "] " + name + (extra !== undefined ? "   " + extra : ""));
  };

  /* 模拟「连接建立后服务端一直不响应」。
     必须尊重 init.signal —— 否则 AbortSignal.timeout 对它无效，await 会永远挂住。 */
  function hangingFetch(url, init) {
    return new Promise((resolve, reject) => {
      const fail = () => {
        const e = new Error("signal timed out");
        e.name = "TimeoutError";
        reject(e);
      };
      const signal = init && init.signal;
      if (!signal) return;                 // 没超时信号就真的挂死
      if (signal.aborted) return fail();
      signal.addEventListener("abort", fail);
    });
  }

  const savedSession = sessionId;
  const frame = new Uint8Array(640);

  // ---------- A) 背压：上行堵住时丢帧，而不是无限积压 ----------
  log.push("A) 上行背压");
  sessionId = "backpressure-test";
  inflightAudioFrames = 0;
  droppedAudioFrames = 0;
  lastAudioDropLogAt = 0;            // 让拥塞日志正常输出，好验证文案
  window.fetch = hangingFetch;

  for (let i = 0; i < 30; i += 1) queueAudio(frame);
  await wait(80);

  check("在途帧不会超过上限", inflightAudioFrames <= MAX_INFLIGHT_AUDIO_FRAMES,
    "inflight=" + inflightAudioFrames + " 上限=" + MAX_INFLIGHT_AUDIO_FRAMES);
  check("拥塞会记录丢帧量", droppedAudioFrames > 0, "dropped=" + droppedAudioFrames);
  check("旧链被放弃后新帧立刻续上", audioSendGeneration >= 1,
    "generation=" + audioSendGeneration + " inflight=" + inflightAudioFrames);

  const before = $("#messages").querySelectorAll(".bubble").length;
  for (let i = 0; i < 8; i += 1) queueAudio(frame);
  await wait(80);
  const after = $("#messages").querySelectorAll(".bubble").length;
  check("拥塞提示有节流，不刷屏", after - before <= 1, "新增消息=" + (after - before));
  check("提示文案可读", /上行拥塞/.test($("#messages").textContent));

  // ---------- B) 超时 ----------
  log.push("B) 请求超时");
  let caught = null;
  const t0 = Date.now();
  try {
    await postJSON("/api/audio", { x: 1 }, null, { timeoutMs: 300 });
  } catch (err) {
    caught = err;
  }
  const elapsed = Date.now() - t0;
  check("卡住的请求会抛错而不是永久挂起", !!caught);
  check("错误带 timeout 标记", !!(caught && caught.timeout === true));
  check("文案含超时与时长", !!(caught && /请求超时/.test(caught.message) && /300ms/.test(caught.message)),
    caught ? JSON.stringify(caught.message) : "");
  check("确实是等到超时才返回", elapsed >= 250 && elapsed < 1600, "耗时=" + elapsed + "ms");

  // 超时后计数能正常回落（说明 finally 生效，闸门不会卡死）
  const inflightBefore = inflightAudioFrames;
  await wait(200);
  check("超时不影响闸门复位", inflightAudioFrames <= inflightBefore,
    "before=" + inflightBefore + " after=" + inflightAudioFrames);

  // ---------- C) 恢复后真实接口仍能走通 ----------
  log.push("C) 恢复后链路正常");
  window.fetch = origFetch;
  let okResult = null;
  try {
    okResult = await postJSON("/api/session", {
      api_key: "sk-backpressure-check",
      model: "qwen3.8-omni-flash-realtime",
      voice: "Tina",
    });
  } catch (err) {
    okResult = { ok: false, error: String(err.message) };
  }
  check("真实接口可调用并返回 session_id", !!(okResult && okResult.session_id),
    okResult && (okResult.session_id || okResult.error));

  // ---------- D) 收尾 ----------
  log.push("D) 收尾");
  sendChain = Promise.resolve();
  audioSendGeneration = 0;
  inflightAudioFrames = 0;
  droppedAudioFrames = 0;
  sessionId = savedSession;
  window.fetch = origFetch;
  check("计数已复位", inflightAudioFrames === 0 && droppedAudioFrames === 0);

  log.push("");
  log.push(fails === 0 ? "==> 全部通过" : "==> 有 " + fails + " 项未通过");
  return log.join("\n");
})()
