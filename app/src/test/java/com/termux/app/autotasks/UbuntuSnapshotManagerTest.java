package com.termux.app.autotasks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class UbuntuSnapshotManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void rootfsUsableRejectsPartialSnapshotDirectory() throws Exception {
        File rootfs = tmp.newFolder("ubuntu-partial");
        assertTrue(new File(rootfs, ".l2s").mkdirs());
        assertTrue(new File(rootfs, "home/claude").mkdirs());

        assertFalse(UbuntuSnapshotManager.isRootfsUsable(rootfs));
    }

    @Test
    public void rootfsUsableAcceptsMinimalUbuntuStructure() throws Exception {
        File rootfs = tmp.newFolder("ubuntu-valid");
        File etc = new File(rootfs, "etc");
        File usrBin = new File(rootfs, "usr/bin");
        File bin = new File(rootfs, "bin");
        assertTrue(etc.mkdirs());
        assertTrue(usrBin.mkdirs());
        assertTrue(bin.mkdirs());

        write(new File(etc, "passwd"), "root:x:0:0:root:/root:/bin/bash\n");
        write(new File(etc, "os-release"), "ID=ubuntu\n");
        write(new File(usrBin, "env"), "#!/bin/sh\n");
        write(new File(bin, "sh"), "#!/bin/sh\n");

        assertTrue(UbuntuSnapshotManager.isRootfsUsable(rootfs));
    }

    @Test
    public void rootfsManagerAvoidsAndroid11MissingStreamMethods() throws Exception {
        String code = readProductionSource();

        assertFalse("Android 11 runtime lacks InputStream.transferTo", code.contains(".transferTo("));
        assertFalse("Android 11 runtime lacks OutputStream.nullOutputStream", code.contains("nullOutputStream("));
    }

    @Test
    public void networkSnapshotDownloadUsesRetriesAndLongReadTimeout() throws Exception {
        String code = readProductionSource();

        assertTrue(code.contains("DOWNLOAD_ATTEMPTS"));
        assertTrue(code.contains("DOWNLOAD_READ_TIMEOUT_MS"));
        assertTrue(code.contains("120_000") || code.contains("180_000"));
        assertTrue(code.contains("attempt < DOWNLOAD_ATTEMPTS"));
    }

    private static void write(File file, String content) throws Exception {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String readProductionSource() throws Exception {
        String relative = "src/main/java/com/termux/app/autotasks/UbuntuSnapshotManager.java";
        File source = new File(relative);
        if (!source.isFile()) {
            source = new File("app/" + relative);
        }
        assertTrue("production source not found: " + source.getAbsolutePath(), source.isFile());
        return new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
    }
}
