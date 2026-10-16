(function () {
  resetTurnForSpeech();
  setQuestion("帮我看看当前目录下有哪些文件？");
  setThinking();
  appendAnswer("当前目录下有 5 个文件：server.py、static/、README.md、.gitignore、requirements.txt。");
  if (currentTurn.record) currentTurn.record.timing.firstAudioAt = Date.now() + 260;
  handleUpstream({ event: { type: "response.done", usage: { total_tokens: 812, input_tokens: 300, output_tokens: 512 } } });

  resetTurnForSpeech();
  setQuestion("哪些文件跟音频有关？");
  setThinking();
  appendAnswer("server.py 里有完整的 PCM 收发与重采样逻辑，静态目录只提供播放器。");
  if (currentTurn.record) currentTurn.record.timing.firstAudioAt = Date.now() + 310;
  handleUpstream({ event: { type: "response.done", usage: { total_tokens: 640 } } });

  document.querySelector("#callStateBubble").textContent = "正在听...";
  var s = document.querySelector("#heroCallState");
  s.textContent = "正在听...";
  s.classList.add("listening");
  document.querySelector("#callDuration").textContent = "01:24";
  document.querySelector("#recordBtn").disabled = false;
  document.querySelector("#forceCommitBtn").disabled = false;

  // 造一段说话中的波形
  resetWaveform({ idle: false });
  for (let i = 0; i < 56; i += 1) {
    const base = Math.sin((i / 56) * Math.PI * 1.6);
    const wobble = Math.abs(Math.sin(i * 1.7)) * 0.35;
    pushWaveformLevel(Math.min(1, base * 0.55 + wobble));
  }
})()
