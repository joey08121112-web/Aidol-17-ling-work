const fs = require("fs");
const dir = process.argv[2];
const src = fs.readFileSync(dir + "/app.js", "utf8");

const defs = [...src.matchAll(/^(?:async\s+)?function\s+([A-Za-z0-9_$]+)/gm)].map(m => m[1]);
const consts = [...src.matchAll(/^const\s+([A-Za-z0-9_$]+)\s*=/gm)].map(m => m[1]);
const names = [...new Set([...defs, ...consts])];
const count = (n) => src.split(new RegExp("\\b" + n + "\\b")).length - 1;
const orphans = names.filter(n => count(n) <= 1);
console.log("零引用定义:", orphans.length ? orphans.join(", ") : "无");

for (const n of ["showToolConfirmCard", "settleToolConfirm", "markToolConfirmDone",
                 "clearToolConfirmCards", "friendlyToolName", "toolConfirmCards"]) {
  console.log("  " + n + ": 出现 " + count(n) + " 次");
}

const css = fs.readFileSync(dir + "/style.css", "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const open = (css.match(/\{/g) || []).length;
const close = (css.match(/\}/g) || []).length;
console.log("CSS 花括号:", open, "vs", close, open === close ? "平衡" : "不平衡!");

// els 对象定义键 vs 实际引用
const elsBlock = src.match(/const els = \{([\s\S]*?)\n\};/);
if (elsBlock) {
  const keys = [...elsBlock[1].matchAll(/^\s*([A-Za-z0-9_$]+)\s*:/gm)].map(m => m[1]);
  const body = src.replace(elsBlock[0], "");
  console.log("els 定义键数:", keys.length);
  console.log("定义但未引用的 els 键:", keys.filter(k => count("els." + k) === 0).join(", ") || "无");
} else {
  console.log("未找到 els 定义块");
}
