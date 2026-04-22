package com.termux.app.autotasks;

import android.content.res.AssetManager;

import androidx.annotation.NonNull;

import com.termux.app.TermuxActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Phase 3b: 自动在 Ubuntu 内安装 AgentServer（离线 tar.gz 包）。
 *
 * 工作原理：
 * 1. 后台将 assets/agentserver-linux-arm64.tar.gz 提取到 Termux filesDir
 * 2. 后台写交互式安装脚本到 filesDir
 * 3. AutoUbuntuManager 在注入 Claude 脚本时同步注入 AgentServer 脚本和 tar.gz
 * 4. Ubuntu 首次登录时 .bashrc 触发：先运行 Claude 安装，完成后运行 AgentServer 安装
 *
 * 注意：AgentServer hook 追加在 Claude hook 之后，所以 AgentServer 安装时 Claude 已就绪。
 */
public class AutoAgentServerManager {

    /** assets 中预置的 AgentServer 二进制包文件名。
     *  使用 .tgz 扩展名：AAPT2 会对 .gz 结尾的文件自动解压并改名，用 .tgz 可绕过此行为。 */
    static final String ASSET_TGZ_NAME   = "agentserver-linux-arm64.tgz";

    /** 提取到 Termux filesDir 后的相对路径。 */
    static final String ASSET_TGZ_REL    = "home/.agentserver/" + ASSET_TGZ_NAME;

    /** 安装脚本在 filesDir 中的相对路径（AutoUbuntuManager 注入时使用）。 */
    static final String INNER_SCRIPT_REL = "home/.agentserver-inner-setup.sh";

    private final TermuxActivity mActivity;
    private volatile boolean mExtractionDone = false;
    private volatile boolean mWasUpdated = false;

    public AutoAgentServerManager(@NonNull TermuxActivity activity) {
        mActivity = activity;
        Thread t = new Thread(this::prepare, "agentserver-setup-prepare");
        t.setDaemon(true);
        t.start();
    }

    /** 最多等待 timeoutMs 毫秒直到 asset 提取完成。 */
    public void awaitExtraction(long timeoutMs) {
        if (mExtractionDone) return;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!mExtractionDone && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
        }
    }

    /** 安装脚本的绝对路径（注入前获取，路径固定）。 */
    @NonNull
    public String getInnerScriptPath() {
        return new File(mActivity.getFilesDir(), INNER_SCRIPT_REL).getAbsolutePath();
    }

    /** 已提取的 tar.gz 的绝对路径。 */
    @NonNull
    public String getTgzPath() {
        return new File(mActivity.getFilesDir(), ASSET_TGZ_REL).getAbsolutePath();
    }

    // -------------------------------------------------------------------------
    // 后台准备：提取 asset + 写脚本
    // -------------------------------------------------------------------------

    private void prepare() {
        File dest = new File(mActivity.getFilesDir(), ASSET_TGZ_REL);
        boolean prevExisted = dest.exists() && dest.length() > 0;
        boolean ok = extractAsset();
        writeInnerScript(ok);
        mExtractionDone = true;
        if (ok && mWasUpdated && prevExisted) {
            updateInstalledBinaryAsync();
        }
    }

    /** 将 assets/agentserver-linux-arm64.tgz 复制到 filesDir，返回是否成功。
     *  若 asset 大小与磁盘上的文件不一致（APK 更新带入新版），强制重新提取并置 mWasUpdated。 */
    private boolean extractAsset() {
        File dest = new File(mActivity.getFilesDir(), ASSET_TGZ_REL);
        long assetSize = getAssetSize();
        // 大小一致：无需更新
        if (dest.exists() && dest.length() > 0 && dest.length() == assetSize) return true;
        // 大小不一致或文件损坏：强制重新提取
        if (dest.exists()) dest.delete();
        mWasUpdated = true;
        dest.getParentFile().mkdirs();
        AssetManager am = mActivity.getAssets();
        File tmp = new File(dest.getParent(), ASSET_TGZ_NAME + ".tmp");
        try (InputStream in  = am.open(ASSET_TGZ_NAME);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        // 原子性重命名，避免写到一半时 dest 被读取
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            return false;
        }
        return dest.exists() && dest.length() > 0;
    }

    /** 读取 asset 文件的字节数，用于与磁盘文件比对版本。 */
    private long getAssetSize() {
        try (InputStream in = mActivity.getAssets().open(ASSET_TGZ_NAME)) {
            long size = 0;
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) size += n;
            return size;
        } catch (IOException e) {
            return -1;
        }
    }

    /** APK 更新后在后台直接替换 Ubuntu proot 内已安装的 binary，无需重启终端。 */
    private void updateInstalledBinaryAsync() {
        new Thread(() -> {
            String prefix = System.getenv("PREFIX");
            if (prefix == null || prefix.isEmpty())
                prefix = "/data/data/com.termux/files/usr";
            String tgzPath = new File(mActivity.getFilesDir(), ASSET_TGZ_REL).getAbsolutePath();
            String bash = prefix + "/bin/bash";
            String termuxHome = prefix + "/../home";
            final String finalPrefix = prefix;

            String script =
                "command -v proot-distro >/dev/null 2>&1 || exit 0\n" +
                "proot-distro login ubuntu -- sh << 'INNER'\n" +
                "  _t=$(mktemp -d)\n" +
                "  cp '" + tgzPath + "' \"$_t/pkg.tgz\" 2>/dev/null || { rm -rf \"$_t\"; exit 0; }\n" +
                "  tar -xzf \"$_t/pkg.tgz\" -C \"$_t\" 2>/dev/null || { rm -rf \"$_t\"; exit 0; }\n" +
                "  _b=$(find \"$_t\" -name agentserver -type f | head -1)\n" +
                "  [ -n \"$_b\" ] || { rm -rf \"$_t\"; exit 0; }\n" +
                "  cp \"$_b\" /usr/local/bin/agentserver && chmod +x /usr/local/bin/agentserver\n" +
                "  id claude >/dev/null 2>&1 || useradd -m -s /bin/bash claude\n" +
                "  _cc=$(which claude 2>/dev/null); [ -n \"$_cc\" ] && [ ! -e /usr/local/bin/claude ] && ln -sf \"$_cc\" /usr/local/bin/claude\n" +
                "  rm -rf \"$_t\"\n" +
                "  echo '[*] AgentServer updated'\n" +
                "INNER\n";

            try {
                ProcessBuilder pb = new ProcessBuilder(bash, "-c", script);
                pb.redirectErrorStream(true);
                Map<String, String> env = pb.environment();
                env.putAll(System.getenv());
                env.put("PATH", finalPrefix + "/bin:" + finalPrefix + "/bin/applets:"
                        + env.getOrDefault("PATH", ""));
                env.put("PREFIX", finalPrefix);
                env.put("HOME", termuxHome);
                pb.start().waitFor();
            } catch (Exception ignored) {}
        }, "agentserver-update").start();
    }

    /** 写入 Ubuntu 内运行的安装脚本。extractionOk 为 false 时脚本会提示本地包不可用。 */
    private void writeInnerScript(boolean extractionOk) {
        File scriptFile = new File(mActivity.getFilesDir(), INNER_SCRIPT_REL);
        try {
            scriptFile.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(scriptFile)) {
                w.write(buildInnerScript(extractionOk));
            }
        } catch (IOException ignored) {}
    }

    // -------------------------------------------------------------------------
    // 安装脚本内容
    // -------------------------------------------------------------------------

    private String buildInnerScript(boolean localTgzAvailable) {
        StringBuilder s = new StringBuilder();
        s.append("#!/bin/bash\n");
        s.append("# AgentServer auto-setup (sourced from ~/.bashrc after Claude setup)\n\n");

        // ── 幂等保护：已安装且 /tmp 没有新包时跳过 ───────────────────────────
        s.append("if command -v agentserver >/dev/null 2>&1 && [ ! -f /tmp/agentserver-linux-arm64.tar.gz ]; then\n");
        s.append("    sed -i '/.agentserver-setup/d' ~/.bashrc 2>/dev/null\n");
        s.append("    rm -f ~/.agentserver-setup.sh\n");
        s.append("    return 0 2>/dev/null || exit 0\n");
        s.append("fi\n\n");

        // ── 欢迎界面 ─────────────────────────────────────────────────────────
        s.append("echo ''\n");
        s.append("echo '================================'\n");
        s.append("echo '  AgentServer 安装'\n");
        s.append("echo '================================'\n");
        s.append("echo ''\n\n");

        // ── 定位 tar.gz（由 AutoUbuntuManager 复制到 /tmp，或联网下载）────
        if (!localTgzAvailable) {
            s.append("echo '[!] 警告：Android 本地安装包提取失败，将尝试从网络下载'\n");
        }
        s.append("_tgz='/tmp/agentserver-linux-arm64.tar.gz'\n");
        s.append("_download_url='https://github.com/agentserver/agentserver/releases/download/v0.40.0/agentserver-linux-arm64.tar.gz'\n");
        s.append("if [ ! -f \"$_tgz\" ]; then\n");
        s.append("    echo '[*] 本地安装包未找到，尝试从网络下载...'\n");
        s.append("    if command -v curl >/dev/null 2>&1; then\n");
        s.append("        curl -L --progress-bar -o \"$_tgz\" \"$_download_url\"\n");
        s.append("    elif command -v wget >/dev/null 2>&1; then\n");
        s.append("        wget -O \"$_tgz\" \"$_download_url\"\n");
        s.append("    else\n");
        s.append("        echo '[!] 未找到 curl 或 wget，请先安装：apt-get install -y curl'\n");
        s.append("        sed -i '/.agentserver-setup/d' ~/.bashrc 2>/dev/null\n");
        s.append("        rm -f ~/.agentserver-setup.sh\n");
        s.append("        return 1 2>/dev/null || exit 1\n");
        s.append("    fi\n");
        s.append("fi\n");
        s.append("if [ ! -f \"$_tgz\" ]; then\n");
        s.append("    echo '[!] 下载失败，无法安装 AgentServer。'\n");
        s.append("    echo '    可手动下载：' \"$_download_url\"\n");
        s.append("    sed -i '/.agentserver-setup/d' ~/.bashrc 2>/dev/null\n");
        s.append("    rm -f ~/.agentserver-setup.sh\n");
        s.append("    return 1 2>/dev/null || exit 1\n");
        s.append("fi\n\n");

        // ── 解压并安装二进制 ──────────────────────────────────────────────────
        s.append("echo '[1/2] 正在解压 AgentServer...'\n");
        s.append("_tmpdir=$(mktemp -d)\n");
        s.append("tar -xzf \"$_tgz\" -C \"$_tmpdir\" 2>&1\n");
        s.append("if [ $? -ne 0 ]; then\n");
        s.append("    echo '[!] 解压失败，请检查安装包完整性。'\n");
        s.append("    rm -rf \"$_tmpdir\"\n");
        s.append("    return 1 2>/dev/null || exit 1\n");
        s.append("fi\n\n");

        // 找到 agentserver 二进制（可能在子目录里）
        s.append("_bin=$(find \"$_tmpdir\" -type f -name 'agentserver' | head -1)\n");
        s.append("if [ -z \"$_bin\" ]; then\n");
        s.append("    echo '[!] 未在压缩包中找到 agentserver 二进制文件。'\n");
        s.append("    rm -rf \"$_tmpdir\"\n");
        s.append("    return 1 2>/dev/null || exit 1\n");
        s.append("fi\n\n");

        s.append("echo '[2/2] 正在安装到 /usr/local/bin/agentserver ...'\n");
        s.append("mkdir -p /usr/local/bin\n");
        s.append("cp \"$_bin\" /usr/local/bin/agentserver\n");
        s.append("chmod +x /usr/local/bin/agentserver\n");
        s.append("rm -rf \"$_tmpdir\"\n");
        s.append("rm -f \"$_tgz\"\n\n");

        s.append("if ! command -v agentserver >/dev/null 2>&1; then\n");
        s.append("    echo '[!] 安装失败，未在 PATH 中找到 agentserver。'\n");
        s.append("    return 1 2>/dev/null || exit 1\n");
        s.append("fi\n");
        s.append("echo \"[*] AgentServer $(agentserver version 2>/dev/null || echo '') 安装成功\"\n\n");

        // ── 创建非 root 用户 claude，供 agentserver 以非 root 权限运行 ──────
        s.append("echo '[3/3] 配置非 root 运行用户...'\n");
        s.append("id claude >/dev/null 2>&1 || useradd -m -s /bin/bash claude\n");
        // 确保 claude CLI 二进制在 /usr/local/bin，对 claude 用户可见
        s.append("_claude_cli=$(which claude 2>/dev/null)\n");
        s.append("if [ -n \"$_claude_cli\" ] && [ ! -e /usr/local/bin/claude ]; then\n");
        s.append("    ln -sf \"$_claude_cli\" /usr/local/bin/claude\n");
        s.append("fi\n");
        s.append("echo '[*] 运行用户 claude 已就绪'\n\n");

        // ── 使用说明（OAuth 流程，不能在脚本内交互完成）────────────────────
        s.append("echo ''\n");
        s.append("echo '接下来请在 App 的 AgentServer 页面完成配置：'\n");
        s.append("echo '  ① 输入服务器地址（默认: https://agent.cs.ac.cn）'\n");
        s.append("echo '  ② 点击「登录」→ 浏览器扫码完成 OAuth 认证'\n");
        s.append("echo '  ③ 点击「启动 Agent」→ 连接 Claude Code 到服务器'\n");
        s.append("echo ''\n");
        s.append("echo '或在终端手动运行：'\n");
        s.append("echo '  agentserver login --server <URL>'\n");
        s.append("echo '  agentserver claudecode --server <URL>'\n");
        s.append("echo ''\n\n");

        // ── 自我清除 ──────────────────────────────────────────────────────────
        s.append("sed -i '/.agentserver-setup/d' ~/.bashrc 2>/dev/null\n");
        s.append("rm -f ~/.agentserver-setup.sh\n\n");

        s.append("echo ''\n");
        s.append("echo '================================'\n");
        s.append("echo '  AgentServer 配置完成'\n");
        s.append("echo '================================'\n");
        s.append("echo ''\n");

        return s.toString();
    }
}
