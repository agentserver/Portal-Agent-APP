package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class AgentTaskStore {

    private static final String PREFS_CLAUDE = "agent_tasks_v2";
    private static final String PREFS_CODEX  = "agent_tasks_v2_codex";
    private static final String KEY   = "tasks";
    private static final int    MAX   = 50;

    private final SharedPreferences mPrefs;

    public AgentTaskStore(Context ctx) {
        this(ctx, AssistantProvider.CLAUDE);
    }

    public AgentTaskStore(Context ctx, AssistantProvider provider) {
        mPrefs = ctx.getApplicationContext()
            .getSharedPreferences(prefsNameForProvider(provider), Context.MODE_PRIVATE);
    }

    public static String prefsNameForProvider(AssistantProvider provider) {
        return provider == AssistantProvider.CODEX ? PREFS_CODEX : PREFS_CLAUDE;
    }

    /** 按 id 存在则更新；否则头插。 */
    public synchronized void upsert(AgentTask task) {
        if (task == null || task.id == null) return;
        List<AgentTask> list = loadAll();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (task.id.equals(list.get(i).id)) {
                list.set(i, task);
                found = true;
                break;
            }
        }
        if (!found) {
            list.add(0, task);
            if (list.size() > MAX) list = list.subList(0, MAX);
        }
        save(list);
    }

    /** 全量读取，最新在前。 */
    public synchronized List<AgentTask> loadAll() {
        List<AgentTask> result = new ArrayList<>();
        String raw = mPrefs.getString(KEY, null);
        if (raw == null) return result;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                AgentTask t = AgentTask.fromJson(arr.getJSONObject(i));
                if (t != null) result.add(t);
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** 按 id 读取单条，找不到返回 null。 */
    public synchronized AgentTask get(String id) {
        if (id == null) return null;
        for (AgentTask t : loadAll()) {
            if (id.equals(t.id)) return t;
        }
        return null;
    }

    public synchronized void delete(String id) {
        if (id == null) return;
        List<AgentTask> list = loadAll();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (id.equals(list.get(i).id)) {
                list.remove(i);
                break;
            }
        }
        save(list);
    }

    public synchronized void clear() {
        mPrefs.edit().remove(KEY).apply();
    }

    private void save(List<AgentTask> list) {
        try {
            JSONArray arr = new JSONArray();
            for (AgentTask t : list) arr.put(t.toJson());
            mPrefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }
}
