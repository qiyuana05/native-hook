package com.changan.nativehook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Date;
import java.util.Locale;

/**
 * v2.2 精简界面：只保留开启/关闭 Hook、启动/停止文件管理、日/夜切换。
 * 开启 Hook = 按「功能开关」里勾选的项目放行（保护名单仍生效）。
 */
public class MainActivity2 extends Activity {

    private static final String HOOK_DIR = "/data/local/tmp/hook";
    private static final String STAGE_DIR = "/data/local/tmp/hook_next";
    private static final String REMOTE_BIN = HOOK_DIR + "/frida-inject";
    private static final String REMOTE_JS = HOOK_DIR + "/uninstall-hook.js";
    private static final String REMOTE_SEC_JS = HOOK_DIR + "/sec-guard.js";
    private static final String REMOTE_VER = HOOK_DIR + "/.version";
    private static final String REMOTE_LOG = HOOK_DIR + "/uninstall-hook.log";
    private static final String REMOTE_SEC_LOG = HOOK_DIR + "/sec-guard.log";
    private static final String REMOTE_CFG = "/data/system/uninstall-hook-config.json";
    private static final String SEC_PACKAGE = "com.vecentek.decoreapp";

    private static final String HELPER_VERSION = "16.7.19-uh9";

    /** 隐藏功能是否已解锁。存在 prefs 里，解锁后永久显示。 */
    private static final String PREF_UNLOCKED = "fn_unlocked";

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
            "com.iflytek.launcher"
    };

    private DmClient dm;
    private SharedPreferences prefs;

    private TextView status;
    private TextView ipText;
    private TextView logText;
    private ScrollView logScroll;
    private Button btnLogMode;
    private Button btnLogFull;
    private Button btnLogExport;
    private Button btnLogCapture;
    private Button btnStart;
    private Button btnStop;
    private Button btnDeploy;
    private Button btnCleanup;
    private Button btnRestart;
    private Button btnKeepAlive;
    private Button btnModules;
    private Button btnLogs;
    private Button btnTheme;
    private Button btnApplyFn;
    private Button btnHelp;
    /** 功能开关控件。顺序 == 界面上实际渲染的顺序，**不等于** FEATURES 的下标。 */
    private final List<ToggleSwitch> featureSwitches = new ArrayList<ToggleSwitch>();

    private boolean busy;
    private boolean hookRunning;
    private boolean keepAlive;
    private boolean verbose;
    private boolean night;
    private boolean receiverRegistered;
    /** 直接盯 Settings.Secure 里的 ui_night_mode，比只等广播可靠。 */
    private ContentObserver nightObserver;

    private final StringBuilder rawLog = new StringBuilder();
    private final StringBuilder simpleLog = new StringBuilder();

    private int cBg;
    private int cCard;
    private int cText;
    private int cSub;
    private int cPrimary;
    private int cDanger;
    private int cOk;
    private int cOff;
    private int cChip;
    private int cLine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dm = new DmClient(this);
        prefs = getSharedPreferences("uh", MODE_PRIVATE);
        verbose = prefs.getBoolean("log_verbose", false);
        // 三态：0=自动跟随车机，1=强制日间，2=强制夜间。
        // 以前只有「手动/自动」两态，点过一次日夜按钮就永久停在手动，
        // 再也跟不上车机切换（prefs 里 manual_night 一旦为 true 就再也不判自动）。
        night = resolveNight();
        hookRunning = prefs.getBoolean("hook_running", false);
        keepAlive = prefs.getBoolean("keep_alive", false);
        // 换成 NoActionBar 主题后窗口底色由系统决定，先按当前日夜刷一次，
        // 否则夜间启动会先白闪一帧。
        applyColors();
        getWindow().setBackgroundDrawable(new ColorDrawable(cBg));
        buildUi();
        refreshIp();
        if (keepAlive) {
            startKeepAliveService();
        }
        simple("等待操作");
    }

    private void buildUi() {
        applyColors();

        // 车机是 1440x1920、density 160（1dp = 1px），dp 值就等于像素。
        // 所以这里的字号/高度比手机上的习惯值大一倍多，否则在车里根本看不清。
        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(cBg);
        root.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(36);
        // 顶上必须让出车机状态栏的高度：换成 NoActionBar 主题后内容会直接
        // 顶到状态栏那一层，右上角的日夜按钮会被时间和系统图标压住。
        int topInset = Math.max(dp(28), UiSafeArea.TOP_INSET_PX + dp(20));
        // 底部也要留出来，否则最后一块内容会被车机底部导航栏压住
        col.setPadding(pad, topInset, pad, UiSafeArea.BOTTOM_INSET_PX + dp(28));
        root.addView(col, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---------------- 标题栏 ----------------
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        // 隐藏功能的解锁入口就挂在这个标题上（长按 / 连点 7 次）。
        TextView title = text("NativeHook", 48, cText, true);
        titles.addView(title);
        titles.addView(text("LSPlant + Dobby · 安装 / 卸载放行", 20, cSub, false));
        bindHiddenUnlock(title);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnTheme = styledButton(themeLabel(), cChip, cLine, cText, 22);
        header.addView(btnTheme, new LinearLayout.LayoutParams(dp(170), dp(88)));
        col.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---------------- 状态大卡片 ----------------
        LinearLayout statusCard = cardBox(36, 30);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scLp.topMargin = dp(28);
        col.addView(statusCard, scLp);

        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // 状态圆点
        View dot = new View(this);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(hookRunning ? cOk : cSub);
        dot.setBackground(dg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        dotLp.rightMargin = dp(18);
        stateRow.addView(dot, dotLp);

        status = text(hookRunning ? "运行中" : "未开启", 44, hookRunning ? cOk : cSub, true);
        stateRow.addView(status, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusCard.addView(stateRow);

        TextView statusSub = text("开启后按下方开关放行对应操作；保护名单始终生效。",
                20, cSub, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(10);
        statusCard.addView(statusSub, subLp);

        ipText = text("", 18, cSub, false);
        LinearLayout.LayoutParams ipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ipLp.topMargin = dp(8);
        statusCard.addView(ipText, ipLp);

        // 主操作：两个大按钮并排
        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = dp(24);
        statusCard.addView(actRow, actLp);

        btnStart = styledButton("开启 Hook", cPrimary, Color.TRANSPARENT, Color.WHITE, 20);
        btnStart.setTextSize(30);
        btnStart.setText(hookRunning ? "已开启" : "开启 Hook");
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, dp(112), 1f);
        b1.rightMargin = dp(18);
        actRow.addView(btnStart, b1);

        btnStop = styledButton("关闭 Hook", Color.TRANSPARENT, cLine, cText, 20);
        btnStop.setTextSize(30);
        actRow.addView(btnStop, new LinearLayout.LayoutParams(0, dp(112), 1f));

        // 首次使用帮助：紧贴状态卡片，第一次打开就看得见。
        // 故意不放进「维护」——那里全是排障用的，新手不会往下翻。
        btnHelp = styledButton("首次使用帮助", cCard, cLine, cPrimary, 20);
        btnHelp.setTextSize(26);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(104));
        helpLp.topMargin = dp(20);
        col.addView(btnHelp, helpLp);

        // ---------------- 功能开关 ----------------
        col.addView(sectionTitle("功能开关"));

        TextView fnTip = text(hiddenUnlocked()
                ? "记录仪改完立即生效；其余开关需软重启或彻底重启车机"
                : "开关改完需软重启或彻底重启车机才生效", 18, cSub, false);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = dp(2);
        col.addView(fnTip, tipLp);

        // 2 列卡片网格
        featureSwitches.clear();
        // 先把要显示的项挑出来。默认隐藏的项（见 NativeHook.featureHidden）
        // 只有解锁后才参与布局 —— 所以下面**不能**再用 FEATURES 的下标，
        // 过滤之后下标就和控件列表错开了，开关会写到别的配置键上。
        List<String[]> shown = new ArrayList<String[]>();
        for (String[] f : NativeHook.FEATURES) {
            if (NativeHook.featureHidden(f) && !hiddenUnlocked()) {
                continue;
            }
            shown.add(f);
        }
        LinearLayout grid = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % 2 == 0) {
                grid = new LinearLayout(this);
                grid.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                glp.topMargin = dp(18);
                col.addView(grid, glp);
            }
            LinearLayout card = featureCard(shown.get(i));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 2 == 0) {
                clp.rightMargin = dp(18);
            }
            grid.addView(card, clp);
        }

        btnApplyFn = styledButton("应用开关", cPrimary, Color.TRANSPARENT, Color.WHITE, 20);
        btnApplyFn.setTextSize(30);
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(112));
        applyLp.topMargin = dp(24);
        col.addView(btnApplyFn, applyLp);

        // ---------------- 维护 ----------------
        col.addView(sectionTitle("维护"));

        LinearLayout mRow1 = new LinearLayout(this);
        mRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams m1Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        m1Lp.topMargin = dp(18);
        col.addView(mRow1, m1Lp);

        btnDeploy = styledButton("首次部署", Color.TRANSPARENT, cPrimary, cPrimary, 20);
        btnDeploy.setTextSize(26);
        btnCleanup = styledButton("清理部署", Color.TRANSPARENT, cLine, cText, 20);
        btnCleanup.setTextSize(26);
        btnRestart = styledButton("重启框架", Color.TRANSPARENT, cDanger, cDanger, 20);
        btnRestart.setTextSize(26);
        LinearLayout.LayoutParams m1a = new LinearLayout.LayoutParams(0, dp(104), 1f);
        m1a.rightMargin = dp(16);
        LinearLayout.LayoutParams m1b = new LinearLayout.LayoutParams(0, dp(104), 1f);
        m1b.rightMargin = dp(16);
        mRow1.addView(btnDeploy, m1a);
        mRow1.addView(btnCleanup, m1b);
        mRow1.addView(btnRestart, new LinearLayout.LayoutParams(0, dp(104), 1f));

        LinearLayout mRow2 = new LinearLayout(this);
        mRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams m2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        m2Lp.topMargin = dp(16);
        col.addView(mRow2, m2Lp);

        btnModules = styledButton("查看模块", Color.TRANSPARENT, cLine, cText, 20);
        btnModules.setTextSize(26);
        btnLogs = styledButton("守护日志", Color.TRANSPARENT, cLine, cText, 20);
        btnLogs.setTextSize(26);
        btnKeepAlive = styledButton(keepAlive ? "常驻检测：开" : "常驻检测：关",
                Color.TRANSPARENT, keepAlive ? cPrimary : cLine,
                keepAlive ? cPrimary : cSub, 20);
        btnKeepAlive.setTextSize(26);
        LinearLayout.LayoutParams m2a = new LinearLayout.LayoutParams(0, dp(104), 1f);
        m2a.rightMargin = dp(16);
        LinearLayout.LayoutParams m2b = new LinearLayout.LayoutParams(0, dp(104), 1f);
        m2b.rightMargin = dp(16);
        mRow2.addView(btnModules, m2a);
        mRow2.addView(btnLogs, m2b);
        mRow2.addView(btnKeepAlive, new LinearLayout.LayoutParams(0, dp(104), 1f));

        btnLogFull = styledButton("查看运行日志", Color.TRANSPARENT, cLine, cText, 20);
        btnLogFull.setTextSize(28);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(104));
        logLp.topMargin = dp(16);
        col.addView(btnLogFull, logLp);

        // 版本
        // 1.30~1.35 只改配置/界面，native payload 仍是 1.29，
        // 所以 NativeHook.VERSION 不动，避免白白重部署 17 个文件。
        TextView ver = text("v1.35 · native 1.29", 16, cSub, false);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        verLp.topMargin = dp(24);
        col.addView(ver, verLp);

        setContentView(root);

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startHook();
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopHook();
            }
        });
        btnDeploy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deployAll();
            }
        });
        btnCleanup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cleanupDeploy();
            }
        });
        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRestartFramework();
            }
        });
        btnKeepAlive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleKeepAlive();
            }
        });
        btnApplyFn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyFeatures();
            }
        });
        btnModules.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModules();
            }
        });
        btnLogs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showKeepAliveLogs();
            }
        });
        btnTheme.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTheme();
            }
        });
        btnLogFull.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogDialog();
            }
        });
        btnHelp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHelp();
            }
        });

        setBusy(false);
    }

    /** 区块标题：左边一条竖色条 + 文字。 */
    private TextView sectionTitle(String s) {
        TextView t = text(s, 32, cText, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(36);
        lp.bottomMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    /** 大圆角卡片容器。 */
    private LinearLayout cardBox(int padDp, int radiusDp) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp));
        GradientDrawable g = new GradientDrawable();
        g.setColor(cCard);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), cLine);
        l.setBackground(g);
        return l;
    }

    /** 功能开关卡片：标题 + 开关 + 说明。 */
    private LinearLayout featureCard(final String[] f) {
        LinearLayout card = cardBox(26, 24);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        top.addView(text(f[2], 28, cText, true), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final ToggleSwitch sw = new ToggleSwitch(this,
                NativeHook.featureOn(this, f[1], f[3]),
                cPrimary, cOff, Color.WHITE);
        sw.setTag(f[1]);
        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 状态翻转由 ToggleSwitch.performClick 负责，这里不要再翻一次
            }
        });
        featureSwitches.add(sw);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(56));
        swLp.leftMargin = dp(16);
        top.addView(sw, swLp);
        card.addView(top);

        TextView hint = text(f[4], 18, cSub, false);
        hint.setSingleLine(false);
        hint.setMaxLines(2);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(8);
        card.addView(hint, hLp);

        return card;
    }

    /** 隐藏功能是否已解锁。存在 prefs 里，解锁一次后永久显示。 */
    private boolean hiddenUnlocked() {
        return prefs.getBoolean(PREF_UNLOCKED, false);
    }

    /**
     * 隐藏功能的解锁手势：长按标题，或 2 秒内连点 7 次。
     *
     * 为什么要把「状态栏触摸」「后摄记录仪」藏起来：
     * 前者可能干扰原车状态栏交互，后者一开就占约 6G 空间，
     * 都不是日常要点的开关，放在外面容易被误触。
     */
    private void bindHiddenUnlock(View v) {
        // TextView 本身不可点，设了 OnClickListener 后自动变 clickable，
        // 长按才能同时生效（长按触发 onLongClick 时不会再走 onClick）。
        v.setClickable(true);
        final int[] taps = {0};
        final long[] lastAt = {0};
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                unlockHidden();
                return true;
            }
        });
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                long now = SystemClock.uptimeMillis();
                // 超过 2 秒算断档重新计数，避免几次误点累加成解锁
                if (now - lastAt[0] > 2000) {
                    taps[0] = 0;
                }
                lastAt[0] = now;
                taps[0]++;
                if (taps[0] >= 7) {
                    taps[0] = 0;
                    unlockHidden();
                }
            }
        });
    }

    private void unlockHidden() {
        if (hiddenUnlocked()) {
            Toast.makeText(this, "隐藏功能已显示", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putBoolean(PREF_UNLOCKED, true).apply();
        Toast.makeText(this, "已显示隐藏功能", Toast.LENGTH_SHORT).show();
        // 直接重建界面，比动态往网格里插卡片省事，也不会漏掉 setBusy 之类的状态
        recreate();
    }


    private void applyColors() {
        if (night) {
            cBg = 0xFF0F131B;
            cCard = 0xFF171C26;
            cText = 0xFFE8ECF4;
            cSub = 0xFF9AA3B2;
            cPrimary = 0xFF6D8BFF;
            cDanger = 0xFFFF6B70;
            cLine = 0xFF28303D;
            cOk = 0xFF3DD68C;
            cOff = 0xFF2A3242;
            cChip = 0xFF1E2532;
        } else {
            cBg = 0xFFF4F6FB;
            cCard = 0xFFFFFFFF;
            cText = 0xFF172033;
            cSub = 0xFF6B7280;
            cPrimary = 0xFF3B6CF6;
            cDanger = 0xFFE5484D;
            cLine = 0xFFE6EAF3;
            cOk = 0xFF12A150;
            cOff = 0xFFD8DDE8;
            cChip = 0xFFEDF0F7;
        }
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) {
            v.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return v;
    }

    private Button styledButton(String s, int fill, int stroke, int color, int radiusDp) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(color);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT) {
            g.setStroke(dp(1), stroke);
        }
        b.setBackground(g);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinHeight(dp(56));
        b.setStateListAnimator(null);
        return b;
    }

    /** 车机上按钮需要更大的触摸区域，统一高度和间距。 */
    private void addButton(LinearLayout parent, Button button, int heightDp,
                           int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp));
        lp.topMargin = dp(topMarginDp);
        parent.addView(button, lp);
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(cCard);
        g.setCornerRadius(dp(18));
        l.setBackground(g);
        l.setElevation(dp(2));
        return l;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(cLine);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        v.setLayoutParams(lp);
        return v;
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    private void setBusy(boolean b) {
        busy = b;
        btnStart.setEnabled(!b && !hookRunning);
        btnStart.setText(hookRunning ? "已开启" : "开启 Hook");
        btnStop.setEnabled(!b && hookRunning);
        btnDeploy.setEnabled(!b);
        btnCleanup.setEnabled(!b);
        btnRestart.setEnabled(!b);
        btnKeepAlive.setEnabled(!b);
        btnModules.setEnabled(!b);
        btnLogs.setEnabled(!b);
        btnTheme.setEnabled(!b);
        btnApplyFn.setEnabled(!b);
        for (ToggleSwitch sw : featureSwitches) sw.setEnabled(!b);
        // 日志控件已移到全屏弹窗里按需创建，这里必须判空
        if (btnLogExport != null) btnLogExport.setEnabled(!b);
        if (btnLogCapture != null) btnLogCapture.setEnabled(!b);
        if (btnLogMode != null) btnLogMode.setEnabled(!b);
        btnLogFull.setEnabled(!b);
        btnStart.setAlpha((b || hookRunning) ? 0.45f : 1f);
        btnStop.setAlpha((b || !hookRunning) ? 0.45f : 1f);
        btnDeploy.setAlpha(b ? 0.45f : 1f);
        btnCleanup.setAlpha(b ? 0.45f : 1f);
        btnRestart.setAlpha(b ? 0.45f : 1f);
        btnKeepAlive.setAlpha(b ? 0.45f : 1f);
        btnModules.setAlpha(b ? 0.45f : 1f);
        btnLogs.setAlpha(b ? 0.45f : 1f);
        btnTheme.setAlpha(b ? 0.45f : 1f);
        btnApplyFn.setAlpha(b ? 0.45f : 1f);
    }

    private static final int THEME_AUTO = 0;
    private static final int THEME_DAY = 1;
    private static final int THEME_NIGHT = 2;

    /** 当前主题模式。老版本只有 manual_night/night 两个布尔，这里做一次兼容。 */
    private int themeMode() {
        if (prefs.contains("theme_mode")) {
            return prefs.getInt("theme_mode", THEME_AUTO);
        }
        if (prefs.getBoolean("manual_night", false)) {
            return prefs.getBoolean("night", false) ? THEME_NIGHT : THEME_DAY;
        }
        return THEME_AUTO;
    }

    /** 按当前模式算出该用日间还是夜间。 */
    private boolean resolveNight() {
        int m = themeMode();
        if (m == THEME_NIGHT) return true;
        if (m == THEME_DAY) return false;
        return DayNight.isNight(this);
    }

    private String themeLabel() {
        int m = themeMode();
        if (m == THEME_DAY) return "日间";
        if (m == THEME_NIGHT) return "夜间";
        return "自动";
    }

    /** 点一次切换：自动 → 日间 → 夜间 → 自动。 */
    /** 自动模式下，若车机日夜变了就重建界面。 */
    private void applyAutoNight() {
        if (themeMode() != THEME_AUTO) return;
        boolean auto = DayNight.isNight(this);
        if (auto != night) {
            night = auto;
            recreate();
        }
    }

    private void toggleTheme() {
        if (busy) return;
        int m = (themeMode() + 1) % 3;
        prefs.edit().putInt("theme_mode", m).apply();
        recreate();
    }

    private final BroadcastReceiver dayNightReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            applyAutoNight();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        refreshIp();
        if (!receiverRegistered) {
            registerReceiver(dayNightReceiver,
                    new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED));
            receiverRegistered = true;
        }
        // 车机切日夜通常是改 Settings.Secure 的 ui_night_mode，
        // 不一定伴随 ACTION_CONFIGURATION_CHANGED，所以直接盯这个键。
        if (nightObserver == null) {
            nightObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean self) {
                    applyAutoNight();
                }
            };
        }
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor("ui_night_mode"), false, nightObserver);
        applyAutoNight();
    }

    @Override
    protected void onPause() {
        if (nightObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(nightObserver);
            } catch (Exception ignored) {
            }
        }
        if (receiverRegistered) {
            unregisterReceiver(dayNightReceiver);
            receiverRegistered = false;
        }
        super.onPause();
    }

    /**
     * 运行日志改成独立全屏页。
     * 车机是 1440x1920 竖屏，原来左右分栏两边都挤；现在主界面只留按钮，
     * 日志按需全屏打开。控件在这里懒创建，所以 renderLog() 要容忍 null。
     */
    private void showLogDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(24), dp(28), dp(16));
        box.setBackgroundColor(cBg);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.addView(text("运行日志", 28, cText, true),
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnLogMode = styledButton(verbose ? "原生" : "简化",
                Color.TRANSPARENT, cLine, cText, 16);
        btnLogMode.setTextSize(20);
        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(dp(120), dp(76));
        m1.rightMargin = dp(12);
        bar.addView(btnLogMode, m1);

        btnLogExport = styledButton("导出", Color.TRANSPARENT, cLine, cText, 16);
        btnLogExport.setTextSize(20);
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(dp(120), dp(76));
        m2.rightMargin = dp(12);
        bar.addView(btnLogExport, m2);

        btnLogCapture = styledButton("抓取", Color.TRANSPARENT, cLine, cText, 16);
        btnLogCapture.setTextSize(20);
        bar.addView(btnLogCapture, new LinearLayout.LayoutParams(dp(120), dp(76)));
        box.addView(bar);

        logText = text("", 18, cText, false);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextIsSelectable(true);
        logText.setMovementMethod(new ScrollingMovementMethod());

        logScroll = new ScrollView(this);
        logScroll.addView(logText);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        sLp.topMargin = dp(16);
        box.addView(logScroll, sLp);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT);
        }

        btnLogMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                verbose = !verbose;
                prefs.edit().putBoolean("log_verbose", verbose).apply();
                btnLogMode.setText(verbose ? "原生" : "简化");
                renderLog();
            }
        });
        btnLogExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLog();
            }
        });
        btnLogCapture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureLogcat();
            }
        });

        renderLog();
    }

    private void showModules() {
        if (busy) return;
        setBusy(true);
        status.setText("读取模块状态 ...");
        NativeHook.listModules(dm, new NativeHook.Listener() {
            @Override
            public void log(String s) {
                raw(s);
            }

            @Override
            public void done(final boolean ok, final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        status.setText(ok ? "模块状态已读取" : "读取失败");
                        simple(message + "（见原生日志）");
                        renderLog();
                    }
                });
            }
        });
    }

    private void showKeepAliveLogs() {
        if (busy) return;
        File dir = new File(getFilesDir(), "logs");
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            simple("暂无守护日志");
            return;
        }
        Arrays.sort(files);
        StringBuilder sb = new StringBuilder("[keepalive logs]\n");
        for (File file : files) {
            if (!file.getName().startsWith("keepalive-")) continue;
            sb.append("--- ").append(file.getName()).append(" ---\n");
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(file), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                reader.close();
            } catch (Exception e) {
                sb.append("read failed: ").append(e).append('\n');
            }
        }
        raw(sb.toString());
        renderLog();
        simple("守护日志已显示在原生日志中");
    }

    private void appendBuffer(StringBuilder sb, String s) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (sb) {
            sb.append(ts).append(' ').append(s).append('\n');
            if (sb.length() > 200 * 1024) {
                sb.delete(0, sb.length() - 150 * 1024);
            }
        }
    }

    private final Runnable renderTask = new Runnable() {
        @Override
        public void run() {
            renderLog();
        }
    };

    private void raw(String s) {
        appendBuffer(rawLog, s);
        RunLog.append(this, "raw", s);
        if (verbose) {
            runOnUiThread(renderTask);
        }
    }

    private void simple(String s) {
        appendBuffer(simpleLog, s);
        RunLog.append(this, "ui", s);
        if (!verbose) {
            runOnUiThread(renderTask);
        }
    }

    private void logFromThread(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                raw(s);
            }
        });
    }

    private void renderLog() {
        if (logText == null) {
            return;
        }
        String text = verbose ? rawLog.toString() : simpleLog.toString();
        logText.setText(text.length() == 0 ? "（暂无日志）" : text);
        logScroll.post(new Runnable() {
            @Override
            public void run() {
                logScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /** 主界面日志区太小，这里给一个整屏可滚动、可复制的视图。 */
    private void showFullLog() {
        String text = verbose ? rawLog.toString() : simpleLog.toString();
        if (text.length() == 0) {
            text = "（暂无日志）";
        }
        final String content = text;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(cCard);
        int p = dp(16);
        box.setPadding(p, p, p, p);

        TextView path = text("文件: " + RunLog.currentPath(this), 11, cSub, false);
        path.setTextIsSelectable(true);
        box.addView(path);

        TextView view = text(content, 14, cText, false);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setMovementMethod(new ScrollingMovementMethod());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(box)
                .setNegativeButton("关闭", null)
                .setNeutralButton("复制", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager)
                                        getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData
                                    .newPlainText("nativehook", content));
                        }
                        simple("日志已复制到剪贴板");
                    }
                })
                .setPositiveButton("导出到 4466", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        exportLog();
                    }
                })
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    /**
     * 「首次使用帮助」弹窗。
     *
     * 新手最容易卡住的三件事：点完开关不重启以为没生效、分不清「清理部署」和
     * 「重启框架」、找不到日志怎么导出。这里按 原理 → 上手三步 → 开关含义 →
     * 维护按钮 → 常见问题 讲清楚。
     *
     * **只写公开功能**：界面默认隐藏的那两项是私有的，帮助里一个字都不能提
     * ——不写名字、不写「默认隐藏」、也不写解锁手势，解锁后同样不写。
     * 用户明确要求过，改之前先问。
     */
    private void showHelp() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(text("NativeHook 使用说明", 34, cText, true));

        helpSection(col, "一、它是做什么的");
        helpBody(col, "绕过和辰安全对第三方 APK 的安装 / 卸载白名单，另外可选隐藏顶部黑带、"
                + "手机互联全屏。\n"
                + "原理是往系统进程的内存里注入 LSPlant + Dobby：只改运行时，"
                + "不动 /system，不刷机。所以软重启或彻底重启车机后注入就没了，"
                + "「常驻检测」开着会自动补注入，关着就得手动「开启 Hook」。");

        helpSection(col, "二、第一次用（三步）");
        helpBody(col, "1. 点「开启 Hook」。App 会先核对 /data/local/tmp/hook-native 下的 17 个文件，"
                + "缺了就自动释放，然后依次注入 system_server、和辰安全、systemui、"
                + "语音服务、手机互联五个进程。第一次要十几秒，之后就快了。\n\n"
                + "2. 按需要在「功能开关」里拨好，再点「应用开关」保存。"
                + "开关最终写到 /data/system/uninstall-hook-native.cfg。\n\n"
                + "3. 开关改完不会立刻生效，得让进程重新读配置。两条路：\n"
                + "   ① 点「重启框架」= 软重启，只重启 system_server，黑屏几秒，应用会全部重启；\n"
                + "   ② 彻底重启车机。\n"
                + "   注意：锁车一般只是休眠、不算重启，下电再上电配置不会重新读，别指望它。");

        // 帮助里**只讲公开的四个开关**。
        // 另外两个是私有功能，别在这里提：不加条件分支、不写名字、不写「默认隐藏」，
        // 解锁后也不写。这是用户明确要求的，改动前先问。
        helpSection(col, "三、功能开关都是干什么的");
        helpBody(col, "· 允许安装应用：装第三方 APK 不再被安全模块拦。"
                + "保护名单里的系统应用照样受保护。\n"
                + "· 允许卸载应用：能卸掉预装应用，保护名单同样不受影响。\n"
                + "· 状态栏透明：消掉车机顶部 110px 黑带，让状态栏区域透出背景。\n"
                + "· ICCOA 全屏：手机互联画面铺满整块屏（com.wt.phonelink）。");

        helpSection(col, "四、维护区按钮");
        helpBody(col, "· 首次部署：强制重新释放全部文件。怀疑文件缺失或权限不对时用。\n"
                + "· 清理部署：删掉部署目录和状态文件。已经注入的内存 hook 不会因此消失，"
                + "要它失效得软重启或彻底重启车机。\n"
                + "· 重启框架：杀掉 system_server 让它自己重启，也就是上面说的软重启，"
                + "黑屏几秒、应用全部重启。\n"
                + "· 查看模块：读各进程的注入结果。\n"
                + "· 守护日志：常驻检测服务自己的日志。\n"
                + "· 常驻检测：开着时每 15 秒查一次，进程被杀或重启会自动补注入。\n"
                + "· 查看运行日志：本 App 的运行日志，可以复制，也能导出成 nhlog.txt，"
                + "用电脑浏览器打开 http://车机IP:4466/nhlog.txt 下载。");

        helpSection(col, "五、常见问题");
        helpBody(col, "· 桌面找不到图标：部分车机桌面不显示这类应用，可从应用列表或启源助手进。\n"
                + "· 开关点了没反应：先点「应用开关」保存，再软重启或彻底重启车机，"
                + "两步都不能漏（锁车休眠不算重启）。\n"
                + "· 覆盖安装失败：签名必须一致（本包用长安车机证书），换签名要先卸载再装。\n"
                + "· 点「关闭 Hook」没变化：内存里的 hook 没法热卸载，它只是取消开机自动注入，"
                + "真要失效得软重启或彻底重启车机。\n"
                + "· 怀疑是 hook 引起的异常：先「关闭 Hook」，再彻底重启车机；"
                + "重启后一切回到原厂状态，因为它从头到尾没改过系统分区。");

        // 内容放 ScrollView，底部自己画一个「知道了」。
        // 不用 AlertDialog 的按钮：全屏布局下那一排会被 ScrollView 挤成半截，
        // 实测文字被裁在卡片边缘上，很难看。
        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cBg);
        bg.setCornerRadius(dp(26));
        box.setBackground(bg);
        int p = dp(30);
        box.setPadding(p, dp(26), p, dp(20));
        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button close = styledButton("知道了", cPrimary, Color.TRANSPARENT, Color.WHITE, 20);
        close.setTextSize(26);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(92));
        closeLp.topMargin = dp(18);
        box.addView(close, closeLp);

        final AlertDialog dialog = new AlertDialog.Builder(this).setView(box).create();
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            // 留出四周，能看出是个浮层；高度按屏幕 86% 走，车机上够读完
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            android.util.DisplayMetrics m = getResources().getDisplayMetrics();
            dialog.getWindow().setLayout((int) (m.widthPixels * 0.92f),
                    (int) (m.heightPixels * 0.86f));
        }
    }

    /** 帮助弹窗里的小标题。 */
    private void helpSection(LinearLayout col, String title) {
        TextView t = text(title, 26, cPrimary, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(26);
        col.addView(t, lp);
    }

    /** 帮助弹窗里的正文。行距放大一点，车机上远看也读得清。 */
    private void helpBody(LinearLayout col, String body) {
        TextView t = text(body, 20, cText, false);
        t.setLineSpacing(dp(7), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        col.addView(t, lp);
    }

    /** 抓取 logcat 里与模块/投屏相关的日志，写入运行日志后可直接导出。 */
    private void captureLogcat() {
        status.setText("正在抓取 logcat ...");
        simple("正在抓取 logcat ...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String cmd = "echo '=== inject ==='\n"
                        + "grep '^inject=' " + NativeHook.CFG + " 2>/dev/null\n"
                        + "echo '=== pid ==='\n"
                        + "pidof com.wt.phonelink\n"
                        + "echo '=== status ==='\n"
                        + "cat " + NativeHook.ICCOA_STATUS + " 2>/dev/null\n"
                        + "echo '=== crash ==='\n"
                        + "logcat -d -v time -b crash -t 300 2>/dev/null | tail -n 200\n"
                        + "echo '=== events ==='\n"
                        + "logcat -d -v time -t 8000 -s NHookNative UCarAdapter"
                        + " UCarConnectProxy UCar_ShareObservers CarLinkMainActivity"
                        + " AndroidRuntime DEBUG 2>/dev/null | tail -n 500\n"
                        + "echo '=== session ==='\n"
                        + "tail -n 800 " + NativeHook.DIR + "/session.log 2>/dev/null\n";
                String out = null;
                if (FastChannel.isPortOpen(FastChannel.SHELL_PORT, 300)) {
                    try {
                        out = FastChannel.exec(cmd, 20000);
                    } catch (Exception e) {
                        out = "[fast] " + e;
                    }
                }
                if (out == null) {
                    final java.util.concurrent.CountDownLatch latch =
                            new java.util.concurrent.CountDownLatch(1);
                    final String[] holder = new String[1];
                    dm.exec(cmd, new DmClient.Callback() {
                        @Override
                        public void onResult(boolean ok, String o) {
                            holder[0] = ok ? o : "[dm] " + o;
                            latch.countDown();
                        }
                    }, 2);
                    try {
                        latch.await(90, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    out = holder[0] == null ? "[capture] 等待超时" : holder[0];
                }
                final String text = out.length() == 0 ? "（没有匹配的日志）" : out;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        raw("[logcat]\n" + text);
                        renderLog();
                        status.setText("logcat 已抓取");
                        new AlertDialog.Builder(MainActivity2.this)
                                .setTitle("logcat 已抓取")
                                .setMessage("已写入运行日志。点「导出」下载 nhlog.txt，"
                                        + "或点「全屏」直接查看。")
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            }
        }, "nh-logcat").start();
    }

    /** 把今天的运行日志复制到 4466 根目录，PC 浏览器可直接下载。 */
    private void exportLog() {
        final File log = RunLog.currentFile(this);
        final String remote = "/data/local/tmp/lan_file/nhlog.txt";
        status.setText("正在导出日志 ...");
        simple("正在导出日志到 " + remote);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                String cmd = "mkdir -p /data/local/tmp/lan_file\n"
                        + "cp " + log.getAbsolutePath() + " " + remote + "\n"
                        + "chmod 644 " + remote + "\n"
                        + "ls -l " + remote + "\n";
                boolean fast = FastChannel.isPortOpen(FastChannel.HTTP_PORT, 300);
                if (FastChannel.isPortOpen(FastChannel.SHELL_PORT, 300)) {
                    try {
                        String out = FastChannel.exec(cmd, 15000);
                        raw("[export] fast\n" + out);
                    } catch (Exception e) {
                        error = "快速通道失败: " + e;
                    }
                }
                if (error != null || !fast) {
                    try {
                        final java.util.concurrent.CountDownLatch latch =
                                new java.util.concurrent.CountDownLatch(1);
                        dm.exec(cmd, new DmClient.Callback() {
                            @Override
                            public void onResult(boolean ok, String out) {
                                raw("[export] dm " + (ok ? "OK" : "FAIL") + "\n" + out);
                                latch.countDown();
                            }
                        }, 2);
                        latch.await();
                    } catch (Exception e) {
                        error = "DM01 失败: " + e;
                    }
                }
                final String ip = FastChannel.ipAddress();
                final String url = "http://" + ip + ":4466/nhlog.txt";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText("日志已导出");
                        renderLog();
                        if (ip.isEmpty()) {
                            simple("日志已导出，但没有取到车机 IP；"
                                    + "可用 4455 查看 " + log.getAbsolutePath());
                        } else {
                            simple("下载地址: " + url);
                            new AlertDialog.Builder(MainActivity2.this)
                                    .setTitle("日志已导出")
                                    .setMessage("浏览器打开（和车机同一局域网）：\n"
                                            + url
                                            + "\n\n本地文件：\n"
                                            + log.getAbsolutePath())
                                    .setPositiveButton("知道了", null)
                                    .show();
                        }
                    }
                });
            }
        }, "nh-export-log").start();
    }

    private void preflight(final boolean deployIfNeeded, final Runnable onReady) {
        setBusy(true);
        status.setText("前置检查 ...");
        simple(deployIfNeeded ? "正在检查服务文件..." : "正在检查环境...");
        String cmd = "mkdir -p " + HOOK_DIR + "\n"
                + "echo version=$(cat " + REMOTE_VER + " 2>/dev/null)\n"
                + "test -x " + REMOTE_BIN + " && echo inject=yes || echo inject=no\n"
                + "test -f " + REMOTE_JS + " && echo script=yes || echo script=no\n"
                + "test -f " + REMOTE_SEC_JS + " && echo sec=yes || echo sec=no\n"
                + "ls -l " + REMOTE_BIN + " " + REMOTE_JS + " " + REMOTE_SEC_JS
                + " " + REMOTE_VER + " 2>&1\n";
        dm.exec(cmd, new DmClient.Callback() {
            @Override
            public void onResult(boolean ok, String out) {
                raw("[env] " + (ok ? "OK" : "FAIL") + "\n" + out);
                boolean deployed = ok
                        && out.contains("version=" + HELPER_VERSION)
                        && out.contains("inject=yes")
                        && out.contains("script=yes")
                        && out.contains("sec=yes");
                if (deployed) {
                    status.setText("服务已就绪");
                    simple("服务已就绪");
                    setBusy(false);
                    if (onReady != null) {
                        onReady.run();
                    }
                } else if (deployIfNeeded) {
                    simple("需要部署服务文件");
                    deploy(onReady);
                } else {
                    status.setText("需要部署");
                    simple("检查完成：需要部署");
                    setBusy(false);
                }
            }
        });
    }

    private void deploy(final Runnable onReady) {
        status.setText("部署服务文件 ...");
        simple("正在部署服务文件...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File bin = new File(getFilesDir(), "frida-inject");
                    File js = new File(getFilesDir(), "uninstall-hook.js");
                    File sec = new File(getFilesDir(), "sec-guard.js");
                    long n1 = copyAsset("frida-inject", bin);
                    long n2 = copyAsset("uninstall-hook.js", js);
                    long n3 = copyAsset("sec-guard.js", sec);
                    logFromThread("[deploy] 本地: inject=" + n1 + " B, js=" + n2
                            + " B, sec=" + n3 + " B");

                    final String cmd = "rm -rf " + STAGE_DIR + "\n"
                            + "mkdir -p " + STAGE_DIR + "\n"
                            + "cp " + bin.getAbsolutePath() + " " + STAGE_DIR + "/frida-inject\n"
                            + "cp " + js.getAbsolutePath() + " " + STAGE_DIR + "/uninstall-hook.js\n"
                            + "cp " + sec.getAbsolutePath() + " " + STAGE_DIR + "/sec-guard.js\n"
                            + "chmod 755 " + STAGE_DIR + "/frida-inject\n"
                            + "[ \"$(wc -c < " + STAGE_DIR + "/frida-inject)\" = \""
                            + bin.length() + "\" ] && echo size_bin=ok || echo size_bin=bad\n"
                            + "[ \"$(wc -c < " + STAGE_DIR + "/uninstall-hook.js)\" = \""
                            + js.length() + "\" ] && echo size_js=ok || echo size_js=bad\n"
                            + "[ \"$(wc -c < " + STAGE_DIR + "/sec-guard.js)\" = \""
                            + sec.length() + "\" ] && echo size_sec=ok || echo size_sec=bad\n"
                            + "rm -rf " + HOOK_DIR + "\n"
                            + "mv " + STAGE_DIR + " " + HOOK_DIR + "\n"
                            + "echo " + HELPER_VERSION + " > " + REMOTE_VER + "\n"
                            + "ls -l " + HOOK_DIR + " 2>&1\n";

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dm.exec(cmd, new DmClient.Callback() {
                                @Override
                                public void onResult(boolean ok, String out) {
                                    raw("[deploy] " + (ok ? "OK" : "FAIL") + "\n" + out);
                                    boolean deployed = ok
                                            && out.contains("size_bin=ok")
                                            && out.contains("size_js=ok")
                                            && out.contains("size_sec=ok")
                                            && out.contains(HELPER_VERSION);
                                    if (deployed) {
                                        status.setText("服务已就绪");
                                        simple("服务已就绪");
                                        setBusy(false);
                                        if (onReady != null) {
                                            onReady.run();
                                        }
                                    } else {
                                        status.setText("部署失败");
                                        simple("部署失败，请切换原生日志");
                                        setBusy(false);
                                    }
                                }
                            });
                        }
                    });
                } catch (final Exception e) {
                    logFromThread("[deploy] 本地解压失败: " + e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("部署失败");
                            simple("部署失败，请切换原生日志");
                            setBusy(false);
                        }
                    });
                }
            }
        }, "uh-deploy").start();
    }

    private long copyAsset(String name, File out) throws IOException {
        InputStream in = getAssets().open(name);
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

    private void writeText(File f, String s) throws IOException {
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            w.write(s);
        } finally {
            w.close();
        }
    }

    private File writeConfigLocal() throws Exception {
        JSONObject o = new JSONObject();
        o.put("uninstall", true);
        o.put("install", true);
        JSONArray deny = new JSONArray();
        for (String d : DENY) {
            deny.put(d);
        }
        o.put("deny", deny);
        File f = new File(getFilesDir(), "uninstall-hook-config.json");
        writeText(f, o.toString());
        return f;
    }

    private String configCopyCmd(File cfg) {
        String p = cfg.getAbsolutePath();
        return "mkdir -p /data/system\n"
                + "cp " + p + " " + REMOTE_CFG + "\n"
                + "chmod 644 " + REMOTE_CFG + "\n"
                + "if [ -x /system/bin/restorecon ]; then /system/bin/restorecon " + REMOTE_CFG
                + " 2>/dev/null; elif [ -x /system/bin/chcon ]; then /system/bin/chcon "
                + "u:object_r:system_data_file:s0 " + REMOTE_CFG + " 2>/dev/null; fi\n"
                + "ls -l " + REMOTE_CFG + " 2>&1\n"
                + "cat " + REMOTE_CFG + "\n";
    }

    private String buildStartScript(String cfgPath) {
        StringBuilder sb = new StringBuilder();
        sb.append(configCopyCmd(new File(cfgPath)));
        sb.append("mkdir -p ").append(HOOK_DIR).append("\n");
        sb.append("pkill -f '/data/local/tmp/frida-inject' 2>/dev/null\n");
        sb.append("pkill -f '").append(REMOTE_BIN).append("' 2>/dev/null\n");
        sb.append("sleep 0.5\n");
        sb.append("SPID=$(pidof system_server)\n");
        sb.append("echo \"SPID=$SPID\"\n");
        sb.append("if [ -z \"$SPID\" ]; then\n");
        sb.append("  echo \"system_server not found\"\n");
        sb.append("else\n");
        sb.append("  if command -v setsid >/dev/null 2>&1; then\n");
        sb.append("    setsid ").append(REMOTE_BIN).append(" -p $SPID -s ")
                .append(REMOTE_JS).append(" > ").append(REMOTE_LOG).append(" 2>&1 &\n");
        sb.append("  else\n");
        sb.append("    nohup ").append(REMOTE_BIN).append(" -p $SPID -s ")
                .append(REMOTE_JS).append(" > ").append(REMOTE_LOG).append(" 2>&1 &\n");
        sb.append("  fi\n");
        sb.append("fi\n");
        sb.append("VPID=$(pidof ").append(SEC_PACKAGE).append(")\n");
        sb.append("echo \"VPID=$VPID\"\n");
        sb.append("if [ -n \"$VPID\" ]; then\n");
        sb.append("  if command -v setsid >/dev/null 2>&1; then\n");
        sb.append("    setsid ").append(REMOTE_BIN).append(" -p $VPID -s ")
                .append(REMOTE_SEC_JS).append(" > ").append(REMOTE_SEC_LOG).append(" 2>&1 &\n");
        sb.append("  else\n");
        sb.append("    nohup ").append(REMOTE_BIN).append(" -p $VPID -s ")
                .append(REMOTE_SEC_JS).append(" > ").append(REMOTE_SEC_LOG).append(" 2>&1 &\n");
        sb.append("  fi\n");
        sb.append("else\n");
        sb.append("  echo 'vecentek not running'\n");
        sb.append("fi\n");
        sb.append("i=0\n");
        sb.append("while [ $i -lt 20 ]; do\n");
        sb.append("  OK_SYS=0\n");
        sb.append("  OK_SEC=0\n");
        sb.append("  grep -q 'WhiteListHelper hooked' ").append(REMOTE_LOG).append(" 2>/dev/null && OK_SYS=1\n");
        sb.append("  if [ -z \"$VPID\" ]; then OK_SEC=1; else grep -q 'c.a.b hooked' ").append(REMOTE_SEC_LOG).append(" 2>/dev/null && OK_SEC=1; fi\n");
        sb.append("  if [ \"$OK_SYS\" = \"1\" ] && [ \"$OK_SEC\" = \"1\" ]; then break; fi\n");
        sb.append("  sleep 0.5\n");
        sb.append("  i=$((i+1))\n");
        sb.append("done\n");
        sb.append("echo PID=$(pidof frida-inject)\n");
        sb.append("echo '--- hook log ---'\n");
        sb.append("tail -n 100 ").append(REMOTE_LOG).append(" 2>&1\n");
        sb.append("echo '--- sec log ---'\n");
        sb.append("tail -n 60 ").append(REMOTE_SEC_LOG).append(" 2>&1\n");
        return sb.toString();
    }

    /**
     * 应用功能开关：存 SharedPreferences → 重写配置 → 同步到车机 → 跑 ensure_services.sh。
     *
     * 为什么不用「重启注入」那套完整流程：
     * 这些开关大多只影响模块 init 时读的配置值，重新注入是不必要的；
     * 但配置要先落地，再让 ensure_services.sh 按新配置决定记录仪启停。
     * 已经注入的 Java 模块要生效需要重启对应进程，这里只提示，不强制。
     */
    private void applyFeatures() {
        if (busy) return;
        final StringBuilder changed = new StringBuilder();
        // Java 模块（install/uninstall/statusbar/presentation/iccoa）改完要重启车机
        // 才生效；记录仪由 ensure_services.sh 直接启停，立即生效。
        // 这里单独记一下，好决定要不要问用户重启。
        // 用数组包一层：匿名内部类只能捕获 effectively final 的局部变量
        final boolean[] needReboot = {false};
        SharedPreferences.Editor ed = prefs.edit();
        // 按 tag（prefs 键）反查配置行，不要用下标：
        // 隐藏项不参与渲染，featureSwitches 的第 i 个不等于 FEATURES[i]，
        // 用下标会把开关悄悄写到别的功能上。
        for (ToggleSwitch sw : featureSwitches) {
            Object tag = sw.getTag();
            if (!(tag instanceof String)) {
                continue;
            }
            String[] f = NativeHook.featureByPrefKey((String) tag);
            if (f == null) {
                continue;
            }
            boolean on = sw.isChecked();
            boolean was = NativeHook.featureOn(this, f[1], f[3]);
            ed.putBoolean(f[1], on);
            if (on != was) {
                changed.append(f[2]).append(on ? " 开启" : " 关闭").append('\n');
                if (!"evs_dvr".equals(f[0])) needReboot[0] = true;
            }
        }
        ed.apply();

        setBusy(true);
        status.setText("正在应用开关 ...");
        simple(changed.length() == 0 ? "开关无变化，仅同步配置" : "变更：\n" + changed);

        NativeHook.syncConfigAndServices(this, dm, new NativeHook.Listener() {
            @Override
            public void log(String s) {
                raw(s);
            }

            @Override
            public void done(final boolean ok, final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        status.setText(ok ? "开关已保存" : "应用失败");
                        // 记录仪由 ensure_services.sh 直接按配置启停，改完立即生效；
                        // 其余开关是模块 init() 时读配置，而 start.sh 会跳过已注入的
                        // 进程（避免重复 dlopen 触发 LSPlant 二次 Init），
                        // 所以必须等对应进程重启 / 车机重启后才会用上新配置。
                        simple(ok ? message
                                + "\n后摄记录仪：立即生效"
                                + "\n其余开关：需软重启或彻底重启车机后生效" : message);
                        renderLog();
                        if (ok && needReboot[0]) {
                            askReboot();
                        }
                    }
                });
            }
        });
    }

    /**
     * Java 模块开关改完必须重新注入才生效，而 start.sh 会跳过已注入的进程。
     * 所以只能等进程重启：软重启（重启框架）或彻底重启车机。
     * 这里交给用户决定，不擅自重启。
     */
    private void askReboot() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("需要重启才生效")
                .setMessage("安装/卸载、状态栏、投屏这些开关是模块加载时读取的，\n"
                        + "当前已注入的进程不会重新读配置。\n\n"
                        + "注意：锁车一般只是休眠、不算重启，下电再上电不会生效。\n"
                        + "要么点「重启框架」软重启（黑屏几秒），要么彻底重启车机。\n\n"
                        + "现在彻底重启车机吗？\n"
                        + "（选「稍后」的话，下次开机自动生效）")
                .setPositiveButton("现在重启", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        doReboot();
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void doReboot() {
        status.setText("正在重启 ...");
        simple("车机重启中，约 1 分钟后恢复。");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    dm.exec("sync\nreboot\n", new DmClient.Callback() {
                        @Override
                        public void onResult(boolean ok, String out) {
                            raw("[reboot] " + out);
                        }
                    });
                } catch (Exception e) {
                    raw("[reboot] 失败: " + e);
                }
            }
        }, "nh-reboot").start();
    }

    private void startHook() {
        if (busy) return;
        setBusy(true);
        status.setText("准备启动 ...");
        simple("正在检查 native 服务...");
        NativeHook.ensureDeployed(this, dm, new NativeHook.Listener() {
            @Override
            public void log(String s) {
                raw(s);
            }

            @Override
            public void done(final boolean deployed, final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!deployed) {
                            status.setText("部署失败");
                            simple(message);
                            setBusy(false);
                            return;
                        }
                        status.setText("正在注入 ...");
                        simple("正在注入 system_server 和 VecentekApp...");
                        NativeHook.start(MainActivity2.this, dm, new NativeHook.Listener() {
                            @Override
                            public void log(String s) {
                                raw(s);
                            }

                            @Override
                            public void done(final boolean started, final String msg) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        hookRunning = started;
                                        prefs.edit().putBoolean("hook_running", started).apply();
                                        status.setText(started ? "已开启" : "Hook 失败");
                                        btnStart.setText(started ? "已开启" : "开启 Hook");
                                        btnStart.setEnabled(!started);
                                        simple(started ? msg : "Hook 失败，请切换原生日志查看");
                                        setBusy(false);
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });
    }

    /**
     * 内存 hook 没有可靠的卸载方式（LSPlant 挂上去的方法无法安全还原），
     * 所以这个按钮只做「标记为已关闭」，真正的失效要等对应进程重启。
     * 这里把话说清楚，不要让按钮看起来像真的能关。
     */
    private void stopHook() {
        if (busy) return;
        setBusy(false);
        hookRunning = false;
        prefs.edit().putBoolean("hook_running", false).apply();
        status.setText("已标记为关闭");
        btnStart.setText("开启 Hook");
        btnStart.setEnabled(true);
        simple("内存 hook 无法热卸载。\n"
                + "要真正失效：软重启（重启框架）或彻底重启车机；\n"
                + "或把「功能开关」里的项关掉再重启。");
    }

    private void deployAll() {
        if (busy) return;
        setBusy(true);
        status.setText("首次部署 ...");
        simple("正在释放文件并启动 4455/4466...");
        NativeHook.deployAll(this, dm, new NativeHook.Listener() {
            @Override
            public void log(String s) {
                raw(s);
            }

            @Override
            public void done(final boolean ok, final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        status.setText(ok ? "部署完成" : "部署失败");
                        simple(ok ? message + "；" + FastChannel.ipAddress() : message);
                        refreshIp();
                        renderLog();
                    }
                });
            }
        });
    }

    private void refreshIp() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String ip = FastChannel.ipAddress();
                final boolean shell = FastChannel.isPortOpen(4455, 200);
                final boolean http = FastChannel.isPortOpen(4466, 200);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (ipText == null) return;
                        String text = (ip.isEmpty() ? "车机 IP: 未获取到" : "车机 IP: " + ip)
                                + "\n4455 终端: " + (shell ? "已启动" : "未启动")
                                + "  |  4466 文件: " + (http ? "已启动" : "未启动");
                        ipText.setText(text);
                    }
                });
            }
        }, "nh-ip").start();
    }

    private void cleanupDeploy() {
        if (busy) return;
        if (keepAlive) {
            keepAlive = false;
            prefs.edit().putBoolean("keep_alive", false).apply();
            stopKeepAliveService();
            btnKeepAlive.setText("常驻检测：关闭");
            btnKeepAlive.setTextColor(cSub);
        }
        setBusy(true);
        status.setText("正在清理部署 ...");
        simple("正在清理部署文件和状态...");
        NativeHook.cleanup(dm, new NativeHook.Listener() {
            @Override
            public void log(String s) {
                raw(s);
            }

            @Override
            public void done(final boolean ok, final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        hookRunning = false;
                        prefs.edit().putBoolean("hook_running", false).apply();
                        status.setText(ok ? "部署已清理" : "清理失败");
                        simple(ok ? message + "；下次开启会重新部署" : message);
                        setBusy(false);
                    }
                });
            }
        });
    }

    private void confirmRestartFramework() {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle("重启框架")
                .setMessage("会重启 system_server，车机短暂黑屏，所有应用会重启。\n"
                        + "用于清除已注入的内存 hook。确定吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("重启", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doRestartFramework();
                    }
                })
                .show();
    }

    private void doRestartFramework() {
        setBusy(true);
        status.setText("正在重启框架 ...");
        simple("正在重启 system_server...");
        NativeHook.restartFramework(dm, new NativeHook.Listener() {
            @Override
            public void log(String s) {
                raw(s);
            }

            @Override
            public void done(boolean ok, final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        hookRunning = false;
                        prefs.edit().putBoolean("hook_running", false).apply();
                        status.setText("已发送重启");
                        simple(message);
                        setBusy(false);
                    }
                });
            }
        });
    }

    private void toggleKeepAlive() {
        if (busy) return;
        keepAlive = !keepAlive;
        prefs.edit().putBoolean("keep_alive", keepAlive).apply();
        if (keepAlive) {
            startKeepAliveService();
            status.setText("常驻已开启");
            simple("常驻检测已开启：每 15 秒检查，进程重启后自动重新注入");
        } else {
            stopKeepAliveService();
            status.setText("常驻已关闭");
            simple("常驻检测已关闭");
        }
        btnKeepAlive.setText(keepAlive ? "常驻检测：已开启" : "常驻检测：关闭");
        btnKeepAlive.setTextColor(keepAlive ? cPrimary : cSub);
    }

    private void startKeepAliveService() {
        Intent i = new Intent(this, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void stopKeepAliveService() {
        stopService(new Intent(this, KeepAliveService.class));
    }
}