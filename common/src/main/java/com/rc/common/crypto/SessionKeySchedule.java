package com.rc.common.crypto;

import com.rc.common.protocol.PathType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** HKDF schedule that isolates sessions, route epochs and traffic directions. */
public final class SessionKeySchedule {

    public enum Direction {
        CONTROLLER_TO_AGENT((short) 1),
        AGENT_TO_CONTROLLER((short) 2);

        private final short id;

        Direction(short id) {
            this.id = id;
        }

        public short id() {
            return id;
        }
    }

    private SessionKeySchedule() {
    }

    public static byte[] sessionMasterKey(byte[] inviteEntropy, long sessionId,
                                          long controllerDeviceId, long agentDeviceId) {
        Objects.requireNonNull(inviteEntropy, "inviteEntropy");
        ByteBuffer salt = ByteBuffer.allocate(Long.BYTES * 3)
                .putLong(sessionId).putLong(controllerDeviceId).putLong(agentDeviceId);
        return Hkdf.derive(inviteEntropy, salt.array(), "rc-session-v2".getBytes(StandardCharsets.UTF_8), 32);
    }

    public static byte[] directionKey(byte[] sessionMasterKey, long routeEpoch,
                                      PathType pathType, Direction direction) {
        Objects.requireNonNull(sessionMasterKey, "sessionMasterKey");
        Objects.requireNonNull(pathType, "pathType");
        Objects.requireNonNull(direction, "direction");
        ByteBuffer epochInfo = ByteBuffer.allocate(32)
                .put("rc-data-v2".getBytes(StandardCharsets.UTF_8))
                .putLong(routeEpoch)
                .putInt(pathType.getNumber());
        byte[] epochKey = Hkdf.derive(sessionMasterKey, null,
                java.util.Arrays.copyOf(epochInfo.array(), epochInfo.position()), 32);
        return Hkdf.derive(epochKey, null,
                direction.name().getBytes(StandardCharsets.UTF_8), 32);
    }
}
