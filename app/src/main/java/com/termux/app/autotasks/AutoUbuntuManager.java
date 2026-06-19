package com.termux.app.autotasks;

import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class AutoUbuntuManager {

    // 资产目录下各架构 rootfs 文件名映射
    private static final String ASSET_DIR = "ubuntu";
    private static final String LOCAL_ROOTFS_BASE = ".ubuntu-local-rootfs";
    // 支持的压缩格式，优先 .tar.gz（与现有 assets 包格式一致），兼容 .tar.xz
    private static final String[] SUPPORTED_EXTENSIONS = { ".tar.gz", ".tar.xz" };

    // ── 静态打包的 Termux 端依赖（脱离 apt 仓库，避免上游变更影响）─────────
    /** APK 内 libtalloc deb 资产名（proot 的运行时依赖） */
    private static final String LIBTALLOC_DEB_ASSET = "termux-tools/libtalloc_2.4.3_aarch64.deb";
    /** APK 内 file deb 资产名（proot-distro 用 file 检测下载档案类型） */
    private static final String FILE_DEB_ASSET     = "termux-tools/file_5.47_aarch64.deb";
    /** APK 内 proot deb 资产名（aarch64 only；其他架构未支持） */
    private static final String PROOT_DEB_ASSET   = "termux-tools/proot_5.1.107-71_aarch64.deb";
    /** APK 内 proot-distro 源 .tgz 资产名（shell 实现 v4.38.0，跨架构通用）
     *  注意：用 .tgz 后缀而非 .tar.gz，避免 AAPT2 自动 gunzip 把 asset 改名 */
    private static final String PROOT_DISTRO_ASSET = "termux-tools/proot-distro-4.38.0.tgz";

    // Ubuntu rootfs CDN 镜像（Ubuntu 的 rootfs 来自 Ubuntu 官方 CDN，不是 GitHub）
    // 清华/中科大均镜像了 ubuntu-cdimage，可直接替换域名
    private static final String[][] ROOTFS_CDN_MIRRORS = {
        // { 原域名前缀,  镜像替换前缀 }
        { "https://cdimage.ubuntu.com/",     "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/" },
        { "https://cloud-images.ubuntu.com/","https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/" },
        { "https://cdimage.ubuntu.com/",     "https://mirrors.ustc.edu.cn/ubuntu-cdimage/" },
        { "https://cloud-images.ubuntu.com/","https://mirrors.ustc.edu.cn/ubuntu-cdimage/" },
    };

    private final TermuxActivity mActivity;
    private AutoAgentServerManager mAgentServerManager;
    private AutoLoomManager mLoomManager;
    private boolean mAutoLaunchAttempted;
    private boolean mEnabled = true;

    // 后台提取完成后填充，volatile 保证主线程可见
    private volatile String mLocalRootfsPath = null;
    private volatile boolean mExtractionDone = false;

    public AutoUbuntuManager(@NonNull TermuxActivity activity) {
        mActivity = activity;
        // 后台提前提取 assets 中的 rootfs，减少等待时间
        startRootfsExtraction();
    }

    /** 注入 AgentServer 管理器引用，用于在复制安装包前确认提取完成。 */
    public void setAgentServerManager(@NonNull AutoAgentServerManager mgr) {
        mAgentServerManager = mgr;
    }

    /** 注入 Loom 管理器引用，用于复制离线 Loom addon。 */
    public void setLoomManager(@NonNull AutoLoomManager mgr) {
        mLoomManager = mgr;
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    public void resetAttempt() {
        mAutoLaunchAttempted = false;
    }

    public void maybeAutoLaunchUbuntu() {
        if (!mEnabled || mAutoLaunchAttempted) return;

        TerminalSession session = mActivity.getCurrentSession();
        if (session == null || !session.isRunning()) return;

        mAutoLaunchAttempted = true;

        // 快速路径：rootfs 完整可用，直接进入配置 + 登录（后台线程避免主线程阻塞）。
        File ubuntuRootfs = new File(UbuntuSnapshotManager.UBUNTU_ROOTFS);
        if (UbuntuSnapshotManager.isRootfsUsable()) {
            new Thread(() -> launchSetupScript(session), "ubuntu-setup").start();
            return;
        }
        if (ubuntuRootfs.exists()) {
            writeEcho(session, "[!] 检测到不完整 Ubuntu rootfs，将重新部署...");
        }

        // rootfs 缺失：后台尝试快照部署，完成后再运行安装脚本
        writeEcho(session, "[*] Ubuntu 环境未检测到，准备部署...");
        Thread t = new Thread(() -> {
            boolean deployed = false;

            // 优先级 1：APK 内置快照（离线）
            if (UbuntuSnapshotManager.hasAsset(mActivity.getAssets())) {
                writeEcho(session, "[*] 发现内置快照，离线部署中 (" + UbuntuSnapshotManager.SNAPSHOT_SIZE_LABEL + ")...");
                final boolean[] ok = {false};
                UbuntuSnapshotManager.deployFromAsset(mActivity.getAssets(),
                    makeSnapshotCallback(session, ok));
                deployed = ok[0];
            }

            // 优先级 2：从 GitHub Release 下载
            if (!deployed) {
                writeEcho(session, "[*] 从 GitHub 下载快照 (" + UbuntuSnapshotManager.SNAPSHOT_SIZE_LABEL + ")，请稍候...");
                final boolean[] ok = {false};
                UbuntuSnapshotManager.deploy(makeSnapshotCallback(session, ok));
                deployed = ok[0];
            }

            writeEcho(session, deployed
                ? "[✓] 快照部署完成，继续环境配置..."
                : "[!] 快照部署失败，回退到逐步网络安装...");

            launchSetupScript(session);
        }, "ubuntu-snapshot-deploy");
        t.setDaemon(true);
        t.start();
    }

    /** 将快照部署进度写到终端，返回成功标志的回调。 */
    private UbuntuSnapshotManager.Callback makeSnapshotCallback(
            TerminalSession session, boolean[] ok) {
        return new UbuntuSnapshotManager.Callback() {
            int lastP10 = -1;
            @Override public void onStatus(String msg) { writeEcho(session, msg); }
            @Override public void onProgress(int pct) {
                int p10 = pct / 10;
                if (p10 != lastP10) { lastP10 = p10; writeEcho(session, "[↓] " + pct + "%"); }
            }
            @Override public void onSuccess() { ok[0] = true; }
            @Override public void onFailed(String reason) { writeEcho(session, "[!] " + reason); }
        };
    }

    static String buildRootfsGuardShellForTest() {
        return buildRootfsGuardShell();
    }

    static String buildProotDistroPatchShellForTest() {
        return buildProotDistroPatchShell();
    }

    static String buildBundledTermuxToolsInstallShellForTest() {
        return buildBundledTermuxToolsInstallShell();
    }

    static String buildUbuntuSnapshotPathRepairShellForTest() {
        return buildUbuntuSnapshotPathRepairShell();
    }

    static String buildProviderLoginDispatcherForTest() {
        return buildProviderLoginDispatcherFileContent(buildProviderFilePathForUbuntu());
    }

    static String buildProviderFilePathForUbuntuForTest() {
        return buildProviderFilePathForUbuntu();
    }

    private static String buildProviderFilePathForUbuntu() {
        return TermuxConstants.TERMUX_HOME_DIR_PATH + "/.assistant-provider";
    }

    private static String buildRootfsGuardShell() {
        return "_ubuntu_rootfs=\"$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\"; "
            + "ubuntu_rootfs_ok(){ "
            + "[ -d \"$_ubuntu_rootfs\" ] && "
            + "[ -f \"$_ubuntu_rootfs/etc/passwd\" ] && "
            + "[ -f \"$_ubuntu_rootfs/etc/os-release\" ] && "
            + "[ -d \"$_ubuntu_rootfs/usr\" ] && "
            + "[ -f \"$_ubuntu_rootfs/usr/bin/env\" ] && "
            + "{ [ -e \"$_ubuntu_rootfs/bin/sh\" ] || [ -e \"$_ubuntu_rootfs/usr/bin/sh\" ]; }; "
            + "}; "
            + "if [ -d \"$_ubuntu_rootfs\" ] && ! ubuntu_rootfs_ok; then "
            + "echo \"[!] Incomplete Ubuntu rootfs detected, reinstalling...\"; "
            + "rm -rf \"$_ubuntu_rootfs\" || auto_ok=0; "
            + "fi; ";
    }

    private static String buildProotDistroPatchShell() {
        return "if [ \"$auto_ok\" = \"1\" ]; then "
            + "pd_bin=$(command -v proot-distro 2>/dev/null); "
            + "[ -n \"$pd_bin\" ] && [ -f \"$pd_bin\" ] && { "
            + "sed -i 's/set -e -u/set -e/g' \"$pd_bin\" 2>/dev/null; "
            + "sed -i 's/set -euo/set -eo/g' \"$pd_bin\" 2>/dev/null; "
            + "sed -i 's/set -uo/set -o/g'   \"$pd_bin\" 2>/dev/null; "
            + "sed -i 's/local cpu_arch$/local cpu_arch=\"\"/' \"$pd_bin\" 2>/dev/null; "
            + "echo \"[*] proot-distro patched (nounset disabled + cpu_arch initialized).\"; }; "
            + "case \"$(uname -m)\" in "
            + "aarch64) export cpu_arch=aarch64 ;; "
            + "armv7*|armv8l) export cpu_arch=arm ;; "
            + "x86_64) export cpu_arch=x86_64 ;; "
            + "i*86) export cpu_arch=i686 ;; "
            + "*) export cpu_arch=$(uname -m) ;; "
            + "esac; "
            + "echo \"[*] cpu_arch=$cpu_arch\"; "
            + "fi; ";
    }

    private static String buildProviderLoginDispatcherFileContent(String providerFile) {
        return "#!/bin/sh\n"
            + "_provider_file=\"" + providerFile + "\"\n"
            + "_provider=claude\n"
            + "[ -f \"$_provider_file\" ] && _provider=$(cat \"$_provider_file\" 2>/dev/null | head -n 1)\n"
            + "case \"$_provider\" in\n"
            + "  codex) exec su - codex ;;\n"
            + "  *) exec su - claude ;;\n"
            + "esac\n";
    }

    private static String buildProviderLoginDispatcherHook() {
        return "[ -f ~/.assistant-login-dispatcher.sh ] && . ~/.assistant-login-dispatcher.sh";
    }

    private static String buildUbuntuSnapshotPathRepairShell() {
        String rootfs = TermuxConstants.TERMUX_PREFIX_DIR_PATH
            .replace("\"", "\\\"") + "/var/lib/proot-distro/installed-rootfs/ubuntu";
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH.replace("\"", "\\\"");
        String legacyPrivatePackage = "$(printf '\\143\\157\\155\\056\\172\\162\\163\\056\\160\\141')";

        return "repair_snapshot_package_paths() { "
            + "_ubr=\"$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\"; "
            + "[ -d \"$_ubr\" ] || return 0; "
            + "_new_rootfs=\"" + rootfs + "\"; "
            + "_new_home=\"" + home + "\"; "
            + "find \"$_ubr\" -type l 2>/dev/null | while IFS= read -r _link; do "
            + "_target=$(readlink \"$_link\" 2>/dev/null || true); "
            + "_new_target=\"$_target\"; "
            + "for _old_pkg in com.termux " + legacyPrivatePackage + "; do "
            + "_old_rootfs1=\"/data/data/${_old_pkg}/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu\"; "
            + "_old_rootfs2=\"/data/user/0/${_old_pkg}/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu\"; "
            + "_old_home1=\"/data/data/${_old_pkg}/files/home\"; "
            + "_old_home2=\"/data/user/0/${_old_pkg}/files/home\"; "
            + "case \"$_new_target\" in "
            + "${_old_rootfs1}*) _new_target=\"$_new_rootfs${_new_target#$_old_rootfs1}\" ;; "
            + "${_old_rootfs2}*) _new_target=\"$_new_rootfs${_new_target#$_old_rootfs2}\" ;; "
            + "${_old_home1}*) _new_target=\"$_new_home${_new_target#$_old_home1}\" ;; "
            + "${_old_home2}*) _new_target=\"$_new_home${_new_target#$_old_home2}\" ;; "
            + "esac; "
            + "done; "
            + "[ \"$_new_target\" != \"$_target\" ] && ln -sfn \"$_new_target\" \"$_link\"; "
            + "done; "
            + "for _old_pkg in com.termux " + legacyPrivatePackage + "; do "
            + "rmdir \"$_ubr/data/data/${_old_pkg}/files\" \"$_ubr/data/data/${_old_pkg}\" 2>/dev/null || true; "
            + "done; "
            + "}; repair_snapshot_package_paths; ";
    }

    private static String buildBundledTermuxToolsInstallShell() {
        String packageName = shellSingleQuoteForScript(TermuxConstants.TERMUX_PACKAGE_NAME);
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH.replace("\"", "\\\"");
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH.replace("\"", "\\\"");
        String bootstrapCompatPrefix =
            TermuxConstants.TERMUX_BOOTSTRAP_COMPAT_PREFIX_DIR_PATH.replace("\"", "\\\"");

        return "export PREFIX=\"${PREFIX:-" + prefix + "}\"; "
            + "export HOME=\"${HOME:-" + home + "}\"; "
            + "patch_bundled_tool_paths() { "
            + "old_prefix=\"/data/data/com.termux/files/usr\"; "
            + "compat_prefix=\"" + bootstrapCompatPrefix + "\"; "
            + "for f in "
            + "\"$PREFIX/bin/proot\" \"$PREFIX/bin/termux-chroot\" "
            + "\"$PREFIX/libexec/proot/loader\" \"$PREFIX/libexec/proot/loader32\" "
            + "\"$PREFIX/bin/file\" \"$PREFIX/lib/libmagic.so\" \"$PREFIX\"/lib/libmagic.so.* "
            + "\"$PREFIX/lib/pkgconfig/libmagic.pc\"; do "
            + "[ -f \"$f\" ] && sed -i \"s|$old_prefix|$compat_prefix|g\" \"$f\" 2>/dev/null || true; "
            + "done; "
            + "}; "
            + "install_legacy_termux_deb() { "
            + "deb=\"$1\"; label=\"$2\"; required=\"${3:-0}\"; "
            + "if [ ! -f \"$deb\" ]; then "
            + "echo \"[!] ${label}.deb missing\"; "
            + "[ \"$required\" = \"1\" ] && return 1 || return 0; "
            + "fi; "
            + "echo \"[*] installing ${label}.deb into $PREFIX\"; "
            + "deb_tmp=$(mktemp -d); deb_ok=1; "
            + "if command -v dpkg-deb >/dev/null 2>&1; then "
            + "dpkg-deb -x \"$deb\" \"$deb_tmp\" 2>&1 || deb_ok=0; "
            + "else "
            + "echo \"[!] dpkg-deb not found\"; deb_ok=0; "
            + "fi; "
            + "legacy_usr=\"$deb_tmp/data/data/com.termux/files/usr\"; "
            + "if [ \"$deb_ok\" = \"1\" ] && [ -d \"$legacy_usr\" ]; then "
            + "mkdir -p \"$PREFIX\" && cp -a \"$legacy_usr\"/. \"$PREFIX\"/ 2>&1 || deb_ok=0; "
            + "else "
            + "echo \"[!] ${label}.deb payload missing\"; deb_ok=0; "
            + "fi; "
            + "rm -rf \"$deb_tmp\"; "
            + "if [ \"$deb_ok\" = \"1\" ]; then "
            + "patch_bundled_tool_paths; "
            + "echo \"[*] ${label}.deb installed under $PREFIX\"; return 0; "
            + "fi; "
            + "[ \"$required\" = \"1\" ] && return 1 || return 0; "
            + "}; "
            + "auto_ok=1; "
            + "patch_bundled_tool_paths; if ! command -v proot-distro >/dev/null 2>&1; then "
            + "echo \"[*] Installing bundled libtalloc + file + proot + proot-distro (static)...\"; "
            + "install_legacy_termux_deb \"$HOME/.termux-tools/libtalloc.deb\" libtalloc 0 || true; "
            + "install_legacy_termux_deb \"$HOME/.termux-tools/file.deb\" file 0 || true; "
            + "install_legacy_termux_deb \"$HOME/.termux-tools/proot.deb\" proot 1 || { echo \"[!] proot install failed.\"; auto_ok=0; }; "
            + "if [ -f \"$HOME/.termux-tools/proot-distro.tgz\" ]; then "
            + "echo \"[*] extract proot-distro.tgz\"; "
            + "pd_tmp=$(mktemp -d); "
            + "if tar -xzf \"$HOME/.termux-tools/proot-distro.tgz\" -C \"$pd_tmp\" 2>&1; then "
            + "pd_src=$(find \"$pd_tmp\" -maxdepth 2 -name install.sh -type f | head -1); "
            + "if [ -n \"$pd_src\" ]; then "
            + "(cd \"$(dirname \"$pd_src\")\" && TERMUX_APP_PACKAGE='" + packageName + "' TERMUX_PREFIX=\"$PREFIX\" TERMUX_ANDROID_HOME=\"$HOME\" bash install.sh) 2>&1 "
            + "&& echo \"[*] proot-distro installed.\" || { echo \"[!] install.sh failed.\"; auto_ok=0; }; "
            + "else echo \"[!] install.sh not found in tarball.\"; auto_ok=0; fi; "
            + "else echo \"[!] tar extract failed.\"; auto_ok=0; fi; "
            + "rm -rf \"$pd_tmp\"; "
            + "else echo \"[!] proot-distro.tgz missing.\"; auto_ok=0; fi; "
            + "fi; ";
    }

    /**
     * 向 Ubuntu rootfs /root/ 注入三项内容（每次启动更新，幂等）：
     *   1. CLAUDE.md          — Claude Code 自动读取的能力说明
     *   2. .claude/commands/phone.md — /phone skill，供用户显式触发
     *   3. .claude/settings.json    — 确保 root 用户有 android-mcp 注册
     *      （AutoClaudeManager 的 claude mcp add 只给 claude 用户注册，
     *        而 HomeFragment 以 root 运行 claude -p，读的是 /root/.claude/）
     */
    private void injectClaudeMd() {
        // claude -p 以 --user claude 运行，home 是 /home/claude/
        File claudeHome = new File(UbuntuSnapshotManager.UBUNTU_ROOTFS + "/home/claude");
        if (!claudeHome.isDirectory()) return;

        // 1. CLAUDE.md — Claude Code 读取当前工作目录的 CLAUDE.md
        writeFile(new File(claudeHome, "CLAUDE.md"),
            AndroidCapabilityPromptBuilder.buildClaudeInstructions());

        // 2. /phone skill
        File commandsDir = new File(claudeHome, ".claude/commands");
        commandsDir.mkdirs();
        writeFile(new File(commandsDir, "phone.md"), buildPhoneSkillContent());

        // 3. settings.json — 与 AutoClaudeManager 的 su -l claude claude mcp add 一致
        injectMcpSettings(new File(claudeHome, ".claude"));

        // 4. claude 包装脚本 — 拦截 AgentServer 对 claude 的调用，
        //    把任务 prompt 和 JSONL 输出写入 ~/.agentserver-pipe.jsonl，
        //    供 Android App 实时显示 AgentServer 任务执行过程。
        injectClaudeWrapper(claudeHome);
    }

    private void injectProviderInstructions() {
        injectClaudeMd();

        File codexHome = new File(UbuntuSnapshotManager.UBUNTU_ROOTFS + "/home/codex");
        if (!codexHome.isDirectory()) return;

        writeFile(new File(codexHome, "AGENTS.md"),
            AndroidCapabilityPromptBuilder.buildCodexInstructions());

        File skillDir = new File(codexHome, ".codex/skills/android-phone");
        skillDir.mkdirs();
        writeFile(new File(skillDir, "SKILL.md"),
            AndroidCapabilityPromptBuilder.buildCodexAndroidSkill());
    }

    /**
     * 注入 ~/.local/bin/claude 包装脚本。
     * 优先级高于 /usr/local/bin/claude，对 AgentServer 调用透明拦截：
     *   - 有 CLAUDE_DIRECT=1 或交互终端 → exec 真实 claude，不拦截（HomeFragment 直接调用）
     *   - 其他（AgentServer 后台调用）→ 把 prompt(base64) 和 JSONL 输出追加到 pipe 文件
     */
    private static void injectClaudeWrapper(File claudeHome) {
        File localBin = new File(claudeHome, ".local/bin");
        localBin.mkdirs();
        File wrapper = new File(localBin, "claude");
        // 每次启动更新，保证脚本与当前版本一致
        writeFile(wrapper,
            "#!/bin/bash\n"
            + "_REAL=/usr/local/bin/claude\n"
            + "_PIPE=/home/claude/.agentserver-pipe.jsonl\n"
            + "# 透传条件 1：HomeFragment 显式 CLAUDE_DIRECT=1\n"
            + "# 透传条件 2：交互终端（tty stdin）\n"
            + "if [ -n \"$CLAUDE_DIRECT\" ] || [ -t 0 ]; then exec \"$_REAL\" \"$@\"; fi\n"
            + "# 透传条件 3：流式 JSON 输入模式（agentserver 长驻通道，"
            +   "stdin 永不关闭，绝不能拦截）。同时给 stdout 做 tee 让 App 仍能看到响应。\n"
            + "_STREAM_IN=\n"
            + "_PREV=\n"
            + "for _arg in \"$@\"; do\n"
            + "  if [ \"$_PREV\" = \"--input-format\" ] && [ \"$_arg\" = \"stream-json\" ]; then\n"
            + "    _STREAM_IN=1; break\n"
            + "  fi\n"
            + "  _PREV=\"$_arg\"\n"
            + "done\n"
            + "if [ -n \"$_STREAM_IN\" ]; then\n"
            + "  _TS=$(date +%s%3N 2>/dev/null || echo \"$(( $(date +%s) * 1000 ))\")\n"
            + "  printf '{\"type\":\"as_stream_session_start\",\"ts\":%s}\\n' \"$_TS\" >> \"$_PIPE\"\n"
            + "  # tee stdin: 每行 base64 包装写入 pipe，同时透传给真 claude；tee stdout 给 pipe\n"
            + "  cat | tee >( while IFS= read -r _l; do"
            +   " printf '{\"type\":\"as_stream_in\",\"b64\":\"%s\"}\\n'"
            +   " \"$(printf '%s' \"$_l\" | base64 -w0)\" >> \"$_PIPE\"; done )"
            +   " | \"$_REAL\" \"$@\" 2>&1 | tee -a \"$_PIPE\"\n"
            + "  exit ${PIPESTATUS[1]}\n"
            + "fi\n"
            + "# 透传条件 4：未传 -p / --print（长驻 / SDK 实例，不能拦截）\n"
            + "_PRINT_MODE=\n"
            + "for _arg in \"$@\"; do\n"
            + "  case \"$_arg\" in\n"
            + "    -p|--print) _PRINT_MODE=1; break;;\n"
            + "  esac\n"
            + "done\n"
            + "if [ -z \"$_PRINT_MODE\" ]; then exec \"$_REAL\" \"$@\"; fi\n"
            + "# 拦截路径：HomeFragment 风格的一次性 -p 任务（stdin 是有限内容）\n"
            + "_TS=$(date +%s%3N 2>/dev/null || echo \"$(( $(date +%s) * 1000 ))\")\n"
            + "_TMP=$(mktemp /tmp/cc-XXXXXX)\n"
            + "cat > \"$_TMP\"\n"
            + "_B64=$(base64 -w 0 < \"$_TMP\")\n"
            + "printf '{\"type\":\"as_task_start\",\"ts\":%s,\"prompt_b64\":\"%s\"}\\n'"
            + " \"$_TS\" \"$_B64\" >> \"$_PIPE\"\n"
            + "\"$_REAL\" \"$@\" < \"$_TMP\" | tee -a \"$_PIPE\"\n"
            + "_EXIT=${PIPESTATUS[0]}\n"
            + "_TS2=$(date +%s%3N 2>/dev/null || echo \"$_TS\")\n"
            + "printf '{\"type\":\"as_task_end\",\"ts\":%s}\\n' \"$_TS2\" >> \"$_PIPE\"\n"
            + "rm -f \"$_TMP\"\n"
            + "exit $_EXIT\n"
        );
        wrapper.setExecutable(true, false);
    }

    private static void writeFile(File dest, String content) {
        try (java.io.FileWriter w = new java.io.FileWriter(dest)) {
            w.write(content);
        } catch (IOException ignored) {}
    }

    private static String buildPhoneSkillContent() {
        return "# Android 手机操控\n\n"
            + "你拥有以下 MCP 工具，可直接控制这台 Android 手机。\n"
            + "**立即开始操作，无需询问是否有能力。**\n\n"
            + "## 工具速查\n"
            + "| 工具 | 用途 |\n"
            + "|------|------|\n"
            + "| `screen.capture` | 截取当前屏幕（base64 JPEG）|\n"
            + "| `ui.get_accessibility_tree` | 获取 UI 元素树与坐标 |\n"
            + "| `ui.tap` | 点击坐标 `{x, y}` |\n"
            + "| `ui.click_text` | 点击含指定文字的元素 |\n"
            + "| `ui.input_text` | 在焦点输入框输入文字 |\n"
            + "| `ui.swipe` | 滑动手势 |\n"
            + "| `app.open` | 通过包名启动应用 |\n"
            + "| `app.get_current_activity` | 查看当前 Activity |\n"
            + "| `adb.get_status` | 检查宿主机 ADB Companion |\n"
            + "| `adb.screenshot` | 通过 ADB 截图 |\n"
            + "| `adb.tap` / `adb.swipe` | ADB 坐标点击与滑动 |\n"
            + "| `adb.input_text` / `adb.keyevent` | ADB 文本输入与按键 |\n"
            + "| `camera.take_photo` | 拍照 |\n\n"
            + "## 标准操作循环\n"
            + "```\n"
            + "screen.capture → 观察屏幕\n"
            + "ui.get_accessibility_tree → 定位目标元素\n"
            + "ui.tap / ui.click_text / ui.input_text → 执行操作\n"
            + "screen.capture → 确认结果\n"
            + "重复直到任务完成\n"
            + "```\n\n"
            + "## 回退规则\n"
            + "- 微信、小程序、WebView 或自绘界面可能返回空节点树。\n"
            + "- 节点树为空时，不要反复尝试 `ui.click_text`；改用截图识别坐标。\n"
            + "- `adb.get_status` 在线时，可用 `adb.*` 工具作为高级回退；不要在 proot 里直接运行 `adb shell`。\n"
            + "- 发送、删除、支付、转账、授权等动作必须先确认或只在测试白名单内执行。\n";
    }

    private static void injectMcpSettings(File claudeDir) {
        claudeDir.mkdirs();
        File settingsFile = new File(claudeDir, "settings.json");
        try {
            JSONObject settings = new JSONObject();
            if (settingsFile.exists()) {
                String existing = new String(
                    Files.readAllBytes(settingsFile.toPath()), StandardCharsets.UTF_8);
                try { settings = new JSONObject(existing); } catch (JSONException ignored) {}
            }

            boolean dirty = false;

            // 1. MCP server 注册
            JSONObject servers = settings.optJSONObject("mcpServers");
            if (servers == null || !servers.has("android-mcp")) {
                if (servers == null) {
                    servers = new JSONObject();
                    settings.put("mcpServers", servers);
                }
                JSONObject entry = new JSONObject();
                entry.put("type", "http");
                entry.put("url", "http://127.0.0.1:8765/mcp");
                servers.put("android-mcp", entry);
                dirty = true;
            }

            // 2. 跳过权限对话框——AgentServer 在后台无人值守，必须设置此项；
            //    否则 Claude Code 每次调用 MCP 工具都会弹出确认对话框并永久阻塞。
            if (!settings.optBoolean("dangerouslySkipPermissions", false)) {
                settings.put("dangerouslySkipPermissions", true);
                dirty = true;
            }

            if (dirty) writeFile(settingsFile, settings.toString(2));
        } catch (Exception ignored) {}
    }

    /** 向终端 stdin 写一条 echo 命令（shell 会立即执行并显示输出）。 */
    private static void writeEcho(TerminalSession session, String msg) {
        String safe = msg.replace("'", "'\\''");
        session.write("echo '" + safe + "'\n");
    }

    private static String shellSingleQuoteForScript(String value) {
        return (value == null ? "" : value).replace("'", "'\\''");
    }

    /** 等待 assets 准备完毕，然后向终端写入安装脚本命令。 */
    private void launchSetupScript(TerminalSession session) {
        // 每次启动都写入/更新 provider instructions，让 agents 知道它们的 Android 能力
        injectProviderInstructions();

        // 若后台提取仍在进行，最多等待 5 秒，超时则放弃本地包走网络
        if (!mExtractionDone) {
            long deadline = System.currentTimeMillis() + 5000;
            while (!mExtractionDone && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) { break; }
            }
        }
        // 等待 AgentServer asset 提取完成（最多 5 秒），避免 cp 时文件不存在被静默跳过
        if (mAgentServerManager != null) {
            mAgentServerManager.awaitExtraction(5000);
        }
        // 等待 Loom asset 提取完成（最多 5 秒），避免复制时错过离线 addon
        if (mLoomManager != null) {
            mLoomManager.awaitExtraction(5000);
        }
        // 脚本写入临时文件再执行，避免超长单行命令超出 pty 输入缓冲区（N_TTY_BUF_SIZE=4096）被截断
        String scriptPath = writeScriptToFile();
        if (scriptPath != null) {
            session.write("bash '" + scriptPath + "'\n");
        } else {
            session.write(buildUbuntuCommand()); // 兜底（文件写入失败时）
        }
    }

    /**
     * 将安装脚本写入 $HOME/.ubuntu-setup.sh 并返回绝对路径。
     * 失败时返回 null。
     */
    @Nullable
    private String writeScriptToFile() {
        File scriptFile = new File(mActivity.getFilesDir(), "home/.ubuntu-setup.sh");
        try {
            scriptFile.getParentFile().mkdirs();
            try (java.io.FileWriter w = new java.io.FileWriter(scriptFile)) {
                w.write(buildUbuntuCommand());
            }
            return scriptFile.getAbsolutePath();
        } catch (IOException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // 资产提取（后台线程）
    // -------------------------------------------------------------------------

    private void startRootfsExtraction() {
        Thread t = new Thread(() -> {
            // 顺手把 Termux 端依赖（proot deb / proot-distro tar.gz）也提取到 Termux home
            extractTermuxToolsToHome();
            mLocalRootfsPath = extractRootfsAsset();
            mExtractionDone = true;
        }, "ubuntu-rootfs-extract");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 把 termux-tools/ 下的静态依赖（proot deb + proot-distro tar.gz）解压到 Termux $HOME。
     * 安装脚本会从这里 dpkg -i / tar -xzf 安装，**不依赖 apt 仓库**。
     */
    private void extractTermuxToolsToHome() {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        copyAsset(LIBTALLOC_DEB_ASSET, home + "/.termux-tools/libtalloc.deb");
        copyAsset(FILE_DEB_ASSET,      home + "/.termux-tools/file.deb");
        copyAsset(PROOT_DEB_ASSET,     home + "/.termux-tools/proot.deb");
        copyAsset(PROOT_DISTRO_ASSET,  home + "/.termux-tools/proot-distro.tgz");
    }

    /** 把 asset 复制到目标路径（若大小一致跳过）。失败静默。 */
    private void copyAsset(String assetName, String destPath) {
        try {
            File dest = new File(destPath);
            long assetSize;
            try (InputStream probe = mActivity.getAssets().open(assetName)) {
                assetSize = probe.available();
                // available() 对 asset 流近似准；做一次完整读确保大小（小文件成本可忽略）
                long real = 0;
                byte[] tmp = new byte[8192];
                int n;
                while ((n = probe.read(tmp)) != -1) real += n;
                assetSize = real;
            }
            if (dest.exists() && dest.length() == assetSize) return; // 已就绪
            dest.getParentFile().mkdirs();
            File tmpFile = new File(destPath + ".tmp");
            try (InputStream in = mActivity.getAssets().open(assetName);
                 FileOutputStream out = new FileOutputStream(tmpFile)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            }
            if (!tmpFile.renameTo(dest)) tmpFile.delete();
        } catch (IOException ignored) {
            // 资产缺失或写入失败：让安装脚本里 [ -f ... ] 兜底跳过
        }
    }

    /**
     * 将 assets/ubuntu/ubuntu-rootfs-{arch}.tar.{gz,xz} 复制到 $HOME 目录。
     * 若文件已存在且非空则直接返回路径（跳过重复提取）。
     * @return 本地文件的绝对路径，或 null（资产不存在 / 提取失败）
     */
    @Nullable
    private String extractRootfsAsset() {
        // 检测 assets 中实际存在的 rootfs 文件（.tar.gz 优先）
        String assetFileName = resolveRootfsAssetName();
        if (assetFileName == null) return null; // assets 中没有预置文件，走网络

        String assetName = ASSET_DIR + "/" + assetFileName;
        // 本地文件名必须保留原始扩展名：proot-distro 依赖 URL 扩展名选择解压器
        String ext = assetFileName.endsWith(".tar.gz") ? ".tar.gz" : ".tar.xz";
        String localFileName = LOCAL_ROOTFS_BASE + ext;

        // 目标路径：/data/data/<pkg>/files/home/.ubuntu-local-rootfs.tar.{gz,xz}
        // getFilesDir() = .../files，加 "home" = .../files/home（Termux $HOME）
        // 注意：getFilesDir().getParent() = .../（缺少 files 层），是错误写法
        File homeDir = new File(mActivity.getFilesDir(), "home");
        File destFile = new File(homeDir, localFileName);

        // 已存在且非空则复用
        if (destFile.exists() && destFile.length() > 0) {
            return destFile.getAbsolutePath();
        }

        homeDir.mkdirs();
        File tmpFile = new File(homeDir, localFileName + ".tmp");
        try (InputStream in = mActivity.getAssets().open(assetName);
             OutputStream out = new FileOutputStream(tmpFile)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
        } catch (IOException e) {
            tmpFile.delete();
            return null;
        }

        if (!tmpFile.renameTo(destFile)) {
            tmpFile.delete();
            return null;
        }
        return destFile.getAbsolutePath();
    }

    /**
     * 检测 assets 中实际存在的 rootfs 文件名（含扩展名）。
     * 优先 .tar.gz，兼容 .tar.xz。找不到返回 null。
     */
    @Nullable
    private String resolveRootfsAssetName() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        // proot-distro 架构名
        String arch;
        switch (abi) {
            case "armeabi-v7a": arch = "arm";    break;
            case "x86_64":      arch = "x86_64"; break;
            case "x86":         arch = "x86";    break;
            default:            arch = "aarch64"; break; // arm64-v8a
        }
        // Ubuntu 官方 base 包使用的架构名（与 proot-distro 不同）
        String ubuntuArch;
        switch (abi) {
            case "armeabi-v7a": ubuntuArch = "armhf"; break;
            case "x86_64":      ubuntuArch = "amd64"; break;
            case "x86":         ubuntuArch = "i386";  break;
            default:            ubuntuArch = "arm64"; break; // arm64-v8a
        }

        try {
            String[] files = mActivity.getAssets().list(ASSET_DIR);
            if (files != null) {
                // 第1优先：proot-distro 专用包 ubuntu-questing-{arch}-pd-*.tar.xz
                // 命名规则与 easycli.sh CDN 下载链接完全一致，直接替换 URL 即可使用，无需额外处理
                for (String f : files) {
                    if (f.startsWith("ubuntu-questing-") && f.contains("-" + arch + "-")
                            && (f.endsWith(".tar.xz") || f.endsWith(".tar.gz"))) {
                        return f;
                    }
                }
                // 第2优先：Ubuntu 官方 base 包 ubuntu-base-*-{ubuntuArch}.tar.{gz,xz}
                for (String f : files) {
                    if (f.startsWith("ubuntu-base-") && f.contains("-" + ubuntuArch)
                            && (f.endsWith(".tar.gz") || f.endsWith(".tar.xz"))) {
                        return f;
                    }
                }
            }
        } catch (IOException ignored) {}

        // 第3优先：ubuntu-rootfs-{arch}.tar.{gz,xz}（自定义打包格式兼容）
        for (String ext : SUPPORTED_EXTENSIONS) {
            String name = "ubuntu-rootfs-" + arch + ext;
            try {
                mActivity.getAssets().open(ASSET_DIR + "/" + name).close();
                return name;
            } catch (IOException ignored) {}
        }
        return null; // assets 中没有预置文件
    }

    // -------------------------------------------------------------------------
    // Shell 命令生成
    // -------------------------------------------------------------------------

    private String buildUbuntuCommand() {
        String localPath = mLocalRootfsPath != null ? mLocalRootfsPath : "";

        // rootfs CDN 镜像列表：每对加单引号避免 | 被 bash 解析为管道符
        StringBuilder rootfsMirrors = new StringBuilder();
        for (String[] pair : ROOTFS_CDN_MIRRORS) {
            rootfsMirrors.append("'").append(pair[0]).append("|").append(pair[1]).append("' ");
        }

        StringBuilder sb = new StringBuilder();

        // ── 入口判断：已在 ubuntu 则直接跳过 ──────────────────────────────────
        sb.append("if [ \"${PROOT_DISTRO_NAME:-}\" = \"ubuntu\" ]; then ")
          .append("echo \"[*] Already in ubuntu.\"; ")
          .append("else ");

        // ── 工具函数 ──────────────────────────────────────────────────────────
        // retry_cmd: 带退避重试
        sb.append("retry_cmd() { ")
          .append("cmd=\"$1\"; label=\"$2\"; attempts=${3:-3}; delay=${4:-5}; ")
          .append("i=1; while [ $i -le $attempts ]; do ")
          .append("echo \"[*] ${label} (try ${i}/${attempts})\"; ")
          .append("eval \"$cmd\" && return 0; ")
          .append("echo \"[!] failed (exit $?).\"; ")
          .append("if [ $i -lt $attempts ]; then echo \"[*] retry in ${delay}s\"; sleep $delay; fi; ")
          .append("i=$((i+1)); delay=$((delay*2)); ")
          .append("done; return 1; }; ");

        // override_distro_setup: 直接在 proot-distro 系统文件末尾追加 distro_setup 无操作覆盖。
        // proot-distro 4.38 实际读 $PREFIX/etc/proot-distro/ubuntu.sh，而非用户配置目录；
        // bash 取最后一个同名函数，追加到文件末尾即可覆盖原实现，跳过触发 signal 11 的 proot 内 syscall。
        // grep 防止重复追加（多次安装时幂等）。
        sb.append("override_distro_setup() { ")
          .append("sys=\"$PREFIX/etc/proot-distro/ubuntu.sh\"; ")
          .append("if [ -f \"$sys\" ]; then ")
          .append("grep -qF 'distro_setup() { true; }' \"$sys\" 2>/dev/null || ")
          .append("{ printf '\\ndistro_setup() { true; }\\n' >> \"$sys\"; ")
          .append("echo \"[*] distro_setup overridden in system ubuntu.sh (signal-11 bypass)\"; }; ")
          .append("fi; }; ");

        // backup_sys: 首次调用时将系统 ubuntu.sh 备份为 ubuntu.sh.orig（幂等）
        // restore_sys: 从备份还原系统文件，再追加 distro_setup 覆盖（幂等）
        // proot-distro 4.38+ 只读 $PREFIX/etc/proot-distro/ubuntu.sh，用户配置目录对 URL 无效
        sb.append("backup_sys() { ")
          .append("sys=\"$PREFIX/etc/proot-distro/ubuntu.sh\"; ")
          .append("[ -f \"$sys\" ] && [ ! -f \"${sys}.orig\" ] && { cp \"$sys\" \"${sys}.orig\"; echo \"[*] sys ubuntu.sh backed up\"; }; }; ");

        sb.append("restore_sys() { ")
          .append("sys=\"$PREFIX/etc/proot-distro/ubuntu.sh\"; ")
          // 若还没有备份则先备份（兼容无本地包时直接走到 2b/2c 的场景）
          .append("[ -f \"$sys\" ] && [ ! -f \"${sys}.orig\" ] && cp \"$sys\" \"${sys}.orig\"; ")
          .append("[ -f \"${sys}.orig\" ] && { cp \"${sys}.orig\" \"$sys\"; echo \"[*] sys ubuntu.sh restored\"; }; ")
          .append("override_distro_setup; }; ");

        // apply_rootfs_mirror: 还原系统 ubuntu.sh 后在原文件上替换 CDN 域名
        // 每次从干净备份出发，防止多个镜像叠加修改
        sb.append("apply_rootfs_mirror() { ")
          .append("pair=\"$1\"; ")
          .append("orig=\"${pair%%|*}\"; repl=\"${pair##*|}\"; ")
          .append("sys=\"$PREFIX/etc/proot-distro/ubuntu.sh\"; ")
          .append("restore_sys; ")
          .append("[ -f \"$sys\" ] && sed -i \"s|${orig}|${repl}|g\" \"$sys\"; ")
          .append("echo \"[*] rootfs mirror: $orig -> $repl\"; }; ");

        // apply_local_rootfs: 直接修改系统 ubuntu.sh（proot-distro 4.38+ 只读系统文件）
        // 按 URL 内容替换而非按变量名，兼容 DISTRO_ARCHITECTURE_AARCH64_TARBALL_URL 等所有命名格式
        if (!localPath.isEmpty()) {
            sb.append("apply_local_rootfs() { ")
              .append("sys=\"$PREFIX/etc/proot-distro/ubuntu.sh\"; ")
              .append("backup_sys; restore_sys; ")
              .append("if [ -f \"$sys\" ]; then ")
              // 替换 easycli.sh / Ubuntu CDN 任意 URL → 本地 file:// 路径（覆盖所有变量命名格式）
              .append("_lu=\"file://").append(localPath).append("\"; ")
              .append("sed -i \"s|https://easycli.sh/[^[:space:]'\\\"]*|$_lu|g\" \"$sys\"; ")
              .append("sed -i \"s|https://cdimage.ubuntu.com/[^[:space:]'\\\"]*|$_lu|g\" \"$sys\"; ")
              .append("sed -i \"s|https://cloud-images.ubuntu.com/[^[:space:]'\\\"]*|$_lu|g\" \"$sys\"; ")
              // 清空 SHA256：保留变量名前缀，只清空值（兼容带架构名的变量如 ..._AARCH64_TARBALL_SHA256=）
              .append("sed -i 's/\\(.*SHA256=\\).*/\\1\"\"/g' \"$sys\"; ")
              .append("override_distro_setup; ")
              .append("echo \"[*] sys ubuntu.sh -> local bundle\"; ")
              // 打印补丁后的 URL 行，用于确认替换是否生效
              .append("grep -i 'URL\\|SHA256' \"$sys\" | grep -v '^[[:space:]]*#' | head -6 | sed 's/^/[D] /'; ")
              .append("fi; }; ");
        }

        // ── Step 1: 安装 proot + proot-distro（静态打包，不走 apt 仓库）───────
        // 来源：APK assets/termux-tools/，已在启动时 copy 到 $HOME/.termux-tools/
        //   - proot.deb              ← proot 5.1.107-71 (aarch64)
        //   - proot-distro.tar.gz    ← proot-distro 4.38.0 源码（shell 实现，最后一个 shell 版）
        // 锁死版本避免被 apt upgrade 拉到上游 5.x（Python 重写，会破坏我们的 ProcessBuilder 调用）
        sb.append(buildBundledTermuxToolsInstallShell());

        // ── Step 2: 安装 Ubuntu rootfs ────────────────────────────────────────
        // proot-distro 补丁必须每次 setup 都执行：离线快照路径会提前创建 rootfs，
        // 不能把补丁绑定在“安装 rootfs”分支里。
        sb.append(buildProotDistroPatchShell());
        sb.append(buildRootfsGuardShell());
        sb.append("if [ \"$auto_ok\" = \"1\" ] && ! ubuntu_rootfs_ok; then ")
          .append("echo \"[*] Installing Ubuntu...\"; ")
          .append("ubuntu_ok=0; ");

        // 2a: 优先本地预置包
        if (!localPath.isEmpty()) {
            sb.append("if [ -f \"").append(localPath).append("\" ]; then ")
              .append("echo \"[*] Found local bundle, trying offline install...\"; ")
              .append("apply_local_rootfs; ")
              .append("if proot-distro install ubuntu 2>&1; then ")
              .append("ubuntu_ok=1; echo \"[*] Installed from local bundle.\"; ")
              .append("else ")
              .append("echo \"[!] Local bundle failed, falling back to network.\"; ")
              .append("restore_sys; ")
              .append("fi; ")
              .append("fi; ");
        }

        // 2b: 直连网络（从还原的干净系统文件出发，只追加 distro_setup 覆盖）
        sb.append("if [ \"$ubuntu_ok\" != \"1\" ]; then ")
          .append("echo \"[*] Trying direct network install...\"; ")
          .append("restore_sys; ")
          .append("proot-distro install ubuntu 2>&1 && ubuntu_ok=1; ")
          .append("fi; ");

        // 2c: 依次尝试 Ubuntu CDN 国内镜像
        sb.append("if [ \"$ubuntu_ok\" != \"1\" ]; then ")
          .append("echo \"[*] Trying Ubuntu CDN mirrors...\"; ")
          .append("for pair in ").append(rootfsMirrors).append("; do ")
          .append("apply_rootfs_mirror \"$pair\"; ")
          .append("proot-distro install ubuntu 2>&1 && { ubuntu_ok=1; break; }; ")
          .append("echo \"[!] mirror failed, trying next...\"; ")
          .append("done; ")
          .append("fi; ");

        // 全部失败
        sb.append("if [ \"$ubuntu_ok\" != \"1\" ]; then ")
          .append("echo \"[!] All install attempts failed.\"; ")
          .append("echo \"    Tip: pre-place ubuntu-rootfs-aarch64.tar.xz in app assets to install offline.\"; ")
          .append("auto_ok=0; fi; ")
          .append("fi; ");

        sb.append("if [ \"$auto_ok\" = \"1\" ]; then ")
          .append(buildUbuntuSnapshotPathRepairShell())
          .append("fi; ");

        // ── Step 2.9: 注入 Claude Code 安装向导（幂等，每次启动均执行）────────
        // inner 脚本由 AutoClaudeManager 在后台写入 Termux $HOME；
        // 此处将其复制到 Ubuntu rootfs 并在 /root/.bashrc 追加 source hook。
        // inner 脚本首行检查 claude 是否已安装——已装则自我清除，未装则交互引导。
        String claudeInnerPath = new File(mActivity.getFilesDir(),
            AutoClaudeManager.INNER_SCRIPT_REL).getAbsolutePath();
        String codexInnerPath = new File(mActivity.getFilesDir(),
            AutoCodexManager.INNER_SCRIPT_REL).getAbsolutePath();
        String capabilitiesPath = new File(mActivity.getFilesDir(),
            CapabilitiesManager.CAPABILITIES_FILE_REL).getAbsolutePath();
        String providerFilePath = buildProviderFilePathForUbuntu();
        String agentTgzPath = new File(mActivity.getFilesDir(),
            AutoAgentServerManager.ASSET_TGZ_REL).getAbsolutePath();
        String agentInnerPath = new File(mActivity.getFilesDir(),
            AutoAgentServerManager.INNER_SCRIPT_REL).getAbsolutePath();
        String loomTgzPath = mLoomManager == null ? "" : mLoomManager.getTgzPath();
        String loomInnerPath = mLoomManager == null ? "" : mLoomManager.getInnerScriptPath();
        sb.append("if [ \"$auto_ok\" = \"1\" ]; then ")
          .append("_ubr=\"$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\"; ")
          .append("_cis=\"").append(claudeInnerPath).append("\"; ")
          .append("_cxis=\"").append(codexInnerPath).append("\"; ")
          .append("if [ -d \"$_ubr/root\" ]; then ")
          // 注入 claude 安装向导
          .append("[ -f \"$_cis\" ] && cp \"$_cis\" \"$_ubr/root/.claude-setup.sh\" && ")
          .append("{ grep -qF '.claude-setup' \"$_ubr/root/.bashrc\" 2>/dev/null || ")
          .append("printf '\\n[ -f ~/.claude-setup.sh ] && . ~/.claude-setup.sh\\n' ")
          .append(">> \"$_ubr/root/.bashrc\"; }; ")
          // 注入 Codex 安装向导（默认终端仍切换 claude，后续 provider 路由显式以 codex 用户运行）
          .append("[ -f \"$_cxis\" ] && cp \"$_cxis\" \"$_ubr/root/.codex-setup.sh\" && ")
          .append("{ grep -qF '.codex-setup' \"$_ubr/root/.bashrc\" 2>/dev/null || ")
          .append("printf '\\n[ -f ~/.codex-setup.sh ] && . ~/.codex-setup.sh\\n' ")
          .append(">> \"$_ubr/root/.bashrc\"; }; ")
          // 注入 AgentServer 安装包 + 安装向导（在 Claude hook 之后，确保安装时 Claude 已就绪）
          .append("if [ -f \"").append(agentTgzPath).append("\" ] && [ -s \"").append(agentTgzPath).append("\" ]; then ")
          .append("mkdir -p \"$_ubr/tmp\" && ")
          .append("cp \"").append(agentTgzPath).append("\" \"$_ubr/tmp/agentserver-linux-arm64.tar.gz\" && ")
          .append("echo \"[*] agentserver 安装包已复制到 Ubuntu /tmp/\"; ")
          .append("else echo \"[!] agentserver 安装包未就绪（路径: ").append(agentTgzPath).append("），将由脚本联网下载\"; fi; ")
          .append("[ -f \"").append(agentInnerPath).append("\" ] && ")
          .append("cp \"").append(agentInnerPath).append("\" \"$_ubr/root/.agentserver-setup.sh\" && ")
          .append("{ grep -qF '.agentserver-setup' \"$_ubr/root/.bashrc\" 2>/dev/null || ")
          .append("printf '\\n[ -f ~/.agentserver-setup.sh ] && . ~/.agentserver-setup.sh\\n' ")
          .append(">> \"$_ubr/root/.bashrc\"; }; ")
          // 注入 Loom addon + 安装向导（独立于 AgentServer 包，复用同一个 Ubuntu runtime）
          .append("if [ -n \"").append(loomTgzPath).append("\" ] && [ -f \"").append(loomTgzPath).append("\" ] && [ -s \"").append(loomTgzPath).append("\" ]; then ")
          .append("mkdir -p \"$_ubr/tmp\" && ")
          .append("cp \"").append(loomTgzPath).append("\" \"$_ubr/tmp/loom-linux-arm64.tgz\" && ")
          .append("echo \"[*] Loom addon 已复制到 Ubuntu /tmp/\"; ")
          .append("else echo \"[!] Loom addon 未就绪，将由脚本联网下载\"; fi; ")
          .append("if [ -n \"").append(loomInnerPath).append("\" ] && [ -f \"").append(loomInnerPath).append("\" ]; then ")
          .append("cp \"").append(loomInnerPath).append("\" \"$_ubr/root/.loom-setup.sh\" && ")
          .append("{ grep -qF '.loom-setup' \"$_ubr/root/.bashrc\" 2>/dev/null || ")
          .append("printf '\\n[ -f ~/.loom-setup.sh ] && . ~/.loom-setup.sh\\n' ")
          .append(">> \"$_ubr/root/.bashrc\"; }; fi; ")
          // root/.bashrc 末尾注入 provider dispatcher，使终端跟随 App 当前 provider 用户
          .append("printf '").append(shellSingleQuoteForScript(
                buildProviderLoginDispatcherFileContent(providerFilePath)))
          .append("' > \"$_ubr/root/.assistant-login-dispatcher.sh\"; ")
          .append("chmod +x \"$_ubr/root/.assistant-login-dispatcher.sh\" 2>/dev/null; ")
          .append("sed -i '/exec su - claude/d;/.assistant-login-dispatcher/d' \"$_ubr/root/.bashrc\" 2>/dev/null || true; ")
          .append("printf '\\n").append(buildProviderLoginDispatcherHook().replace("'", "'\\''"))
          .append("\\n' >> \"$_ubr/root/.bashrc\"; ")
          // 建 capabilities.json 软链接（放 /root/ 供 setup 阶段用，/home/claude/ 供运行时用）
          .append("ln -sf \"").append(capabilitiesPath).append("\" ")
          .append("\"$_ubr/root/capabilities.json\" 2>/dev/null; ")
          // 建 termux-* wrapper 脚本（HTTP 桥接版）
          // Termux 二进制为 Android bionic 编译，Ubuntu glibc 环境无法执行（exec 会报错）。
          // 改用 curl 调用 ApiHttpBridgeServer（Android 侧 HTTP 服务，127.0.0.1:PORT）。
          .append("mkdir -p \"$_ubr/usr/local/bin\"; ")
          .append("_bp=").append(ApiHttpBridgeServer.PORT).append("; ")
          .append("printf '#!/bin/sh\\ncurl -sf http://127.0.0.1:%s/battery\\n' \"$_bp\" ")
          .append("> \"$_ubr/usr/local/bin/termux-battery-status\" && ")
          .append("chmod +x \"$_ubr/usr/local/bin/termux-battery-status\" 2>/dev/null; ")
          .append("printf '#!/bin/sh\\ncurl -sf http://127.0.0.1:%s/camera\\n' \"$_bp\" ")
          .append("> \"$_ubr/usr/local/bin/termux-camera-info\" && ")
          .append("chmod +x \"$_ubr/usr/local/bin/termux-camera-info\" 2>/dev/null; ")
          .append("printf '#!/bin/sh\\ncurl -sf http://127.0.0.1:%s/sensors\\n' \"$_bp\" ")
          .append("> \"$_ubr/usr/local/bin/termux-sensor\" && ")
          .append("chmod +x \"$_ubr/usr/local/bin/termux-sensor\" 2>/dev/null; ")
          .append("printf '#!/bin/sh\\ncurl -sf http://127.0.0.1:%s/wifi\\n' \"$_bp\" ")
          .append("> \"$_ubr/usr/local/bin/termux-wifi-connectioninfo\" && ")
          .append("chmod +x \"$_ubr/usr/local/bin/termux-wifi-connectioninfo\" 2>/dev/null; ")
          .append("printf '#!/bin/sh\\ncurl -sf http://127.0.0.1:%s/clipboard\\n' \"$_bp\" ")
          .append("> \"$_ubr/usr/local/bin/termux-clipboard-get\" && ")
          .append("chmod +x \"$_ubr/usr/local/bin/termux-clipboard-get\" 2>/dev/null; ")
          .append("echo \"[*] Claude + Codex + AgentServer + Loom setup + capabilities ready.\"; fi; fi; ");

        // ── Step 2.95: 在 Ubuntu 内创建 provider 用户（非交互），并建 capabilities 软链接 ──
        final String capPath = capabilitiesPath;
        sb.append("if [ \"$auto_ok\" = \"1\" ]; then ")
          .append("proot-distro login ubuntu -- sh -c ")
          .append("'id claude >/dev/null 2>&1 || useradd -m -s /bin/bash claude; ")
          .append("id codex >/dev/null 2>&1 || useradd -m -s /bin/bash codex' 2>/dev/null || true; ")
          // capabilities.json 软链接到 claude 用户 home（运行时需要）
          .append("_ubr2=\"$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\"; ")
          .append("mkdir -p \"$_ubr2/home/claude\" 2>/dev/null; ")
          .append("ln -sf \"").append(capPath).append("\" \"$_ubr2/home/claude/capabilities.json\" 2>/dev/null; ")
          .append("mkdir -p \"$_ubr2/home/codex\" 2>/dev/null; ")
          .append("ln -sf \"").append(capPath).append("\" \"$_ubr2/home/codex/capabilities.json\" 2>/dev/null; ")
          .append("fi; ");

        // ── Step 3: 登录 Ubuntu ───────────────────────────────────────────────
        // 登录策略：依次尝试四种组合，直到成功（用 || 链：前一个非零退出才执行下一个）。
        // root/.bashrc 末尾已注入 provider dispatcher，终端会切到当前选中的 provider 用户。
        // signal 11 根因：Ubuntu 25.10 的 glibc/bash 在启动时调用了 proot 未完全拦截的 syscall。
        //   - --kernel 5.4.0：让 glibc 认为内核较旧，退回不依赖新 syscall 的代码路径
        //   - -- /bin/sh：用比 bash 更简单的 shell，减少对 syscall 的依赖
        sb.append("if [ \"$auto_ok\" = \"1\" ]; then ")
          .append("proot-distro login ubuntu || ")
          .append("proot-distro login --kernel 5.4.0 ubuntu || ")
          .append("proot-distro login --kernel 5.4.0 ubuntu -- /bin/sh || ")
          .append("proot-distro login ubuntu -- /bin/sh; ")
          .append("fi; ");

        sb.append("fi\n");
        return sb.toString();
    }
}
