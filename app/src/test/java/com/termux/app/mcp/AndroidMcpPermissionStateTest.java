package com.termux.app.mcp;

import org.junit.Assert;
import org.junit.Test;

public class AndroidMcpPermissionStateTest {

    @Test
    public void detectsAccessibilityServiceFromSecureSettingList() {
        Assert.assertTrue(AndroidMcpPermissionState.containsAccessibilityService(
            "com.other/.Service:com.termux/com.termux.app.mcp.McpAccessibilityService",
            "com.termux", "com.termux.app.mcp.McpAccessibilityService"));
    }

    @Test
    public void detectsShortAccessibilityServiceComponent() {
        Assert.assertTrue(AndroidMcpPermissionState.containsAccessibilityService(
            "com.termux/.app.mcp.McpAccessibilityService",
            "com.termux", "com.termux.app.mcp.McpAccessibilityService"));
    }

    @Test
    public void rejectsMissingAccessibilityService() {
        Assert.assertFalse(AndroidMcpPermissionState.containsAccessibilityService(
            "com.other/.Service", "com.termux", "com.termux.app.mcp.McpAccessibilityService"));
    }
}
