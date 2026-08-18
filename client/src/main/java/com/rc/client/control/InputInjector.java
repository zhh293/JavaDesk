package com.rc.client.control;

import com.rc.client.transport.TransportListener;
import com.rc.common.codec.DataFrame;
import com.rc.common.constant.ChannelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.InputEvent;

/**
 * 被控端输入注入：以 {@link TransportListener} 身份挂在 {@link com.rc.client.transport.TransportChannel}
 * 上，过滤 {@link ChannelType#CONTROL} 控制帧（与 QoS echo 按 magic 区分），
 * 解码后经 AWT {@link Robot} 注入本机键鼠。
 */
public final class InputInjector implements TransportListener {

    private static final Logger log = LoggerFactory.getLogger(InputInjector.class);

    private final Robot robot;

    public InputInjector() {
        try {
            this.robot = new Robot();
        } catch (AWTException e) {
            throw new IllegalStateException("input injection unavailable", e);
        }
    }

    @Override
    public void onData(DataFrame frame) {
        if (frame.channel() != ChannelType.CONTROL) {
            return;
        }
        ControlCodec.Event e = ControlCodec.decode(frame.payload());
        if (e == null) {
            return; // 非控制帧（如 QoS PING/PONG echo）
        }
        apply(e);
    }

    private void apply(ControlCodec.Event e) {
        try {
            switch (e.type()) {
                case ControlCodec.TYPE_MOUSE_MOVE -> robot.mouseMove(e.a(), e.b());
                case ControlCodec.TYPE_MOUSE_PRESS -> {
                    robot.mouseMove(e.b(), e.c());
                    robot.mousePress(buttonMask(e.a()));
                }
                case ControlCodec.TYPE_MOUSE_RELEASE -> robot.mouseRelease(buttonMask(e.a()));
                case ControlCodec.TYPE_MOUSE_WHEEL -> robot.mouseWheel(e.a());
                case ControlCodec.TYPE_KEY_PRESS -> {
                    if (e.a() != java.awt.event.KeyEvent.VK_UNDEFINED) {
                        robot.keyPress(e.a());
                    }
                }
                case ControlCodec.TYPE_KEY_RELEASE -> {
                    if (e.a() != java.awt.event.KeyEvent.VK_UNDEFINED) {
                        robot.keyRelease(e.a());
                    }
                }
                default -> log.debug("unknown control type: {}", e.type());
            }
        } catch (Exception ex) {
            log.warn("input inject failed: {}", ex.getMessage());
        }
    }

    private static int buttonMask(int button) {
        return switch (button) {
            case ControlCodec.BUTTON_MIDDLE -> InputEvent.BUTTON2_DOWN_MASK;
            case ControlCodec.BUTTON_RIGHT -> InputEvent.BUTTON3_DOWN_MASK;
            default -> InputEvent.BUTTON1_DOWN_MASK;
        };
    }

    @Override
    public void onClosed(Throwable cause) {
        // 无独立资源需释放。
    }
}
