package com.termux.app.autotasks;

import androidx.annotation.NonNull;

import com.termux.app.TermuxActivity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class AutoCodexManager {

    static final String INNER_SCRIPT_REL = "home/.codex-inner-setup.sh";

    private final TermuxActivity mActivity;

    public AutoCodexManager(@NonNull TermuxActivity activity) {
        mActivity = activity;
        Thread t = new Thread(this::writeInnerScript, "codex-setup-write");
        t.setDaemon(true);
        t.start();
    }

    @NonNull
    public String getInnerScriptPath() {
        return new File(mActivity.getFilesDir(), INNER_SCRIPT_REL).getAbsolutePath();
    }

    private void writeInnerScript() {
        File scriptFile = new File(mActivity.getFilesDir(), INNER_SCRIPT_REL);
        try {
            File parent = scriptFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileWriter w = new FileWriter(scriptFile)) {
                w.write(buildInnerScript());
            }
        } catch (IOException ignored) {
            // AutoUbuntuManager silently skips injection when the inner script is missing.
        }
    }

    public static String buildInnerScriptForTest() {
        return buildInnerScript();
    }

    private static String buildInnerScript() {
        String agentsB64 = Base64.getEncoder().encodeToString(
            AndroidCapabilityPromptBuilder.buildCodexInstructions().getBytes(StandardCharsets.UTF_8));
        String skillB64 = Base64.getEncoder().encodeToString(
            AndroidCapabilityPromptBuilder.buildCodexAndroidSkill().getBytes(StandardCharsets.UTF_8));
        StringBuilder s = new StringBuilder();
        s.append("#!/bin/bash\n");
        s.append("# Codex auto-setup (sourced from ~/.bashrc on first Ubuntu login)\n\n");
        s.append("CODEX_SETUP_SENTINEL=/home/codex/.codex/setup-complete\n\n");

        s.append("cleanup_hook() {\n");
        s.append("    sed -i '/.codex-setup/d' ~/.bashrc 2>/dev/null\n");
        s.append("    rm -f ~/.codex-setup.sh\n");
        s.append("}\n\n");

        s.append("refresh_codex_runtime_files() {\n");
        s.append("    mkdir -p /home/codex/.codex/skills/android-phone\n");
        s.append("    printf '%s' '").append(agentsB64).append("' | base64 -d > /home/codex/AGENTS.md\n");
        s.append("    printf '%s' '").append(skillB64).append("' | base64 -d > /home/codex/.codex/skills/android-phone/SKILL.md\n");
        s.append("    _cfg=/home/codex/.codex/config.toml\n");
        s.append("    touch \"$_cfg\"\n");
        s.append("    _tmp=$(mktemp)\n");
        s.append("    awk 'BEGIN{skip=0} /^\\[mcp_servers\\.android-mcp(\\.|\\])/{skip=1; next} /^\\[/{skip=0} !skip{print}' \"$_cfg\" > \"$_tmp\" && cat \"$_tmp\" > \"$_cfg\"\n");
        s.append("    rm -f \"$_tmp\"\n");
        s.append("    printf '\\n' >> \"$_cfg\"\n");
        s.append("    cat >> \"$_cfg\" <<'EOF'\n");
        s.append("[mcp_servers.android-mcp]\n");
        s.append("type = \"streamable_http\"\n");
        s.append("url = \"http://127.0.0.1:8765/mcp\"\n");
        s.append("EOF\n");
        s.append("    chown -R codex:codex /home/codex 2>/dev/null || true\n");
        s.append("}\n\n");

        s.append("export DEBIAN_FRONTEND=noninteractive\n");
        s.append("id codex >/dev/null 2>&1 || useradd -m -s /bin/bash codex\n");
        s.append("mkdir -p /home/codex\n");
        s.append("mkdir -p /home/codex/.codex\n");
        s.append("refresh_codex_runtime_files\n\n");

        s.append("if command -v codex >/dev/null 2>&1 && [ -f \"$CODEX_SETUP_SENTINEL\" ]; then\n");
        s.append("    cleanup_hook\n");
        s.append("    return 0 2>/dev/null || exit 0\n");
        s.append("fi\n\n");

        s.append("if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then\n");
        s.append("    mkdir -p /var/cache/apt/archives/partial /var/lib/apt/lists/partial /var/log/apt\n");
        s.append("    chmod 700 /var/cache/apt/archives/partial /var/lib/apt/lists/partial 2>/dev/null || true\n");
        s.append("    apt-get update 2>&1\n");
        s.append("    apt-get install -y --no-install-recommends nodejs npm curl 2>&1\n");
        s.append("fi\n\n");

        s.append("if ! command -v codex >/dev/null 2>&1; then\n");
        s.append("    npm config set registry https://registry.npmmirror.com 2>/dev/null\n");
        s.append("    echo '[*] Installing Codex CLI (npm install -g @openai/codex)...'\n");
        s.append("    npm install -g @openai/codex 2>&1\n");
        s.append("fi\n\n");

        s.append("if ! command -v codex >/dev/null 2>&1; then\n");
        s.append("    echo '[!] Codex CLI install failed. Please retry: npm install -g @openai/codex'\n");
        s.append("    return 1 2>/dev/null || exit 1\n");
        s.append("fi\n\n");

        s.append("touch \"$CODEX_SETUP_SENTINEL\"\n");
        s.append("chown -R codex:codex /home/codex/.codex 2>/dev/null || true\n");
        s.append("cleanup_hook\n");
        s.append("echo '[*] Codex setup complete. Set OpenAI key from the App API Key page.'\n");
        s.append("return 0 2>/dev/null || exit 0\n");

        return s.toString();
    }
}
