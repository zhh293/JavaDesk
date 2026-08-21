package com.rc.common.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayWindowTest {
    @Test
    void acceptsOutOfOrderOnceAndRejectsDuplicatesAndOldPackets() {
        ReplayWindow window = new ReplayWindow();
        assertThat(window.accept(100)).isTrue();
        assertThat(window.accept(102)).isTrue();
        assertThat(window.accept(101)).isTrue();
        assertThat(window.accept(101)).isFalse();
        assertThat(window.accept(30)).isFalse();
    }

    @Test
    void nonceLayoutIsDeterministicAndSeparatesDirectionsAndStreams() {
        assertThat(DataNonceFactory.create(9, (short) 1, (short) 2, 3))
                .hasSize(12)
                .isNotEqualTo(DataNonceFactory.create(9, (short) 2, (short) 2, 3))
                .isNotEqualTo(DataNonceFactory.create(9, (short) 1, (short) 3, 3))
                .isNotEqualTo(DataNonceFactory.create(9, (short) 1, (short) 2, 4));
    }
}
