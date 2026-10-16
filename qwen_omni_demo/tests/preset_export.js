(function () {
  // 进入通话页 + 造两轮记录，最后把导出下拉打开，用于截图检查是否被面板裁掉
  resetTurnForSpeech();
  setQuestion("帮我看看当前目录下有哪些文件？");
  setThinking();
  appendAnswer("当前目录下有 5 个文件：server.py、static/、README.md、.gitignore、requirements.txt。");
  if (currentTurn.record) currentTurn.record.timing.firstAudioAt = Date.now() + 120;
  handleUpstream({ event: { type: "response.done", usage: { total_tokens: 812, input_tokens: 300, output_tokens: 512 } } });

  resetTurnForSpeech();
  setQuestion("哪些文件跟音频有关？");
  setThinking();
  appendAnswer("server.py 里有完整的 PCM 收发与重采样逻辑，静态目录只提供播放器。");
  if (currentTurn.record) currentTurn.record.timing.firstAudioAt = Date.now() + 180;
  handleUpstream({ event: { type: "response.done", usage: { total_tokens: 640, input_tokens: 250, output_tokens: 390 } } });

  document.querySelector("#callStateBubble").textContent = "正在听...";
  var s = document.querySelector("#heroCallState");
  s.textContent = "正在听...";
  s.classList.add("listening");
  document.querySelector("#callDuration").textContent = "01:24";
  document.querySelector("#meterFill").style.width = "46%";
  document.querySelector("#recordBtn").disabled = false;
  document.querySelector("#forceCommitBtn").disabled = false;
  document.querySelector("#exportMenu").open = true;
})()
