(function () {
  var app = document.querySelector(".app");
  app.classList.add("call-mode");
  document.querySelector("#callPage").hidden = false;
  document.querySelector("#callStateBubble").textContent = "连接中断，正在重连...";
  var s = document.querySelector("#heroCallState");
  s.textContent = "连接中断，正在重连...";
  s.classList.add("connecting");
  document.querySelector("#callDuration").textContent = "01:18";
  document.querySelector("#recordBtn").disabled = true;
  document.querySelector("#forceCommitBtn").disabled = true;
  function card(q, a) {
    return "<div class=qa-card><div class=qa-meta>14:22:08 轮次 1  logid=abc123</div>"
      + "<div class=qa-row><span class=qa-label>Q</span><div class=\"qa-text question\">" + q + "</div></div>"
      + "<div class=qa-row><span class=qa-label>A</span><div class=\"qa-text answer\">" + a + "</div></div></div>";
  }
  document.querySelector("#messages").innerHTML = card(
    "帮我看看当前目录下有哪些文件？",
    "当前目录下有 5 个文件：server.py、static/、README.md、.gitignore、requirements.txt。"
  );
  // 直接驱动真实的重连调度，让提示条按真实逻辑渲染
  cancelReconnect({ silent: true });
  scheduleReconnect("上游会话已关闭");
})()
