package com.changan.nh;

/**
 * 运行时与模块之间的回调容器。
 * 每个被 hook 的方法使用独立实例，backup 不会互相覆盖。
 *
 * cb/user 是模块传入的 native 函数指针和上下文，由 runtime 解释。
 */
public final class Hooker {
    public final long cb;
    public final long user;
    public Object backup;

    public Hooker(long cb, long user) {
        this.cb = cb;
        this.user = user;
    }

    public native Object callback(Object[] args);
}