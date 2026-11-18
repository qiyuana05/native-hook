package com.changan.nativehook;

import android.content.Context;
import android.content.res.Configuration;
import android.provider.Settings;

/** 跟随车机日夜模式，优先读系统设置，最后看 Configuration。 */
final class DayNight {

    private DayNight() {
    }

    static boolean isNight(Context ctx) {
        int secure = read(ctx, true);
        if (secure == 2) return true;
        if (secure == 1) return false;
        int system = read(ctx, false);
        if (system == 2) return true;
        if (system == 1) return false;
        int mode = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int read(Context ctx, boolean secure) {
        try {
            return secure
                    ? Settings.Secure.getInt(ctx.getContentResolver(), "ui_night_mode")
                    : Settings.System.getInt(ctx.getContentResolver(), "ui_night_mode");
        } catch (Exception ignored) {
            return -1;
        }
    }
}