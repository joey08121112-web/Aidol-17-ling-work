(async function () {
  const log = [];
  const $ = s => document.querySelector(s);
  const wait = ms => new Promise(r => setTimeout(r, ms));
  let fails = 0;
  const check = (name, cond, extra) => {
    if (!cond) fails += 1;
    log.push("  [" + (cond ? "PASS" : "FAIL") + "] " + name + (extra !== undefined ? "   " + extra : ""));
  };

  window.__errs = [];
  window.addEventListener("error", e => window.__errs.push(String(e.message)));
  window.addEventListener("unhandledrejection", e => window.__errs.push("reject: " + String(e.reason)));

  log.push("A) 关键元素齐全性");
  const elIds = [
    "apiKey", "voice", "instructions", "toolsJson", "enableTools",
    "connectBtn", "closeBtn", "recordBtn", "forceCommitBtn", "heroConnectBtn",
    "status", "messages", "qaSearchInput", "qaSearchStatus", "clearMessagesBtn",
    "exportMenu", "exportMarkdownBtn", "exportJsonBtn", "themeToggle",
    "waveform", "reconnectBar", "reconnectText", "reconnectNowBtn", "reconnectCancelBtn",
    "callPage", "callStateBubble", "callDuration", "events", "errorToast",
  ];
  const missing = elIds.filter(id => !document.getElementById(id));
  check(elIds.length + " 个关键 id 全部存在", missing.length === 0, missing.length ? "缺: " + missing.join(",") : "");

  log.push("B) 主题切换");
  const before = document.documentElement.dataset.theme;
  $("#themeToggle").click();
  await wait(160);
  const after = document.documentElement.dataset.theme;
  check("点击可切换主题", before !== after, before + " -> " + after);
  $("#themeToggle").click();
  await wait(160);
  check("再点可切回", document.documentElement.dataset.theme === before, "-> " + document.documentElement.dataset.theme);

  log.push("C) 音量波形");
  const bars = document.querySelectorAll("#waveform i");
  check("波形柱已渲染", bars.length === 56, bars.length + " 根");
  if (bars.length) {
    for (let i = 0; i < 40; i += 1) pushWaveformLevel(0.9);
    await wait(80);
    const peak = Math.max(...[...bars].map(b => {
      const m = /scaleY\(([\d.]+)\)/.exec(b.style.transform || "");
      return m ? parseFloat(m[1]) : 0;
    }));
    check("注入能量后柱子抬起", peak > 0.5, "最高 scaleY=" + peak.toFixed(2));
    resetWaveform();
    await wait(80);
    const low = [...bars].every(b => {
      const m = /scaleY\(([\d.]+)\)/.exec(b.style.transform || "");
      return m ? parseFloat(m[1]) <= 0.07 : false;
    });
    check("复位后全部回落到底", low);
    check("静音态带 is-idle", $("#waveform").classList.contains("is-idle"));
  }

  log.push("D) 通话记录与导出");
  check("transcript 是数组", Array.isArray(transcript));
  // 导出菜单挂在通话页里，先切到通话页再验，否则父级 hidden 会让一切测不准
  $(".app").classList.add("call-mode");
  $("#callPage").hidden = false;
  await wait(100);
  check("导出菜单初始收起", $("#exportMenu").open === false);
  $("#exportMenu").open = true;
  await wait(100);
  const listBox = $("#exportMenu .export-menu-list").getBoundingClientRect();
  check("导出菜单可展开", $("#exportMenu").open === true && listBox.height > 0, "菜单高度=" + Math.round(listBox.height) + "px");
  const toastBefore = $("#errorToast").hidden;
  try {
    exportTranscript("md");
    check("空记录导出不抛异常", true);
  } catch (err) {
    check("空记录导出不抛异常", false, String(err && err.message));
  }
  check("空记录导出给出提示", $("#errorToast").hidden === false || toastBefore === false);
  check("导出后菜单自动收起", $("#exportMenu").open === false);
  $("#errorToastCloseBtn").click();

  log.push("E) 断线重连");
  cancelReconnect({ silent: true });
  scheduleReconnect("综合自检");
  await wait(140);
  check("提示条出现", $("#reconnectBar").hidden === false, JSON.stringify($("#reconnectText").textContent));
  check("卡片带 reconnecting 类", $(".app").classList.contains("reconnecting"));
  check("状态栏显示重连中", $("#status").textContent === "重连中");
  cancelReconnect({ silent: true });
  await wait(140);
  check("取消后提示条收起", $("#reconnectBar").hidden === true);
  check("取消后类名移除", !$(".app").classList.contains("reconnecting"));

  log.push("F) 运行期报错");
  const errs = window.__errs.filter(Boolean);
  check("自检期间无 JS 报错", errs.length === 0, errs.join(" | "));

  log.push("");
  log.push(fails === 0 ? "==> 全部通过" : "==> 有 " + fails + " 项未通过");
  return log.join("\n");
})()
