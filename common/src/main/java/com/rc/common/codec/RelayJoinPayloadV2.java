package com.rc.common.codec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Bounded JOIN payload carrying the signed assignment ticket and per-connection nonce. */
public record RelayJoinPayloadV2(String ticket, String connectionNonce) {
    private static final int MAX_TICKET = 8192;
    private static final int MAX_NONCE = 128;

    public RelayJoinPayloadV2 {
        if (ticket == null || ticket.isBlank() || ticket.length() > MAX_TICKET) {
            throw new IllegalArgumentException("invalid relay ticket");
        }
        if (connectionNonce == null || connectionNonce.isBlank() || connectionNonce.length() > MAX_NONCE) {
            throw new IllegalArgumentException("invalid connection nonce");
        }
    }

    public byte[] encode() {
        byte[] ticketBytes = ticket.getBytes(StandardCharsets.UTF_8);
        byte[] nonceBytes = connectionNonce.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(2 + ticketBytes.length + 1 + nonceBytes.length)
                .putShort((short) ticketBytes.length).put(ticketBytes)
                .put((byte) nonceBytes.length).put(nonceBytes).array();
    }

    public static RelayJoinPayloadV2 decode(byte[] payload) {
        if (payload == null || payload.length < 4 || payload.length > MAX_TICKET + MAX_NONCE + 3) {
            throw new IllegalArgumentException("invalid relay join payload size");
        }
        ByteBuffer in = ByteBuffer.wrap(payload);
        int ticketLength = Short.toUnsignedInt(in.getShort());
        if (ticketLength == 0 || ticketLength > MAX_TICKET || in.remaining() < ticketLength + 1) {
            throw new IllegalArgumentException("invalid relay ticket length");
        }
        byte[] ticket = new byte[ticketLength];
        in.get(ticket);
        int nonceLength = Byte.toUnsignedInt(in.get());
        if (nonceLength == 0 || nonceLength > MAX_NONCE || in.remaining() != nonceLength) {
            throw new IllegalArgumentException("invalid relay nonce length");
        }
        byte[] nonce = new byte[nonceLength];
        in.get(nonce);
        return new RelayJoinPayloadV2(new String(ticket, StandardCharsets.UTF_8),
                new String(nonce, StandardCharsets.UTF_8));
    }
}
