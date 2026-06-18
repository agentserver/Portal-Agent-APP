package com.termux.app.loom;

import com.termux.app.AssistantProvider;
import com.termux.app.ProviderProfile;

public final class LoomCommandBuilder {

    public static final String DRIVER_CONFIG_BEGIN_MARKER = "__LOOM_DRIVER_CONFIG_PUBLIC_IDENTITY_BEGIN__";
    public static final String DRIVER_CONFIG_END_MARKER = "__LOOM_DRIVER_CONFIG_PUBLIC_IDENTITY_END__";

    private LoomCommandBuilder() {
    }

    public static String statusScript(String prefix) {
        return statusScript(prefix, LoomSettings.defaults());
    }

    public static String statusScript(String prefix, LoomSettings settings) {
        Paths p = paths(settings);
        String observerProcess = observerProcess(p);
        String slaveProcess = slaveProcess(p);
        String observerLog = logPath(prefix, "loom-observer.log");
        String slaveLog = logPath(prefix, "loom-slave.log");
        String driverBindLog = logPath(prefix, "loom-driver-bind.log");
        String ubuntuStatus = ""
            + "set +e\n"
            + loomPidsFunction()
            + "echo '[loom] Ubuntu binaries'\n"
            + "command -v observer-server\n"
            + "command -v driver-agent\n"
            + "command -v slave-agent\n"
            + "echo '[loom] processes'\n"
            + "observer_pids=$(loom_pids '" + observerProcess + "')\n"
            + "test -n \"$observer_pids\" && echo 'observer: running' || echo 'observer: stopped'\n"
            + "slave_pids=$(loom_pids '" + slaveProcess + "')\n"
            + "test -n \"$slave_pids\" && echo 'slave: running' || echo 'slave: stopped'";

        return header()
            + "echo '[loom] Termux binaries'\n"
            + "command -v proot-distro\n"
            + readableCommand("pgrep -f '" + observerProcess + "'")
            + readableCommand("pgrep -f '" + slaveProcess + "'")
            + readableCommand("ps -p \"$p\" -o args= | grep -Eq 'bash -lc|proot-distro login' && continue")
            + proot(ubuntuStatus, p.user) + "\n"
            + tailLog("observer log", observerLog)
            + tailLog("slave log", slaveLog)
            + tailLog("driver bind log", driverBindLog);
    }

    public static String startObserverScript(String prefix) {
        return startObserverScript(prefix, LoomSettings.defaults());
    }

    public static String startObserverScript(String prefix, LoomSettings settings) {
        Paths p = paths(settings);
        String observerLog = logPath(prefix, "loom-observer.log");
        String observerProcess = observerProcess(p);
        String command = ""
            + loomPidsFunction()
            + "pids=$(loom_pids '" + observerProcess + "')\n"
            + "if [ -n \"$pids\" ]; then echo 'observer: already running'; exit 0; fi\n"
            + "cd " + p.observerHome + "\n"
            + "exec observer-server --config " + p.observerConfig;

        return header()
            + "command -v proot-distro\n"
            + readableCommand("nohup observer-server --config observer.yaml")
            + nohupProot(command, observerLog, p.user) + "\n";
    }

    public static String stopObserverScript() {
        return stopObserverScript(LoomSettings.defaults());
    }

    public static String stopObserverScript(LoomSettings settings) {
        Paths p = paths(settings);
        String observerProcess = observerProcess(p);
        String command = stopCommand(observerProcess, "observer");
        return header()
            + "command -v proot-distro\n"
            + readableCommand("pkill -f '" + observerProcess + "'")
            + proot(command, p.user) + "\n";
    }

    public static String registerDriverScript() {
        return bindDriverScript(LoomSettings.defaults());
    }

    public static String registerDriverScript(LoomSettings settings) {
        return bindDriverScript(settings);
    }

    public static String bindDriverScript() {
        return bindDriverScript(LoomSettings.defaults());
    }

    public static String bindDriverScript(LoomSettings settings) {
        Paths p = paths(settings);
        String command = ""
            + "cd " + p.driverProject + "\n"
            + p.driverProject + "/driver-agent register --config " + p.driverConfig;

        return header()
            + "echo '[*] 绑定 Driver 到当前 Agent'\n"
            + "set -o pipefail\n"
            + "command -v proot-distro\n"
            + proot(command, p.user) + " 2>&1 | tee -a \"$HOME/loom-driver-bind.log\"\n";
    }

    public static String bindDriverIfNeededScript(LoomSettings settings) {
        LoomSettings safeSettings = settings == null ? LoomSettings.defaults() : settings;
        Paths p = paths(safeSettings);
        String command = ""
            + driverConfigIdentityFunctions()
            + "_driver_cfg=" + shellQuote(p.driverConfig) + "\n"
            + "_reuse_driver=0\n"
            + "if [ -s \"$_driver_cfg\" ] && driver_has_identity \"$_driver_cfg\""
                + " && driver_has_credentials \"$_driver_cfg\""
                + " && driver_server_matches \"$_driver_cfg\" " + shellQuote(safeSettings.agentServerUrl) + "; then\n"
            + "  if driver_credentials_valid \"$_driver_cfg\"; then\n"
            + "    _reuse_driver=1\n"
            + "  else\n"
            + "    echo '[*] Driver credentials invalid; registering again'\n"
            + "  fi\n"
            + "fi\n"
            + "if [ \"$_reuse_driver\" = 1 ]; then\n"
            + "  echo '[*] Driver already registered; reusing existing config'\n"
            + "else\n"
            + "  cd " + p.driverProject + "\n"
            + "  " + p.driverProject + "/driver-agent register --config " + p.driverConfig + "\n"
            + "fi";

        return header()
            + "echo '[*] 绑定 Driver 到当前 Agent'\n"
            + "set -o pipefail\n"
            + "command -v proot-distro\n"
            + proot(command, p.user) + " 2>&1 | tee -a \"$HOME/loom-driver-bind.log\"\n";
    }

    public static String setupConfigScript(LoomSettings settings) {
        return setupConfigScript(settings, false);
    }

    public static String setupConfigScript(LoomSettings settings, boolean resetDriverConfig) {
        LoomSettings safeSettings = settings == null ? LoomSettings.defaults() : settings;
        Paths p = paths(safeSettings);
        String observer = b64(LoomConfigRenderer.renderObserverConfig(safeSettings, p.observerHome));
        String driver = b64(LoomConfigRenderer.renderDriverConfig(safeSettings, p.driverProject, p.driverHome));
        String slave = b64(LoomConfigRenderer.renderSlaveConfig(safeSettings, p.slaveHome, 1, "aarch64", 1));
        String mcpJson = b64(driverMcpJson(p));
        String codexMcpToml = codexMcpToml(p);
        String command = ""
            + "mkdir -p " + p.observerHome + " " + p.driverHome + " " + p.slaveHome + " "
                + p.driverProject + "/logs " + p.loomSkillsDir + " " + p.loomMcpParent + "\n"
            + "printf '%s' '" + observer + "' | base64 -d > " + p.observerConfig + "\n"
            + driverConfigWriteCommand(p, driver, resetDriverConfig, safeSettings.agentServerUrl)
            + "printf '%s' '" + slave + "' | base64 -d > " + p.slaveConfig + "\n"
            + "if [ " + shellQuote(p.provider.id) + " = 'claude' ]; then\n"
            + "  printf '%s' '" + mcpJson + "' | base64 -d > " + p.loomMcpConfig + "\n"
            + "fi\n"
            + "if [ " + shellQuote(p.provider.id) + " = 'codex' ]; then\n"
            + "  _codex_cfg=" + shellQuote(p.loomMcpConfig) + "\n"
            + "  touch \"$_codex_cfg\"\n"
            + "  awk 'BEGIN{skip=0} /^\\[mcp_servers\\.loom-driver\\]/{skip=1; next} /^\\[/{skip=0} !skip{print}' \"$_codex_cfg\" > \"$_codex_cfg.tmp\" 2>/dev/null || cp \"$_codex_cfg\" \"$_codex_cfg.tmp\"\n"
            + "  mv \"$_codex_cfg.tmp\" \"$_codex_cfg\"\n"
            + "  cat >> \"$_codex_cfg\" <<'LOOM_CODEX_MCP'\n"
            + codexMcpToml
            + "LOOM_CODEX_MCP\n"
            + "fi\n"
            + "cp /usr/local/bin/driver-agent " + p.driverProject + "/driver-agent\n"
            + "chmod +x " + p.driverProject + "/driver-agent\n"
            + "chmod 600 " + p.observerConfig + " " + p.driverConfig + " " + p.slaveConfig + "\n"
            + "echo '[*] Loom configs written'\n"
            + "echo '[*] Driver project is ready at " + p.driverProject + "'";

        return header()
            + "command -v proot-distro\n"
            + proot(command, p.user) + "\n";
    }

    public static String readDriverConfigScript(LoomSettings settings) {
        LoomSettings safeSettings = settings == null ? LoomSettings.defaults() : settings;
        Paths p = paths(safeSettings);
        String command = ""
            + "_driver_cfg=" + shellQuote(p.driverConfig) + "\n"
            + "test -f \"$_driver_cfg\"\n"
            + "echo " + shellQuote(DRIVER_CONFIG_BEGIN_MARKER) + "\n"
            + "grep -E '^(server:|credentials:|[[:space:]]+(url|name|sandbox_id|workspace_id|short_id):)' \"$_driver_cfg\" || true\n"
            + "echo " + shellQuote(DRIVER_CONFIG_END_MARKER) + "\n";

        return header()
            + "command -v proot-distro\n"
            + proot(command, p.user) + "\n";
    }

    public static String startAllInOneScript(String prefix, LoomSettings settings) {
        LoomSettings safeSettings = settings == null ? LoomSettings.defaults() : settings;
        Paths p = paths(safeSettings);
        return setupConfigScript(safeSettings)
            + "\n"
            + startObserverScript(prefix, safeSettings)
            + "\n"
            + startSlaveScript(prefix, safeSettings)
            + "\n"
            + "echo '[*] Driver project is ready at " + p.driverProject + "'\n"
            + "echo '[*] Run driver binding from Loom page if config.yaml has empty credentials.'\n";
    }

    public static String setupManagedSlaveConfigScript(
            LoomSettings settings,
            LoomSlave slave,
            String machineId,
            String computerName) {
        LoomSettings safeSettings = settings == null ? LoomSettings.defaults() : settings;
        LoomSlave safeSlave = requireSlave(slave);
        Paths p = paths(safeSettings);
        String config = b64(LoomConfigRenderer.renderManagedSlaveConfig(
            safeSettings,
            safeSlave,
            machineId,
            computerName));
        String configDir = parentDir(safeSlave.configPath);
        String logDir = parentDir(safeSlave.logPath);
        String command = ""
            + "mkdir -p " + shellQuote(configDir) + " " + shellQuote(logDir) + "\n"
            + slaveConfigIdentityFunctions()
            + "_slave_cfg=" + shellQuote(safeSlave.configPath) + "\n"
            + "_slave_tmp=" + shellQuote(safeSlave.configPath + ".tmp.android") + "\n"
            + "printf '%s' '" + config + "' | base64 -d > \"$_slave_tmp\"\n"
            + "if [ -s \"$_slave_cfg\" ] && slave_has_identity \"$_slave_cfg\""
                + " && slave_server_matches \"$_slave_cfg\" " + shellQuote(safeSettings.agentServerUrl)
                + " && slave_credentials_valid \"$_slave_cfg\"; then\n"
            + "  echo '[*] Keeping registered Slave config'\n"
            + "  rm -f \"$_slave_tmp\"\n"
            + "else\n"
            + "  if [ -s \"$_slave_cfg\" ] && slave_has_identity \"$_slave_cfg\""
                + " && slave_server_matches \"$_slave_cfg\" " + shellQuote(safeSettings.agentServerUrl) + "; then\n"
            + "    echo '[*] Slave credentials invalid; registering again'\n"
            + "  fi\n"
            + "  mv \"$_slave_tmp\" \"$_slave_cfg\"\n"
            + "fi\n"
            + "chmod 600 \"$_slave_cfg\"\n";

        return header()
            + "command -v proot-distro\n"
            + proot(command, p.user) + "\n";
    }

    public static String startManagedSlaveRuntimeScript(
        String prefix,
        LoomSettings settings,
        LoomSlave slave,
        String machineId,
        String computerName) {
        LoomSettings safeSettings = settings == null ? LoomSettings.defaults() : settings;
        LoomSlave safeSlave = requireSlave(slave);
        return setupConfigScript(safeSettings)
            + "\n" + setupManagedSlaveConfigScript(safeSettings, safeSlave, machineId, computerName)
            + "\n" + startObserverScript(prefix, safeSettings)
            + "\n" + waitForObserverScript(safeSettings)
            + "\n" + startManagedSlaveScript(prefix, safeSettings, safeSlave);
    }

    public static String startManagedSlaveScript(String prefix, LoomSettings settings, LoomSlave slave) {
        LoomSettings safeSettings = settings == null ? LoomSettings.defaults() : settings;
        LoomSlave safeSlave = requireSlave(slave);
        Paths p = paths(safeSettings);
        String process = managedSlaveProcess(safeSlave);
        String findPidCommand = ""
            + loomPidsFunction()
            + "loom_pids '" + process + "' | head -n 1\n";
        String clearLogCommand = ""
            + "mkdir -p " + shellQuote(parentDir(safeSlave.logPath)) + "\n"
            + ": > " + shellQuote(safeSlave.logPath) + "\n";
        String launchCommand = ""
            + "exec slave-agent " + safeSlave.configPath + " >> " + shellQuote(safeSlave.logPath) + " 2>&1\n";
        String identityCommand = ""
            + slaveConfigIdentityFunctions()
            + "slave_has_identity " + shellQuote(safeSlave.configPath) + "\n";
        String authUrlCommand = ""
            + "grep -Eo 'https?://[^[:space:]]*(device|user[_-]code|verification)[^[:space:]]*' "
                + shellQuote(safeSlave.logPath) + " 2>/dev/null | tail -n 1 || true\n";
        String tailErrorCommand = ""
            + "tail -n 8 " + shellQuote(safeSlave.logPath)
                + " 2>/dev/null | tr '\\n' ' ' | sed 's/[[:space:]][[:space:]]*/ /g' || true\n";
        String discoveryCardErrorCommand = ""
            + "grep -E 'publish card:.*status (4[0-9][0-9]|5[0-9][0-9])' "
                + shellQuote(safeSlave.logPath) + " 2>/dev/null | tail -n 1 || true\n";
        String script = ""
            + "auth_emitted=0\n"
            + "pids=$(" + proot(findPidCommand, p.user) + " 2>/dev/null || true)\n"
            + "if [ -n \"$pids\" ]; then\n"
            + "  pid=$(echo \"$pids\" | head -n 1)\n"
            + "else\n"
            + "  " + proot(clearLogCommand, p.user) + "\n"
            + "  nohup " + proot(launchCommand, p.user) + " >/dev/null 2>&1 &\n"
            + "  pid=$!\n"
            + "fi\n"
            + "echo \"__LOOM_SLAVE_PID__=$pid\"\n"
            + "for i in $(seq 1 300); do\n"
            + "  if ! kill -0 \"$pid\" 2>/dev/null; then\n"
            + "    err=$(" + proot(tailErrorCommand, p.user) + " 2>/dev/null || true)\n"
            + "    [ -n \"$err\" ] || err='slave-agent 启动后立即退出'\n"
            + "    echo \"__LOOM_SLAVE_ERROR__=$err\"\n"
            + "    exit 1\n"
            + "  fi\n"
            + "  card_err=$(" + proot(discoveryCardErrorCommand, p.user) + " 2>/dev/null || true)\n"
            + "  if [ -n \"$card_err\" ]; then\n"
            + "    echo \"__LOOM_SLAVE_ERROR__=Slave 已连接，但能力卡发布失败：$card_err。Loom 版本或 AgentServer discovery 协议不兼容\"\n"
            + "    exit 1\n"
            + "  fi\n"
            + "  if " + proot(identityCommand, p.user) + " >/dev/null 2>&1; then\n"
            + "    sleep 2\n"
            + "    if kill -0 \"$pid\" 2>/dev/null; then echo '__LOOM_SLAVE_READY__=1'; exit 0; fi\n"
            + "    err=$(" + proot(tailErrorCommand, p.user) + " 2>/dev/null || true)\n"
            + "    [ -n \"$err\" ] || err='slave-agent 认证完成后退出'\n"
            + "    echo \"__LOOM_SLAVE_ERROR__=$err\"\n"
            + "    exit 1\n"
            + "  fi\n"
            + "  url=$(" + proot(authUrlCommand, p.user) + " 2>/dev/null || true)\n"
            + "  if [ -n \"$url\" ] && [ \"$auth_emitted\" = 0 ]; then echo \"__LOOM_SLAVE_AUTH_URL__=$url\"; auth_emitted=1; fi\n"
            + "  sleep 1\n"
            + "done\n"
            + "if [ \"$auth_emitted\" = 1 ]; then echo '__LOOM_SLAVE_STATUS__=auth_required'; exit 0; fi\n"
            + "echo '__LOOM_SLAVE_ERROR__=启动超时，未收到认证链接或身份信息'\n"
            + "exit 1\n";

        return header()
            + "command -v proot-distro\n"
            + readableCommand("nohup slave-agent " + baseName(safeSlave.configPath))
            + script;
    }

    public static String stopManagedSlaveScript(LoomSettings settings, LoomSlave slave) {
        LoomSettings safeSettings = settings == null ? LoomSettings.defaults() : settings;
        LoomSlave safeSlave = requireSlave(slave);
        Paths p = paths(safeSettings);
        String command = stopCommand(managedSlaveProcess(safeSlave), "slave");
        return header()
            + "command -v proot-distro\n"
            + readableCommand("pkill -f '" + managedSlaveProcess(safeSlave) + "'")
            + proot(command, p.user) + "\n";
    }

    private static String waitForObserverScript(LoomSettings settings) {
        LoomSettings safeSettings = settings == null ? LoomSettings.defaults() : settings;
        Paths p = paths(safeSettings);
        String command = ""
            + "_obs_addr=" + shellQuote(safeSettings.observerListenAddr) + "\n"
            + "_obs_host=${_obs_addr%:*}\n"
            + "_obs_port=${_obs_addr##*:}\n"
            + "[ \"$_obs_host\" = \"0.0.0.0\" ] && _obs_host=127.0.0.1\n"
            + "for i in $(seq 1 15); do\n"
            + "  if (echo > /dev/tcp/$_obs_host/$_obs_port) >/dev/null 2>&1; then echo 'observer: ready'; exit 0; fi\n"
            + "  sleep 1\n"
            + "done\n"
            + "echo 'observer: wait timeout'\n"
            + "exit 1\n";
        return header()
            + "command -v proot-distro\n"
            + proot(command, p.user) + "\n";
    }

    public static String startSlaveScript(String prefix) {
        return startSlaveScript(prefix, LoomSettings.defaults());
    }

    public static String startSlaveScript(String prefix, LoomSettings settings) {
        Paths p = paths(settings);
        String slaveProcess = slaveProcess(p);
        String slaveLog = logPath(prefix, "loom-slave.log");
        String command = ""
            + loomPidsFunction()
            + "pids=$(loom_pids '" + slaveProcess + "')\n"
            + "if [ -n \"$pids\" ]; then echo 'slave: already running'; exit 0; fi\n"
            + "exec slave-agent " + p.slaveConfig;

        return header()
            + "command -v proot-distro\n"
            + readableCommand("nohup slave-agent config.yaml")
            + nohupProot(command, slaveLog, p.user) + "\n";
    }

    public static String stopSlaveScript() {
        return stopSlaveScript(LoomSettings.defaults());
    }

    public static String stopSlaveScript(LoomSettings settings) {
        Paths p = paths(settings);
        String slaveProcess = slaveProcess(p);
        String command = stopCommand(slaveProcess, "slave");
        return header()
            + "command -v proot-distro\n"
            + readableCommand("pkill -f '" + slaveProcess + "'")
            + proot(command, p.user) + "\n";
    }

    private static String header() {
        return "#!/data/data/com.termux/files/usr/bin/bash\n"
            + "set -e\n";
    }

    private static String proot(String innerCommand, String user) {
        return "proot-distro login --user " + user + " ubuntu -- bash -lc "
            + shellQuote(innerCommand);
    }

    private static String nohupProot(String innerCommand, String logPath, String user) {
        return "nohup " + proot(innerCommand, user) + " >> " + shellQuote(logPath) + " 2>&1 &";
    }

    private static String loomPidsFunction() {
        return "loom_pids() {\n"
            + "    pattern=\"$1\"\n"
            + "    pgrep -f \"$pattern\" 2>/dev/null | while read -r p; do\n"
            + "        [ \"$p\" = \"$$\" ] && continue\n"
            + "        ps -p \"$p\" -o args= 2>/dev/null | grep -Eq 'bash -lc|proot-distro login' && continue\n"
            + "        echo \"$p\"\n"
            + "    done\n"
            + "}\n";
    }

    private static String stopCommand(String processPattern, String name) {
        return ""
            + "set +e\n"
            + loomPidsFunction()
            + "pids=$(loom_pids '" + processPattern + "')\n"
            + "if [ -z \"$pids\" ]; then echo '" + name + ": not running'; exit 0; fi\n"
            + "kill $pids 2>/dev/null || true\n"
            + "echo '" + name + ": stopped'\n";
    }

    private static String logPath(String prefix, String fileName) {
        return prefix + "/../home/" + fileName;
    }

    private static String tailLog(String label, String path) {
        return "echo '[loom] recent " + label + "'\n"
            + "test -f " + shellQuote(path) + " && tail -n 40 " + shellQuote(path) + " || true\n";
    }

    private static String readableCommand(String command) {
        return "# " + command + "\n";
    }

    private static String driverMcpJson(Paths p) {
        return "{\n"
            + "  \"mcpServers\": {\n"
            + "    \"loom-driver\": {\n"
            + "      \"command\": \"" + p.driverProject + "/driver-agent\",\n"
            + "      \"args\": [\"serve-mcp\", \"--config\", \"" + p.driverConfig + "\"]\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
    }

    private static String codexMcpToml(Paths p) {
        return "\n[mcp_servers.loom-driver]\n"
            + "command = \"" + p.driverProject + "/driver-agent\"\n"
            + "args = [\"serve-mcp\", \"--config\", \"" + p.driverConfig + "\"]\n";
    }

    private static String driverConfigWriteCommand(
            Paths p,
            String driverConfigB64,
            boolean resetDriverConfig,
            String agentServerUrl) {
        if (resetDriverConfig) {
            return "printf '%s' '" + driverConfigB64 + "' | base64 -d > " + p.driverConfig + "\n";
        }
        return ""
            + driverConfigIdentityFunctions()
            + "_driver_cfg=" + shellQuote(p.driverConfig) + "\n"
            + "_driver_tmp=" + shellQuote(p.driverConfig + ".tmp.android") + "\n"
            + "printf '%s' '" + driverConfigB64 + "' | base64 -d > \"$_driver_tmp\"\n"
            + "if [ -s \"$_driver_cfg\" ] && driver_has_identity \"$_driver_cfg\""
                + " && driver_has_credentials \"$_driver_cfg\""
                + " && driver_server_matches \"$_driver_cfg\" " + shellQuote(agentServerUrl) + "; then\n"
            + "  echo '[*] Keeping registered Driver config'\n"
            + "  rm -f \"$_driver_tmp\"\n"
            + "else\n"
            + "  mv \"$_driver_tmp\" \"$_driver_cfg\"\n"
            + "fi\n";
    }

    private static String driverConfigIdentityFunctions() {
        return ""
            + "driver_yaml_scalar() {\n"
            + "  grep -E \"^[[:space:]]+$2:\" \"$1\" 2>/dev/null | head -n 1"
                + " | sed 's/^[^:]*:[[:space:]]*//; s/^\"//; s/\"$//'\n"
            + "}\n"
            + "driver_has_identity() {\n"
            + "  grep -Eq '^[[:space:]]+(sandbox_id|workspace_id|short_id):[[:space:]]*\"?[^\"#[:space:]]+' \"$1\" 2>/dev/null\n"
            + "}\n"
            + "driver_has_credentials() {\n"
            + "  grep -Eq '^[[:space:]]+sandbox_id:[[:space:]]*\"?[^\"#[:space:]]+' \"$1\" 2>/dev/null"
                + " && grep -Eq '^[[:space:]]+tunnel_token:[[:space:]]*\"?[^\"#[:space:]]+' \"$1\" 2>/dev/null"
                + " && grep -Eq '^[[:space:]]+proxy_token:[[:space:]]*\"?[^\"#[:space:]]+' \"$1\" 2>/dev/null"
                + " && grep -Eq '^[[:space:]]+workspace_id:[[:space:]]*\"?[^\"#[:space:]]+' \"$1\" 2>/dev/null"
                + " && grep -Eq '^[[:space:]]+short_id:[[:space:]]*\"?[^\"#[:space:]]+' \"$1\" 2>/dev/null\n"
            + "}\n"
            + "driver_server_matches() {\n"
            + "  _expected=\"$2\"\n"
            + "  [ -z \"$_expected\" ] && return 0\n"
            + "  _actual=$(driver_yaml_scalar \"$1\" url)\n"
            + "  _expected=${_expected%/}\n"
            + "  _actual=${_actual%/}\n"
            + "  [ \"$_actual\" = \"$_expected\" ]\n"
            + "}\n"
            + "driver_credentials_valid() {\n"
            + "  command -v curl >/dev/null 2>&1 || return 1\n"
            + "  _server=$(driver_yaml_scalar \"$1\" url)\n"
            + "  _token=$(driver_yaml_scalar \"$1\" proxy_token)\n"
            + "  [ -n \"$_server\" ] && [ -n \"$_token\" ] || return 1\n"
            + "  _server=${_server%/}\n"
            + "  _code=$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 8 --max-time 15"
                + " -H \"Authorization: Bearer $_token\" \"$_server/api/agent/whoami\" 2>/dev/null || true)\n"
            + "  [ \"$_code\" = \"200\" ]\n"
            + "}\n";
    }

    private static String slaveConfigIdentityFunctions() {
        return ""
            + "slave_yaml_scalar() {\n"
            + "  grep -E \"^[[:space:]]+$2:\" \"$1\" 2>/dev/null | head -n 1"
                + " | sed 's/^[^:]*:[[:space:]]*//; s/^\"//; s/\"$//'\n"
            + "}\n"
            + "slave_has_identity() {\n"
            + "  grep -Eq '^[[:space:]]+sandbox_id:[[:space:]]*\"?[^\"#[:space:]]+' \"$1\" 2>/dev/null"
                + " && grep -Eq '^[[:space:]]+tunnel_token:[[:space:]]*\"?[^\"#[:space:]]+' \"$1\" 2>/dev/null"
                + " && grep -Eq '^[[:space:]]+proxy_token:[[:space:]]*\"?[^\"#[:space:]]+' \"$1\" 2>/dev/null"
                + " && grep -Eq '^[[:space:]]+workspace_id:[[:space:]]*\"?[^\"#[:space:]]+' \"$1\" 2>/dev/null"
                + " && grep -Eq '^[[:space:]]+short_id:[[:space:]]*\"?[^\"#[:space:]]+' \"$1\" 2>/dev/null\n"
            + "}\n"
            + "slave_server_matches() {\n"
            + "  _expected=\"$2\"\n"
            + "  [ -z \"$_expected\" ] && return 0\n"
            + "  _actual=$(slave_yaml_scalar \"$1\" url)\n"
            + "  _expected=${_expected%/}\n"
            + "  _actual=${_actual%/}\n"
            + "  [ \"$_actual\" = \"$_expected\" ]\n"
            + "}\n"
            + "slave_credentials_valid() {\n"
            + "  command -v curl >/dev/null 2>&1 || return 1\n"
            + "  _server=$(slave_yaml_scalar \"$1\" url)\n"
            + "  _token=$(slave_yaml_scalar \"$1\" proxy_token)\n"
            + "  [ -n \"$_server\" ] && [ -n \"$_token\" ] || return 1\n"
            + "  _server=${_server%/}\n"
            + "  _code=$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 8 --max-time 15"
                + " -H \"Authorization: Bearer $_token\" \"$_server/api/agent/whoami\" 2>/dev/null || true)\n"
            + "  [ \"$_code\" = \"200\" ]\n"
            + "}\n";
    }

    private static String b64(String value) {
        return java.util.Base64.getEncoder().encodeToString(
            value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static Paths paths(LoomSettings settings) {
        AssistantProvider provider = settings == null ? AssistantProvider.CODEX : settings.agentProvider;
        return new Paths(provider);
    }

    private static String observerProcess(Paths p) {
        return "observer-server --config " + regexPath(p.observerConfig);
    }

    private static String slaveProcess(Paths p) {
        return "slave-agent " + regexPath(p.slaveConfig);
    }

    private static String managedSlaveProcess(LoomSlave slave) {
        return "slave-agent " + regexPath(slave.configPath);
    }

    private static String regexPath(String value) {
        return value.replace(".", "\\.");
    }

    private static String parentDir(String path) {
        int slash = path == null ? -1 : path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : ".";
    }

    private static String baseName(String path) {
        int slash = path == null ? -1 : path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static LoomSlave requireSlave(LoomSlave slave) {
        if (slave == null || slave.configPath.isEmpty() || slave.logPath.isEmpty()) {
            throw new IllegalArgumentException("slave config and log paths required");
        }
        return slave;
    }

    private static final class Paths {
        final String user;
        final AssistantProvider provider;
        final String home;
        final String observerHome;
        final String driverHome;
        final String driverProject;
        final String slaveHome;
        final String observerConfig;
        final String driverConfig;
        final String slaveConfig;
        final String loomMcpConfig;
        final String loomMcpParent;
        final String loomSkillsDir;

        Paths(AssistantProvider provider) {
            ProviderProfile profile = ProviderProfile.forProvider(provider);
            this.provider = profile.provider;
            user = profile.user;
            home = profile.home;
            observerHome = profile.home + "/.loom/observer-local";
            driverHome = profile.driverTokenDir;
            driverProject = profile.driverProjectDir;
            slaveHome = profile.home + "/.loom/slave-local";
            observerConfig = observerHome + "/observer.yaml";
            driverConfig = profile.driverConfigPath;
            slaveConfig = slaveHome + "/config.yaml";
            loomMcpConfig = profile.loomMcpConfigPath;
            int slash = loomMcpConfig.lastIndexOf('/');
            loomMcpParent = slash > 0 ? loomMcpConfig.substring(0, slash) : profile.home;
            loomSkillsDir = profile.loomSkillsDir;
        }
    }
}
