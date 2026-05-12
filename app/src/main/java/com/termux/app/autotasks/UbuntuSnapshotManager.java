package com.termux.app.autotasks;

import android.content.res.AssetManager;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
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
        "https://github.com/Zeta112233/Claude_code_Android_app/releases/download/snapshot-v2/ubuntu-claude-aarch64-20260512.tar.xz";
    public static final String SNAPSHOT_SHA256 =
        "9d84146748aba561286088955e66343c88c078f86179441a1c279d0a9b402bd2";
    public static final long   SNAPSHOT_BYTES = 208_834_812L; // 实际字节数，用于进度计算

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
        try {
            Process p = new ProcessBuilder(BASH, PROOT_D, "login", "ubuntu",
                "--user", "claude", "--", "sh", "-c", "claude --version")
                .redirectErrorStream(true)
                .start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
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
        try {
            File tmp = new File(TMP_TAR);
            cb.onStatus("从安装包提取快照（171MB）…");
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
            cb.onStatus("解压中（可能需要 1-2 分钟）…");
            extract(tmp, cb);
            tmp.delete();
            cb.onStatus("部署完成");
            cb.onSuccess();
        } catch (Exception e) {
            Log.e(TAG, "deployFromAsset failed", e);
            new File(TMP_TAR).delete();
            cb.onFailed(e.getMessage());
        }
    }

    /**
     * 完整部署流程：下载 → 校验 → 解压。在后台线程调用。
     */
    public static void deploy(Callback cb) {
        try {
            // Step 1: 下载
            cb.onStatus("正在下载 Ubuntu 快照（171MB）…");
            File tmp = new File(TMP_TAR);
            download(SNAPSHOT_URL, tmp, cb);

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
            cb.onFailed(e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 内部实现
    // ─────────────────────────────────────────────────────────────────────

    private static void download(String urlStr, File dest, Callback cb) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(true);

        // GitHub Release 会 301 重定向到 CDN
        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == 307 || code == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (HttpURLConnection) new URL(location).openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
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
                int pct = (int) (downloaded * 100 / total);
                if (pct != lastPercent) {
                    lastPercent = pct;
                    cb.onProgress(pct);
                }
            }
        } finally {
            conn.disconnect();
        }
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
        proc.getInputStream().transferTo(OutputStream.nullOutputStream());
        int exit = proc.waitFor();
        if (exit != 0) throw new Exception("解压失败 (exit=" + exit + ")");
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
