package com.termux.app.loom;

import android.content.Context;

import com.termux.app.AssistantProvider;
import com.termux.app.ProviderProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class LoomLocalSlaveRuntimeStore {

    public static final String SNAPSHOT_PATH = ".loom/android-local-slaves.json";
    private static final String UBUNTU_ROOTFS_RELATIVE =
        "usr/var/lib/proot-distro/installed-rootfs/ubuntu";
    private static final Pattern UUID_DIR = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private LoomLocalSlaveRuntimeStore() {
    }

    public static void sync(Context context, String machineName, List<LoomSlave> slaves) {
        if (context == null) return;
        if (!isUbuntuRootfsReady(context)) return;
        for (AssistantProvider provider : AssistantProvider.values()) {
            try {
                File home = providerHome(context, provider);
                writeSnapshot(home, snapshot(machineName, slaves, provider));
                pruneStaleSlaveDirs(home, slaves, provider);
                ensureDriverMcpRuntime(home, provider);
            } catch (Exception ignored) {
            }
        }
    }

    public static JSONObject snapshot(
            String machineName,
            List<LoomSlave> slaves,
            AssistantProvider provider) throws Exception {
        List<LoomSlave> raw = filterByProvider(slaves, provider);
        List<LoomSlave> visible = LoomSlaveListPolicy.visibleSlaves(raw);
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("local_only", true);
        result.put("machine_name", clean(machineName));
        result.put("provider", provider == null ? "all" : provider.id);
        result.put("raw_count", raw.size());
        result.put("visible_count", visible.size());
        JSONArray items = new JSONArray();
        for (LoomSlave slave : visible) {
            items.put(toJson(slave));
        }
        result.put("slaves", items);
        return result;
    }

    public static int pruneStaleSlaveDirs(
            File home,
            List<LoomSlave> slaves,
            AssistantProvider provider) {
        if (home == null) return 0;
        File slavesDir = new File(home, ".loom/slaves");
        File[] children = slavesDir.listFiles();
        if (children == null || children.length == 0) return 0;

        Set<String> keep = new HashSet<>();
        for (LoomSlave slave : filterByProvider(slaves, provider)) {
            if (!clean(slave.id).isEmpty()) keep.add(slave.id);
        }

        int deleted = 0;
        for (File child : children) {
            if (child == null || !child.isDirectory()) continue;
            String name = child.getName();
            if (!UUID_DIR.matcher(name).matches()) continue;
            if (keep.contains(name)) continue;
            if (deleteRecursively(child)) deleted++;
        }
        return deleted;
    }

    static String rewriteCodexLoomMcpConfig(String content, AssistantProvider provider) {
        ProviderProfile profile = ProviderProfile.forProvider(provider);
        String existing = content == null ? "" : content;
        StringBuilder kept = new StringBuilder();
        boolean skip = false;
        String[] lines = existing.split("\\r?\\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (isLoomMcpSection(trimmed)) {
                skip = true;
                continue;
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                skip = false;
            }
            if (!skip) kept.append(line).append('\n');
        }
        String out = kept.toString().replaceAll("\\n{3,}", "\n\n").trim();
        StringBuilder result = new StringBuilder();
        if (!out.isEmpty()) result.append(out).append("\n\n");
        result.append("[mcp_servers.loom-driver]\n")
            .append("command = \"").append(profile.driverProjectDir)
            .append("/driver-agent-mcp-wrapper.py\"\n")
            .append("args = [\"").append(profile.driverProjectDir)
            .append("/driver-agent\", \"serve-mcp\", \"--config\", \"")
            .append(profile.driverConfigPath)
            .append("\", \"--local-slaves\", \"")
            .append(profile.home).append('/').append(SNAPSHOT_PATH)
            .append("\"]\n");
        return result.toString();
    }

    private static File providerHome(Context context, AssistantProvider provider) {
        ProviderProfile profile = ProviderProfile.forProvider(provider);
        return new File(context.getFilesDir(), UBUNTU_ROOTFS_RELATIVE + profile.home);
    }

    private static boolean isUbuntuRootfsReady(Context context) {
        File rootfs = new File(context.getFilesDir(), UBUNTU_ROOTFS_RELATIVE);
        if (!rootfs.isDirectory()) return false;
        if (!new File(rootfs, "etc/passwd").isFile()) return false;
        if (!new File(rootfs, "etc/os-release").isFile()) return false;
        if (!new File(rootfs, "usr/bin/env").isFile()) return false;
        return new File(rootfs, "bin/sh").exists()
            || new File(rootfs, "usr/bin/sh").exists();
    }

    private static void ensureDriverMcpRuntime(File home, AssistantProvider provider) throws IOException {
        ProviderProfile profile = ProviderProfile.forProvider(provider);
        File driverProject = new File(home, "loom-driver");
        if (!driverProject.exists() && !driverProject.mkdirs()) return;

        File wrapper = new File(driverProject, "driver-agent-mcp-wrapper.py");
        writeIfChanged(wrapper, LoomCommandBuilder.driverMcpWrapperPy());
        wrapper.setExecutable(true, false);

        if (provider == AssistantProvider.CODEX) {
            File config = new File(home, ".codex/config.toml");
            String existing = config.isFile()
                ? new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8)
                : "";
            writeIfChanged(config, rewriteCodexLoomMcpConfig(existing, provider));
        } else {
            File config = new File(driverProject, ".mcp.json");
            writeIfChanged(config, driverMcpJson(profile));
        }
    }

    private static void writeSnapshot(File home, JSONObject snapshot) throws Exception {
        File file = new File(home, SNAPSHOT_PATH);
        writeIfChanged(file, snapshot.toString(2) + "\n");
    }

    private static void writeIfChanged(File file, String content) throws IOException {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return;
        String safeContent = content == null ? "" : content;
        if (file.isFile()) {
            String existing = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (existing.equals(safeContent)) return;
        }
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(safeContent.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String driverMcpJson(ProviderProfile profile) {
        return "{\n"
            + "  \"mcpServers\": {\n"
            + "    \"loom-driver\": {\n"
            + "      \"command\": \"" + profile.driverProjectDir + "/driver-agent-mcp-wrapper.py\",\n"
            + "      \"args\": [\"" + profile.driverProjectDir + "/driver-agent\", \"serve-mcp\", \"--config\", \""
                + profile.driverConfigPath + "\", \"--local-slaves\", \"" + profile.home + "/" + SNAPSHOT_PATH + "\"]\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
    }

    private static boolean isLoomMcpSection(String section) {
        return "[mcp_servers.loom-driver]".equals(section)
            || section.startsWith("[mcp_servers.loom-driver.");
    }

    private static List<LoomSlave> filterByProvider(List<LoomSlave> slaves, AssistantProvider provider) {
        List<LoomSlave> out = new ArrayList<>();
        if (slaves == null) return out;
        for (LoomSlave slave : slaves) {
            if (slave == null) continue;
            if (provider != null && !provider.id.equals(slave.providerId)) continue;
            out.add(slave);
        }
        return out;
    }

    private static JSONObject toJson(LoomSlave slave) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", slave.id);
        item.put("name", slave.name);
        item.put("display_name", slave.displayName);
        item.put("folder", slave.folder);
        item.put("provider", slave.providerId);
        item.put("status", slave.status);
        item.put("pid", slave.pid);
        item.put("auth_required", LoomSlaveStatus.AUTH_REQUIRED.equals(slave.status));
        item.put("updated_at_millis", slave.updatedAtMillis);
        return item;
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        return file.delete();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
