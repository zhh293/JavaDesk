package com.rc.common.constant;

/**
 * 会话状态机（客户端侧）。
 *
 * <pre>
 * Idle → Online → Connecting → Probing
 *      → P2PConnected | RelayConnected
 *      → Degraded / Reconnecting → Ended
 * </pre>
 */
public enum SessionStatus {
    IDLE,
    ONLINE,
    CONNECTING,
    PROBING,
    P2P_CONNECTED,
    RELAY_CONNECTED,
    DEGRADED,
    RECONNECTING,
    ENDED
}
