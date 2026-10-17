# Android 任务说明（交给 ChatGPT）

分支 `feature/ux-fixes-and-ai-prompt`。先读 `AGENTS.md`、`docs/ux-fix-plan-2026-10.md`。
后端已经完成（提交 `2912bbd`），**本文所有任务只改 `android-app/`，不要改 `app/`、`tests/`。** 需要后端改动时停下来说明，不要自己改。

## 通用要求

- `MainActivity.java` 用代码构建界面（没有 XML 布局），一行写多条语句的紧凑风格。保持这种风格，复用已有的 `label/button/segment/field/input/card/disclosure/small/request/mutate/feedback` 等辅助方法。
- 能独立出来的纯逻辑（时间格式化、去向文案、判断函数）放到新的小类里，并在 `app/src/test/java/com/aidol/demo/` 加 JUnit 测试。参考现有的 `ModeCopy`、`InteractionCopy` 和它们的测试。
- 不引入新依赖，不把任何 Key 写进 App。
- 每个任务一个 commit，信息以 `[Android #编号]` 开头。
- 每个任务完成后在 `android-app/` 运行 `./gradlew testDebugUnitTest lintDebug assembleDebug`，贴出最后几行结果。失败就先修好，不要跳过。
- 做完一个任务就停下来汇报，等确认后再做下一个。

---

## 任务 3：时间戳精确到秒（B1 + B3）

**现状**：`MainActivity.java:1881` 的 `date()` 用 `MM-dd HH:mm`，没有年份和秒。会议原文（约 749–752 行）只显示"片段 N"。

**要求**
1. 新建 `TimeFormat.java`：把 ISO-8601 时间（带时区偏移，如 `2026-10-17T08:03:05.123+00:00`）转成设备时区的 `yyyy-MM-dd HH:mm:ss`。解析失败时原样返回，和现在一样。`date()` 改为调用它。
2. 会议原文每段显示为 `片段 N · yyyy-MM-dd HH:mm:ss`。后端每条 source 已经带 `created_at`，不需要后端改动。
3. 复制会议原文时，每段前面加 `[yyyy-MM-dd HH:mm:ss]`。
4. `TimeFormatTest`：至少覆盖 UTC 转 `Asia/Shanghai`、带毫秒、非法字符串原样返回这几种情况（测试里显式传时区，不要依赖运行机器的时区）。

**验收**：首页"最近对话"、记忆、会议列表、会议原文里的时间都显示为 `2026-10-17 16:03:05` 这样的格式。

---

## 任务 4：首页对话实时显示（B2）

**现状**
- 通话中只有气泡区上方的 `liveWords`（386–389 行，最多 4 行）显示当前这一句，不保留之前的内容。
- 每轮结果到达时（`acceptBusinessResult`，约 538–552 行）会追加气泡，但时间写的是字符串"刚刚"。通话中 `if (!repeatArmed)` 的判断让列表**不滚动**，所以新内容看不到。

**要求**
1. 结果气泡的时间改为设备当前时间（`OffsetDateTime.now()` 的 ISO 字符串，交给 `TimeFormat` 显示到秒），不再用"刚刚"；`state.transcripts` 里本地追加的那两条也一样。
2. 用户说话时，在"最近对话"列表**底部**显示一个临时的"你"气泡，内容跟随 `transcript(String)` 回调实时更新，标注"识别中"。这一轮结果到达后，用正式气泡替换它。AI 回复同理，用 `reply(String)` 回调逐步更新临时的"聆"气泡。
3. 通话中有新内容时自动滚到底部。如果用户正在往上翻看历史（离底部超过约一屏），就不要强制滚动。
4. `liveWords` 保留，仍显示当前这一句（单行摘要即可），避免改动太大。
5. 关闭了"保存转写"（`retain_transcripts=false`）时，实时气泡照样显示，但不写入 `state.transcripts`，行为和现在一致。

**验收**：开启 AI 通话连续说三句，每句话说的过程中就出现在列表底部，结束后变成带秒级时间的正式气泡，列表一直停在最新内容。

---

## 任务 6：通勤页重做（C1、C2、C3、C5、C6、C7）

**后端契约**（已上线，详见 `docs/api.md` 的"设置"一节）
- 设置新增 `home_name/home_city/home_lon/home_lat`、`work_name/work_city/work_lon/work_lat`（GCJ-02），以及 `commute_target`：`auto|home|work|custom`。
- `auto`：在家 1 公里内就去公司，在公司 1 公里内就回家；两边都不在附近时，按工作时段中点之前去公司、之后回家。
- `GET /v1/state` 的 `agent.commute`：`{target, name, basis, reason}`；`agent.route.target` 与之对应。
- `POST /v1/maps/reverse`（请求体是 WGS-84 的 `{longitude, latitude, accuracy, source:"gps"}`）现在额外返回 GCJ-02 的 `longitude/latitude`，可以**直接写入** `home_*/work_*`。
- `PUT /v1/settings` 是完整替换：先 `cloneJson(settings())` 再修改字段。

**要求**
1. **"家"和"公司"两张卡片**，取代现在单一的"今天，想去哪里？"。每张显示名称和地址（未设置时显示"未设置"）。设置方式有两种：
   - 搜索：沿用现有的 `/v1/maps/search` 和 `poiResults` 弹窗，按钮文字改成"设为家" / "设为公司"，分别写入 `home_*` / `work_*`，城市写入 `*_city`。
   - 📍 **"用当前位置"**：拿到一次有效定位后调 `/v1/maps/reverse`，把返回的 `longitude/latitude/address/city` 写入对应字段，`*_name` 保持"家" / "公司"。
2. **输入框右侧加📍按钮**：搜索输入框的同一行右侧放一个定位按钮（最小触控 48dp，`contentDescription="使用当前位置"`）。点击后自动定位，并把逆地理得到的地址和城市填进输入框和城市框，用户不用手打。
3. **城市不再默认写死"上海"**：有定位时用 reverse 返回的 city；没有时才用 `transit_city`。
4. **去向选择**：一排 `segment`："自动 / 去公司 / 回家"，对应 `commute_target=auto/work/home`。旧账号如果 `commute_target=custom`（或家和公司都没设置、只有旧的 `destination_*`），额外显示"其他目的地"选项，保持可用。
5. **路线卡**标题按 `agent.commute` 显示："去公司" / "回家" / "前往 X"；`basis=near_home` 时副标题写"你在家附近"，`near_work` 写"你在公司附近"，`time_of_day` 写"按时间推断"。`target=null` 时显示 `reason`。
6. **C5**："搜索周边"和"附近地铁站"没有定位时，不再提示"请先点击定位"，而是自动定位成功后再执行搜索。
7. **C6**：`locate()`（约 1033 行）不要再强制把设置改成 `mode=commute`、`auto_mode=false`；只上报位置。
8. **C7**：两个天气按钮合并为一个"目的地天气"，弹窗同时显示实况和预报（调用 `/v1/maps/weather` 和 `/v1/maps/weather?forecast=true`，不带 city 参数，后端会按 `agent.commute` 解析目的地）。删除依赖本机 `weatherPreferenceKey()` adcode 缓存的逻辑。
9. 文案"仅在点击时获取位置"改为与任务 7 的行为一致的说明。

**验收**：新账号在家点"用当前位置"设为家，再搜索公司地址设为公司；回到通勤页点定位，路线卡显示"去公司 · 你在家附近"和分钟数。全程不需要手动输入城市。

---

## 任务 7：通勤中持续定位 + 通话开始时自动上报位置（C4 + A3）

**要求**
1. 通勤页可见、或当前模式是 `commute` 且 AI 通话进行中时，前台持续定位：`requestLocationUpdates` 间隔约 20 秒、最小位移约 50 米，每次有效定位（精度 ≤200 米、60 秒内）都 `POST /v1/location`。后端自己会做 30 秒路线节流，客户端不用重复节流路线。
2. 离开通勤页且没有通勤通话、`onPause`、退出登录、`commute_target` 无可用去向时，停止定位。
3. 有定位权限时，**任何模式**下开始 AI 通话都先静默定位一次并上报（复用现有的 `locate(false)` 静默路径），这样 AI 查附近和路线时就有位置可用。没有权限时不弹窗打扰，保持现状。
4. 不申请后台定位权限（`ACCESS_BACKGROUND_LOCATION`），不新增前台服务类型。锁屏后持续定位不在本任务范围内，在汇报里写明这一限制。
5. 状态栏 `lastNotice` 显示"实时定位中 · 每 20 秒更新"，停止后清除。

**验收**：在通勤页开着屏幕走动，路线分钟数随位置自动更新，不需要再按按钮；切到"我的"页后定位停止（可以用 `adb shell dumpsys location` 确认没有活跃请求）。

---

## 后续任务（暂不开始）

任务 10（说话人标签显示）要等 Claude 完成供应商调研和后端字段后再交接。
