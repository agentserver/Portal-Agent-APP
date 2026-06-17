package com.termux.app.loom;

public final class LoomSlaveStatus {
    public static final String STOPPED = "stopped";
    public static final String STARTING = "starting";
    public static final String AUTH_REQUIRED = "auth_required";
    public static final String RUNNING = "running";
    public static final String PAUSED = "paused";
    public static final String ERROR = "error";

    private LoomSlaveStatus() {
    }

    public static String normalize(String status) {
        String safe = status == null ? "" : status.trim();
        if (STARTING.equals(safe)
            || AUTH_REQUIRED.equals(safe)
            || RUNNING.equals(safe)
            || PAUSED.equals(safe)
            || ERROR.equals(safe)) {
            return safe;
        }
        return STOPPED;
    }
}
