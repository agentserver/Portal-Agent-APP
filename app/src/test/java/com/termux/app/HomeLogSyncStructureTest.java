package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class HomeLogSyncStructureTest {

    @Test
    public void homeNavigationDoesNotReplayFlatChatLogIntoBubbles() throws Exception {
        String activity = readSource("src/main/java/com/termux/app/TermuxActivity.java");
        String home = readSource("src/main/java/com/termux/app/HomeFragment.java");

        Assert.assertFalse(activity.contains("syncChatLogToHome();"));
        Assert.assertFalse(home.contains("public void syncFromLog"));
        Assert.assertTrue(home.contains("appendChatLog(\"Codex\""));
        Assert.assertTrue(home.contains("appendChatLog(\"Claude\""));
    }

    private static String readSource(String relativePath) throws Exception {
        File file = new File(relativePath);
        if (!file.isFile()) file = new File("app/" + relativePath);
        Assert.assertTrue("Missing source file: " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
