package com.termux.app.loom;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.app.AssistantProvider;
import com.termux.app.ProviderProfile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class LoomSlaveRegistry {

    private static final String PREFS_NAME = "loom_slaves";
    private static final String KEY_MACHINE_ID = "machine_id";
    private static final String KEY_MACHINE_NAME = "machine_name";
    private static final String KEY_SLAVES = "slaves_v1";
    private static final int MAX_NAME_CHARS = 20;

    private final Store store;

    public interface Store {
        String get(String key);
        void put(String key, String value);
    }

    public static final class Machine {
        public final String machineId;
        public final String computerName;

        Machine(String machineId, String computerName) {
            this.machineId = clean(machineId);
            this.computerName = clean(computerName);
        }
    }

    public LoomSlaveRegistry(Store store) {
        if (store == null) throw new IllegalArgumentException("store required");
        this.store = store;
    }

    public static LoomSlaveRegistry forContext(Context context) {
        if (context == null) throw new IllegalArgumentException("context required");
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return new LoomSlaveRegistry(new SharedPreferencesStore(prefs));
    }

    public synchronized Machine ensureMachine(String computerName) {
        String existingId = clean(store.get(KEY_MACHINE_ID));
        String existingName = clean(store.get(KEY_MACHINE_NAME));
        if (!existingId.isEmpty() && !existingName.isEmpty()) {
            return new Machine(existingId, existingName);
        }
        String name = clean(computerName);
        if (name.isEmpty()) name = "Android";
        String id = UUID.randomUUID().toString();
        store.put(KEY_MACHINE_ID, id);
        store.put(KEY_MACHINE_NAME, name);
        return new Machine(id, name);
    }

    public synchronized Machine machineOrDefault(String computerName) {
        return ensureMachine(computerName);
    }

    public synchronized List<LoomSlave> list() {
        return new ArrayList<>(readSlaves());
    }

    public synchronized int markStaleStarting(long nowMillis, long timeoutMillis) {
        List<LoomSlave> slaves = readSlaves();
        int changed = 0;
        long safeTimeout = Math.max(1, timeoutMillis);
        for (int i = 0; i < slaves.size(); i++) {
            LoomSlave slave = slaves.get(i);
            if (!LoomSlaveStatus.STARTING.equals(slave.status)) continue;
            if (nowMillis - slave.updatedAtMillis <= safeTimeout) continue;
            slaves.set(i, slave.withRuntime(
                LoomSlaveStatus.ERROR,
                0,
                "",
                "启动超时，请重试",
                nowMillis));
            changed++;
        }
        if (changed > 0) writeSlaves(slaves);
        return changed;
    }

    public synchronized LoomSlave create(
            Machine machine,
            String folder,
            String name,
            AssistantProvider provider) {
        if (machine == null || clean(machine.machineId).isEmpty() || clean(machine.computerName).isEmpty()) {
            throw new IllegalArgumentException("machine identity required");
        }
        String safeFolder = normalizeFolder(folder);
        String safeName = normalizeName(name, safeFolder);
        validateName(safeName);
        AssistantProvider safeProvider = provider == null ? AssistantProvider.CODEX : provider;
        ProviderProfile profile = ProviderProfile.forProvider(safeProvider);
        String displayName = machine.computerName + "-" + safeName;

        List<LoomSlave> slaves = readSlaves();
        for (LoomSlave existing : slaves) {
            if (displayName.equals(existing.displayName)) {
                throw new IllegalArgumentException("slave display name already exists: " + displayName);
            }
        }

        String id = UUID.randomUUID().toString();
        String root = profile.home + "/.loom/slaves/" + id;
        long now = System.currentTimeMillis();
        LoomSlave slave = new LoomSlave(
            id,
            safeName,
            displayName,
            safeFolder,
            root + "/config.yaml",
            root + "/logs/slave.log",
            safeProvider.id,
            LoomSlaveStatus.STOPPED,
            0,
            "",
            "",
            now,
            now);
        slaves.add(slave);
        writeSlaves(slaves);
        return slave;
    }

    public synchronized LoomSlave updateRuntime(
            String id,
            String status,
            int pid,
            String authUrl,
            String lastError) {
        List<LoomSlave> slaves = readSlaves();
        for (int i = 0; i < slaves.size(); i++) {
            LoomSlave slave = slaves.get(i);
            if (!slave.id.equals(clean(id))) continue;
            LoomSlave updated = slave.withRuntime(
                status,
                pid,
                authUrl,
                lastError,
                System.currentTimeMillis());
            slaves.set(i, updated);
            writeSlaves(slaves);
            return updated;
        }
        throw new IllegalArgumentException("slave not found: " + id);
    }

    public synchronized void delete(String id) {
        List<LoomSlave> slaves = readSlaves();
        String safeId = clean(id);
        for (int i = 0; i < slaves.size(); i++) {
            if (!slaves.get(i).id.equals(safeId)) continue;
            slaves.remove(i);
            writeSlaves(slaves);
            return;
        }
        throw new IllegalArgumentException("slave not found: " + id);
    }

    public synchronized LoomSlave get(String id) {
        String safeId = clean(id);
        for (LoomSlave slave : readSlaves()) {
            if (slave.id.equals(safeId)) return slave;
        }
        throw new IllegalArgumentException("slave not found: " + id);
    }

    private List<LoomSlave> readSlaves() {
        String raw = store.get(KEY_SLAVES);
        List<LoomSlave> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        String[] lines = raw.split("\\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] p = line.split("\\t", -1);
            if (p.length != 13) continue;
            out.add(new LoomSlave(
                decode(p[0]),
                decode(p[1]),
                decode(p[2]),
                decode(p[3]),
                decode(p[4]),
                decode(p[5]),
                decode(p[6]),
                decode(p[7]),
                parseInt(decode(p[8])),
                decode(p[9]),
                decode(p[10]),
                parseLong(decode(p[11])),
                parseLong(decode(p[12]))));
        }
        return out;
    }

    private void writeSlaves(List<LoomSlave> slaves) {
        StringBuilder out = new StringBuilder();
        for (LoomSlave slave : slaves) {
            if (out.length() > 0) out.append('\n');
            out.append(encode(slave.id)).append('\t')
                .append(encode(slave.name)).append('\t')
                .append(encode(slave.displayName)).append('\t')
                .append(encode(slave.folder)).append('\t')
                .append(encode(slave.configPath)).append('\t')
                .append(encode(slave.logPath)).append('\t')
                .append(encode(slave.providerId)).append('\t')
                .append(encode(slave.status)).append('\t')
                .append(encode(String.valueOf(slave.pid))).append('\t')
                .append(encode(slave.authUrl)).append('\t')
                .append(encode(slave.lastError)).append('\t')
                .append(encode(String.valueOf(slave.createdAtMillis))).append('\t')
                .append(encode(String.valueOf(slave.updatedAtMillis)));
        }
        store.put(KEY_SLAVES, out.toString());
    }

    private static String normalizeFolder(String folder) {
        String safe = clean(folder).replace('\\', '/');
        while (safe.endsWith("/") && safe.length() > 1) {
            safe = safe.substring(0, safe.length() - 1);
        }
        if (safe.isEmpty()) throw new IllegalArgumentException("folder required");
        return safe;
    }

    private static String normalizeName(String name, String folder) {
        String safe = clean(name);
        if (!safe.isEmpty()) return safe;
        int slash = folder.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < folder.length()) {
            return folder.substring(slash + 1);
        }
        return folder;
    }

    private static void validateName(String name) {
        String safe = clean(name);
        if (safe.isEmpty()) throw new IllegalArgumentException("slave name required");
        if (safe.codePointCount(0, safe.length()) > MAX_NAME_CHARS) {
            throw new IllegalArgumentException("slave name must be at most 20 characters");
        }
        if (safe.indexOf('/') >= 0 || safe.indexOf('\\') >= 0 || safe.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("slave name contains path separators");
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(clean(value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        if (value == null || value.isEmpty()) return "";
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class SharedPreferencesStore implements Store {
        private final SharedPreferences prefs;

        SharedPreferencesStore(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @Override
        public String get(String key) {
            return prefs.getString(key, "");
        }

        @Override
        public void put(String key, String value) {
            prefs.edit().putString(key, value == null ? "" : value).apply();
        }
    }
}
