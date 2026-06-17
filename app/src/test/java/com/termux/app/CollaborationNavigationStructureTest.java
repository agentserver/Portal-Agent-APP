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

public class CollaborationNavigationStructureTest {

    @Test
    public void bottomNavHasCollaborationTabAfterHome() throws Exception {
        Document doc = parseXml("src/main/res/menu/bottom_nav_menu.xml");
        NodeList items = doc.getElementsByTagName("item");

        Assert.assertTrue("Bottom navigation should have five entries",
            items.getLength() >= 5);
        Assert.assertEquals("@+id/nav_home", ((Element) items.item(0)).getAttribute("android:id"));
        Assert.assertEquals("@+id/nav_collaboration", ((Element) items.item(1)).getAttribute("android:id"));
        Assert.assertEquals("协作", ((Element) items.item(1)).getAttribute("android:title"));
    }

    @Test
    public void collaborationDashboardExposesUnifiedRuntimeSections() throws Exception {
        Document doc = parseXml("src/main/res/layout/fragment_collaboration.xml");

        Assert.assertNotNull(findById(doc, "collaboration_workspace_card"));
        Assert.assertNotNull(findById(doc, "collaboration_runtime_card"));
        Assert.assertNotNull(findById(doc, "collaboration_agentserver_card"));
        Assert.assertNotNull(findById(doc, "collaboration_android_capabilities_card"));
        Assert.assertNotNull(findById(doc, "btn_collaboration_workspace_access"));
        Assert.assertNotNull(findById(doc, "collaboration_driver_binding_dot"));
        Assert.assertNotNull(findById(doc, "collaboration_driver_binding_status"));
        Assert.assertNotNull(findById(doc, "btn_collaboration_bind_driver"));
        Assert.assertNotNull(findById(doc, "collaboration_slave_machine"));
        Assert.assertNotNull(findById(doc, "collaboration_slave_list"));
        Assert.assertNotNull(findById(doc, "collaboration_empty_slaves"));
        Assert.assertNotNull(findById(doc, "btn_collaboration_create_slave"));
        Assert.assertNotNull(findById(doc, "btn_collaboration_refresh_slaves"));
        Assert.assertNotNull(findById(doc, "btn_collaboration_agentserver_optional"));
        Assert.assertNull(findById(doc, "collaboration_loom_card"));
        Assert.assertNull(findById(doc, "collaboration_update_area"));
        Assert.assertNotNull(findById(doc, "btn_collaboration_switch_provider"));
        Assert.assertNull(findById(doc, "btn_collaboration_switch_role"));
        Assert.assertNull(findById(doc, "btn_collaboration_start_role"));
        Assert.assertNull(findById(doc, "btn_collaboration_stop_role"));
    }

    @Test
    public void collaborationDashboardUsesManagedSlaveListLikeAgentServerApp() throws Exception {
        Document doc = parseXml("src/main/res/layout/fragment_collaboration.xml");
        String source = readSource("src/main/java/com/termux/app/CollaborationFragment.java");

        Assert.assertNotNull(findById(doc, "collaboration_slave_machine"));
        Assert.assertNotNull(findById(doc, "collaboration_slave_list"));
        Assert.assertNotNull(findById(doc, "btn_collaboration_create_slave"));
        Assert.assertNotNull(findById(doc, "btn_collaboration_refresh_slaves"));
        Assert.assertNull(findById(doc, "btn_collaboration_switch_role"));
        Assert.assertNull(findById(doc, "btn_collaboration_start_role"));
        Assert.assertNull(findById(doc, "btn_collaboration_stop_role"));
        Assert.assertTrue(source.contains("LoomSlaveRegistry"));
        Assert.assertTrue(source.contains("createManagedSlave"));
        Assert.assertTrue(source.contains("startManagedSlave"));
        Assert.assertTrue(source.contains("pauseManagedSlave"));
        Assert.assertTrue(source.contains("deleteManagedSlave"));
        Assert.assertFalse(source.contains("startSelectedRole"));
        Assert.assertFalse(source.contains("stopSelectedRole"));
    }

    @Test
    public void collaborationDashboardUsesStatusListsForDetailSettings() throws Exception {
        Document doc = parseXml("src/main/res/layout/fragment_collaboration.xml");
        Element workspaceSummary = findById(doc, "collaboration_workspace_summary");
        Element loomSummary = findById(doc, "collaboration_loom_summary");
        Element agentServerButton = findById(doc, "btn_collaboration_agentserver_optional");

        Assert.assertNotNull(workspaceSummary);
        Assert.assertNotEquals("true", workspaceSummary.getAttribute("android:clickable"));
        Assert.assertNotEquals("true", workspaceSummary.getAttribute("android:focusable"));
        Assert.assertNotNull(loomSummary);
        Assert.assertEquals("true", loomSummary.getAttribute("android:clickable"));
        Assert.assertEquals("true", loomSummary.getAttribute("android:focusable"));
        Assert.assertNull(findById(doc, "btn_collaboration_loom_settings"));
        Assert.assertNull(findById(doc, "btn_collaboration_workspace_settings"));
        Assert.assertEquals("管理工作目录",
            findById(doc, "btn_collaboration_workspace_access").getAttribute("android:text"));
        Assert.assertEquals("连接 AgentServer", agentServerButton.getAttribute("android:text"));
        Assert.assertEquals("false", agentServerButton.getAttribute("android:textAllCaps"));
        Assert.assertEquals("0", agentServerButton.getAttribute("android:letterSpacing"));
    }

    @Test
    public void collaborationWorkspaceSummaryUsesDriverPrimaryWording() throws Exception {
        String xml = readSource("src/main/res/layout/fragment_collaboration.xml");
        String source = readSource("src/main/java/com/termux/app/CollaborationFragment.java");

        Assert.assertFalse(xml.contains("旧版 AgentServer 连接页中配置/授权"));
        Assert.assertFalse(source.contains("旧版 AgentServer 连接页中配置/授权"));
        Assert.assertTrue(xml.contains("绑定 Driver 后管理本机 Slave 列表"));
        Assert.assertTrue(xml.contains("扫码绑定 Driver 到当前 Agent"));
        Assert.assertFalse(xml.contains("绑定后同步 workspace"));
        Assert.assertTrue(source.contains("工作区：绑定后自动同步"));
        Assert.assertFalse(xml.contains("工作空间：填写 workspace ID 后复用"));
        Assert.assertFalse(source.contains("工作空间：填写 workspace ID 后复用"));
    }

    @Test
    public void collaborationPutsDriverBindingAboveRuntimeAndAgentServerBelowLoom() throws Exception {
        String xml = readSource("src/main/res/layout/fragment_collaboration.xml");
        String source = readSource("src/main/java/com/termux/app/CollaborationFragment.java");

        Assert.assertTrue(xml.indexOf("btn_collaboration_bind_driver")
            < xml.indexOf("collaboration_runtime_card"));
        Assert.assertTrue(xml.indexOf("btn_collaboration_agentserver_optional")
            > xml.indexOf("collaboration_agentserver_card"));
        Assert.assertTrue(xml.contains("若不使用 Loom 编排，从这里连接 AgentServer"));
        Assert.assertTrue(source.contains("R.id.btn_collaboration_agentserver_optional"));
        Assert.assertFalse(source.contains("R.id.collaboration_workspace_summary).setOnClickListener"));
        Assert.assertFalse(source.contains("R.id.btn_collaboration_workspace_settings"));
    }

    @Test
    public void collaborationMergesRuntimeAndLoomAndRemovesNonUserUpdateCopy() throws Exception {
        String xml = readSource("src/main/res/layout/fragment_collaboration.xml");
        String source = readSource("src/main/java/com/termux/app/CollaborationFragment.java");

        Assert.assertTrue(xml.contains("本机 Slave 列表"));
        Assert.assertTrue(xml.contains("Loom 编排设置"));
        Assert.assertTrue(xml.contains("AgentServer 连接"));
        Assert.assertTrue(xml.contains("工作目录限制"));
        Assert.assertTrue(xml.contains("管理应用/文件目录权限"));
        Assert.assertTrue(source.contains("管理应用/文件目录权限"));
        Assert.assertFalse(xml.contains("更新区"));
        Assert.assertFalse(xml.contains("安装层保持分包"));
        Assert.assertFalse(source.contains("mUpdateSummary"));
        Assert.assertFalse(source.contains("安装层保持分包"));
    }

    @Test
    public void collaborationRuntimeActionButtonsAreOutlined() throws Exception {
        Document doc = parseXml("src/main/res/layout/fragment_collaboration.xml");
        Element bindDriverButton = findById(doc, "btn_collaboration_bind_driver");
        Element switchProviderButton = findById(doc, "btn_collaboration_switch_provider");
        Element createSlaveButton = findById(doc, "btn_collaboration_create_slave");
        Element refreshSlavesButton = findById(doc, "btn_collaboration_refresh_slaves");
        Element optionalAgentServerButton = findById(doc, "btn_collaboration_agentserver_optional");

        Assert.assertNotNull(bindDriverButton);
        Assert.assertNotNull(switchProviderButton);
        Assert.assertNotNull(createSlaveButton);
        Assert.assertNotNull(refreshSlavesButton);
        Assert.assertNotNull(optionalAgentServerButton);
        Assert.assertTrue(bindDriverButton.getAttribute("style").contains("OutlinedButton"));
        Assert.assertTrue(switchProviderButton.getAttribute("style").contains("OutlinedButton"));
        Assert.assertTrue(createSlaveButton.getAttribute("style").contains("OutlinedButton"));
        Assert.assertTrue(refreshSlavesButton.getAttribute("style").contains("OutlinedButton"));
        Assert.assertTrue(optionalAgentServerButton.getAttribute("style").contains("OutlinedButton"));
        Assert.assertEquals("切换 Agent", switchProviderButton.getAttribute("android:text"));
        Assert.assertEquals("创建并启动 Slave", createSlaveButton.getAttribute("android:text"));
        Assert.assertEquals("刷新", refreshSlavesButton.getAttribute("android:text"));
        Assert.assertEquals("连接 AgentServer", optionalAgentServerButton.getAttribute("android:text"));
        Assert.assertEquals("false", bindDriverButton.getAttribute("android:textAllCaps"));
        Assert.assertEquals("false", switchProviderButton.getAttribute("android:textAllCaps"));
        Assert.assertEquals("false", createSlaveButton.getAttribute("android:textAllCaps"));
        Assert.assertEquals("false", refreshSlavesButton.getAttribute("android:textAllCaps"));
        Assert.assertEquals("false", optionalAgentServerButton.getAttribute("android:textAllCaps"));
        Assert.assertEquals("0", bindDriverButton.getAttribute("android:letterSpacing"));
        Assert.assertEquals("0", switchProviderButton.getAttribute("android:letterSpacing"));
        Assert.assertEquals("0", createSlaveButton.getAttribute("android:letterSpacing"));
        Assert.assertEquals("0", refreshSlavesButton.getAttribute("android:letterSpacing"));
        Assert.assertEquals("0", optionalAgentServerButton.getAttribute("android:letterSpacing"));
        Assert.assertEquals("112dp", switchProviderButton.getAttribute("android:layout_width"));
        Assert.assertEquals("82dp", refreshSlavesButton.getAttribute("android:layout_width"));
        Assert.assertEquals("match_parent", createSlaveButton.getAttribute("android:layout_width"));
        Assert.assertEquals("match_parent", bindDriverButton.getAttribute("android:layout_width"));
        Assert.assertEquals("@color/app_primary_border",
            bindDriverButton.getAttribute("app:strokeColor"));
        Assert.assertEquals("@color/app_primary_border",
            switchProviderButton.getAttribute("app:strokeColor"));
        Assert.assertEquals("@color/app_accent",
            createSlaveButton.getAttribute("app:strokeColor"));
        Assert.assertEquals("@color/app_primary_border",
            refreshSlavesButton.getAttribute("app:strokeColor"));
    }

    @Test
    public void collaborationProviderRowSharesRuntimeActionColumn() throws Exception {
        Document doc = parseXml("src/main/res/layout/fragment_collaboration.xml");
        Element providerRow = findById(doc, "collaboration_provider_card");

        Assert.assertNotNull(providerRow);
        Assert.assertEquals("wrap_content", providerRow.getAttribute("android:layout_height"));
        Assert.assertEquals("", providerRow.getAttribute("android:background"));
        Assert.assertEquals("", providerRow.getAttribute("android:paddingStart"));
        Assert.assertEquals("", providerRow.getAttribute("android:paddingEnd"));
    }

    @Test
    public void collaborationRuntimeOnlyExplicitControlsAreClickable() throws Exception {
        Document doc = parseXml("src/main/res/layout/fragment_collaboration.xml");
        Element runtimeCard = findById(doc, "collaboration_runtime_card");
        Element providerRow = findById(doc, "collaboration_provider_card");
        Element localAgentStatus = findById(doc, "collaboration_local_agent_status");
        Element slaveMachine = findById(doc, "collaboration_slave_machine");
        Element emptySlaves = findById(doc, "collaboration_empty_slaves");
        Element slaveList = findById(doc, "collaboration_slave_list");
        String source = readSource("src/main/java/com/termux/app/CollaborationFragment.java");

        Assert.assertNotEquals("true", runtimeCard.getAttribute("android:clickable"));
        Assert.assertNotEquals("true", providerRow.getAttribute("android:clickable"));
        Assert.assertNotEquals("true", localAgentStatus.getAttribute("android:clickable"));
        Assert.assertNotEquals("true", slaveMachine.getAttribute("android:clickable"));
        Assert.assertNotEquals("true", emptySlaves.getAttribute("android:clickable"));
        Assert.assertNotEquals("true", slaveList.getAttribute("android:clickable"));
        Assert.assertFalse(source.contains("R.id.collaboration_runtime_card).setOnClickListener"));
        Assert.assertFalse(source.contains("R.id.collaboration_provider_card)\n            .setOnClickListener"));
        Assert.assertTrue(source.contains("R.id.btn_collaboration_switch_provider"));
        Assert.assertTrue(source.contains("R.id.btn_collaboration_create_slave"));
        Assert.assertTrue(source.contains("R.id.btn_collaboration_refresh_slaves"));
    }

    @Test
    public void collaborationRuntimeHidesInternalDefaultSlaveName() throws Exception {
        String xml = readSource("src/main/res/layout/fragment_collaboration.xml");
        String source = readSource("src/main/java/com/termux/app/CollaborationFragment.java");

        Assert.assertFalse(xml.contains("slave-phone"));
        Assert.assertFalse(xml.contains("All-in-one"));
        Assert.assertFalse(source.contains("slave-phone"));
        Assert.assertFalse(source.contains("All-in-one"));
        Assert.assertTrue(xml.contains("本机 Slave 列表"));
        Assert.assertTrue(xml.contains("尚未创建 Slave"));
        Assert.assertTrue(source.contains("本机 Slave · 新建默认使用"));
        Assert.assertTrue(source.contains("\"Slave：\" + slave.displayName"));
    }

    @Test
    public void collaborationFragmentSwitchesProviderAndMarksDriverBindingStale() throws Exception {
        String source = readSource("src/main/java/com/termux/app/CollaborationFragment.java");

        Assert.assertTrue(source.contains("showProviderDialog()"));
        Assert.assertTrue(source.contains("switchProviderAndMarkDriverStale"));
        Assert.assertTrue(source.contains("markDriverBindingStale"));
        Assert.assertTrue(source.contains("updateDriverBindingDot"));
        Assert.assertFalse(source.contains("switchProviderAndBind"));
        Assert.assertTrue(source.contains("LoomCommandBuilder.bindDriverIfNeededScript"));
    }

    @Test
    public void collaborationFragmentRemovesRoleSwitchAndUsesManagedSlaveCreation() throws Exception {
        Document doc = parseXml("src/main/res/layout/fragment_collaboration.xml");
        Element runtimeCard = findById(doc, "collaboration_runtime_card");
        Element createSlaveButton = findById(doc, "btn_collaboration_create_slave");
        String source = readSource("src/main/java/com/termux/app/CollaborationFragment.java");

        Assert.assertNotEquals("true", runtimeCard.getAttribute("android:clickable"));
        Assert.assertNotNull(createSlaveButton);
        Assert.assertEquals("创建并启动 Slave", createSlaveButton.getAttribute("android:text"));
        Assert.assertFalse(source.contains("showRoleDialog()"));
        Assert.assertFalse(source.contains("btn_collaboration_switch_role"));
        Assert.assertFalse(source.contains("switchRoleOnly"));
        Assert.assertFalse(source.contains("switchRoleAndBind"));
        Assert.assertTrue(source.contains("createManagedSlave"));
        Assert.assertTrue(source.contains("LoomSlaveRegistry"));
    }

    @Test
    public void collaborationNoLongerOffersFixedRoleSwitchChoices() throws Exception {
        String source = readSource("src/main/java/com/termux/app/CollaborationFragment.java")
            .replace("\r\n", "\n");

        Assert.assertFalse(source.contains("private static final String[] ROLE_LABELS"));
        Assert.assertFalse(source.contains("private static final String[] ROLE_VALUES"));
        Assert.assertFalse(source.contains("\"All-in-one\", \"Observer\", \"Driver\", \"Slave\""));
        Assert.assertFalse(source.contains("\"all\", \"observer\", \"driver\", \"slave\""));
    }

    @Test
    public void collaborationFragmentStartsPausesAndDeletesManagedSlaves() throws Exception {
        String source = readSource("src/main/java/com/termux/app/CollaborationFragment.java");

        Assert.assertTrue(source.contains("startManagedSlave"));
        Assert.assertTrue(source.contains("pauseManagedSlave"));
        Assert.assertTrue(source.contains("deleteManagedSlave"));
        Assert.assertTrue(source.contains("__LOOM_SLAVE_ERROR__="));
        Assert.assertTrue(source.contains("LoomCommandBuilder.startManagedSlaveRuntimeScript"));
        Assert.assertTrue(source.contains("LoomCommandBuilder.stopManagedSlaveScript"));
        Assert.assertTrue(source.contains("registry.delete(slave.id)"));
        Assert.assertFalse(source.contains("R.id.btn_collaboration_start_role"));
        Assert.assertFalse(source.contains("R.id.btn_collaboration_stop_role"));
        Assert.assertFalse(source.contains("startSelectedRole"));
        Assert.assertFalse(source.contains("stopSelectedRole"));
        Assert.assertFalse(source.contains("rememberRuntimeProvider"));
        Assert.assertFalse(source.contains("LoomCommandBuilder.startAllInOneScript"));
    }

    @Test
    public void collaborationDriverBindingAllowsServerUrlBeforeWorkspaceKnown() throws Exception {
        String source = readSource("src/main/java/com/termux/app/CollaborationFragment.java");

        Assert.assertTrue(source.contains("CollaborationConnectionState.canBindDriver(serverUrl, workspaceId)"));
        Assert.assertTrue(source.contains("请先配置协作服务器地址"));
        Assert.assertTrue(source.contains("saveDriverBindingSuccess(profile.provider, identity)"));
        Assert.assertTrue(source.contains("LoomDriverConfigIdentity.parse"));
        Assert.assertFalse(source.contains("请先连接 AgentServer 工作空间。"));
    }

    @Test
    public void collaborationDriverBindingReusesExistingRegistrationAndDismissesAuthDialog() throws Exception {
        String source = readSource("src/main/java/com/termux/app/CollaborationFragment.java");

        Assert.assertTrue(source.contains("LoomCommandBuilder.setupConfigScript(settings)"));
        Assert.assertTrue(source.contains("LoomCommandBuilder.bindDriverIfNeededScript(settings)"));
        Assert.assertTrue(source.contains("dismissAuthDialog()"));
        Assert.assertFalse(source.contains("LoomCommandBuilder.setupConfigScript(settings, true)"));
    }

    @Test
    public void collaborationDismissesAuthDialogAfterSlaveLeavesAuthState() throws Exception {
        String source = readSource("src/main/java/com/termux/app/CollaborationFragment.java");

        Assert.assertTrue(source.contains("dismissAuthDialogIfNoAuthRequired(slaves)"));
        Assert.assertTrue(source.contains("LoomSlaveStatus.AUTH_REQUIRED.equals(slave.status)"));
        Assert.assertTrue(source.contains("dismissAuthDialog();"));
    }

    @Test
    public void settingsHubNoLongerOwnsAgentserverAndLoomEntries() throws Exception {
        Document doc = parseXml("src/main/res/layout/fragment_settings_hub.xml");

        Assert.assertNull(findById(doc, "settings_item_agentserver"));
        Assert.assertNull(findById(doc, "settings_item_loom"));
    }

    @Test
    public void collaborationFragmentRoutesToExistingDetailPages() throws Exception {
        String source = readSource("src/main/java/com/termux/app/CollaborationFragment.java");

        Assert.assertTrue(source.contains("showAgentServerMode()"));
        Assert.assertTrue(source.contains("showLoomMode()"));
        Assert.assertTrue(source.contains("showWorkspaceAccessSettingsMode()"));
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
