# AgentServer / Loom 连接边界整合设计

日期：2026-06-16

## 目标

当前协作页把三类不同“连接”混在了一起：

- AgentServer 工作空间连接。
- Driver MCP 绑定与扫码注册。
- Loom Observer / Slave 角色启动和停止。

这种混合会导致重复授权。最明显的问题是：用户只是切换本机 Loom 身份，应用却触发 Driver 绑定，进而弹出扫码窗口。用户感知上就是“切身份也要重新扫码”。

本设计的目标是把连接职责分清，让每个按钮只做一类事，并避免不必要的重复扫码。

## 当前逻辑梳理

### AgentServer 连接

当前由 `AgentServerFragment` 负责。

相关文件：

- `app/src/main/java/com/termux/app/AgentServerFragment.java`
- `app/src/main/java/com/termux/app/AgentServerCommandBuilder.java`

当前职责：

- 读取和保存 `agentserver_config`。
- 使用 `server_url`、`sandbox_code`、`device_name`。
- 通过 Ubuntu 环境启动当前 provider 对应的 `agentserver` 进程。
- 当 AgentServer 输出 OAuth Device Flow 链接时显示扫码弹窗。
- 从日志解析 `tunnel connected (sandbox:...)`。
- 成功后保存 `agentserver_config.sandbox_id`。
- 当服务器返回 session 过期或不存在时清空旧 `sandbox_id`。

当前状态键：

- `agentserver_config.server_url`
- `agentserver_config.sandbox_code`
- `agentserver_config.device_name`
- `agentserver_config.sandbox_id`

边界结论：AgentServer 连接是唯一的 workspace/tunnel 授权流程。

### Loom 角色运行

当前协作页的 `CollaborationFragment` 负责启动和停止 Loom 角色。

相关文件：

- `app/src/main/java/com/termux/app/CollaborationFragment.java`
- `app/src/main/java/com/termux/app/LoomFragment.java`
- `app/src/main/java/com/termux/app/loom/LoomCommandBuilder.java`
- `app/src/main/java/com/termux/app/loom/LoomSettings.java`

当前职责：

- 从 `ProviderSettingsStore` 读取当前助手。
- 从 `loom_config.role_mode` 读取当前角色。
- 通过 `LoomCommandBuilder` 启动或停止 Observer / Slave。
- 从 `agentserver_config` 派生 AgentServer URL 和 workspace id。
- 启动角色前写入 Loom 配置文件。

当前问题：

- `CollaborationFragment.switchRoleAndBind()` 在切换角色后会调用 Driver 绑定。
- 角色切换因此变成了注册动作。
- 当注册流程打印授权链接时，用户会看到扫码弹窗。

边界结论：Loom 角色启动/停止不应该拥有用户授权逻辑。

### Driver MCP 绑定

当前由 `CollaborationFragment.bindDriverToCurrentAgent()` 负责。

相关文件：

- `app/src/main/java/com/termux/app/CollaborationFragment.java`
- `app/src/main/java/com/termux/app/loom/LoomCommandBuilder.java`
- `app/src/main/java/com/termux/app/ProviderProfile.java`

当前职责：

- 运行 `LoomCommandBuilder.setupConfigScript(settings)`。
- 运行 `driver-agent register --config ...`。
- 当 Driver 注册输出授权链接时显示扫码弹窗。
- 为 Codex 或 Claude 写入对应 MCP 配置。

边界结论：Driver 绑定是独立授权边界。它表示“把本机 Driver MCP 绑定到当前 Agent / workspace”，不是 AgentServer 连接，也不是 Loom 角色启动。

## 目标边界

协作页可以继续作为统一控制台，但内部必须保留三个独立状态机。

### 1. AgentServer 工作空间连接

拥有者：`AgentServerFragment` 和共享状态读取层。

范围：

- 服务器地址。
- Workspace / sandbox id。
- 设备名。
- 当前 provider 对应的 AgentServer 进程。
- AgentServer 授权扫码。

允许触发：

- 用户点击 AgentServer 连接。
- 用户修改服务器、workspace 或设备配置后重新连接。
- token 过期后用户重试连接。

禁止触发：

- Loom 角色切换不能触发 AgentServer 连接。
- Driver 绑定不能改写 AgentServer 连接状态，只能读取它。

### 2. Driver MCP 绑定

拥有者：协作运行时层。

范围：

- provider 对应的 Driver 配置路径。
- provider 对应的 MCP 配置路径。
- `driver-agent register`。
- Driver 绑定授权扫码。
- Driver 绑定持久状态。

允许触发：

- 用户点击 `绑定 Driver`。
- 用户切换 Codex / Claude 后明确确认重新绑定。
- AgentServer workspace 身份变化后用户重新绑定。
- Driver 绑定指纹缺失、过期或上次绑定失败后用户重新绑定。

禁止触发：

- 切换 Observer / Slave 不能执行 Driver 注册。
- 启动 Observer 不能执行 Driver 注册。
- 启动 Slave 不能静默执行 Driver 注册。若 Driver 未绑定，应阻止并提示。

### 3. Loom 角色运行

拥有者：协作页和 `LoomCommandBuilder`。

范围：

- 角色选择。
- Observer 进程启动/停止。
- Slave 进程启动/停止。
- 运行日志和状态。
- 从当前 AgentServer 状态渲染 Loom 配置。

允许触发：

- 用户点击 `启动当前角色`。
- 用户点击 `停止当前角色`。
- 用户切换 Observer / Slave 后再启动角色。

禁止触发：

- 切换角色本身不能弹扫码。
- 角色启动不能自动注册 Driver。
- 角色启动不能自动重连 AgentServer。

## 状态模型

新增一个轻量共享状态层，避免 UI 和命令执行代码重复解析 preferences。

建议类：

`app/src/main/java/com/termux/app/collab/CollaborationConnectionState.java`

职责：

- 读取 AgentServer 状态。
- 读取 Loom 状态。
- 计算 Driver 绑定指纹。
- 判断 Driver 绑定状态。
- 判断当前角色是否允许启动。
- 为协作首页提供状态摘要。

### AgentServer 状态

字段：

- `provider`
- `serverUrl`
- `sandboxId`
- `sandboxCode`
- `deviceName`
- `isConnectedHint`

第一版可以把非空 `sandbox_id` 当成持久连接身份。实时进程检测继续由现有“刷新状态”脚本负责。

### Driver 绑定状态

建议新增到 `loom_config`：

- `driver_binding_fingerprint`
- `driver_binding_provider`
- `driver_binding_sandbox_id`
- `driver_binding_server_url`
- `driver_binding_device_name`
- `driver_binding_driver_name`
- `driver_binding_updated_at`
- `driver_binding_status`

指纹输入：

```text
provider
server_url
sandbox_id or sandbox_code
device_name
driver_name
driver_config_path
mcp_config_path
```

状态值：

- `missing`：没有成功绑定记录。
- `valid`：保存的指纹与当前指纹一致，且上次绑定命令成功。
- `stale`：存在旧绑定，但 provider、workspace、设备名或 Driver 配置已变化。
- `binding`：当前正在绑定。
- `failed`：上次绑定失败。

### Loom 角色状态

字段：

- `roleMode`
- `runtimeProvider`
- `observerUrl`
- `observerName`
- `slaveName`
- `roleRuntimeStatus`

协作首页只暴露 `Observer` 和 `Slave` 作为本机身份。Driver 不再是角色选项，而是一个绑定能力。

## UI 设计

协作首页应明确显示三类状态：

```text
AgentServer：已连接 / 未连接 / Token 过期
Driver MCP：已绑定 / 需绑定 / 绑定中 / 绑定失败
Loom 角色：Observer 运行 / Slave 运行 / 已停止
```

按钮职责：

- `连接`：进入或执行 AgentServer 连接流程，可能扫码。
- `绑定 Driver`：只执行 Driver 注册，可能扫码。
- `切换身份`：只切换 Observer / Slave。
- `启动当前角色`：只启动当前 Loom 角色。
- `停止当前角色`：只停止当前 Loom 角色。
- `切换 Agent`：切换 Codex / Claude，并把 Driver 绑定标记为可能过期。

用户可见规则：

只有 `连接` 和 `绑定 Driver` 允许出现扫码/授权弹窗。`切换身份`、`启动当前角色`、`停止当前角色` 不应突然要求扫码。

当 `启动当前角色` 需要 Driver 但 Driver 未绑定时，显示：

```text
Driver MCP 尚未绑定，请先点击“绑定 Driver”。
```

不要从启动角色按钮自动开始 Driver 注册。

## 行为规则

### 切换角色

当前行为：

- 保存 `loom_config.role_mode`。
- 调用 `bindDriverToCurrentAgent()`。

目标行为：

- 保存 `loom_config.role_mode`。
- 刷新 Dashboard。
- 不绑定 Driver。
- 不显示扫码。

### 切换 Provider

当前行为：

- 保存当前 provider。
- 调用 `bindDriverToCurrentAgent()`。

目标行为：

- 保存当前 provider。
- 如果 provider 变化，标记 Driver 绑定为 `stale`。
- 刷新 Dashboard。
- 提示用户需要重新绑定 Driver。
- 除非用户明确确认或点击 `绑定 Driver`，否则不显示扫码。

### 绑定 Driver

目标行为：

- 计算当前 Driver 指纹。
- 如果 AgentServer 没有 workspace 身份，阻止执行并提示：

```text
请先连接 AgentServer 工作空间。
```

- 如果当前指纹已经有效，提示：

```text
Driver MCP 已绑定，无需重复扫码。
```

- 如果状态为 `missing`、`stale` 或 `failed`，执行 `setupConfigScript` 和 `driver-agent register`。
- 监听输出中的授权链接，并且一次绑定尝试只显示一次扫码弹窗。
- 命令 exit 0 后保存指纹和 `valid` 状态。
- 命令失败后保存 `failed` 状态，不写入有效指纹。

### 启动 Observer

目标行为：

- 使用当前 AgentServer 状态渲染 Loom 配置。
- 启动 Observer。
- 不要求 Driver 绑定。
- 不显示 Driver 授权扫码。

### 启动 Slave

目标行为：

- 使用当前 AgentServer 状态渲染 Loom 配置。
- 如果 Driver 绑定缺失或过期，先阻止并提示用户绑定 Driver。
- 启动 Slave。
- 不执行 `driver-agent register`。

### 停止角色

目标行为：

- 只停止当前角色进程。
- 不修改 Driver 绑定状态。
- 不修改 AgentServer 连接状态。

## 错误处理

AgentServer 连接失败：

- token 过期：清空 `sandbox_id`，workspace 状态变为过期，Driver 绑定变为 `stale`。
- 环境或二进制缺失：显示环境未就绪。
- 服务器 404 或地址错误：提示服务器地址问题。

Driver 绑定失败：

- workspace 缺失：命令执行前阻止。
- 用户取消授权或命令非 0 退出：保存 `failed`。
- 输出授权链接：一次绑定尝试只弹一次二维码。
- 指纹已有效：不执行注册命令。

Loom 运行失败：

- 环境或二进制缺失：显示环境未就绪。
- Driver 未绑定：启动 Slave 前阻止。
- 角色进程已运行：如果命令返回 already running，视作成功。

## 实施顺序

1. 添加连接边界测试：
   - 切换角色不能调用 `bindDriverToCurrentAgent`。
   - 切换 provider 标记 Driver 过期，而不是立即绑定。
   - Driver 绑定成功后保存指纹。
   - 有效指纹命中时跳过注册。
   - Driver 缺失或过期时，启动 Slave 被阻止。
   - 启动 Observer 不要求 Driver 绑定。

2. 添加 `CollaborationConnectionState`：
   - 读取当前 AgentServer 状态。
   - 读取当前 Loom 状态。
   - 计算 Driver 指纹。
   - 返回 Driver 绑定状态。

3. 更新 `CollaborationFragment`：
   - 移除角色切换后的自动 Driver 绑定。
   - 把 provider 自动绑定改为标记过期。
   - 在 `bindDriverToCurrentAgent()` 前增加 AgentServer 与指纹检查。
   - 绑定成功后保存有效指纹。
   - 在 `startSelectedRole()` 中根据角色需求做前置检查。

4. 更新协作首页文案：
   - 分别显示 AgentServer、Driver MCP、Loom 角色状态。
   - 只在 AgentServer 连接和显式 Driver 绑定时显示扫码。

5. 验证：
   - 运行 Collaboration 连接边界测试。
   - 运行现有 Collaboration / Loom 测试。
   - 运行 Debug 构建。

## 验收标准

- Observer / Slave 之间切换不会触发 Driver 绑定。
- Observer / Slave 之间切换不会显示扫码弹窗。
- Driver 绑定只从显式绑定动作或显式确认中触发。
- 当前 Driver 指纹有效时不会重复注册。
- 切换 provider 或 AgentServer workspace 后，Driver 绑定状态变为过期。
- 启动 Slave 不会静默执行 Driver 注册。
- 启动 Observer 不要求 Driver 绑定。
- 协作首页清晰区分 AgentServer 连接、Driver 绑定、Loom 角色运行。

## 自检

- 没有保留未决内容。
- AgentServer 连接和 Driver 绑定是两个独立授权边界。
- Loom 角色运行不拥有扫码/授权流程。
- 实施范围可以拆成一个后续实现计划。
- 现有 AgentServer 与 Loom command builder 可以复用，不需要改后端协议。
