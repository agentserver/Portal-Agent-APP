package com.termux.app;

public final class AgentServerCommandBuilder {

    public static final class Config {
        public final String serverUrl;
        public final String resumeId;
        public final String deviceName;
        public final String apiKey;
        public final String baseUrl;

        public Config(String serverUrl, String resumeId, String deviceName, String apiKey, String baseUrl) {
            this.serverUrl = serverUrl == null ? "" : serverUrl;
            this.resumeId = resumeId == null ? "" : resumeId;
            this.deviceName = deviceName == null ? "" : deviceName;
            this.apiKey = apiKey == null ? "" : apiKey;
            this.baseUrl = baseUrl == null ? "" : baseUrl;
        }
    }

    private AgentServerCommandBuilder() {}

    public static String connectScript(AssistantProvider provider, Config c, String prefix) {
        AssistantProvider safeProvider = provider == null ? AssistantProvider.CLAUDE : provider;
        Config cfg = c == null ? new Config("", "", "", "", "") : c;
        ProviderProfile p = ProviderProfile.forProvider(safeProvider);
        String safePrefix = (prefix == null || prefix.isEmpty())
            ? "/data/data/com.termux/files/usr"
            : prefix;
        String home = safePrefix + "/../home";
        String logFile = home + (safeProvider == AssistantProvider.CODEX
            ? "/agentserver-codex-agent.log" : "/agentserver-agent.log");
        String pipeFile = safePrefix
            + "/var/lib/proot-distro/installed-rootfs/ubuntu/home/claude/.agentserver-pipe.jsonl";
        String pdBin = safePrefix + "/bin/proot-distro";
        String processPattern = processPattern(safeProvider);

        String inner = ". " + p.home + "/.bashrc 2>/dev/null; "
            + pathFor(safeProvider)
            + supportProbeFor(safeProvider)
            + envFor(safeProvider, cfg)
            + "exec /usr/local/bin/agentserver " + subcommandFor(safeProvider)
            + " --server " + sq(cfg.serverUrl)
            + (cfg.resumeId.isEmpty() ? "" : " --resume " + sq(cfg.resumeId))
            + (cfg.deviceName.isEmpty() ? "" : " --name " + sq(cfg.deviceName))
            + " --skip-open-browser";

        String clearPipe = safeProvider == AssistantProvider.CLAUDE
            ? "> '" + pipeFile + "' 2>/dev/null || true\n"
            : "";

        return ""
            + "for _p in $(pgrep -f '" + processPattern + "' 2>/dev/null);"
            + " do [ \"$_p\" != \"$$\" ] && kill \"$_p\" 2>/dev/null; done; sleep 1\n"
            + "> '" + logFile + "'\n"
            + clearPipe
            + "echo '[*] 正在启动 AgentServer (" + p.displayName + ")...'\n"
            + "nohup '" + pdBin + "' login --user " + p.user + " ubuntu -- bash -c "
            + dq(inner)
            + " >> '" + logFile + "' 2>&1 &\n"
            + "AS_PID=$!\n"
            + "echo '[*] AgentServer 已启动，等待 OAuth 授权 + tunnel 建立（最多 180s）...'\n"
            + "timeout 180 tail -F -n +1 '" + logFile + "' 2>/dev/null &\n"
            + "TAIL_PID=$!\n"
            + "( while kill -0 $TAIL_PID 2>/dev/null; do\n"
            + "    if grep -qE 'tunnel connected|Failed to load session|session not found|got 401|status code 101 but got|不支持 Codex|does not support Codex' '"
            + logFile + "' 2>/dev/null; then\n"
            + "      sleep 1\n"
            + "      kill $TAIL_PID 2>/dev/null\n"
            + "      break\n"
            + "    fi\n"
            + "    sleep 1\n"
            + "  done ) &\n"
            + "WATCHER_PID=$!\n"
            + "wait $TAIL_PID 2>/dev/null\n"
            + "kill $WATCHER_PID 2>/dev/null\n"
            + "echo ''\n"
            + "if kill -0 $AS_PID 2>/dev/null; then\n"
            + "  echo \"[*] Agent 进程运行中（PID: $AS_PID）\"\n"
            + "else\n"
            + "  echo '[!] Agent 进程已退出'\n"
            + "fi\n";
    }

    public static String processPattern(AssistantProvider provider) {
        return provider == AssistantProvider.CODEX
            ? "agentserver codex(code)?([[:space:]]|$)"
            : "agentserver claudecode([[:space:]]|$)";
    }

    private static String pathFor(AssistantProvider provider) {
        if (provider == AssistantProvider.CLAUDE) {
            return "export PATH=/home/claude/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH; ";
        }
        return "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH; ";
    }

    private static String supportProbeFor(AssistantProvider provider) {
        if (provider != AssistantProvider.CODEX) return "";
        return "_agentserver_help=$(/usr/local/bin/agentserver help 2>&1 || true); "
            + "if printf '%s\\n' \"$_agentserver_help\" | grep -Eq '(^|[[:space:]])codexcode([[:space:]]|$)'; then _agentserver_subcommand=codexcode; "
            + "elif printf '%s\\n' \"$_agentserver_help\" | grep -Eq '(^|[[:space:]])codex([[:space:]]|$)'; then _agentserver_subcommand=codex; "
            + "else echo '[!] 当前 AgentServer 不支持 Codex 后端，请更新 AgentServer addon 或切回 Claude'; exit 2; fi; ";
    }

    private static String subcommandFor(AssistantProvider provider) {
        return provider == AssistantProvider.CODEX ? "$_agentserver_subcommand" : "claudecode";
    }

    private static String envFor(AssistantProvider provider, Config c) {
        if (provider == AssistantProvider.CODEX) {
            return "OPENAI_API_KEY=" + sq(c.apiKey) + " ";
        }
        return "ANTHROPIC_API_KEY=" + sq(c.apiKey) + " "
            + (c.baseUrl.isEmpty() ? "" : "ANTHROPIC_BASE_URL=" + sq(c.baseUrl) + " ");
    }

    private static String sq(String value) {
        return "'" + (value == null ? "" : value).replace("'", "'\\''") + "'";
    }

    private static String dq(String value) {
        String v = value == null ? "" : value;
        return "\"" + v
            .replace("\\", "\\\\")
            .replace("$", "\\$")
            .replace("`", "\\`")
            .replace("\"", "\\\"")
            + "\"";
    }
}
