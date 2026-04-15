package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * API Key 持久化存储（SharedPreferences）。
 * 每条记录含：唯一 ID、别名、Key 值。
 * 同时记录当前激活的 Key ID。
 */
public class ApiKeyStore {

    private static final String PREFS_NAME     = "api_keys_store";
    private static final String K_COUNT        = "count";
    private static final String K_ID           = "id_";
    private static final String K_ALIAS        = "alias_";
    private static final String K_VALUE        = "value_";
    private static final String K_BASE_URL     = "base_url_";
    private static final String K_ACTIVE_ID    = "active_id";

    // -------------------------------------------------------------------------

    public static class Entry {
        public final String id;
        public String alias;
        public String value;
        public String baseUrl;  // ANTHROPIC_BASE_URL，空字符串表示使用官方默认

        Entry(String id, String alias, String value, String baseUrl) {
            this.id      = id;
            this.alias   = alias;
            this.value   = value;
            this.baseUrl = baseUrl == null ? "" : baseUrl;
        }
    }

    // -------------------------------------------------------------------------

    private final SharedPreferences mPrefs;

    public ApiKeyStore(Context context) {
        mPrefs = context.getApplicationContext()
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 读取所有条目（顺序与保存顺序一致）。 */
    public List<Entry> loadAll() {
        int count = mPrefs.getInt(K_COUNT, 0);
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id      = mPrefs.getString(K_ID      + i, null);
            String alias   = mPrefs.getString(K_ALIAS   + i, "");
            String value   = mPrefs.getString(K_VALUE   + i, "");
            String baseUrl = mPrefs.getString(K_BASE_URL + i, "");
            if (id != null) list.add(new Entry(id, alias, value, baseUrl));
        }
        return list;
    }

    /** 将条目列表整体写回（覆盖原有）。 */
    public void saveAll(List<Entry> entries) {
        SharedPreferences.Editor ed = mPrefs.edit();
        ed.putInt(K_COUNT, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            ed.putString(K_ID      + i, e.id);
            ed.putString(K_ALIAS   + i, e.alias);
            ed.putString(K_VALUE   + i, e.value);
            ed.putString(K_BASE_URL + i, e.baseUrl);
        }
        ed.apply();
    }

    /** 添加一条新 Key，返回新建的 Entry。 */
    public Entry add(String alias, String value, String baseUrl) {
        List<Entry> list = loadAll();
        Entry e = new Entry(UUID.randomUUID().toString(), alias.trim(), value.trim(), baseUrl.trim());
        list.add(e);
        saveAll(list);
        return e;
    }

    /** 删除指定 ID 的 Key；若被删除的是激活 Key，同时清除激活记录。 */
    public void remove(String id) {
        List<Entry> list = loadAll();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
        saveAll(list);
        if (id.equals(getActiveId())) {
            mPrefs.edit().remove(K_ACTIVE_ID).apply();
        }
    }

    /** 获取当前激活的 Key ID（可能为 null）。 */
    public String getActiveId() {
        return mPrefs.getString(K_ACTIVE_ID, null);
    }

    /** 设置激活的 Key ID。 */
    public void setActiveId(String id) {
        mPrefs.edit().putString(K_ACTIVE_ID, id).apply();
    }
}
