package com.termux.app.loom;

import com.termux.app.AssistantProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class LoomLocalSlaveRuntimeStoreTest {

    @Test
    public void snapshotContainsOnlyVisibleSlavesForProvider() throws Exception {
        List<LoomSlave> slaves = Arrays.asList(
            slave("one", "BeamPro-slave1", AssistantProvider.CODEX, LoomSlaveStatus.RUNNING, 20),
            slave("old", "BeamPro-slave1", AssistantProvider.CODEX, LoomSlaveStatus.STOPPED, 10),
            slave("claude", "BeamPro-claude", AssistantProvider.CLAUDE, LoomSlaveStatus.RUNNING, 30));

        JSONObject snapshot = LoomLocalSlaveRuntimeStore.snapshot(
            "BeamPro",
            slaves,
            AssistantProvider.CODEX);
        JSONArray visible = snapshot.getJSONArray("slaves");

        Assert.assertTrue(snapshot.getBoolean("local_only"));
        Assert.assertEquals("BeamPro", snapshot.getString("machine_name"));
        Assert.assertEquals(2, snapshot.getInt("raw_count"));
        Assert.assertEquals(1, snapshot.getInt("visible_count"));
        Assert.assertEquals("one", visible.getJSONObject(0).getString("id"));
        Assert.assertEquals("BeamPro-slave1", visible.getJSONObject(0).getString("display_name"));
    }

    @Test
    public void pruneDeletesOnlyUuidSlaveDirsMissingFromRegistry() throws Exception {
        File home = Files.createTempDirectory("loom-home").toFile();
        File slavesDir = new File(home, ".loom/slaves");
        Assert.assertTrue(new File(slavesDir, "11111111-1111-4111-8111-111111111111/logs").mkdirs());
        Assert.assertTrue(new File(slavesDir, "22222222-2222-4222-8222-222222222222/logs").mkdirs());
        Assert.assertTrue(new File(slavesDir, "manual-slave/logs").mkdirs());

        int deleted = LoomLocalSlaveRuntimeStore.pruneStaleSlaveDirs(
            home,
            Arrays.asList(slave(
                "11111111-1111-4111-8111-111111111111",
                "BeamPro-slave1",
                AssistantProvider.CODEX,
                LoomSlaveStatus.RUNNING,
                20)),
            AssistantProvider.CODEX);

        Assert.assertEquals(1, deleted);
        Assert.assertTrue(new File(slavesDir, "11111111-1111-4111-8111-111111111111").exists());
        Assert.assertFalse(new File(slavesDir, "22222222-2222-4222-8222-222222222222").exists());
        Assert.assertTrue(new File(slavesDir, "manual-slave").exists());
    }

    @Test
    public void rewriteCodexLoomMcpConfigReplacesOldDriverSectionWithWrapper() {
        String existing = "model = \"gpt-5.5\"\n"
            + "[mcp_servers.loom-driver]\n"
            + "command = \"/home/codex/loom-driver/driver-agent\"\n"
            + "args = [\"serve-mcp\", \"--config\", \"/home/codex/loom-driver/config.yaml\"]\n"
            + "\n[mcp_servers.android-mcp]\n"
            + "url = \"http://127.0.0.1:8765/mcp\"\n";

        String rewritten = LoomLocalSlaveRuntimeStore.rewriteCodexLoomMcpConfig(
            existing,
            AssistantProvider.CODEX);

        Assert.assertFalse(rewritten.contains("command = \"/home/codex/loom-driver/driver-agent\""));
        Assert.assertTrue(rewritten.contains(
            "command = \"/home/codex/loom-driver/driver-agent-mcp-wrapper.py\""));
        Assert.assertTrue(rewritten.contains("\"/home/codex/loom-driver/driver-agent\""));
        Assert.assertTrue(rewritten.contains("\"/home/codex/.loom/android-local-slaves.json\""));
        Assert.assertTrue(rewritten.contains("[mcp_servers.android-mcp]"));
    }

    @Test
    public void syncDoesNotCreatePrefixBeforeUbuntuRootfsExists() {
        File filesDir = RuntimeEnvironment.getApplication().getFilesDir();
        deleteRecursively(new File(filesDir, "usr"));

        LoomLocalSlaveRuntimeStore.sync(
            RuntimeEnvironment.getApplication(),
            "BeamPro",
            Arrays.asList(slave(
                "11111111-1111-4111-8111-111111111111",
                "BeamPro-slave1",
                AssistantProvider.CODEX,
                LoomSlaveStatus.RUNNING,
                20)));

        Assert.assertFalse(new File(filesDir, "usr").exists());
    }

    private static LoomSlave slave(
            String id,
            String displayName,
            AssistantProvider provider,
            String status,
            long updatedAt) {
        return new LoomSlave(
            id,
            "slave1",
            displayName,
            "/home/" + provider.id,
            "/home/" + provider.id + "/.loom/slaves/" + id + "/config.yaml",
            "/home/" + provider.id + "/.loom/slaves/" + id + "/logs/slave.log",
            provider.id,
            status,
            0,
            "",
            "",
            1,
            updatedAt);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
