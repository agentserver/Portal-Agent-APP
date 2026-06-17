package com.termux.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.app.activities.SettingsActivity;

/**
 * 设置导航页：作为底部 Tab "设置" 的入口，提供自动化与应用设置跳转。
 */
public class SettingsHubFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_hub, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.settings_back_button).setOnClickListener(v -> {
            TermuxActivity a = act();
            if (a != null) a.navigateBackToHomeFromSettings();
        });

        view.findViewById(R.id.settings_item_automation).setOnClickListener(v -> {
            TermuxActivity a = act();
            if (a != null) a.showAutomationSettingsMode();
        });

        view.findViewById(R.id.settings_item_workspace_access).setOnClickListener(v -> {
            TermuxActivity a = act();
            if (a != null) a.showWorkspaceAccessSettingsMode();
        });

        view.findViewById(R.id.settings_item_app_settings).setOnClickListener(v -> {
            startActivity(new Intent(requireContext(), SettingsActivity.class));
        });
    }

    @Nullable
    private TermuxActivity act() {
        return (getActivity() instanceof TermuxActivity)
            ? (TermuxActivity) getActivity() : null;
    }
}
