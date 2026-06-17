package com.termux.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

public class AgentTaskDetailFragment extends Fragment {

    private static final String ARG_TASK_ID = "task_id";
    private static final String ARG_PROVIDER = "provider";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private AgentTaskStore     mStore;
    private String             mTaskId;
    private AssistantProvider  mProvider = AssistantProvider.CLAUDE;
    private final List<ChatMessage> mMessages = new ArrayList<>();
    private ChatAdapter        mAdapter;
    private RecyclerView       mRecycler;
    private TextView           mTitle;
    private TextView           mStatus;
    private Runnable           mPoller;

    public static AgentTaskDetailFragment newInstance(String taskId) {
        return newInstance(AssistantProvider.CLAUDE, taskId);
    }

    public static AgentTaskDetailFragment newInstance(AssistantProvider provider, String taskId) {
        AgentTaskDetailFragment f = new AgentTaskDetailFragment();
        Bundle b = new Bundle();
        b.putString(ARG_TASK_ID, taskId);
        b.putString(ARG_PROVIDER, (provider == null ? AssistantProvider.CLAUDE : provider).id);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_agent_task_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mTaskId = getArguments() != null ? getArguments().getString(ARG_TASK_ID) : null;
        String providerId = getArguments() != null ? getArguments().getString(ARG_PROVIDER) : null;
        mProvider = providerId == null ? AssistantProvider.CLAUDE : AssistantProvider.fromId(providerId);
        mStore  = new AgentTaskStore(requireContext(), mProvider);

        mTitle    = view.findViewById(R.id.agent_detail_title);
        mStatus   = view.findViewById(R.id.agent_detail_status);
        mRecycler = view.findViewById(R.id.agent_detail_recycler);

        mAdapter = new ChatAdapter(mMessages);
        mRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecycler.setAdapter(mAdapter);

        view.findViewById(R.id.agent_detail_back).setOnClickListener(v -> {
            if (getActivity() instanceof TermuxActivity) {
                ((TermuxActivity) getActivity()).showHomeMode();
            }
        });

        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        AgentTask t = mStore.get(mTaskId);
        if (t != null && t.status == AgentTask.Status.RUNNING) startPolling();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopPolling();
    }

    private void refresh() {
        AgentTask t = mStore.get(mTaskId);
        if (t == null) {
            mTitle.setText("（任务已删除）");
            mStatus.setVisibility(View.GONE);
            mMessages.clear();
            mAdapter.notifyDataSetChanged();
            stopPolling();
            return;
        }
        String preview = t.previewLine();
        mTitle.setText(preview.isEmpty() ? "AgentServer 任务" : preview);
        if (t.status == AgentTask.Status.RUNNING) {
            mStatus.setText("● 运行中");
            mStatus.setTextColor(0xFFF59E0B);
            mStatus.setBackgroundResource(R.drawable.bg_status_chip);
            mStatus.setVisibility(View.VISIBLE);
        } else {
            mStatus.setText("● 已完成");
            mStatus.setTextColor(0xFF16A34A);
            mStatus.setBackgroundResource(R.drawable.bg_status_chip);
            mStatus.setVisibility(View.VISIBLE);
            stopPolling();
        }
        // 整体重建 messages 列表（每秒钟一次，性能足够）
        mMessages.clear();
        // user prompt 作为第一条 USER 气泡
        if (t.prompt != null && !t.prompt.isEmpty()) {
            mMessages.add(ChatMessage.user(t.prompt));
        }
        mMessages.addAll(t.messages);
        mAdapter.notifyDataSetChanged();
        mRecycler.post(() -> {
            int last = mAdapter.getItemCount() - 1;
            if (last >= 0) mRecycler.scrollToPosition(last);
        });
    }

    private void startPolling() {
        if (mPoller != null) return;
        mPoller = new Runnable() {
            @Override public void run() {
                refresh();
                if (mPoller != null) mHandler.postDelayed(this, 1000);
            }
        };
        mHandler.postDelayed(mPoller, 1000);
    }

    private void stopPolling() {
        if (mPoller != null) {
            mHandler.removeCallbacks(mPoller);
            mPoller = null;
        }
    }
}
