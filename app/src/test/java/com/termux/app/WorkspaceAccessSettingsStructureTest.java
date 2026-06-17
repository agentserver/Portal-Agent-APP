package com.termux.app;

import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.xml.parsers.DocumentBuilderFactory;

public class WorkspaceAccessSettingsStructureTest {

    @Test
    public void settingsHubLinksToWorkspaceAccessSettings() throws Exception {
        Document doc = parseXml("src/main/res/layout/fragment_settings_hub.xml");
        String source = readSource("src/main/java/com/termux/app/SettingsHubFragment.java");
        String activity = readSource("src/main/java/com/termux/app/TermuxActivity.java");

        Assert.assertNotNull(findById(doc, "settings_item_workspace_access"));
        Assert.assertTrue(readSource("src/main/res/layout/fragment_settings_hub.xml")
            .contains("工作目录限制"));
        Assert.assertTrue(source.contains("R.id.settings_item_workspace_access"));
        Assert.assertTrue(source.contains("showWorkspaceAccessSettingsMode()"));
        Assert.assertTrue(activity.contains("WorkspaceAccessSettingsFragment"));
        Assert.assertTrue(activity.contains("\"workspace_access_settings\""));
    }

    @Test
    public void workspaceAccessPageExplainsDirectoryAndAppBoundaries() throws Exception {
        Document doc = parseXml("src/main/res/layout/fragment_workspace_access_settings.xml");
        String xml = readSource("src/main/res/layout/fragment_workspace_access_settings.xml");
        String source = readSource("src/main/java/com/termux/app/WorkspaceAccessSettingsFragment.java");

        Assert.assertNotNull(findById(doc, "workspace_access_back_button"));
        Assert.assertNotNull(findById(doc, "workspace_access_android_defaults"));
        Assert.assertNotNull(findById(doc, "workspace_access_ubuntu_scope"));
        Assert.assertNotNull(findById(doc, "workspace_access_app_list"));
        Assert.assertNotNull(findById(doc, "workspace_access_allowed_count"));
        Assert.assertNotNull(findById(doc, "workspace_access_app_header"));
        Assert.assertNotNull(findById(doc, "workspace_access_app_content"));
        Assert.assertNotNull(findById(doc, "workspace_access_app_search"));
        Assert.assertTrue(xml.contains("Android 默认目录"));
        Assert.assertTrue(xml.contains("Ubuntu 当前用户目录"));
        Assert.assertTrue(xml.contains("允许 Agent 操作的应用"));
        Assert.assertEquals("gone",
            findById(doc, "workspace_access_app_content").getAttribute("android:visibility"));
        Assert.assertTrue(xml.contains("搜索应用或包名"));
        Assert.assertTrue(source.contains("WorkspaceAccessSettingsStore"));
        Assert.assertTrue(source.contains("PackageManager"));
        Assert.assertTrue(source.contains("SearchView"));
        Assert.assertTrue(source.contains("CheckBox"));
        Assert.assertTrue(source.contains("loadIcon"));
        Assert.assertTrue(source.contains("setSearchViewTextColors"));
        Assert.assertTrue(source.contains("search_src_text"));
        Assert.assertTrue(source.contains("setOnQueryTextListener"));
        Assert.assertTrue(source.contains("toggleAppList"));
        Assert.assertTrue(source.contains("filterApps"));
    }

    @Test
    public void bottomNavigationAlignsWithMainContentMargin() throws Exception {
        Document doc = parseXml("src/main/res/layout/activity_termux.xml");

        Element mainContent = findById(doc, "activity_termux_root_relative_layout");
        Element bottomNav = findById(doc, "bottom_nav");

        Assert.assertNotNull(mainContent);
        Assert.assertNotNull(bottomNav);
        Assert.assertEquals(mainContent.getAttribute("android:layout_marginHorizontal"),
            bottomNav.getAttribute("android:layout_marginHorizontal"));
    }

    @Test
    public void workspaceAccessModeDoesNotHideReusedFragment() throws Exception {
        String activity = readSource("src/main/java/com/termux/app/TermuxActivity.java");
        String method = extractMethod(activity, "showWorkspaceAccessSettingsMode");

        Assert.assertTrue(method.contains("ft.show(workspaceF)"));
        Assert.assertFalse(method.contains("if (workspaceF != null) ft.hide(workspaceF);"));
    }

    @Test
    public void workspaceAccessStoreKeepsDefaultDirectoriesAndAllowedAppsSeparate() throws Exception {
        String source = readSource("src/main/java/com/termux/app/WorkspaceAccessSettingsStore.java");

        Assert.assertTrue(source.contains("DEFAULT_ANDROID_DIRS"));
        Assert.assertTrue(source.contains("/storage/emulated/0/Download"));
        Assert.assertTrue(source.contains("/storage/emulated/0/Documents"));
        Assert.assertTrue(source.contains("/storage/emulated/0/DCIM"));
        Assert.assertTrue(source.contains("allowed_apps"));
        Assert.assertTrue(source.contains("isAppAllowed"));
        Assert.assertTrue(source.contains("setAppAllowed"));
        Assert.assertFalse(source.contains("/home/claude/**,/home/codex/**"));
    }

    private static Document parseXml(String relativePath) throws Exception {
        File file = resolveProjectFile(relativePath);
        Assert.assertTrue("Missing XML file: " + file.getAbsolutePath(), file.isFile());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(file);
    }

    private static String readSource(String relativePath) throws Exception {
        File file = resolveProjectFile(relativePath);
        Assert.assertTrue("Missing source file: " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File resolveProjectFile(String relativePath) {
        File file = new File(relativePath);
        if (!file.isFile()) {
            file = new File("app/" + relativePath);
        }
        return file;
    }

    private static Element findById(Document doc, String id) {
        NodeList all = doc.getElementsByTagName("*");
        String suffix = "/" + id;
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (element.getAttribute("android:id").endsWith(suffix)) {
                return element;
            }
        }
        return null;
    }

    private static String extractMethod(String source, String methodName) {
        String marker = "public void " + methodName + "()";
        int start = source.indexOf(marker);
        Assert.assertTrue("Missing method: " + methodName, start >= 0);
        int brace = source.indexOf('{', start);
        Assert.assertTrue("Missing method body: " + methodName, brace >= 0);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') depth++;
            if (ch == '}') {
                depth--;
                if (depth == 0) return source.substring(start, i + 1);
            }
        }
        Assert.fail("Unclosed method body: " + methodName);
        return "";
    }
}
