(async function () {
  const log = [];
  const $ = s => document.querySelector(s);
  const wait = ms => new Promise(r => setTimeout(r, ms));
  const origFetch = window.fetch;
  const reset = () => {
    cancelReconnect({ silent: true });
    userClosedSession = false;
    errorToastEndCallOnClose = false;
    closeErrorToast();
    $("#connectBtn").disabled = false;
    $("#reconnectBar").hidden = true;
  };
  const ok = b => (b ? "PASS" : "FAIL");

  // ---------- A) 鉴权类错误（4xx）不应触发重连 ----------
  reset();
  $("#apiKey").value = "sk-fake-reconnect-test";
  $("#connectBtn").click();
  await wait(5000);
  const a1 = $("#reconnectBar").hidden === true;
  const a2 = reconnectAttempt === 0;
  const a3 = $("#errorToast").hidden === false;
  log.push("A) 假 key → 上游 401  [不重连 + 弹错误框]");
  log.push("   提示条收起      = " + ok(a1) + "  (hidden=" + $("#reconnectBar").hidden + ")");
  log.push("   重连次数归零    = " + ok(a2) + "  (attempt=" + reconnectAttempt + ")");
  log.push("   错误框弹出      = " + ok(a3) + "  (可见=" + (!$("#errorToast").hidden) + ")");
  log.push("   错误框标题      = " + JSON.stringify($("#errorToastTitle").textContent));
  log.push("   错误框正文      = " + JSON.stringify($("#errorToastMessage").textContent.slice(0, 130)));
  log.push("   事件流日志尾部  = " + JSON.stringify(
    ($("#events").textContent || "").trim().split("\n").slice(-3).join(" | ")));

  // ---------- B) 服务未就绪（5xx）应触发重连 ----------
  reset();
  window.fetch = function (url) {
    if (String(url).includes("/api/session")) {
      return Promise.resolve(new Response(JSON.stringify({ ok: false, error: "后端未就绪（模拟）" }), {
        status: 503, headers: { "Content-Type": "application/json" },
      }));
    }
    return origFetch.apply(this, arguments);
  };
  $("#connectBtn").click();
  await wait(700);
  const b1 = $("#reconnectBar").hidden === false;
  const b2 = reconnectAttempt === 1;
  log.push("B) 模拟 503  [应触发重连]");
  log.push("   提示条显示      = " + ok(b1) + "  (hidden=" + $("#reconnectBar").hidden + ")");
  log.push("   重连次数=1      = " + ok(b2) + "  (attempt=" + reconnectAttempt + ")");
  log.push("   提示条文案      = " + JSON.stringify($("#reconnectText").textContent));
  log.push("   状态栏          = " + JSON.stringify($("#status").textContent));

  window.fetch = origFetch;
  reset();
  return log.join("\n");
})()
