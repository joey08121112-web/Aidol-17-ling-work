(function () {
  // 截图用：显式指定主题。localStorage 里可能残留着上一次回归脚本点出来的手动选择，
  // 那份选择会盖掉 CDP 的 prefers-color-scheme 模拟，导致"暗色"截出来还是亮色。
  var forced = new URLSearchParams(location.search).get("theme");
  if (forced) {
    try { localStorage.setItem("qwen_omni_demo_theme", forced); } catch (e) {}
    document.documentElement.dataset.theme = forced;
  }

  // 进入通话页并造一段有来有往的对话，把确认卡片放在真实语境里看
  document.querySelector(".app").classList.add("call-mode");
  document.querySelector("#callPage").hidden = false;
  document.querySelector("#callStateBubble").textContent = "正在听...";
  var s = document.querySelector("#heroCallState");
  s.textContent = "正在听...";
  s.classList.add("listening");
  document.querySelector("#callDuration").textContent = "02:07";

  resetTurnForSpeech();
  setQuestion("把今天会议的要点写到桌面上的纪要文件里。");
  setThinking();
  appendAnswer("好的，我整理好了要写入的内容，写入前需要你确认一下。");
  if (currentTurn.record) currentTurn.record.timing.firstAudioAt = Date.now() + 280;
  handleUpstream({event: {type: "response.done", usage: {total_tokens: 720, input_tokens: 240, output_tokens: 480}}});

  // 一张待确认的卡片
  handleSessionMessage({
    type: "local.tool_confirm_request",
    session_id: "demo-session",
    token: "demo-token-1",
    call_id: "demo-call-1",
    name: "mcp__filesystem__write_file",
    arguments: JSON.stringify({
      path: "D:\\workbuddy\\会议纪要-2026-10-16.md",
      content: "# 会议纪要\n\n1. 确认工具层接入 MCP\n2. 危险动作走二次确认\n3. 下周验证真实麦克风链路\n",
      mode: "overwrite"
    }, null, 2),
    expires_in: 60
  });

  resetWaveform({idle: false});
  for (let i = 0; i < 56; i += 1) {
    const base = Math.sin((i / 56) * Math.PI * 1.6);
    const wobble = Math.abs(Math.sin(i * 1.7)) * 0.35;
    pushWaveformLevel(Math.min(1, base * 0.5 + wobble));
  }
})()
