package com.rc.client.file;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 文件互传帧编解码（{@code ChannelType.FILE} 通道的应用层协议，非 Protobuf）。
 *
 * <pre>
 * OFFER   : [type=0x01][transferId 4B][nameLen 2B][name UTF-8][fileSize 8B]
 * CHUNK   : [type=0x02][transferId 4B][offset 8B][dataLen 4B][data]
 * COMPLETE: [type=0x03][transferId 4B][status 1B]        （0=成功 1=失败）
 * </pre>
 *
 * <p>offset/dataLen/fileSize/transferId 均按大端序编码；transferId 由发送方分配，
 * 用于双向并发传输时的分片归属关联。</p>
 */
public final class FileTransferCodec {

    public static final byte TYPE_OFFER = 0x01;
    public static final byte TYPE_CHUNK = 0x02;
    public static final byte TYPE_COMPLETE = 0x03;

    public static final byte STATUS_OK = 0x00;
    public static final byte STATUS_ERROR = 0x01;

    /** 解码后的文件消息（data 仅在 CHUNK 时非空）。 */
    public record FileMessage(byte type, int transferId, String fileName, long fileSize,
                              long offset, byte[] data, byte status) {
    }

    private FileTransferCodec() {
    }

    public static byte[] offer(int transferId, String fileName, long fileSize) {
        byte[] name = fileName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + 2 + name.length + 8);
        buf.put(TYPE_OFFER);
        buf.putInt(transferId);
        buf.putShort((short) name.length);
        buf.put(name);
        buf.putLong(fileSize);
        return buf.array();
    }

    public static byte[] chunk(int transferId, long offset, byte[] data) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + 8 + 4 + data.length);
        buf.put(TYPE_CHUNK);
        buf.putInt(transferId);
        buf.putLong(offset);
        buf.putInt(data.length);
        buf.put(data);
        return buf.array();
    }

    public static byte[] complete(int transferId, byte status) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + 1);
        buf.put(TYPE_COMPLETE);
        buf.putInt(transferId);
        buf.put(status);
        return buf.array();
    }

    /** 解析文件帧载荷；非法载荷返回 {@code null}。 */
    public static FileMessage decode(byte[] payload) {
        if (payload == null || payload.length < 1) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        byte type = buf.get();
        switch (type) {
            case TYPE_OFFER -> {
                if (buf.remaining() < 6) {
                    return null;
                }
                int transferId = buf.getInt();
                int nameLen = buf.getShort() & 0xFFFF;
                if (buf.remaining() < nameLen + 8) {
                    return null;
                }
                byte[] nameBytes = new byte[nameLen];
                buf.get(nameBytes);
                String name = new String(nameBytes, StandardCharsets.UTF_8);
                long fileSize = buf.getLong();
                return new FileMessage(type, transferId, name, fileSize, 0, null, STATUS_OK);
            }
            case TYPE_CHUNK -> {
                if (buf.remaining() < 16) {
                    return null;
                }
                int transferId = buf.getInt();
                long offset = buf.getLong();
                int dataLen = buf.getInt();
                if (dataLen < 0 || buf.remaining() < dataLen) {
                    return null;
                }
                byte[] data = new byte[dataLen];
                buf.get(data);
                return new FileMessage(type, transferId, null, 0, offset, data, STATUS_OK);
            }
            case TYPE_COMPLETE -> {
                if (buf.remaining() < 5) {
                    return null;
                }
                int transferId = buf.getInt();
                byte status = buf.get();
                return new FileMessage(type, transferId, null, 0, 0, null, status);
            }
            default -> {
                return null;
            }
        }
    }
}
