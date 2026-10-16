(function () {
  // 用 preset_wave 的建场逻辑先进入通话页
  resetTurnForSpeech();
  setQuestion("帮我看看当前目录下有哪些文件？");
  setThinking();
  appendAnswer("当前目录下有 5 个文件：server.py、static/、README.md、.gitignore、requirements.txt。");
  handleUpstream({ event: { type: "response.done", usage: { total_tokens: 812 } } });
  resetTurnForSpeech();
  setQuestion("哪些文件跟音频有关？");
  setThinking();
  appendAnswer("server.py 里有完整的 PCM 收发逻辑。");
  handleUpstream({ event: { type: "response.done", usage: { total_tokens: 640 } } });

  const rect = sel => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { top: Math.round(r.top), bottom: Math.round(r.bottom), h: Math.round(r.height), w: Math.round(r.width) };
  };
  const shell = document.querySelector(".call-shell");
  const cs = getComputedStyle(shell);
  const m = document.querySelector(".call-meter");
  const mr = m.getBoundingClientRect();
  const shellR = shell.getBoundingClientRect();
  return JSON.stringify({
    viewportH: window.innerHeight,
    app: rect(".app"),
    shell: rect(".call-shell"),
    shellScroll: { scrollH: shell.scrollHeight, clientH: shell.clientHeight, overflow: cs.overflow },
    profile: rect(".call-profile"),
    qaPanel: rect(".call-qa-panel"),
    messages: rect(".call-qa-panel .messages"),
    controls: rect(".call-controls"),
    tip: rect(".force-commit-tip"),
    meter: rect(".call-meter"),
    meterBottomVsShellBottom: Math.round(shellR.bottom - mr.bottom),
    gridRows: cs.gridTemplateRows,
  }, null, 2);
})()
