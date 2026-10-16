(function () {
  const out = {};

  // 模拟两轮真实生命周期：开口 -> 转写 -> 判停 -> 出字 -> response.done
  function runTurn(question, answer, toolCall) {
    resetTurnForSpeech();
    const t0 = Date.now();
    // 让时间戳有差异
    const spin = ms => { const s = Date.now(); while (Date.now() - s < ms) {} };
    spin(12);
    setQuestion(question);
    spin(20);
    setThinking();
    spin(35);
    appendAnswer(answer);
    if (toolCall) {
      appendToolResult({ name: toolCall.name, arguments: toolCall.args, result: toolCall.result });
    }
    handleUpstream({ event: { type: "response.done", usage: { total_tokens: 123, input_tokens: 45, output_tokens: 78 } } });
    spin(8);
    return t0;
  }

  runTurn("帮我看看当前目录下有哪些文件？", "当前目录下有 5 个文件：server.py、static/。", {
    name: "list_local_directory",
    args: JSON.stringify({ path: "." }),
    result: { entries: ["server.py", "static/"] },
  });
  runTurn("哪些文件跟音频有关？", "server.py 里有完整的 PCM 收发逻辑。", null);

  out.transcriptLength = transcript.length;
  out.statsLines = [...document.querySelectorAll(".qa-stats")].map(el => el.textContent);
  out.statsLiveCount = document.querySelectorAll(".qa-stats.is-live").length;
  out.recordShape = JSON.stringify(transcript[0], null, 2);
  out.markdown = transcriptToMarkdown();
  out.jsonParsed = JSON.parse(transcriptToJson());
  out.emptyGuard = (function () {
    const backup = transcript;
    transcript = [];
    const hasContent = transcriptHasContent();
    transcript = backup;
    return hasContent;
  })();
  return JSON.stringify(out, null, 2);
})()
