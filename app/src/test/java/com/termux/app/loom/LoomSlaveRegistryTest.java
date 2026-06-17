package com.termux.app.loom;

import com.termux.app.AssistantProvider;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LoomSlaveRegistryTest {

    @Test
    public void createsMultipleFolderSlavesWithStableMachineIdentity() {
        MapStore store = new MapStore();
        LoomSlaveRegistry registry = new LoomSlaveRegistry(store);

        LoomSlaveRegistry.Machine machine = registry.ensureMachine("BeamPro");
        LoomSlave first = registry.create(machine, "/home/codex/project-a", "", AssistantProvider.CODEX);
        LoomSlave second = registry.create(machine, "/home/codex/project-b", "research", AssistantProvider.CLAUDE);

        Assert.assertFalse(machine.machineId.isEmpty());
        Assert.assertEquals("BeamPro", machine.computerName);
        Assert.assertEquals("project-a", first.name);
        Assert.assertEquals("BeamPro-project-a", first.displayName);
        Assert.assertEquals("/home/codex/project-a", first.folder);
        Assert.assertEquals(AssistantProvider.CODEX.id, first.providerId);
        Assert.assertTrue(first.configPath.contains("/home/codex/.loom/slaves/" + first.id + "/config.yaml"));
        Assert.assertTrue(first.logPath.contains("/home/codex/.loom/slaves/" + first.id + "/logs/slave.log"));
        Assert.assertEquals("research", second.name);
        Assert.assertEquals("BeamPro-research", second.displayName);
        Assert.assertEquals(AssistantProvider.CLAUDE.id, second.providerId);
        Assert.assertTrue(second.configPath.contains("/home/claude/.loom/slaves/" + second.id + "/config.yaml"));

        List<LoomSlave> reloaded = new LoomSlaveRegistry(store).list();
        Assert.assertEquals(2, reloaded.size());
        Assert.assertEquals(first.id, reloaded.get(0).id);
        Assert.assertEquals(second.id, reloaded.get(1).id);
    }

    @Test
    public void updatesSlaveStatusAndKeepsImmutableFields() {
        LoomSlaveRegistry registry = new LoomSlaveRegistry(new MapStore());
        LoomSlaveRegistry.Machine machine = registry.ensureMachine("BeamPro");
        LoomSlave slave = registry.create(machine, "/home/codex/project-a", "worker", AssistantProvider.CODEX);

        LoomSlave auth = registry.updateRuntime(
            slave.id,
            LoomSlaveStatus.AUTH_REQUIRED,
            1234,
            "https://agent.cs.ac.cn/device?user_code=ABCD",
            "");
        LoomSlave running = registry.updateRuntime(slave.id, LoomSlaveStatus.RUNNING, 1234, "", "");

        Assert.assertEquals("worker", running.name);
        Assert.assertEquals("/home/codex/project-a", running.folder);
        Assert.assertEquals(LoomSlaveStatus.AUTH_REQUIRED, auth.status);
        Assert.assertEquals("https://agent.cs.ac.cn/device?user_code=ABCD", auth.authUrl);
        Assert.assertEquals(LoomSlaveStatus.RUNNING, running.status);
        Assert.assertEquals("", running.authUrl);
        Assert.assertEquals(1234, running.pid);
    }

    @Test
    public void marksStaleStartingSlavesAsErrors() {
        LoomSlaveRegistry registry = new LoomSlaveRegistry(new MapStore());
        LoomSlaveRegistry.Machine machine = registry.ensureMachine("BeamPro");
        LoomSlave slave = registry.create(machine, "/home/codex/project-a", "worker", AssistantProvider.CODEX);
        LoomSlave starting = registry.updateRuntime(slave.id, LoomSlaveStatus.STARTING, 1234, "", "");

        int changed = registry.markStaleStarting(
            starting.updatedAtMillis + 60_001,
            60_000);

        LoomSlave latest = registry.get(slave.id);
        Assert.assertEquals(1, changed);
        Assert.assertEquals(LoomSlaveStatus.ERROR, latest.status);
        Assert.assertEquals(0, latest.pid);
        Assert.assertTrue(latest.lastError.contains("启动超时"));
    }

    @Test
    public void rejectsDuplicateDisplayNamesAndInvalidInput() {
        LoomSlaveRegistry registry = new LoomSlaveRegistry(new MapStore());
        LoomSlaveRegistry.Machine machine = registry.ensureMachine("BeamPro");
        registry.create(machine, "/home/codex/project-a", "worker", AssistantProvider.CODEX);

        assertInvalid(() -> registry.create(machine, "/home/codex/project-b", "worker", AssistantProvider.CODEX));
        assertInvalid(() -> registry.create(machine, "", "empty", AssistantProvider.CODEX));
        assertInvalid(() -> registry.create(machine, "/home/codex/project-c", "a/b", AssistantProvider.CODEX));
        assertInvalid(() -> registry.create(machine, "/home/codex/project-c", repeat("工", 21), AssistantProvider.CODEX));
    }

    private static void assertInvalid(Runnable runnable) {
        try {
            runnable.run();
            Assert.fail("Expected invalid slave input");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }

    private static final class MapStore implements LoomSlaveRegistry.Store {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void put(String key, String value) {
            values.put(key, value);
        }
    }
}
