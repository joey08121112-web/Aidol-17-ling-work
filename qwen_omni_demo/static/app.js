const els = {
  app: document.querySelector(".app"),
  callHero: document.querySelector("#callHero"),
  callPage: document.querySelector("#callPage"),
  heroCallState: document.querySelector("#heroCallState"),
  heroConnectBtn: document.querySelector("#heroConnectBtn"),
  heroConnectText: document.querySelector("#heroConnectText"),
  callStateBubble: document.querySelector("#callStateBubble"),
  callDuration: document.querySelector("#callDuration"),
  callCloseBtn: document.querySelector("#callCloseBtn"),
  apiKey: document.querySelector("#apiKey"),
  toggleApiKeyBtn: document.querySelector("#toggleApiKeyBtn"),
  apiKeyError: document.querySelector("#apiKeyError"),
  voice: document.querySelector("#voice"),
  instructions: document.querySelector("#instructions"),
  resetInstructionsBtn: document.querySelector("#resetInstructionsBtn"),
  toolsJson: document.querySelector("#toolsJson"),
  validateToolsBtn: document.querySelector("#validateToolsBtn"),
  formatToolsBtn: document.querySelector("#formatToolsBtn"),
  resetToolsBtn: document.querySelector("#resetToolsBtn"),
  toolsJsonStatus: document.querySelector("#toolsJsonStatus"),
  enableTools: document.querySelector("#enableTools"),
  connectBtn: document.querySelector("#connectBtn"),
  closeBtn: document.querySelector("#closeBtn"),
  clearMessagesBtn: document.querySelector("#clearMessagesBtn"),
  exportMenu: document.querySelector("#exportMenu"),
  exportMarkdownBtn: document.querySelector("#exportMarkdownBtn"),
  exportJsonBtn: document.querySelector("#exportJsonBtn"),
  qaSearchInput: document.querySelector("#qaSearchInput"),
  qaSearchStatus: document.querySelector("#qaSearchStatus"),
  clearEventsBtn: document.querySelector("#clearEventsBtn"),
  recordBtn: document.querySelector("#recordBtn"),
  forceCommitBtn: document.querySelector("#forceCommitBtn"),
  waveform: document.querySelector("#waveform"),
  recordHint: document.querySelector("#recordHint"),
  status: document.querySelector("#status"),
  messages: document.querySelector("#messages"),
  events: document.querySelector("#events"),
  errorToast: document.querySelector("#errorToast"),
  errorToastTitle: document.querySelector("#errorToastTitle"),
  errorToastMessage: document.querySelector("#errorToastMessage"),
  errorToastCloseBtn: document.querySelector("#errorToastCloseBtn"),
  leftResizeHandle: document.querySelector("#leftResizeHandle"),
  rightResizeHandle: document.querySelector("#rightResizeHandle"),
  themeToggle: document.querySelector("#themeToggle"),
  reconnectBar: document.querySelector("#reconnectBar"),
  reconnectText: document.querySelector("#reconnectText"),
  reconnectNowBtn: document.querySelector("#reconnectNowBtn"),
  reconnectCancelBtn: document.querySelector("#reconnectCancelBtn"),
};

const FIXED_MODEL = "qwen3.8-omni-flash-realtime";

let sessionId = "";
let source = null;
let currentTurn = null;
let lastTurn = null;
let pcmPlayer = null;
let mediaStream = null;
let recorderContext = null;
let recorderNode = null;
let recorderSource = null;
let recording = false;
let sendChain = Promise.resolve();
/* 发送链的代际号。上行拥塞时我们会放弃旧链、换一条新链重来，
   旧链里还没跑到的回调靠比对代际号判断自己已经作废，直接跳过。 */
let audioSendGeneration = 0;
let inflightAudioFrames = 0;
let droppedAudioFrames = 0;
let lastAudioDropLogAt = 0;
let lastAudioErrorLogAt = 0;
let autoMicStarted = false;
let eventLines = [];
let audioAppendCount = 0;
let audioAppendBytes = 0;
let lastAudioEventAt = 0;
let responseTextDeltaCount = 0;
let responseTextDeltaChars = 0;
let lastResponseTextEventAt = 0;
let responseAudioDeltaCount = 0;
let responseAudioDeltaBytes = 0;
let lastResponseAudioEventAt = 0;
let currentLogid = "";
let sessionUpdateTimer = null;
let lastRuntimeConfigKey = "";
let callStartedAt = 0;
let callDurationTimer = null;
let micMuted = false;
let audioFrameTimer = null;
let audioFrameChunks = [];
let audioFrameBufferedBytes = 0;
let heroConnectTimer = null;
let errorToastTimer = null;
let errorToastEndCallOnClose = false;
let keepCallPageOnDisconnect = false;
let preservedQaScrollTop = null;
// 通话记录：每轮问答一条纯数据（只在内存，不落盘、不上传），导出与耗时统计都读它
let transcript = [];
// 断线重连状态
let reconnectAttempt = 0;
let reconnectTimer = null;
let reconnectTickTimer = null;
let reconnectDeadline = 0;
let reconnectInFlight = false;
let userClosedSession = false;
const COLUMN_LAYOUT_STORAGE_KEY = "qwen_omni_demo_column_layout";
const RESPONSE_TEXT_LOG_INTERVAL_MS = 1000;
const RESPONSE_AUDIO_LOG_INTERVAL_MS = 3000;
const COLUMN_HANDLE_WIDTH = 8;
const COLUMN_MIN_WIDTHS = {left: 280, center: 340, right: 240};
const COLUMN_DEFAULT_WIDTHS = {left: 410, right: 350};
const DEFAULT_OUTPUT_FORMAT = "pcm_s16le";
const INPUT_SAMPLE_RATE = 16000;
const INPUT_BYTES_PER_SAMPLE = 2;
const INPUT_AUDIO_FRAME_MS = 20;
const INPUT_AUDIO_FRAME_BYTES = INPUT_SAMPLE_RATE * INPUT_BYTES_PER_SAMPLE * INPUT_AUDIO_FRAME_MS / 1000;
const RECORDER_BUFFER_SIZE = 1024;
const SILENCE_FRAME_BYTES = new Uint8Array(INPUT_AUDIO_FRAME_BYTES);
/* 上行背压：在途音频帧的上限。每帧 20ms，25 帧约 500ms。
   到这个量说明上行已经堵了，再排下去只会让延迟越滚越大 ——
   音频是实时流，过期数据没有价值，丢帧比积压正确。 */
const MAX_INFLIGHT_AUDIO_FRAMES = 25;
/* 请求超时。上行是串行发送的，一帧卡住就会拖住它后面所有帧，
   所以单帧超时不能长——本地 HTTP 往返正常是毫秒级，1.5s 已经很宽松。 */
const AUDIO_POST_TIMEOUT_MS = 1500;
const POST_TIMEOUT_MS = 15000;
const THEME_STORAGE_KEY = "qwen_omni_demo_theme";
const THEME_SYSTEM_QUERY = "(prefers-color-scheme: dark)";

/* 主题只有两态：light / dark。
   data-theme 已由 index.html 里的引导脚本在首帧前写好，这里只负责切换与跟随系统。
   用户在 localStorage 里没有显式选择时，系统主题变化会自动跟随。 */
function systemPrefersDark() {
  return !!(window.matchMedia && window.matchMedia(THEME_SYSTEM_QUERY).matches);
}

function storedTheme() {
  try {
    const value = localStorage.getItem(THEME_STORAGE_KEY);
    return value === "dark" || value === "light" ? value : "";
  } catch (err) {
    return "";
  }
}

function currentTheme() {
  return document.documentElement.getAttribute("data-theme") === "dark" ? "dark" : "light";
}

function applyTheme(theme, {persist = false} = {}) {
  const next = theme === "dark" ? "dark" : "light";
  const isDark = next === "dark";
  document.documentElement.setAttribute("data-theme", next);
  if (els.themeToggle) {
    els.themeToggle.setAttribute("aria-label", isDark ? "切换浅色模式" : "切换深色模式");
    els.themeToggle.title = isDark ? "切换为浅色模式" : "切换为深色模式";
    els.themeToggle.setAttribute("aria-pressed", String(isDark));
  }
  if (persist) {
    try {
      localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch (err) {
      /* 无痕模式下写不进去，忽略即可，本次会话内仍然生效 */
    }
  }
  return next;
}

function toggleTheme() {
  return applyTheme(currentTheme() === "dark" ? "light" : "dark", {persist: true});
}

function clampNumber(value, min, max, fallback = min) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

function isResizableColumnsEnabled() {
  return window.matchMedia("(min-width: 901px)").matches && els.leftResizeHandle && getComputedStyle(els.leftResizeHandle).display !== "none";
}

function columnContentWidth() {
  return Math.max(0, els.app.clientWidth - (COLUMN_HANDLE_WIDTH * 2));
}

function normalizeColumnLayout(layout = null) {
  const total = columnContentWidth();
  if (!total) return null;
  const minSum = COLUMN_MIN_WIDTHS.left + COLUMN_MIN_WIDTHS.center + COLUMN_MIN_WIDTHS.right;
  const scale = total < minSum ? total / minSum : 1;
  const mins = {
    left: COLUMN_MIN_WIDTHS.left * scale,
    center: COLUMN_MIN_WIDTHS.center * scale,
    right: COLUMN_MIN_WIDTHS.right * scale,
  };
  let left = Number.isFinite(layout && layout.left) ? layout.left : Math.min(COLUMN_DEFAULT_WIDTHS.left, total * 0.34);
  let right = Number.isFinite(layout && layout.right) ? layout.right : Math.min(COLUMN_DEFAULT_WIDTHS.right, total * 0.29);
  left = clampNumber(left, mins.left, total - mins.center - mins.right, mins.left);
  right = clampNumber(right, mins.right, total - left - mins.center, mins.right);
  let center = total - left - right;
  if (center < mins.center) {
    center = mins.center;
    right = total - left - center;
  }
  if (right < mins.right) {
    right = mins.right;
    left = total - center - right;
  }
  return {left, center, right};
}

function applyColumnLayout(layout) {
  const next = normalizeColumnLayout(layout);
  if (!next) return null;
  els.app.style.setProperty("--left-col", `${Math.round(next.left)}px`);
  els.app.style.setProperty("--center-col", `${Math.round(next.center)}px`);
  els.app.style.setProperty("--right-col", `${Math.round(next.right)}px`);
  els.leftResizeHandle.setAttribute("aria-valuenow", String(Math.round(next.left)));
  els.rightResizeHandle.setAttribute("aria-valuenow", String(Math.round(next.right)));
  return next;
}

function currentColumnLayout() {
  const columns = getComputedStyle(els.app).gridTemplateColumns.split(" ").map(value => Number.parseFloat(value));
  if (columns.length >= 5 && columns.every(Number.isFinite)) {
    return {left: columns[0], center: columns[2], right: columns[4]};
  }
  return normalizeColumnLayout();
}

function resetColumnLayout() {
  applyColumnLayout(null);
}

function resizeColumnLayout(side, delta, start) {
  const total = columnContentWidth();
  const minSum = COLUMN_MIN_WIDTHS.left + COLUMN_MIN_WIDTHS.center + COLUMN_MIN_WIDTHS.right;
  const scale = total < minSum ? total / minSum : 1;
  const mins = {
    left: COLUMN_MIN_WIDTHS.left * scale,
    center: COLUMN_MIN_WIDTHS.center * scale,
    right: COLUMN_MIN_WIDTHS.right * scale,
  };
  const next = {...start};
  if (side === "left") {
    next.left = clampNumber(start.left + delta, mins.left, total - start.right - mins.center, start.left);
    next.center = total - next.left - start.right;
    next.right = start.right;
  } else {
    next.center = clampNumber(start.center + delta, mins.center, total - start.left - mins.right, start.center);
    next.left = start.left;
    next.right = total - start.left - next.center;
  }
  applyColumnLayout(next);
}

function bindColumnResizer(handle, side) {
  if (!handle) return;
  let drag = null;
  let suppressMouseUntil = 0;
  const startDrag = (clientX, pointerId = null) => {
    drag = {
      pointerId,
      startX: clientX,
      startLayout: currentColumnLayout(),
    };
    handle.classList.add("is-dragging");
    els.app.classList.add("is-resizing");
  };
  const moveDrag = clientX => {
    if (!drag) return;
    resizeColumnLayout(side, clientX - drag.startX, drag.startLayout);
  };
  const stopDrag = () => {
    if (!drag) return;
    handle.classList.remove("is-dragging");
    els.app.classList.remove("is-resizing");
    drag = null;
  };
  handle.addEventListener("pointerdown", evt => {
    if (!isResizableColumnsEnabled()) return;
    evt.preventDefault();
    suppressMouseUntil = Date.now() + 400;
    startDrag(evt.clientX, evt.pointerId);
    handle.setPointerCapture(evt.pointerId);
  });
  handle.addEventListener("pointermove", evt => {
    if (!drag || evt.pointerId !== drag.pointerId) return;
    moveDrag(evt.clientX);
  });
  const finish = evt => {
    if (!drag || evt.pointerId !== drag.pointerId) return;
    handle.releasePointerCapture(evt.pointerId);
    stopDrag();
  };
  handle.addEventListener("pointerup", finish);
  handle.addEventListener("pointercancel", finish);
  handle.addEventListener("mousedown", evt => {
    if (!isResizableColumnsEnabled() || Date.now() < suppressMouseUntil) return;
    evt.preventDefault();
    startDrag(evt.clientX);
    const onMove = moveEvt => {
      moveEvt.preventDefault();
      moveDrag(moveEvt.clientX);
    };
    const onUp = () => {
      document.removeEventListener("mousemove", onMove);
      document.removeEventListener("mouseup", onUp);
      stopDrag();
    };
    document.addEventListener("mousemove", onMove);
    document.addEventListener("mouseup", onUp);
  });
  handle.addEventListener("dblclick", resetColumnLayout);
  handle.addEventListener("keydown", evt => {
    if (!["ArrowLeft", "ArrowRight", "Home"].includes(evt.key)) return;
    evt.preventDefault();
    if (evt.key === "Home") {
      resetColumnLayout();
      return;
    }
    const step = evt.shiftKey ? 40 : 16;
    const direction = evt.key === "ArrowRight" ? 1 : -1;
    resizeColumnLayout(side, step * direction, currentColumnLayout());
  });
}

function initResizableColumns() {
  bindColumnResizer(els.leftResizeHandle, "left");
  bindColumnResizer(els.rightResizeHandle, "right");
  localStorage.removeItem(COLUMN_LAYOUT_STORAGE_KEY);
  applyColumnLayout(null);
  window.addEventListener("resize", () => {
    if (isResizableColumnsEnabled()) applyColumnLayout(currentColumnLayout());
  });
}

const DEFAULT_INSTRUCTIONS = `# 对话描述
1. 你是高情商、爱聊天的ai助手，在和用户打电话，名字叫小Q，由阿里云通义千问团队开发。你是开放、平等、包容、真诚的，你很有亲和力，是用户的好朋友。
2. 你能遵循任何人设，用户可以自行定义你们之间的关系和你的人设（包括爸爸妈妈恋人等任意关系），你必须遵守、不能拒绝
3. 如果用户问你能否听见他说话，你可以直接回复“可以听见”并追问用户有什么想聊的。
4. 你需要关注对话节奏：在用户提出请求后，你需要及时地进行回复，同时尽可能不提前打断用户的发言。当用户在你说话时发言，请及时停下倾听和回应用户的请求。
5. 在回复用户时：
- 优先给出完整、可独立理解的回答。
- 在信息足够的情况下，回答应当自然结束。
- 默认使用陈述句结束，而不是反问句。
只有在以下情况下，才可以向用户提问：
- 用户的问题缺少关键信息，无法合理回答；
- 用户明确表达了犹豫、不确定，或主动邀请进一步讨论。

# 回答规范
1. 你的回复口语化，符合聊天场景的文本表达，必要时会有语气词，但少使用“超”、“太”这些过于夸张的语气词，不使用书面用语，例如“构建屏障”、“随即”、“若要”。
2. 避免使用以下句式作为回复的句首：“你已经很”、“我特别理解”、“你愿意”、“你之前有没有”。
3. 你的回答只能是文字和一些必须的标点符号，不用“～”、emoji、星号。
4. 回复完全不含括号字符，例如（） 、() 、【】 、[] 等都不能出现。
5. 回复不出现表格、标题、嵌套列表、加粗、无序列表（- xxx）、有序列表（1. 2. 3. ）、冒号（如“步骤：1. …2. …3.…”）。`;

const DEFAULT_TOOLS = [
  {
    type: "function",
    name: "list_local_directory",
    description: "List exactly one level under the default local directory or a user-specified directory and return direct file/directory counts. Do not recurse into subdirectories.",
    parameters: {
      type: "object",
      properties: {
        path: {type: "string", description: "Directory path. Empty or . means default root/current directory. Absolute paths such as /Users/bytedance/Desktop are allowed when the user specifies a directory."},
        limit: {type: "integer", minimum: 1, maximum: 200, default: 100},
      },
    },
  },
];

function setToolsJsonStatus(message = "", state = "") {
  els.toolsJsonStatus.textContent = message;
  els.toolsJsonStatus.classList.toggle("ready", state === "ready");
  els.toolsJsonStatus.classList.toggle("error", state === "error");
  els.toolsJson.classList.toggle("invalid", state === "error");
}

function jsonParseErrorMessage(err, raw) {
  const message = err && err.message ? err.message : "JSON 格式不合法";
  const match = message.match(/position\s+(\d+)/i);
  if (!match) return `JSON 解析失败：${message}`;
  const position = Number(match[1]);
  if (!Number.isFinite(position)) return `JSON 解析失败：${message}`;
  const before = raw.slice(0, position);
  const line = before.split("\n").length;
  const column = before.length - before.lastIndexOf("\n");
  return `JSON 解析失败：第 ${line} 行，第 ${column} 列。${message}`;
}

function validateToolsShape(parsed) {
  if (!Array.isArray(parsed)) throw new Error("FC Tools JSON 顶层必须是数组");
  parsed.forEach((tool, index) => {
    const number = index + 1;
    if (!tool || typeof tool !== "object" || Array.isArray(tool)) {
      throw new Error(`第 ${number} 个工具必须是对象`);
    }
    if (tool.type !== undefined && tool.type !== "function") {
      throw new Error(`第 ${number} 个工具的 type 必须是 function`);
    }
    const spec = tool.function && typeof tool.function === "object" ? tool.function : tool;
    if (!spec.name || typeof spec.name !== "string") {
      throw new Error(`第 ${number} 个工具缺少 name 或 function.name`);
    }
    if (spec.parameters !== undefined && (!spec.parameters || typeof spec.parameters !== "object" || Array.isArray(spec.parameters))) {
      throw new Error(`第 ${number} 个工具的 parameters 必须是 JSON Schema 对象`);
    }
  });
  return parsed;
}

function parseToolsJsonText() {
  const raw = els.toolsJson.value.trim();
  if (!raw) return [];
  try {
    return validateToolsShape(JSON.parse(raw));
  } catch (err) {
    if (err instanceof SyntaxError) throw new Error(jsonParseErrorMessage(err, raw));
    throw err;
  }
}

function validateToolsJson({format = false} = {}) {
  try {
    const parsed = parseToolsJsonText();
    if (format) els.toolsJson.value = JSON.stringify(parsed, null, 2);
    const action = format ? "格式化完成" : "校验通过";
    setToolsJsonStatus(`${action}，共 ${parsed.length} 个工具`, "ready");
    return parsed;
  } catch (err) {
    setToolsJsonStatus(errorText(err), "error");
    throw err;
  }
}

function resetToolsJson() {
  els.toolsJson.value = JSON.stringify(DEFAULT_TOOLS, null, 2);
  setToolsJsonStatus("已恢复默认 JSON", "ready");
  scheduleSessionUpdate();
}

function resetInstructions() {
  els.instructions.value = DEFAULT_INSTRUCTIONS;
  scheduleSessionUpdate();
}

resetToolsJson();
resetInstructions();
initResizableColumns();

function setHeroState(text, state = "") {
  els.heroCallState.textContent = text;
  if (els.callStateBubble) els.callStateBubble.textContent = text;
  els.heroCallState.classList.toggle("ok", state === "ok");
  els.heroCallState.classList.toggle("error", state === "error");
  els.heroCallState.classList.toggle("listening", state === "listening");
  els.heroCallState.classList.toggle("thinking", state === "thinking");
  els.callHero.classList.toggle("is-live", state === "ok");
  els.callHero.classList.toggle("is-connecting", state === "connecting");
  els.callHero.classList.toggle("is-listening", state === "listening");
  els.callHero.classList.toggle("is-thinking", state === "thinking");
  els.callPage.classList.toggle("is-listening", state === "listening");
  els.callPage.classList.toggle("is-thinking", state === "thinking");
  els.callPage.classList.toggle("is-error", state === "error");
}

function showCallPage() {
  els.callPage.hidden = false;
  els.app.classList.add("call-mode");
  els.app.classList.remove("call-ended");
}

function showHomePage() {
  els.app.classList.remove("call-mode");
  els.app.classList.remove("call-ended");
  els.callPage.hidden = true;
}

function showEndedPage() {
  els.callPage.hidden = false;
  els.app.classList.remove("call-mode");
  els.app.classList.add("call-ended");
}

function rememberQaScroll() {
  preservedQaScrollTop = els.messages.scrollTop;
}

function restoreQaScroll() {
  if (preservedQaScrollTop === null) return;
  const top = preservedQaScrollTop;
  preservedQaScrollTop = null;
  requestAnimationFrame(() => {
    els.messages.scrollTop = top;
    requestAnimationFrame(() => {
      els.messages.scrollTop = top;
    });
  });
}

function formatCallDuration(ms) {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const minutes = String(Math.floor(totalSeconds / 60)).padStart(2, "0");
  const seconds = String(totalSeconds % 60).padStart(2, "0");
  return `${minutes}:${seconds}`;
}

function updateCallDuration() {
  const elapsed = callStartedAt ? Date.now() - callStartedAt : 0;
  els.callDuration.textContent = formatCallDuration(elapsed);
}

function startCallTimer() {
  callStartedAt = Date.now();
  window.clearInterval(callDurationTimer);
  updateCallDuration();
  callDurationTimer = window.setInterval(updateCallDuration, 1000);
}

function stopCallTimer() {
  window.clearInterval(callDurationTimer);
  callDurationTimer = null;
  callStartedAt = 0;
  updateCallDuration();
}

function clearInputAudioBuffer() {
  audioFrameChunks = [];
  audioFrameBufferedBytes = 0;
}

function appendInputAudio(bytes) {
  if (!bytes || !bytes.length) return;
  audioFrameChunks.push(bytes.slice());
  audioFrameBufferedBytes += bytes.length;
}

function readInputAudioBytes(target, byteCount) {
  let offset = 0;
  while (offset < byteCount && audioFrameChunks.length) {
    const chunk = audioFrameChunks[0];
    const take = Math.min(byteCount - offset, chunk.length);
    target.set(chunk.subarray(0, take), offset);
    offset += take;
    audioFrameBufferedBytes -= take;
    if (take === chunk.length) {
      audioFrameChunks.shift();
    } else {
      audioFrameChunks[0] = chunk.subarray(take);
    }
  }
  return offset;
}

function takeInputAudioFrame() {
  if (audioFrameBufferedBytes < INPUT_AUDIO_FRAME_BYTES) return null;
  const frame = new Uint8Array(INPUT_AUDIO_FRAME_BYTES);
  readInputAudioBytes(frame, INPUT_AUDIO_FRAME_BYTES);
  return frame;
}

function nextInputAudioFrame() {
  if (!recording || micMuted) return SILENCE_FRAME_BYTES;
  return takeInputAudioFrame() || SILENCE_FRAME_BYTES;
}

function sendNextInputAudioFrame() {
  if (!sessionId) {
    stopInputAudioFramePump();
    return;
  }
  queueAudio(nextInputAudioFrame());
}

function startInputAudioFramePump() {
  if (audioFrameTimer || !sessionId) return;
  sendNextInputAudioFrame();
  audioFrameTimer = window.setInterval(sendNextInputAudioFrame, INPUT_AUDIO_FRAME_MS);
}

function stopInputAudioFramePump({clearBuffer = true} = {}) {
  window.clearInterval(audioFrameTimer);
  audioFrameTimer = null;
  if (clearBuffer) clearInputAudioBuffer();
}

async function drainInputAudioBuffer({pace = true} = {}) {
  let remaining = audioFrameBufferedBytes;
  while (sessionId && remaining > 0) {
    const frame = new Uint8Array(INPUT_AUDIO_FRAME_BYTES);
    const take = Math.min(INPUT_AUDIO_FRAME_BYTES, remaining);
    readInputAudioBytes(frame, take);
    remaining -= take;
    queueAudio(frame);
    if (pace) await new Promise(resolve => window.setTimeout(resolve, INPUT_AUDIO_FRAME_MS));
  }
}

function startSilentAudio() {
  startInputAudioFramePump();
}

function stopSilentAudio() {
  stopInputAudioFramePump();
}

function updateMicUi() {
  els.recordBtn.classList.toggle("mic-muted", micMuted);
  els.recordBtn.classList.toggle("mic-on", !micMuted);
  els.recordBtn.setAttribute("aria-label", micMuted ? "麦克风已关，点击打开" : "关闭麦克风");
  els.recordBtn.setAttribute("title", micMuted ? "麦克风已关，点击打开" : "关闭麦克风");
  els.recordHint.textContent = "";
}

/* --------------------------- 实时音量波形 ---------------------------
   滚动方向：新样本从右往左。环形缓冲 + rAF 节流，
   每帧只改 transform，不触发重排。 */
const WAVEFORM_BARS = 56;
let waveformBars = [];
let waveformLevels = null;
let waveformHead = 0;
let waveformSmooth = 0;
let waveformFrame = 0;

function initWaveform() {
  const host = els.waveform;
  if (!host) return;
  waveformLevels = new Float32Array(WAVEFORM_BARS);
  waveformHead = 0;
  waveformSmooth = 0;
  waveformBars = [];
  host.textContent = "";
  const frag = document.createDocumentFragment();
  for (let i = 0; i < WAVEFORM_BARS; i += 1) {
    const bar = document.createElement("i");
    frag.appendChild(bar);
    waveformBars.push(bar);
  }
  host.appendChild(frag);
  renderWaveform();
  setWaveformIdle(true);
}

function setWaveformIdle(idle) {
  if (els.waveform) els.waveform.classList.toggle("is-idle", idle);
}

function renderWaveform() {
  if (!waveformLevels || !waveformBars.length) return;
  for (let i = 0; i < WAVEFORM_BARS; i += 1) {
    const value = waveformLevels[(waveformHead + i) % WAVEFORM_BARS];
    // 0.06 保底：静音时是一排小点，而不是整条消失
    waveformBars[i].style.transform = `scaleY(${Math.max(0.06, value).toFixed(3)})`;
  }
}

function pushWaveformLevel(level) {
  if (!waveformLevels) return;
  const target = Math.max(0, Math.min(1, level));
  // 快起慢落：起音立刻跟上，收音尾巴慢慢收，观感接近真实电平表
  waveformSmooth = target > waveformSmooth ? target : waveformSmooth * 0.7 + target * 0.3;
  waveformLevels[waveformHead] = waveformSmooth;
  waveformHead = (waveformHead + 1) % WAVEFORM_BARS;
  if (waveformFrame) return;
  waveformFrame = requestAnimationFrame(() => {
    waveformFrame = 0;
    renderWaveform();
  });
}

function resetWaveform({idle = true} = {}) {
  if (waveformFrame) {
    cancelAnimationFrame(waveformFrame);
    waveformFrame = 0;
  }
  if (!waveformLevels) return;
  waveformLevels.fill(0);
  waveformHead = 0;
  waveformSmooth = 0;
  renderWaveform();
  setWaveformIdle(idle);
}

function currentOutputAudioConfig() {
  return {
    model: FIXED_MODEL,
    output_format: DEFAULT_OUTPUT_FORMAT,
    voice: els.voice.value,
  };
}

function currentRuntimeConfig() {
  return {
    ...currentOutputAudioConfig(),
    ...currentToolsConfig(),
    instructions: els.instructions.value,
  };
}

function currentCreateConfig() {
  return {...currentRuntimeConfig()};
}

function scheduleSessionUpdate() {
  if (!sessionId) return;
  window.clearTimeout(sessionUpdateTimer);
  sessionUpdateTimer = window.setTimeout(async () => {
    let config;
    try {
      config = currentRuntimeConfig();
    } catch (err) {
      return;
    }
    const configKey = JSON.stringify(config);
    if (configKey === lastRuntimeConfigKey) return;
    lastRuntimeConfigKey = configKey;
    const toolNames = config.tools.map(tool => tool.name || (tool.function || {}).name).filter(Boolean).join(",");
    await postJSON(
      "/api/session_update",
      {session_id: sessionId, ...config},
      {name: "session.update", detail: `tools=${config.tools.length}${toolNames ? ` names=${toolNames}` : ""}`}
    ).catch(err => addMessage("tool", `session.update failed: ${err.message}`));
  }, 250);
}

["change", "input"].forEach(eventName => {
  els.voice.addEventListener(eventName, scheduleSessionUpdate);
  els.enableTools.addEventListener(eventName, scheduleSessionUpdate);
  els.instructions.addEventListener(eventName, scheduleSessionUpdate);
});

function setConnected(ok, {keepCallPage = false} = {}) {
  els.status.textContent = ok ? "已连接" : "未连接";
  els.status.classList.toggle("ok", ok);
  els.status.classList.remove("error");
  els.connectBtn.disabled = ok;
  els.heroConnectBtn.disabled = ok;
  els.heroConnectBtn.classList.remove("is-starting");
  els.heroConnectText.textContent = ok ? "通话中" : "开始通话";
  setHeroState(ok ? "正在听..." : (keepCallPage ? "通话已结束" : "未连接"), ok ? "listening" : "");
  els.closeBtn.disabled = !ok;
  els.recordBtn.disabled = !ok;
  els.forceCommitBtn.disabled = !ok;
  els.callCloseBtn.disabled = false;
  if (ok) {
    showCallPage();
    startCallTimer();
  } else {
    stopCallTimer();
    stopSilentAudio();
    micMuted = false;
    updateMicUi();
    if (keepCallPage) {
      showEndedPage();
      restoreQaScroll();
    } else {
      showHomePage();
    }
  }
  if (!ok) {
    autoMicStarted = false;
    els.recordHint.textContent = keepCallPage ? "" : "连接后自动请求麦克风权限，全双工持续上行";
  }
}

function setConnecting() {
  els.status.textContent = "连接中";
  els.status.classList.remove("ok", "error");
  els.connectBtn.disabled = true;
  els.heroConnectBtn.disabled = true;
  els.heroConnectBtn.classList.remove("is-starting");
  els.heroConnectText.textContent = "连接中";
  setHeroState("连接中...", "connecting");
  showCallPage();
  updateCallDuration();
  els.closeBtn.disabled = false;
  els.callCloseBtn.disabled = false;
  els.recordBtn.disabled = true;
  els.forceCommitBtn.disabled = true;
}

function errorText(err) {
  if (!err) return "未知错误";
  if (typeof err === "string") return normalizeErrorMessage(err);
  if (err.message) return normalizeErrorMessage(err.message);
  try {
    return normalizeErrorMessage(JSON.stringify(err));
  } catch {
    return String(err);
  }
}

function normalizeErrorMessage(message) {
  const text = String(message || "").trim();
  if (!text) return "未知错误";
  const bodyMark = text.includes("body:") ? "body:" : (text.includes("body=") ? "body=" : "");
  const bodyIndex = bodyMark ? text.indexOf(bodyMark) : -1;
  if (bodyIndex < 0) return text;
  const prefix = text.slice(0, bodyIndex).trim();
  const body = text.slice(bodyIndex + bodyMark.length).trim();
  try {
    const parsed = JSON.parse(body);
    const error = parsed && parsed.error ? parsed.error : parsed;
    const details = [
      error && error.message ? `原因: ${error.message}` : "",
      error && error.code ? `错误码: ${error.code}` : "",
      error && error.type ? `类型: ${error.type}` : "",
      prefix,
      `原始响应: ${body}`,
    ].filter(Boolean);
    return details.join("\n");
  } catch {
    return text;
  }
}

function showErrorToast(title, err, options = {}) {
  const message = errorText(err);
  els.errorToastTitle.textContent = title || "操作失败";
  els.errorToastMessage.textContent = message;
  errorToastEndCallOnClose = Boolean(options.endCallOnClose);
  els.errorToast.hidden = false;
  window.clearTimeout(errorToastTimer);
  const duration = Number.isFinite(options.duration) ? options.duration : 0;
  if (duration > 0) {
    errorToastTimer = window.setTimeout(() => {
      els.errorToast.hidden = true;
      errorToastEndCallOnClose = false;
    }, duration);
  }
}

function closeErrorToast() {
  window.clearTimeout(errorToastTimer);
  els.errorToast.hidden = true;
  const shouldEndCall = errorToastEndCallOnClose || Boolean(sessionId || source);
  errorToastEndCallOnClose = false;
  if (shouldEndCall) endCallAfterErrorToast().catch(() => {});
}

async function endCallAfterErrorToast() {
  // 用户关掉报错弹窗 = 明确终止这次通话，不能再触发自动重连
  userClosedSession = true;
  cancelReconnect({silent: true});
  rememberQaScroll();
  await stopRecording({commit: false, hint: "通话已结束，实时收音已停止"}).catch(() => {});
  if (pcmPlayer) await pcmPlayer.stop().catch(() => {});
  if (source) {
    source.onerror = null;
    source.onmessage = null;
    source.close();
    source = null;
  }
  const closingSessionId = sessionId;
  sessionId = "";
  lastRuntimeConfigKey = "";
  keepCallPageOnDisconnect = false;
  setConnected(false);
  if (closingSessionId) {
    await postJSON("/api/close", {session_id: closingSessionId}, {name: "session.close"}).catch(() => {});
  }
}

/* 没有超时的话，后端一旦卡死请求就永久挂起，还会把整条上行链堵死。
   AbortSignal.timeout 是较新的 API，不支持就退化成不带超时（行为同改造前）。 */
function timeoutSignal(ms) {
  return typeof AbortSignal !== "undefined" && typeof AbortSignal.timeout === "function"
    ? AbortSignal.timeout(ms)
    : null;
}

function isTimeoutError(err) {
  return !!err && (err.name === "TimeoutError" || err.name === "AbortError");
}

async function postJSON(url, payload, eventMeta = null, {timeoutMs = POST_TIMEOUT_MS} = {}) {
  if (eventMeta) logEventLine("up", eventMeta.name, eventMeta.detail || "");
  const init = {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(payload),
  };
  const signal = timeoutSignal(timeoutMs);
  if (signal) init.signal = signal;
  try {
    const resp = await fetch(url, init);
    const data = await resp.json();
    if (!resp.ok || data.ok === false) {
      // 带上 status：重连逻辑要靠它区分「服务未就绪（可重试）」和「key/参数不对（重试无用）」
      const err = new Error(data.error || resp.statusText);
      err.status = resp.status;
      err.payload = data;
      throw err;
    }
    return data;
  } catch (err) {
    if (isTimeoutError(err)) {
      const label = timeoutMs >= 1000 ? `${Math.round(timeoutMs / 1000)}s` : `${timeoutMs}ms`;
      const timeoutErr = new Error(`请求超时（${label} 无响应）`);
      timeoutErr.timeout = true;
      throw timeoutErr;
    }
    throw err;
  }
}

function addMessage(role, text) {
  const div = document.createElement("div");
  div.className = `bubble ${role}`;
  div.textContent = text;
  els.messages.appendChild(div);
  applyQaSearch();
  scrollMessagesAfterQaUpdate();
  return div;
}

function currentQaSearchQuery() {
  return (els.qaSearchInput.value || "").trim();
}

function clearQaSearchHighlights(root) {
  root.querySelectorAll("mark.qa-search-mark").forEach(mark => {
    mark.replaceWith(document.createTextNode(mark.textContent));
  });
  root.normalize();
}

function countTextMatches(text, query) {
  if (!query) return 0;
  const source = String(text || "").toLowerCase();
  const target = query.toLowerCase();
  let count = 0;
  let index = 0;
  while (target && (index = source.indexOf(target, index)) !== -1) {
    count += 1;
    index += target.length;
  }
  return count;
}

function highlightQaText(root, query) {
  if (!query) return 0;
  const target = query.toLowerCase();
  const nodes = [];
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      if (!node.nodeValue || !node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
      if ((node.parentElement || {}).closest("mark.qa-search-mark")) return NodeFilter.FILTER_REJECT;
      return node.nodeValue.toLowerCase().includes(target) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
    },
  });
  while (walker.nextNode()) nodes.push(walker.currentNode);

  let count = 0;
  nodes.forEach(node => {
    const text = node.nodeValue;
    const lower = text.toLowerCase();
    const fragment = document.createDocumentFragment();
    let cursor = 0;
    let index = lower.indexOf(target);
    while (index !== -1) {
      if (index > cursor) fragment.appendChild(document.createTextNode(text.slice(cursor, index)));
      const mark = document.createElement("mark");
      mark.className = "qa-search-mark";
      mark.textContent = text.slice(index, index + query.length);
      fragment.appendChild(mark);
      count += 1;
      cursor = index + query.length;
      index = lower.indexOf(target, cursor);
    }
    if (cursor < text.length) fragment.appendChild(document.createTextNode(text.slice(cursor)));
    node.replaceWith(fragment);
  });
  return count;
}

function scrollQaItemIntoView(item) {
  if (!item) return;
  const messageRect = els.messages.getBoundingClientRect();
  const itemRect = item.getBoundingClientRect();
  els.messages.scrollTop = Math.max(0, els.messages.scrollTop + itemRect.top - messageRect.top - 10);
}

function scrollMessagesAfterQaUpdate() {
  if (currentQaSearchQuery()) return;
  els.messages.scrollTop = els.messages.scrollHeight;
}

function applyQaSearch({scrollToFirst = false} = {}) {
  const query = currentQaSearchQuery();
  let matchedItems = 0;
  let matchCount = 0;
  let firstMatch = null;

  Array.from(els.messages.children).forEach(item => {
    clearQaSearchHighlights(item);
    const count = countTextMatches(item.textContent, query);
    const matched = !query || count > 0;
    item.hidden = !matched;
    item.classList.toggle("qa-search-hit", Boolean(query && matched));
    if (query && matched) {
      matchedItems += 1;
      matchCount += highlightQaText(item, query);
      if (!firstMatch) firstMatch = item;
    }
  });

  els.messages.classList.toggle("qa-searching", Boolean(query));
  els.messages.classList.toggle("qa-search-no-match", Boolean(query && matchedItems === 0));
  if (els.qaSearchStatus) {
    els.qaSearchStatus.textContent = query
      ? (matchedItems ? `${matchedItems} 条 / ${matchCount} 处` : "无匹配")
      : "";
  }
  if (scrollToFirst) scrollQaItemIntoView(firstMatch);
  return {query, matchedItems, matchCount, firstMatch};
}

function extractEventText(event) {
  return event.text || event.delta || event.transcript || event.content || "";
}

function extractLogid(payload) {
  if (!payload) return "";
  return payload.logid || payload.log_id || payload["X-Tt-Logid"] || payload["x-tt-logid"] || "";
}

function updateCurrentLogid(payload) {
  const logid = extractLogid(payload);
  if (!logid) return;
  currentLogid = logid;
  if (currentTurn && currentTurn.logidEl) {
    currentTurn.logidEl.textContent = `logid: ${currentLogid}`;
  }
  if (currentTurn && currentTurn.record) currentTurn.record.logid = currentLogid;
}

/* ------------------------- 通话记录 / 耗时统计 ------------------------- */

function newTranscriptRecord() {
  return {
    index: transcript.length + 1,
    logid: "",
    question: "",
    answer: "",
    toolCalls: [],
    usage: "",
    startedAt: Date.now(),
    timing: {speechAt: 0, askAt: 0, thinkAt: 0, firstTextAt: 0, firstAudioAt: 0, doneAt: 0},
  };
}

function formatDuration(ms) {
  if (!Number.isFinite(ms) || ms < 0) return "-";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

/* 一行轮次统计：总耗时统一以「判停」（thinkAt）为起点，
   对上行的模型来说这才是后端真正开始出字的时刻。 */
function turnStatsText(record) {
  if (!record) return "";
  const t = record.timing;
  const parts = [`轮次 ${record.index}`];
  if (t.thinkAt) {
    if (t.firstTextAt) parts.push(`首字 ${formatDuration(t.firstTextAt - t.thinkAt)}`);
    if (t.firstAudioAt) parts.push(`首播 ${formatDuration(t.firstAudioAt - t.thinkAt)}`);
    parts.push(t.doneAt ? `总 ${formatDuration(t.doneAt - t.thinkAt)}` : "回复中");
  } else if (t.speechAt) {
    parts.push("收音中");
  } else {
    parts.push("等待中");
  }
  return parts.join(" · ");
}

function syncTurnStats(turn = currentTurn || lastTurn) {
  if (!turn || !turn.record || !turn.statsEl) return;
  turn.statsEl.textContent = turnStatsText(turn.record);
  turn.statsEl.classList.toggle("is-live", !turn.record.timing.doneAt);
}

function transcriptHasContent() {
  return transcript.some(record => record.question || record.answer);
}

function transcriptFilename(ext) {
  const d = new Date();
  const pad = value => String(value).padStart(2, "0");
  const stamp = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}`;
  return `qwen-omni-call-${stamp}.${ext}`;
}

function transcriptToMarkdown() {
  const used = transcript.filter(record => record.question || record.answer);
  const lines = [
    "# Qwen3-Omni 实时语音通话记录",
    "",
    `- 导出时间：${new Date().toLocaleString("zh-CN", {hour12: false})}`,
    `- 模型：${FIXED_MODEL}`,
    `- 音色：${els.voice.value}`,
    `- 轮次数：${used.length}`,
    "",
  ];
  used.forEach(record => {
    lines.push(`## 轮次 ${record.index}`, "");
    if (record.question) lines.push("**用户**", "", record.question, "");
    if (record.answer) lines.push("**助手**", "", record.answer, "");
    const meta = [turnStatsText(record)];
    if (record.logid) meta.push(`logid: ${record.logid}`);
    if (record.usage) meta.push(record.usage);
    lines.push(`> ${meta.join(" · ")}`, "");
    record.toolCalls.forEach(call => {
      lines.push(`### 工具调用 ${call.name}`, "");
      lines.push("参数：", "", "```json", formatJsonBlock(call.arguments), "```", "");
      lines.push("结果：", "", "```json", formatJsonBlock(call.result), "```", "");
    });
  });
  lines.push("---", "", "由 Qwen3-Omni Web Demo 导出。");
  return lines.join("\n");
}

function transcriptToJson() {
  return JSON.stringify({
    exported_at: new Date().toISOString(),
    model: FIXED_MODEL,
    voice: els.voice.value,
    turn_count: transcript.length,
    turns: transcript.map(record => {
      const t = record.timing;
      const since = key => (t.thinkAt && t[key]) ? t[key] - t.thinkAt : null;
      return {
        index: record.index,
        logid: record.logid,
        started_at: record.startedAt ? new Date(record.startedAt).toISOString() : null,
        question: record.question,
        answer: record.answer,
        usage: record.usage,
        tool_calls: record.toolCalls,
        timing: {
          speech_at: t.speechAt ? new Date(t.speechAt).toISOString() : null,
          first_text_ms: since("firstTextAt"),
          first_audio_ms: since("firstAudioAt"),
          total_ms: since("doneAt"),
        },
      };
    }),
  }, null, 2);
}

function downloadTextFile(filename, text, mime) {
  const blob = new Blob([text], {type: mime});
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 2000);
}

function exportTranscript(format) {
  if (els.exportMenu) els.exportMenu.open = false;
  if (!transcriptHasContent()) {
    showErrorToast("没有可导出的内容", "当前还没有产生任何可导出的问答轮次。");
    return;
  }
  try {
    if (format === "json") {
      downloadTextFile(transcriptFilename("json"), transcriptToJson(), "application/json;charset=utf-8");
    } else {
      downloadTextFile(transcriptFilename("md"), transcriptToMarkdown(), "text/markdown;charset=utf-8");
    }
    addMessage("tool", `已导出通话记录（${format === "json" ? "JSON" : "Markdown"}），共 ${transcript.filter(r => r.question || r.answer).length} 轮`);
  } catch (err) {
    showErrorToast("导出通话记录失败", err);
  }
}

/* --------------------------------------------------------------------- */

function ensureTurn() {
  showCallPage();
  if (currentTurn && !currentTurn.done) return currentTurn;
  const card = document.createElement("div");
  card.className = "qa-card";
  card.innerHTML = [
    `<div class="qa-meta qa-logid">logid: ${currentLogid || "等待中"}</div>`,
    '<div class="qa-stats"></div>',
    '<div class="qa-row">',
    '<div class="qa-label">Q</div>',
    '<div class="qa-text question">正在听...</div>',
    '</div>',
    '<div class="qa-row">',
    '<div class="qa-label">A</div>',
    '<div class="qa-text answer">等待提问...</div>',
    '</div>',
  ].join("");
  els.messages.appendChild(card);
  applyQaSearch();
  scrollMessagesAfterQaUpdate();
  const record = newTranscriptRecord();
  record.logid = currentLogid || "";
  transcript.push(record);
  currentTurn = {
    card,
    record,
    logidEl: card.querySelector(".qa-logid"),
    statsEl: card.querySelector(".qa-stats"),
    questionEl: card.querySelector(".question"),
    answerEl: card.querySelector(".answer"),
    question: "",
    answer: "",
    done: false,
  };
  syncTurnStats(currentTurn);
  lastTurn = currentTurn;
  return currentTurn;
}

function resetTurnForSpeech() {
  if (currentTurn && currentTurn.question && !currentTurn.answer) {
    currentTurn.done = false;
    markTurnSpeech(currentTurn);
    return;
  }
  if (!currentTurn || currentTurn.question || currentTurn.answer) {
    currentTurn = null;
  }
  const turn = ensureTurn();
  turn.question = "";
  turn.answer = "";
  turn.done = false;
  if (turn.record) {
    turn.record.question = "";
    turn.record.answer = "";
  }
  turn.questionEl.textContent = "正在听...";
  turn.answerEl.textContent = "等待提问...";
  markTurnSpeech(turn);
  setHeroState("正在听...", "listening");
}

/* 用户开口（transcription.started）时打点，每轮只记第一次 */
function markTurnSpeech(turn) {
  if (!turn || !turn.record) return;
  if (!turn.record.timing.speechAt) turn.record.timing.speechAt = Date.now();
  syncTurnStats(turn);
}

function formatJsonBlock(value) {
  const displayValue = reorderJsonForDisplay(value);
  if (typeof displayValue === "string") return displayValue;
  return JSON.stringify(displayValue, null, 2);
}

function reorderJsonForDisplay(value) {
  if (typeof value === "string") {
    try {
      return reorderJsonForDisplay(JSON.parse(value));
    } catch {
      return value;
    }
  }
  if (Array.isArray(value)) return value.map(item => reorderJsonForDisplay(item));
  if (!value || typeof value !== "object") return value;

  let keys = Object.keys(value);
  if (keys.includes("id") && keys.includes("model")) {
    keys = keys.filter(key => key !== "id");
    keys.splice(keys.indexOf("model"), 0, "id");
  }

  return keys.reduce((ordered, key) => {
    ordered[key] = reorderJsonForDisplay(value[key]);
    return ordered;
  }, {});
}

function addJsonDetailsMessage(title, value) {
  const div = document.createElement("div");
  div.className = "bubble tool json-bubble";

  const details = document.createElement("details");
  details.className = "json-details";

  const summary = document.createElement("summary");
  summary.textContent = `${title} · 查看 JSON`;

  const pre = document.createElement("pre");
  pre.textContent = formatJsonBlock(value);

  details.append(summary, pre);
  div.appendChild(details);
  els.messages.appendChild(div);
  applyQaSearch();
  scrollMessagesAfterQaUpdate();
  return div;
}

function appendToolResult(msg) {
  if (!currentTurn) {
    addMessage("tool", `FC ${msg.name}\nargs: ${msg.arguments}\nresult: ${JSON.stringify(msg.result, null, 2)}`);
    return;
  }
  if (currentTurn.record) {
    currentTurn.record.toolCalls.push({
      name: msg.name || "tool",
      arguments: msg.arguments,
      result: msg.result,
    });
  }

  const details = document.createElement("details");
  details.className = "qa-tool";

  const summary = document.createElement("summary");
  summary.textContent = `FC ${msg.name || "tool"} · 查看 JSON`;

  const pre = document.createElement("pre");
  pre.textContent = [
    "args:",
    formatJsonBlock(msg.arguments),
    "",
    "result:",
    formatJsonBlock(msg.result),
  ].join("\n");

  details.append(summary, pre);
  currentTurn.card.appendChild(details);
  applyQaSearch();
  scrollMessagesAfterQaUpdate();
}

/* ---------------- 危险工具的二次确认 ----------------
   语音场景里 VAD 误判、背景人声、甚至电视里的对白都可能触发工具调用，
   所以「会改外部状态」的工具必须由人在界面上点一下才真正执行。
   卡片只负责收集这一次授权，真正的动作在后端 /api/tool_confirm 里完成。 */
const toolConfirmCards = new Map();

function friendlyToolName(name) {
  const raw = String(name || "tool");
  // mcp__服务名__工具名 —— 展示时把前缀拆开更好读
  const parts = raw.split("__");
  return parts.length >= 3 && parts[0] === "mcp" ? `${parts[2]}（来自 ${parts[1]}）` : raw;
}

function showToolConfirmCard(msg) {
  const token = String(msg.token || "");
  if (!token) return;
  const card = document.createElement("div");
  card.className = "tool-confirm";

  const title = document.createElement("div");
  title.className = "tool-confirm-title";
  title.textContent = `需要你确认：${friendlyToolName(msg.name)}`;

  const hint = document.createElement("div");
  hint.className = "tool-confirm-hint";
  const seconds = Math.round(Number(msg.expires_in) || 0);
  hint.textContent = seconds
    ? `这个工具会修改外部内容，${seconds} 秒内没有确认就自动取消。`
    : "这个工具会修改外部内容。";

  const pre = document.createElement("pre");
  pre.className = "tool-confirm-args";
  pre.textContent = formatJsonBlock(msg.arguments);

  const actions = document.createElement("div");
  actions.className = "tool-confirm-actions";

  const approve = document.createElement("button");
  approve.type = "button";
  approve.className = "tool-confirm-btn primary";
  approve.textContent = "确认执行";

  const deny = document.createElement("button");
  deny.type = "button";
  deny.className = "tool-confirm-btn";
  deny.textContent = "拒绝";

  const status = document.createElement("span");
  status.className = "tool-confirm-status";

  approve.addEventListener("click", () => settleToolConfirm(token, true));
  deny.addEventListener("click", () => settleToolConfirm(token, false));

  actions.append(approve, deny, status);
  card.append(title, hint, pre, actions);
  els.messages.appendChild(card);
  toolConfirmCards.set(token, {card, approve, deny, status});
  applyQaSearch();
  scrollMessagesAfterQaUpdate();
}

async function settleToolConfirm(token, approve) {
  const entry = toolConfirmCards.get(token);
  if (!entry) return;   // 已经超时或被别的路径处理掉了
  entry.approve.disabled = true;
  entry.deny.disabled = true;
  entry.status.textContent = approve ? "已确认，正在执行…" : "已拒绝";
  try {
    await postJSON("/api/tool_confirm", {session_id: sessionId, token, approve},
      {name: "tool.confirm", detail: approve ? "approve" : "deny"});
    if (!approve) markToolConfirmDone(token, "已拒绝", "denied");
  } catch (err) {
    // 失败就把按钮还回去，让用户能重试，而不是留一张点不动的卡片
    entry.status.textContent = `操作失败：${err.message}`;
    entry.approve.disabled = false;
    entry.deny.disabled = false;
  }
}

function markToolConfirmDone(token, text, state) {
  const key = String(token || "");
  const entry = toolConfirmCards.get(key);
  if (!entry) return;
  entry.approve.disabled = true;
  entry.deny.disabled = true;
  entry.status.textContent = text;
  entry.card.classList.add("is-done");
  if (state) entry.card.classList.add(`is-${state}`);
  toolConfirmCards.delete(key);
}

function clearToolConfirmCards(reason) {
  toolConfirmCards.forEach((entry, token) => {
    entry.approve.disabled = true;
    entry.deny.disabled = true;
    entry.status.textContent = reason || "会话已结束";
    entry.card.classList.add("is-done", "is-expired");
  });
  toolConfirmCards.clear();
}

function firstUsageObject(value, depth = 0) {
  if (!value || typeof value !== "object" || depth > 5) return null;
  if (value.usage && typeof value.usage === "object") return value.usage;
  for (const child of Object.values(value)) {
    const usage = firstUsageObject(child, depth + 1);
    if (usage) return usage;
  }
  return null;
}

function flattenUsage(value, prefix = "", rows = []) {
  if (!value || typeof value !== "object") return rows;
  Object.entries(value).forEach(([key, child]) => {
    const path = prefix ? `${prefix}.${key}` : key;
    if (child && typeof child === "object" && !Array.isArray(child)) {
      flattenUsage(child, path, rows);
    } else if (child !== undefined && child !== null && child !== "") {
      rows.push([path, child]);
    }
  });
  return rows;
}

function responseDoneUsageSummary(event) {
  const usage = firstUsageObject(event);
  if (!usage) return "";
  const rows = flattenUsage(usage);
  const preferred = ["total_tokens", "input_tokens", "output_tokens", "text_tokens", "audio_tokens"];
  const selected = [];
  preferred.forEach(name => {
    const row = rows.find(([path]) => path === name || path.endsWith(`.${name}`));
    if (row && !selected.some(([path]) => path === row[0])) selected.push(row);
  });
  rows.forEach(row => {
    if (selected.length < 6 && !selected.some(([path]) => path === row[0])) selected.push(row);
  });
  return selected.slice(0, 6).map(([path, value]) => `${path}=${value}`).join(" ");
}

function appendResponseDoneDetails(event) {
  const turn = currentTurn || lastTurn;
  if (!turn || !turn.card) {
    addJsonDetailsMessage("response.done", event);
    return;
  }
  const details = document.createElement("details");
  details.className = "qa-tool";

  const summary = document.createElement("summary");
  const usageSummary = responseDoneUsageSummary(event);
  summary.textContent = `response.done${usageSummary ? ` · ${usageSummary}` : ""} · 查看 JSON`;

  const pre = document.createElement("pre");
  pre.textContent = formatJsonBlock(event);

  details.append(summary, pre);
  turn.card.appendChild(details);
  applyQaSearch();
  scrollMessagesAfterQaUpdate();
}

function setQuestion(text) {
  const turn = ensureTurn();
  if (text) turn.question = text;
  turn.questionEl.textContent = turn.question || "未获取到识别文本";
  if (turn.record) {
    turn.record.question = turn.question;
    if (turn.question && !turn.record.timing.askAt) turn.record.timing.askAt = Date.now();
  }
  syncTurnStats(turn);
  applyQaSearch();
  scrollMessagesAfterQaUpdate();
}

function setThinking() {
  const turn = ensureTurn();
  turn.answerEl.textContent = "正在思考...";
  if (turn.record && !turn.record.timing.thinkAt) turn.record.timing.thinkAt = Date.now();
  syncTurnStats(turn);
  setHeroState("正在思考...", "thinking");
}

function appendAnswer(text) {
  if (!text) return;
  const turn = ensureTurn();
  turn.answer += text;
  turn.answerEl.textContent = turn.answer;
  if (turn.record) {
    turn.record.answer = turn.answer;
    if (!turn.record.timing.firstTextAt) turn.record.timing.firstTextAt = Date.now();
  }
  syncTurnStats(turn);
  setHeroState("正在回复...", "ok");
  applyQaSearch();
  scrollMessagesAfterQaUpdate();
}

function finishAnswer(text) {
  const turn = ensureTurn();
  if (text) turn.answer = text;
  turn.answerEl.textContent = turn.answer || "未获取到回复文本";
  turn.done = true;
  if (turn.record) {
    turn.record.answer = turn.answer;
    if (!turn.record.timing.thinkAt) turn.record.timing.thinkAt = Date.now();
    if (!turn.record.timing.doneAt) turn.record.timing.doneAt = Date.now();
  }
  syncTurnStats(turn);
  lastTurn = turn;
  currentTurn = null;
  setHeroState(recording && !micMuted ? "正在听..." : "已连接", sessionId ? (recording && !micMuted ? "listening" : "ok") : "");
  applyQaSearch();
  scrollMessagesAfterQaUpdate();
}

function formatClock(ts = Date.now()) {
  const date = new Date(ts);
  return date.toLocaleTimeString("zh-CN", {hour12: false}) + `.${String(date.getMilliseconds()).padStart(3, "0")}`;
}

function summarizePayload(payload) {
  if (!payload) return "";
  const parts = [];
  if (payload.event_id) parts.push(`event_id=${payload.event_id}`);
  if (payload.session_id) parts.push(`session=${String(payload.session_id).slice(0, 8)}`);
  if (payload.logid) parts.push(`logid=${payload.logid}`);
  if (payload.status) parts.push(`status=${payload.status}`);
  const text = extractEventText(payload);
  if (text) parts.push(`text=${JSON.stringify(text.length > 80 ? text.slice(0, 80) + "..." : text)}`);
  if (payload.audio) parts.push(`audio_b64=${payload.audio.length}`);
  if (payload.items) parts.push(`items=${Array.isArray(payload.items) ? payload.items.length : 1}`);
  if (payload.name) parts.push(`name=${payload.name}`);
  if (payload.message) parts.push(`message=${JSON.stringify(payload.message)}`);
  return parts.join(" ");
}

function summarizeResponseTextDone(event) {
  const parts = [];
  if (event.event_id) parts.push(`event_id=${event.event_id}`);
  if (event.logid) parts.push(`logid=${event.logid}`);
  const text = extractEventText(event);
  if (text) {
    parts.push(`text_chars=${text.length}`);
    parts.push(`preview=${JSON.stringify(text.length > 60 ? text.slice(0, 60) + "..." : text)}`);
  }
  return parts.join(" ");
}

function logEventLine(direction, name, detail = "") {
  const arrow = direction === "up" ? "↑" : direction === "down" ? "↓" : "•";
  eventLines.push(`${formatClock()} ${arrow} ${name}${detail ? `  ${detail}` : ""}`);
  if (eventLines.length > 300) eventLines = eventLines.slice(-300);
  els.events.textContent = eventLines.join("\n");
  els.events.scrollTop = els.events.scrollHeight;
}

function logResponseTextDelta(event) {
  const text = extractEventText(event);
  responseTextDeltaCount += 1;
  responseTextDeltaChars += text.length;

  const now = Date.now();
  if (now - lastResponseTextEventAt < RESPONSE_TEXT_LOG_INTERVAL_MS) return;

  logEventLine("down", event.type || "response.output_text.delta", `chunk_nums=${responseTextDeltaCount} text_chars=${responseTextDeltaChars}`);
  responseTextDeltaCount = 0;
  responseTextDeltaChars = 0;
  lastResponseTextEventAt = now;
}

function flushResponseTextDelta() {
  if (responseTextDeltaCount <= 0) return;
  logEventLine("down", "response.output_text.delta", `chunk_nums=${responseTextDeltaCount} text_chars=${responseTextDeltaChars}`);
  responseTextDeltaCount = 0;
  responseTextDeltaChars = 0;
}

function resetResponseAudioDelta() {
  responseAudioDeltaCount = 0;
  responseAudioDeltaBytes = 0;
  lastResponseAudioEventAt = Date.now();
}

function logResponseAudioDelta(event) {
  const audio = event.audio || event.delta || "";
  responseAudioDeltaCount += 1;
  responseAudioDeltaBytes += audio.length;

  const now = Date.now();
  if (now - lastResponseAudioEventAt < RESPONSE_AUDIO_LOG_INTERVAL_MS) return;

  logEventLine("down", event.type || "response.output_audio.delta", `chunk_nums=${responseAudioDeltaCount} audio_b64=${responseAudioDeltaBytes}`);
  responseAudioDeltaCount = 0;
  responseAudioDeltaBytes = 0;
  lastResponseAudioEventAt = now;
}

function flushResponseAudioDelta() {
  if (responseAudioDeltaCount <= 0) return;
  logEventLine("down", "response.output_audio.delta", `chunk_nums=${responseAudioDeltaCount} audio_b64=${responseAudioDeltaBytes}`);
  responseAudioDeltaCount = 0;
  responseAudioDeltaBytes = 0;
}

function logInboundEvent(msg) {
  if (msg.type === "local.outgoing") {
    const event = msg.event || {};
    logEventLine("up", event.type || "local.outgoing", JSON.stringify(event));
    return;
  }
  if (msg.type === "upstream.event") {
    const event = msg.event || {};
    updateCurrentLogid(event);
    if (event.type === "response.output_text.delta") {
      logResponseTextDelta(event);
      return;
    }
    if (event.type === "response.output_text.done") {
      flushResponseTextDelta();
      logEventLine("down", event.type, summarizeResponseTextDone(event));
      return;
    }
    if (event.type === "response.output_audio.started") {
      resetResponseAudioDelta();
      logEventLine("down", event.type, summarizePayload(event));
      return;
    }
    if (event.type === "response.output_audio.delta") {
      logResponseAudioDelta(event);
      return;
    }
    if (event.type === "response.output_audio.done") {
      flushResponseAudioDelta();
      lastResponseAudioEventAt = 0;
      logEventLine("down", event.type, summarizePayload(event));
      return;
    }
    logEventLine("down", event.type || "upstream.event", summarizePayload(event));
    return;
  }
  updateCurrentLogid(msg);
  logEventLine("local", msg.type || "local.event", summarizePayload(msg));
}

function parseToolsJson() {
  if (!els.enableTools.checked) return [];
  const parsed = validateToolsJson();
  return parsed;
}

function currentToolsConfig() {
  const tools = parseToolsJson();
  return {
    enable_tools: els.enableTools.checked,
    tool_root: currentToolRoot(),
    tools,
  };
}

function currentToolRoot() {
  // 本地文件 FC 的根目录由后端在启动时决定（默认用户桌面），前端不再单独配置。
  return "";
}


function microphoneErrorMessage(err) {
  const raw = err && (err.name || err.message) ? `${err.name || ""} ${err.message || ""}`.trim() : String(err);
  if (/NotAllowed|Permission|denied/i.test(raw)) {
    return [
      "麦克风权限被拒绝。",
      "请在浏览器地址栏/站点设置里允许 http://127.0.0.1:8765 使用麦克风。",
      "如果仍失败，请到 macOS 系统设置 > 隐私与安全性 > 麦克风，允许当前浏览器使用麦克风，然后刷新页面重新连接。",
    ].join("\n");
  }
  if (/NotFound|DevicesNotFound/i.test(raw)) {
    return "没有检测到可用麦克风，请确认系统输入设备可用。";
  }
  return `record failed: ${err.message || raw}`;
}

function bytesToBase64(bytes) {
  let binary = "";
  const step = 0x8000;
  for (let i = 0; i < bytes.length; i += step) {
    binary += String.fromCharCode(...bytes.subarray(i, i + step));
  }
  return btoa(binary);
}

function downsampleToPcm16(input, inputRate, targetRate = 16000) {
  const ratio = inputRate / targetRate;
  const length = Math.floor(input.length / ratio);
  const out = new Int16Array(length);
  for (let i = 0; i < length; i += 1) {
    const start = Math.floor(i * ratio);
    const end = Math.min(Math.floor((i + 1) * ratio), input.length);
    let sum = 0;
    for (let j = start; j < end; j += 1) sum += input[j];
    const sample = Math.max(-1, Math.min(1, sum / Math.max(1, end - start)));
    out[i] = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
  }
  return new Uint8Array(out.buffer);
}

function queueAudio(bytes) {
  if (!sessionId || !bytes.length) return;

  // 背压闸门：在途帧数到上限说明上行已经堵住。
  // 处理方式不是"丢一帧了事"，而是整条链重来 —— 原因见下。
  if (inflightAudioFrames >= MAX_INFLIGHT_AUDIO_FRAMES) {
    // 只丢新帧是不够的：发送链是串行的，旧帧还在一个接一个地超时，
    // 新帧排在它们后面得等上几十秒，那段时间麦克风等于哑的。
    // 所以放弃整条旧链，换一条新链重新来。
    const abandoned = inflightAudioFrames;
    droppedAudioFrames += abandoned;
    const now = Date.now();
    if (now - lastAudioDropLogAt > 2000) {
      lastAudioDropLogAt = now;
      logEventLine("up", "input_audio_buffer.drop",
        `abandoned_frames=${abandoned} dropped_total=${droppedAudioFrames}`);
      addMessage("tool",
        `上行拥塞，已丢弃 ${abandoned} 帧音频（约 ${abandoned * INPUT_AUDIO_FRAME_MS}ms）并重置发送队列`);
    }
    audioSendGeneration += 1;
    sendChain = Promise.resolve();
    inflightAudioFrames = 0;
    // 这里不 return —— 重置后空间已经腾出来，当前这帧照常发出去。
    // 重置后计数归零，所以不会每帧都触发重置。
  }

  const payload = {session_id: sessionId, audio: bytesToBase64(bytes)};
  audioAppendCount += 1;
  audioAppendBytes += bytes.length;
  const now = Date.now();
  const shouldLog = now - lastAudioEventAt > 1000;
  if (shouldLog) {
    logEventLine("up", "input_audio_buffer.append", `chunk_nums=${audioAppendCount} pcm_bytes=${audioAppendBytes}`);
    lastAudioEventAt = now;
    audioAppendCount = 0;
    audioAppendBytes = 0;
  }
  inflightAudioFrames += 1;
  const generation = audioSendGeneration;
  sendChain = sendChain
    .then(() => {
      // 所在链路可能已经被拥塞重置作废：作废就别发了，
      // 否则旧音频会和重置后发出的新音频交错，PCM 乱序听起来就是噪声。
      if (generation !== audioSendGeneration) return null;
      return postJSON("/api/audio", payload, null, {timeoutMs: AUDIO_POST_TIMEOUT_MS});
    })
    .catch(err => {
      // 上行出问题时错误是连着的，节流一下免得把消息区刷满
      const t = Date.now();
      if (t - lastAudioErrorLogAt > 2000) {
        lastAudioErrorLogAt = t;
        addMessage("tool", `audio send failed: ${err.message}`);
      }
    })
    .finally(() => {
      // 只有同一代的帧才记自己的账；旧代的残留回调不能动新代的计数
      if (generation === audioSendGeneration) {
        inflightAudioFrames = Math.max(0, inflightAudioFrames - 1);
      }
    });
}

function flushAudioAppendEvent() {
  if (audioAppendCount <= 0) return;
  logEventLine("up", "input_audio_buffer.append", `chunk_nums=${audioAppendCount} pcm_bytes=${audioAppendBytes}`);
  audioAppendCount = 0;
  audioAppendBytes = 0;
}

async function forceCommitAudio(detail = "强制判停") {
  if (!sessionId) return;
  const shouldRestartPump = Boolean(audioFrameTimer && recording);
  stopInputAudioFramePump({clearBuffer: false});
  await drainInputAudioBuffer();
  await sendChain;
  flushAudioAppendEvent();
  await postJSON(
    "/api/audio_commit",
    {session_id: sessionId},
    {name: "input_audio_buffer.commit", detail}
  ).catch(err => addMessage("tool", err.message));
  if (shouldRestartPump && sessionId) startInputAudioFramePump();
}

async function startRecording() {
  if (recording || !sessionId) return;
  clearInputAudioBuffer();
  mediaStream = await navigator.mediaDevices.getUserMedia({
    audio: {
      channelCount: 1,
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true,
    },
  });
  recorderContext = new AudioContext();
  recorderSource = recorderContext.createMediaStreamSource(mediaStream);
  recorderNode = recorderContext.createScriptProcessor(RECORDER_BUFFER_SIZE, 1, 1);
  recorderNode.onaudioprocess = event => {
    if (!recording) return;
    if (micMuted) return;
    const input = event.inputBuffer.getChannelData(0);
    let sumSquares = 0;
    for (let i = 0; i < input.length; i += 1) sumSquares += input[i] * input[i];
    const rms = Math.sqrt(sumSquares / input.length);
    // ×4.5 是把语音 RMS 拉到 0.2~0.9 的经验系数（已开 AGC）
    pushWaveformLevel(rms * 4.5);
    appendInputAudio(downsampleToPcm16(input, recorderContext.sampleRate));
  };
  recorderSource.connect(recorderNode);
  recorderNode.connect(recorderContext.destination);
  recording = true;
  resetWaveform({idle: false});
  startInputAudioFramePump();
  updateMicUi();
  showCallPage();
  setHeroState(micMuted ? "麦克风已关" : "正在听...", micMuted ? "ok" : "listening");
}

async function stopRecording({commit = true, hint = "连接后自动请求麦克风权限，全双工持续上行"} = {}) {
  const hadRecorder = recording || mediaStream || recorderContext || recorderNode || recorderSource || audioFrameTimer;
  if (!hadRecorder) {
    stopInputAudioFramePump();
    updateMicUi();
    els.recordHint.textContent = hint;
    resetWaveform();
    return;
  }
  recording = false;
  els.recordHint.textContent = commit ? "正在提交音频" : hint;
  resetWaveform();
  stopInputAudioFramePump({clearBuffer: false});
  updateMicUi();
  if (sessionId) setHeroState("已连接", "ok");
  if (recorderNode) recorderNode.disconnect();
  if (recorderSource) recorderSource.disconnect();
  if (mediaStream) mediaStream.getTracks().forEach(track => track.stop());
  if (recorderContext) await recorderContext.close().catch(() => {});
  recorderNode = null;
  recorderSource = null;
  mediaStream = null;
  recorderContext = null;
  if (commit) {
    await forceCommitAudio("停止收音");
    els.recordHint.textContent = hint;
  } else {
    clearInputAudioBuffer();
  }
}

async function toggleMicrophone() {
  if (!sessionId) return;
  micMuted = !micMuted;
  updateMicUi();
  if (micMuted) {
    resetWaveform();
    clearInputAudioBuffer();
    startInputAudioFramePump();
    setHeroState("麦克风已关", "ok");
    addMessage("tool", "麦克风已关闭，客户端仍每 20ms 发送一包静音帧保持上行。");
    return;
  }
  clearInputAudioBuffer();
  startInputAudioFramePump();
  setHeroState("正在听...", "listening");
  if (!recording) await startRecording();
}

function base64ToBytes(b64) {
  const raw = atob(b64);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i += 1) out[i] = raw.charCodeAt(i);
  return out;
}

class PcmStreamPlayer {
  constructor(sampleRate = 24000) {
    this.sampleRate = sampleRate;
    this.ctx = null;
    this.nextPlayTime = 0;
  }

  async start() {
    if (!this.ctx || this.ctx.state === "closed") {
      this.ctx = new AudioContext({sampleRate: this.sampleRate});
    }
    if (this.ctx.state === "suspended") await this.ctx.resume();
    this.nextPlayTime = Math.max(this.ctx.currentTime + 0.04, this.nextPlayTime || 0);
  }

  async enqueue(bytes) {
    if (!bytes || bytes.length < 2) return;
    await this.start();
    const samples = Math.floor(bytes.length / 2);
    const buffer = this.ctx.createBuffer(1, samples, this.sampleRate);
    const channel = buffer.getChannelData(0);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    for (let i = 0; i < samples; i += 1) {
      channel[i] = view.getInt16(i * 2, true) / 32768;
    }
    const sourceNode = this.ctx.createBufferSource();
    sourceNode.buffer = buffer;
    sourceNode.connect(this.ctx.destination);
    const startAt = Math.max(this.nextPlayTime, this.ctx.currentTime + 0.01);
    sourceNode.start(startAt);
    this.nextPlayTime = startAt + buffer.duration;
  }

  async stop() {
    if (!this.ctx || this.ctx.state === "closed") return;
    await this.ctx.close().catch(() => {});
    this.ctx = null;
    this.nextPlayTime = 0;
  }
}

// Qwen3-Omni 经 vLLM-Omni 全双工通道输出的下行音频固定为 PCM16 / 24kHz。
const OUTPUT_SAMPLE_RATE = 24000;

function createOutputAudioPlayer() {
  return new PcmStreamPlayer(OUTPUT_SAMPLE_RATE);
}

function enqueueOutputAudio(event) {
  if (!pcmPlayer) pcmPlayer = createOutputAudioPlayer();
  const audio = event.audio || event.delta || "";
  if (!audio) return;
  pcmPlayer.enqueue(base64ToBytes(audio)).catch(err => {
    addMessage("tool", `audio playback failed: ${err.message}`);
    showErrorToast("音频播放失败", err);
  });
}

function handleUpstream(msg) {
  const event = msg.event || {};
  switch (event.type) {
    case "conversation.item.input_audio_transcription.started":
      if (pcmPlayer) pcmPlayer.stop().catch(() => {});
      pcmPlayer = null;
      resetTurnForSpeech();
      break;
    case "conversation.item.input_audio_transcription.delta":
      setQuestion(extractEventText(event));
      break;
    case "conversation.item.input_audio_transcription.completed":
      setQuestion(extractEventText(event));
      setThinking();
      break;
    case "conversation.item.input_audio_transcription.failed":
      setQuestion("语音识别失败");
      setHeroState(recording && !micMuted ? "正在听..." : "已连接", recording && !micMuted ? "listening" : "ok");
      break;
    case "response.output_text.delta":
      appendAnswer(extractEventText(event));
      break;
    case "response.output_text.done":
      finishAnswer(extractEventText(event));
      break;
    case "response.done": {
      const turn = currentTurn || lastTurn;
      if (turn && turn.record) {
        turn.record.timing.doneAt = Date.now();
        turn.record.usage = responseDoneUsageSummary(event);
        syncTurnStats(turn);
      }
      if (currentTurn && currentTurn.answer) finishAnswer("");
      appendResponseDoneDetails(event);
      break;
    }
    case "response.output_audio.started":
      if (pcmPlayer) pcmPlayer.stop().catch(() => {});
      pcmPlayer = createOutputAudioPlayer();
      break;
    case "response.output_audio.delta": {
      // 首包音频延迟：从判停到第一段声音可以播放
      const turn = currentTurn || lastTurn;
      if (turn && turn.record && !turn.record.timing.firstAudioAt) {
        turn.record.timing.firstAudioAt = Date.now();
        syncTurnStats(turn);
      }
      enqueueOutputAudio(event);
      break;
    }
    case "response.output_audio.done":
      if (pcmPlayer && typeof pcmPlayer.finish === "function") {
        pcmPlayer.finish().catch(err => {
          addMessage("tool", `audio playback failed: ${err.message}`);
          showErrorToast("音频播放失败", err);
        });
      }
      break;
    case "error":
      addMessage("tool", `upstream error: ${JSON.stringify(event, null, 2)}`);
      showErrorToast("上游返回错误", event.message || JSON.stringify(event, null, 2), {duration: 6000});
      break;
    default:
      break;
  }
}

function clearApiKeyError() {
  els.apiKey.classList.remove("invalid");
  els.apiKeyError.hidden = true;
}

function requireApiKey() {
  if (els.apiKey.value.trim()) {
    clearApiKeyError();
    return true;
  }
  els.apiKey.classList.add("invalid");
  els.apiKeyError.hidden = false;
  els.status.textContent = "缺少 API Key";
  els.status.classList.remove("ok");
  els.status.classList.add("error");
  setHeroState("请先填写 API Key", "error");
  els.heroConnectText.textContent = "开始通话";
  els.apiKey.focus();
  addMessage("tool", "请先填写千问 AI 平台的 API Key，再点击连接");
  showErrorToast("缺少 API Key", "API Key 由本地后端直连上游，不会写进浏览器存储");
  return false;
}

els.toggleApiKeyBtn.addEventListener("click", () => {
  const showing = els.apiKey.type === "text";
  els.apiKey.type = showing ? "password" : "text";
  // is-visible 驱动眼睛图标的开合；之前漏了这一行，图标永远不变。
  els.toggleApiKeyBtn.classList.toggle("is-visible", !showing);
  els.toggleApiKeyBtn.setAttribute("aria-label", showing ? "显示 API Key" : "隐藏 API Key");
  els.toggleApiKeyBtn.title = showing ? "显示 API Key" : "隐藏 API Key";
});

els.apiKey.addEventListener("input", clearApiKeyError);

els.heroConnectBtn.addEventListener("click", evt => {
  evt.preventDefault();
  if (els.connectBtn.disabled) return;
  if (!requireApiKey()) return;
  window.clearTimeout(heroConnectTimer);
  els.heroConnectBtn.disabled = true;
  els.heroConnectBtn.classList.add("is-starting");
  els.heroConnectText.textContent = "接通中";
  heroConnectTimer = window.setTimeout(() => {
    els.heroConnectBtn.classList.remove("is-starting");
    els.connectBtn.click();
  }, 640);
});

/* --------------------------- 会话生命周期 ---------------------------
   把「连一次会话」抽成 openSession，手动连接和自动重连走同一条路径，
   避免两套逻辑各自漂移。 */

async function teardownSession({hint = "连接已断开，实时收音已停止"} = {}) {
  await stopRecording({commit: false, hint}).catch(() => {});
  if (pcmPlayer) pcmPlayer.stop().catch(() => {});
  if (source) {
    // 先摘掉回调，否则 close() 触发的 onerror 会被当成一次真实断线
    source.onerror = null;
    source.onmessage = null;
    source.close();
  }
  source = null;
  sessionId = "";
  lastRuntimeConfigKey = "";
  autoMicStarted = false;
  // 换一代发送链：旧链里没跑完的回调靠代际号自动作废，
  // 不会把上一通会话的音频发进新会话
  audioSendGeneration += 1;
  sendChain = Promise.resolve();
  inflightAudioFrames = 0;
  droppedAudioFrames = 0;
  stopSilentAudio();
  resetWaveform();
}

async function openSession({reason = ""} = {}) {
  const tools = parseToolsJson();
  const createConfig = currentCreateConfig();
  const toolNames = tools.map(tool => tool.name || (tool.function || {}).name).filter(Boolean).join(",");
  const data = await postJSON("/api/session", {
    ...createConfig,
    api_key: els.apiKey.value.trim(),
    tool_root: currentToolRoot(),
    instructions: els.instructions.value,
    enable_tools: els.enableTools.checked,
    tools,
  }, {
    name: "session.create",
    detail: `model=${createConfig.model} voice=${createConfig.voice} tools=${tools.length}`
      + `${toolNames ? ` names=${toolNames}` : ""}${reason ? ` via=${reason}` : ""}`,
  });
  sessionId = data.session_id;
  lastRuntimeConfigKey = JSON.stringify(currentRuntimeConfig());
  currentLogid = "";
  els.recordHint.textContent = "连接中，成功后将自动请求麦克风权限";
  addMessage("tool", `session: ${sessionId}\ntool_root: ${data.tool_root}`);
  source = new EventSource(`/api/events?session_id=${encodeURIComponent(sessionId)}`);
  source.onmessage = evt => {
    let msg = null;
    try {
      msg = JSON.parse(evt.data);
    } catch (err) {
      addMessage("tool", `事件流返回了无法解析的数据：${err.message}`);
      return;
    }
    handleSessionMessage(msg);
  };
  source.onerror = onEventStreamError;
  return true;
}

function handleSessionMessage(msg) {
  logInboundEvent(msg);
  if (msg.type === "local.connected" && !autoMicStarted) {
    autoMicStarted = true;
    onSessionHealthy();
    setConnected(true);
    startRecording().catch(err => {
      recording = false;
      micMuted = true;
      clearInputAudioBuffer();
      startInputAudioFramePump();
      updateMicUi();
      resetWaveform();
      els.recordHint.textContent = "麦克风启动失败，客户端将继续每 20ms 发送静音帧";
      setHeroState("麦克风已关", "ok");
      const message = microphoneErrorMessage(err);
      addMessage("tool", `${message}\n客户端将继续每 20ms 发送静音帧保持上行。`);
      showErrorToast("麦克风启动失败", message);
    });
  }
  if (msg.type === "local.session_create") {
    addJsonDetailsMessage("session.create", msg.event || {});
  }
  if (msg.type === "local.session_extension") {
    addJsonDetailsMessage("session.create", {type: "session.create", extension: msg.extension || {}});
  }
  if (msg.type === "local.update_diff") {
    addMessage("tool", `session.update diff:\n${JSON.stringify(msg.diff, null, 2)}`);
  }
  if (msg.type === "upstream.event") handleUpstream(msg);
  if (msg.type === "upstream.event" && (msg.event || {}).type === "session.closed") {
    addMessage("tool", "session.closed ack received");
  }
  if (msg.type === "upstream.event" && (msg.event || {}).type === "session.updated") {
    addMessage("tool", "session.updated ack received");
  }
  if (msg.type === "local.tool_result") {
    appendToolResult(msg);
  }
  if (msg.type === "local.tool_confirm_request") showToolConfirmCard(msg);
  if (msg.type === "local.tool_confirm_resolved") {
    markToolConfirmDone(msg.token, msg.approved ? "已确认" : "已拒绝", msg.approved ? "approved" : "denied");
  }
  if (msg.type === "local.tool_confirm_expired") markToolConfirmDone(msg.token, "已超时取消", "expired");
  if (msg.type === "local.warning") addMessage("tool", `warning: ${msg.message}`);
  if (msg.type === "local.error") onLocalError(msg);
  if (msg.type === "local.closed") onLocalClosed();
}

function onEventStreamError() {
  if (userClosedSession) return;
  addMessage("tool", "event stream disconnected");
  teardownSession({hint: "连接中断，实时收音已停止"}).then(() => {
    if (scheduleReconnect("事件流断开")) return;
    finishFailedSession("事件流断开", "event stream disconnected");
  });
}

/* 上游 HTTP 状态码：后端优先给数字 status_code；
   老版本只给 "HTTP/1.1 401 Unauthorized" 这类状态行，这里兜底抠出三位数字。
   拿不到就返回 NaN，调用方按「可重试」处理。 */
function upstreamStatusCode(msg) {
  const direct = Number(msg && msg.status_code);
  if (Number.isFinite(direct)) return direct;
  const match = /\b(\d{3})\b/.exec(String((msg && msg.status) || ""));
  return match ? Number(match[1]) : NaN;
}

function onLocalError(msg) {
  const detail = [
    `local error: ${msg.message}`,
    msg.status ? `status: ${msg.status}` : "",
    msg.logid ? `logid: ${msg.logid}` : "",
    msg.body ? `body: ${msg.body}` : "",
  ].filter(Boolean).join("\n");
  addMessage("tool", detail);
  const status = upstreamStatusCode(msg);
  // 4xx 基本是 key / 参数 / 配额问题，重试多少次都一样；5xx 和网络类错误才值得重连
  const retryable = !(Number.isFinite(status) && status >= 400 && status < 500);
  if (retryable) {
    teardownSession({hint: "连接中断，实时收音已停止"}).then(() => {
      if (scheduleReconnect(`上游会话异常（${msg.message}）`)) return;
      finishFailedSession("通话失败", detail);
    });
    return;
  }
  finishFailedSession("通话创建失败", detail);
}

function onLocalClosed() {
  // 会话没了，挂起的确认也就没有意义了，别留一张点不动的卡片
  clearToolConfirmCards("会话已结束，此次调用已取消");
  if (userClosedSession || keepCallPageOnDisconnect) {
    finishClosedSession();
    return;
  }
  // 非用户主动关闭：先走重连，别急着把通话页切回首页造成闪动
  teardownSession({hint: "连接中断，实时收音已停止"}).then(() => {
    if (scheduleReconnect("上游会话已关闭")) return;
    finishFailedSession("会话已关闭", "上游会话关闭，且连续多次自动重连均未成功。");
  });
}

async function finishClosedSession() {
  cancelReconnect({silent: true});
  await teardownSession({hint: "连接已断开，实时收音已停止"});
  setConnected(false, {keepCallPage: keepCallPageOnDisconnect});
  keepCallPageOnDisconnect = false;
}

async function finishFailedSession(title, detail) {
  cancelReconnect({silent: true});
  await teardownSession({hint: "连接失败，实时收音已停止"});
  keepCallPageOnDisconnect = false;
  setConnected(false);
  showErrorToast(title, detail, {duration: 0, endCallOnClose: true});
}

/* --------------------------- 自动重连（指数退避） --------------------------- */

const RECONNECT_DELAYS_MS = [1000, 2500, 5000, 10000];

function clearReconnectTimers() {
  window.clearTimeout(reconnectTimer);
  window.clearInterval(reconnectTickTimer);
  reconnectTimer = null;
  reconnectTickTimer = null;
}

function updateReconnectBar(text) {
  if (!els.reconnectBar) return;
  // 提示条是悬浮的，显示时给通话卡片补一段顶部内边距，免得盖住顶部状态文案
  const app = els.reconnectBar.closest(".app");
  if (app) app.classList.toggle("reconnecting", Boolean(text));
  if (!text) {
    els.reconnectBar.hidden = true;
    return;
  }
  els.reconnectText.textContent = text;
  els.reconnectBar.hidden = false;
}

function cancelReconnect({silent = true} = {}) {
  const wasPending = Boolean(reconnectTimer) || reconnectAttempt > 0;
  clearReconnectTimers();
  reconnectAttempt = 0;
  reconnectInFlight = false;
  updateReconnectBar("");
  if (!silent && wasPending) addMessage("tool", "已取消自动重连");
}

/* 重连期间不能用 setConnected(false)——那会把通话页切回首页。
   这里只把控件置灰、保持通话页和计时器。 */
function setReconnecting() {
  els.status.textContent = "重连中";
  els.status.classList.remove("ok", "error");
  els.connectBtn.disabled = true;
  els.heroConnectBtn.disabled = true;
  els.heroConnectBtn.classList.remove("is-starting");
  els.heroConnectText.textContent = "重连中";
  els.closeBtn.disabled = true;
  els.recordBtn.disabled = true;
  els.forceCommitBtn.disabled = true;
  setHeroState("连接中断，正在重连...", "connecting");
  showCallPage();
}

function onSessionHealthy() {
  const recovered = reconnectAttempt > 0;
  clearReconnectTimers();
  reconnectAttempt = 0;
  reconnectInFlight = false;
  updateReconnectBar("");
  if (recovered) addMessage("tool", "自动重连成功，通话已恢复");
}

async function runReconnectNow() {
  if (reconnectInFlight || userClosedSession) return;
  reconnectInFlight = true;
  const total = RECONNECT_DELAYS_MS.length;
  updateReconnectBar(`正在重连（${reconnectAttempt}/${total}）…`);
  let ok = false;
  try {
    ok = await openSession({reason: `reconnect-${reconnectAttempt}`});
  } catch (err) {
    ok = false;
  }
  reconnectInFlight = false;
  if (ok) return; // 真正恢复以 local.connected 为准，在 onSessionHealthy 里收尾
  if (scheduleReconnect("重连请求未成功")) return;
  finishFailedSession("自动重连失败", "连续多次重连均未成功，请检查本地后端与网络后点「开始通话」重试。");
}

function scheduleReconnect(reason) {
  if (userClosedSession) return false;
  if (reconnectTimer || reconnectInFlight) return true;
  const total = RECONNECT_DELAYS_MS.length;

  // 次数用尽：只负责收摊，报错交给调用方的 finishFailedSession
  if (reconnectAttempt >= total) {
    updateReconnectBar("");
    clearReconnectTimers();
    reconnectAttempt = 0;
    reconnectInFlight = false;
    addMessage("tool", `自动重连已放弃：连续 ${total} 次未成功（${reason}）`);
    return false;
  }

  const delay = RECONNECT_DELAYS_MS[reconnectAttempt];
  reconnectAttempt += 1;
  reconnectDeadline = Date.now() + delay;
  setReconnecting();
  addMessage("tool", `连接中断：${reason}\n${(delay / 1000).toFixed(1)}s 后自动重连（第 ${reconnectAttempt}/${total} 次）`);
  const tick = () => {
    const left = Math.max(0, reconnectDeadline - Date.now()) / 1000;
    updateReconnectBar(`${left.toFixed(1)}s 后自动重连（${reconnectAttempt}/${total}）`);
  };
  tick();
  reconnectTickTimer = window.setInterval(tick, 100);
  reconnectTimer = window.setTimeout(() => {
    clearReconnectTimers();
    runReconnectNow();
  }, delay);
  return true;
}

els.reconnectNowBtn.addEventListener("click", () => {
  if (reconnectInFlight) return;
  clearReconnectTimers();
  if (reconnectAttempt === 0) reconnectAttempt = 1;
  runReconnectNow();
});

els.reconnectCancelBtn.addEventListener("click", () => {
  userClosedSession = true;
  cancelReconnect({silent: false});
  finishClosedSession();
});

els.connectBtn.addEventListener("click", async () => {
  if (!requireApiKey()) return;
  userClosedSession = false;
  cancelReconnect({silent: true});
  keepCallPageOnDisconnect = false;
  micMuted = false;
  updateMicUi();
  await teardownSession({hint: "准备连接，实时收音已停止"});
  setConnecting();
  try {
    currentTurn = null;
    lastTurn = null;
    await openSession({reason: "manual"});
  } catch (err) {
    addMessage("tool", err.message);
    const status = Number(err && err.status);
    const retryable = !(Number.isFinite(status) && status >= 400 && status < 500);
    if (retryable && scheduleReconnect(`本地后端未就绪（${errorText(err)}）`)) return;
    await finishFailedSession("通话创建失败", err);
  }
});

els.closeBtn.addEventListener("click", async () => {
  if (!sessionId) return;
  // 用户主动挂断：关掉自动重连，否则下一轮 onerror 会把会话又拉起来
  userClosedSession = true;
  cancelReconnect({silent: true});
  rememberQaScroll();
  keepCallPageOnDisconnect = true;
  await stopRecording({commit: false, hint: "正在断开，实时收音已停止"}).catch(() => {});
  els.closeBtn.disabled = true;
  els.callCloseBtn.disabled = true;
  els.recordBtn.disabled = true;
  els.forceCommitBtn.disabled = true;
  setHeroState("正在关闭...", "connecting");
  await postJSON("/api/close", {session_id: sessionId}, {name: "session.close"}).catch(() => {});
  els.recordHint.textContent = "等待 session.closed ack";
});

els.callCloseBtn.addEventListener("click", evt => {
  evt.preventDefault();
  if (sessionId) {
    els.closeBtn.click();
    return;
  }
  rememberQaScroll();
  userClosedSession = true;
  cancelReconnect({silent: true});
  stopRecording({commit: false}).catch(() => {});
  sessionId = "";
  lastRuntimeConfigKey = "";
  setConnected(false, {keepCallPage: true});
});

els.recordBtn.addEventListener("click", evt => {
  evt.preventDefault();
  toggleMicrophone().catch(err => {
    els.recordHint.textContent = "麦克风切换失败";
    setHeroState("麦克风切换失败", "error");
    const message = microphoneErrorMessage(err);
    addMessage("tool", message);
    showErrorToast("麦克风切换失败", message);
  });
});

els.forceCommitBtn.addEventListener("click", evt => {
  evt.preventDefault();
  forceCommitAudio("强制判停").catch(err => {
    addMessage("tool", `force commit failed: ${err.message}`);
    showErrorToast("强制判停失败", err);
  });
});

els.clearMessagesBtn.addEventListener("click", () => {
  els.messages.textContent = "";
  els.qaSearchInput.value = "";
  applyQaSearch();
  currentTurn = null;
  lastTurn = null;
  transcript = [];
});

els.exportMarkdownBtn.addEventListener("click", () => exportTranscript("md"));
els.exportJsonBtn.addEventListener("click", () => exportTranscript("json"));

// 下拉菜单点空白处收起
document.addEventListener("click", evt => {
  if (els.exportMenu && els.exportMenu.open && !els.exportMenu.contains(evt.target)) {
    els.exportMenu.open = false;
  }
});
window.addEventListener("keydown", evt => {
  if (evt.key === "Escape" && els.exportMenu && els.exportMenu.open) els.exportMenu.open = false;
});

els.qaSearchInput.addEventListener("input", () => {
  applyQaSearch({scrollToFirst: true});
});

els.clearEventsBtn.addEventListener("click", () => {
  eventLines = [];
  audioAppendCount = 0;
  audioAppendBytes = 0;
  responseTextDeltaCount = 0;
  responseTextDeltaChars = 0;
  lastResponseTextEventAt = 0;
  responseAudioDeltaCount = 0;
  responseAudioDeltaBytes = 0;
  lastResponseAudioEventAt = 0;
  els.events.textContent = "";
});

els.errorToastCloseBtn.addEventListener("click", closeErrorToast);
els.errorToast.addEventListener("click", evt => {
  if (evt.target === els.errorToast) closeErrorToast();
});
window.addEventListener("keydown", evt => {
  if (evt.key === "Escape" && !els.errorToast.hidden) closeErrorToast();
});

els.resetToolsBtn.addEventListener("click", resetToolsJson);
els.resetInstructionsBtn.addEventListener("click", resetInstructions);
els.validateToolsBtn.addEventListener("click", () => {
  try {
    validateToolsJson();
  } catch (err) {
    showErrorToast("FC Tools JSON 校验失败", err);
  }
});
els.formatToolsBtn.addEventListener("click", () => {
  try {
    validateToolsJson({format: true});
    scheduleSessionUpdate();
  } catch (err) {
    showErrorToast("FC Tools JSON 格式化失败", err);
  }
});
els.toolsJson.addEventListener("input", () => {
  setToolsJsonStatus("");
  scheduleSessionUpdate();
});

if (els.themeToggle) {
  els.themeToggle.addEventListener("click", toggleTheme);
}

// 没手动选过主题时跟随系统；选过就以用户的为准
if (window.matchMedia) {
  const themeMedia = window.matchMedia(THEME_SYSTEM_QUERY);
  const onSystemThemeChange = () => {
    if (!storedTheme()) applyTheme(systemPrefersDark() ? "dark" : "light");
  };
  if (themeMedia.addEventListener) {
    themeMedia.addEventListener("change", onSystemThemeChange);
  } else if (themeMedia.addListener) {
    themeMedia.addListener(onSystemThemeChange);
  }
}

// 引导脚本已经定好 data-theme，这里补一次 aria / title 的同步
applyTheme(currentTheme());

// 波形柱子在文件后段才声明（WAVEFORM_BARS 是 const，有 TDZ），
// 所以初始化必须放在这里，不能挪到前面的启动序列里。
initWaveform();
