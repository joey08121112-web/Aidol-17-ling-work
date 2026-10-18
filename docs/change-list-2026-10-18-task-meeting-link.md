# 行动清单与会议联动更新清单（2026-10-18）

分支 `feat/task-meeting-link`，基于 `origin/main` `edfdbca`。**尚未提交，也未装到真机。**

## 原有链路（检查确认已通）

1. 工作模式录一场会，点结束。
2. 后端把整场原文一次交给文字大模型写纪要。纪要里的“总结任务”一项，提取有明确承诺或分配的任务、负责人、截止时间，每条都带原文依据。
3. 保存纪要时，这些任务自动写入行动任务表。重新生成不会重复；用户删掉的任务不会再出现；已完成状态会保留。
4. 工作页“行动清单”刷新后显示这些任务，可勾选、编辑、删除，有具体时间时可设提醒。

## 本次补的

- **清单卡片显示来源**：AI 生成的待办多一行“来自会议：标题 · 开始时间 ›”。点一下打开那场会议，并滚动、高亮到这条待办引用的第一句原话。只跳转，不自动播放录音。手动添加的任务没有这一行。
- **会议详情和清单联动**：会议详情里的“行动待办”改成显示行动清单中本场的任务，包括你编辑过的内容，可以直接勾选。勾选的是同一条记录，清单和提醒随之刷新；在清单里勾选，回到会议详情也是已完成。
- **任务全被删掉的会议**：详情改为只读显示纪要原始待办，并注明“已不在行动清单中”。

## 后端

- `action_tasks` 新增 `meeting_id` 列。生成纪要时写入；用户编辑任务后，任务和纪要的关联会解除，但所属会议保留。
- 旧数据升级时自动补齐：先按所属纪要匹配，找不到再按引用的原文句子匹配。
- `GET /v1/tasks`（以及新建、编辑任务的返回）增加 `meeting_id` 和 `meeting: {id, title, started_at}`。
- `GET /v1/work-meetings/{id}` 增加 `action_tasks`。
- 改动文件：`app/storage.py`；测试 `tests/test_work_meetings.py`；文档 `api.md`、`android-handoff.md`、`acceptance.md`。

## Android

- `ActionTaskBoard`：来源行，通过 `onOpenMeeting` 回调打开会议。
- `MainActivity`：
  - `openWorkMeetingAt` 打开会议，原文渲染后跳到引用句；
  - `meetingActions` / `toggleMeetingAction` 负责详情勾选，复用 `PATCH /v1/tasks/{id}`；
  - 详情刷新指纹加入 `action_tasks`。

## 暂不覆盖

- 截止时间仍是原文文字（如“周五”），不自动换算成日期，也不自动设提醒，需要在清单里编辑。
- 负责人显示“说话人N”，不跟随说话人改名（改名在 PR #9）。
- 纪要防丢失的修复（PR #7、#8）尚未合入 main。纪要没保存成功时，不会生成行动项。

## 验证

- 后端全量 **645 passed**，`compileall` 通过。新增 2 项回归测试。
- Android：`testDebugUnitTest lintDebug assembleDebug` 成功，315 项单测零失败，Lint 0 errors / 31 warnings，与基线相同。
- 未在真机验证。
