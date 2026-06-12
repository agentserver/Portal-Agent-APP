package com.termux.app.mcp.tools;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class AppToolTest {

    @Test
    public void openMissingPackageThrowsToolError() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        AppTool tool = new AppTool(AppTool.Kind.OPEN);

        try {
            tool.call(new JSONObject().put("package_name", "com.invalid.missing"), context);
            Assert.fail("Missing package should throw");
        } catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("App not found or not launchable"));
        }
    }
}
