package com.rc.client.clipboard;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 剪贴板内容编解码（{@code ChannelType.CLIPBOARD} 通道的应用层协议）。
 *
 * <pre>
 * TEXT  : [type=0x01][UTF-8 bytes]
 * IMAGE : [type=0x02][PNG bytes]
 * </pre>
 *
 * <p>Phase 1 支持文本与图片两类内容；文件列表 / 富文本留后续按需扩展。</p>
 */
public final class ClipboardCodec {

    public static final byte TYPE_TEXT = 0x01;
    public static final byte TYPE_IMAGE = 0x02;

    /** 解码后的剪贴板内容（text / image 二选一非空）。 */
    public record ClipboardContent(byte type, String text, byte[] image) {
    }

    private ClipboardCodec() {
    }

    public static byte[] text(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + data.length);
        buf.put(TYPE_TEXT);
        buf.put(data);
        return buf.array();
    }

    public static byte[] image(byte[] png) {
        ByteBuffer buf = ByteBuffer.allocate(1 + png.length);
        buf.put(TYPE_IMAGE);
        buf.put(png);
        return buf.array();
    }

    /** 解析剪贴板载荷；非法载荷返回 {@code null}。 */
    public static ClipboardContent decode(byte[] payload) {
        if (payload == null || payload.length < 1) {
            return null;
        }
        byte type = payload[0];
        byte[] body = new byte[payload.length - 1];
        System.arraycopy(payload, 1, body, 0, body.length);
        return switch (type) {
            case TYPE_TEXT -> new ClipboardContent(type, new String(body, StandardCharsets.UTF_8), null);
            case TYPE_IMAGE -> new ClipboardContent(type, null, body);
            default -> null;
        };
    }
}
