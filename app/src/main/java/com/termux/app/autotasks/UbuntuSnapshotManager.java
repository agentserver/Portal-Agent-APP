package com.termux.app.autotasks;

import android.content.res.AssetManager;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 基于预配置快照（tar.xz）的 Ubuntu 环境快速部署与自动修复。
 *
 * 流程：
 *   1. 检查当前 Ubuntu rootfs 健康状态（claude --version）
 *   2. 不健康 / 不存在 → 下载快照 → SHA256 验证 → 解压替换 rootfs
 *   3. 下载失败 → 回退到现有 assets + 原有安装流程
 *
 * 快照托管在 GitHub Releases，不打入 APK，避免 APK 体积过大。
 */
public class UbuntuSnapshotManager {

    private static final String TAG = "UbuntuSnapshotMgr";

    // ── 快照元数据（每次更新快照时同步修改） ──────────────────────────────
    public static final String SNAPSHOT_URL =
        "https://github.com/Zeta112233/Claude_code_Android_app/releases/download/snapshot-v3/ubuntu-claude-aarch64-20260521.tar.xz";
    public static final String SNAPSHOT_SHA256 =
        "cd87105f6ea7c9427693aa5cf4c6063bd4d055b3bf82249cb157b6de7867076d";
    public static final long   SNAPSHOT_BYTES = 208_145_332L; // 实际字节数，用于进度计算

    /** 用于 UI 提示，自动从字节数四舍五入算 MB，避免改快照时漏掉硬编码字符串。 */
    public static final String SNAPSHOT_SIZE_LABEL =
        Math.round(SNAPSHOT_BYTES / 1024.0 / 1024.0) + "MB";

    // APK 内置快照 asset 路径（构建时手动放置，不入 git）
    static final String SNAPSHOT_ASSET_NAME =
        "ubuntu-snapshot/ubuntu-claude-aarch64-20260512.tar.xz";

    // ── 路径 ──────────────────────────────────────────────────────────────
    private static final String PREFIX    = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
    private static final String BASH      = PREFIX + "/bin/bash";
    private static final String PROOT_D   = PREFIX + "/bin/proot-distro";
    private static final String ROOTFS_DIR =
        PREFIX + "/var/lib/proot-distro/installed-rootfs";
    // package-private：供 AutoUbuntuManager 检查 rootfs 是否存在
    static final String UBUNTU_ROOTFS = ROOTFS_DIR + "/ubuntu";
    private static final String TMP_TAR   =
        TermuxConstants.TERMUX_HOME_DIR_PATH + "/.ubuntu-snapshot-tmp.tar.xz";
    private static final int DOWNLOAD_ATTEMPTS = 3;
    private static final int DOWNLOAD_CONNECT_TIMEOUT_MS = 45_000;
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 180_000;
    private static final int MAX_REDIRECTS = 5;

    // ── 回调接口 ──────────────────────────────────────────────────────────

    public interface Callback {
        /** 状态文字更新（主线程调用） */
        void onStatus(String message);
        /** 下载进度 0-100 */
        void onProgress(int percent);
        /** 部署成功 */
        void onSuccess();
        /** 部署失败，fallback 将接管 */
        void onFailed(String reason);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 公开入口
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 检查 Ubuntu 环境健康状态。
     * @return true = 健康（claude --version 正常）
     */
    public static boolean isHealthy() {
        if (!isRootfsUsable()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(PROOT_D, "login", "ubuntu",
                "--user", "claude", "--", "sh", "-c", "claude --version")
                .redirectErrorStream(true);
            setTermuxEnv(pb);
            Process p = pb.start();
            drain(p.getInputStream());
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isRootfsUsable() {
        return isRootfsUsable(new File(UBUNTU_ROOTFS));
    }

    static boolean isRootfsUsable(File rootfs) {
        if (rootfs == null || !rootfs.isDirectory()) return false;
        if (!new File(rootfs, "etc/passwd").isFile()) return false;
        if (!new File(rootfs, "etc/os-release").isFile()) return false;
        if (!new File(rootfs, "usr").isDirectory()) return false;
        if (!new File(rootfs, "usr/bin/env").isFile()) return false;
        return new File(rootfs, "bin/sh").exists()
            || new File(rootfs, "usr/bin/sh").exists();
    }

    /** 检查 APK assets 中是否内置了快照文件。 */
    public static boolean hasAsset(AssetManager assets) {
        try {
            assets.open(SNAPSHOT_ASSET_NAME).close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 从 APK 内置 asset 部署（离线，无 SHA256 校验——APK 签名即保证完整性）。
     * 在后台线程调用。
     */
    public static void deployFromAsset(AssetManager assets, Callback cb) {
        boolean replacingRootfs = false;
        try {
            File tmp = new File(TMP_TAR);
            cb.onStatus("从安装包提取快照（" + SNAPSHOT_SIZE_LABEL + "）…");
            try (InputStream in = assets.open(SNAPSHOT_ASSET_NAME);
                 OutputStream out = Files.newOutputStream(tmp.toPath())) {
                byte[] buf = new byte[65536];
                long copied = 0;
                int n, lastPct = -1;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    copied += n;
                    int pct = (int) Math.min(copied * 100 / SNAPSHOT_BYTES, 100);
                    if (pct != lastPct) { lastPct = pct; cb.onProgress(pct); }
                }
            }
            cb.onStatus("清除旧环境…");
            deleteRootfs();
            replacingRootfs = true;
            cb.onStatus("解压中（可能需要 1-2 分钟）…");
            extract(tmp, cb);
            tmp.delete();
            cb.onStatus("部署完成");
            cb.onSuccess();
        } catch (Exception e) {
            Log.e(TAG, "deployFromAsset failed", e);
            new File(TMP_TAR).delete();
            if (replacingRootfs) deleteRootfsQuietly();
            cb.onFailed(e.getMessage());
        }
    }

    /**
     * 完整部署流程：下载 → 校验 → 解压。在后台线程调用。
     */
    public static void deploy(Callback cb) {
        boolean replacingRootfs = false;
        try {
            File tmp = new File(TMP_TAR);

            // Step 1: 下载
            if (!tryReuseVerifiedSnapshot(tmp, cb)) {
                downloadWithRetries(tmp, cb);
            }

            // Step 2: SHA256 校验
            cb.onStatus("校验文件完整性…");
            String actualSha = sha256(tmp);
            if (!SNAPSHOT_SHA256.equalsIgnoreCase(actualSha)) {
                tmp.delete();
                cb.onFailed("SHA256 不匹配，文件可能损坏（actual=" + actualSha + "）");
                return;
            }

            // Step 3: 删除旧 rootfs
            cb.onStatus("清除旧环境…");
            deleteRootfs();
            replacingRootfs = true;

            // Step 4: 解压
            cb.onStatus("正在解压（请稍候）…");
            extract(tmp, cb);

            // Step 5: 清理临时文件
            tmp.delete();

            cb.onStatus("部署完成");
            cb.onSuccess();

        } catch (Exception e) {
            Log.e(TAG, "deploy failed", e);
            new File(TMP_TAR).delete();
            if (replacingRootfs) deleteRootfsQuietly();
            cb.onFailed(e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 内部实现
    // ─────────────────────────────────────────────────────────────────────

    private static boolean tryReuseVerifiedSnapshot(File tmp, Callback cb) throws Exception {
        if (!tmp.isFile()) return false;
        if (tmp.length() != SNAPSHOT_BYTES) {
            tmp.delete();
            return false;
        }
        cb.onStatus("发现已下载快照，校验文件完整性…");
        String actualSha = sha256(tmp);
        if (SNAPSHOT_SHA256.equalsIgnoreCase(actualSha)) {
            cb.onProgress(100);
            return true;
        }
        Log.w(TAG, "discarding cached snapshot with sha256=" + actualSha);
        tmp.delete();
        return false;
    }

    private static void downloadWithRetries(File tmp, Callback cb) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            cb.onStatus("正在下载 Ubuntu 快照（" + SNAPSHOT_SIZE_LABEL + "，"
                + attempt + "/" + DOWNLOAD_ATTEMPTS + "）…");
            try {
                download(SNAPSHOT_URL, tmp, cb);
                return;
            } catch (IOException e) {
                last = e;
                tmp.delete();
                Log.w(TAG, "snapshot download attempt " + attempt + " failed", e);
                if (attempt < DOWNLOAD_ATTEMPTS) {
                    cb.onStatus("下载失败，准备重试：" + shortMessage(e));
                    sleepBeforeRetry(attempt);
                }
            }
        }
        throw last != null ? last : new IOException("download failed");
    }

    private static void sleepBeforeRetry(int attempt) throws IOException {
        try {
            Thread.sleep(Math.min(15_000L, attempt * 3_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download retry interrupted", e);
        }
    }

    private static String shortMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) return e.getClass().getSimpleName();
        return msg;
    }

    private static void download(String urlStr, File dest, Callback cb) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = null;
        try {
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                conn = openDownloadConnection(url);
                int code = conn.getResponseCode();
                if (isRedirect(code)) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (location == null || location.trim().isEmpty()) {
                        throw new IOException("redirect without Location");
                    }
                    url = new URL(url, location);
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code + " while downloading snapshot");
                }

                long total = conn.getContentLengthLong();
                if (total <= 0) total = SNAPSHOT_BYTES;

                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(dest.toPath())) {
                    byte[] buf = new byte[65536];
                    long downloaded = 0;
                    int n;
                    int lastPercent = -1;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        downloaded += n;
                        int pct = (int) Math.min(downloaded * 100 / total, 100);
                        if (pct != lastPercent) {
                            lastPercent = pct;
                            cb.onProgress(pct);
                        }
                    }
                }
                return;
            }
            throw new IOException("too many redirects while downloading snapshot");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection openDownloadConnection(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "ClaudeCodeAndroidApp");
        conn.setRequestProperty("Accept", "application/octet-stream,*/*");
        return conn;
    }

    private static boolean isRedirect(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM
            || code == HttpURLConnection.HTTP_MOVED_TEMP
            || code == HttpURLConnection.HTTP_SEE_OTHER
            || code == 307
            || code == 308;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void deleteRootfs() throws Exception {
        File rootfs = new File(UBUNTU_ROOTFS);
        if (!rootfs.exists()) return;
        // proot-distro remove 可能会因为 rootfs 损坏而失败，直接用 bash rm
        ProcessBuilder pb = new ProcessBuilder(BASH, "-c",
            "rm -rf '" + UBUNTU_ROOTFS + "'");
        setTermuxEnv(pb);
        int exit = pb.start().waitFor();
        if (exit != 0) throw new Exception("删除旧 rootfs 失败 (exit=" + exit + ")");
    }

    private static void deleteRootfsQuietly() {
        try {
            deleteRootfs();
        } catch (Exception e) {
            Log.w(TAG, "failed to cleanup incomplete rootfs", e);
        }
    }

    private static void extract(File tar, Callback cb) throws Exception {
        cb.onStatus("解压中（可能需要 1-2 分钟）…");
        // tar -xJf <tar.xz> -C <rootfs_dir>
        // 快照内顶层目录名为 "ubuntu"，解压后即 rootfs_dir/ubuntu/
        ProcessBuilder pb = new ProcessBuilder(BASH, "-c",
            "mkdir -p '" + ROOTFS_DIR + "' && " +
            "tar -xJf '" + tar.getAbsolutePath() + "' -C '" + ROOTFS_DIR + "'");
        setTermuxEnv(pb);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(proc.getInputStream(), output);
        int exit = proc.waitFor();
        if (exit != 0) {
            String detail = tail(new String(output.toByteArray(), StandardCharsets.UTF_8), 800);
            throw new Exception("解压失败 (exit=" + exit + ")" +
                (detail.isEmpty() ? "" : ": " + detail));
        }
        if (!isRootfsUsable()) {
            throw new Exception("解压后的 Ubuntu rootfs 不完整");
        }
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        while (in.read(buffer) != -1) {
            // Discard output.
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
    }

    private static String tail(String s, int maxChars) {
        if (s == null) return "";
        String trimmed = s.trim();
        if (trimmed.length() <= maxChars) return trimmed;
        return trimmed.substring(trimmed.length() - maxChars);
    }

    private static void setTermuxEnv(ProcessBuilder pb) {
        java.util.Map<String, String> env = pb.environment();
        env.put("PREFIX", PREFIX);
        env.put("HOME",   TermuxConstants.TERMUX_HOME_DIR_PATH);
        env.put("PATH",   TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":"
                          + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets");
        env.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        env.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        env.put("LANG",   "en_US.UTF-8");
    }
}
