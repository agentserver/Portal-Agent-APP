package com.termux.app.autotasks;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.api.apis.BatteryStatusAPI;
import com.termux.api.apis.CameraInfoAPI;
import com.termux.api.apis.SensorAPI;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Phase 3: 采集设备能力信息，在 App 启动时写入 capabilities.json。
 *
 * 输出路径：$HOME/capabilities.json（Termux home，即 filesDir/home/capabilities.json）
 * Ubuntu 内软链接指向该文件，Claude Code 可直接 cat ~/capabilities.json 读取。
 *
 * 内容：
 *   - device: 设备型号、系统版本、架构
 *   - battery: 电量、状态、温度
 *   - cameras: 摄像头列表（需 CAMERA 权限）
 *   - sensors: 传感器列表（需 BODY_SENSORS 权限）
 *   - permissions: 已授权的权限列表
 *   - api_instructions: 告知 Claude Code 如何调用 Termux API
 */
public class CapabilitiesManager {

    static final String CAPABILITIES_FILE_REL = "home/capabilities.json";

    private final TermuxActivity mActivity;

    public CapabilitiesManager(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    /** 后台异步生成，避免阻塞主线程。 */
    public void generateAsync() {
        Thread t = new Thread(this::generate, "capabilities-gen");
        t.setDaemon(true);
        t.start();
    }

    /** 返回 capabilities.json 的绝对路径（固定路径）。 */
    @NonNull
    public String getCapabilitiesFilePath() {
        return new File(mActivity.getFilesDir(), CAPABILITIES_FILE_REL).getAbsolutePath();
    }

    // -------------------------------------------------------------------------
    // 采集与写入
    // -------------------------------------------------------------------------

    private void generate() {
        try {
            JSONObject root = new JSONObject();

            // ── 设备基础信息 ──────────────────────────────────────────────────
            JSONObject device = new JSONObject();
            device.put("model", Build.MODEL);
            device.put("manufacturer", Build.MANUFACTURER);
            device.put("android_version", Build.VERSION.RELEASE);
            device.put("android_sdk", Build.VERSION.SDK_INT);
            device.put("architecture", Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "unknown");
            root.put("device", device);

            // ── 电量 ──────────────────────────────────────────────────────────
            try {
                String batteryJson = BatteryStatusAPI.getBatteryStatusJson(mActivity);
                root.put("battery", new JSONObject(batteryJson));
            } catch (Throwable t) {
                root.put("battery", "unavailable: " + t.getMessage());
            }

            // ── 摄像头 ────────────────────────────────────────────────────────
            if (isGranted(Manifest.permission.CAMERA)) {
                try {
                    String cameraJson = CameraInfoAPI.getCameraInfoJson(mActivity);
                    JSONArray cameras = new JSONArray(cameraJson);
                    root.put("cameras", cameras);
                    root.put("camera_count", cameras.length());
                } catch (Throwable t) {
                    root.put("cameras", "unavailable: " + t.getMessage());
                }
            } else {
                root.put("cameras", "permission_required: android.permission.CAMERA");
            }

            // ── 传感器 ────────────────────────────────────────────────────────
            if (isGranted(Manifest.permission.BODY_SENSORS)) {
                try {
                    String sensorJson = SensorAPI.getSensorListJson(mActivity);
                    JSONObject sensorObj = new JSONObject(sensorJson);
                    JSONArray sensors = sensorObj.optJSONArray("sensors");
                    root.put("sensors", sensors != null ? sensors : new JSONArray());
                } catch (Throwable t) {
                    root.put("sensors", "unavailable: " + t.getMessage());
                }
            } else {
                root.put("sensors", "permission_required: android.permission.BODY_SENSORS");
            }

            // ── 权限状态 ──────────────────────────────────────────────────────
            JSONObject permissions = new JSONObject();
            permissions.put("camera",
                isGranted(Manifest.permission.CAMERA));
            permissions.put("body_sensors",
                isGranted(Manifest.permission.BODY_SENSORS));
            permissions.put("fine_location",
                isGranted(Manifest.permission.ACCESS_FINE_LOCATION));
            permissions.put("record_audio",
                isGranted(Manifest.permission.RECORD_AUDIO));
            root.put("permissions", permissions);

            // ── API 使用说明（给 Claude Code 的提示）──────────────────────────
            // Android API 通过 HTTP 桥暴露给 Ubuntu 内进程：
            // ApiHttpBridgeServer 在 Android 侧监听 127.0.0.1:PORT，返回实时数据。
            // Ubuntu 内用标准 curl 调用，或使用 /usr/local/bin/ 下的同名封装脚本。
            JSONObject apiInstructions = new JSONObject();
            apiInstructions.put("note",
                "Android APIs are bridged via HTTP on 127.0.0.1:" + ApiHttpBridgeServer.PORT
                + ". Use curl from Ubuntu — wrapper scripts in /usr/local/bin/ call the same endpoints.");
            apiInstructions.put("http_bridge_port", ApiHttpBridgeServer.PORT);
            apiInstructions.put("http_endpoints", new JSONArray()
                .put("GET /battery   → real-time battery status JSON")
                .put("GET /camera    → camera list JSON")
                .put("GET /sensors   → sensor list JSON")
                .put("GET /wifi      → WiFi connection info JSON")
                .put("GET /clipboard → clipboard plain text"));
            apiInstructions.put("from_ubuntu_examples", new JSONArray()
                .put("curl -s http://127.0.0.1:" + ApiHttpBridgeServer.PORT + "/battery")
                .put("curl -s http://127.0.0.1:" + ApiHttpBridgeServer.PORT + "/camera")
                .put("curl -s http://127.0.0.1:" + ApiHttpBridgeServer.PORT + "/sensors")
                .put("curl -s http://127.0.0.1:" + ApiHttpBridgeServer.PORT + "/wifi")
                .put("curl -s http://127.0.0.1:" + ApiHttpBridgeServer.PORT + "/clipboard")
                .put("termux-battery-status  # wrapper script in /usr/local/bin/")
                .put("termux-camera-info")
                .put("termux-wifi-connectioninfo"));
            apiInstructions.put("capabilities_file",
                TermuxConstants.TERMUX_HOME_DIR_PATH + "/capabilities.json");
            root.put("api_instructions", apiInstructions);

            // ── 生成时间 ──────────────────────────────────────────────────────
            root.put("generated_at",
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));

            // ── 写文件 ────────────────────────────────────────────────────────
            File outFile = new File(mActivity.getFilesDir(), CAPABILITIES_FILE_REL);
            outFile.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(outFile)) {
                w.write(root.toString(2));
            }

        } catch (Throwable ignored) {
            // 静默失败；文件不存在时 Claude Code 只是读不到，不崩溃
        }
    }

    private boolean isGranted(String permission) {
        return ContextCompat.checkSelfPermission(mActivity, permission)
            == PackageManager.PERMISSION_GRANTED;
    }
}
