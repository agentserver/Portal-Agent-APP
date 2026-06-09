package com.termux.app.autotasks;

import androidx.annotation.NonNull;

import com.termux.app.TermuxActivity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Phase 3: 自动在 Ubuntu 内安装并配置 Claude Code。
 *
 * 工作原理：
 * 1. 构造时在后台将交互式安装脚本写入 Termux $HOME/.claude-inner-setup.sh
 * 2. AutoUbuntuManager 在 Ubuntu 安装/登录前将该脚本复制到 Ubuntu rootfs 的
 *    /root/.claude-setup.sh，并在 /root/.bashrc 中追加 source hook
 * 3. Ubuntu 首次登录时 .bashrc 自动触发安装向导（一次性，完成后自我清除）
 */
public class AutoClaudeManager {

    /** Termux $HOME 下的 inner 脚本相对路径（相对于 filesDir）。
     *  AutoUbuntuManager 用此路径做注入，两边保持一致。 */
    static final String INNER_SCRIPT_REL = "home/.claude-inner-setup.sh";

    private final TermuxActivity mActivity;

    public AutoClaudeManager(@NonNull TermuxActivity activity) {
        mActivity = activity;
        // 后台写脚本，Ubuntu 安装需要几分钟，有充足时间在注入前写完
        Thread t = new Thread(this::writeInnerScript, "claude-setup-write");
        t.setDaemon(true);
        t.start();
    }

    /** 返回 inner 脚本的绝对路径（固定路径，不依赖后台线程完成状态）。 */
    @NonNull
    public String getInnerScriptPath() {
        return new File(mActivity.getFilesDir(), INNER_SCRIPT_REL).getAbsolutePath();
    }

    // -------------------------------------------------------------------------
    // 后台：写入交互式安装脚本
    // -------------------------------------------------------------------------

    private void writeInnerScript() {
        File scriptFile = new File(mActivity.getFilesDir(), INNER_SCRIPT_REL);
        try {
            scriptFile.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(scriptFile)) {
                w.write(buildClaudeInnerScript());
            }
        } catch (IOException ignored) {
            // 写失败时注入步骤因文件不存在而静默跳过
        }
    }

    /**
     * 返回在 Ubuntu 内运行的交互式安装脚本内容（bash，从 .bashrc 被 source）。
     *
     * 流程：
     *   1. 幂等保护：claude 已存在 → 自我清除后返回
     *   2. 安装 curl（Ubuntu minimal 可能缺失）
     *   3. 安装 Node.js LTS（NodeSource → 系统 apt 兜底）
     *   4. 配置 npmmirror（加速国内下载）
     *   5. npm install -g @anthropic-ai/claude-code
     *   6. 交互：选择认证方式（API key / 官方登录）
     *      - API key：选择接入点（官方 / 中科院镜像 / 自定义），写入 ~/.bashrc + ~/.claude.json
     *   7. 自我清除（从 .bashrc 移除 hook，删除脚本文件）
     */
    public static String buildInnerScriptForTest() {
        return buildClaudeInnerScript();
    }

    private static String buildClaudeInnerScript() {
        StringBuilder s = new StringBuilder();

        s.append("#!/bin/bash\n");
        s.append("# Claude Code auto-setup (sourced from ~/.bashrc on first Ubuntu login)\n\n");

        // ── 幂等保护：claude 已安装则自我清除并退出 ──────────────────────────
        s.append("if command -v claude >/dev/null 2>&1; then\n");
        s.append("    sed -i '/.claude-setup/d' ~/.bashrc 2>/dev/null\n");
        s.append("    rm -f ~/.claude-setup.sh\n");
        s.append("    return 0 2>/dev/null || exit 0\n");
        s.append("fi\n\n");

        // ── 欢迎界面 ─────────────────────────────────────────────────────────
        s.append("echo ''\n");
        s.append("echo '================================'\n");
        s.append("echo '  Claude Code 首次配置'\n");
        s.append("echo '================================'\n");
        s.append("echo ''\n\n");

        // ── Step 1: 切换 Ubuntu apt 镜像（清华，加速国内下载）─────────────────
        // Ubuntu 25.10 系统 apt 自带 Node.js 22.x，无需 NodeSource；
        // 但默认源在国内极慢，先切镜像再 update，避免卡住。
        s.append("export DEBIAN_FRONTEND=noninteractive\n");
        s.append("_codename=$(. /etc/os-release 2>/dev/null && ")
         .append("echo \"${UBUNTU_CODENAME:-${VERSION_CODENAME:-questing}}\")\n");
        s.append("if ! grep -qF 'tuna.tsinghua.edu.cn' /etc/apt/sources.list 2>/dev/null; then\n");
        s.append("    echo \"[*] 切换 apt 源 -> 清华镜像 (${_codename})...\"\n");
        s.append("    cat > /etc/apt/sources.list << EOF\n");
        s.append("deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ ${_codename} main restricted universe multiverse\n");
        s.append("deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ ${_codename}-updates main restricted universe multiverse\n");
        s.append("deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ ${_codename}-security main restricted universe multiverse\n");
        s.append("deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ ${_codename}-backports main restricted universe multiverse\n");
        s.append("EOF\n");
        s.append("fi\n\n");

        // ── Step 2: 安装 Node.js（系统 apt，Ubuntu 25.10 自带 22.x）──────────
        // 去掉管道过滤，让 apt 直接输出到终端，用户可以看到实时进度。
        // 兜底：旧 snapshot（v2 及之前）打包时 `rm -rf /var/lib/apt/lists/*` 会把
        // partial/ 子目录一同删掉，apt 启动会报 "Archives directory ... is missing"。
        // snapshot-v3 起 snapshot_ubuntu.sh 已修复源头，这里保留 mkdir 兼容旧包。
        s.append("mkdir -p /var/cache/apt/archives/partial /var/lib/apt/lists/partial /var/log/apt\n");
        s.append("chmod 700 /var/cache/apt/archives/partial /var/lib/apt/lists/partial 2>/dev/null || true\n");
        s.append("if ! command -v node >/dev/null 2>&1; then\n");
        s.append("    echo '[1/3] 正在更新软件包索引（apt-get update）...'\n");
        s.append("    apt-get update 2>&1\n");
        s.append("    echo '[2/3] 正在安装 Node.js + npm + curl...'\n");
        s.append("    apt-get install -y --no-install-recommends nodejs npm curl 2>&1\n");
        s.append("fi\n");
        // 即使 node 已存在，也确保 curl 就绪
        s.append("command -v curl >/dev/null 2>&1 || {\n");
        s.append("    echo '[*] 正在安装 curl...'\n");
        s.append("    apt-get install -y --no-install-recommends curl 2>&1\n");
        s.append("}\n\n");

        s.append("if ! command -v node >/dev/null 2>&1; then\n");
        s.append("    echo '[!] Node.js 安装失败，请检查网络后重试。'\n");
        s.append("    return 1 2>/dev/null || exit 1\n");
        s.append("fi\n");
        s.append("echo \"[*] Node.js $(node --version) / npm $(npm --version) 就绪\"\n\n");

        // ── Step 3: npm 配置（镜像 + 确保 optional 依赖不被跳过）────────────
        // npmmirror 用于加速主包下载；optional=true 确保 ARM64 native binary 不被跳过。
        // 注意：native binary (@anthropic-ai/claude-code-linux-arm64) 属于 optionalDependencies，
        // 若被 npm 的 omit=optional 配置跳过，会导致 "claude native binary not installed" 错误。
        s.append("npm config set registry https://registry.npmmirror.com 2>/dev/null\n");
        s.append("npm config delete omit 2>/dev/null\n");           // 清除可能残留的 omit=optional
        s.append("npm config set optional true 2>/dev/null\n\n");   // 显式允许 optional 依赖

        // ── Step 4: 安装 claude-code ──────────────────────────────────────────
        s.append("echo '[3/3] 正在安装 Claude Code（npm install -g）...'\n");
        s.append("echo '      包较多，预计 1~3 分钟，请耐心等待...'\n");
        s.append("npm install -g @anthropic-ai/claude-code --include=optional 2>&1\n\n");

        // 若主包安装后 native binary 仍缺失，依次尝试两种修复方案
        s.append("if ! claude --version >/dev/null 2>&1; then\n");
        s.append("    echo '[*] native binary 缺失，尝试补装 ARM64 包...'\n");
        s.append("    npm install -g @anthropic-ai/claude-code-linux-arm64 --registry https://registry.npmjs.org 2>&1 || true\n");
        s.append("fi\n");
        // 若仍不行，用官方内置的 JS fallback 替换 claude 命令（无需下载，稳定可用）
        s.append("if ! claude --version >/dev/null 2>&1; then\n");
        s.append("    _wrapper=$(npm root -g)/@anthropic-ai/claude-code/cli-wrapper.cjs\n");
        s.append("    if [ -f \"$_wrapper\" ]; then\n");
        s.append("        echo '[*] 使用 JS fallback 替代 native binary...'\n");
        s.append("        printf '#!/bin/bash\\nexec node %s \"$@\"\\n' \"$_wrapper\" > /usr/local/bin/claude\n");
        s.append("        chmod +x /usr/local/bin/claude\n");
        s.append("    fi\n");
        s.append("fi\n\n");

        s.append("if ! command -v claude >/dev/null 2>&1; then\n");
        s.append("    echo '[!] Claude Code 安装失败，请手动运行：'\n");
        s.append("    echo '    npm install -g @anthropic-ai/claude-code --include=optional'\n");
        s.append("    return 1 2>/dev/null || exit 1\n");
        s.append("fi\n");
        s.append("echo '[*] Claude Code 安装成功'\n\n");

        // ── Step 5: 非交互式基础配置 ──────────────────────────────────────────
        // API key 由 App 的密钥页面写入 provider 环境；这里不能阻塞后续 Codex/AgentServer/Loom setup。
        s.append("echo '[*] Claude 密钥由 App API Key 页面管理，首次部署不再等待终端输入。'\n");
        s.append("id claude >/dev/null 2>&1 || useradd -m -s /bin/bash claude\n");
        s.append("_chome=/home/claude\n");
        s.append("mkdir -p \"$_chome\" \"$_chome/.local/bin\" 2>/dev/null\n");
        // 确保 ~/.local/bin 在 PATH 前面，便于使用 wrapper 拦截 AgentServer 任务
        s.append("grep -qF 'export PATH=\"$HOME/.local/bin:$PATH\"' \"$_chome/.bashrc\" 2>/dev/null || ");
        s.append("printf 'export PATH=\"$HOME/.local/bin:$PATH\"\\n' >> \"$_chome/.bashrc\"\n");
        // 写 /home/claude/.claude.json 跳过 onboarding
        s.append("printf '{\\n  \"hasCompletedOnboarding\": true\\n}\\n' > \"$_chome/.claude.json\"\n");
        s.append("chown -R claude:claude \"$_chome\" 2>/dev/null\n\n");

        // ── Step 6: 注册 Android MCP Server ─────────────────────────────────
        s.append("echo '[*] 注册 Android MCP Server...'\n");
        s.append("su -l claude -c \"claude mcp add --transport http android-mcp http://127.0.0.1:8765/mcp\" 2>&1 || true\n");
        s.append("echo '[*] MCP 注册完成（可用 claude mcp list 验证）'\n\n");

        // ── Step 7: 自我清除 ──────────────────────────────────────────────────
        s.append("sed -i '/.claude-setup/d' ~/.bashrc 2>/dev/null\n");
        s.append("rm -f ~/.claude-setup.sh\n\n");

        // ── 完成提示 ──────────────────────────────────────────────────────────
        s.append("echo ''\n");
        s.append("echo '配置完成！输入 claude 启动 Claude Code'\n");
        s.append("echo ''\n");
        s.append("echo 'Android API 实时调用（HTTP 桥，无需 root）:'\n");
        s.append("echo '  curl -s http://127.0.0.1:").append(ApiHttpBridgeServer.PORT)
         .append("/battery    # 实时电量'\n");
        s.append("echo '  curl -s http://127.0.0.1:").append(ApiHttpBridgeServer.PORT)
         .append("/wifi       # WiFi 信息'\n");
        s.append("echo '  curl -s http://127.0.0.1:").append(ApiHttpBridgeServer.PORT)
         .append("/sensors    # 传感器列表'\n");
        s.append("echo '  termux-battery-status      # /usr/local/bin 封装，同上'\n");
        s.append("echo '================================'\n");
        s.append("echo ''\n");

        return s.toString();
    }
}
