package com.changan.nativehook;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 自绘开关。
 *
 * 为什么不用 android.widget.Switch：车机主题把 Switch 的轨道背景改没了，
 * 实测在车机上只显示一个孤立的小圆点，完全看不出开/关状态。
 * 自绘一个只依赖 Canvas 的控件，不受系统主题影响，颜色也和本 App 的配色统一。
 */
final class ToggleSwitch extends View {

    private boolean checked;
    private int cOnColor;
    private int cOffColor;
    private int cKnob;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();

    ToggleSwitch(Context ctx, boolean def, int onColor, int offColor, int knobColor) {
        super(ctx);
        this.checked = def;
        this.cOnColor = onColor;
        this.cOffColor = offColor;
        this.cKnob = knobColor;
        setClickable(true);
        setFocusable(true);
    }

    void setChecked(boolean v) {
        if (checked != v) {
            checked = v;
            invalidate();
        }
    }

    boolean isChecked() {
        return checked;
    }

    void setColors(int on, int off, int knob) {
        cOnColor = on;
        cOffColor = off;
        cKnob = knob;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 固定尺寸：宽 76dp、高 40dp，车机上手指好点
        int w = (int) (getResources().getDisplayMetrics().density * 76);
        int h = (int) (getResources().getDisplayMetrics().density * 40);
        setMeasuredDimension(
                resolveSize(w, widthMeasureSpec),
                resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        float pad = getHeight() * 0.12f;
        float h = getHeight() - pad * 2;
        float r = h / 2f;

        // 轨道
        track.set(pad, pad, getWidth() - pad, pad + h);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(checked ? cOnColor : cOffColor);
        c.drawRoundRect(track, r, r, paint);

        // 滑块：靠右=开，靠左=关
        float knobR = r - getHeight() * 0.10f;
        float cy = pad + r;
        float cx = checked
                ? (getWidth() - pad - r)
                : (pad + r);
        paint.setColor(cKnob);
        c.drawCircle(cx, cy, knobR, paint);
    }

    @Override
    public boolean performClick() {
        checked = !checked;
        invalidate();
        return super.performClick();
    }
}
