package com.changan.nativehook;

/** 车机系统栏会覆盖应用内容，这几个值用来给内容让位。 */
final class UiSafeArea {
    /** 顶部状态栏高度（时间、信号那一条）。 */
    static final int TOP_INSET_PX = 150;
    /** 底部导航栏高度（返回 / 主页那一条）。 */
    static final int BOTTOM_INSET_PX = 110;

    private UiSafeArea() {
    }
}
