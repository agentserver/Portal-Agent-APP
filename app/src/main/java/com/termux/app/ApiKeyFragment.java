package com.termux.app;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.termux.R;

import java.util.List;

/**
 * API Key 管理页面。
 *
 * - 显示已保存的 Claude API Key 列表（别名 + 脱敏值）
 * - 支持添加（别名 + Key 值）、删除、设为当前激活
 * - "设为当前"会将 Key 写入 Ubuntu ~/.profile 并在当前终端 session 中 export
 */
public class ApiKeyFragment extends Fragment implements ApiKeyAdapter.Listener {

    private ApiKeyStore            mStore;
    private List<ApiKeyStore.Entry> mEntries;
    private ApiKeyAdapter          mAdapter;
    private TextView               mCountText;
    private AssistantProvider      mProvider = AssistantProvider.CODEX;
    private MaterialButton         mBtnClaude;
    private MaterialButton         mBtnCodex;
    private TextView               mConfigTitle;
    private TextView               mConfigPath;
    private EditText               mModelEdit;

    // =========================================================================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_api_key, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mProvider = new ProviderSettingsStore(requireContext()).getSelectedProvider();
        mBtnClaude = view.findViewById(R.id.btn_key_provider_claude);
        mBtnCodex = view.findViewById(R.id.btn_key_provider_codex);
        mBtnClaude.setOnClickListener(v -> switchProvider(AssistantProvider.CLAUDE));
        mBtnCodex.setOnClickListener(v -> switchProvider(AssistantProvider.CODEX));
        mConfigTitle = view.findViewById(R.id.provider_config_title);
        mConfigPath = view.findViewById(R.id.provider_config_path);
        mModelEdit = view.findViewById(R.id.provider_model_edit);

        mStore    = new ApiKeyStore(requireContext(), mProvider);
        mEntries  = mStore.loadAll();
        mCountText = view.findViewById(R.id.key_count_text);

        // RecyclerView
        RecyclerView recycler = view.findViewById(R.id.key_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        mAdapter = new ApiKeyAdapter(mEntries, mStore.getActiveId(), this);
        recycler.setAdapter(mAdapter);

        updateCount();
        updateProviderButtons();

        // 添加按钮
        MaterialButton btnAdd = view.findViewById(R.id.btn_add_key);
        styleOutlinedButton(btnAdd, R.color.app_accent);
        btnAdd.setOnClickListener(v -> showAddDialog());
        MaterialButton apply = view.findViewById(R.id.btn_apply_provider_config);
        MaterialButton raw = view.findViewById(R.id.btn_edit_raw_provider_config);
        styleOutlinedButton(apply, R.color.app_accent);
        styleOutlinedButton(raw, R.color.app_primary);
        apply.setOnClickListener(v -> applyProviderConfig());
        raw.setOnClickListener(v -> showRawConfigDialog());
        updateConfigPanel();
    }

    // =========================================================================
    // ApiKeyAdapter.Listener
    // =========================================================================

    @Override
    public void onSetActive(ApiKeyStore.Entry entry) {
        mStore.setActiveId(entry.id);
        mAdapter.setActiveId(entry.id);

        // 直接用 Java 文件 I/O 写入 ubuntu ~/.bashrc，不发任何终端命令
        TermuxActivity a = act();
        if (a != null) {
            a.setActiveApiKey(mProvider, entry.value, entry.baseUrl);
            writeProviderConfig(entry, false);
            String displayName = ProviderProfile.forProvider(mProvider).displayName;
            Toast.makeText(getContext(), "已设为当前 " + displayName + " Key", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "已记录", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDelete(ApiKeyStore.Entry entry) {
        new AlertDialog.Builder(requireContext())
            .setTitle("删除确认")
            .setMessage("确认删除「" + (entry.alias.isEmpty() ? "该 Key" : entry.alias) + "」？")
            .setPositiveButton("删除", (d, w) -> {
                mStore.remove(entry.id);
                int idx = indexOf(entry.id);
                if (idx >= 0) {
                    mEntries.remove(idx);
                    mAdapter.notifyItemRemoved(idx);
                }
                updateCount();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // =========================================================================
    // 添加对话框
    // =========================================================================

    private void showAddDialog() {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int dp16 = dp(16);
        layout.setPadding(dp16, dp(8), dp16, 0);

        EditText etAlias = new EditText(requireContext());
        etAlias.setHint("别名 / 备注（可选）");
        etAlias.setInputType(InputType.TYPE_CLASS_TEXT);
        etAlias.setSingleLine(true);
        layout.addView(etAlias);

        EditText etKey = new EditText(requireContext());
        etKey.setHint("API Key");
        etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etKey.setSingleLine(true);
        layout.addView(etKey);

        EditText etBaseUrl = new EditText(requireContext());
        if (mProvider == AssistantProvider.CODEX) {
            etBaseUrl.setHint("API Base URL（可选；当前 Codex CLI 默认使用 OpenAI 官方接口）");
        } else {
            etBaseUrl.setHint("API Base URL（留空 = 官方 Anthropic；第三方填 Anthropic 兼容地址）");
        }
        etBaseUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etBaseUrl.setSingleLine(true);
        layout.addView(etBaseUrl);

        String displayName = ProviderProfile.forProvider(mProvider).displayName;
        new AlertDialog.Builder(requireContext())
            .setTitle("添加 " + displayName + " API Key")
            .setView(layout)
            .setPositiveButton("保存", (d, w) -> {
                String alias   = etAlias.getText().toString().trim();
                String key     = etKey.getText().toString().trim();
                String baseUrl = etBaseUrl.getText().toString().trim();
                if (key.isEmpty()) {
                    Toast.makeText(getContext(), "Key 不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                ApiKeyStore.Entry entry = mStore.add(alias, key, baseUrl);
                mEntries.add(entry);
                mAdapter.notifyItemInserted(mEntries.size() - 1);
                updateCount();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    private void switchProvider(AssistantProvider provider) {
        if (mProvider == provider) return;

        mProvider = provider;
        mStore = new ApiKeyStore(requireContext(), mProvider);
        mEntries.clear();
        mEntries.addAll(mStore.loadAll());
        mAdapter.setActiveId(mStore.getActiveId());
        updateCount();
        updateProviderButtons();
        updateConfigPanel();
    }

    private void updateProviderButtons() {
        updateProviderButton(mBtnClaude, mProvider == AssistantProvider.CLAUDE);
        updateProviderButton(mBtnCodex, mProvider == AssistantProvider.CODEX);
    }

    private void updateConfigPanel() {
        if (mConfigTitle == null || mConfigPath == null || mModelEdit == null) return;
        ProviderProfile profile = ProviderProfile.forProvider(mProvider);
        mConfigTitle.setText(profile.displayName + " CLI 配置");
        mConfigPath.setText(mProvider == AssistantProvider.CODEX
            ? "/home/codex/.codex/config.toml"
            : "/home/claude/.claude/settings.json");
        mModelEdit.setHint(mProvider == AssistantProvider.CODEX
            ? "Model（例如 gpt-5-codex，可选）"
            : "Model（例如 claude-sonnet-4，可选）");
    }

    private void applyProviderConfig() {
        ApiKeyStore.Entry entry = activeEntry();
        if (entry == null) {
            Toast.makeText(getContext(), "请先激活一个 Key", Toast.LENGTH_SHORT).show();
            return;
        }
        writeProviderConfig(entry, true);
    }

    private void writeProviderConfig(ApiKeyStore.Entry entry, boolean showToast) {
        if (entry == null || getContext() == null) return;
        ProviderConfigManager.ProviderConfig config =
            new ProviderConfigManager.ProviderConfig(
                entry.value,
                entry.baseUrl,
                mModelEdit == null ? "" : mModelEdit.getText().toString().trim());
        try {
            if (mProvider == AssistantProvider.CODEX) {
                ProviderConfigManager.writeCodexConfig(requireContext(), config);
            } else {
                ProviderConfigManager.writeClaudeSettings(requireContext(), config);
            }
            if (showToast) Toast.makeText(getContext(), "配置已写入 CLI 文件", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), "配置写入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showRawConfigDialog() {
        if (getContext() == null) return;
        EditText editor = new EditText(requireContext());
        editor.setMinLines(12);
        editor.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setSingleLine(false);
        int pad = dp(12);
        editor.setPadding(pad, pad, pad, pad);
        try {
            editor.setText(ProviderConfigManager.readRaw(requireContext(), mProvider));
        } catch (Exception e) {
            editor.setText("");
        }
        new AlertDialog.Builder(requireContext())
            .setTitle(mProvider == AssistantProvider.CODEX
                ? "编辑 config.toml" : "编辑 settings.json")
            .setView(editor)
            .setPositiveButton("保存", (d, w) -> {
                try {
                    ProviderConfigManager.writeRaw(requireContext(), mProvider,
                        editor.getText().toString());
                    Toast.makeText(getContext(), "已保存，原文件已备份", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(getContext(), "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private ApiKeyStore.Entry activeEntry() {
        String activeId = mStore == null ? null : mStore.getActiveId();
        if (activeId == null) return null;
        for (ApiKeyStore.Entry entry : mEntries) {
            if (activeId.equals(entry.id)) return entry;
        }
        return null;
    }

    private void updateProviderButton(MaterialButton button, boolean selected) {
        int blue = ContextCompat.getColor(requireContext(), R.color.app_primary);
        int white = ContextCompat.getColor(requireContext(), R.color.app_card_bg);
        int background = selected ? blue : white;
        int text = selected ? white : blue;

        button.setBackgroundTintList(ColorStateList.valueOf(background));
        button.setStrokeColor(ColorStateList.valueOf(blue));
        button.setStrokeWidth(dp(1));
        button.setTextColor(text);
        button.setAllCaps(false);
    }

    private void updateCount() {
        int n = mEntries.size();
        mCountText.setText(n + " 个");
    }

    private void styleOutlinedButton(MaterialButton button, int colorRes) {
        if (button == null || getContext() == null) return;
        int color = ContextCompat.getColor(requireContext(), colorRes);
        int white = ContextCompat.getColor(requireContext(), R.color.app_card_bg);
        button.setBackgroundTintList(ColorStateList.valueOf(white));
        button.setStrokeColor(ColorStateList.valueOf(color));
        button.setStrokeWidth(dp(1));
        button.setTextColor(color);
        button.setAllCaps(false);
    }

    private int indexOf(String id) {
        for (int i = 0; i < mEntries.size(); i++) {
            if (mEntries.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    @Nullable
    private TermuxActivity act() {
        return (getActivity() instanceof TermuxActivity)
            ? (TermuxActivity) getActivity() : null;
    }
}
