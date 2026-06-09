package com.termux.app;

import android.content.Context;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
public class ProviderEnvironmentWriterTest {

    @Test
    public void claudeExportsAnthropicKeyAndBaseUrl() {
        String updated = ProviderEnvironmentWriter.rewriteBashrc(
            "export ANTHROPIC_API_KEY='old'\nexport ANTHROPIC_BASE_URL='old'\nexport PATH=\"$HOME/.local/bin:$PATH\"\n",
            AssistantProvider.CLAUDE,
            "sk-new",
            "https://api.example.com");

        Assert.assertFalse(updated.contains("old"));
        Assert.assertTrue(updated.contains("export ANTHROPIC_API_KEY='sk-new'"));
        Assert.assertTrue(updated.contains("export ANTHROPIC_BASE_URL='https://api.example.com'"));
        Assert.assertTrue(updated.contains("export PATH=\"$HOME/.local/bin:$PATH\""));
    }

    @Test
    public void codexExportsOpenAiKeyAndRemovesOldOpenAiLine() {
        String updated = ProviderEnvironmentWriter.rewriteBashrc(
            "export OPENAI_API_KEY='old'\nexport ANTHROPIC_API_KEY='keep'\n",
            AssistantProvider.CODEX,
            "sk-openai",
            "");

        Assert.assertFalse(updated.contains("OPENAI_API_KEY='old'"));
        Assert.assertTrue(updated.contains("export OPENAI_API_KEY='sk-openai'"));
        Assert.assertTrue(updated.contains("export ANTHROPIC_API_KEY='keep'"));
        Assert.assertFalse(updated.contains("OPENAI_BASE_URL"));
    }

    @Test
    public void codexRemovesStaleTopLevelOpenAiBaseUrl() {
        String updated = ProviderEnvironmentWriter.rewriteBashrc(
            "export OPENAI_API_KEY='old'\n"
                + "export OPENAI_BASE_URL='https://stale.example.com'\n"
                + "export PATH=\"$HOME/.local/bin:$PATH\"\n",
            AssistantProvider.CODEX,
            "sk-openai",
            "");

        Assert.assertFalse(updated.contains("OPENAI_BASE_URL"));
        Assert.assertFalse(updated.contains("https://stale.example.com"));
        Assert.assertTrue(updated.contains("export OPENAI_API_KEY='sk-openai'"));
        Assert.assertTrue(updated.contains("export PATH=\"$HOME/.local/bin:$PATH\""));
    }

    @Test
    public void codexExportsOpenAiBaseUrlWhenProvided() {
        String updated = ProviderEnvironmentWriter.rewriteBashrc(
            "export OPENAI_API_KEY='old'\nexport OPENAI_BASE_URL='old'\n",
            AssistantProvider.CODEX,
            "sk-openai",
            "https://api.example.com/v1");

        Assert.assertTrue(updated.contains("export OPENAI_API_KEY='sk-openai'"));
        Assert.assertTrue(updated.contains("export OPENAI_BASE_URL='https://api.example.com/v1'"));
        Assert.assertFalse(updated.contains("OPENAI_BASE_URL='old'"));
    }

    @Test
    public void rewritePreservesCommentsAndShellLogicMentioningKeyNames() {
        String existing = "# keep OPENAI_API_KEY note\n"
            + "if [ -z \"$OPENAI_API_KEY\" ]; then\n"
            + "  echo missing\n"
            + "fi\n"
            + "export OPENAI_API_KEY='old'\n";

        String updated = ProviderEnvironmentWriter.rewriteBashrc(
            existing,
            AssistantProvider.CODEX,
            "sk-new",
            "");

        Assert.assertTrue(updated.contains("# keep OPENAI_API_KEY note"));
        Assert.assertTrue(updated.contains("if [ -z \"$OPENAI_API_KEY\" ]; then"));
        Assert.assertTrue(updated.contains("  echo missing"));
        Assert.assertTrue(updated.contains("fi"));
        Assert.assertFalse(updated.contains("OPENAI_API_KEY='old'"));
        Assert.assertTrue(updated.contains("export OPENAI_API_KEY='sk-new'"));
    }

    @Test
    public void rewritePreservesIndentedOpenAiExportInsideShellControlFlow() {
        String existing = "if [ -z \"$OPENAI_API_KEY\" ]; then\n"
            + "  export OPENAI_API_KEY='fallback'\n"
            + "fi\n"
            + "export OPENAI_API_KEY='old'\n";

        String updated = ProviderEnvironmentWriter.rewriteBashrc(
            existing,
            AssistantProvider.CODEX,
            "sk-new",
            "");

        Assert.assertTrue(updated.contains("if [ -z \"$OPENAI_API_KEY\" ]; then"));
        Assert.assertTrue(updated.contains("  export OPENAI_API_KEY='fallback'"));
        Assert.assertTrue(updated.contains("fi"));
        Assert.assertFalse(updated.contains("OPENAI_API_KEY='old'"));
        Assert.assertTrue(updated.contains("export OPENAI_API_KEY='sk-new'"));
    }

    @Test
    public void rewriteEscapesSingleQuotesInKeyAndBaseUrl() {
        String updated = ProviderEnvironmentWriter.rewriteBashrc(
            "",
            AssistantProvider.CLAUDE,
            "sk-'abc",
            "https://api.example.com/a'b");

        Assert.assertTrue(updated.contains("export ANTHROPIC_API_KEY='sk-'\\''abc'"));
        Assert.assertTrue(updated.contains("export ANTHROPIC_BASE_URL='https://api.example.com/a'\\''b'"));
    }

    @Test
    public void writeActiveKeyTargetsProviderUserBashrc() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File filesDir = context.getFilesDir();
        File ubuntu = new File(filesDir, "usr/var/lib/proot-distro/installed-rootfs/ubuntu");
        File claudeHome = new File(ubuntu, "home/claude");
        File codexHome = new File(ubuntu, "home/codex");
        claudeHome.mkdirs();
        codexHome.mkdirs();
        File claudeBashrc = new File(claudeHome, ".bashrc");
        File codexBashrc = new File(codexHome, ".bashrc");
        Files.write(claudeBashrc.toPath(), "# claude\n".getBytes(StandardCharsets.UTF_8));
        Files.write(codexBashrc.toPath(), "# codex\n".getBytes(StandardCharsets.UTF_8));

        ProviderEnvironmentWriter.writeActiveKey(context, AssistantProvider.CODEX, "sk-openai", "");

        String claude = new String(Files.readAllBytes(claudeBashrc.toPath()), StandardCharsets.UTF_8);
        String codex = new String(Files.readAllBytes(codexBashrc.toPath()), StandardCharsets.UTF_8);
        Assert.assertEquals("# claude\n", claude);
        Assert.assertTrue(codex.contains("# codex"));
        Assert.assertTrue(codex.contains("export OPENAI_API_KEY='sk-openai'"));
    }
}
