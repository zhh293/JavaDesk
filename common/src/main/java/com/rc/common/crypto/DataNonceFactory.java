package com.rc.common.crypto;

import java.nio.ByteBuffer;

/** Deterministic 96-bit nonce: epoch-low32 | direction16 | stream16 | sequence32. */
public final class DataNonceFactory {
    private DataNonceFactory() {
    }

    public static byte[] create(long routeEpoch, short directionId, short streamId, long sequence) {
        if (routeEpoch < 0 || sequence < 0 || sequence > 0xffff_ffffL) {
            throw new IllegalArgumentException("epoch/sequence out of range");
        }
        return ByteBuffer.allocate(AesGcmCipher.IV_LENGTH)
                .putInt((int) routeEpoch)
                .putShort(directionId)
                .putShort(streamId)
                .putInt((int) sequence)
                .array();
    }
}
