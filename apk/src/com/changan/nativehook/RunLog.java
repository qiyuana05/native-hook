package com.changan.nativehook;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 运行日志落地。
 *
 * 写到外部私有目录，文件管理器 / adb / 4455 都能直接看：
 *   /sdcard/Android/data/com.changan.nativehook/files/logs/run-YYYYMMDD.log
 * 外部目录不可用时回退 /data/data/<pkg>/files/logs。
 */
final class RunLog {

    private static final Object LOCK = new Object();
    private static final long KEEP_MS = 7L * 24 * 3600 * 1000;
    private static File cachedDir;

    private RunLog() {
    }

    static File dir(Context ctx) {
        synchronized (LOCK) {
            if (cachedDir != null) {
                return cachedDir;
            }
            File base = ctx.getExternalFilesDir(null);
            if (base == null) {
                base = ctx.getFilesDir();
            }
            File d = new File(base, "logs");
            d.mkdirs();
            cachedDir = d;
            return d;
        }
    }

    static File currentFile(Context ctx) {
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        return new File(dir(ctx), "run-" + day + ".log");
    }

    static String currentPath(Context ctx) {
        return currentFile(ctx).getAbsolutePath();
    }

    static void append(Context ctx, String level, String message) {
        if (message == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                File d = dir(ctx);
                String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
                File file = new File(d, "run-" + day + ".log");
                String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                        .format(new Date());
                String text = message.replace("\r", "");
                StringBuilder sb = new StringBuilder(text.length() + 32);
                boolean first = true;
                for (String line : text.split("\n", -1)) {
                    if (first) {
                        sb.append(ts).append(" [").append(level).append("] ")
                                .append(line).append('\n');
                        first = false;
                    } else {
                        sb.append("    ").append(line).append('\n');
                    }
                }
                FileOutputStream fos = new FileOutputStream(file, true);
                try {
                    fos.write(sb.toString().getBytes("UTF-8"));
                } finally {
                    fos.close();
                }
                cleanup(d);
            } catch (Exception ignored) {
            }
        }
    }

    private static void cleanup(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith("run-")
                    && f.lastModified() < cutoff) {
                f.delete();
            }
        }
    }
}