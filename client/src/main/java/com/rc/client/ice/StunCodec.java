package com.rc.client.ice;

import com.rc.common.model.Endpoint;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * 手写 STUN（RFC 5389）编解码。Phase 1 只支持 IPv4 与 Binding 语义，
 * 用于候选发现（srflx 映射）与打洞连通性检查（探测包本身即 STUN，天然刷新 NAT 映射）。
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |0 0|     Message Type         |         Message Length        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Magic Cookie                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     Transaction ID (96 bits)                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 */
public final class StunCodec {

    /** STUN magic cookie，用于区分 STUN 与数据帧（DataFrame 首字节为 channel）。 */
    public static final int MAGIC_COOKIE = 0x2112A442;
    public static final int HEADER_SIZE = 20;

    public static final int BINDING_REQUEST = 0x0001;
    public static final int BINDING_SUCCESS = 0x0101;
    public static final int BINDING_ERROR = 0x0111;

    private static final int ATTR_MAPPED_ADDRESS = 0x0001;
    private static final int ATTR_USERNAME = 0x0006;
    private static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;

    private static final int ATTR_HEADER = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private StunCodec() {
    }

    /** 解码结果。{@code mapped} 为 XOR-MAPPED-ADDRESS 优先、MAPPED-ADDRESS 回退。 */
    public record Decoded(int type, byte[] transactionId, Endpoint mapped, String username) {
    }

    public static byte[] newTransactionId() {
        byte[] id = new byte[12];
        RANDOM.nextBytes(id);
        return id;
    }

    public static byte[] bindingRequest(byte[] txId, String username) {
        byte[] user = username == null ? null : username.getBytes(StandardCharsets.UTF_8);
        int attrLen = user == null ? 0 : aligned(ATTR_HEADER + user.length);
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + attrLen);
        writeHeader(buf, BINDING_REQUEST, attrLen, txId);
        if (user != null) {
            writeAttribute(buf, ATTR_USERNAME, user);
        }
        return buf.array();
    }

    /** 以 XOR-MAPPED-ADDRESS 回填请求方（本端看到的对端源地址），用于打洞应答。 */
    public static byte[] bindingSuccessResponse(byte[] txId, Endpoint requester) {
        byte[] xorMapped = encodeXorMappedAddress(requester);
        int attrLen = aligned(ATTR_HEADER + xorMapped.length);
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + attrLen);
        writeHeader(buf, BINDING_SUCCESS, attrLen, txId);
        writeAttribute(buf, ATTR_XOR_MAPPED_ADDRESS, xorMapped);
        return buf.array();
    }

    /** STUN 判定：长度足够 + 首字节高 2 位为 00 + 偏移 4 处命中 magic cookie。 */
    public static boolean isStun(byte[] data) {
        if (data.length < HEADER_SIZE) {
            return false;
        }
        return (data[0] & 0xC0) == 0
                && (data[4] & 0xFF) == ((MAGIC_COOKIE >>> 24) & 0xFF)
                && (data[5] & 0xFF) == ((MAGIC_COOKIE >>> 16) & 0xFF)
                && (data[6] & 0xFF) == ((MAGIC_COOKIE >>> 8) & 0xFF)
                && (data[7] & 0xFF) == (MAGIC_COOKIE & 0xFF);
    }

    public static Decoded decode(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int type = buf.getShort() & 0x3FFF;
        int length = buf.getShort() & 0xFFFF;
        buf.getInt(); // magic cookie（由 isStun 判定过）
        byte[] txId = new byte[12];
        buf.get(txId);

        Endpoint mapped = null;
        String username = null;
        int end = Math.min(HEADER_SIZE + length, data.length);
        while (buf.position() + ATTR_HEADER <= end) {
            int attrType = buf.getShort() & 0xFFFF;
            int attrLen = buf.getShort() & 0xFFFF;
            int valueStart = buf.position();
            int valueEnd = Math.min(valueStart + attrLen, end);
            if (attrType == ATTR_XOR_MAPPED_ADDRESS && attrLen >= 8) {
                mapped = decodeMappedAddress(buf, true);
            } else if (attrType == ATTR_MAPPED_ADDRESS && attrLen >= 8) {
                mapped = decodeMappedAddress(buf, false);
            } else if (attrType == ATTR_USERNAME && attrLen > 0) {
                byte[] u = new byte[Math.min(attrLen, end - valueStart)];
                buf.get(u);
                username = new String(u, StandardCharsets.UTF_8);
            }
            buf.position(valueStart + aligned(attrLen));
            if (buf.position() > valueEnd + aligned(attrLen)) {
                buf.position(valueEnd);
            }
            if (buf.position() <= valueStart) {
                break; // 防畸形属性死循环
            }
        }
        return new Decoded(type, txId, mapped, username);
    }

    private static Endpoint decodeMappedAddress(ByteBuffer buf, boolean xor) {
        buf.get(); // reserved
        int family = buf.get() & 0xFF;
        if (family != 0x01) {
            return null; // Phase 1 仅 IPv4
        }
        int port = buf.getShort() & 0xFFFF;
        int addr = buf.getInt();
        if (xor) {
            port ^= (MAGIC_COOKIE >>> 16);
            addr ^= MAGIC_COOKIE;
        }
        return new Endpoint(ipv4ToString(addr), port);
    }

    private static byte[] encodeXorMappedAddress(Endpoint ep) {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.put((byte) 0);            // reserved
        buf.put((byte) 0x01);         // family IPv4
        buf.putShort((short) (ep.port() ^ (MAGIC_COOKIE >>> 16)));
        buf.putInt(ipv4ToInt(ep.ip()) ^ MAGIC_COOKIE);
        return buf.array();
    }

    private static void writeHeader(ByteBuffer buf, int type, int length, byte[] txId) {
        buf.putShort((short) type);
        buf.putShort((short) length);
        buf.putInt(MAGIC_COOKIE);
        buf.put(txId);
    }

    private static void writeAttribute(ByteBuffer buf, int type, byte[] value) {
        buf.putShort((short) type);
        buf.putShort((short) value.length);
        buf.put(value);
        int pad = (4 - (value.length % 4)) % 4;
        for (int i = 0; i < pad; i++) {
            buf.put((byte) 0);
        }
    }

    private static int aligned(int n) {
        return (n + 3) & ~3;
    }

    private static String ipv4ToString(int addr) {
        return ((addr >>> 24) & 0xFF) + "." + ((addr >>> 16) & 0xFF)
                + "." + ((addr >>> 8) & 0xFF) + "." + (addr & 0xFF);
    }

    private static int ipv4ToInt(String ip) {
        String[] p = ip.split("\\.");
        return (Integer.parseInt(p[0]) << 24)
                | (Integer.parseInt(p[1]) << 16)
                | (Integer.parseInt(p[2]) << 8)
                | Integer.parseInt(p[3]);
    }
}
