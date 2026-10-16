// 通过 CDP 精确测量布局：视口宽度、是否横向溢出、关键元素矩形
const [, , url, widthStr, heightStr, darkStr] = process.argv;
const width = Number(widthStr), height = Number(heightStr);
const dark = darkStr === "dark";

const res = await fetch(`http://127.0.0.1:${9222}/json/new?about:blank`, { method: "PUT" })
  .catch(async () => {
    // 老版本只支持 GET
    return fetch(`http://127.0.0.1:9222/json/new?about:blank`);
  });
const target = await res.json();
const wsUrl = target.webSocketDebuggerUrl;

const ws = new WebSocket(wsUrl);
let id = 0;
const pending = new Map();
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const msgId = ++id;
  pending.set(msgId, { resolve, reject });
  ws.send(JSON.stringify({ id: msgId, method, params }));
});

ws.addEventListener("message", evt => {
  const msg = JSON.parse(evt.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
  }
});

await new Promise(r => ws.addEventListener("open", r, { once: true }));

await send("Emulation.setDeviceMetricsOverride", {
  width, height, deviceScaleFactor: 1, mobile: width < 700,
});
await send("Emulation.setEmulatedMedia", {
  features: [{ name: "prefers-color-scheme", value: dark ? "dark" : "light" }],
});
await send("Page.enable");
await send("Page.navigate", { url });
await new Promise(r => setTimeout(r, 2500));

const expr = `(() => {
  const de = document.documentElement;
  const rect = sel => { const el = document.querySelector(sel); if (!el) return null;
    const r = el.getBoundingClientRect(); return {l: Math.round(r.left), r: Math.round(r.right), w: Math.round(r.width), t: Math.round(r.top), h: Math.round(r.height)}; };
  return JSON.stringify({
    theme: de.getAttribute("data-theme"),
    innerWidth: window.innerWidth,
    docClientWidth: de.clientWidth,
    docScrollWidth: de.scrollWidth,
    bodyScrollWidth: document.body.scrollWidth,
    overflowX: de.scrollWidth - de.clientWidth,
    hero: rect(".call-hero"),
    heroContent: rect(".call-hero-content"),
    callCopy: rect(".call-copy"),
    heroBtn: rect(".hero-call-btn"),
    settings: rect(".settings"),
    apiKey: rect("#apiKey"),
    workspace: rect(".workspace"),
    themeToggle: rect("#themeToggle"),
    bodyBg: getComputedStyle(document.body).backgroundColor,
    panelBg: getComputedStyle(document.querySelector(".settings")).backgroundColor,
    wide: [...document.querySelectorAll("body *")].filter(el => {
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.right > de.clientWidth + 1;
    }).slice(0, 12).map(el => el.tagName.toLowerCase() + (el.id ? "#" + el.id : "") + (el.className && typeof el.className === "string" ? "." + el.className.trim().split(/\\s+/).join(".") : "") + " right=" + Math.round(el.getBoundingClientRect().right)),
  });
})()`;

const { result } = await send("Runtime.evaluate", { expression: expr, returnByValue: true });
console.log(result.value);
ws.close();
