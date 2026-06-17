package com.termux.app.loom;

public final class LoomConfigRenderer {

    private LoomConfigRenderer() {
    }

    public static String renderObserverConfig(LoomSettings s, String observerHome) {
        String dbPath = joinPath(observerHome, "observer.db");
        return ""
            + "listen_addr: " + q(s.observerListenAddr) + "\n"
            + "db_path: " + q(dbPath) + "\n"
            + "api_keys:\n"
            + "  - id: bootstrap\n"
            + "    key: " + q(s.workspaceApiKey) + "\n"
            + "    note: Android app bootstrap key\n";
    }

    public static String renderDriverConfig(LoomSettings s, String projectDir, String tokenDir) {
        return ""
            + "server:\n"
            + "  url: " + q(s.agentServerUrl) + "\n"
            + "  name: " + q(s.driverName) + "\n"
            + "credentials:\n"
            + "  sandbox_id: \"\"\n"
            + "  tunnel_token: \"\"\n"
            + "  proxy_token: \"\"\n"
            + "  workspace_id: \"\"\n"
            + "  short_id: \"\"\n"
            + "listen_addr: 127.0.0.1:0\n"
            + "discovery:\n"
            + "  display_name: " + q(s.driverName) + "\n"
            + "  description: " + q("Android Loom driver (" + s.driverName + ")") + "\n"
            + "  skills: []\n"
            + renderAgentBackend(s, projectDir)
            + "planner:\n"
            + "  bin: \"\"\n"
            + "  timeout_sec: 300\n"
            + "  extra_args: []\n"
            + "fanout:\n"
            + "  max_concurrency: 2\n"
            + "  default_policy: \"\"\n"
            + "  policy_by_skill: {}\n"
            + "  subtask_defaults:\n"
            + "    timeout_sec: 600\n"
            + "    max_budget_usd: 0\n"
            + "driver_defaults:\n"
            + "  target_display_name: \"\"\n"
            + "  task_timeout_sec: 600\n"
            + "  audit_log_dir: " + q(joinPath(projectDir, "logs")) + "\n"
            + "  disable_uid_check: true\n"
            + "  max_dir_cache_entries: 50000\n"
            + "  artifact_transport: observer_lazy\n"
            + "observer:\n"
            + "  enabled: true\n"
            + "  url: " + q(s.observerUrl) + "\n"
            + "  workspace_id: " + q(s.workspaceId) + "\n"
            + "  agent_id: " + q(s.driverName) + "\n"
            + "  api_key: " + q(s.workspaceApiKey) + "\n"
            + "  token_state_path: " + q(joinPath(tokenDir, "observer.token")) + "\n";
    }

    public static String renderSlaveConfig(
        LoomSettings s,
        String slaveHome,
        int cpuCores,
        String arch,
        int memoryGb) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("server:\n");
        yaml.append("  url: ").append(q(s.agentServerUrl)).append("\n");
        yaml.append("  name: ").append(q(s.slaveName)).append("\n");
        yaml.append("credentials:\n");
        yaml.append("  sandbox_id: \"\"\n");
        yaml.append("  tunnel_token: \"\"\n");
        yaml.append("  proxy_token: \"\"\n");
        yaml.append("  workspace_id: \"\"\n");
        yaml.append("  short_id: \"\"\n");
        yaml.append(renderAgentBackend(s, slaveHome));
        yaml.append("mcp_servers: {}\n");
        yaml.append("discovery:\n");
        yaml.append("  display_name: ").append(q(s.slaveName)).append("\n");
        yaml.append("  description: ").append(q("Android Loom slave (" + s.slaveName + ")")).append("\n");
        yaml.append("  skills:\n");
        yaml.append("    - chat\n");
        yaml.append("    - bash\n");
        yaml.append("    - permissions\n");
        yaml.append("    - register_mcp\n");
        yaml.append("    - file\n");
        yaml.append("planner:\n");
        yaml.append("  bin: \"\"\n");
        yaml.append("  timeout_sec: 300\n");
        yaml.append("  extra_args: []\n");
        yaml.append("fanout:\n");
        yaml.append("  max_concurrency: 1\n");
        yaml.append("  default_policy: best_effort\n");
        yaml.append("  policy_by_skill: {}\n");
        yaml.append("resources:\n");
        yaml.append("  cpu:\n");
        yaml.append("    cores: ").append(cpuCores).append("\n");
        yaml.append("    arch: ").append(q(arch)).append("\n");
        yaml.append("  memory_gb: ").append(memoryGb).append("\n");
        yaml.append("  tags:\n");
        appendTags(yaml, s.tags, "    ");
        yaml.append("observer:\n");
        yaml.append("  enabled: true\n");
        yaml.append("  url: ").append(q(s.observerUrl)).append("\n");
        yaml.append("  workspace_id: ").append(q(s.workspaceId)).append("\n");
        yaml.append("  agent_id: ").append(q(s.slaveName)).append("\n");
        yaml.append("  api_key: ").append(q(s.workspaceApiKey)).append("\n");
        yaml.append("  token_state_path: ").append(q(joinPath(slaveHome, "observer.token"))).append("\n");
        return yaml.toString();
    }

    public static String renderManagedSlaveConfig(
        LoomSettings s,
        LoomSlave slave,
        String machineId,
        String computerName) {
        LoomSettings safeSettings = s == null ? LoomSettings.defaults() : s;
        LoomSlave safeSlave = slave == null
            ? new LoomSlave("", "", "", "", "", "", safeSettings.agentProvider.id,
                LoomSlaveStatus.STOPPED, 0, "", "", 0, 0)
            : slave;
        String displayName = safeSlave.displayName.isEmpty() ? safeSlave.name : safeSlave.displayName;
        String folder = safeSlave.folder.isEmpty() ? safeSettings.agentProvider == com.termux.app.AssistantProvider.CODEX
            ? "/home/codex" : "/home/claude" : safeSlave.folder;
        String slaveStateDir = parentDir(safeSlave.configPath);
        String host = computerName == null || computerName.trim().isEmpty() ? "Android" : computerName.trim();
        String localMachineId = machineId == null ? "" : machineId.trim();
        StringBuilder yaml = new StringBuilder();
        yaml.append("server:\n");
        yaml.append("  url: ").append(q(safeSettings.agentServerUrl)).append("\n");
        yaml.append("  name: ").append(q(displayName)).append("\n");
        yaml.append("credentials:\n");
        yaml.append("  sandbox_id: \"\"\n");
        yaml.append("  tunnel_token: \"\"\n");
        yaml.append("  proxy_token: \"\"\n");
        yaml.append("  workspace_id: \"\"\n");
        yaml.append("  short_id: \"\"\n");
        yaml.append(renderAgentBackend(safeSettings, folder));
        yaml.append("discovery:\n");
        yaml.append("  display_name: ").append(q(displayName)).append("\n");
        yaml.append("  description: ").append(q("来自同一台设备：" + host + "；工作目录：" + folder)).append("\n");
        yaml.append("  skills:\n");
        yaml.append("    - chat\n");
        yaml.append("    - bash\n");
        yaml.append("    - file\n");
        yaml.append("    - permissions\n");
        yaml.append("    - register_mcp\n");
        yaml.append("    - unregister_mcp\n");
        yaml.append("resources:\n");
        yaml.append("  tags:\n");
        yaml.append("    - ").append(q("agentserver-app-slave")).append("\n");
        if (!localMachineId.isEmpty()) {
            yaml.append("    - ").append(q("local-machine:" + localMachineId)).append("\n");
        }
        yaml.append("    - ").append(q("host:" + host)).append("\n");
        yaml.append("    - ").append(q("android")).append("\n");
        yaml.append("    - ").append(q("provider:" + safeSettings.agentProvider.id)).append("\n");
        yaml.append("observer:\n");
        yaml.append("  enabled: true\n");
        yaml.append("  url: ").append(q(safeSettings.observerUrl)).append("\n");
        yaml.append("  workspace_id: ").append(q(safeSettings.workspaceId)).append("\n");
        yaml.append("  agent_id: ").append(q(displayName)).append("\n");
        yaml.append("  api_key: ").append(q(safeSettings.workspaceApiKey)).append("\n");
        yaml.append("  token_state_path: ").append(q(joinPath(slaveStateDir, "observer.token"))).append("\n");
        return yaml.toString();
    }

    private static String renderAgentBackend(LoomSettings s, String workdir) {
        if (s.agentProvider == com.termux.app.AssistantProvider.CODEX) {
            return ""
                + "agent:\n"
                + "  kind: codex\n"
                + "codex:\n"
                + "  bin: codex\n"
                + "  workdir: " + q(workdir) + "\n"
                + "  extra_args: []\n";
        }
        return ""
            + "agent:\n"
            + "  kind: claude\n"
            + "claude:\n"
            + "  bin: claude\n"
            + "  workdir: " + q(workdir) + "\n"
            + "  extra_args: []\n";
    }

    private static void appendTags(StringBuilder yaml, String tags, String indent) {
        String tagString = tags == null || tags.trim().isEmpty() ? "android" : tags;
        String[] parts = tagString.split(",");
        boolean appended = false;
        for (String part : parts) {
            String tag = part.trim();
            if (!tag.isEmpty()) {
                yaml.append(indent).append("- ").append(q(tag)).append("\n");
                appended = true;
            }
        }
        if (!appended) {
            yaml.append(indent).append("- ").append(q("android")).append("\n");
        }
    }

    private static String q(String value) {
        return LoomSettings.yamlQuote(value);
    }

    private static String joinPath(String dir, String file) {
        if (dir.endsWith("/")) {
            return dir + file;
        }
        return dir + "/" + file;
    }

    private static String parentDir(String path) {
        int slash = path == null ? -1 : path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : ".";
    }
}
