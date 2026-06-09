package com.termux.app;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ChatAdapterProviderLabelTest {

    @Test
    public void assistantBubbleUsesConfiguredProviderLabel() {
        Context context = RuntimeEnvironment.getApplication();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistant("answer"));
        ChatAdapter adapter = new ChatAdapter(messages);
        adapter.setAssistantLabel("Codex");

        ViewGroup parent = new FrameLayout(context);
        RecyclerView.ViewHolder holder =
            adapter.onCreateViewHolder(parent, adapter.getItemViewType(0));
        adapter.onBindViewHolder(holder, 0);

        TextView label = holder.itemView.findViewById(R.id.msg_sender_label);
        Assert.assertEquals("Codex", label.getText().toString());
    }
}
