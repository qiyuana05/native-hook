package com.changan.nativehook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DM01 root 通道客户端。
 *
 * 参考 root-term 的 DmClient：通过 startService(com.incall.spm/.DMService)
 * 投递 {"msgType":"DM01","cmd":"..."}，由系统侧 ca.car.spm daemon 以 root 执行。
 *
 * 稳定性要点（1.12）：
 *  - 全进程串行：MainActivity 与常驻服务共用 static 锁和同一结果文件，
 *    并发投递会互相截断，必须排队；
 *  - 轮次标记带全局序号 + 随机后缀：App 重启后旧结果文件里的标记不会被误认；
 *  - 暂存文件固定短路径：可见命令保持 <200 字符，避免被 DM 过滤器丢弃；
 *  - 投递失败自动重投：部署等幂等脚本可直接重试，不用用户再点一次。
 */
public class DmClient {

    public interface Callback {
        void onResult(boolean ok, String output);
    }

    private static final String DM_PACKAGE = "com.incall.spm";
    private static final String DM_SERVICE = "com.incall.spm.DMService";
    private static final int B64_CHUNK = 128;          // 4 的倍数，命令仍 <200 字符
    private static final long ROUND_GAP_MS = 700;      // DMService 单线程 FIFO
    private static final long POLL_INTERVAL_MS = 400;
    private static final long TIMEOUT_MS = 60000;
    private static final long RETRY_GAP_MS = 1500;

    /** 跨实例串行：MainActivity 和 KeepAliveService 各有一个 DmClient。 */
    private static final Object STAGE_LOCK = new Object();
    /** 跨实例全局序号，保证暂存文件和轮次标记不重复。 */
    private static final AtomicInteger SEQ =
            new AtomicInteger((int) (System.currentTimeMillis() & 0x7fffffff));

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final String outFile;

    public DmClient(Context ctx) {
        this.app = ctx.getApplicationContext();
        File base = app.getExternalFilesDir(null);
        if (base == null) {
            base = app.getFilesDir();
        }
        File dir = new File(base, "uh");
        dir.mkdirs();
        this.outFile = new File(dir, "dm_out.txt").getAbsolutePath();
    }

    /** 异步执行一条 root 命令，结果回调到主线程。 */
    public void exec(final String cmd, final Callback cb) {
        exec(cmd, cb, 1);
    }

    /**
     * 异步执行，失败时自动重新投递。
     * 只给幂等脚本（cp/chmod/校验）用；start.sh 这类重复执行有副作用的不要用。
     */
    public void exec(final String cmd, final Callback cb, final int attempts) {
        final int n = SEQ.getAndIncrement();
        final String tag = "NH" + n + "_" + Integer.toHexString(
                (int) (System.nanoTime() & 0xffff));
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                String out = null;
                synchronized (STAGE_LOCK) {
                    String marker = "__" + tag + "_END__";
                    try {
                        for (int a = 0; a < attempts; a++) {
                            if (a > 0) {
                                sleepQuietly(RETRY_GAP_MS);
                            }
                            error = null;
                            out = null;
                            try {
                                runStaged(cmd, marker, n);
                            } catch (Throwable t) {
                                error = "投递失败: " + t;
                                continue;
                            }
                            out = pollForMarker(marker);
                            if (out != null) {
                                break;
                            }
                            error = timeoutDetail();
                        }
                    } finally {
                        // 无论成功还是超时，都必须清掉 sticky 状态。
                        // 放在 finally 里：中途抛异常也不能把雷留下。
                        stopDm();
                    }
                }
                final boolean ok = error == null;
                final String text = ok ? out : error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(ok, text);
                    }
                });
            }
        }, "uh-dm-" + n).start();
    }

    /**
     * 分块暂存：脚本 base64 切块，多轮短命令 echo 追加到 daemon 侧暂存文件，
     * 末轮解码执行。DM01 可见层永远只有 <200 字符的 echo/base64/sh。
     */
    private void runStaged(String cmd, String marker, int n) throws Exception {
        String stageB64 = "/data/local/tmp/uh_" + android.os.Process.myPid() + "_s.b64";
        String stageSh = "/data/local/tmp/uh_" + android.os.Process.myPid() + "_s.sh";

        // 清掉上一轮结果，避免任何形式的旧标记干扰
        new File(outFile).delete();

        String script = "exec > " + outFile + " 2>&1\n"
                + cmd
                + "\nrm -f " + stageB64 + " " + stageSh
                + "\necho " + marker + " >> " + outFile;
        String b64 = Base64.encodeToString(script.getBytes("UTF-8"), Base64.NO_WRAP);
        int rounds = (b64.length() + B64_CHUNK - 1) / B64_CHUNK;

        for (int i = 0; i <= rounds; i++) {
            String visible;
            if (i < rounds) {
                String chunk = b64.substring(i * B64_CHUNK,
                        Math.min((i + 1) * B64_CHUNK, b64.length()));
                visible = "echo -n " + chunk + (i == 0 ? " > " : " >> ") + stageB64;
            } else {
                visible = "base64 -d " + stageB64 + " > " + stageSh + "; sh " + stageSh;
            }
            sendRound(visible, n, i);
            Thread.sleep(ROUND_GAP_MS);
        }
    }

    /**
     * 清掉 DMService 的 started 状态。
     *
     * <p>DMService 是 {@code START_STICKY}，而它的 {@code onStartCommand}
     * 直接 {@code intent.getStringExtra("data")} 没有判空。只要有人 startService
     * 之后不 stop，spm 进程一旦被系统回收，AMS 就会用 <b>null intent</b> 重建它，
     * 立刻 NPE 崩溃、再被贴回、再崩，每秒 7~8 次，把 logd/dropbox 全占满。
     *
     * <p>实测证据：车机 {@code /resources/incalllog/SPM/crash_info} 里从
     * 2026-08-20 起累积了 2 万多份同一条堆栈。所以命令执行完必须显式 stop，
     * 不能把 sticky 状态留在系统里。
     *
     * <p>stop 只是清 started 状态，不影响后续按需调用——下一次 DM 指令
     * （包括车机自己的云端 DM）重新 startService 就能用。
     */
    private void stopDm() {
        try {
            Intent i = new Intent();
            i.setClassName(DM_PACKAGE, DM_SERVICE);
            app.stopService(i);
        } catch (Throwable ignored) {
            // 停不掉不影响命令结果，最多少清一次状态
        }
    }

    private void sendRound(String visibleCmd, int n, int round) throws Exception {
        Intent i = new Intent();
        i.setClassName(DM_PACKAGE, DM_SERVICE);
        i.putExtra("data", "{\"msgType\":\"DM01\",\"msgId\":\"nh-" + n + "-" + round
                + "\",\"systemId\":\"uninstall-hook\",\"cmd\":"
                + jsonQuote(visibleCmd) + "}");
        ComponentName started = app.startService(i);
        if (started == null) {
            throw new IllegalStateException("startService 返回 null，DMService 不可达");
        }
    }

    private String pollForMarker(String marker) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String content = readFile(new File(outFile));
            if (content != null) {
                int idx = content.lastIndexOf(marker);
                if (idx >= 0) {
                    return content.substring(0, idx).trim();
                }
            }
            sleepQuietly(POLL_INTERVAL_MS);
        }
        return null;
    }

    /** 超时后给出可区分的原因，便于判断是没投递还是脚本执行失败。 */
    private String timeoutDetail() {
        File f = new File(outFile);
        if (!f.exists()) {
            return "超时：结果文件未生成——DMService 没有执行本轮脚本"
                    + "（服务不可达或命令被丢弃）";
        }
        String content = readFile(f);
        String tail = content == null ? "" : content.length() > 160
                ? content.substring(content.length() - 160) : content;
        return "超时：结果文件 " + f.length() + " 字节但没有本轮标记，末尾: "
                + tail.replace('\n', ' ');
    }

    static String readFile(File f) {
        FileInputStream in = null;
        try {
            if (!f.exists()) {
                return null;
            }
            byte[] buf = new byte[(int) Math.min(f.length(), 512 * 1024)];
            in = new FileInputStream(f);
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n) : "";
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String jsonQuote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}