(function () {
  const log = [];
  const $ = s => document.querySelector(s);
  function snap(tag) {
    log.push("── " + tag);
    log.push("   bar.hidden = " + String($("#reconnectBar").hidden)
      + "   text = " + JSON.stringify($("#reconnectText").textContent));
    log.push("   status = " + JSON.stringify($("#status").textContent)
      + "   callPage = " + ($("#callPage").hidden ? "hidden" : "visible"));
    log.push("   attempt = " + reconnectAttempt
      + "   closeBtn.disabled = " + $("#closeBtn").disabled
      + "   recordBtn.disabled = " + $("#recordBtn").disabled);
  }

  if (typeof scheduleReconnect !== "function") {
    return "FATAL: scheduleReconnect 未定义（脚本可能未加载）";
  }

  cancelReconnect({ silent: true });
  log.push("初始 attempt = " + reconnectAttempt + "  bar.hidden = " + $("#reconnectBar").hidden);

  // 1) 首次断线
  const r1 = scheduleReconnect("测试断线A");
  snap("1) 首次断线 返回=" + r1);

  // 2) 定时器已存在时的重复调用应被挡住（返回 true 且 attempt 不涨）
  const r2 = scheduleReconnect("重复调用");
  snap("2) 重复调用 返回=" + r2);

  // 3) 逐步推进退避次数（每次都清 timer，但保留 attempt）
  clearReconnectTimers(); const r3 = scheduleReconnect("断线B");
  clearReconnectTimers(); const r4 = scheduleReconnect("断线C");
  clearReconnectTimers(); const r5 = scheduleReconnect("断线D");
  snap("3) 推进到第4次 返回=" + [r3, r4, r5].join(","));

  // 4) 超过上限应放弃
  clearReconnectTimers();
  const r6 = scheduleReconnect("断线E");
  snap("4) 第5次(超限) 返回=" + r6);

  // 5) 用户点「取消」应停止重连并收起提示条
  clearReconnectTimers();
  scheduleReconnect("断线F");
  const beforeCancel = $("#reconnectBar").hidden === false;
  $("#reconnectCancelBtn").click();
  log.push("── 5) 点取消（取消前 bar 可见=" + beforeCancel + "）");
  log.push("   bar.hidden = " + String($("#reconnectBar").hidden)
    + "   attempt = " + reconnectAttempt
    + "   userClosedSession = " + userClosedSession);

  // 6) 用户已主动关闭后，不应再自动重连
  const r7 = scheduleReconnect("取消后再断线");
  log.push("── 6) 已主动关闭后再断线 返回=" + r7 + "（应为 false）");

  return log.join("\n");
})()
