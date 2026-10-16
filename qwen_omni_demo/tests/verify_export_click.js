(function () {
  const out = {};
  const errors = [];
  window.addEventListener("error", e => errors.push(String(e.message)));

  // 空记录时必须给出提示而不是静默失败
  transcript = [];
  exportTranscript("md");
  out.emptyToastShown = !document.querySelector("#errorToast").hidden;
  out.emptyToastTitle = document.querySelector("#errorToastTitle").textContent;
  closeErrorToast();

  // 造一轮记录再真正触发导出
  resetTurnForSpeech();
  setQuestion("测试导出");
  setThinking();
  appendAnswer("测试回答内容。");
  handleUpstream({ event: { type: "response.done", usage: { total_tokens: 10 } } });

  out.menuOpenBefore = document.querySelector("#exportMenu").open;
  document.querySelector("#exportMenu").open = true;
  document.querySelector("#exportMarkdownBtn").click();
  out.menuOpenAfter = document.querySelector("#exportMenu").open;
  out.lastBubble = [...document.querySelectorAll(".bubble.tool")].slice(-1)[0].textContent.trim();
  out.toastVisible = !document.querySelector("#errorToast").hidden;

  out.errors = errors;
  out.exportJsonLen = transcriptToJson().length;
  return JSON.stringify(out, null, 2);
})()
