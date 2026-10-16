// CDP 截图：精确控制视口 / 主题 / 可注入预置 JS（比如强行进入通话页看样式）
import { writeFileSync, readFileSync } from "node:fs";

const [, , url, widthStr, heightStr, theme, preset, out] = process.argv;
const width = Number(widthStr), height = Number(heightStr);

const target = await (await fetch(`http://127.0.0.1:9222/json/new?about:blank`, { method: "PUT" })).json();
const ws = new WebSocket(target.webSocketDebuggerUrl);
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

await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: width < 700 });
await send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: theme }] });
await send("Page.enable");
await send("Network.enable");
await send("Network.setCacheDisabled", { cacheDisabled: true });
await send("Page.navigate", { url });
await new Promise(r => setTimeout(r, 2500));

if (preset) {
  const code = preset.startsWith("@") ? readFileSync(preset.slice(1), "utf8") : preset;
  await send("Runtime.evaluate", { expression: code });
  await new Promise(r => setTimeout(r, 700));
}

const clipArg = process.env.CLIP; // "x,y,w,h,scale"
const clip = clipArg
  ? (([x, y, w, h, s]) => ({ x, y, width: w, height: h, scale: s || 1 }))(clipArg.split(",").map(Number))
  : undefined;
const { data } = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false, ...(clip ? { clip } : {}) });
writeFileSync(out, Buffer.from(data, "base64"));
console.log("saved", out, width + "x" + height, theme);

// 关掉这个临时标签页
await fetch(`http://127.0.0.1:9222/json/close/${target.id}`);
ws.close();
