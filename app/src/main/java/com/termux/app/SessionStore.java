package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 历史对话会话存储（SharedPreferences）。
 * 每条记录含：session ID（用于 claude --resume）、时间戳、首条消息预览。
 * 最多保存 50 条，按最近使用倒序排列。
 */
public class SessionStore {

    private static final String PREFS_CLAUDE = "claude_sessions";
    private static final String PREFS_CODEX  = "codex_sessions";
    private static final String K_COUNT   = "count";
    private static final int    MAX_ITEMS = 50;

    // -------------------------------------------------------------------------

    public static class Entry {
        public final String id;
        public final long   timestamp;
        public final String preview;   // 首条用户消息前 80 字符

        Entry(String id, long timestamp, String preview) {
            this.id        = id;
            this.timestamp = timestamp;
            this.preview   = preview;
        }

        /** 格式化时间，如 "今天 14:32" 或 "04-18 09:12"。 */
        public String formatTime() {
            Date d   = new Date(timestamp);
            Date now = new Date();
            SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
            SimpleDateFormat dateFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            // 同一天显示 "今天 HH:mm"
            SimpleDateFormat dayFmt  = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
            if (dayFmt.format(d).equals(dayFmt.format(now))) {
                return "今天 " + timeFmt.format(d);
            }
            return dateFmt.format(d);
        }
    }

    // -------------------------------------------------------------------------

    private final SharedPreferences mPrefs;

    public SessionStore(Context ctx) {
        this(ctx, AssistantProvider.CLAUDE);
    }

    public SessionStore(Context ctx, AssistantProvider provider) {
        mPrefs = ctx.getApplicationContext()
            .getSharedPreferences(prefsNameForProvider(provider), Context.MODE_PRIVATE);
    }

    public static String prefsNameForProvider(AssistantProvider provider) {
        return provider == AssistantProvider.CODEX ? PREFS_CODEX : PREFS_CLAUDE;
    }

    /** 读取所有条目（最近在前）。 */
    public List<Entry> loadAll() {
        int count = mPrefs.getInt(K_COUNT, 0);
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id      = mPrefs.getString("id_"      + i, null);
            long   ts      = mPrefs.getLong  ("ts_"      + i, 0);
            String preview = mPrefs.getString("preview_" + i, "");
            if (id != null) list.add(new Entry(id, ts, preview));
        }
        return list;
    }

    /**
     * 添加或更新一条会话记录。
     * 若已存在相同 ID 的条目则先移除（去重），再插入到列表头部。
     */
    public void add(String sessionId, long timestamp, String firstUserMessage) {
        List<Entry> list = loadAll();
        // 去重：移除已有相同 ID 的条目
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(sessionId)) list.remove(i);
        }
        String preview = firstUserMessage.length() > 80
                ? firstUserMessage.substring(0, 80) + "…"
                : firstUserMessage;
        list.add(0, new Entry(sessionId, timestamp, preview));
        // 超出上限时截断
        while (list.size() > MAX_ITEMS) list.remove(list.size() - 1);
        saveAll(list);
    }

    /** 删除指定 session ID 的记录。 */
    public void delete(String sessionId) {
        List<Entry> list = loadAll();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(sessionId)) { list.remove(i); break; }
        }
        saveAll(list);
    }

    private void saveAll(List<Entry> list) {
        SharedPreferences.Editor ed = mPrefs.edit();
        ed.putInt(K_COUNT, list.size());
        for (int i = 0; i < list.size(); i++) {
            ed.putString("id_"      + i, list.get(i).id);
            ed.putLong  ("ts_"      + i, list.get(i).timestamp);
            ed.putString("preview_" + i, list.get(i).preview);
        }
        ed.apply();
    }
}
