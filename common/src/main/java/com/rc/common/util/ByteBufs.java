package com.rc.common.util;

import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

/**
 * 引用计数释放工具，保证 DirectByteBuf 的 {@code release()} 闭环。
 */
public final class ByteBufs {

    private ByteBufs() {
    }

    /** 安全释放任意 Netty 消息（null / 非引用计数对象自动跳过）。 */
    public static void safeRelease(Object msg) {
        ReferenceCountUtil.release(msg);
    }

    public static void safeRelease(ReferenceCounted refCounted) {
        if (refCounted != null) {
            ReferenceCountUtil.release(refCounted);
        }
    }
}
