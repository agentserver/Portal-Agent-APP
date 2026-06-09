# 自动化 Boost 总设计

## 目标

在现有 Android MCP 能力基础上加入“自动化 Boost”：第一次由 Agent 正常探索手机操作，后续遇到相似任务时使用已验证动作配方快速执行，减少重复观察、重复推理和反复试错。

第一版聚焦低风险的 App 内导航和点击操作。表单填写、系统设置变更、AgentServer/Loom 工作流和用户手动录制保留扩展空间，但不作为首批必须交付范围。

## 当前项目上下文

现有项目已经具备实现 Boost 的基础：

- `McpHttpServer` 统一接收 `tools/call`，能看到所有 MCP 工具调用。
- `McpAccessibilityService` 能获取当前包名和 Activity，并执行点击、滑动、输入。
- `UiTreeTool` 能读取 UI 树，提供 text、content-desc、class、bounds 等节点信息。
- `UiTool` 提供 `ui.tap`、`ui.swipe`、`ui.click_text`、`ui.input_text`。
- `AppTool` 提供 `app.open` 和 `app.get_current_activity`。
- `AuditLogger` 已记录 MCP 调用结果。
- `AgentTask` / `AgentTaskStore` 已有任务历史概念。

Boost 应优先接在 MCP 工具层和任务历史层，而不是深度改 Claude 或 Codex 的模型输出逻辑。这样 Claude、Codex、AgentServer 后续都能共享同一套自动化能力。

## 页面结构

主页做轻量调整：

- 当前对话状态指示灯移动到 UI 中心位置，作为运行状态、Boost 状态、Agent 状态的主反馈。
- 右上角增加设置图标。
- 点击设置图标进入设置页。

设置页第一版只包含自动化相关内容，不迁移密钥、Loom、AgentServer：

```text
设置
  自动化 Boost
    总开关
    悬浮窗状态
    App 白名单
    动作配方
    候选配方
    失败与修复
    数据与隐私
```

密钥管理、Loom 和 AgentServer 暂时保留在现有入口。等应用从调试期进入稳定期后，再考虑把这些模块整合进设置页。

## 自动化页职责

自动化页不是普通历史记录页，它只管理“可复用、可验证、风险可控”的动作配方。

核心功能：

- 查看候选配方。
- 审核候选配方并命名。
- 启用、禁用、删除配方。
- 将配方或 App 加入自动白名单。
- 查看每个配方的步骤、验证条件、成功率、失败次数和最近执行时间。
- 查看 Boost 失败记录。
- 对失败配方选择保留、禁用、等待 Agent 修复。
- 合并相似候选，避免同一操作在列表里重复出现。

第一版不做复杂编辑器。用户可以修改名称、开关、白名单和风险确认；步骤级手工编辑保留为后续版本。

## 什么操作能进入自动化页

准入标准以 `2026-06-10-automation-recipe-metrics.md` 为核心指标。

简化判断：

- 操作必须可重复。
- 起点和终点必须明确。
- 成功必须可验证。
- 必须具备稳定 UI 锚点。
- 坐标不能作为唯一定位方式。
- 高风险动作不能自动 Boost。
- 动态输入、敏感输入必须参数化或排除。

适合第一版：

- 打开某个 App。
- 进入某个 App 的固定页面。
- 点击固定 Tab、菜单、按钮。
- 从系统设置中导航到某个权限页，但不直接执行高风险授权。

不适合第一版：

- 付款、删除、发送、发布。
- 输入密码、验证码、API Key、Token。
- 只有截图视觉线索、没有 UI 树锚点的操作。
- 无法判断完成状态的开放式任务。

## 动作来源

第一版支持或预留三种来源：

1. **Agent 成功执行后自动候选**
   - 任务完成后，从 MCP 工具调用轨迹提取动作骨架。
   - 自动生成候选配方，但不自动加入白名单。

2. **用户手动录制**
   - 保留方案和数据模型扩展点。
   - 暂不作为首批实现。
   - 后续录制结果必须进入审核流程。

3. **从历史任务提取**
   - 从已完成 AgentTask 和 MCP 审计记录中发现重复路径。
   - 多次相似历史可以合并成同一配方的不同候选版本。

## 相似配方去重

需要做相似操作去重，但第一版只做候选阶段的轻量去重。

去重目标：

- 避免自动化页出现多个同义候选。
- 让用户审核的是“一个操作的多个版本”，而不是多条重复记录。
- 为后续配方优化保留版本空间。

去重不应该只看用户原始文字。应综合：

- 归一化后的用户意图。
- 目标 package 和 Activity。
- 结束条件。
- 关键 UI 锚点。
- 工具调用骨架。
- 风险等级。

合并规则：

- 目标页面和结束条件相同的候选，合并为同一配方的不同版本。
- 新版本如果步骤更短、验证更稳定、成功率更高，可以标记为推荐升级版本。
- 已经启用的配方不被自动覆盖，只提示用户有更优版本。
- Boost 失败后 Agent 修复出的路径，也进入同一配方的修复候选版本。

禁止合并：

- 风险等级不同。
- 结束条件不同。
- 参数模型不同。
- 目标 App 不同。
- 一个包含发送、删除、支付、授权等高风险动作，另一个不包含。

数据模型上，`ActionRecipe` 表示一个用户可理解的自动化动作，`versions` 表示完成同一动作的不同路径。白名单绑定到配方和当前启用版本；切换版本需要保留回滚能力。

## 动作配方模型

动作配方应保存结构化信息，而不是保存一段模型回复。

建议模型：

```text
ActionRecipe
  id
  name
  enabled
  autoBoostEnabled
  riskLevel
  intentPatterns
  targetPackage
  targetActivity
  startConditions
  endConditions
  steps
  source
  sourceTaskIds
  stats
  versions
```

步骤模型：

```text
ActionStep
  id
  toolName
  argumentsTemplate
  selectors
  preconditions
  postconditions
  timeoutMs
  fallbackPolicy
```

选择器模型：

```text
UiSelector
  text
  contentDesc
  className
  bounds
  parentSummary
  screenFingerprint
  confidence
```

统计模型：

```text
RecipeStats
  successCount
  failureCount
  lastSuccessAt
  lastFailureAt
  averageDurationMs
  lastFailureReason
```

## 屏幕指纹

屏幕指纹用于判断当前页面是否适合执行某一步。第一版不需要复杂视觉算法，优先使用无障碍树摘要：

- 当前 package。
- 当前 Activity。
- 关键 text / content-desc 集合。
- 可点击节点数量。
- 可编辑节点数量。
- 顶层节点 class 摘要。

指纹用于轻量验证，不用于绝对判断。只要关键锚点匹配即可执行；如果关键锚点缺失，应停止 Boost 并回退 Agent。

## Boost 执行流程

```text
用户发起任务
  -> 匹配意图和上下文
  -> 命中已启用配方
  -> 检查 App/配方白名单
  -> 进入 boosting 状态
  -> 悬浮窗显示 Boosting
  -> 逐步执行动作
  -> 每步执行后验证
  -> 成功：输出完成结果，更新统计
  -> 失败：取消 boosting，记录失败，回退 Agent
```

命中白名单后可以自动执行；未进入白名单的配方只给出建议，不自动运行。

悬浮窗状态：

- `Boosting: 1/5` 正在执行快捷路径。
- `Boost paused` 用户暂停。
- `Boost failed, falling back to Agent` 执行失败并回退。
- `Boost completed` 快速路径完成。

碰到失败回退 Agent 时，必须删除当前运行态 `boosting` flag，避免 UI 和后续逻辑误判仍在快速路径中。

## 失败与回退

失败包括：

- 无障碍服务不可用。
- 当前包名或 Activity 不符合预期。
- 目标节点找不到。
- 验证条件失败。
- 手势超时或取消。
- 页面跳转到未预期位置。

失败策略：

- 立即停止后续步骤。
- 清除 `boosting` flag。
- 悬浮窗显示回退状态。
- 记录失败步骤和上下文。
- 将控制权交给 Agent 正常探索。
- Agent 成功后生成修复候选，不直接覆盖旧配方。

连续失败策略：

- 1 次失败：降低置信度。
- 2 次失败：暂停自动白名单。
- 3 次失败：禁用配方，等待用户审核。

## 与 Agent 的关系

Boost 不替代 Agent，而是作为 Agent 前置快速路径。

Agent 仍负责：

- 首次探索。
- Boost 失败后的恢复。
- 页面变化后的修复。
- 新任务理解。
- 高风险动作的判断和说明。

Boost 负责：

- 已知低风险路径的快速执行。
- 减少重复 UI 树读取和截图观察。
- 将稳定动作沉淀为可管理配方。

## 与 Claude / Codex 的关系

Boost 应位于 Android MCP 层上方或旁路层，不绑定 Claude 或 Codex。

原因：

- 当前项目已经支持 Claude 和 Codex。
- 两者都通过 Android MCP 工具操作手机。
- 如果自动化能力绑定某个模型，后续维护成本会变高。

设计上应让 Claude、Codex、AgentServer 和后续 Loom 工作流都能共享配方库。

## 数据与隐私

第一版必须遵守：

- 配方默认本地保存。
- 不保存密码、验证码、API Key、Token。
- `ui.input_text` 的内容默认不进入配方，除非明确标记为非敏感模板参数。
- 用户可以删除所有候选、配方、失败记录。
- 自动白名单需要用户显式开启。
- 高风险动作不能因为历史成功而自动白名单。

## 存储建议

第一版可以使用 App 私有目录下的 JSON 文件，保持实现简单：

```text
files/automation/
  recipes.json
  candidates.json
  failures.jsonl
```

后续如果需要复杂查询、版本比较和统计，可以迁移到 SQLite。

## 实现边界

第一版应包含：

- 设置页入口和自动化 Boost 页面。
- 动作配方/候选配方基础存储。
- MCP 工具调用轨迹记录增强。
- 从成功任务生成候选配方。
- 用户审核候选并启用。
- App/配方白名单。
- Boost 执行状态和悬浮窗提示。
- 失败停止、清除 boosting flag、回退 Agent。

第一版不包含：

- 完整手动录制 UI。
- 复杂步骤编辑器。
- 支付/发送/删除等高风险自动化。
- 基于图像识别的视觉配方。
- 云同步。
- 将密钥、Loom、AgentServer 设置迁入新设置页。

## 测试策略

单元测试：

- 配方准入规则。
- 风险等级判断。
- UI selector 匹配。
- 屏幕指纹匹配。
- 失败次数到禁用状态转换。
- 敏感输入脱敏。

集成测试：

- MCP 工具调用轨迹能被记录。
- 成功任务能生成候选配方。
- 白名单配方能进入 Boost。
- 失败步骤会清除 `boosting` flag。
- 失败后能回退 Agent。

手动设备测试：

- 打开设置页中的固定页面。
- 打开某个 App 并进入固定 Tab。
- 目标文本变化时能失败回退。
- 禁用白名单后不会自动 Boost。
- 悬浮窗状态能正确切换。

## 交付顺序建议

1. 加数据模型和存储。
2. 增强 MCP 调用轨迹记录。
3. 从 AgentTask 生成候选配方。
4. 加候选配方相似去重和版本合并。
5. 加设置入口和自动化页。
6. 加白名单和启用/禁用管理。
7. 加 Boost 执行器。
8. 加悬浮窗 Boost 状态。
9. 加失败回退和配方降级。

这个顺序能先验证“是否能生成有价值配方”，再接入自动执行，降低误触风险。
