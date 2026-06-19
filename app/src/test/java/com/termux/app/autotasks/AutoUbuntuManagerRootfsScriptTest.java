package com.termux.app.autotasks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.shared.termux.TermuxConstants;

import org.junit.Test;

public class AutoUbuntuManagerRootfsScriptTest {

    @Test
    public void rootfsGuardRequiresUbuntuSystemFiles() {
        String script = AutoUbuntuManager.buildRootfsGuardShellForTest();

        assertTrue(script.contains("ubuntu_rootfs_ok()"));
        assertTrue(script.contains("etc/passwd"));
        assertTrue(script.contains("etc/os-release"));
        assertTrue(script.contains("usr/bin/env"));
        assertTrue(script.contains("Incomplete Ubuntu rootfs detected"));
        assertFalse(script.contains("[ ! -d \"$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\" ]"));
    }

    @Test
    public void prootDistroPatchExportsCpuArchIndependently() {
        String script = AutoUbuntuManager.buildProotDistroPatchShellForTest();

        assertTrue(script.contains("sed -i 's/set -euo/set -eo/g'"));
        assertTrue(script.contains("sed -i 's/set -uo/set -o/g'"));
        assertTrue(script.contains("export cpu_arch=aarch64"));
        assertTrue(script.contains("echo \"[*] cpu_arch=$cpu_arch\""));
    }

    @Test
    public void bundledTermuxToolsInstallUsesPortalAgentPrefix() {
        String script = AutoUbuntuManager.buildBundledTermuxToolsInstallShellForTest();
        String oldPrefix = "/data/data/com.termux/files/usr";
        String compatPrefix = TermuxConstants.TERMUX_BOOTSTRAP_COMPAT_PREFIX_DIR_PATH;

        assertTrue(script.contains("export PREFIX=\"${PREFIX:-" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "}\""));
        assertTrue(script.contains("export HOME=\"${HOME:-" + TermuxConstants.TERMUX_HOME_DIR_PATH + "}\""));
        assertTrue(script.contains("TERMUX_APP_PACKAGE='" + TermuxConstants.TERMUX_PACKAGE_NAME + "'"));
        assertTrue(script.contains("TERMUX_PREFIX=\"$PREFIX\""));
        assertTrue(script.contains("TERMUX_ANDROID_HOME=\"$HOME\""));
        assertTrue(script.contains("install_legacy_termux_deb"));
        assertTrue(script.contains("patch_bundled_tool_paths()"));
        assertTrue(compatPrefix.startsWith("/data/data/com.portalagent/"));
        assertFalse(compatPrefix.equals(TermuxConstants.TERMUX_PREFIX_DIR_PATH));
        assertEquals(oldPrefix.length(), compatPrefix.length());
        assertTrue(script.contains("old_prefix=\"" + oldPrefix + "\""));
        assertTrue(script.contains("compat_prefix=\"" + compatPrefix + "\""));
        assertTrue(script.contains("sed -i \"s|$old_prefix|$compat_prefix|g\""));
        assertFalse(script.contains("sed -i \"s|/data/data/com.termux|/data/data/com.portalagent|g\""));
        assertTrue(script.contains("patch_bundled_tool_paths; if ! command -v proot-distro"));
        assertTrue(script.contains("install_legacy_termux_deb \"$HOME/.termux-tools/proot.deb\" proot 1 ||"));
        assertFalse(script.contains("dpkg -i \"$HOME/.termux-tools/proot.deb\""));
        assertFalse(script.contains("&& bash install.sh)"));
    }

    @Test
    public void snapshotPathRepairRewritesOldPackageSymlinks() {
        String script = AutoUbuntuManager.buildUbuntuSnapshotPathRepairShellForTest();
        String legacyPrivatePackage = new String(new char[] {
            99, 111, 109, 46, 122, 114, 115, 46, 112, 97
        });

        assertTrue(script.contains("repair_snapshot_package_paths()"));
        assertTrue(script.contains("find \"$_ubr\" -type l"));
        assertTrue(script.contains("readlink \"$_link\""));
        assertTrue(script.contains("for _old_pkg in com.termux $(printf '\\143\\157\\155\\056\\172\\162\\163\\056\\160\\141')"));
        assertFalse(script.contains(legacyPrivatePackage));
        assertTrue(script.contains("/data/data/${_old_pkg}/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu"));
        assertTrue(script.contains("/data/user/0/${_old_pkg}/files/home"));
        assertTrue(script.contains("_new_rootfs=\"" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/installed-rootfs/ubuntu\""));
        assertTrue(script.contains("_new_home=\"" + TermuxConstants.TERMUX_HOME_DIR_PATH + "\""));
        assertTrue(script.contains("ln -sfn \"$_new_target\" \"$_link\""));
        assertTrue(script.contains("rmdir \"$_ubr/data/data/${_old_pkg}/files\""));
    }

    @Test
    public void terminalLoginDispatcherUsesSelectedProviderFile() {
        String script = AutoUbuntuManager.buildProviderLoginDispatcherForTest();

        assertTrue(script.contains(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.assistant-provider"));
        assertFalse(script.contains("/data/user/0/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/home/.assistant-provider"));
        assertTrue(script.contains("exec su - codex"));
        assertTrue(script.contains("exec su - claude"));
        assertTrue(script.contains("case \"$_provider\" in"));
        assertFalse(script.contains("printf '\\nexec su - claude\\n'"));
    }

    @Test
    public void terminalLoginDispatcherPathUsesCanonicalTermuxHome() {
        String path = AutoUbuntuManager.buildProviderFilePathForUbuntuForTest();

        assertTrue(path.endsWith("/files/home/.assistant-provider"));
        assertTrue(path.startsWith("/data/data/"));
        assertFalse(path.startsWith("/data/user/0/"));
    }
}
