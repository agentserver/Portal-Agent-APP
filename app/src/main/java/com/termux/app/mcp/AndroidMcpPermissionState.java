package com.termux.app.mcp;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

import androidx.annotation.Nullable;

public final class AndroidMcpPermissionState {

    private AndroidMcpPermissionState() {}

    public static boolean isAccessibilityServiceEnabled(@Nullable Context context) {
        if (context == null) return false;
        String enabled = Settings.Secure.getString(context.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        ComponentName target = new ComponentName(context, McpAccessibilityService.class);
        return containsAccessibilityService(enabled, target);
    }

    public static boolean isAccessibilityReady(@Nullable Context context) {
        return McpAccessibilityService.isRunning() || isAccessibilityServiceEnabled(context);
    }

    public static boolean isScreenCaptureRunning() {
        return ScreenCaptureService.isRunning();
    }

    static boolean containsAccessibilityService(@Nullable String enabledServices, ComponentName target) {
        if (target == null) return false;
        return containsAccessibilityService(enabledServices,
            target.getPackageName(), target.getClassName());
    }

    static boolean containsAccessibilityService(
            @Nullable String enabledServices,
            String targetPackage,
            String targetClass) {
        if (enabledServices == null || enabledServices.trim().isEmpty()
                || targetPackage == null || targetPackage.isEmpty()
                || targetClass == null || targetClass.isEmpty()) {
            return false;
        }
        String[] parts = enabledServices.split(":");
        for (String part : parts) {
            if (part == null) continue;
            String clean = part.trim();
            if (clean.isEmpty()) continue;
            String[] component = clean.split("/", 2);
            if (component.length != 2) continue;
            String pkg = component[0];
            String cls = component[1];
            if (cls.startsWith(".")) cls = pkg + cls;
            if (targetPackage.equals(pkg) && targetClass.equals(cls)) {
                return true;
            }
        }
        return false;
    }
}
