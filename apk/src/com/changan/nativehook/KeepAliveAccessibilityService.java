package com.changan.nativehook;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 空壳无障碍服务：不读窗口内容、不接管手势，唯一作用是让系统长期持有本进程。
 *
 * 系统会 bind 已启用的无障碍服务，进程被杀或被覆盖安装后会自动重连，
 * 比 START_STICKY 可靠得多；开机时也会随无障碍服务恢复而被拉起。
 * 连上之后顺手把 KeepAliveService 拉起来，接通常驻链路（ensure → nhkeepd → 注入）。
 */
public class KeepAliveAccessibilityService extends AccessibilityService {

    private static final String TAG = "NHKeepAliveAcc";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "accessibility connected");
        try {
            Intent i = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "start keepalive failed", t);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不处理任何事件
    }

    @Override
    public void onInterrupt() {
    }
}
