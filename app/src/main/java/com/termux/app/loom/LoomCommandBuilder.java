package com.termux.app.loom;

import com.termux.app.AssistantProvider;
import com.termux.app.ProviderProfile;

public final class LoomCommandBuilder {

    public static final String PROOT_USER = "claude";
    public static final String OBSERVER_HOME = "/home/claude/.loom/observer-local";
    public static final String DRIVER_HOME = "/home/claude/.loom/driver-local";
    public static final String DRIVER_PROJECT = "/home/claude/loom-driver";
    public static final String SLAVE_HOME = "/home/claude/.loom/slave-local";

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
        String driverRegisterLog = logPath(prefix, "loom-driver-register.log");
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
            + tailLog("driver register log", driverRegisterLog);
    }

    public static String startObserverScript(String prefix) {
        return startObserverScript(prefix, LoomSettings.defaults());
    }

    public static String startObserverScript(String prefix, LoomSettings settings) {
        Paths p = paths(settings);
        String observerLog = logPath(prefix, "loom-observer.log");
        String command = ""
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
        return registerDriverScript(LoomSettings.defaults());
    }

    public static String registerDriverScript(LoomSettings settings) {
        Paths p = paths(settings);
        String command = ""
            + "cd " + p.driverProject + "\n"
            + p.driverProject + "/driver-agent register --config " + p.driverConfig;

        return header()
            + "set -o pipefail\n"
            + "command -v proot-distro\n"
            + proot(command, p.user) + " 2>&1 | tee -a \"$HOME/loom-driver-register.log\"\n";
    }

    public static String setupConfigScript(LoomSettings settings) {
        LoomSettings safeSettings = settings == null ? LoomSettings.defaults() : settings;
        Paths p = paths(safeSettings);
        String observer = b64(LoomConfigRenderer.renderObserverConfig(safeSettings, p.observerHome));
        String driver = b64(LoomConfigRenderer.renderDriverConfig(safeSettings, p.driverProject, p.driverHome));
        String slave = b64(LoomConfigRenderer.renderSlaveConfig(safeSettings, p.slaveHome, 1, "aarch64", 1));
        String mcpJson = b64(driverMcpJson(p));
        String command = ""
            + "mkdir -p " + p.observerHome + " " + p.driverHome + " " + p.slaveHome + " "
                + p.driverProject + "/logs " + p.driverProject + "/.claude/skills\n"
            + "printf '%s' '" + observer + "' | base64 -d > " + p.observerConfig + "\n"
            + "printf '%s' '" + driver + "' | base64 -d > " + p.driverConfig + "\n"
            + "printf '%s' '" + slave + "' | base64 -d > " + p.slaveConfig + "\n"
            + "printf '%s' '" + mcpJson + "' | base64 -d > " + p.driverProject + "/.mcp.json\n"
            + "cp /usr/local/bin/driver-agent " + p.driverProject + "/driver-agent\n"
            + "chmod +x " + p.driverProject + "/driver-agent\n"
            + "chmod 600 " + p.observerConfig + " " + p.driverConfig + " " + p.slaveConfig + "\n"
            + "echo '[*] Loom configs written'\n"
            + "echo '[*] Driver project is ready at " + p.driverProject + "'";

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
            + "echo '[*] Run driver registration from Loom page if config.yaml has empty credentials.'\n";
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

    private static String b64(String value) {
        return java.util.Base64.getEncoder().encodeToString(
            value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static Paths paths(LoomSettings settings) {
        AssistantProvider provider = settings == null ? AssistantProvider.CLAUDE : settings.agentProvider;
        return new Paths(provider);
    }

    private static String observerProcess(Paths p) {
        return "observer-server --config " + regexPath(p.observerConfig);
    }

    private static String slaveProcess(Paths p) {
        return "slave-agent " + regexPath(p.slaveConfig);
    }

    private static String regexPath(String value) {
        return value.replace(".", "\\.");
    }

    private static final class Paths {
        final String user;
        final String observerHome;
        final String driverHome;
        final String driverProject;
        final String slaveHome;
        final String observerConfig;
        final String driverConfig;
        final String slaveConfig;

        Paths(AssistantProvider provider) {
            ProviderProfile profile = ProviderProfile.forProvider(provider);
            user = profile.user;
            observerHome = profile.home + "/.loom/observer-local";
            driverHome = profile.home + "/.loom/driver-local";
            driverProject = profile.home + "/loom-driver";
            slaveHome = profile.home + "/.loom/slave-local";
            observerConfig = observerHome + "/observer.yaml";
            driverConfig = driverProject + "/config.yaml";
            slaveConfig = slaveHome + "/config.yaml";
        }
    }
}
