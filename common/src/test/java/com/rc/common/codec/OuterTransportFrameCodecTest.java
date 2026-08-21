package com.rc.common.codec;

import com.rc.common.constant.ChannelType;
import com.rc.common.constant.FrameType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OuterTransportFrameCodecTest {
    @Test
    void roundTripsOuterAndInnerFramesAndRejectsLengthTampering() {
        EncryptedInnerFrame inner = new EncryptedInnerFrame(ChannelType.FILE, FrameType.DATA,
                3, 42, 99, new byte[]{1, 2, 3});
        assertThat(OuterTransportFrameCodec.decodeInner(OuterTransportFrameCodec.encodeInner(inner)))
                .isEqualTo(inner);

        OuterTransportFrame outer = new OuterTransportFrame((byte) 2, 8, 4,
                (short) 1, (short) 3, 42, new byte[]{9, 8});
        byte[] encoded = OuterTransportFrameCodec.encode(outer);
        assertThat(OuterTransportFrameCodec.decode(encoded)).isEqualTo(outer);
        encoded[encoded.length - 5] = 99;
        assertThatThrownBy(() -> OuterTransportFrameCodec.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
