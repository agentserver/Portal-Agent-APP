package com.termux.app.loom;

public final class LoomSlave {
    public final String id;
    public final String name;
    public final String displayName;
    public final String folder;
    public final String configPath;
    public final String logPath;
    public final String providerId;
    public final String status;
    public final int pid;
    public final String authUrl;
    public final String lastError;
    public final long createdAtMillis;
    public final long updatedAtMillis;

    public LoomSlave(
            String id,
            String name,
            String displayName,
            String folder,
            String configPath,
            String logPath,
            String providerId,
            String status,
            int pid,
            String authUrl,
            String lastError,
            long createdAtMillis,
            long updatedAtMillis) {
        this.id = clean(id);
        this.name = clean(name);
        this.displayName = clean(displayName);
        this.folder = clean(folder);
        this.configPath = clean(configPath);
        this.logPath = clean(logPath);
        this.providerId = clean(providerId);
        this.status = LoomSlaveStatus.normalize(status);
        this.pid = Math.max(0, pid);
        this.authUrl = clean(authUrl);
        this.lastError = clean(lastError);
        this.createdAtMillis = createdAtMillis;
        this.updatedAtMillis = updatedAtMillis;
    }

    LoomSlave withRuntime(String status, int pid, String authUrl, String lastError, long updatedAtMillis) {
        return new LoomSlave(
            id,
            name,
            displayName,
            folder,
            configPath,
            logPath,
            providerId,
            status,
            pid,
            authUrl,
            lastError,
            createdAtMillis,
            updatedAtMillis);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
