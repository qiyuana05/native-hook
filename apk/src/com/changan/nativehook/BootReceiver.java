package com.changan.nativehook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** 开机后如果用户勾选过常驻，自动拉起 KeepAliveService。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        SharedPreferences p = context.getSharedPreferences("uh", Context.MODE_PRIVATE);
        if (!p.getBoolean("keep_alive", false)) return;
        // 只有真正重启才提示；APK 覆盖安装不弹。
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            p.edit().putBoolean("boot_toast_pending", true).apply();
        }
        Intent svc = new Intent(context, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}