package com.changan.nativehook;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Native payload 的部署与注入。
 *
 * 三个文件：nhinject（ptrace 注入器）、liblsplant.so、libnhpayload.so。
 * 部署到 /data/local/tmp/hook-native/，用 nhinject 依次注入 system_server
 * 和 com.vecentek.decoreapp。
 */
final class NativeHook {

    static final String DIR = "/data/local/tmp/hook-native";
    static final String BIN = DIR + "/nhinject";
    static final String LS = DIR + "/liblsplant.so";
    static final String PAYLOAD = DIR + "/libnhpayload.so";
    static final String MODULE_DIR = DIR + "/modules";
    static final String MODULE = MODULE_DIR + "/uninstall_guard.so";
    static final String STATUS_MODULE = MODULE_DIR + "/statusbar_touch.so";
    static final String PRESENTATION_MODULE = MODULE_DIR + "/hide_presentation.so";
    static final String ICCOA_MODULE = MODULE_DIR + "/iccoa_fullscreen.so";
    static final String RT_SHELL = DIR + "/rt_shell";
    static final String RT_HTTPD = DIR + "/rt_httpd";
    static final String START_SH = DIR + "/start.sh";
    static final String STATUS_SH = DIR + "/status.sh";
    static final String ENSURE_SH = DIR + "/ensure_services.sh";

    /**
     * 把空壳无障碍服务登记进 secure settings。
     *
     * 系统会长期 bind 已启用的无障碍服务，进程被杀/覆盖安装后自动重连，
     * 比 START_STICKY 可靠；这是「启源助手」一直在用的那套。
     * 走 root shell 直接写，不需要 App 自己拿 WRITE_SECURE_SETTINGS。
     * 重复执行无副作用：已经在列表里就跳过。
     */
    static final String ACC_CMD =
            "CUR=$(settings get secure enabled_accessibility_services 2>/dev/null)\n"
            + "case \"$CUR\" in\n"
            + "  *com.changan.nativehook*) ;;\n"
            + "  *) if [ -z \"$CUR\" ] || [ \"$CUR\" = \"null\" ]; then\n"
            + "       settings put secure enabled_accessibility_services "
            + "com.changan.nativehook/.KeepAliveAccessibilityService\n"
            + "     else\n"
            + "       settings put secure enabled_accessibility_services "
            + "\"$CUR:com.changan.nativehook/.KeepAliveAccessibilityService\"\n"
            + "     fi ;;\n"
            + "esac\n"
            + "settings put secure accessibility_enabled 1\n";
    static final String VERSION_FILE = DIR + "/.version";
    static final String CFG = "/data/system/uninstall-hook-native.cfg";
    static final String STATUS = "/data/system/nativehook-system_server.status";
    static final String SEC_STATUS = "/data/system/nativehook-com.vecentek.decoreapp.status";
    static final String UI_STATUS = "/data/system/nativehook-com.android.systemui.status";
    static final String VOICE_STATUS = "/data/system/nativehook-com.iflytek.autofly.voicecoreservice.status";
    static final String ICCOA_STATUS = "/data/system/nativehook-com.wt.phonelink.status";
    static final String LEGACY_STATUS = "/data/system/uninstall-hook-native.status";
    static final String LEGACY_SEC_STATUS = "/data/system/uninstall-hook-native-sec.status";
    // status.sh / start.sh / 配置里的注入进程列表，顺序无关。
    static final String INJECT_LIST = "system_server,com.vecentek.decoreapp,"
            + "com.android.systemui,com.iflytek.autofly.voicecoreservice,com.wt.phonelink";
    // 脚本或模块变化时提升，触发一次完整重部署。
    // 1.29：加入后摄记录仪（libevsdvr.so + evsdvr + boot/run.sh），
    //       并把部署改成清单驱动，不再手写 11 组 test/cp/chmod/verify。
    static final String VERSION = "1.29-native29";
    static final String SEC_PACKAGE = "com.vecentek.decoreapp";
    static final String UI_PACKAGE = "com.android.systemui";

    /**
     * 部署清单：{assets 里的路径, 车机上的相对路径, 权限}。
     *
     * 以前每个文件要手写 4 处（test -f 检查、cp 复制、chmod 授权、wc -c 校验），
     * 加一个文件改 4 个地方、漏一处就会「版本对得上但文件没释放」。
     * 改成表驱动后只改这里一行，其余全自动拼出来。
     *
     * 记录仪放在 evsdvr/ 子目录，和 hook-native 其它二进制隔离；
     * 它不进 modules/ —— modules 是给有 ART 的进程用的（依赖 LSPlant+Dobby），
     * 而记录仪跑在纯 native 的 evsserver 里，靠 GOT 覆盖，完全不同的路径。
     */
    static final String[][] ASSETS = {
            // assets 路径,                        目标相对路径,               权限
            {"native/nhinject",                    "nhinject",                 "755"},
            {"native/liblsplant.so",               "liblsplant.so",            "644"},
            {"native/libnhpayload.so",             "libnhpayload.so",          "644"},
            {"native/modules/uninstall_guard.so",  "modules/uninstall_guard.so",  "644"},
            {"native/modules/statusbar_touch.so",  "modules/statusbar_touch.so",  "644"},
            {"native/modules/hide_presentation.so","modules/hide_presentation.so","644"},
            {"native/modules/iccoa_fullscreen.so","modules/iccoa_fullscreen.so", "644"},
            {"native/rt_shell",                    "rt_shell",                 "755"},
            {"native/rt_httpd",                    "rt_httpd",                 "755"},
            {"native/nhkeepd",                     "nhkeepd",                  "755"},
            {"native/start.sh",                    "start.sh",                 "644"},
            {"native/status.sh",                   "status.sh",                "644"},
            {"native/ensure_services.sh",          "ensure_services.sh",       "644"},
            // 后摄记录仪
            {"native/libevsdvr.so",                "evsdvr/libevsdvr.so",      "644"},
            {"native/evsdvr",                      "evsdvr/evsdvr",            "755"},
            {"native/evsdvr-boot.sh",              "evsdvr/boot.sh",           "755"},
            {"native/evsdvr-run.sh",               "evsdvr/run.sh",            "755"},
    };

    /** 需要创建的目录（相对 DIR）。 */
    private static final String[] SUBDIRS = {"", "modules", "evsdvr"};

    /** 拍平后的 key 名，用于 shell 输出里的 size_xxx=ok 校验。 */
    private static String keyOf(String rel) {
        return rel.replace('/', '_').replace('.', '_').replace('-', '_');
    }

    static final String VOICE_PACKAGE = "com.iflytek.autofly.voicecoreservice";
    static final String ICCOA_PACKAGE = "com.wt.phonelink";

    private static final String[] DENY = {
            "android",
            "com.android.settings",
            "com.android.systemui",
            "com.android.launcher3",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.android.providers.settings",
            "com.android.providers.media",
            "com.android.shell",
            "com.android.phone",
            "com.android.bluetooth",
            "com.android.car",
            "com.android.carrierconfig",
            "com.incall.spm",
            "io.dushu.car",
            "com.iflytek.systemserver",
            "com.iflytek.launcher",
            "com.vecentek.decoreapp",
            "com.changan.nativehook"
    };

    interface Listener {
        void log(String s);

        void done(boolean ok, String message);
    }

    private NativeHook() {
    }

    /** 检查远端文件版本，不匹配则部署。 */
    static void ensureDeployed(final Context ctx, final DmClient dm, final Listener listener) {
        // 由 ASSETS 表自动拼出检查命令，新增文件不用再手改这里
        StringBuilder cmd = new StringBuilder("D=" + DIR + "\n");
        cmd.append("echo version=$(cat $D/.version 2>/dev/null)\n");
        for (String[] a : ASSETS) {
            String rel = a[1];
            String key = keyOf(rel);
            String test = "755".equals(a[2]) ? "test -x" : "test -f";
            cmd.append(test).append(" $D/").append(rel)
               .append(" && echo ").append(key).append("=yes || echo ")
               .append(key).append("=no\n");
        }
        dm.exec(cmd.toString(), new DmClient.Callback() {
            @Override
            public void onResult(boolean ok, String out) {
                listener.log("[native] 检查 " + (ok ? "OK" : "FAIL") + "\n" + out);
                boolean allPresent = ok && out != null
                        && out.contains("version=" + VERSION);
                if (allPresent) {
                    for (String[] a : ASSETS) {
                        if (!out.contains(keyOf(a[1]) + "=yes")) {
                            allPresent = false;
                            break;
                        }
                    }
                }
                if (allPresent) {
                    listener.done(true, "服务已就绪");
                } else {
                    deploy(ctx, dm, listener);
                }
            }
        });
    }

    /** 从 assets 部署到 /data/local/tmp/hook-native，分两步便于定位失败。 */
    static void deploy(final Context ctx, final DmClient dm, final Listener listener) {
        deployAttempt(ctx, dm, listener, 1);
    }

    /** “首次部署”按钮：强制释放全部文件并启动 4455/4466。 */
    static void deployAll(final Context ctx, final DmClient dm, final Listener listener) {
        deploy(ctx, dm, listener);
    }

    private static void deployAttempt(final Context ctx, final DmClient dm,
                                      final Listener listener, final int attempt) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 先把配置写出来（含 evs_dvr 等开关），后面 cp 到车机。
                    // 不写的话 ensure_services.sh 读不到 evs_dvr，记录仪不会启动。
                    try {
                        writeConfig(ctx);
                    } catch (Exception ignore) {
                        // 写失败不阻断部署，start() 时还会再补一次
                    }

                    // ---- 1. 按清单把 assets 释放到私有目录，同时记下每个文件的大小 ----
                    final java.util.List<Long> sizes = new java.util.ArrayList<Long>();
                    StringBuilder sb = new StringBuilder();
                    for (String[] a : ASSETS) {
                        // assets 里用完整路径，本地扁平化到 filesDir（同名冲突由 keyOf 规避）
                        File out = new File(ctx.getFilesDir(), keyOf(a[1]));
                        long n = copyAsset(ctx, a[0], out);
                        sizes.add(n);
                        sb.append(keyOf(a[1])).append('=').append(n).append(' ');
                    }
                    listener.log("[deploy] 本地文件就绪: " + sb);

                    // ---- 2. 拼复制命令：建目录 + cp + 写版本号 ----
                    final String root = ctx.getFilesDir().getAbsolutePath();
                    StringBuilder cp = new StringBuilder();
                    cp.append("D=").append(DIR).append('\n')
                      .append("S=").append(root).append('\n')
                      .append("sleep 1\n");
                    for (String d : SUBDIRS) {
                        String path = d.isEmpty() ? "$D" : "$D/" + d;
                        cp.append("mkdir -p ").append(path).append('\n')
                          .append("chmod 755 ").append(path).append('\n');
                    }
                    for (String[] a : ASSETS) {
                        cp.append("cp $S/").append(keyOf(a[1]))
                          .append(" $D/").append(a[1]).append('\n')
                          // 复制完立刻授权，不要只等 verifyCmd 那一步。
                          // 实测出现过 evsdvr 停在 600（其余文件都正常）导致
                          // boot.sh 的 [ -x ] 判定失败、记录仪起不来。
                          .append("chmod ").append(a[2])
                          .append(" $D/").append(a[1]).append('\n');
                    }
                    cp.append("echo ").append(VERSION).append(" > $D/.version\n")
                      .append("echo copy_done\n");
                    final String copyCmd = cp.toString();

                    final long t0 = System.currentTimeMillis();
                    dm.exec(copyCmd, new DmClient.Callback() {
                        @Override
                        public void onResult(boolean ok, String out) {
                            long cost = (System.currentTimeMillis() - t0) / 1000;
                            listener.log("[deploy] step1 复制 " + (ok ? "OK" : "FAIL")
                                    + " (" + cost + "s)\n" + out);
                            if (!ok) {
                                listener.done(false,
                                        "第 1 步复制失败（" + cost + "s）：" + out);
                                return;
                            }
                            final long t1 = System.currentTimeMillis();

                            // ---- 3. 拼校验命令：chmod + 逐个比对字节数 ----
                            StringBuilder vf = new StringBuilder();
                            // S 必须在这个脚本里重新定义：copyCmd 和 verifyCmd 是两次
                            // 独立的 dm.exec，环境变量不跨调用保留。漏了它 $S/... 会展开成
                            // /uninstall-hook-native.cfg，cp 静默失败，配置永远同步不过去。
                            vf.append("D=").append(DIR).append('\n')
                              .append("S=").append(root).append('\n')
                              .append("echo verify_start\n");
                            // chmod 必须逐个文件执行。
                            // 早期把 18 个文件拼成一条 chmod 755 a b c ... 命令，
                            // 实测在车机上整条被丢掉（文件停在 cp 时的 600 权限，
                            // 二进制不可执行、脚本也跑不了）。逐个来最可靠。
                            for (String[] a : ASSETS) {
                                vf.append("chmod ").append(a[2])
                                  .append(" $D/").append(a[1]).append('\n');
                            }
                            // 只有全部字节数都对上才写版本号。
                            // 以前无条件写：万一某个文件复制不全，本次虽然报失败，
                            // 但版本号已经是新的，下次启动 ensureDeployed 只看
                            // 「版本对上 + 文件存在」就判定已就绪，坏文件永远留着。
                            vf.append("BAD=0\n");
                            for (int i = 0; i < ASSETS.length; i++) {
                                String key = keyOf(ASSETS[i][1]);
                                vf.append("[ \"$(wc -c < $D/").append(ASSETS[i][1])
                                  .append(")\" = \"").append(sizes.get(i))
                                  .append("\" ] && echo size_").append(key)
                                  .append("=ok || { echo size_").append(key)
                                  .append("=bad; BAD=1; }\n");
                            }
                            vf.append("[ \"$BAD\" = \"0\" ] && echo ").append(VERSION)
                              .append(" > $D/.version\n")
                              .append("echo version_now=$(cat $D/.version 2>/dev/null)\n")
                              // 部署完也要把配置同步过去：ensure_services.sh 靠
                              // evs_dvr= 这个键决定要不要拉起记录仪，
                              // 缺了它记录仪永远起不来。
                              .append("cp $S/uninstall-hook-native.cfg ").append(CFG).append('\n')
                              .append("chown system:system ").append(CFG).append('\n')
                              .append("chmod 600 ").append(CFG).append('\n')
                              .append("echo cfg_evs=$(grep '^evs_dvr=' ")
                              .append(CFG).append(" 2>/dev/null)\n")
                              .append("sh $D/ensure_services.sh\n")
                              .append("sleep 1\n")
                              .append("echo keepd_running=$(ps -A | grep -c nhkeepd)\n")
                              .append("ls -ld $D $D/modules $D/evsdvr\n");
                            final String verifyCmd = vf.toString();

                            dm.exec(verifyCmd, new DmClient.Callback() {
                                @Override
                                public void onResult(boolean ok, String out) {
                                    long cost2 = (System.currentTimeMillis() - t1) / 1000;
                                    boolean good = ok && out != null
                                            && out.contains("version_now=" + VERSION);
                                    if (good) {
                                        for (String[] a : ASSETS) {
                                            if (!out.contains("size_" + keyOf(a[1]) + "=ok")) {
                                                good = false;
                                                break;
                                            }
                                        }
                                    }
                                    listener.log("[deploy] step2 校验 "
                                            + (good ? "OK" : "FAIL")
                                            + " (" + cost2 + "s)\n" + out);
                                    if (!good) {
                                        listener.done(false, ok
                                                ? "第 2 步校验失败：文件内容或权限不完整"
                                                : "第 2 步校验失败（" + cost2 + "s）：" + out);
                                        return;
                                    }
                                    listener.done(true, "服务已就绪");
                                }
                            }, 2);
                        }
                    }, 2);
                } catch (final Exception e) {
                    listener.done(false, "部署失败: " + e);
                }
            }
        }, "nh-deploy-" + attempt).start();
    }

    /** 注入 system_server 和 VecentekApp。 */
    static void start(final Context ctx, final DmClient dm, final Listener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File cfg = writeConfig(ctx);
                    if (FastChannel.isPortOpen(FastChannel.SHELL_PORT, 300)) {
                        try {
                            // 先把配置同步到车机：1.14 的 inject 列表和 iccoa 参数都在里面，
                            // 不复制的话 start.sh 会退回旧的四进程列表。
                            String prep = "cp " + cfg.getAbsolutePath() + " " + CFG + "\n"
                                    + "chown system:system " + CFG + "\n"
                                    + "chmod 600 " + CFG + "\n"
                                    // 开机浮层提示需要这个 appop；用 root 预先授予，
                                    // 否则 Settings.canDrawOverlays() 一直是 false。
                                    + "appops set com.changan.nativehook SYSTEM_ALERT_WINDOW allow\n"
                                    + ACC_CMD
                                    + "echo cfg_done\n";
                            listener.log("[fast] 配置\n" + FastChannel.exec(prep, 10000));
                            String out = FastChannel.exec("sh " + START_SH, 40000);
                            listener.log("[fast] 启动\n" + out);
                            String status = FastChannel.exec("sh " + STATUS_SH, 10000);
                            listener.log("[fast] 状态\n" + status);
                            boolean systemOk = status.contains("process=system_server")
                                    && status.contains("ok=1");
                            boolean voiceOk = status.contains("process=" + VOICE_PACKAGE);
                            listener.done(systemOk, systemOk
                                    ? (voiceOk ? "Native Hook 已开启（快速通道）"
                                               : "Native Hook 已开启；语音服务未注入")
                                    : "注入失败，请查看日志");
                            return;
                        } catch (Exception e) {
                            listener.log("[fast] 失败: " + e);
                        }
                    }
                    final String cmd = "D=" + DIR + "\n"
                            + "cp " + cfg.getAbsolutePath() + " " + CFG + "\n"
                            + "chown system:system " + CFG + "\n"
                            + "chmod 600 " + CFG + "\n"
                            + "sh $D/ensure_services.sh\n"
                            + "sleep 1\n"
                            + "sh $D/start.sh\n"
                            + "sh $D/status.sh\n";
                    dm.exec(cmd, new DmClient.Callback() {
                        @Override
                        public void onResult(boolean ok, String out) {
                            boolean systemOk = out != null
                                    && out.contains("process=system_server")
                                    && out.contains("ok=1");
                            boolean secOk = out != null
                                    && out.contains("process=" + SEC_PACKAGE);
                            boolean uiOk = out != null
                                    && out.contains("process=" + UI_PACKAGE);
                            boolean voiceOk = out != null
                                    && out.contains("process=" + VOICE_PACKAGE);
                            listener.log("[native] 启动 " + (ok ? "OK" : "FAIL") + "\n" + out);
                            if (systemOk) {
                                String msg = "Native Hook 已开启";
                                if (voiceOk) msg += "（安装 + 卸载 + 隐藏顶部黑带）";
                                else msg += "；语音服务未注入";
                                if (!secOk) msg += "；为辰安全未运行";
                                listener.done(true, msg);
                            } else {
                                listener.done(false, "注入失败，请查看日志");
                            }
                        }
                    });
                } catch (final Exception e) {
                    listener.done(false, "启动失败: " + e);
                }
            }
        }, "nh-start").start();
    }

    /** 删除部署文件和状态；不会移除已注入的内存 hook。 */
    static void cleanup(final DmClient dm, final Listener listener) {
        String cmd = "D=" + DIR + "\n"
                + "pkill -f \"$D/rt_shell\" 2>/dev/null\n"
                + "pkill -f \"$D/rt_httpd\" 2>/dev/null\n"
                + "pkill -f \"$D/session.log\" 2>/dev/null\n"
                + "rm -f $D/session.log $D/session.log.1 $D/session.log.2 2>/dev/null\n"
                + "rm -rf $D\n"
                + "rm -f " + STATUS + " " + SEC_STATUS + " " + UI_STATUS + " " + VOICE_STATUS
                + " " + ICCOA_STATUS + "\n"
                + "rm -f " + LEGACY_STATUS + " " + LEGACY_SEC_STATUS + "\n"
                + "echo cleaned\n"
                + "ls -ld $D 2>&1\n";
        dm.exec(cmd, new DmClient.Callback() {
            @Override
            public void onResult(boolean ok, String out) {
                listener.log("[cleanup] " + (ok ? "OK" : "FAIL") + "\n" + out);
                listener.done(ok, ok ? "部署文件和状态已清理" : "清理失败");
            }
        });
    }

    /** 读取模块目录和进程状态，优先走 4455。 */
    static void listModules(final DmClient dm, final Listener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (FastChannel.isPortOpen(FastChannel.SHELL_PORT, 300)) {
                    try {
                        String out = FastChannel.exec("sh " + STATUS_SH, 10000);
                        listener.log("[modules] " + out);
                        listener.done(true, "模块状态已读取");
                        return;
                    } catch (Exception e) {
                        listener.log("[modules] fast failed: " + e);
                    }
                }
                dm.exec("sh " + STATUS_SH, new DmClient.Callback() {
                    @Override
                    public void onResult(boolean ok, String out) {
                        listener.log("[modules] " + (ok ? "OK" : "FAIL") + "\n" + out);
                        listener.done(ok, ok ? "模块状态已读取" : "读取模块状态失败");
                    }
                });
            }
        }, "nh-modules").start();
    }

    /** 软重启 system_server，清除已注入的内存 hook。 */
    static void restartFramework(final DmClient dm, final Listener listener) {
        String cmd = "S=$(pidof system_server)\n"
                + "echo SPID=$S\n"
                + "[ -n \"$S\" ] && kill -9 $S\n"
                + "echo sent\n";
        dm.exec(cmd, new DmClient.Callback() {
            @Override
            public void onResult(boolean ok, String out) {
                listener.log("[restart] " + (ok ? "OK" : "FAIL") + "\n" + out);
                listener.done(true, "已发送重启；车机会短暂黑屏");
            }
        });
    }

    /**
     * 功能开关的默认值。用户可以在界面上改，存 SharedPreferences，写配置时覆盖默认。
     *
     * key 与模块 init 里读的配置键一一对应
     * （见 payload/modules 下各 module.cpp 里的 api-&gt;config("xxx")），
     * 改名字要两边一起改。
     */
    static final String[][] FEATURES = {
            // 配置键,        SharedPreferences 键,  显示名,          默认值, 说明, 默认隐藏
            {"install",       "fn_install",          "允许安装应用",   "1",
             "绕过和辰安全白名单校验，装第三方 apk 不再被拦",        "0"},
            {"uninstall",     "fn_uninstall",        "允许卸载应用",   "1",
             "绕过和辰安全白名单校验，可卸载系统预装应用",            "0"},
            // 下面两项默认隐藏：普通用户不需要，误开反而有副作用
            // （后摄记录仪占 6G；状态栏触摸可能干扰原车交互）。
            // 长按 / 连点 7 次标题「NativeHook」才显示，解锁后永久可见。
            {"statusbar",     "fn_statusbar",        "状态栏触摸",     "0",
             "系统状态栏下拉区可触摸，避免被车机自带的屏蔽层吃掉",    "1"},
            {"presentation",  "fn_presentation",     "状态栏透明",     "1",
             "消掉车机顶部 110px 黑带，让状态栏区域透明",             "0"},
            {"iccoa",         "fn_iccoa",            "ICCOA 全屏",     "1",
             "手机互联画面铺满全屏（com.wt.phonelink）",             "0"},
            {"evs_dvr",       "fn_evs_dvr",          "后摄记录仪",     "0",
             "从 evsserver 取原始帧录成 mp4，占约 6G 空间，默认关闭", "1"},
    };

    /** 该功能是否默认隐藏（需要解锁才显示）。 */
    static boolean featureHidden(String[] f) {
        return f.length > 5 && "1".equals(f[5]);
    }

    /**
     * 按 SharedPreferences 键反查 FEATURES 行。
     *
     * 界面可能过滤掉隐藏项，所以**不能再靠数组下标**把控件和配置对应起来
     * —— 一旦下标错位，开关就会写到别的键上。tag 存的是这个键，用它反查最稳。
     */
    static String[] featureByPrefKey(String prefKey) {
        if (prefKey == null) return null;
        for (String[] f : FEATURES) {
            if (prefKey.equals(f[1])) return f;
        }
        return null;
    }

    /** 读某个功能是否启用。 */
    static boolean featureOn(Context ctx, String prefKey, String defVal) {
        return ctx.getSharedPreferences("uh", Context.MODE_PRIVATE)
                .getBoolean(prefKey, "1".equals(defVal));
    }

    private static File writeConfig(Context ctx) throws IOException {
        StringBuilder sb = new StringBuilder();
        // uninstall / install 不再写死：它们已放进 FEATURES 表，
        // 由下面的循环按用户开关生成（以前固定为 1，界面上没法关）。
        sb.append("deny=");
        for (int i = 0; i < DENY.length; ++i) {
            if (i > 0) sb.append(',');
            sb.append(DENY[i]);
        }
        sb.append('\n');
        sb.append("sec_target=*\n");
        sb.append("inject=").append(INJECT_LIST).append('\n');

        // 功能开关：从用户设置读，不再硬编码。
        // 以前这里写死 statusbar=0 / presentation=1 / iccoa=1，
        // 每次同步配置都覆盖用户改动，界面也没法切。
        for (String[] f : FEATURES) {
            boolean on = featureOn(ctx, f[1], f[3]);
            sb.append(f[0]).append('=').append(on ? 1 : 0).append('\n');
        }
        sb.append("touch_panel=0\n");
        sb.append("iccoa_mode=adapt\n");
        // 视频流降一档到 1080x1440：它是面板 1440x1920 的 3/4，同样 4:3，
        // 放大回面板是 1.33 倍整数比缩放，不变形也不留边。
        // 编码端（手机）+ 解码端（车机 MtkOmxVenc）的像素数从 276 万降到 156 万，
        // 两头压力一起降——这是「手机和车机都卡」时性价比最高的一刀。
        // surface 保持与面板 1:1，本地不再做一次缩放；
        // dpi 按 320 * video_w / 1080 = 320，保证手机端 UI 物理尺寸不变，只降清晰度。
        sb.append("iccoa_video=1080x1440\n");
        sb.append("iccoa_surface=1440x1920\n");
        sb.append("iccoa_dpi=320\n");
        File f = new File(ctx.getFilesDir(), "uninstall-hook-native.cfg");
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(sb.toString().getBytes("UTF-8"));
        } finally {
            fos.close();
        }
        return f;
    }

    /** 开机常驻检测时也先同步配置，避免车机上留着没有 phonelink 的旧配置。 */
    /**
     * 只同步配置并跑一次 ensure_services.sh，不做完整重新部署。
     *
     * 用于「应用功能开关」：配置落地后，ensure_services.sh 会按 evs_dvr 的值
     * 决定要不要拉起/停掉记录仪，其余常驻服务它本来就幂等。
     */
    static void syncConfigAndServices(final Context ctx, final DmClient dm,
                                      final Listener listener) {
        // 先确保部署完整：没部署过的话 $D/ensure_services.sh 根本不存在，
        // 后面 cp 配置、跑脚本都会失败，用户只会看到一条含糊的报错。
        ensureDeployed(ctx, dm, new Listener() {
            @Override
            public void log(String s) {
                listener.log(s);
            }

            @Override
            public void done(boolean deployed, String message) {
                if (!deployed) {
                    listener.done(false, "服务未就绪，无法应用开关：" + message);
                    return;
                }
                doSyncConfigAndServices(ctx, dm, listener);
            }
        });
    }

    private static void doSyncConfigAndServices(final Context ctx, final DmClient dm,
                                                final Listener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File cfg = writeConfig(ctx);
                    final String cmd = "D=" + DIR + "\n"
                            + "cp " + cfg.getAbsolutePath() + " " + CFG + "\n"
                            + "chown system:system " + CFG + "\n"
                            + "chmod 600 " + CFG + "\n"
                            + "echo cfg_now:\n"
                            + "grep -E '^(install|uninstall|statusbar|presentation|iccoa|evs_dvr)=' " + CFG + "\n"
                            + "sh $D/ensure_services.sh\n"
                            + "sleep 2\n"
                            + "echo dvr=$(pgrep -f \"$D/evsdvr/run.sh\" | head -1)\n";
                    dm.exec(cmd, new DmClient.Callback() {
                        @Override
                        public void onResult(boolean ok, String out) {
                            listener.log("[apply] " + (ok ? "OK" : "FAIL") + "\n" + out);
                            if (!ok || out == null) {
                                listener.done(false, "同步配置失败，请查看日志");
                                return;
                            }
                            // 从输出里判断记录仪是否按预期启停
                            String dvrPid = "";
                            for (String line : out.split("\n")) {
                                line = line.trim();
                                if (line.startsWith("dvr=")) {
                                    dvrPid = line.substring(4).trim();
                                }
                            }
                            boolean wantDvr = featureOn(ctx, "fn_evs_dvr", "0");
                            boolean hasDvr = dvrPid.length() > 0;
                            String extra;
                            if (wantDvr && !hasDvr) {
                                extra = "；记录仪未启动（检查 evsdvr 是否已部署）";
                            } else if (!wantDvr && hasDvr) {
                                extra = "；记录仪仍在运行（可彻底重启车机，或手动停掉进程）";
                            } else {
                                extra = wantDvr ? "；记录仪运行中" : "";
                            }
                            listener.done(true, "配置已同步" + extra);
                        }
                    });   // 第三个参数是重试次数不是超时，别乱传
                } catch (final Exception e) {
                    listener.done(false, "同步配置失败: " + e);
                }
            }
        }, "nh-apply").start();
    }

    static String ensureAndSyncCommand(Context ctx) throws IOException {
        File cfg = writeConfig(ctx);
        return "cp " + cfg.getAbsolutePath() + " " + CFG + "\n"
                + "chown system:system " + CFG + "\n"
                + "chmod 600 " + CFG + "\n"
                + "appops set com.changan.nativehook SYSTEM_ALERT_WINDOW allow\n"
                + ACC_CMD
                + "sh " + ENSURE_SH + "\n";
    }

    private static long copyAsset(Context ctx, String name, File out) throws IOException {
        InputStream in = ctx.getAssets().open(name);
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buf = new byte[1024 * 1024];
        long total = 0;
        try {
            int r;
            while ((r = in.read(buf)) > 0) {
                fos.write(buf, 0, r);
                total += r;
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            try {
                fos.close();
            } catch (IOException ignored) {
            }
        }
        return total;
    }
}