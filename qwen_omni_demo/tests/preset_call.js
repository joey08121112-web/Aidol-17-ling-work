(function () {
  var app = document.querySelector(".app");
  app.classList.add("call-mode");
  document.querySelector("#callPage").hidden = false;
  document.querySelector("#callStateBubble").textContent = "正在听...";
  var s = document.querySelector("#heroCallState");
  s.textContent = "正在听...";
  s.classList.add("listening");
  document.querySelector("#callDuration").textContent = "00:42";
  document.querySelector("#meterFill").style.width = "38%";
  document.querySelector("#recordBtn").disabled = false;
  document.querySelector("#forceCommitBtn").disabled = false;
  function card(q, a) {
    return '<div class="qa-card"><div class="qa-meta">14:22:08  轮次 1  logid=abc123</div>' +
      '<div class="qa-row"><span class="qa-label">Q</span><div class="qa-text question">' + q + '</div></div>' +
      '<div class="qa-row"><span class="qa-label">A</span><div class="qa-text answer">' + a + '</div></div></div>';
  }
  document.querySelector("#messages").innerHTML =
    card("帮我看看当前目录下有哪些文件？", "当前目录下有 5 个文件：server.py、static/、README.md、.gitignore、requirements.txt。") +
    card("哪些文件跟音频有关？", '<mark class="qa-search-mark">server.py</mark> 里有完整的 PCM 收发与重采样逻辑，静态目录只提供播放器。');
})()
