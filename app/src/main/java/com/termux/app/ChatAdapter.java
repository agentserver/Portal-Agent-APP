package com.termux.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import io.noties.markwon.Markwon;

import java.util.List;

/** RecyclerView Adapter，渲染用户消息（右侧蓝色气泡）和 Claude 回复（左侧灰色气泡）。 */
public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_USER         = 0;
    private static final int TYPE_ASSISTANT    = 1;
    private static final int TYPE_SYSTEM       = 2;
    private static final int TYPE_TOOL_USE     = 3;
    private static final int TYPE_TOOL_RESULT  = 4;

    private final List<ChatMessage> mMessages;
    private Markwon mMarkwon;
    private String mAssistantLabel = "Claude";

    public ChatAdapter(List<ChatMessage> messages) {
        this.mMessages = messages;
    }

    // -------------------------------------------------------------------------
    // Adapter 标准方法
    // -------------------------------------------------------------------------

    @Override
    public int getItemViewType(int position) {
        switch (mMessages.get(position).type) {
            case USER:         return TYPE_USER;
            case SYSTEM:       return TYPE_SYSTEM;
            case TOOL_USE:     return TYPE_TOOL_USE;
            case TOOL_RESULT:  return TYPE_TOOL_RESULT;
            default:           return TYPE_ASSISTANT;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_USER) {
            return new UserViewHolder(inflater.inflate(R.layout.item_msg_user, parent, false));
        } else if (viewType == TYPE_SYSTEM) {
            return new SystemViewHolder(inflater.inflate(R.layout.item_msg_system, parent, false));
        } else if (viewType == TYPE_TOOL_USE) {
            return new ToolViewHolder(inflater.inflate(R.layout.item_msg_tool_use, parent, false));
        } else if (viewType == TYPE_TOOL_RESULT) {
            return new ToolViewHolder(inflater.inflate(R.layout.item_msg_tool_result, parent, false));
        } else {
            return new AssistantViewHolder(inflater.inflate(R.layout.item_msg_assistant, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage msg = mMessages.get(position);
        if (holder instanceof UserViewHolder) {
            ((UserViewHolder) holder).bind(msg.content);
        } else if (holder instanceof SystemViewHolder) {
            ((SystemViewHolder) holder).bind(msg.content, markwon(holder.itemView.getContext()));
        } else if (holder instanceof ToolViewHolder) {
            ((ToolViewHolder) holder).bind(msg, position, ChatAdapter.this);
        } else {
            ((AssistantViewHolder) holder).bind(msg, mAssistantLabel, markwon(holder.itemView.getContext()));
        }
    }

    @Override
    public int getItemCount() {
        return mMessages.size();
    }

    // -------------------------------------------------------------------------
    // 辅助方法（HomeFragment 调用）
    // -------------------------------------------------------------------------

    /** 在列表末尾添加新消息并通知刷新。 */
    public void addMessage(ChatMessage msg) {
        int index = ChatTurnOrdering.isToolMessage(msg)
                ? ChatTurnOrdering.findToolInsertIndex(mMessages)
                : mMessages.size();
        mMessages.add(index, msg);
        notifyItemInserted(index);
    }

    public void setAssistantLabel(String label) {
        String next = (label == null || label.trim().isEmpty()) ? "Claude" : label.trim();
        if (java.util.Objects.equals(mAssistantLabel, next)) return;
        mAssistantLabel = next;
        notifyDataSetChanged();
    }

    /**
     * 更新最后一条 ASSISTANT 消息的正文和思考内容（流式调用）。
     * 如果不存在 ASSISTANT 消息则自动创建。
     */
    public void updateLastAssistant(String content, String thinking) {
        if (thinking != null && !thinking.isEmpty()) {
            updateLastAssistantThinking(thinking);
        }
        if (content != null && !content.isEmpty()) {
            updateLastAssistantText(content);
        }
    }

    /** 兼容旧调用（无思考内容）。 */
    public void updateLastAssistant(String content) {
        updateLastAssistant(content, null);
    }

    /** 仅更新最后一条 ASSISTANT 的正文（thinking 字段保留不动）。 */
    public void updateLastAssistantText(String text) {
        int outputIndex = ChatTurnOrdering.findOutputIndex(mMessages);
        if (outputIndex >= 0) {
            ChatMessage msg = mMessages.get(outputIndex);
            if (!java.util.Objects.equals(msg.content, text)) {
                msg.content = text;
                notifyItemChanged(outputIndex);
            }
            return;
        }
        int index = ChatTurnOrdering.findOutputInsertIndex(mMessages);
        mMessages.add(index, ChatMessage.assistant(text));
        notifyItemInserted(index);
    }

    /** 仅更新最后一条 ASSISTANT 的 thinking 字段。 */
    public void updateLastAssistantThinking(String thinking) {
        int thinkingIndex = ChatTurnOrdering.findThinkingIndex(mMessages);
        if (thinkingIndex >= 0) {
            ChatMessage msg = mMessages.get(thinkingIndex);
            if (!java.util.Objects.equals(msg.thinking, thinking)) {
                msg.thinking = thinking;
                notifyItemChanged(thinkingIndex);
            }
            return;
        }
        ChatMessage m = ChatMessage.assistant("");
        m.thinking = thinking;
        int index = ChatTurnOrdering.findThinkingInsertIndex(mMessages);
        mMessages.add(index, m);
        notifyItemInserted(index);
    }

    /** 折叠当前 turn 内所有 TOOL_USE / TOOL_RESULT 气泡的详情区。 */
    public void collapseAllToolDetailsInLastTurn() {
        // 从末尾向前，直到遇到 USER 消息为止
        for (int i = mMessages.size() - 1; i >= 0; i--) {
            ChatMessage msg = mMessages.get(i);
            if (msg.type == ChatMessage.Type.USER) break;
            if ((msg.type == ChatMessage.Type.TOOL_USE
                    || msg.type == ChatMessage.Type.TOOL_RESULT)
                    && !msg.toolDetailCollapsed) {
                msg.toolDetailCollapsed = true;
                notifyItemChanged(i);
            }
        }
    }

    /** 回复完成后，将最后一条 ASSISTANT 消息的思考内容折叠。 */
    public void collapseLastAssistantThinking() {
        for (int i = mMessages.size() - 1; i >= 0; i--) {
            ChatMessage msg = mMessages.get(i);
            if (msg.type == ChatMessage.Type.ASSISTANT && msg.thinking != null
                    && !msg.thinkingCollapsed) {
                msg.thinkingCollapsed = true;
                notifyItemChanged(i);
                return;
            }
        }
    }

    /** 获取最后一条 ASSISTANT 消息，不存在返回 null。 */
    @Nullable
    public ChatMessage getLastAssistantMessage() {
        for (int i = mMessages.size() - 1; i >= 0; i--) {
            if (mMessages.get(i).type == ChatMessage.Type.ASSISTANT) {
                return mMessages.get(i);
            }
        }
        return null;
    }

    private Markwon markwon(Context context) {
        if (mMarkwon == null) {
            mMarkwon = Markwon.builder(context.getApplicationContext()).build();
        }
        return mMarkwon;
    }

    // -------------------------------------------------------------------------
    // ViewHolder
    // -------------------------------------------------------------------------

    static class UserViewHolder extends RecyclerView.ViewHolder {
        private final TextView mText;

        UserViewHolder(View itemView) {
            super(itemView);
            mText = itemView.findViewById(R.id.msg_text);
            itemView.setOnLongClickListener(v -> { copyToClipboard(v, mText.getText().toString()); return true; });
        }

        void bind(String content) {
            mText.setText(content);
        }
    }

    static class AssistantViewHolder extends RecyclerView.ViewHolder {
        private final TextView mText;
        private final TextView mLabel;
        private String         mRawText = "";
        private final View     mThinkingContainer;
        private final TextView mThinkingHeader;
        private final TextView mThinkingText;

        AssistantViewHolder(View itemView) {
            super(itemView);
            mText              = itemView.findViewById(R.id.msg_text);
            mLabel             = itemView.findViewById(R.id.msg_sender_label);
            mThinkingContainer = itemView.findViewById(R.id.thinking_container);
            mThinkingHeader    = itemView.findViewById(R.id.thinking_header);
            mThinkingText      = itemView.findViewById(R.id.thinking_text);
            itemView.setOnLongClickListener(v -> { copyToClipboard(v, mRawText); return true; });
        }

        void bind(ChatMessage msg, String assistantLabel, Markwon markwon) {
            mLabel.setText(assistantLabel == null || assistantLabel.isEmpty() ? "Claude" : assistantLabel);
            mRawText = msg.content == null ? "" : msg.content;
            if (mRawText.trim().isEmpty()) {
                mText.setVisibility(View.GONE);
            } else {
                mText.setVisibility(View.VISIBLE);
                markwon.setMarkdown(mText, mRawText);
            }
            String thinking = msg.thinking;
            if (thinking == null || thinking.isEmpty()) {
                mThinkingContainer.setVisibility(View.GONE);
                return;
            }
            mThinkingContainer.setVisibility(View.VISIBLE);
            mThinkingText.setText(thinking);
            if (msg.thinkingCollapsed) {
                mThinkingText.setVisibility(View.GONE);
                mThinkingHeader.setText("💭 思考过程 ▶");
            } else {
                mThinkingText.setVisibility(View.VISIBLE);
                mThinkingHeader.setText("💭 思考中… ▼");
            }
            mThinkingContainer.setOnClickListener(v -> {
                if (mThinkingText.getVisibility() == View.VISIBLE) {
                    mThinkingText.setVisibility(View.GONE);
                    mThinkingHeader.setText("💭 思考过程 ▶");
                } else {
                    mThinkingText.setVisibility(View.VISIBLE);
                    mThinkingHeader.setText(msg.thinkingCollapsed ? "💭 思考过程 ▼" : "💭 思考中… ▼");
                }
            });
        }
    }

    static class SystemViewHolder extends RecyclerView.ViewHolder {
        private final TextView mText;
        private String         mRawText = "";

        SystemViewHolder(View itemView) {
            super(itemView);
            mText = itemView.findViewById(R.id.msg_text);
            itemView.setOnLongClickListener(v -> { copyToClipboard(v, mRawText); return true; });
        }

        void bind(String content, Markwon markwon) {
            mRawText = content == null ? "" : content;
            markwon.setMarkdown(mText, mRawText);
        }
    }

    static class ToolViewHolder extends RecyclerView.ViewHolder {
        private final TextView mHeader;
        private final TextView mDetail;
        private final View     mContainer;

        ToolViewHolder(View itemView) {
            super(itemView);
            mContainer = itemView.findViewById(R.id.tool_container);
            mHeader    = itemView.findViewById(R.id.tool_header);
            mDetail    = itemView.findViewById(R.id.tool_detail);
            itemView.setOnLongClickListener(v -> {
                CharSequence detail = (mDetail.getVisibility() == View.VISIBLE)
                        ? mDetail.getText() : null;
                String full = (detail == null || detail.length() == 0)
                        ? mHeader.getText().toString()
                        : mHeader.getText() + "\n" + detail;
                copyToClipboard(v, full);
                return true;
            });
        }

        void bind(ChatMessage msg, int position, ChatAdapter adapter) {
            String triangle = msg.toolDetailCollapsed ? " ▶" : " ▼";
            mHeader.setText(msg.content + triangle);
            if (msg.toolDetail == null || msg.toolDetail.isEmpty()) {
                mDetail.setVisibility(View.GONE);
                mContainer.setOnClickListener(null);  // 无内容则不可点
                return;
            }
            mDetail.setText(msg.toolDetail);
            mDetail.setVisibility(msg.toolDetailCollapsed ? View.GONE : View.VISIBLE);
            mContainer.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                msg.toolDetailCollapsed = !msg.toolDetailCollapsed;
                adapter.notifyItemChanged(pos);
            });
        }
    }

    private static void copyToClipboard(View v, String text) {
        ClipboardManager cm = (ClipboardManager) v.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("message", text));
            Toast.makeText(v.getContext(), "已复制", Toast.LENGTH_SHORT).show();
        }
    }
}
