package com.termux.app;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.termux.R;

/**
 * 后台悬浮窗：只在 (App 后台 && Claude 正在执行任务 && 用户未手动关闭) 时显示。
 * 通过 static updateStatus() 从任意线程更新内容并切换 busy 状态。
 */
public class FloatingStatusService extends Service {

    public static final String ACTION_SHOW = "SHOW";  // App 进入后台
    public static final String ACTION_HIDE = "HIDE";  // App 回到前台

    private static FloatingStatusService sInstance;

    private WindowManager mWindowManager;
    private View          mFloatingView;
    private TextView      mStatusText;
    private TextView      mPreviewText;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // ── 显示控制状态（决定 mFloatingView 可见性）────────────────────────────
    // 注：是否在后台直接读 TermuxActivity.sActivityForeground，避免 Service 自己缓存的状态
    // 与 Activity 真实生命周期不同步（早期版本用 mInBackground 缓存，存在前台仍显示的 bug）
    private static volatile boolean sIsBusy      = false;
    private static volatile boolean sUserClosed  = false;

    // 最新状态缓存（Service 可能在 SHOW 前就收到更新）
    private static volatile String sPendingStatus  = "● 等待中";
    private static volatile String sPendingPreview = "";
    private static volatile int    sPendingColor   = 0xFF888888;

    // ── 静态接口（供 HomeFragment 跨线程调用）──────────────────────────────

    /**
     * 更新悬浮窗显示的状态、消息预览，并指明 Claude 是否正在执行任务。
     * isBusy=true → 满足其它条件时显示；isBusy=false → 隐藏。
     * busy 从 false→true 的边沿会重置 sUserClosed（让新任务能再次弹出）。
     */
    public static void updateStatus(String status, int color, String preview, boolean isBusy) {
        boolean wasBusy = sIsBusy;
        sPendingStatus  = status;
        sPendingColor   = color;
        sPendingPreview = preview != null ? preview : "";
        sIsBusy         = isBusy;
        if (!wasBusy && isBusy) sUserClosed = false;
        FloatingStatusService inst = sInstance;
        if (inst != null) {
            inst.mHandler.post(() -> {
                inst.applyPending();
                inst.updateVisibility();
            });
        }
    }

    /** 兼容旧 3 参形式：默认认为是 idle 更新（busy=false）。 */
    public static void updateStatus(String status, int color, String preview) {
        updateStatus(status, color, preview, false);
    }

    // ── Service 生命周期 ────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mFloatingView  = LayoutInflater.from(this).inflate(R.layout.layout_floating_status, null);
        mStatusText    = mFloatingView.findViewById(R.id.float_status);
        mPreviewText   = mFloatingView.findViewById(R.id.float_preview);

        // 关闭按钮：用户主动关掉本次任务的悬浮窗，下一个新任务才会再次出现
        View closeBtn = mFloatingView.findViewById(R.id.float_close);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> {
                sUserClosed = true;
                mFloatingView.setVisibility(View.GONE);
            });
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 16;
        params.y = 80;

        mFloatingView.setVisibility(View.GONE);
        mWindowManager.addView(mFloatingView, params);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        // SHOW/HIDE 现在只触发一次重新评估；真实前后台状态以 TermuxActivity.sActivityForeground 为准
        if (ACTION_SHOW.equals(action) || ACTION_HIDE.equals(action)) {
            applyPending();
            updateVisibility();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mFloatingView != null && mWindowManager != null) {
            mWindowManager.removeView(mFloatingView);
        }
        sInstance = null;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private void applyPending() {
        if (mStatusText == null) return;
        mStatusText.setText(sPendingStatus);
        mStatusText.setTextColor(sPendingColor);
        String preview = sPendingPreview;
        if (preview.isEmpty()) {
            mPreviewText.setVisibility(View.GONE);
        } else {
            mPreviewText.setText(preview.length() > 40 ? preview.substring(0, 40) + "…" : preview);
            mPreviewText.setVisibility(View.VISIBLE);
        }
    }

    private void updateVisibility() {
        if (mFloatingView == null) return;
        boolean inBackground = !TermuxActivity.sActivityForeground;
        boolean shouldShow   = inBackground && sIsBusy && !sUserClosed;
        mFloatingView.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
    }
}
