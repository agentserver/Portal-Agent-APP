package com.termux.app;

public final class ProviderProfile {

    public final AssistantProvider provider;
    public final String displayName;
    public final String user;
    public final String home;
    public final String cliBinary;
    public final String apiKeyEnv;
    public final String baseUrlEnv;
    public final String memoryDir;
    public final String commandsDir;
    public final String instructionsFile;

    private ProviderProfile(
        AssistantProvider provider,
        String displayName,
        String user,
        String home,
        String cliBinary,
        String apiKeyEnv,
        String baseUrlEnv,
        String memoryDir,
        String commandsDir,
        String instructionsFile) {
        this.provider = provider;
        this.displayName = displayName;
        this.user = user;
        this.home = home;
        this.cliBinary = cliBinary;
        this.apiKeyEnv = apiKeyEnv;
        this.baseUrlEnv = baseUrlEnv == null ? "" : baseUrlEnv;
        this.memoryDir = memoryDir;
        this.commandsDir = commandsDir;
        this.instructionsFile = instructionsFile;
    }

    public static ProviderProfile forProvider(AssistantProvider provider) {
        if (provider == AssistantProvider.CODEX) {
            return new ProviderProfile(
                AssistantProvider.CODEX,
                "Codex",
                "codex",
                "/home/codex",
                "codex",
                "OPENAI_API_KEY",
                "",
                "",
                "",
                "/home/codex/AGENTS.md");
        }
        return new ProviderProfile(
            AssistantProvider.CLAUDE,
            "Claude Code",
            "claude",
            "/home/claude",
            "claude",
            "ANTHROPIC_API_KEY",
            "ANTHROPIC_BASE_URL",
            "/home/claude/.claude/memory",
            "/home/claude/.claude/commands",
            "/home/claude/CLAUDE.md");
    }
}
