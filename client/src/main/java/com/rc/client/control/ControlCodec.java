package com.rc.client.control;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 键鼠控制帧编解码（数据面 {@code CONTROL} 通道载荷）。
 *
 * <p>帧格式：<pre>magic(2B "CT") | type(1B) | a(4B) | b(4B) | c(4B)</pre>
 * 以独立 magic {@code 0x43 0x54} 与 QoS PING/PONG echo（{@code 0x52 0x51}）在
 * 同一 CONTROL 通道上区分，两者由各自监听器按 magic 前缀解复用。</p>
 */
public final class ControlCodec {

    private static final byte MAGIC_0 = 0x43; // 'C'
    private static final byte MAGIC_1 = 0x54; // 'T'

    public static final int TYPE_MOUSE_MOVE = 1;
    public static final int TYPE_MOUSE_PRESS = 2;
    public static final int TYPE_MOUSE_RELEASE = 3;
    public static final int TYPE_MOUSE_WHEEL = 4;
    public static final int TYPE_KEY_PRESS = 5;
    public static final int TYPE_KEY_RELEASE = 6;

    public static final int BUTTON_LEFT = 1;
    public static final int BUTTON_MIDDLE = 2;
    public static final int BUTTON_RIGHT = 3;

    /** 解码后的控制事件：type + 三个整型参数（各类型语义见对应构造方法）。 */
    public record Event(int type, int a, int b, int c) {
    }

    private ControlCodec() {
    }

    public static byte[] mouseMove(int x, int y) {
        return encode(TYPE_MOUSE_MOVE, x, y, 0);
    }

    public static byte[] mousePress(int button, int x, int y) {
        return encode(TYPE_MOUSE_PRESS, button, x, y);
    }

    public static byte[] mouseRelease(int button, int x, int y) {
        return encode(TYPE_MOUSE_RELEASE, button, x, y);
    }

    public static byte[] mouseWheel(int rotation) {
        return encode(TYPE_MOUSE_WHEEL, rotation, 0, 0);
    }

    public static byte[] keyPress(int awtKeyCode) {
        return encode(TYPE_KEY_PRESS, awtKeyCode, 0, 0);
    }

    public static byte[] keyRelease(int awtKeyCode) {
        return encode(TYPE_KEY_RELEASE, awtKeyCode, 0, 0);
    }

    private static byte[] encode(int type, int a, int b, int c) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(15);
             DataOutputStream out = new DataOutputStream(bos)) {
            out.writeByte(MAGIC_0);
            out.writeByte(MAGIC_1);
            out.writeByte(type);
            out.writeInt(a);
            out.writeInt(b);
            out.writeInt(c);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("control encode failed", e);
        }
    }

    /** 解码控制帧；非控制帧（如 QoS echo）返回 {@code null}。 */
    public static Event decode(byte[] payload) {
        if (payload == null || payload.length < 15) {
            return null;
        }
        if (payload[0] != MAGIC_0 || payload[1] != MAGIC_1) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            in.readByte();
            in.readByte();
            int type = in.readByte();
            int a = in.readInt();
            int b = in.readInt();
            int c = in.readInt();
            return new Event(type, a, b, c);
        } catch (IOException e) {
            return null;
        }
    }
}
