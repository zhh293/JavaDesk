package com.rc.common.constant;

/**
 * 统一失败码，编码规则 {@code RC-阶段-序号}，便于排障聚合。
 *
 * <p>阶段：1xxx 设备上线 / 2xxx 会话创建 / 3xxx 候选交换 / 4xxx 打洞建链 /
 * 5xxx 业务通道 / 6xxx 重协商迁移 / 7xxx 重连 / 8xxx 结束。</p>
 */
public enum ErrorCode {

    UNKNOWN(-1, "未知错误"),
    SUCCESS(0, "成功"),

    // 设备上线
    AUTH_INVALID(1001, "鉴权失败"),
    DEVICE_CONFLICT(1002, "设备 ID 冲突或重复登录"),
    HEARTBEAT_TIMEOUT(1003, "心跳超时"),
    USERNAME_EXISTS(1004, "用户名已存在"),
    TOKEN_EXPIRED(1005, "令牌已过期"),

    // 会话创建
    TARGET_OFFLINE(2001, "目标设备离线"),
    POLICY_DENY(2002, "权限不足或风控拒绝"),
    SESSION_QUOTA(2003, "并发会话超限"),

    // 候选交换
    CANDIDATE_GATHER_TIMEOUT(3001, "候选收集超时"),
    CANDIDATE_EMPTY(3002, "候选为空"),
    SIGNALING_TIMEOUT(3003, "信令超时"),

    // 打洞 / 探测 / 建链
    PUNCH_TIMEOUT(4001, "打洞超时"),
    CHECK_FAILED(4002, "连通性检查全失败"),
    HANDSHAKE_TIMEOUT(4101, "建链握手超时"),
    FINGERPRINT_MISMATCH(4102, "指纹不匹配，疑似劫持"),

    // 业务通道
    CHANNEL_OPEN_TIMEOUT(5001, "通道建立失败"),
    CODEC_NEGOTIATION_FAIL(5002, "编解码协商失败"),
    KEEPALIVE_LOST(5101, "保活丢失"),

    // 重协商 / 迁移
    RENEGOTIATION_CONFLICT(6001, "重协商冲突"),
    MIGRATION_FAIL(6002, "路径迁移失败"),
    RELAY_ALLOC_FAIL(6101, "中继分配失败"),

    // 重连
    RESUME_TOKEN_EXPIRED(7001, "恢复票据过期"),
    RETRY_BUDGET_EXHAUSTED(7002, "重连预算耗尽"),

    // 结束
    REMOTE_HANGUP(8001, "对端挂断");

    private final int code;
    private final String description;

    ErrorCode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }

    /** 对外展示码，形如 {@code RC-1001}。 */
    public String rcCode() {
        return "RC-" + code;
    }

    public static ErrorCode of(int code) {
        for (ErrorCode e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return UNKNOWN;
    }
}
