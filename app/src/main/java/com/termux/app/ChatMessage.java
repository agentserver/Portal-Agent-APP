package com.termux.app;

/** 聊天消息数据类，用于简化 UI 的聊天视图。 */
public class ChatMessage {

    private static final int MAX_TOOL_DETAIL_CHARS = 12_000;

    public enum Type { USER, ASSISTANT, SYSTEM, TOOL_USE, TOOL_RESULT }

    public final Type type;
    public String content;            // 主气泡正文（USER/ASSISTANT/SYSTEM）或标题行（TOOL_USE/TOOL_RESULT）
    public String thinking;           // 思考过程原文（null = 无思考内容）
    public boolean thinkingCollapsed; // true = 折叠显示（回复完成后）

    // TOOL_USE / TOOL_RESULT 专用字段
    public String  toolName;            // 工具名，例如 "Bash"
    public String  toolDetail;          // 折叠区域内容（input JSON 或完整 output）
    public boolean toolDetailCollapsed = true;

    public ChatMessage(Type type, String content) {
        this.type = type;
        this.content = content;
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Type.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Type.ASSISTANT, content);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Type.SYSTEM, content);
    }

    /** 工具调用气泡，标题行显示 "🔧 调用 {name}"，点击展开 input JSON。 */
    public static ChatMessage toolUse(String name, String inputJson) {
        ChatMessage m = new ChatMessage(Type.TOOL_USE, "🔧 调用 " + name);
        m.toolName   = name;
        m.toolDetail = boundedToolDetail(inputJson);
        return m;
    }

    /** 工具返回气泡，标题行显示 "📥 {name}: {summary}"，点击展开完整内容。 */
    public static ChatMessage toolResult(String name, String summary, String full) {
        String title = (summary == null || summary.isEmpty())
                ? "📥 " + name
                : "📥 " + name + ": " + summary;
        ChatMessage m = new ChatMessage(Type.TOOL_RESULT, title);
        m.toolName   = name;
        m.toolDetail = boundedToolDetail(full);
        return m;
    }

    private static String boundedToolDetail(String value) {
        if (value == null) return "";
        if (value.length() <= MAX_TOOL_DETAIL_CHARS) return value;
        return value.substring(0, MAX_TOOL_DETAIL_CHARS)
            + "\n\n... 内容过长，已省略 "
            + (value.length() - MAX_TOOL_DETAIL_CHARS)
            + " 个字符。完整结果已返回给模型，聊天界面仅显示摘要。";
    }
}
