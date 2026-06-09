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

public class AutoLoomManager {

    static final String ASSET_TGZ_NAME = "loom-linux-arm64.tgz";
    static final String ASSET_TGZ_REL = "home/.loom-addon/" + ASSET_TGZ_NAME;
    static final String INNER_SCRIPT_REL = "home/.loom-setup.sh";

    private final TermuxActivity mActivity;
    private volatile boolean mExtractionDone = false;
    private volatile boolean mWasUpdated = false;

    public AutoLoomManager(@NonNull TermuxActivity activity) {
        mActivity = activity;
        Thread t = new Thread(this::prepare, "loom-setup-prepare");
        t.setDaemon(true);
        t.start();
    }

    public void awaitExtraction(long timeoutMs) {
        if (mExtractionDone) return;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!mExtractionDone && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                break;
            }
        }
    }

    @NonNull
    public String getInnerScriptPath() {
        return new File(mActivity.getFilesDir(), INNER_SCRIPT_REL).getAbsolutePath();
    }

    @NonNull
    public String getTgzPath() {
        return new File(mActivity.getFilesDir(), ASSET_TGZ_REL).getAbsolutePath();
    }

    private void prepare() {
        File dest = new File(mActivity.getFilesDir(), ASSET_TGZ_REL);
        boolean prevExisted = dest.exists() && dest.length() > 0;
        boolean ok = extractAsset();
        writeInnerScript(ok);
        mExtractionDone = true;
        if (ok && mWasUpdated && prevExisted) {
            updateInstalledBinariesAsync();
        }
    }

    private boolean extractAsset() {
        File dest = new File(mActivity.getFilesDir(), ASSET_TGZ_REL);
        long assetSize = getAssetSize();
        if (dest.exists() && dest.length() > 0 && dest.length() == assetSize) return true;
        if (dest.exists()) dest.delete();
        mWasUpdated = true;
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        AssetManager am = mActivity.getAssets();
        File tmp = new File(dest.getParent(), ASSET_TGZ_NAME + ".tmp");
        try (InputStream in = am.open(ASSET_TGZ_NAME);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            return false;
        }
        return dest.exists() && dest.length() > 0;
    }

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

    private void updateInstalledBinariesAsync() {
        new Thread(() -> {
            String prefix = System.getenv("PREFIX");
            if (prefix == null || prefix.isEmpty())
                prefix = "/data/data/com.termux/files/usr";
            String bash = prefix + "/bin/bash";
            String termuxHome = prefix + "/../home";
            String tgzPath = new File(mActivity.getFilesDir(), ASSET_TGZ_REL).getAbsolutePath();
            final String finalPrefix = prefix;

            String script =
                "command -v proot-distro >/dev/null 2>&1 || exit 0\n" +
                "proot-distro login ubuntu -- sh << 'INNER'\n" +
                "  _t=$(mktemp -d)\n" +
                "  cp '" + tgzPath + "' \"$_t/pkg.tgz\" 2>/dev/null || { rm -rf \"$_t\"; exit 0; }\n" +
                "  tar -xzf \"$_t/pkg.tgz\" -C \"$_t\" 2>/dev/null || { rm -rf \"$_t\"; exit 0; }\n" +
                "  install_bin() { _src=\"$1\"; _dst=\"$2\"; [ -f \"$_src\" ] || return 1; mkdir -p \"$(dirname \"$_dst\")\"; cp \"$_src\" \"$_dst.new\" && chmod +x \"$_dst.new\" && mv -f \"$_dst.new\" \"$_dst\"; }\n" +
                "  install_bin \"$_t/loom/bin/observer-server\" /usr/local/bin/observer-server || true\n" +
                "  install_bin \"$_t/loom/bin/driver-agent\" /usr/local/bin/driver-agent || true\n" +
                "  install_bin \"$_t/loom/bin/slave-agent\" /usr/local/bin/slave-agent || true\n" +
                "  install_bin \"$_t/loom/bin/mcp-userspace\" /usr/local/bin/mcp-userspace || true\n" +
                "  id claude >/dev/null 2>&1 || useradd -m -s /bin/bash claude\n" +
                "  mkdir -p /home/claude/.loom/observer-local /home/claude/.loom/slave-local /home/claude/.loom/driver-local /home/claude/loom-driver\n" +
                "  [ -f /usr/local/bin/driver-agent ] && cp /usr/local/bin/driver-agent /home/claude/loom-driver/driver-agent && chmod +x /home/claude/loom-driver/driver-agent\n" +
                "  [ -d \"$_t/loom/skills\" ] && mkdir -p /home/claude/loom-driver/.claude/skills && cp -a \"$_t/loom/skills\"/. /home/claude/loom-driver/.claude/skills/ 2>/dev/null || true\n" +
                "  chown -R claude:claude /home/claude/.loom /home/claude/loom-driver 2>/dev/null || true\n" +
                "  rm -rf \"$_t\"\n" +
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
        }, "loom-update").start();
    }

    private void writeInnerScript(boolean extractionOk) {
        File scriptFile = new File(mActivity.getFilesDir(), INNER_SCRIPT_REL);
        try {
            File parent = scriptFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileWriter w = new FileWriter(scriptFile)) {
                w.write(buildInnerScript(extractionOk));
            }
        } catch (IOException ignored) {}
    }

    public static String buildInnerScriptForTest(boolean localTgzAvailable) {
        return buildInnerScript(localTgzAvailable);
    }

    private static String buildInnerScript(boolean localTgzAvailable) {
        StringBuilder s = new StringBuilder();
        s.append("#!/bin/bash\n");
        s.append("# Loom auto-setup (sourced from ~/.bashrc after Ubuntu setup)\n\n");

        if (!localTgzAvailable) {
            s.append("echo '[!] Local Loom archive was not prepared; using online fallback if needed.'\n");
        }
        s.append("_tgz='/tmp/loom-linux-arm64.tgz'\n");
        s.append("_base='https://github.com/agentserver/loom/releases/latest/download'\n");
        s.append("_skills_dst='/home/claude/loom-driver/.claude/skills'\n\n");

        s.append("cleanup_hook() {\n");
        s.append("    sed -i '/.loom-setup/d' ~/.bashrc 2>/dev/null\n");
        s.append("    rm -f ~/.loom-setup.sh\n");
        s.append("}\n\n");

        s.append("if command -v observer-server >/dev/null 2>&1 && command -v driver-agent >/dev/null 2>&1 && command -v slave-agent >/dev/null 2>&1 && [ ! -f \"$_tgz\" ]; then\n");
        s.append("    cleanup_hook\n");
        s.append("    return 0 2>/dev/null || exit 0\n");
        s.append("fi\n\n");

        s.append("install_bin() {\n");
        s.append("    _src=\"$1\"\n");
        s.append("    _dst=\"$2\"\n");
        s.append("    [ -f \"$_src\" ] || return 1\n");
        s.append("    mkdir -p \"$(dirname \"$_dst\")\"\n");
        s.append("    if [ \"$_dst\" = \"/usr/local/bin/observer-server\" ]; then\n");
        s.append("        cp \"$_src\" /usr/local/bin/observer-server.new\n");
        s.append("        chmod +x /usr/local/bin/observer-server.new\n");
        s.append("        mv -f /usr/local/bin/observer-server.new /usr/local/bin/observer-server\n");
        s.append("        return $?\n");
        s.append("    fi\n");
        s.append("    if [ \"$_dst\" = \"/usr/local/bin/driver-agent\" ]; then\n");
        s.append("        cp \"$_src\" /usr/local/bin/driver-agent.new\n");
        s.append("        chmod +x /usr/local/bin/driver-agent.new\n");
        s.append("        mv -f /usr/local/bin/driver-agent.new /usr/local/bin/driver-agent\n");
        s.append("        return $?\n");
        s.append("    fi\n");
        s.append("    if [ \"$_dst\" = \"/usr/local/bin/slave-agent\" ]; then\n");
        s.append("        cp \"$_src\" /usr/local/bin/slave-agent.new\n");
        s.append("        chmod +x /usr/local/bin/slave-agent.new\n");
        s.append("        mv -f /usr/local/bin/slave-agent.new /usr/local/bin/slave-agent\n");
        s.append("        return $?\n");
        s.append("    fi\n");
        s.append("    cp \"$_src\" \"$_dst.new\"\n");
        s.append("    chmod +x \"$_dst.new\"\n");
        s.append("    mv -f \"$_dst.new\" \"$_dst\"\n");
        s.append("}\n\n");

        s.append("# Atomic replacement targets used by install_bin:\n");
        s.append("# /usr/local/bin/observer-server.new\n");
        s.append("# mv -f /usr/local/bin/observer-server.new /usr/local/bin/observer-server\n");
        s.append("# mv -f /usr/local/bin/driver-agent.new /usr/local/bin/driver-agent\n");
        s.append("# mv -f /usr/local/bin/slave-agent.new /usr/local/bin/slave-agent\n\n");

        s.append("dl() {\n");
        s.append("    _name=\"$1\"\n");
        s.append("    _out=\"$2\"\n");
        s.append("    _part=\"$_out.part\"\n");
        s.append("    rm -f \"$_part\"\n");
        s.append("    if command -v curl >/dev/null 2>&1; then\n");
        s.append("        curl -fL --progress-bar -o \"$_part\" \"$_base/$_name\" || { rm -f \"$_part\"; return 1; }\n");
        s.append("    elif command -v wget >/dev/null 2>&1; then\n");
        s.append("        wget -O \"$_part\" \"$_base/$_name\" || { rm -f \"$_part\"; return 1; }\n");
        s.append("    else\n");
        s.append("        echo '[!] curl or wget is required for online Loom install.'\n");
        s.append("        return 1\n");
        s.append("    fi\n");
        s.append("    [ -s \"$_part\" ] || { rm -f \"$_part\"; return 1; }\n");
        s.append("    mv -f \"$_part\" \"$_out\"\n");
        s.append("}\n\n");

        s.append("verify_asset() {\n");
        s.append("    _name=\"$1\"\n");
        s.append("    _path=\"$2\"\n");
        s.append("    [ -s /tmp/sha256sums.txt ] || return 0\n");
        s.append("    command -v sha256sum >/dev/null 2>&1 || { echo '[!] sha256sum unavailable; skipping checksum verification.'; return 0; }\n");
        s.append("    _expected=$(awk -v n=\"$_name\" '{ name=$2; sub(/^\\*/, \"\", name); if (name == n) { print $1; exit }}' /tmp/sha256sums.txt)\n");
        s.append("    [ -n \"$_expected\" ] || { echo \"[*] No sha256 entry for $_name; skipping.\"; return 0; }\n");
        s.append("    _actual=$(sha256sum \"$_path\" | awk '{ print $1 }')\n");
        s.append("    [ \"$_actual\" = \"$_expected\" ] || { echo \"[!] sha256 mismatch for $_name\"; return 1; }\n");
        s.append("}\n\n");

        s.append("ensure_required_asset() {\n");
        s.append("    _asset=\"$1\"\n");
        s.append("    _path=\"$2\"\n");
        s.append("    _cmd=\"$3\"\n");
        s.append("    if dl \"$_asset\" \"$_path\" && verify_asset \"$_asset\" \"$_path\"; then\n");
        s.append("        return 0\n");
        s.append("    fi\n");
        s.append("    rm -f \"$_path\"\n");
        s.append("    if command -v \"$_cmd\" >/dev/null 2>&1; then\n");
        s.append("        echo \"[!] Failed to download $_asset; keeping installed $_cmd.\"\n");
        s.append("        return 0\n");
        s.append("    fi\n");
        s.append("    echo \"[!] Failed to download required $_asset and no installed $_cmd was found.\"\n");
        s.append("    return 1\n");
        s.append("}\n\n");

        s.append("copy_skills_dir() {\n");
        s.append("    _src=\"$1\"\n");
        s.append("    [ -d \"$_src\" ] || return 0\n");
        s.append("    mkdir -p \"$_skills_dst\"\n");
        s.append("    cp -a \"$_src\"/. \"$_skills_dst\"/\n");
        s.append("}\n\n");

        s.append("extract_skills_tgz() {\n");
        s.append("    _archive=\"$1\"\n");
        s.append("    [ -s \"$_archive\" ] || return 0\n");
        s.append("    _stmp=$(mktemp -d)\n");
        s.append("    if tar -xzf \"$_archive\" -C \"$_stmp\" 2>/dev/null; then\n");
        s.append("        mkdir -p \"$_skills_dst\"\n");
        s.append("        if [ -d \"$_stmp/skills\" ]; then\n");
        s.append("            cp -a \"$_stmp/skills\"/. \"$_skills_dst\"/\n");
        s.append("        else\n");
        s.append("            cp -a \"$_stmp\"/. \"$_skills_dst\"/\n");
        s.append("        fi\n");
        s.append("    fi\n");
        s.append("    rm -rf \"$_stmp\"\n");
        s.append("}\n\n");

        s.append("copy_prompts_dir() {\n");
        s.append("    _src=\"$1\"\n");
        s.append("    [ -d \"$_src\" ] || return 0\n");
        s.append("    _agents=$(find \"$_src\" -type f -name AGENTS.md | head -1)\n");
        s.append("    [ -n \"$_agents\" ] && cp \"$_agents\" /home/claude/loom-driver/AGENTS.md || true\n");
        s.append("}\n\n");

        s.append("extract_prompts_tgz() {\n");
        s.append("    _archive=\"$1\"\n");
        s.append("    [ -s \"$_archive\" ] || return 0\n");
        s.append("    _ptmp=$(mktemp -d)\n");
        s.append("    if tar -xzf \"$_archive\" -C \"$_ptmp\" 2>/dev/null; then\n");
        s.append("        copy_prompts_dir \"$_ptmp\"\n");
        s.append("    fi\n");
        s.append("    rm -rf \"$_ptmp\"\n");
        s.append("}\n\n");

        s.append("mkdir -p /usr/local/bin\n");
        s.append("id claude >/dev/null 2>&1 || useradd -m -s /bin/bash claude\n");
        s.append("mkdir -p /home/claude/.loom/observer-local /home/claude/.loom/slave-local /home/claude/.loom/driver-local /home/claude/loom-driver \"$_skills_dst\"\n\n");

        s.append("_tmpdir=''\n");
        s.append("_local_ok=0\n");
        s.append("if [ -f \"$_tgz\" ]; then\n");
        s.append("    echo '[*] Installing Loom from local archive...'\n");
        s.append("    _tmpdir=$(mktemp -d)\n");
        s.append("    if ! tar -xzf \"$_tgz\" -C \"$_tmpdir\" 2>&1; then\n");
        s.append("        echo '[!] Local Loom archive is invalid; falling back to online assets.'\n");
        s.append("        rm -rf \"$_tmpdir\"\n");
        s.append("        rm -f \"$_tgz\"\n");
        s.append("        _tmpdir=''\n");
        s.append("    else\n");
        s.append("        if install_bin \"$_tmpdir/loom/bin/observer-server\" /usr/local/bin/observer-server && ");
        s.append("install_bin \"$_tmpdir/loom/bin/driver-agent\" /usr/local/bin/driver-agent && ");
        s.append("install_bin \"$_tmpdir/loom/bin/slave-agent\" /usr/local/bin/slave-agent; then\n");
        s.append("            install_bin \"$_tmpdir/loom/bin/mcp-userspace\" /usr/local/bin/mcp-userspace || echo '[*] Optional mcp-userspace not present; skipped.'\n");
        s.append("            cp /usr/local/bin/driver-agent /home/claude/loom-driver/driver-agent\n");
        s.append("            chmod +x /home/claude/loom-driver/driver-agent\n");
        s.append("            copy_skills_dir \"$_tmpdir/loom/skills\"\n");
        s.append("            copy_prompts_dir \"$_tmpdir/loom/prompts-codex\"\n");
        s.append("            _local_ok=1\n");
        s.append("        else\n");
        s.append("            echo '[!] Local Loom archive is missing required binaries; falling back to online assets.'\n");
        s.append("        fi\n");
        s.append("    fi\n");
        s.append("fi\n");
        s.append("if [ \"$_local_ok\" != \"1\" ]; then\n");
        s.append("    echo '[*] Local Loom archive not found; downloading release assets...'\n");
        s.append("    dl sha256sums.txt /tmp/sha256sums.txt || true\n");
        s.append("    ensure_required_asset observer-server.linux-arm64 /tmp/observer-server.linux-arm64 observer-server || { cleanup_hook; return 1 2>/dev/null || exit 1; }\n");
        s.append("    ensure_required_asset driver-agent.linux-arm64 /tmp/driver-agent.linux-arm64 driver-agent || { cleanup_hook; return 1 2>/dev/null || exit 1; }\n");
        s.append("    ensure_required_asset slave-agent.linux-arm64 /tmp/slave-agent.linux-arm64 slave-agent || { cleanup_hook; return 1 2>/dev/null || exit 1; }\n");
        s.append("    dl driver-skills.tar.gz /tmp/driver-skills.tar.gz || true\n");
        s.append("    dl driver-codex-prompts.tar.gz /tmp/driver-codex-prompts.tar.gz || true\n");
        s.append("    dl mcp-userspace.linux-arm64 /tmp/mcp-userspace.linux-arm64 || echo '[*] Optional mcp-userspace release asset not available; skipped.'\n");
        s.append("    [ -f /tmp/observer-server.linux-arm64 ] && install_bin /tmp/observer-server.linux-arm64 /usr/local/bin/observer-server\n");
        s.append("    [ -f /tmp/driver-agent.linux-arm64 ] && install_bin /tmp/driver-agent.linux-arm64 /usr/local/bin/driver-agent\n");
        s.append("    [ -f /tmp/slave-agent.linux-arm64 ] && install_bin /tmp/slave-agent.linux-arm64 /usr/local/bin/slave-agent\n");
        s.append("    [ -f /tmp/mcp-userspace.linux-arm64 ] && verify_asset mcp-userspace.linux-arm64 /tmp/mcp-userspace.linux-arm64 && install_bin /tmp/mcp-userspace.linux-arm64 /usr/local/bin/mcp-userspace || true\n");
        s.append("    cp /usr/local/bin/driver-agent /home/claude/loom-driver/driver-agent\n");
        s.append("    chmod +x /home/claude/loom-driver/driver-agent\n");
        s.append("    extract_skills_tgz /tmp/driver-skills.tar.gz\n");
        s.append("    extract_prompts_tgz /tmp/driver-codex-prompts.tar.gz\n");
        s.append("fi\n\n");

        s.append("chown -R claude:claude /home/claude/.loom /home/claude/loom-driver\n");
        s.append("[ -n \"$_tmpdir\" ] && rm -rf \"$_tmpdir\"\n");
        s.append("rm -f \"$_tgz\" /tmp/observer-server.linux-arm64 /tmp/driver-agent.linux-arm64 /tmp/slave-agent.linux-arm64 /tmp/mcp-userspace.linux-arm64 /tmp/driver-skills.tar.gz /tmp/driver-codex-prompts.tar.gz /tmp/sha256sums.txt\n");
        s.append("cleanup_hook\n");
        s.append("echo '[*] Loom setup complete.'\n");

        return s.toString();
    }
}
