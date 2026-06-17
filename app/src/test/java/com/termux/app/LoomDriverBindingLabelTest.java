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

public class LoomDriverBindingLabelTest {

    @Test
    public void loomPageNoLongerDuplicatesDriverBindingAction() throws Exception {
        Document loomDoc = parseLayout("fragment_loom.xml");
        Document collaborationDoc = parseLayout("fragment_collaboration.xml");

        Assert.assertNull(findById(loomDoc, "btn_loom_register_driver"));
        Element bindButton = findById(collaborationDoc, "btn_collaboration_bind_driver");
        Assert.assertNotNull(bindButton);
        Assert.assertEquals("扫码绑定 Driver", bindButton.getAttribute("android:text"));
    }

    @Test
    public void loomPageKeepsOnlyConfigurationActions() throws Exception {
        Document loomDoc = parseLayout("fragment_loom.xml");
        Document collaborationDoc = parseLayout("fragment_collaboration.xml");

        Assert.assertNotNull(findById(loomDoc, "btn_loom_save"));
        Assert.assertNotNull(findById(loomDoc, "btn_loom_status"));
        Assert.assertNull(findById(loomDoc, "btn_loom_setup"));
        Assert.assertNull(findById(loomDoc, "btn_loom_stop_current"));
        Assert.assertNull(findById(loomDoc, "btn_loom_start_observer"));
        Assert.assertNull(findById(loomDoc, "btn_loom_stop_observer"));
        Assert.assertNull(findById(loomDoc, "btn_loom_start_slave"));
        Assert.assertNull(findById(loomDoc, "btn_loom_stop_slave"));
        Assert.assertNotNull(findById(collaborationDoc, "btn_collaboration_create_slave"));
        Assert.assertNotNull(findById(collaborationDoc, "btn_collaboration_refresh_slaves"));
        Assert.assertNotNull(findById(collaborationDoc, "collaboration_slave_list"));
        Assert.assertEquals("创建并启动 Slave",
            findById(collaborationDoc, "btn_collaboration_create_slave").getAttribute("android:text"));
        Assert.assertNull(findById(collaborationDoc, "btn_collaboration_start_role"));
        Assert.assertNull(findById(collaborationDoc, "btn_collaboration_stop_role"));
    }

    @Test
    public void loomPageRoleSelectorOnlyKeepsObserverAndSlave() throws Exception {
        String source = readSource("src/main/java/com/termux/app/LoomFragment.java")
            .replace("\r\n", "\n");

        Assert.assertTrue(source.contains("private static final String[] ROLE_LABELS = {\n"
            + "        \"Observer\", \"Slave\"\n"
            + "    };"));
        Assert.assertTrue(source.contains("private static final String[] ROLE_VALUES = {\n"
            + "        \"observer\", \"slave\"\n"
            + "    };"));
        Assert.assertFalse(source.contains("\"All-in-one\", \"Observer\", \"Driver\", \"Slave\""));
        Assert.assertFalse(source.contains("\"all\", \"observer\", \"driver\", \"slave\""));
        Assert.assertTrue(source.contains("normalizeRole"));
    }

    private static Document parseLayout(String fileName) throws Exception {
        File file = new File("src/main/res/layout/" + fileName);
        if (!file.isFile()) {
            file = new File("app/src/main/res/layout/" + fileName);
        }
        Assert.assertTrue("Missing layout file: " + file.getAbsolutePath(), file.isFile());
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(file);
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

    private static String readSource(String relativePath) throws Exception {
        File file = new File(relativePath);
        if (!file.isFile()) {
            file = new File("app/" + relativePath);
        }
        Assert.assertTrue("Missing source file: " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
