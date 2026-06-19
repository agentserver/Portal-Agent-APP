# UI 优化实施计划

基于 UI/UX Pro Max Skill 分析结果 + 项目现状，只做浅色模式优化，底部导航 5→4 Tab。

---

## Phase 1: 设计 Token 统一

### 1.1 扩展 colors.xml

在现有基础上补充缺失的语义色：

```xml
<!-- 补全 AI-Native UI 推荐色 -->
<color name="app_secondary">#6366F1</color>
<color name="app_accent">#059669</color>
<color name="app_on_primary">#FFFFFF</color>
<color name="app_on_accent">#FFFFFF</color>

<!-- 输入框/表面层级（消除硬编码灰色） -->
<color name="app_input_bg">#F1F5F9</color>
<color name="app_card_bg">#FFFFFF</color>
<color name="app_header_bg">#F8FAFC</color>
<color name="app_log_bg">#F1F5F9</color>

<!-- 文本层级（替代 #212121 #666666 #888888 硬编码） -->
<color name="app_text_hint">#94A3B8</color>

<!-- 气泡色 -->
<color name="app_bubble_user">#2563EB</color>
<color name="app_bubble_assistant">#F1F5F9</color>
<color name="app_bubble_thinking">#F8FAFC</color>
<color name="app_bubble_thinking_stroke">#E2E8F0</color>

<!-- 状态色补充 -->
<color name="app_status_active">#2563EB</color>
<color name="app_status_connected">#16A34A</color>
<color name="app_status_warning">#F59E0B</color>
```

### 1.2 更新 bubble drawable

- `bubble_user.xml`: `#1565C0` → `@color/app_bubble_user`
- `bubble_assistant.xml`: `#EEEEEE` → `@color/app_bubble_assistant`
- `bubble_thinking.xml`: `#F5F5F5` → `@color/app_bubble_thinking`, stroke `#E0E0E0` → `@color/app_bubble_thinking_stroke`

### 1.3 新增通用 drawable

- `bg_card.xml`: 白色圆角 12dp + 1dp border `app_border`（替代各页面手写背景）
- `bg_input_field.xml`: `app_input_bg` 圆角 8dp（替代 `#EEEEEE`）
- `bg_section_header.xml`: `app_header_bg`（替代 `#F5F5F5`）

---

## Phase 2: 导航重构 (5→4 Tab)

### 2.1 修改 bottom_nav_menu.xml

移除 Loom，变为 4 Tab：
- 主页 (nav_home)
- 终端 (nav_terminal)
- 密钥 (nav_apikey)
- 设置 (nav_settings) ← 新增

### 2.2 新建 SettingsHubFragment

一个简单的列表页面作为设置入口：
- AgentServer → 打开 AgentServerFragment
- Loom → 打开 LoomFragment
- 自动化 Boost → 打开 AutomationSettingsFragment
- 应用设置 → 打开原有 SettingsActivity

### 2.3 修改 TermuxActivity.setupBottomNav()

- 移除 `nav_loom` 分支
- `nav_agentserver` → `nav_settings`，改为显示 SettingsHubFragment
- 添加 SettingsHubFragment 与子页面之间的 Fragment 导航逻辑

### 2.4 替换系统默认图标

用项目内自定义 vector icon 替换 `@android:drawable/ic_menu_compass` 等系统图标：
- nav_home → 自定义 ic_home.xml (chat bubble icon)
- nav_terminal → 自定义 ic_terminal.xml (terminal prompt icon)
- nav_apikey → 自定义 ic_key.xml (key icon)
- nav_settings → 自定义 ic_settings.xml (已有)

---

## Phase 3: 主聊天界面精简 (HomeFragment)

### 3.1 底部操作区重构

当前结构（从上到下）：
1. 打断 + 新建对话 (横向2按钮)
2. 无障碍状态 + 截图状态 (横向2文本)
3. 附件预览条
4. 附件按钮 + 输入框 + 回车 + 发送

优化后：
1. ~~打断 + 新建对话~~ → "打断"按钮只在运行中动态出现在输入行左侧替换附件按钮；"新建对话"移入顶栏 overflow menu
2. ~~无障碍状态 + 截图状态~~ → 移到顶部状态栏右侧，以小图标显示
3. 附件预览条（保持）
4. 附件按钮 + 输入框 + 发送（移除回车按钮，文本框自带多行）

省出约 50-60dp 垂直空间给聊天内容。

### 3.2 顶栏优化

当前：汉堡菜单 | "Claude Code" 标题 | 设置齿轮
优化后：汉堡菜单 | "Claude Code" 标题 | [无障碍图标][截图图标] | ⋮ overflow menu

overflow menu 包含：新建对话、打开设置

### 3.3 状态指示器

将 `home_status_text` 从顶栏下方移到标题行内，作为标题右侧的小 badge。

---

## Phase 4: 表单页面 Card 化

### 4.1 fragment_agent_server.xml 重构

- 所有硬编码颜色替换为 token
- 表单用 MaterialCardView 分组：[连接配置卡片] [操作按钮卡片]
- 底部日志区保持但用 `app_log_bg` token
- 输入框用 `bg_input_field` 统一背景
- 按钮高度 36dp → 44dp

### 4.2 fragment_loom.xml 重构

同上模式：
- 颜色 token 化
- 用卡片分组：[基本配置] [Observer 设置] [Driver/Slave 设置] [操作按钮]
- 按钮高度提升

### 4.3 fragment_api_key.xml 优化

- 标题栏颜色替换
- Provider 切换按钮间距和高度统一
- "添加 Key" 按钮色 `#1976D2` → `@color/app_primary`

### 4.4 所有 item_*.xml 列表项

统一将 `#212121` → `@color/app_text_primary`，`#888888`/`#666666` → `@color/app_text_muted`

---

## Phase 5: 聊天气泡升级

### 5.1 item_msg_assistant.xml

- 气泡背景更新为 `app_bubble_assistant`
- 添加微妙 elevation (1dp) 增加层次
- thinking 容器使用新 token

### 5.2 item_msg_user.xml

- 气泡背景使用 `app_bubble_user` (= `app_primary`)

### 5.3 item_msg_tool_use.xml / item_msg_tool_result.xml

- 增加左侧 accent 色条（4dp 宽 `app_secondary` 色条）区分工具调用
- 硬编码色替换

### 5.4 item_msg_system.xml

- 系统消息改为居中、较小字号的灰色文本（已有但颜色硬编码）
- 替换为 `app_text_muted`

---

## 涉及文件清单

### 新增文件
- `app/src/main/res/drawable/bg_card.xml`
- `app/src/main/res/drawable/bg_input_field.xml`
- `app/src/main/res/drawable/bg_section_header.xml`
- `app/src/main/res/drawable/ic_home.xml`
- `app/src/main/res/drawable/ic_terminal.xml`
- `app/src/main/res/drawable/ic_key.xml`
- `app/src/main/res/layout/fragment_settings_hub.xml`
- `app/src/main/java/com/termux/app/SettingsHubFragment.java`

### 修改文件
- `app/src/main/res/values/colors.xml` — 扩展 token
- `app/src/main/res/drawable/bubble_user.xml` — 引用 token
- `app/src/main/res/drawable/bubble_assistant.xml` — 引用 token
- `app/src/main/res/drawable/bubble_thinking.xml` — 引用 token
- `app/src/main/res/menu/bottom_nav_menu.xml` — 4 Tab
- `app/src/main/res/layout/fragment_home.xml` — 底部精简 + 顶栏调整
- `app/src/main/res/layout/fragment_agent_server.xml` — token + card
- `app/src/main/res/layout/fragment_loom.xml` — token + card
- `app/src/main/res/layout/fragment_api_key.xml` — token 化
- `app/src/main/res/layout/fragment_automation_settings.xml` — 微调
- `app/src/main/res/layout/item_msg_assistant.xml` — 气泡升级
- `app/src/main/res/layout/item_msg_user.xml` — 气泡升级
- `app/src/main/res/layout/item_msg_tool_use.xml` — accent 色条
- `app/src/main/res/layout/item_msg_tool_result.xml` — accent 色条
- `app/src/main/res/layout/item_msg_system.xml` — token
- `app/src/main/res/layout/item_session.xml` — token
- `app/src/main/res/layout/item_agent_task.xml` — token
- `app/src/main/res/layout/item_automation_recipe.xml` — token
- `app/src/main/res/layout/item_automation_failure.xml` — token
- `app/src/main/res/layout/item_drawer_file.xml` — token
- `app/src/main/res/layout/dialog_auth_qr.xml` — token
- `app/src/main/java/com/termux/app/TermuxActivity.java` — setupBottomNav 重构
- `app/src/main/java/com/termux/app/HomeFragment.java` — 底部 UI 逻辑变更

---

## 实施顺序

1. Phase 1 (Token) → 基础依赖，所有后续阶段使用
2. Phase 5 (气泡) → 简单替换，可快速看到效果
3. Phase 4 (表单 Card 化) → 独立页面，改动互不影响
4. Phase 2 (导航) → 需要新文件 + TermuxActivity 改动
5. Phase 3 (聊天界面精简) → 影响最大，放最后
