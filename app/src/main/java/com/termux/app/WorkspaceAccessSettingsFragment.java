package com.termux.app;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SearchView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class WorkspaceAccessSettingsFragment extends Fragment {

    private WorkspaceAccessSettingsStore store;
    private TextView androidDefaults;
    private TextView ubuntuScope;
    private TextView allowedCount;
    private View appContent;
    private ImageView appChevron;
    private SearchView appSearch;
    private LinearLayout appList;
    private final List<AppEntry> allApps = new ArrayList<>();
    private boolean appListExpanded;
    private boolean appsLoaded;
    private String appQuery = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_workspace_access_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        store = new WorkspaceAccessSettingsStore(requireContext());
        androidDefaults = view.findViewById(R.id.workspace_access_android_defaults);
        ubuntuScope = view.findViewById(R.id.workspace_access_ubuntu_scope);
        allowedCount = view.findViewById(R.id.workspace_access_allowed_count);
        appContent = view.findViewById(R.id.workspace_access_app_content);
        appChevron = view.findViewById(R.id.workspace_access_app_chevron);
        appSearch = view.findViewById(R.id.workspace_access_app_search);
        appList = view.findViewById(R.id.workspace_access_app_list);

        view.findViewById(R.id.workspace_access_back_button).setOnClickListener(v -> {
            TermuxActivity a = act();
            if (a != null) a.navigateBackToSettingsHub();
        });
        view.findViewById(R.id.workspace_access_app_header).setOnClickListener(v -> toggleAppList());
        if (appSearch != null) {
            setSearchViewTextColors(appSearch);
            appSearch.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    filterApps(query);
                    if (appSearch != null) appSearch.clearFocus();
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    filterApps(newText);
                    return true;
                }
            });
        }

        renderStaticScopes();
        setAppListExpanded(false);
        updateAllowedCount();
    }

    private void renderStaticScopes() {
        if (androidDefaults != null) {
            StringBuilder out = new StringBuilder();
            for (String dir : WorkspaceAccessSettingsStore.DEFAULT_ANDROID_DIRS) {
                if (out.length() > 0) out.append('\n');
                out.append(dir);
            }
            androidDefaults.setText(out.toString());
        }
        if (ubuntuScope != null) {
            AssistantProvider provider = new ProviderSettingsStore(requireContext()).getSelectedProvider();
            ProviderProfile profile = ProviderProfile.forProvider(provider);
            ubuntuScope.setText(profile.displayName + "：" +
                WorkspaceAccessSettingsStore.ubuntuUserScope(provider));
        }
    }

    private void toggleAppList() {
        setAppListExpanded(!appListExpanded);
    }

    private void setAppListExpanded(boolean expanded) {
        appListExpanded = expanded;
        if (appContent != null) {
            appContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (appChevron != null) {
            appChevron.setRotation(expanded ? 90f : 0f);
        }
        if (expanded) {
            ensureAppsLoaded();
            renderApps();
        }
    }

    private void ensureAppsLoaded() {
        if (appsLoaded) return;
        allApps.clear();
        allApps.addAll(loadLaunchableApps());
        appsLoaded = true;
    }

    private void filterApps(String query) {
        appQuery = query == null ? "" : query.trim();
        if (appListExpanded) {
            renderApps();
        }
    }

    private void renderApps() {
        if (appList == null) return;
        appList.removeAllViews();
        List<AppEntry> apps = filteredApps();
        if (apps.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(appQuery.isEmpty() ? "未读取到可操作应用" : "未找到匹配应用");
            empty.setTextColor(getResources().getColor(R.color.app_text_muted));
            empty.setTextSize(13);
            empty.setPadding(0, dp(4), 0, dp(4));
            appList.addView(empty);
            return;
        }
        for (AppEntry app : apps) {
            appList.addView(appRow(app));
        }
    }

    private List<AppEntry> filteredApps() {
        String query = appQuery == null ? "" : appQuery.trim().toLowerCase();
        if (query.isEmpty()) return new ArrayList<>(allApps);
        List<AppEntry> filtered = new ArrayList<>();
        for (AppEntry app : allApps) {
            String label = app.label.toLowerCase();
            String packageName = app.packageName.toLowerCase();
            if (label.contains(query) || packageName.contains(query)) {
                filtered.add(app);
            }
        }
        return filtered;
    }

    private View appRow(AppEntry app) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.setBackgroundResource(android.R.drawable.list_selector_background);

        ImageView icon = new ImageView(requireContext());
        icon.setImageDrawable(app.icon);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        iconParams.setMargins(0, 0, dp(12), 0);
        row.addView(icon, iconParams);

        LinearLayout textColumn = new LinearLayout(requireContext());
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(requireContext());
        label.setText(app.label);
        label.setTextColor(getResources().getColor(R.color.app_text_primary));
        label.setTextSize(13);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        TextView packageName = new TextView(requireContext());
        packageName.setText(app.packageName);
        packageName.setTextColor(getResources().getColor(R.color.app_text_muted));
        packageName.setTextSize(11);
        textColumn.addView(label);
        textColumn.addView(packageName);
        row.addView(textColumn, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        CheckBox box = new CheckBox(requireContext());
        box.setChecked(store.isAppAllowed(app.packageName));
        box.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            store.setAppAllowed(app.packageName, isChecked);
            updateAllowedCount();
        });
        row.setOnClickListener(v -> box.setChecked(!box.isChecked()));
        row.addView(box, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private List<AppEntry> loadLaunchableApps() {
        PackageManager pm = requireContext().getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        List<AppEntry> entries = new ArrayList<>();
        for (ApplicationInfo info : installed) {
            if (info == null || info.packageName == null) continue;
            if (pm.getLaunchIntentForPackage(info.packageName) == null) continue;
            CharSequence label = info.loadLabel(pm);
            entries.add(new AppEntry(
                label == null ? info.packageName : label.toString(),
                info.packageName,
                info.loadIcon(pm)));
        }
        Collections.sort(entries, Comparator
            .comparing((AppEntry e) -> e.label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(e -> e.packageName));
        return entries;
    }

    private void setSearchViewTextColors(SearchView searchView) {
        int textId = getResources().getIdentifier("search_src_text", "id", "android");
        TextView searchText = textId == 0 ? null : searchView.findViewById(textId);
        if (searchText != null) {
            searchText.setTextColor(getResources().getColor(R.color.app_text_primary));
            searchText.setHintTextColor(getResources().getColor(R.color.app_text_hint));
        }
        int plateId = getResources().getIdentifier("search_plate", "id", "android");
        View plate = plateId == 0 ? null : searchView.findViewById(plateId);
        if (plate != null) {
            plate.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private void updateAllowedCount() {
        if (allowedCount != null && store != null) {
            allowedCount.setText(store.allowedAppCount() + " 个");
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Nullable
    private TermuxActivity act() {
        return getActivity() instanceof TermuxActivity ? (TermuxActivity) getActivity() : null;
    }

    private static final class AppEntry {
        final String label;
        final String packageName;
        final Drawable icon;

        AppEntry(String label, String packageName, Drawable icon) {
            this.label = label == null ? "" : label;
            this.packageName = packageName == null ? "" : packageName;
            this.icon = icon;
        }
    }
}
