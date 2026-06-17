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

public class ApiKeyPageStructureTest {

    @Test
    public void configPanelAppearsBeforeKeyListWithoutTokenUsagePanel() throws Exception {
        Document doc = parseXml("src/main/res/layout/fragment_api_key.xml");
        String xml = readSource("src/main/res/layout/fragment_api_key.xml");
        String source = readSource("src/main/java/com/termux/app/ApiKeyFragment.java");

        Assert.assertNotNull(findById(doc, "provider_config_panel"));
        Assert.assertTrue(xml.indexOf("@+id/provider_config_panel") < xml.indexOf("@+id/key_recycler"));
        Assert.assertNull(findById(doc, "key_usage_panel"));
        Assert.assertNull(findById(doc, "key_usage_summary"));
        Assert.assertFalse(xml.contains("Token 用量"));
        Assert.assertFalse(xml.contains("tokens"));
        Assert.assertFalse(xml.contains("服务端额度"));
        Assert.assertFalse(source.contains("服务端额度"));
        Assert.assertFalse(source.contains("updateUsageStats()"));
        Assert.assertFalse(source.contains("mUsageSummary"));
        Assert.assertFalse(source.contains("TokenUsageStore"));
    }

    @Test
    public void keyButtonsUseOutlinedDashboardStyle() throws Exception {
        String fragmentXml = readSource("src/main/res/layout/fragment_api_key.xml");
        String itemXml = readSource("src/main/res/layout/item_api_key.xml");

        Assert.assertTrue(fragmentXml.contains("btn_add_key"));
        Assert.assertTrue(fragmentXml.contains("Widget.MaterialComponents.Button.OutlinedButton"));
        Assert.assertTrue(fragmentXml.contains("app:strokeColor=\"@color/app_accent\""));
        Assert.assertTrue(fragmentXml.contains("app:backgroundTint=\"@color/app_card_bg\""));
        Assert.assertTrue(itemXml.contains("btn_delete_key"));
        Assert.assertTrue(itemXml.contains("app:strokeColor=\"@color/app_warning\""));
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
}
