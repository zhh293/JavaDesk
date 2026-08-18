package com.rc.client.ice;

import com.rc.common.model.Endpoint;
import com.rc.common.protocol.NatType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * NAT 类型推断（RFC 5780 思路的简化版）。
 *
 * <p>用两个 STUN 服务器比较本端映射端口：映射相同 ⇒ 端点无关映射（锥形），
 * 不同 ⇒ 端点依赖映射（对称）。单服务器 / 查询失败回退 {@link NatType#NAT_UNKNOWN}。</p>
 *
 * <p>过滤行为（全锥 / 受限 / 端口受限锥）需 CHANGE-REQUEST 才能区分，Phase 1 不实现，
 * 锥形统一近似记为 {@link NatType#NAT_FULL_CONE}，仅作打洞成功率预判提示，不阻断流程。</p>
 */
public final class NatTypeDetector {

    private static final Logger log = LoggerFactory.getLogger(NatTypeDetector.class);

    private NatTypeDetector() {
    }

    public static NatType detect(UdpSocket socket, List<Endpoint> stunServers, long timeoutMs) {
        if (stunServers.size() < 2) {
            return NatType.NAT_UNKNOWN;
        }
        try {
            Endpoint first = StunClient.query(socket, stunServers.get(0), timeoutMs);
            Endpoint second = StunClient.query(socket, stunServers.get(1), timeoutMs);
            if (first == null || second == null) {
                return NatType.NAT_UNKNOWN;
            }
            if (first.ip().equals(second.ip()) && first.port() == second.port()) {
                return NatType.NAT_FULL_CONE;
            }
            return NatType.NAT_SYMMETRIC;
        } catch (Exception e) {
            log.debug("NAT type detection failed: {}", e.getMessage());
            return NatType.NAT_UNKNOWN;
        }
    }
}
