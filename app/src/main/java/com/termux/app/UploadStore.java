package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 上传文件元数据存储。
 *
 * 数据模型（per-UUID 目录隔离）：
 *   每次用户点 📎 上传一个文件，App 生成一个新 UUID。
 *   物理文件路径：~/uploads/&lt;uuid&gt;/&lt;filename&gt;（每个 UUID 独占一个目录）
 *   Store 记账：sessionId → List&lt;Entry{uuid, filename}&gt;
 *
 *   sessionId 为 PENDING("__pending__") 时表示尚未拿到 Claude session_id 的临时挂靠桶；
 *   收到 session_id 后调 commitPending 把 entries 迁移到真正的 sessionId 桶（只动元数据，物理路径不变）。
 */
public class UploadStore {

    private static final String PREFS_CLAUDE = "upload_index";
    private static final String PREFS_CODEX  = "upload_index_codex";
    private static final String KEY   = "data";
    static final String PENDING = "__pending__";

    /** 一次上传的元数据：物理目录 uuid + 用户可读文件名。 */
    public static final class Entry {
        public final String uuid;
        public final String filename;
        public Entry(String uuid, String filename) {
            this.uuid     = uuid;
            this.filename = filename;
        }
    }

    private final SharedPreferences mPrefs;

    public UploadStore(Context ctx) {
        this(ctx, AssistantProvider.CLAUDE);
    }

    public UploadStore(Context ctx, AssistantProvider provider) {
        mPrefs = ctx.getApplicationContext()
            .getSharedPreferences(prefsNameForProvider(provider), Context.MODE_PRIVATE);
    }

    public static String prefsNameForProvider(AssistantProvider provider) {
        return provider == AssistantProvider.CODEX ? PREFS_CODEX : PREFS_CLAUDE;
    }

    /** 写入指定 session 桶（sessionId 可以是 PENDING）。 */
    public synchronized void addFile(String sessionId, String uuid, String filename) {
        Map<String, List<Entry>> all = loadMap();
        List<Entry> entries = all.computeIfAbsent(sessionId, k -> new ArrayList<>());
        // uuid 唯一，重复添加忽略
        for (Entry e : entries) if (e.uuid.equals(uuid)) return;
        entries.add(new Entry(uuid, filename));
        saveMap(all);
    }

    /** 把 __pending__ 桶合并到真实 sessionId 桶，清空 pending。只动元数据，不动物理文件。 */
    public synchronized void commitPending(String sessionId) {
        Map<String, List<Entry>> all = loadMap();
        List<Entry> pending = all.remove(PENDING);
        if (pending != null && !pending.isEmpty()) {
            List<Entry> dest = all.computeIfAbsent(sessionId, k -> new ArrayList<>());
            for (Entry p : pending) {
                boolean dup = false;
                for (Entry d : dest) if (d.uuid.equals(p.uuid)) { dup = true; break; }
                if (!dup) dest.add(p);
            }
        }
        saveMap(all);
    }

    /** 全量读取，返回 sessionId → entries 的 Map。 */
    public synchronized Map<String, List<Entry>> getAll() {
        return loadMap();
    }

    /** 读取某 session 的 entries。 */
    public synchronized List<Entry> getFiles(String sessionId) {
        return loadMap().getOrDefault(sessionId, new ArrayList<>());
    }

    /** 删除 session 条目，返回该 session 下所有 entries（供调用方按 uuid 删 Ubuntu 目录）。 */
    public synchronized List<Entry> deleteSession(String sessionId) {
        Map<String, List<Entry>> all = loadMap();
        List<Entry> entries = all.remove(sessionId);
        saveMap(all);
        return entries != null ? entries : new ArrayList<>();
    }

    /** 按 uuid 删除单条记录。 */
    public synchronized void deleteByUuid(String sessionId, String uuid) {
        Map<String, List<Entry>> all = loadMap();
        List<Entry> entries = all.get(sessionId);
        if (entries != null) {
            entries.removeIf(e -> e.uuid.equals(uuid));
            if (entries.isEmpty()) all.remove(sessionId);
        }
        saveMap(all);
    }

    /** 清空全部记录，返回所有 entries（供清理物理文件）。 */
    public synchronized List<Entry> clearAll() {
        Map<String, List<Entry>> all = loadMap();
        List<Entry> allEntries = new ArrayList<>();
        for (List<Entry> v : all.values()) allEntries.addAll(v);
        mPrefs.edit().remove(KEY).apply();
        return allEntries;
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private Map<String, List<Entry>> loadMap() {
        Map<String, List<Entry>> result = new HashMap<>();
        String raw = mPrefs.getString(KEY, null);
        if (raw == null) return result;
        try {
            JSONObject obj = new JSONObject(raw);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String sid = keys.next();
                JSONArray arr = obj.getJSONArray(sid);
                List<Entry> entries = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    // 新格式：每条 entry 是 JSON 对象 {"uuid":..., "filename":...}
                    // 旧格式（字符串数组）一律丢弃——历史用户附件不多，迁移成本高于价值
                    Object item = arr.get(i);
                    if (item instanceof JSONObject) {
                        JSONObject o = (JSONObject) item;
                        String uuid = o.optString("uuid", "");
                        String name = o.optString("filename", "");
                        if (!uuid.isEmpty()) entries.add(new Entry(uuid, name));
                    }
                    // else: 旧字符串格式，跳过
                }
                if (!entries.isEmpty()) result.put(sid, entries);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void saveMap(Map<String, List<Entry>> map) {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, List<Entry>> kv : map.entrySet()) {
                JSONArray arr = new JSONArray();
                for (Entry e : kv.getValue()) {
                    JSONObject o = new JSONObject();
                    o.put("uuid",     e.uuid);
                    o.put("filename", e.filename);
                    arr.put(o);
                }
                obj.put(kv.getKey(), arr);
            }
            mPrefs.edit().putString(KEY, obj.toString()).apply();
        } catch (Exception ignored) {}
    }
}
