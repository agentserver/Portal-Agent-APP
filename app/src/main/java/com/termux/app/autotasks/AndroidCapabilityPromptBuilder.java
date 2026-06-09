package com.termux.app.autotasks;

import androidx.annotation.NonNull;

public final class AndroidCapabilityPromptBuilder {

    private AndroidCapabilityPromptBuilder() {}

    @NonNull
    public static String buildClaudeInstructions() {
        return buildSharedAndroidInstructions()
            + "## 记忆系统\n\n"
            + "**当用户要求「记住」某件事时，必须遵守以下规则：**\n\n"
            + "- 将记忆保存为独立 `.md` 文件，路径：`~/.claude/memory/<描述性名称>.md`\n"
            + "- 文件名用英文小写+下划线，如 `user_preference.md`、`api_key.md`\n"
            + "- **不要修改 `~/CLAUDE.md`**（该文件由系统自动管理，每次启动会被覆盖）\n"
            + "- 每次对话开始时，可读取 `~/.claude/memory/` 目录下的文件了解用户偏好\n";
    }

    @NonNull
    public static String buildCodexInstructions() {
        return buildSharedAndroidInstructions()
            + "## Codex 项目说明\n\n"
            + "- 本文件是 `/home/codex/AGENTS.md`，用于告诉 Codex 如何使用这台 Android 手机的能力。\n"
            + "- 若用户要求记录长期偏好或项目规则，请更新当前工作区合适的 `AGENTS.md`。\n"
            + "- 不要把 API key、访问令牌或其他敏感信息写入 `AGENTS.md`。\n";
    }

    @NonNull
    public static String buildCodexAndroidSkill() {
        return "---\n"
            + "name: android-phone\n"
            + "description: Use when the user asks Codex to inspect or control this Android phone, use screenshots, UI automation, app launching, camera, files, sensors, or the local Android MCP tools.\n"
            + "---\n\n"
            + "# Android Phone Control\n\n"
            + buildSharedAndroidInstructions()
            + "## Codex 使用规则\n\n"
            + "- 优先调用已注册的 `android-mcp` MCP 工具完成手机操作。\n"
            + "- 不要用 Linux 截屏命令替代 `screen.capture`。\n"
            + "- MCP 工具不可见时，先报告 Codex MCP 配置未生效，不要声称手机没有能力。\n";
    }

    @NonNull
    private static String buildSharedAndroidInstructions() {
        return "# 运行环境\n\n"
            + "你正在一台 Android 手机的 Ubuntu proot 容器中运行，"
            + "拥有通过 MCP 工具和 HTTP 桥直接控制宿主 Android 系统的能力。"
            + "当用户要求操作手机、打开应用、截图、输入内容等任务时，"
            + "**直接调用下列工具完成，无需询问是否有能力**。\n\n"

            + "## MCP 工具（android-mcp / Android 控制接口）\n\n"

            + "### UI 控制（需无障碍服务权限）\n"
            + "- `ui.tap` — 点击屏幕坐标 `{x, y}`\n"
            + "- `ui.swipe` — 滑动 `{x1,y1} -> {x2,y2}`，可指定时长(ms)\n"
            + "- `ui.click_text` — 点击屏幕上包含指定文字的元素\n"
            + "- `ui.input_text` — 向当前焦点输入框输入文字\n"
            + "- `ui.get_accessibility_tree` — 获取当前屏幕 UI 元素树（用于分析界面、定位元素）\n\n"

            + "### 截图与摄像头\n"
            + "- `screen.capture` — 截取当前屏幕，返回 base64 JPEG\n"
            + "- `camera.take_photo` — 用后置摄像头拍照，返回 base64 JPEG\n\n"

            + "**关于截屏的硬性规则：**\n\n"
            + "1. **`screen.capture` 是获取当前屏幕的唯一正确方式**。这是手机 App 通过 Android `MediaProjection` API 实时截屏的接口。\n"
            + "2. **proot 容器内任何 Linux 截屏工具都会失败**，无需尝试：`scrot`、`grim`、`import`、`gnome-screenshot`、`screencap`、`adb shell`。\n"
            + "3. **`/sdcard/Pictures/Screenshots/` 是用户历史截图，不是当前屏幕**。绝不能把目录里最新的图当作当前屏幕反馈给用户。\n"
            + "4. **若 `screen.capture` 返回 \"Screen capture permission not granted\"**，立即停止替代方案，告诉用户打开手机 App 主页并点击「授权截图」后重试。\n\n"

            + "### 文件、App 与系统状态\n"
            + "- `file.read` / `file.list` / `file.check_exists` — 读取、列出、检查文件\n"
            + "- `app.open` — 通过包名启动应用（如 `com.android.chrome`）\n"
            + "- `app.get_current_activity` — 获取当前前台 Activity 名称\n"
            + "- `android.get_status` — 获取权限状态与工具可用性\n\n"

            + "## Android 传感器数据（HTTP 直接调用）\n\n"
            + "```bash\n"
            + "curl http://127.0.0.1:" + ApiHttpBridgeServer.PORT + "/battery    # 电池状态\n"
            + "curl http://127.0.0.1:" + ApiHttpBridgeServer.PORT + "/wifi       # WiFi 信息\n"
            + "curl http://127.0.0.1:" + ApiHttpBridgeServer.PORT + "/sensors    # 传感器列表\n"
            + "curl http://127.0.0.1:" + ApiHttpBridgeServer.PORT + "/camera     # 摄像头信息\n"
            + "curl http://127.0.0.1:" + ApiHttpBridgeServer.PORT + "/clipboard  # 剪贴板内容\n"
            + "```\n\n"

            + "## MCP/HTTP Android 控制循环\n\n"
            + "1. `screen.capture` 观察当前屏幕。\n"
            + "2. `ui.get_accessibility_tree` 分析 UI 结构，找到目标元素。\n"
            + "3. 使用 `ui.click_text` / `ui.tap` / `ui.input_text` 执行操作。\n"
            + "4. 再次调用 `screen.capture` 或 HTTP 状态接口确认结果，循环直到任务完成。\n\n";
    }
}
