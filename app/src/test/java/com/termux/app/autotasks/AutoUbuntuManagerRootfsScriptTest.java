package com.termux.app.autotasks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void terminalLoginDispatcherUsesSelectedProviderFile() {
        String script = AutoUbuntuManager.buildProviderLoginDispatcherForTest();

        assertTrue(script.contains("/data/data/com.termux/files/home/.assistant-provider"));
        assertFalse(script.contains("/data/user/0/com.termux/files/home/.assistant-provider"));
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
