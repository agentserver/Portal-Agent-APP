package com.termux.app.loom;

import com.termux.app.AssistantProvider;

public final class LoomSettings {

    public static final String PREFS_NAME = "loom_config";

    public static final String KEY_ROLE_MODE = "role_mode";
    public static final String KEY_AGENT_PROVIDER = "agent_provider";
    public static final String KEY_OBSERVER_URL = "observer_url";
    public static final String KEY_OBSERVER_LISTEN_ADDR = "observer_listen_addr";
    public static final String KEY_WORKSPACE_ID = "workspace_id";
    public static final String KEY_WORKSPACE_API_KEY = "workspace_api_key";
    public static final String KEY_AGENTSERVER_URL = "agentserver_url";
    public static final String KEY_OBSERVER_NAME = "observer_name";
    public static final String KEY_DRIVER_NAME = "driver_name";
    public static final String KEY_SLAVE_NAME = "slave_name";
    public static final String KEY_TAGS = "tags";

    public final String roleMode;
    public final String observerUrl;
    public final String observerListenAddr;
    public final String workspaceId;
    public final String workspaceApiKey;
    public final String agentServerUrl;
    public final String observerName;
    public final String driverName;
    public final String slaveName;
    public final String tags;
    public final AssistantProvider agentProvider;

    private LoomSettings(
        String roleMode,
        AssistantProvider agentProvider,
        String observerUrl,
        String observerListenAddr,
        String workspaceId,
        String workspaceApiKey,
        String agentServerUrl,
        String observerName,
        String driverName,
        String slaveName,
        String tags) {
        this.roleMode = roleMode;
        this.agentProvider = agentProvider == null ? AssistantProvider.CLAUDE : agentProvider;
        this.observerUrl = observerUrl;
        this.observerListenAddr = observerListenAddr;
        this.workspaceId = workspaceId;
        this.workspaceApiKey = workspaceApiKey;
        this.agentServerUrl = agentServerUrl;
        this.observerName = observerName;
        this.driverName = driverName;
        this.slaveName = slaveName;
        this.tags = tags;
    }

    public static LoomSettings defaults() {
        return new LoomSettings(
            "all",
            AssistantProvider.CLAUDE,
            "http://127.0.0.1:8090",
            "127.0.0.1:8090",
            "ws-phone",
            "loom-local-bootstrap-key",
            "https://agent.cs.ac.cn",
            "observer-phone",
            "driver-phone",
            "slave-phone",
            "android,phone,aarch64");
    }

    public LoomSettings withObserverUrl(String observerUrl) {
        return copy(roleMode, agentProvider, observerUrl, observerListenAddr, workspaceId, workspaceApiKey, agentServerUrl,
            observerName, driverName, slaveName, tags);
    }

    public LoomSettings withRoleMode(String roleMode) {
        return copy(roleMode, agentProvider, observerUrl, observerListenAddr, workspaceId, workspaceApiKey, agentServerUrl,
            observerName, driverName, slaveName, tags);
    }

    public LoomSettings withAgentProvider(AssistantProvider agentProvider) {
        return copy(roleMode, agentProvider, observerUrl, observerListenAddr, workspaceId, workspaceApiKey, agentServerUrl,
            observerName, driverName, slaveName, tags);
    }

    public LoomSettings withObserverListenAddr(String observerListenAddr) {
        return copy(roleMode, agentProvider, observerUrl, observerListenAddr, workspaceId, workspaceApiKey, agentServerUrl,
            observerName, driverName, slaveName, tags);
    }

    public LoomSettings withWorkspaceId(String workspaceId) {
        return copy(roleMode, agentProvider, observerUrl, observerListenAddr, workspaceId, workspaceApiKey, agentServerUrl,
            observerName, driverName, slaveName, tags);
    }

    public LoomSettings withWorkspaceApiKey(String workspaceApiKey) {
        return copy(roleMode, agentProvider, observerUrl, observerListenAddr, workspaceId, workspaceApiKey, agentServerUrl,
            observerName, driverName, slaveName, tags);
    }

    public LoomSettings withAgentServerUrl(String agentServerUrl) {
        return copy(roleMode, agentProvider, observerUrl, observerListenAddr, workspaceId, workspaceApiKey, agentServerUrl,
            observerName, driverName, slaveName, tags);
    }

    public LoomSettings withObserverName(String observerName) {
        return copy(roleMode, agentProvider, observerUrl, observerListenAddr, workspaceId, workspaceApiKey, agentServerUrl,
            observerName, driverName, slaveName, tags);
    }

    public LoomSettings withDriverName(String driverName) {
        return copy(roleMode, agentProvider, observerUrl, observerListenAddr, workspaceId, workspaceApiKey, agentServerUrl,
            observerName, driverName, slaveName, tags);
    }

    public LoomSettings withSlaveName(String slaveName) {
        return copy(roleMode, agentProvider, observerUrl, observerListenAddr, workspaceId, workspaceApiKey, agentServerUrl,
            observerName, driverName, slaveName, tags);
    }

    public LoomSettings withTags(String tags) {
        return copy(roleMode, agentProvider, observerUrl, observerListenAddr, workspaceId, workspaceApiKey, agentServerUrl,
            observerName, driverName, slaveName, tags);
    }

    public static String yamlQuote(String value) {
        String safeValue = value == null ? "" : value;
        return "\"" + safeValue.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private LoomSettings copy(
        String roleMode,
        AssistantProvider agentProvider,
        String observerUrl,
        String observerListenAddr,
        String workspaceId,
        String workspaceApiKey,
        String agentServerUrl,
        String observerName,
        String driverName,
        String slaveName,
        String tags) {
        AssistantProvider safeProvider = agentProvider == null ? AssistantProvider.CLAUDE : agentProvider;
        return new LoomSettings(roleMode, safeProvider, observerUrl, observerListenAddr, workspaceId, workspaceApiKey,
            agentServerUrl, observerName, driverName, slaveName, tags);
    }
}
