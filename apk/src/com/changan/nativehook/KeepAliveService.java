package com.changan.nativehook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * 常驻检测服务：每 15 秒检查一次 hook 状态。
 * 进程闪退/重启后内存 hook 会消失，缩短间隔可以在用户重连前补注入。
 */
public class KeepAliveService extends Service {

    private static final String TAG = "NativeHookKeep";
    private static final String CHANNEL = "nativehook";
    private static final int NOTIFY_ID = 1001;
    // 开机注入结果：单独一个常驻通知，点一下打开 App 才消失。
    private static final String BOOT_CHANNEL = "nativehook_boot";
    private static final int BOOT_NOTIFY_ID = 1002;
    private static final long FIRST_DELAY_MS = 5000;
    private static final long INTERVAL_MS = 15000;
    private static final Object LOG_LOCK = new Object();

    private DmClient dm;
    private volatile boolean running;
    private volatile boolean checking;
    private Thread worker;
    private View bootOverlay;
    private WindowManager bootOverlayWm;
    /** 当前屏幕上那张卡片是不是「注入失败」的误报，后续成功时要自动撤掉。 */
    private volatile boolean bootOverlayFailed;

    @Override
    public void onCreate() {
        super.onCreate();
        dm = new DmClient(this);
        createChannel();
        startForeground(NOTIFY_ID, buildNotification("常驻检测运行中"));
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                sleepQuietly(FIRST_DELAY_MS);
                while (running) {
                    checkOnce();
                    sleepQuietly(INTERVAL_MS);
                }
            }
        }, "nh-keepalive");
        worker.start();
        Log.i(TAG, "service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        removeBootOverlay();
        Log.i(TAG, "service stopped");
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (getSharedPreferences("uh", MODE_PRIVATE)
                .getBoolean("keep_alive", false)) {
            Intent i = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final long ENSURE_WAIT_MS = 20000;
    /** root 侧守护进程 nhkeepd 的心跳文件：15 秒写一次，60 秒内算活着。 */
    private static final String KEEPD_HB = "/data/local/tmp/hook-native/nhkeepd.hb";

    /** 无障碍登记只需在进程生命周期内做一次。 */
    private boolean accApplied;
    private String lastState = "";
    private int sameStateCount;

    /**
     * 守护进程是否活着。
     *
     * 不能直接 File.lastModified()：心跳文件在 /data/local/tmp，属主 root，
     * App 读不到（root 的 umask 077 建出来是 600，SELinux 也可能挡）。
     * 走 4455 的 root shell 去 cat，和读 status.sh 用的是同一条通道。
     */
    private boolean watchdogAlive() {
        try {
            String out = FastChannel.exec("cat " + KEEPD_HB + " 2>/dev/null", 4000);
            if (out == null) return false;
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("(\\d{9,})").matcher(out);
            long hb = 0;
            while (m.find()) {
                try {
                    hb = Long.parseLong(m.group(1));
                } catch (Throwable ignored) {
                }
            }
            if (hb <= 0) return false;
            long now = System.currentTimeMillis() / 1000L;
            return Math.abs(now - hb) <= 60;
        } catch (Throwable t) {
            return false;
        }
    }

    private void checkOnce() {
        if (checking) return;
        checking = true;
        try {
            boolean shell = FastChannel.isPortOpen(FastChannel.SHELL_PORT, 300);
            boolean http = FastChannel.isPortOpen(FastChannel.HTTP_PORT, 300);
            if (!shell || !http) {
                appendLog("ports shell=" + shell + " http=" + http + " -> ensure");
                ensureServices();
                shell = FastChannel.isPortOpen(FastChannel.SHELL_PORT, 300);
                http = FastChannel.isPortOpen(FastChannel.HTTP_PORT, 300);
                appendLog("after ensure shell=" + shell + " http=" + http);
            }
            if (!accApplied) {
                try {
                    FastChannel.exec(NativeHook.ACC_CMD, 8000);
                    accApplied = true;
                } catch (Exception e) {
                    appendLog("acc enable failed " + e);
                }
            }

            if (!shell) {
                logState("no-shell", "4455 not ready, skip this round");
                // 保留 boot_toast_pending，等下一轮真正出结果再提示；
                // 否则开机瞬间 4455 还没起来就先弹一条“注入失败”。
                return;
            }

            ArrayList<Block> blocks = parseBlocks(sanitize(
                    FastChannel.exec("sh " + NativeHook.STATUS_SH, 15000)));
            String summary = summarize(blocks);
            StringBuilder bad = new StringBuilder();
            for (Block b : blocks) {
                // 进程没运行（live_pid 为空）不算异常，等它自己起来
                if (b.ok || b.livePid.isEmpty()) continue;
                bad.append(b.pkg).append(b.stale ? ":stale " : ":bad ");
            }
            if (bad.length() == 0) {
                logState("ok", "check ok " + summary);
                maybeBootNotify(true);
                return;
            }
            // App 里的巡检会被 force-stop 一锅端；nhkeepd 是 root 侧常驻进程，
            // 它活着就交给它注入，避免两边同时往同一个进程里 dlopen。
            if (watchdogAlive()) {
                logState("watchdog", "bad " + summary + " -> nhkeepd handles");
                return;
            }

            logState("bad:" + bad, "check " + summary + " -> inject");
            updateNotification("正在重新注入 ...");
            String out = FastChannel.exec("sh " + NativeHook.START_SH, 40000);
            appendLog("start " + oneLine(sanitize(out)));

            // 注入完 status 文件由各进程自己写，落在磁盘上可能晚一两百毫秒；
            // 立刻读会读到上一轮的旧 pid，被误判成 still bad（进而误报注入失败）。
            sleepQuietly(1200);
            ArrayList<Block> after = parseBlocks(sanitize(
                    FastChannel.exec("sh " + NativeHook.STATUS_SH, 15000)));
            String summary2 = summarize(after);
            boolean stillBad = hasBad(after);
            if (stillBad) {
                sleepQuietly(1500);
                after = parseBlocks(sanitize(
                        FastChannel.exec("sh " + NativeHook.STATUS_SH, 15000)));
                summary2 = summarize(after);
                stillBad = hasBad(after);
            }
            if (stillBad) {
                logState("after-bad", "after start still bad " + summary2);
                updateNotification("注入失败，下轮重试");
                if (bootOverlayFailed) removeBootOverlay();
            } else {
                logState("ok", "after start ok " + summary2);
                updateNotification("已重新注入");
                // 上一轮如果弹过失败卡片，这轮成功后把误报撤掉
                if (bootOverlayFailed) removeBootOverlay();
            }
            maybeBootNotify(!stillBad);
        } catch (Exception e) {
            logState("error:" + e, "check failed " + e);
        } finally {
            checking = false;
        }
    }

    /**
     * 拉起 4455/4466。
     *
     * 不能等 DM 回调：开机早期 DMService 还没就绪，脚本事后确实执行成功了
     * （rt_shell 4 秒内就起来了），但结果轮次标记迟迟不回，回调要拖到 60 秒超时，
     * 整条常驻线程跟着卡死，开机注入被推迟一分多钟。
     * 这里改成「发完就轮询端口」，最多等 ENSURE_WAIT_MS，端口一通立刻返回。
     */
    private void ensureServices() {
        String cmd;
        try {
            cmd = NativeHook.ensureAndSyncCommand(this);
        } catch (java.io.IOException e) {
            cmd = "sh " + NativeHook.ENSURE_SH;
            appendLog("cfg sync failed " + e);
        }
        dm.exec(cmd, new DmClient.Callback() {
            @Override
            public void onResult(boolean ok, String out) {
                // 只记录，不阻塞主流程
                appendLog("services ensure callback " + (ok ? "ok" : "fail"));
            }
        });
        final long deadline = System.currentTimeMillis() + ENSURE_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (FastChannel.isPortOpen(FastChannel.SHELL_PORT, 300)
                    && FastChannel.isPortOpen(FastChannel.HTTP_PORT, 300)) {
                appendLog("ensure ports up in "
                        + (ENSURE_WAIT_MS - (deadline - System.currentTimeMillis()))
                        + "ms");
                return;
            }
            sleepQuietly(500);
        }
        appendLog("ensure ports still down");
    }

    /** 去掉 rt_shell 的 tty 警告和提示符，避免日志被噪音淹没。 */
    private static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String raw : s.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.contains("can't find: tty fd")) continue;
            if (line.contains("won't have full job control")) continue;
            line = line.replaceFirst("^:?/\\s*#\\s*", "");
            if (line.isEmpty()) continue;
            if (line.equals("STATUS_DONE") || line.equals("START_DONE")
                    || line.equals("__NH_DONE__")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static final class Block {
        String pkg = "";
        String livePid = "";
        boolean ok;
        boolean stale;
    }

    private static ArrayList<Block> parseBlocks(String out) {
        ArrayList<Block> list = new ArrayList<>();
        Block cur = null;
        for (String line : out.split("\n")) {
            if (line.startsWith("=== ") && line.endsWith(" ===")) {
                cur = new Block();
                cur.pkg = line.substring(4, line.length() - 4);
                list.add(cur);
                continue;
            }
            if (cur == null) continue;
            if (line.startsWith("ok=")) cur.ok = line.equals("ok=1");
            if (line.startsWith("live_pid=")) cur.livePid = line.substring(9).trim();
            if (line.equals("stale=1")) cur.stale = true;
        }
        return list;
    }

    private static String summarize(ArrayList<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(b.pkg).append('=').append(
                    b.ok ? "ok" : (b.livePid.isEmpty() ? "off" : "bad"));
            if (b.stale) sb.append("(stale)");
        }
        return sb.toString();
    }

    /** 进程活着但状态是 bad/stale 才算异常；进程没起（livePid 为空）交给下一轮。 */
    private static boolean hasBad(ArrayList<Block> blocks) {
        for (Block b : blocks) {
            if (!b.ok && !b.livePid.isEmpty()) return true;
        }
        return false;
    }

    /** 只在状态变化时写日志；持续异常每 10 分钟提醒一次。 */
    private void logState(String state, String message) {
        if (state.equals(lastState)) {
            sameStateCount++;
            if (sameStateCount % 10 != 0) return;
        } else {
            lastState = state;
            sameStateCount = 0;
        }
        appendLog(message);
    }

    /** 开机自动注入完成后给一个常驻提示：优先浮层，点一下打开 App 才消失；
     *  没有浮层权限时退回通知，至少不会像 Toast 一样错过。 */
    private void maybeBootNotify(final boolean ok) {
        SharedPreferences p = getSharedPreferences("uh", MODE_PRIVATE);
        if (!p.getBoolean("boot_toast_pending", false)) return;
        p.edit().putBoolean("boot_toast_pending", false).apply();

        boolean overlay = false;
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                overlay = Settings.canDrawOverlays(this);
            } catch (Throwable ignored) {
            }
        }
        if (overlay) {
            showBootOverlay(ok);
        } else {
            postBootNotification(ok);
        }
    }

    private void showBootOverlay(final boolean ok) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    LinearLayout box = new LinearLayout(KeepAliveService.this);
                    box.setOrientation(LinearLayout.VERTICAL);
                    int padH = dp(26), padV = dp(18);
                    box.setPadding(padH, padV, padH, padV);
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(0xF21A1F2B);
                    bg.setCornerRadius(dp(16));
                    box.setBackground(bg);

                    TextView title = new TextView(KeepAliveService.this);
                    title.setText(ok ? "Native Hook 已开启" : "Native Hook 注入失败");
                    title.setTextColor(0xFFFFFFFF);
                    title.setTextSize(20);
                    title.setTypeface(Typeface.DEFAULT_BOLD);
                    box.addView(title);

                    TextView sub = new TextView(KeepAliveService.this);
                    sub.setText(ok ? "开机常驻注入成功 · 点击关闭"
                                   : "开机常驻注入失败 · 点击关闭");
                    sub.setTextColor(0xFFB9C0CC);
                    sub.setTextSize(14);
                    LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    subLp.topMargin = dp(6);
                    box.addView(sub, subLp);

                    box.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // 只关闭提示，不打开 App
                            removeBootOverlay();
                        }
                    });

                    WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                    if (wm == null) {
                        postBootNotification(ok);
                        return;
                    }
                    int type = Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            type,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.BOTTOM | Gravity.START;
                    lp.x = dp(40);
                    lp.y = dp(120);
                    wm.addView(box, lp);
                    bootOverlay = box;
                    bootOverlayWm = wm;
                    bootOverlayFailed = !ok;
                } catch (Throwable t) {
                    Log.w(TAG, "boot overlay failed: " + t);
                    postBootNotification(ok);
                }
            }
        });
    }

    private void removeBootOverlay() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (bootOverlay != null && bootOverlayWm != null) {
                        bootOverlayWm.removeView(bootOverlay);
                    }
                } catch (Throwable ignored) {
                }
                bootOverlay = null;
                bootOverlayWm = null;
                bootOverlayFailed = false;
            }
        });
    }

    private void postBootNotification(boolean ok) {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(this, MainActivity2.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, BOOT_NOTIFY_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, BOOT_CHANNEL);
        } else {
            b = new Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_DEFAULT);
        }
        b.setContentTitle(ok ? "Native Hook 已开启" : "Native Hook 注入失败")
                .setContentText(ok ? "开机常驻注入成功，点击打开 App"
                                   : "开机常驻注入失败，点击查看日志")
                .setSmallIcon(ok ? android.R.drawable.stat_sys_download_done
                                 : android.R.drawable.stat_notify_error)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .setWhen(System.currentTimeMillis());
        try {
            nm.notify(BOOT_NOTIFY_ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    private static String oneLine(String s) {
        String v = s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
        return v.length() > 500 ? v.substring(0, 500) : v;
    }

    private void inject(final String pid, final String label) {
        Log.i(TAG, "re-inject " + label + " pid=" + pid);
        appendLog("inject " + label + " pid=" + pid);
        updateNotification("重新注入 " + label);
        dm.exec(NativeHook.BIN + " " + pid, new DmClient.Callback() {
            @Override
            public void onResult(boolean ok, String out) {
                appendLog("inject " + label + " " + (ok ? "ok" : "failed")
                        + (out == null ? "" : ": " + out));
                updateNotification(ok ? "已注入 " + label : "注入失败 " + label);
            }
        });
    }

    private static boolean statusOk(String block, String pid, String process) {
        return block.contains("pid=" + pid)
                && block.contains("process=" + process)
                && block.contains("ok=1");
    }

    private void appendLog(String message) {
        synchronized (LOG_LOCK) {
            try {
                File dir = new File(getFilesDir(), "logs");
                if (!dir.exists()) dir.mkdirs();
                String day = new SimpleDateFormat("yyyyMMdd", Locale.US)
                        .format(new Date());
                File file = new File(dir, "keepalive-" + day + ".log");
                String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                        .format(new Date()) + " " + message + "\n";
                FileOutputStream fos = new FileOutputStream(file, true);
                try {
                    fos.write(line.getBytes("UTF-8"));
                } finally {
                    fos.close();
                }
                cleanupLogs(dir);
            } catch (Exception ignored) {
            }
        }
    }

    private void cleanupLogs(File dir) {
        final long cutoff = System.currentTimeMillis() - 24L * 3600 * 1000;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }

    private static String value(String out, String key) {
        int i = out.indexOf(key);
        if (i < 0) return "";
        i += key.length();
        int j = i;
        while (j < out.length()) {
            char c = out.charAt(j);
            if (c == '\n' || c == '\r' || c == ' ') break;
            j++;
        }
        return out.substring(i, j).trim();
    }

    private static String between(String out, String start, String end) {
        int i = out.indexOf(start);
        if (i < 0) return "";
        i += start.length();
        int j = out.indexOf(end, i);
        return j < 0 ? out.substring(i) : out.substring(i, j);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "NativeHook", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("保持 native hook 生效");
                nm.createNotificationChannel(ch);

                NotificationChannel boot = new NotificationChannel(
                        BOOT_CHANNEL, "NativeHook 开机结果",
                        NotificationManager.IMPORTANCE_DEFAULT);
                boot.setDescription("开机常驻注入结果，点击打开 App 后消失");
                boot.setSound(null, null);
                boot.enableVibration(false);
                nm.createNotificationChannel(boot);
            }
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("NativeHook")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        if (!running) return;
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFY_ID, buildNotification(text));
    }
}