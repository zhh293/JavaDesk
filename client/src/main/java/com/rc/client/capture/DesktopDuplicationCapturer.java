package com.rc.client.capture;

import java.awt.image.BufferedImage;

/**
 * 低延迟屏幕采集抽象（采集 → {@link BufferedImage}），屏蔽 AWT Robot 与
 * Windows Desktop Duplication（DXGI）实现差异。
 *
 * <p>Phase 2 目标：JNA 绑定 {@code IDXGIOutputDuplication}（AcquireNextFrame →
 * CopyResource → Map），按脏矩形只传变化区域，显著低于 AWT Robot 整帧抓屏的时延与
 * CPU 开销。该 native 绑定需引入 JNA 依赖并在 Windows 桌面会话下联调，当前以
 * {@link ScreenCapturer}（AWT）作可用的跨平台回退实现，{@link #awtFallback()} 提供。
 * 上层仅依赖本接口，后续 native 实现零侵入替换。</p>
 */
public interface DesktopDuplicationCapturer extends AutoCloseable {

    /** 抓取一帧当前屏幕画面。 */
    BufferedImage capture();

    @Override
    void close();

    /** 返回 AWT Robot 回退实现（跨平台可用；Windows 低延迟 native 实现落地前使用）。 */
    static DesktopDuplicationCapturer awtFallback() {
        ScreenCapturer capturer = ScreenCapturer.create();
        return new DesktopDuplicationCapturer() {
            @Override
            public BufferedImage capture() {
                return capturer.capture();
            }

            @Override
            public void close() {
                capturer.close();
            }
        };
    }
}
