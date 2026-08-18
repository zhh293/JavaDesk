package com.rc.signaling.service;

import com.rc.common.constant.ErrorCode;
import com.rc.common.constant.Thresholds;
import com.rc.common.crypto.RelayToken;
import com.rc.common.metrics.QosMetricNames;
import com.rc.common.metrics.QosMetrics;
import com.rc.common.model.Device;
import com.rc.common.model.RelayNode;
import com.rc.common.protocol.InviteReq;
import com.rc.common.protocol.InviteResp;
import com.rc.common.protocol.PathType;
import com.rc.common.protocol.RelayAllocReq;
import com.rc.common.protocol.RelayAllocResp;
import com.rc.common.protocol.SessionEnd;
import com.rc.common.protocol.Signal;
import com.rc.signaling.config.SignalingProperties;
import com.rc.signaling.dao.DeviceMapper;
import com.rc.signaling.session.ConnectionRegistry;
import com.rc.signaling.session.SessionManager;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话编排：邀请转发（E2EE 密文透传）、候选转发、会话结束清理。
 * 中继分配本轮未落地，RelayAlloc 回 {@code RC-6101}。
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final ConnectionRegistry connections;
    private final SessionManager sessionManager;
    private final DeviceMapper deviceMapper;
    private final SignalingProperties props;
    private final RelayManager relayManager;
    private final AuditService auditService;

    /** 会话级中继令牌缓存：首次签发后复用，避免双方先后申请 / 跨协议降级时令牌漂移。 */
    private final Map<Long, String> relayTokens = new ConcurrentHashMap<>();

    /** 会话 → 承载中继节点 ID：会话结束时反馈分配成败，驱动节点质量评分。 */
    private final Map<Long, String> sessionRelayNode = new ConcurrentHashMap<>();

    public SessionService(ConnectionRegistry connections, SessionManager sessionManager,
                          DeviceMapper deviceMapper, SignalingProperties props,
                          RelayManager relayManager, AuditService auditService) {
        this.connections = connections;
        this.sessionManager = sessionManager;
        this.deviceMapper = deviceMapper;
        this.props = props;
        this.relayManager = relayManager;
        this.auditService = auditService;
    }

    /** 控制端发起邀请：定位被控端通道，建会话并透传 InviteReq（密文密码）。 */
    public void handleInvite(Channel controllerChannel, long controllerDeviceId, Signal signal) {
        InviteReq req = signal.getInviteReq();
        long sessionId = req.getSessionId();
        Device target = deviceMapper.findByDeviceCode(req.getTargetDeviceCode());
        if (target == null) {
            sendInviteResp(controllerChannel, sessionId, false,
                    ErrorCode.TARGET_OFFLINE.code(), "target device not found");
            return;
        }
        Channel agentChannel = connections.channelOf(target.getId());
        if (agentChannel == null || !agentChannel.isActive()) {
            sendInviteResp(controllerChannel, sessionId, false,
                    ErrorCode.TARGET_OFFLINE.code(), "target device offline");
            return;
        }
        sessionManager.create(sessionId, controllerDeviceId, target.getId(), controllerChannel, agentChannel);
        auditService.record(null, controllerDeviceId, AuditService.ACTION_INVITE,
                "session=" + sessionId + " target=" + req.getTargetDeviceCode());
        agentChannel.writeAndFlush(signal);
    }

    public void handleInviteResp(Channel agentChannel, Signal signal) {
        SessionManager.SessionRecord record = sessionManager.get(signal.getInviteResp().getSessionId());
        if (record == null) {
            return;
        }
        record.controllerChannel().writeAndFlush(signal);
    }

    public void handleCandidate(Channel fromChannel, Signal signal) {
        SessionManager.SessionRecord record = sessionManager.get(signal.getCandidateMsg().getSessionId());
        if (record == null) {
            return;
        }
        Channel peer = record.peerOf(fromChannel);
        if (peer != null) {
            peer.writeAndFlush(signal);
        }
    }

    public void handleSessionEnd(Channel fromChannel, Signal signal) {
        SessionEnd end = signal.getSessionEnd();
        long sessionId = end.getSessionId();
        SessionManager.SessionRecord record = sessionManager.remove(sessionId);
        relayTokens.remove(sessionId);
        if (record == null) {
            return;
        }
        // 中继质量反馈：会话结束时按结束原因回填承载节点的分配成败（0=成功，非 0=失败）。
        String nodeId = sessionRelayNode.remove(sessionId);
        if (nodeId != null) {
            relayManager.recordAllocResult(nodeId, end.getErrorCode() == 0);
        }
        auditService.record(null, record.controllerDeviceId(), AuditService.ACTION_SESSION_END,
                "session=" + sessionId + " reason=" + end.getReason() + " code=" + end.getErrorCode());
        Channel peer = record.peerOf(fromChannel);
        if (peer != null) {
            peer.writeAndFlush(signal);
        }
    }

    /**
     * 降级/回切通知：客户端把数据面路径迁移上报给信令，用于审计与中继质量评分。
     * {@code to_path} 落到 RELAY_* 视为中继承载成功；从 RELAY 迁回 P2P 表示中继任务正常结束。
     */
    public void handlePathSwitch(Channel fromChannel, Signal signal) {
        com.rc.common.protocol.PathSwitchNotify notify = signal.getPathSwitch();
        long sessionId = notify.getSessionId();
        SessionManager.SessionRecord record = sessionManager.get(sessionId);
        if (record == null) {
            return;
        }
        PathType to = notify.getToPath();
        if (to == PathType.RELAY_UDP || to == PathType.RELAY_TCP || to == PathType.RELAY_WS) {
            String nodeId = sessionRelayNode.get(sessionId);
            if (nodeId != null) {
                relayManager.recordAllocResult(nodeId, true);
            }
        }
        auditService.record(null, record.controllerDeviceId(), AuditService.ACTION_PATH_SWITCH,
                "session=" + sessionId + " from=" + notify.getFromPath() + " to=" + to
                        + " reason=" + notify.getReason());
        // 通知对端（数据面迁移通常由一端驱动，另一端需要同步感知）。
        Channel peer = record.peerOf(fromChannel);
        if (peer != null) {
            peer.writeAndFlush(signal);
        }
    }

    /**
     * 中继分配：按客户端请求的 {@code path_type}（RELAY_UDP/TCP/WS）选择对应端口，签发一次性
     * 短 TTL 令牌并广播给会话双方。令牌按会话幂等缓存（跨协议降级复用同一令牌），
     * 双方凭同一令牌 JOIN 任一协议的中继端口，中继据此绑定两端并密文透传。
     */
    public void handleRelayAlloc(Channel channel, Signal signal) {
        RelayAllocReq req = signal.getRelayAllocReq();
        long sessionId = req.getSessionId();
        SessionManager.SessionRecord record = sessionManager.get(sessionId);
        if (record == null) {
            RelayAllocResp resp = RelayAllocResp.newBuilder()
                    .setSessionId(sessionId)
                    .setOk(false)
                    .setErrorCode(ErrorCode.RELAY_ALLOC_FAIL.code())
                    .setErrorMessage("session not found")
                    .build();
            channel.writeAndFlush(Signal.newBuilder()
                    .setSessionId(sessionId)
                    .setTimestamp(System.currentTimeMillis())
                    .setRelayAllocResp(resp)
                    .build());
            return;
        }

        PathType pathType = req.getPathType() == PathType.PATH_UNKNOWN ? PathType.RELAY_UDP : req.getPathType();
        // 就近调度：按客户端期望 region 择优在线节点；无节点回退静态单节点配置（dev）。
        RelayNode node = relayManager.selectBest(req.getRegion());
        String host = node != null ? node.getHost() : props.getRelayHost();
        int port = node != null ? node.portFor(pathType) : relayPortFor(pathType);
        boolean tls = node != null ? node.isTls() : props.isRelayTls();
        String token = relayTokens.computeIfAbsent(sessionId, id ->
                RelayToken.sign(id, props.getRelaySecret().getBytes(StandardCharsets.UTF_8),
                        Thresholds.SESSION_TOKEN_TTL_SECONDS));

        RelayAllocResp resp = RelayAllocResp.newBuilder()
                .setSessionId(sessionId)
                .setOk(true)
                .setRelayHost(host)
                .setRelayPort(port)
                .setToken(token)
                .setTokenTtlSeconds(Thresholds.SESSION_TOKEN_TTL_SECONDS)
                .setPathType(pathType)
                .setTls(tls)
                .build();
        Signal out = Signal.newBuilder()
                .setSessionId(sessionId)
                .setTimestamp(System.currentTimeMillis())
                .setRelayAllocResp(resp)
                .build();
        record.controllerChannel().writeAndFlush(out);
        record.agentChannel().writeAndFlush(out);
        if (node != null) {
            sessionRelayNode.put(sessionId, node.getNodeId());
        }
        QosMetrics.increment(QosMetricNames.RELAY_ALLOC_TOTAL, "path", pathType.name());
        auditService.record(null, record.controllerDeviceId(), AuditService.ACTION_RELAY_ALLOC,
                "session=" + sessionId + " path=" + pathType + " node="
                        + (node != null ? node.getNodeId() : "static"));
        log.info("relay allocated: session={} path={} node={} relay={}:{}", sessionId, pathType,
                node != null ? node.getNodeId() : "static", host, port);
    }

    private int relayPortFor(PathType pathType) {
        return switch (pathType) {
            case RELAY_TCP -> props.getRelayTcpPort();
            case RELAY_WS -> props.getRelayWsPort();
            default -> props.getRelayPort();
        };
    }

    private void sendInviteResp(Channel channel, long sessionId, boolean accepted, int code, String message) {
        InviteResp resp = InviteResp.newBuilder()
                .setSessionId(sessionId)
                .setAccepted(accepted)
                .setErrorCode(code)
                .setErrorMessage(message == null ? "" : message)
                .build();
        channel.writeAndFlush(Signal.newBuilder()
                .setSessionId(sessionId)
                .setTimestamp(System.currentTimeMillis())
                .setInviteResp(resp)
                .build());
    }
}
