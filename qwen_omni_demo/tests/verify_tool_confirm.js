(async function () {
  const log = [];
  let fails = 0;
  const check = (name, cond, extra) => {
    if (!cond) fails += 1;
    log.push("  [" + (cond ? "PASS" : "FAIL") + "] " + name + (extra !== undefined ? "   " + extra : ""));
  };
  const wait = ms => new Promise(r => setTimeout(r, ms));
  const $ = s => document.querySelector(s);

  window.__errs = [];
  window.addEventListener("error", e => window.__errs.push(String(e.message)));
  window.addEventListener("unhandledrejection", e => window.__errs.push("reject: " + String(e.reason)));

  $("#messages").innerHTML = "";

  const request = (token, name, args) => ({
    type: "local.tool_confirm_request",
    session_id: "sess-test",
    token,
    call_id: "call-" + token,
    name,
    arguments: JSON.stringify(args),
    expires_in: 60,
  });

  log.push("A) 确认卡片渲染");
  handleSessionMessage(request("tok-1", "mcp__fake__write_note", {path: "a.txt", content: "hi"}));
  await wait(80);

  const card = $(".tool-confirm");
  check("卡片已渲染", !!card);
  if (!card) { return log.join("\n"); }

  const title = card.querySelector(".tool-confirm-title");
  check("标题带上工具名", /write_note/.test(title.textContent), title.textContent);
  check("标题标出来源服务", /fake/.test(title.textContent), title.textContent);
  check("标题以「需要你确认」开头", /^需要你确认/.test(title.textContent), title.textContent);

  const hint = card.querySelector(".tool-confirm-hint");
  check("提示里说明会改外部内容", /修改外部内容/.test(hint.textContent), hint.textContent);
  check("提示里给出超时秒数", /60/.test(hint.textContent), hint.textContent);

  const pre = card.querySelector(".tool-confirm-args");
  check("参数以 JSON 展示", /"path"/.test(pre.textContent) && /"content"/.test(pre.textContent),
        pre.textContent.slice(0, 80));

  const btns = card.querySelectorAll(".tool-confirm-btn");
  check("有确认与拒绝两个按钮", btns.length === 2, btns.length + " 个");
  check("按钮文案正确", btns[0] && btns[0].textContent === "确认执行" && btns[1] && btns[1].textContent === "拒绝",
        btns[0] ? btns[0].textContent + " / " + btns[1].textContent : "");
  check("确认按钮带 primary 强调类", btns[0].classList.contains("primary"));

  log.push("B) 超时路径（后端推 local.tool_confirm_expired）");
  handleSessionMessage({type: "local.tool_confirm_expired", token: "tok-1", name: "mcp__fake__write_note"});
  await wait(80);
  check("卡片标记为已完成", card.classList.contains("is-done"));
  check("卡片带 expired 态", card.classList.contains("is-expired"));
  check("状态文案提示超时", /超时/.test(card.querySelector(".tool-confirm-status").textContent),
        card.querySelector(".tool-confirm-status").textContent);
  check("两个按钮都禁用", btns[0].disabled && btns[1].disabled);
  check("卡片已从登记表移除", !toolConfirmCards.has("tok-1"), toolConfirmCards.size);
  check("待确认表登记了个数正确", toolConfirmCards.size === 0, toolConfirmCards.size);

  log.push("C) 确认路径（后端推 local.tool_confirm_resolved）");
  $("#messages").innerHTML = "";
  handleSessionMessage(request("tok-2", "mcp__fake__write_note", {path: "b.txt", content: "yo"}));
  await wait(80);
  const card2 = $(".tool-confirm");
  check("第二张卡片渲染出来", !!card2);
  handleSessionMessage({type: "local.tool_confirm_resolved", token: "tok-2", approved: true});
  await wait(80);
  check("通过后卡片标记完成", card2.classList.contains("is-done"));
  check("通过后按钮禁用", card2.querySelector(".tool-confirm-btn").disabled);
  check("状态文案为已确认", /已确认/.test(card2.querySelector(".tool-confirm-status").textContent),
        card2.querySelector(".tool-confirm-status").textContent);

  log.push("D) 拒绝路径：先点按钮，再等后端回执");
  $("#messages").innerHTML = "";
  handleSessionMessage(request("tok-3", "mcp__fake__write_note", {path: "c.txt", content: "no"}));
  await wait(80);
  const card3 = $(".tool-confirm");
  const denyBtn = card3.querySelectorAll(".tool-confirm-btn")[1];
  denyBtn.click();
  // 必须同步就查：请求可能飞快失败并把按钮恢复，等一会儿再查测的就不是"防重复提交"了
  check("点击后立即禁用按钮，防重复提交", card3.querySelectorAll(".tool-confirm-btn")[0].disabled);
  await wait(60);
  check("点击后给出进行中文案",
        /已拒绝|操作失败/.test(card3.querySelector(".tool-confirm-status").textContent),
        card3.querySelector(".tool-confirm-status").textContent);
  // 页面没有真实会话，postJSON 必然失败 —— 失败必须把按钮还回去，不能留一张点不动的卡片
  await wait(500);
  const statusText = card3.querySelector(".tool-confirm-status").textContent;
  if (/操作失败/.test(statusText)) {
    check("请求失败后按钮恢复可用（可重试）", !card3.querySelectorAll(".tool-confirm-btn")[0].disabled, statusText);
  } else {
    check("请求失败后按钮恢复可用（可重试）", true, "本次请求未失败：" + statusText);
  }

  log.push("E) 会话结束清理");
  handleSessionMessage(request("tok-4", "mcp__fake__write_note", {path: "d.txt", content: "z"}));
  await wait(80);
  check("第三张卡片在登记表里", toolConfirmCards.has("tok-4"), toolConfirmCards.size);
  const card4 = (toolConfirmCards.get("tok-4") || {}).card;
  clearToolConfirmCards("会话已结束，此次调用已取消");
  await wait(60);
  check("清理后登记表为空", toolConfirmCards.size === 0, toolConfirmCards.size);
  check("清理后按钮禁用", card4 ? card4.querySelector(".tool-confirm-btn").disabled : false);
  check("清理给出原因文案", card4 ? /会话已结束/.test(card4.querySelector(".tool-confirm-status").textContent) : false,
        card4 ? card4.querySelector(".tool-confirm-status").textContent : "no card");

  log.push("F) 未知 token 不炸");
  try {
    markToolConfirmDone("never-existed", "x", "expired");
    check("对不存在的 token 调用不抛异常", true);
  } catch (err) {
    check("对不存在的 token 调用不抛异常", false, String(err && err.message));
  }
  const cardsBefore = document.querySelectorAll(".tool-confirm").length;
  try {
    handleSessionMessage({type: "local.tool_confirm_request", token: "", name: "x", arguments: "{}"});
    check("空 token 的请求被忽略", document.querySelectorAll(".tool-confirm").length === cardsBefore,
          cardsBefore + " -> " + document.querySelectorAll(".tool-confirm").length);
  } catch (err) {
    check("空 token 的请求被忽略", false, String(err && err.message));
  }

  log.push("G) 运行期报错");
  const errs = window.__errs.filter(Boolean);
  check("自检期间无 JS 报错", errs.length === 0, errs.join(" | "));

  log.push("");
  log.push(fails === 0 ? "==> 全部通过" : "==> 有 " + fails + " 项未通过");
  return log.join("\n");
})()
