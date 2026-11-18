/*
 * ICCOA / WT_CarLink fullscreen hook
 *
 * 目标进程：com.wt.phonelink（system uid）
 * 目标类：  com.wt.phonelink.carlink.CarLinkMainActivity
 *
 * 背景（详见 docs/analysis/ICCOA投屏全屏-重新评估-2026-10-22.md）：
 *   CarLinkMainActivity 把投屏区域硬编码成「屏幕宽度 x 固定高度」，并加 topMargin；
 *   外层 RelativeLayout 又是 gravity=center，于是视频在竖屏里上下留黑。
 *   不同固件的固定高度不一样：旧 APK 是 1440x1640，实车固件是 1440x1784。
 *   本脚本不依赖原值，直接强制成目标尺寸。
 *
 * 实车验证（2026-10-22）：
 *   adapt 模式成功：手机回送 1080x1440，真全屏、不拉伸；
 *   实车固件原 Surface 为 1440x1784，startCast 强制 1440x1920 生效。
 *
 * 用法：attach 到 com.wt.phonelink 后，退出并重新进入「手机互联 → ICCOA」，
 *       让 initDisplayMetrics() / resizeSurfaceAccordingToCastSize() 重新执行。
 *       本次实车验证 initDisplayMetrics 正常触发，不需要 -f spawn。
 *
 * 三种模式：
 *   adapt   （默认，已实车验证）：向手机请求 1080x1440（3:4），Surface 1440x1920。
 *                        手机若照做，则真正全屏且不拉伸。
 *   stretch             ：视频尺寸保持 1080x1230，只把 Surface 拉到 1440x1920。
 *                        一定全屏，但纵向拉伸约 17%。
 *   nochange            ：保留固件原视频/原 Surface 尺寸，只去掉 topMargin 和居中偏移。
 *                        用来验证 hook 是否生效，不依赖 1640/1784 具体值。
 *
 * 脚本不做任何持久化修改；Ctrl+C 退出 Frida、重启 app/车机即恢复原状。
 * 日志使用 ASCII，避免 frida-inject 的 stdout 把中文变成 ??。
 */

var MODE = "adapt"; // "adapt" | "stretch" | "nochange"

var VIDEO_W = 1080;
var VIDEO_H = 1440;
var SURFACE_W = 1440;
var SURFACE_H = 1920;

// nochange 模式不强制尺寸，保留固件原值
var FORCE = (MODE !== "nochange");

if (MODE === "stretch") {
    VIDEO_W = 1080;
    VIDEO_H = 1230;
}

function log(msg) {
    console.log("[iccoa] " + msg);
}

Java.perform(function () {
    var Activity;
    var LayoutParams;
    var Rid;
    var View;
    var RelativeLayout;

    try {
        Activity = Java.use("com.wt.phonelink.carlink.CarLinkMainActivity");
        LayoutParams = Java.use("android.widget.RelativeLayout$LayoutParams");
        Rid = Java.use("com.wt.phonelink.R$id");
        View = Java.use("android.view.View");
        RelativeLayout = Java.use("android.widget.RelativeLayout");
    } catch (e) {
        log("class load failed; attach to com.wt.phonelink main process: " + e);
        return;
    }

    // 1) 改写硬编码的显示尺寸。必须在 UCarConfig 构建、下发手机之前执行。
    var origInitDisplayMetrics = Activity.initDisplayMetrics;
    Activity.initDisplayMetrics.implementation = function () {
        origInitDisplayMetrics.call(this);

        var originalVideoW = this.mVideoDisplayWidth.value;
        var originalVideoH = this.mVideoDisplayHeight.value;
        var originalSurfaceW = this.mExpectedDisplayWidth.value;
        var originalSurfaceH = this.mExpectedDisplayHeight.value;

        if (FORCE) {
            this.mExpectedDisplayWidth.value = SURFACE_W;
            this.mExpectedDisplayHeight.value = SURFACE_H;
            this.mVideoDisplayWidth.value = VIDEO_W;
            this.mVideoDisplayHeight.value = VIDEO_H;
        }

        log("initDisplayMetrics original video=" + originalVideoW + "x" + originalVideoH +
            " surface=" + originalSurfaceW + "x" + originalSurfaceH +
            " -> video=" + this.mVideoDisplayWidth.value + "x" + this.mVideoDisplayHeight.value +
            " surface=" + this.mExpectedDisplayWidth.value + "x" + this.mExpectedDisplayHeight.value +
            " mode=" + MODE);
    };

    // 2) SurfaceView 布局：去掉 topMargin 和居中，nochange 模式保留固件原尺寸。
    var origResizeSurface = Activity.resizeSurfaceAccordingToCastSize;
    Activity.resizeSurfaceAccordingToCastSize.implementation = function () {
        var view = this.mSurfaceView.value;
        if (view === null) {
            log("resizeSurface skipped: mSurfaceView is null");
            return;
        }

        var targetW = FORCE ? SURFACE_W : this.mExpectedDisplayWidth.value;
        var targetH = FORCE ? SURFACE_H : this.mExpectedDisplayHeight.value;

        var lp = LayoutParams.$new(targetW, targetH);
        lp.setMargins(0, 0, 0, 0);
        view.setLayoutParams(lp);

        // 原来 rl_cast_player 是 gravity=center：画面会在 1920 里被居中。
        // 改成顶对齐，topMargin=0 才真正等于画面顶部位置。
        try {
            var player = this.findViewById(Rid.rl_cast_player.value);
            if (player !== null) {
                Java.cast(player, RelativeLayout).setGravity(0x31); // TOP | CENTER_HORIZONTAL
            }
        } catch (e) {
            log("rl_cast_player gravity failed: " + e);
        }

        try {
            var mask = this.findViewById(Rid.top_mask_player.value);
            if (mask !== null) {
                Java.cast(mask, View).setVisibility(8); // View.GONE
                log("top_mask_player -> GONE");
            }
        } catch (e) {
            log("hide top_mask_player failed: " + e);
        }

        log("resizeSurface -> " + targetW + "x" + targetH + " topMargin=0");
    };

    // 3) 补全 LAYOUT_* 全屏标志（HiCarMainActivity 用的是 5894 = 0x1706）。
    var origHideSystemUi = Activity.hideSystemUi;
    Activity.hideSystemUi.implementation = function () {
        origHideSystemUi.call(this);
        try {
            this.getWindow().getDecorView().setSystemUiVisibility(5894);
            log("hideSystemUi -> 5894");
        } catch (e) {
            log("set fullscreen flags failed: " + e);
        }
    };

    // 4) 兜底：layout 可能还没跑完，getWidth() 仍是旧值（实车是 1784）。
    //    这里强制按目标尺寸启动解码器，保证 MediaCodec 按全屏 Surface 配置。
    try {
        var UCarAdapter = Java.use("com.ucar.vehiclesdk.UCarAdapter");
        var origStartCast = UCarAdapter.startCast;
        UCarAdapter.startCast.implementation = function (surface, w, h) {
            if (FORCE) {
                log("startCast surface=" + w + "x" + h + " -> " + SURFACE_W + "x" + SURFACE_H);
                return origStartCast.call(this, surface, SURFACE_W, SURFACE_H);
            }
            log("startCast surface=" + w + "x" + h + " (nochange)");
            return origStartCast.call(this, surface, w, h);
        };
    } catch (e) {
        log("UCarAdapter.startCast hook failed: " + e);
    }

    // 5) 兜底：如果 initDisplayMetrics 被 ART 内联导致 hook 没触发，
    //    这里仍然强制 UCarConfig 使用目标视频尺寸。
    if (FORCE) {
        try {
            var Builder = Java.use("com.ucar.vehiclesdk.UCarConfig$Builder");
            var origSetVideoW = Builder.setVideoDisplayWidth;
            var origSetVideoH = Builder.setVideoDisplayHeight;
            Builder.setVideoDisplayWidth.implementation = function (w) {
                log("UCarConfig.videoWidth " + w + " -> " + VIDEO_W);
                return origSetVideoW.call(this, VIDEO_W);
            };
            Builder.setVideoDisplayHeight.implementation = function (h) {
                log("UCarConfig.videoHeight " + h + " -> " + VIDEO_H);
                return origSetVideoH.call(this, VIDEO_H);
            };
        } catch (e) {
            log("UCarConfig.Builder hook failed: " + e);
        }
    }

    // 6) 观测：手机实际送来的视频尺寸。这是判断 adapt 是否被手机接受的关键。
    try {
        var SinkNative = Java.use("com.ucarsink.sink.natives.SinkNative");
        var origVideoInit = SinkNative.onNativeCastVideoInitialized;
        SinkNative.onNativeCastVideoInitialized.implementation = function (deviceId, mime, w, h) {
            log("phone video initialized: " + w + "x" + h + " mime=" + mime);
            return origVideoInit.call(SinkNative, deviceId, mime, w, h);
        };
    } catch (e) {
        log("SinkNative hook failed: " + e);
    }

    log("hooks installed, mode=" + MODE);
});