package com.rc.client.capture;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

/**
 * AWT Robot 整帧屏幕采集器（Phase 1 简易实现）。
 *
 * <p>抓取默认屏幕设备全幅画面为 {@link BufferedImage}，供上层编码后走数据面。
 * AWT Robot 为 CPU 抓屏、跨平台能力有限，Phase 2 由 JNA + Windows Desktop
 * Duplication API 低延迟采集替换，本类保持「采集 → 图像」的最小接口不变。</p>
 */
public final class ScreenCapturer implements AutoCloseable {

    private final Robot robot;
    private final Rectangle screenBounds;

    private ScreenCapturer(Robot robot, Rectangle screenBounds) {
        this.robot = robot;
        this.screenBounds = screenBounds;
    }

    /** 构建默认屏幕采集器；无头环境或权限受限时抛异常（上层决定是否降级）。 */
    public static ScreenCapturer create() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("screen capture unavailable in headless environment");
        }
        Rectangle bounds = new Rectangle(
                java.awt.Toolkit.getDefaultToolkit().getScreenSize());
        try {
            return new ScreenCapturer(new Robot(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()), bounds);
        } catch (AWTException e) {
            throw new IllegalStateException("screen capture permission denied", e);
        }
    }

    /** 抓取当前全屏画面。 */
    public BufferedImage capture() {
        return robot.createScreenCapture(screenBounds);
    }

    /** 当前抓屏区域（整幅）。 */
    public Rectangle bounds() {
        return new Rectangle(screenBounds);
    }

    @Override
    public void close() {
        // Robot 无显式系统资源，保留接口以匹配 AutoCloseable 生命周期约定。
    }
}
