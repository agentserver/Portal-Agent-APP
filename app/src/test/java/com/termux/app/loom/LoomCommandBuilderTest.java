package com.termux.app.loom;

import org.junit.Assert;
import org.junit.Test;

public class LoomCommandBuilderTest {
    @Test
    public void statusChecksAllRoleBinariesAndProcesses() {
        String script = LoomCommandBuilder.statusScript("/data/data/com.termux/files/usr");

        Assert.assertTrue(script.contains("command -v proot-distro"));
        Assert.assertTrue(script.contains("command -v observer-server"));
        Assert.assertTrue(script.contains("command -v driver-agent"));
        Assert.assertTrue(script.contains("command -v slave-agent"));
        Assert.assertTrue(script.contains(
            "pgrep -f 'observer-server --config /home/claude/\\.loom/observer-local/observer\\.yaml'"));
        Assert.assertTrue(script.contains(
            "pgrep -f 'slave-agent /home/claude/\\.loom/slave-local/config\\.yaml'"));
    }

    @Test
    public void startObserverUsesNohupAndSpecificConfig() {
        String script = LoomCommandBuilder.startObserverScript("/data/data/com.termux/files/usr");

        Assert.assertTrue(script.contains("cd /home/claude/.loom/observer-local"));
        Assert.assertTrue(script.contains("nohup observer-server --config observer.yaml"));
        Assert.assertTrue(script.contains("loom-observer.log"));
    }

    @Test
    public void stopScriptsUseNarrowKillPatterns() {
        Assert.assertTrue(LoomCommandBuilder.stopObserverScript()
            .contains("pkill -f 'observer-server --config /home/claude/\\.loom/observer-local/observer\\.yaml'"));
        Assert.assertTrue(LoomCommandBuilder.stopSlaveScript()
            .contains("pkill -f 'slave-agent /home/claude/\\.loom/slave-local/config\\.yaml'"));
    }

    @Test
    public void registerDriverUsesExpectedProjectPath() {
        String script = LoomCommandBuilder.registerDriverScript();

        Assert.assertTrue(script.contains("/home/claude/loom-driver/driver-agent register"));
        Assert.assertTrue(script.contains("--config /home/claude/loom-driver/config.yaml"));
    }

    @Test
    public void lifecycleScriptsFilterWrapperProcessesBeforeMatching() {
        String statusScript = LoomCommandBuilder.statusScript("/data/data/com.termux/files/usr");
        String stopObserverScript = LoomCommandBuilder.stopObserverScript();
        String stopSlaveScript = LoomCommandBuilder.stopSlaveScript();

        Assert.assertTrue(statusScript.contains("loom_pids()"));
        Assert.assertTrue(statusScript.contains("[ \"$p\" = \"$$\" ] && continue"));
        Assert.assertTrue(statusScript.contains("grep -Eq 'bash -lc|proot-distro login' && continue"));
        Assert.assertTrue(stopObserverScript.contains("loom_pids()"));
        Assert.assertTrue(stopSlaveScript.contains("loom_pids()"));
        Assert.assertFalse(stopObserverScript.contains("\npkill -f 'observer-server --config .*observer-local/observer.yaml'"));
        Assert.assertFalse(stopSlaveScript.contains("\npkill -f 'slave-agent .*\\.loom/slave-local/config.yaml'"));
    }

    @Test
    public void observerLifecycleUsesConsistentAbsoluteConfigPath() {
        String startScript = LoomCommandBuilder.startObserverScript("/data/data/com.termux/files/usr");
        String statusScript = LoomCommandBuilder.statusScript("/data/data/com.termux/files/usr");
        String stopScript = LoomCommandBuilder.stopObserverScript();

        Assert.assertTrue(startScript.contains(
            "observer-server --config /home/claude/.loom/observer-local/observer.yaml"));
        Assert.assertTrue(statusScript.contains("observer-local/observer\\.yaml"));
        Assert.assertTrue(stopScript.contains("observer-local/observer\\.yaml"));
    }

    @Test
    public void registerDriverKeepsStdoutVisibleWhileLogging() {
        String script = LoomCommandBuilder.registerDriverScript();

        Assert.assertTrue(script.contains("tee -a \"$HOME/loom-driver-register.log\""));
        Assert.assertFalse(script.contains(">> \"$HOME/loom-driver-register.log\" 2>&1"));
    }

    @Test
    public void registerDriverPreservesRegisterExitStatusThroughTee() {
        String script = LoomCommandBuilder.registerDriverScript();

        Assert.assertTrue(script.contains("set -o pipefail") || script.contains("${PIPESTATUS[0]}"));
    }

    @Test
    public void setupConfigWritesAllRoleConfigsAndDriverMcpFile() {
        String script = LoomCommandBuilder.setupConfigScript(LoomSettings.defaults());

        Assert.assertTrue(script.contains("/home/claude/.loom/observer-local/observer.yaml"));
        Assert.assertTrue(script.contains("/home/claude/loom-driver/config.yaml"));
        Assert.assertTrue(script.contains("/home/claude/.loom/slave-local/config.yaml"));
        Assert.assertTrue(script.contains("/home/claude/loom-driver/.mcp.json"));
        Assert.assertTrue(script.contains("base64 -d"));
        Assert.assertTrue(script.contains("chmod 600"));
    }

    @Test
    public void codexSetupWritesCodexRuntimePaths() {
        LoomSettings settings = LoomSettings.defaults()
            .withAgentProvider(com.termux.app.AssistantProvider.CODEX);

        String script = LoomCommandBuilder.setupConfigScript(settings);

        Assert.assertTrue(script.contains("proot-distro login --user codex ubuntu"));
        Assert.assertTrue(script.contains("/home/codex/.loom/observer-local/observer.yaml"));
        Assert.assertTrue(script.contains("/home/codex/loom-driver/config.yaml"));
        Assert.assertTrue(script.contains("/home/codex/.loom/slave-local/config.yaml"));
        Assert.assertFalse(script.contains("/home/claude/loom-driver/config.yaml"));
    }

    @Test
    public void codexLifecycleScriptsUseCodexUserPathsAndProcesses() {
        LoomSettings settings = LoomSettings.defaults()
            .withAgentProvider(com.termux.app.AssistantProvider.CODEX);

        String statusScript = LoomCommandBuilder.statusScript("/data/data/com.termux/files/usr", settings);
        String startObserverScript = LoomCommandBuilder.startObserverScript(
            "/data/data/com.termux/files/usr", settings);
        String registerScript = LoomCommandBuilder.registerDriverScript(settings);
        String startSlaveScript = LoomCommandBuilder.startSlaveScript(
            "/data/data/com.termux/files/usr", settings);
        String stopObserverScript = LoomCommandBuilder.stopObserverScript(settings);
        String stopSlaveScript = LoomCommandBuilder.stopSlaveScript(settings);

        Assert.assertTrue(statusScript.contains("proot-distro login --user codex ubuntu"));
        Assert.assertTrue(statusScript.contains(
            "observer-server --config /home/codex/\\.loom/observer-local/observer\\.yaml"));
        Assert.assertTrue(startObserverScript.contains("cd /home/codex/.loom/observer-local"));
        Assert.assertTrue(registerScript.contains("/home/codex/loom-driver/driver-agent register"));
        Assert.assertTrue(startSlaveScript.contains("slave-agent /home/codex/.loom/slave-local/config.yaml"));
        Assert.assertTrue(stopObserverScript.contains("/home/codex/\\.loom/observer-local/observer\\.yaml"));
        Assert.assertTrue(stopSlaveScript.contains("/home/codex/\\.loom/slave-local/config\\.yaml"));
        Assert.assertFalse(stopSlaveScript.contains("/home/claude/\\.loom/slave-local/config\\.yaml"));
    }

    @Test
    public void allInOneStartsObserverAndSlave() {
        String script = LoomCommandBuilder.startAllInOneScript(
            "/data/data/com.termux/files/usr", LoomSettings.defaults());

        Assert.assertTrue(script.contains("observer-server --config observer.yaml"));
        Assert.assertTrue(script.contains("slave-agent config.yaml"));
        Assert.assertTrue(script.contains("Driver project is ready"));
    }

    @Test
    public void startScriptsNohupOuterProotCommand() {
        Assert.assertTrue(LoomCommandBuilder.startObserverScript("/data/data/com.termux/files/usr")
            .contains("nohup proot-distro login --user claude ubuntu"));
        Assert.assertTrue(LoomCommandBuilder.startSlaveScript("/data/data/com.termux/files/usr")
            .contains("nohup proot-distro login --user claude ubuntu"));
    }

    @Test
    public void stopScriptsExitZeroWhenNothingIsRunningAndKillExplicitPids() {
        String stopObserverScript = LoomCommandBuilder.stopObserverScript();
        String stopSlaveScript = LoomCommandBuilder.stopSlaveScript();

        Assert.assertTrue(stopObserverScript.contains("not running"));
        Assert.assertTrue(stopObserverScript.contains("exit 0"));
        Assert.assertTrue(stopObserverScript.contains("kill $pids"));
        Assert.assertTrue(stopSlaveScript.contains("not running"));
        Assert.assertTrue(stopSlaveScript.contains("exit 0"));
        Assert.assertTrue(stopSlaveScript.contains("kill $pids"));
        Assert.assertFalse(stopObserverScript.contains("proot-distro login --user claude ubuntu -- bash -lc 'pkill -f"));
        Assert.assertFalse(stopSlaveScript.contains("proot-distro login --user claude ubuntu -- bash -lc 'pkill -f"));
    }

    @Test
    public void stopScriptsTolerateProcessExitBetweenLookupAndKill() {
        Assert.assertTrue(LoomCommandBuilder.stopObserverScript().contains("kill $pids 2>/dev/null || true"));
        Assert.assertTrue(LoomCommandBuilder.stopSlaveScript().contains("kill $pids 2>/dev/null || true"));
    }
}
