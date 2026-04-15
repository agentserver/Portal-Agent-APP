package com.termux.app;

import android.app.AlertDialog;
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

        mStore    = new ApiKeyStore(requireContext());
        mEntries  = mStore.loadAll();
        mCountText = view.findViewById(R.id.key_count_text);

        // RecyclerView
        RecyclerView recycler = view.findViewById(R.id.key_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        mAdapter = new ApiKeyAdapter(mEntries, mStore.getActiveId(), this);
        recycler.setAdapter(mAdapter);

        updateCount();

        // 添加按钮
        MaterialButton btnAdd = view.findViewById(R.id.btn_add_key);
        btnAdd.setOnClickListener(v -> showAddDialog());
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
            a.setActiveApiKey(entry.value, entry.baseUrl);
            Toast.makeText(getContext(), "已设为当前 Key", Toast.LENGTH_SHORT).show();
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
        etBaseUrl.setHint("API Base URL（留空 = 官方；机构用户填入网关地址）");
        etBaseUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etBaseUrl.setSingleLine(true);
        // 默认填机构网关，用户可清空换成官方
        etBaseUrl.setText("https://code.ai.cs.ac.cn");
        layout.addView(etBaseUrl);

        new AlertDialog.Builder(requireContext())
            .setTitle("添加 API Key")
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

    private void updateCount() {
        int n = mEntries.size();
        mCountText.setText(n + " 个");
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
