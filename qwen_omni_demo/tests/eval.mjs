// 在真实页面里跑一段脚本并把结果打回来
import { readFileSync } from "node:fs";

const [, , url, widthStr, heightStr, theme, presetPath] = process.argv;
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

await send("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: false });
await send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: theme }] });
await send("Page.enable");
await send("Network.enable");
await send("Network.setCacheDisabled", { cacheDisabled: true });
await send("Page.navigate", { url });
await new Promise(r => setTimeout(r, 2500));

const code = readFileSync(presetPath, "utf8");
const { result, exceptionDetails } = await send("Runtime.evaluate", {
  expression: code,
  returnByValue: true,
  awaitPromise: true,
});
if (exceptionDetails) console.log("EXCEPTION:", JSON.stringify(exceptionDetails, null, 2));
console.log(typeof result.value === "string" ? result.value : JSON.stringify(result.value, null, 2));

await fetch(`http://127.0.0.1:9222/json/close/${target.id}`);
ws.close();
