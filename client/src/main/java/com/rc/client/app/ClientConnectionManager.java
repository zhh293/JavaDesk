package com.rc.client.app;

import com.rc.client.ice.IceAgent;
import com.rc.client.monitor.QosListener;
import com.rc.client.monitor.QosMonitor;
import com.rc.client.security.DeviceIdentity;
import com.rc.client.signaling.DeviceInfoClient;
import com.rc.client.signaling.SignalingClient;
import com.rc.client.signaling.SignalingClientConfig;
import com.rc.client.signaling.SignalingListener;
import com.rc.client.transport.QuicTransportChannel;
import com.rc.client.transport.QuicTransportEndpoint;
import com.rc.client.transport.RelayTcpTransportChannel;
import com.rc.client.transport.RelayTransportChannel;
import com.rc.client.transport.RelayWsTransportChannel;
import com.rc.client.transport.TransportChannel;
import com.rc.client.transport.SwitchableTransportChannel;
import com.rc.client.transport.SecureTransportChannel;
import com.rc.client.transport.UdpTransportChannel;
import com.rc.common.constant.ErrorCode;
import com.rc.common.constant.SessionStatus;
import com.rc.common.constant.Thresholds;
import com.rc.common.crypto.CryptoService;
import com.rc.common.crypto.RsaCipher;
import com.rc.common.crypto.RelayTicketV2.PeerRole;
import com.rc.common.metrics.QosMetricNames;
import com.rc.common.metrics.QosMetrics;
import com.rc.common.model.ChannelInfo;
import com.rc.common.model.Endpoint;
import com.rc.common.model.IceCandidate;
import com.rc.common.protocol.CandidateMsg;
import com.rc.common.protocol.Heartbeat;
import com.rc.common.protocol.InviteReq;
import com.rc.common.protocol.InviteResp;
import com.rc.common.protocol.NatType;
import com.rc.common.protocol.PathSwitchNotify;
import com.rc.common.protocol.PathType;
import com.rc.common.protocol.RegisterReq;
import com.rc.common.protocol.RelayAllocReq;
import com.rc.common.protocol.RelayAssignmentV2;
import com.rc.common.protocol.RelayPeerRole;
import com.rc.common.protocol.RelayReadyV2;
import com.rc.common.protocol.RelayFailureReport;
import com.rc.common.protocol.RouteAbortV2;
import com.rc.common.protocol.RouteCommitV2;
import com.rc.common.protocol.SessionEnd;
import com.rc.common.protocol.Signal;
import com.rc.common.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端连接编排 / 会话状态机。
 *
 * <p>显式跟踪 {@link SessionStatus}：Online → Connecting → Probing →
 * P2PConnected | RelayConnected → Degraded → Ended。P2P 优先，打洞失败降级中继；
 * 运行中经 {@link QosMonitor} 检测保活丢失 / 通道关闭后上报信令服务器，
 * 所有运行期路径迁移均由服务端以 PREPARE/READY/COMMIT 协调。</p>
 */
public final class ClientConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ClientConnectionManager.class);

    /** 会话结果回调（上层 UI / 采集据此接管数据面）。 */
    public interface SessionListener {
        /** 数据面建立，{@code controller} 表示本端为控制端（发起方）。 */
        void onConnected(String remoteDeviceCode, TransportChannel channel, boolean controller);

        void onFailed(ErrorCode code, String message);

        void onRemoteEnded(String reason);

        /** 数据面路径切换（P2P ↔ Relay）通知，默认空实现。 */
        default void onPathChanged(PathType pathType, ChannelInfo info) {
        }

        /** 数据面通道切换（make-before-break 回切），上层据此把业务重绑到新 channel。 */
        default void onChannelSwitched(TransportChannel channel, boolean controller) {
        }

        /** 收到被控邀请（仅被控端），UI 据此弹确认框后调用 {@link ClientConnectionManager#acceptInvite}。 */
        default void onInvite(String controllerDeviceCode, long sessionId) {
        }
    }

    private enum Role { CONTROLLER, AGENT }
    private record PreparedClientRoute(RelayAssignmentV2 assignment, TransportChannel channel) { }

    private static final class SessionContext {
        final long sessionId;
        final Role role;
        final String remoteDeviceCode;
        final byte[] sessionKey;
        final java.util.concurrent.CompletableFuture<Void> inviteAccepted = new java.util.concurrent.CompletableFuture<>();
        volatile java.util.concurrent.CompletableFuture<TransportChannel> relayCommitted = new java.util.concurrent.CompletableFuture<>();
        final Map<String, PreparedClientRoute> preparedChannels = new ConcurrentHashMap<>();
        final AtomicBoolean migrationInFlight = new AtomicBoolean();
        final List<IceCandidate> remoteCandidates = new CopyOnWriteArrayList<>();
        volatile SessionStatus status = SessionStatus.IDLE;
        volatile PathType dataPath = PathType.PATH_UNKNOWN;
        volatile IceAgent iceAgent;
        volatile TransportChannel channel;
        volatile SwitchableTransportChannel stableChannel;
        volatile long routeEpoch;
        volatile String assignmentId = "";
        volatile String relayNodeId = "";
        volatile QosMonitor qosMonitor;

        SessionContext(long sessionId, Role role, String remoteDeviceCode, byte[] sessionKey) {
            this.sessionId = sessionId;
            this.role = role;
            this.remoteDeviceCode = remoteDeviceCode;
            this.sessionKey = sessionKey;
        }

        void close() {
            if (qosMonitor != null) {
                qosMonitor.close();
                qosMonitor = null;
            }
            if (stableChannel != null) {
                stableChannel.close();
            } else if (channel != null) {
                channel.close();
            }
            preparedChannels.values().forEach(route -> route.channel().close());
            preparedChannels.clear();
            if (iceAgent != null) {
                iceAgent.close();
            }
        }
    }

    private static final class InviteRejectedException extends RuntimeException {
        final ErrorCode code;

        InviteRejectedException(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 待确认的被控邀请（已通过密码校验，等待用户接受 / 拒绝）。 */
    private record PendingInvite(String controllerDeviceCode, byte[] sessionKey) {
    }

    private final DeviceIdentity identity;
    private final SignalingClientConfig signalingConfig;
    private final List<Endpoint> stunServers;
    private final String baseUrl;
    private final String accessToken;
    private final SessionListener sessionListener;
    private final long stunTimeoutMs;
    private volatile boolean quicEnabled = false;

    private final ScheduledExecutorService worker;
    private final ScheduledExecutorService heartbeat;
    private final Map<Long, SessionContext> sessions = new ConcurrentHashMap<>();
    private final Map<PathType, AtomicInteger> pathCounts = new EnumMap<>(PathType.class);

    private SignalingClient signaling;
    private volatile long deviceId;
    private volatile long connectionEpoch;
    private final String clientInstanceId = java.util.UUID.randomUUID().toString();
    private final Map<Long, PendingInvite> pendingInvites = new ConcurrentHashMap<>();
    private volatile SessionContext activeSession;

    public ClientConnectionManager(DeviceIdentity identity,
                                   SignalingClientConfig signalingConfig,
                                   List<Endpoint> stunServers,
                                   String baseUrl,
                                   String accessToken,
                                   SessionListener sessionListener) {
        this.identity = identity;
        this.signalingConfig = signalingConfig;
        this.stunServers = stunServers;
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
        this.sessionListener = sessionListener;
        this.stunTimeoutMs = Thresholds.PROBE_TIMEOUT_MAX_MS;
        this.worker = newScheduledExecutor("rc-conn-worker", 2);
        this.heartbeat = newScheduledExecutor("rc-conn-heartbeat", 1);
        registerMetrics();
    }

    private void registerMetrics() {
        QosMetrics.gauge(QosMetricNames.SESSIONS_ACTIVE, sessions::size);
        for (PathType p : PathType.values()) {
            if (p == PathType.PATH_UNKNOWN) {
                continue;
            }
            AtomicInteger count = pathCounts.computeIfAbsent(p, k -> new AtomicInteger());
            QosMetrics.gauge(QosMetricNames.PATHS_CURRENT, count::get, "path", p.name());
        }
    }

    public void start() {
        signaling = new SignalingClient(signalingConfig, signalingListener());
        signaling.start();
        startHeartbeat();
    }

    /**
     * 启用 / 关闭 P2P 打洞成功后的 QUIC 数据面（Phase 2）。默认关闭，落地 kwik
     * socket 交接 + 服务端握手后开启；关闭时回退裸 UDP（{@link UdpTransportChannel}）。
     */
    public void setQuicEnabled(boolean quicEnabled) {
        this.quicEnabled = quicEnabled;
    }

    public void stop() {
        heartbeat.shutdownNow();
        worker.shutdownNow();
        sessions.values().forEach(SessionContext::close);
        sessions.clear();
        if (signaling != null) {
            signaling.stop();
        }
    }

    /** 控制端：发起对目标设备的连接。 */
    public void connect(String targetDeviceCode, String password) {
        worker.execute(() -> doConnect(targetDeviceCode, password));
    }

    // ---------- 控制端流程 ----------

    private void doConnect(String targetDeviceCode, String password) {
        try {
            DeviceInfoClient client = new DeviceInfoClient(baseUrl);
            DeviceInfoClient.DeviceInfo info = client.fetch(targetDeviceCode, accessToken);
            if (!info.online()) {
                fail(null, ErrorCode.TARGET_OFFLINE, "target device offline");
                return;
            }
            PublicKey targetPublicKey = RsaCipher.decodePublicKey(info.publicKey());
            CryptoService.SealedPassword sealed =
                    CryptoService.sealConnectionPassword(targetPublicKey, password.getBytes(StandardCharsets.UTF_8));
            byte[] sessionKey = CryptoService.deriveSessionKey(sealed.entropy());
            long sessionId = IdGenerator.newSessionId();

            SessionContext ctx = new SessionContext(sessionId, Role.CONTROLLER, targetDeviceCode, sessionKey);
            sessions.put(sessionId, ctx);
            activeSession = ctx;
            QosMetrics.increment(QosMetricNames.SESSIONS_TOTAL);
            transition(ctx, SessionStatus.CONNECTING);

            InviteReq invite = InviteReq.newBuilder()
                    .setSessionId(sessionId)
                    .setControllerDeviceCode(identity.deviceCode())
                    .setTargetDeviceCode(targetDeviceCode)
                    .setEncryptedPassword(com.google.protobuf.ByteString.copyFrom(sealed.ciphertext()))
                    .build();
            signaling.send(Signal.newBuilder()
                    .setSessionId(sessionId)
                    .setTimestamp(System.currentTimeMillis())
                    .setTraceId(IdGenerator.newTraceId())
                    .setInviteReq(invite)
                    .build());

            try {
                ctx.inviteAccepted.get(Thresholds.CONNECT_BUDGET_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                fail(ctx, ErrorCode.SIGNALING_TIMEOUT, "invite ack timeout");
                return;
            } catch (InviteRejectedException e) {
                fail(ctx, e.code, e.getMessage());
                return;
            } catch (Exception e) {
                fail(ctx, ErrorCode.UNKNOWN, e.getMessage());
                return;
            }
            establishP2P(ctx);
        } catch (Exception e) {
            log.warn("connect failed", e);
            fail(null, ErrorCode.UNKNOWN, e.getMessage());
        }
    }

    // ---------- 被控端流程 ----------

    private void doHandleInvite(Signal signal) {
        InviteReq req = signal.getInviteReq();
        long sessionId = req.getSessionId();
        try {
            CryptoService.UnsealedPassword unsealed =
                    CryptoService.decryptConnectionPassword(identity.privateKey(),
                            req.getEncryptedPassword().toByteArray());
            if (!identity.verifyPassword(unsealed.password())) {
                sendInviteResp(sessionId, false, ErrorCode.AUTH_INVALID.code(), "connection password mismatch");
                return;
            }
            byte[] sessionKey = CryptoService.deriveSessionKey(unsealed.entropy());
            // 密码校验通过后挂起，等待用户确认（UI 弹框）再决定接受 / 拒绝
            pendingInvites.put(sessionId, new PendingInvite(req.getControllerDeviceCode(), sessionKey));
            sessionListener.onInvite(req.getControllerDeviceCode(), sessionId);
        } catch (Exception e) {
            log.warn("invite handling failed", e);
            sendInviteResp(sessionId, false, ErrorCode.UNKNOWN.code(), "invite decrypt failed");
        }
    }

    /** 被控端响应用户对邀请的接受 / 拒绝。 */
    public void acceptInvite(long sessionId, boolean accept) {
        PendingInvite pending = pendingInvites.remove(sessionId);
        if (pending == null) {
            return;
        }
        if (!accept) {
            sendInviteResp(sessionId, false, ErrorCode.POLICY_DENY.code(), "rejected by user");
            return;
        }
        SessionContext ctx = new SessionContext(sessionId, Role.AGENT, pending.controllerDeviceCode(), pending.sessionKey());
        sessions.put(sessionId, ctx);
        activeSession = ctx;
        QosMetrics.increment(QosMetricNames.SESSIONS_TOTAL);
        transition(ctx, SessionStatus.CONNECTING);
        sendInviteResp(sessionId, true, 0, "");
        establishP2P(ctx);
    }

    private void sendInviteResp(long sessionId, boolean accepted, int code, String message) {
        InviteResp resp = InviteResp.newBuilder()
                .setSessionId(sessionId)
                .setAccepted(accepted)
                .setErrorCode(code)
                .setErrorMessage(message == null ? "" : message)
                .build();
        signaling.send(Signal.newBuilder()
                .setSessionId(sessionId)
                .setTimestamp(System.currentTimeMillis())
                .setInviteResp(resp)
                .build());
    }

    // ---------- 候选交换 + 打洞 ----------

    private void establishP2P(SessionContext ctx) {
        worker.execute(() -> {
            transition(ctx, SessionStatus.PROBING);
            ctx.iceAgent = new IceAgent(stunServers);
            List<IceCandidate> local = ctx.iceAgent.gatherCandidates(stunTimeoutMs);
            for (IceCandidate candidate : local) {
                signaling.send(candidateSignal(ctx.sessionId, candidate));
            }
            // 短暂窗口收集对端候选后开始打洞
            worker.schedule(() -> {
                if (ctx.remoteCandidates.isEmpty()) {
                    log.warn("no remote candidate, falling back to relay, session={}", ctx.sessionId);
                    fallbackToRelay(ctx);
                    return;
                }
                QosMetrics.increment(QosMetricNames.PUNCH_ATTEMPTS_TOTAL);
                ctx.iceAgent.connect(ctx.remoteCandidates, ctx.sessionKey, Thresholds.CONNECT_BUDGET_MS)
                        .whenComplete((result, t) -> {
                            if (t != null) {
                                log.warn("punch failed ({}), falling back to relay, session={}",
                                        t.getMessage(), ctx.sessionId);
                                fallbackToRelay(ctx);
                            } else {
                                QosMetrics.increment(QosMetricNames.PUNCH_SUCCESS_TOTAL);
                                TransportChannel channel = openP2PChannel(ctx, result);
                                if (channel == null) {
                                    fallbackToRelay(ctx);
                                    return;
                                }
                                activateInitialChannel(ctx, channel, PathType.P2P);
                                trackPath(ctx, PathType.P2P);
                                transition(ctx, SessionStatus.P2P_CONNECTED);
                                attachQosMonitor(ctx, ctx.stableChannel);
                                log.info("P2P established, session={} peer={}", ctx.sessionId, result.peer());
                                sessionListener.onConnected(ctx.remoteDeviceCode, ctx.stableChannel,
                                        ctx.role == Role.CONTROLLER);
                            }
                        });
            }, Thresholds.CANDIDATE_GATHER_WINDOW_MS, TimeUnit.MILLISECONDS);
        });
    }

    /**
     * 在打洞产出的 socket 上开启数据面：QUIC 开启时尝试建立 QUIC，否则直接使用裸 UDP。
     * 被控端以设备 RSA 密钥派生自签证书充当 QUIC 服务端。
     *
     * <p>QUIC 建立失败时返回 {@code null}：打洞 socket 已交棒给 kwik，无法再回退裸 UDP，
     * 由调用方降级中继（见 {@link #fallbackToRelay}）。</p>
     */
    private TransportChannel openP2PChannel(SessionContext ctx, IceAgent.IceResult result) {
        if (!quicEnabled) {
            return new UdpTransportChannel(result.socket(), result.peer(), result.sessionKey());
        }
        try {
            QuicTransportEndpoint.TlsMaterial tls = ctx.role == Role.AGENT
                    ? QuicTransportEndpoint.selfSigned(identity.keyPair()) : null;
            QuicTransportEndpoint.Role role = ctx.role == Role.CONTROLLER
                    ? QuicTransportEndpoint.Role.CONTROLLER : QuicTransportEndpoint.Role.AGENT;
            net.luminis.quic.QuicConnection conn = QuicTransportEndpoint.establish(
                    role, result.socket(), result.peer(), tls);
            return new QuicTransportChannel(conn, result.sessionKey());
        } catch (Exception e) {
            log.warn("quic establish failed, degrade to relay: {}", e.getMessage());
            result.socket().close();
            return null;
        }
    }

    /** 打洞失败 / 候选为空时的降级：关闭 P2P 残余并切换中继数据面（先 UDP）。 */
    private void fallbackToRelay(SessionContext ctx) {
        if (ctx.iceAgent != null) {
            ctx.iceAgent.close();
            ctx.iceAgent = null;
        }
        TransportChannel relay = establishRelay(ctx, PathType.RELAY_UDP);
        if (relay == null) {
            return; // establishRelay 内部已 fail
        }
        transition(ctx, SessionStatus.RELAY_CONNECTED);
        attachQosMonitor(ctx, relay);
        sessionListener.onConnected(ctx.remoteDeviceCode, ctx.stableChannel, ctx.role == Role.CONTROLLER);
    }

    /**
     * 申请指定协议的中继令牌并建立对应 {@link TransportChannel}（UDP/TCP/WS）；
     * 失败时调用 {@link #fail} 并返回 {@code null}。每次申请重置 {@code relayAllocated}
     * future，避免复用上一协议已完成的响应。
     */
    private TransportChannel establishRelay(SessionContext ctx, PathType pathType) {
        ctx.relayCommitted = new java.util.concurrent.CompletableFuture<>();
        ctx.migrationInFlight.set(true);
        signaling.send(Signal.newBuilder()
                .setSessionId(ctx.sessionId)
                .setTimestamp(System.currentTimeMillis())
                .setRelayAllocReq(RelayAllocReq.newBuilder()
                        .setSessionId(ctx.sessionId)
                        .setPathType(pathType)
                        .setRegion(signalingConfig.getRegion() == null ? "" : signalingConfig.getRegion())
                        .build())
                .build());

        try {
            return ctx.relayCommitted.get(Thresholds.CONNECT_BUDGET_MS + 15_000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            ctx.migrationInFlight.set(false);
            fail(ctx, ErrorCode.RELAY_ALLOC_FAIL, "relay alloc timeout");
            return null;
        } catch (Exception e) {
            ctx.migrationInFlight.set(false);
            fail(ctx, ErrorCode.RELAY_ALLOC_FAIL, "relay fallback failed: " + e.getMessage());
            return null;
        }
    }

    /** 按协议创建中继通道并完成 JOIN。 */
    private TransportChannel createRelayChannel(RelayAssignmentV2 assignment, SessionContext ctx,
                                                String nonce) throws Exception {
        PathType pathType = assignment.getEndpoint().getPathType();
        Endpoint relay = new Endpoint(assignment.getEndpoint().getHost(), assignment.getEndpoint().getPort());
        PeerRole role = assignment.getRole() == RelayPeerRole.RELAY_ROLE_CONTROLLER
                ? PeerRole.CONTROLLER : PeerRole.AGENT;
        TransportChannel raw;
        switch (pathType) {
            case RELAY_TCP: {
                RelayTcpTransportChannel c = new RelayTcpTransportChannel(
                        relay, ctx.sessionId, assignment.getRouteEpoch(), role,
                        assignment.getRelayTicket(), nonce, ctx.sessionKey,
                        assignment.getEndpoint().getTls(), signalingConfig.isTrustAll());
                c.start();
                c.awaitJoined(Thresholds.CONNECT_BUDGET_MS);
                raw = c; break;
            }
            case RELAY_WS: {
                RelayWsTransportChannel c = new RelayWsTransportChannel(
                        relay, ctx.sessionId, assignment.getRouteEpoch(), role,
                        assignment.getRelayTicket(), nonce, ctx.sessionKey,
                        assignment.getEndpoint().getTls(), signalingConfig.isTrustAll());
                c.start();
                c.awaitJoined(Thresholds.CONNECT_BUDGET_MS);
                raw = c; break;
            }
            default: {
                RelayTransportChannel c = new RelayTransportChannel(relay, ctx.sessionId,
                        assignment.getRouteEpoch(), role, assignment.getRelayTicket(), nonce, ctx.sessionKey);
                c.start();
                c.awaitJoined(Thresholds.CONNECT_BUDGET_MS);
                raw = c; break;
            }
        }
        SecureTransportChannel.LocalRole localRole = ctx.role == Role.CONTROLLER
                ? SecureTransportChannel.LocalRole.CONTROLLER : SecureTransportChannel.LocalRole.AGENT;
        return new SecureTransportChannel(raw, ctx.sessionKey, ctx.sessionId,
                assignment.getRouteEpoch(), pathType, localRole);
    }

    /** 运行中质量劣化：只上报观测，具体节点与协议由信令服务器统一选择。 */
    private void degrade(SessionContext ctx) {
        if (ctx.status != SessionStatus.P2P_CONNECTED && ctx.status != SessionStatus.RELAY_CONNECTED) {
            return;
        }
        if (!ctx.migrationInFlight.compareAndSet(false, true)) return;
        QosMetrics.increment(QosMetricNames.DEGRADE_EVENTS_TOTAL,
                "from", ctx.dataPath.name(), "to", "SERVER_SELECTED");
        transition(ctx, SessionStatus.DEGRADED);
        reportRelayFailure(ctx, "QOS_DEGRADED", 1, 0, 0f);
    }

    /** 数据面建立后挂载 QoS 监测器，保活丢失 / 通道关闭时降级。 */
    private void attachQosMonitor(SessionContext ctx, TransportChannel channel) {
        if (ctx.qosMonitor != null) {
            ctx.qosMonitor.close();
        }
        ctx.qosMonitor = new QosMonitor(ctx.sessionId, channel, new QosListener() {
            @Override
            public void onKeepaliveLost() {
                worker.execute(() -> degrade(ctx));
            }

            @Override
            public void onTransportClosed() {
                worker.execute(() -> degrade(ctx));
            }
        });
    }

    private Signal candidateSignal(long sessionId, IceCandidate candidate) {
        CandidateMsg msg = CandidateMsg.newBuilder()
                .setSessionId(sessionId)
                .setType(candidate.type())
                .setIp(candidate.ip())
                .setPort(candidate.port())
                .setPriority((int) candidate.priority())
                .setUfrag(candidate.ufrag() == null ? "" : candidate.ufrag())
                .setPassword(candidate.password() == null ? "" : candidate.password())
                .setSdpMid(candidate.sdpMid() == null ? "0" : candidate.sdpMid())
                .setSdpMlineIndex(candidate.sdpMlineIndex())
                .build();
        return Signal.newBuilder()
                .setSessionId(sessionId)
                .setTimestamp(System.currentTimeMillis())
                .setCandidateMsg(msg)
                .build();
    }

    // ---------- 信令回调 ----------

    private SignalingListener signalingListener() {
        return new SignalingListener() {
            @Override
            public void onConnected() {
                sendRegister();
            }

            @Override
            public void onDisconnected(Throwable cause) {
                log.info("signaling disconnected");
            }

            @Override
            public void onSignal(Signal signal) {
                if (signal.hasRegisterResp()) {
                    deviceId = signal.getRegisterResp().getDeviceId();
                    connectionEpoch = signal.getRegisterResp().getConnectionEpoch();
                    log.info("device registered, deviceId={} code={}", deviceId, identity.deviceCode());
                    sessions.keySet().forEach(ClientConnectionManager.this::requestSnapshot);
                } else if (signal.hasInviteReq()) {
                    worker.execute(() -> doHandleInvite(signal));
                } else if (signal.hasInviteResp()) {
                    handleInviteResp(signal.getInviteResp());
                } else if (signal.hasCandidateMsg()) {
                    handleCandidate(signal.getCandidateMsg());
                } else if (signal.hasRelayAssignmentV2()) {
                    worker.execute(() -> handleRelayAssignment(signal.getRelayAssignmentV2()));
                } else if (signal.hasRouteCommitV2()) {
                    worker.execute(() -> handleRouteCommit(signal.getRouteCommitV2()));
                } else if (signal.hasRouteAbortV2()) {
                    worker.execute(() -> handleRouteAbort(signal.getRouteAbortV2()));
                } else if (signal.hasSessionSnapshotRespV2()) {
                    worker.execute(() -> handleSessionSnapshot(signal.getSessionSnapshotRespV2()));
                } else if (signal.hasSessionEnd()) {
                    handleSessionEnd(signal.getSessionEnd());
                } else if (signal.hasPathSwitch()) {
                    PathSwitchNotify notify = signal.getPathSwitch();
                    log.info("remote path switch: session={} from={} to={} reason={}",
                            notify.getSessionId(), notify.getFromPath(), notify.getToPath(), notify.getReason());
                }
            }
        };
    }

    private void sendRegister() {
        RegisterReq req = RegisterReq.newBuilder()
                .setDeviceCode(identity.deviceCode())
                .setDeviceName(identity.deviceName())
                .setOs(identity.os())
                .setVersion(identity.version())
                .setPublicKeyFingerprint(identity.fingerprint())
                .setNatType(NatType.NAT_UNKNOWN) // NAT 类型在连接期经 STUN 推断，注册期不额外探测
                .setToken(accessToken)
                .setPublicKey(identity.publicKeyBase64())
                .setClientInstanceId(clientInstanceId)
                .setMinProtocolVersion("2.0")
                .setMaxProtocolVersion("2.0")
                .addCapabilities("relay-migration-v2")
                .addCapabilities("e2ee-outer-frame-v1")
                .build();
        signaling.send(Signal.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setRegisterReq(req)
                .build());
    }

    private void handleInviteResp(InviteResp resp) {
        SessionContext ctx = sessions.get(resp.getSessionId());
        if (ctx == null) {
            return;
        }
        if (resp.getAccepted()) {
            ctx.inviteAccepted.complete(null);
        } else {
            ctx.inviteAccepted.completeExceptionally(
                    new InviteRejectedException(ErrorCode.of(resp.getErrorCode()), resp.getErrorMessage()));
        }
    }

    private void handleCandidate(CandidateMsg msg) {
        SessionContext ctx = sessions.get(msg.getSessionId());
        if (ctx == null) {
            return;
        }
        IceCandidate candidate = new IceCandidate(
                msg.getType(),
                msg.getIp(),
                msg.getPort(),
                ((long) msg.getPriority()) & 0xFFFFFFFFL,
                msg.getUfrag(),
                msg.getPassword(),
                msg.getSdpMid(),
                msg.getSdpMlineIndex());
        if (ctx.status == SessionStatus.RELAY_CONNECTED) {
            // 运行期路由由服务端统一协调；Relay 状态下的旧候选不能触发单端自治回切。
            log.debug("stale P2P candidate ignored while relay is active, session={}", ctx.sessionId);
            return;
        }
        ctx.remoteCandidates.add(candidate);
    }

    private void handleRelayAssignment(RelayAssignmentV2 assignment) {
        SessionContext ctx = sessions.get(assignment.getSessionId());
        if (ctx == null || ctx.status == SessionStatus.ENDED) return;
        if (assignment.getDeadlineAt() <= System.currentTimeMillis()
                || assignment.getBaseEpoch() != ctx.routeEpoch) {
            requestSnapshot(ctx.sessionId);
            return;
        }
        PreparedClientRoute duplicate = ctx.preparedChannels.get(assignment.getAssignmentId());
        if (duplicate != null) {
            sendRelayReady(assignment);
            return;
        }
        String nonce = java.util.UUID.randomUUID().toString();
        Throwable last = null;
        for (int attempt = 1; attempt <= 3 && System.currentTimeMillis() < assignment.getDeadlineAt(); attempt++) {
            TransportChannel channel = null;
            try {
                channel = createRelayChannel(assignment, ctx, nonce);
                ctx.preparedChannels.put(assignment.getAssignmentId(), new PreparedClientRoute(assignment, channel));
                QosMetrics.increment(QosMetricNames.RELAY_ALLOC_TOTAL,
                        "path", assignment.getEndpoint().getPathType().name());
                sendRelayReady(assignment);
                return;
            } catch (Throwable failure) {
                last = failure;
                if (channel != null) channel.close();
                if (attempt < 3) {
                    long cap = Math.min(2_000L, 200L << (attempt - 1));
                    long delay = java.util.concurrent.ThreadLocalRandom.current().nextLong(cap + 1);
                    try { Thread.sleep(delay); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
            }
        }
        ctx.migrationInFlight.set(false);
        reportRelayFailure(ctx, assignment, "ASSIGNMENT_CONNECT_FAILED", 3, 0, 0f);
        log.warn("assigned relay preparation failed: session={} assignment={} error={}",
                ctx.sessionId, assignment.getAssignmentId(), last == null ? "unknown" : last.getMessage());
    }

    private void sendRelayReady(RelayAssignmentV2 assignment) {
        String readyRequestId = assignment.getRequestId() + "-ready-" + assignment.getRole().name();
        signaling.send(Signal.newBuilder().setSessionId(assignment.getSessionId())
                .setTimestamp(System.currentTimeMillis()).setTraceId(readyRequestId)
                .setRelayReadyV2(RelayReadyV2.newBuilder().setSessionId(assignment.getSessionId())
                        .setRouteEpoch(assignment.getRouteEpoch()).setAssignmentId(assignment.getAssignmentId())
                        .setRequestId(readyRequestId)).build());
    }

    private void handleRouteCommit(RouteCommitV2 commit) {
        SessionContext ctx = sessions.get(commit.getSessionId());
        if (ctx == null || commit.getRouteEpoch() <= ctx.routeEpoch) return;
        PreparedClientRoute prepared = ctx.preparedChannels.remove(commit.getAssignmentId());
        if (prepared == null || prepared.assignment().getRouteEpoch() != commit.getRouteEpoch()) {
            requestSnapshot(ctx.sessionId);
            return;
        }
        TransportChannel next = prepared.channel();
        if (ctx.stableChannel == null) ctx.stableChannel = new SwitchableTransportChannel(commit.getRouteEpoch(), next);
        else if (!ctx.stableChannel.commit(commit.getRouteEpoch(), next)) return;
        ctx.channel = next;
        ctx.routeEpoch = commit.getRouteEpoch();
        ctx.assignmentId = commit.getAssignmentId();
        ctx.relayNodeId = commit.getEndpoint().getRelayNodeId();
        ctx.migrationInFlight.set(false);
        ctx.preparedChannels.values().removeIf(route -> {
            if (route.assignment().getRouteEpoch() <= commit.getRouteEpoch()) { route.channel().close(); return true; }
            return false;
        });
        trackPath(ctx, commit.getEndpoint().getPathType());
        transition(ctx, SessionStatus.RELAY_CONNECTED);
        attachQosMonitor(ctx, ctx.stableChannel);
        ctx.relayCommitted.complete(ctx.stableChannel);
        sessionListener.onPathChanged(ctx.dataPath, ctx.stableChannel.info());
    }

    private void handleRouteAbort(RouteAbortV2 abort) {
        SessionContext ctx = sessions.get(abort.getSessionId());
        if (ctx == null) return;
        PreparedClientRoute prepared = ctx.preparedChannels.remove(abort.getAssignmentId());
        if (prepared != null) prepared.channel().close();
        ctx.migrationInFlight.set(false);
        if (ctx.stableChannel == null && abort.getAssignmentId().isBlank()) {
            ctx.relayCommitted.completeExceptionally(new IllegalStateException(abort.getReason()));
        }
    }

    private void reportRelayFailure(SessionContext ctx, String type, int attempts, int rtt, float loss) {
        RelayFailureReport report = RelayFailureReport.newBuilder().setSessionId(ctx.sessionId)
                .setRouteEpoch(ctx.routeEpoch).setAssignmentId(ctx.assignmentId)
                .setRelayNodeId(ctx.relayNodeId).setPathType(ctx.dataPath)
                .setFailureType(type).setAttemptCount(attempts).setObservedRttMs(rtt)
                .setObservedLossRate(loss).setFirstFailureAt(System.currentTimeMillis())
                .setLastFailureAt(System.currentTimeMillis()).setRegion(
                        signalingConfig.getRegion() == null ? "" : signalingConfig.getRegion())
                .setRequestId(java.util.UUID.randomUUID().toString()).build();
        signaling.send(Signal.newBuilder().setSessionId(ctx.sessionId).setTimestamp(System.currentTimeMillis())
                .setTraceId(report.getRequestId()).setRelayFailureReport(report).build());
    }

    private void reportRelayFailure(SessionContext ctx, RelayAssignmentV2 assignment, String type,
                                    int attempts, int rtt, float loss) {
        RelayFailureReport report = RelayFailureReport.newBuilder().setSessionId(ctx.sessionId)
                .setRouteEpoch(assignment.getRouteEpoch()).setAssignmentId(assignment.getAssignmentId())
                .setRelayNodeId(assignment.getEndpoint().getRelayNodeId())
                .setPathType(assignment.getEndpoint().getPathType()).setFailureType(type)
                .setAttemptCount(attempts).setObservedRttMs(rtt).setObservedLossRate(loss)
                .setFirstFailureAt(System.currentTimeMillis()).setLastFailureAt(System.currentTimeMillis())
                .setRegion(signalingConfig.getRegion() == null ? "" : signalingConfig.getRegion())
                .setRequestId(assignment.getRequestId()).build();
        signaling.send(Signal.newBuilder().setSessionId(ctx.sessionId).setTimestamp(System.currentTimeMillis())
                .setTraceId(assignment.getRequestId()).setRelayFailureReport(report).build());
    }

    private void requestSnapshot(long sessionId) {
        signaling.send(Signal.newBuilder().setSessionId(sessionId).setTimestamp(System.currentTimeMillis())
                .setSessionSnapshotReqV2(com.rc.common.protocol.SessionSnapshotReqV2.newBuilder()
                        .setSessionId(sessionId)).build());
    }

    private void handleSessionSnapshot(com.rc.common.protocol.SessionSnapshotRespV2 snapshot) {
        SessionContext ctx = sessions.get(snapshot.getSessionId());
        if (ctx == null) return;
        if ("ENDED".equals(snapshot.getSessionState())) {
            endSession(ctx, snapshot.getEndReason());
            return;
        }
        if (snapshot.getCommittedRouteEpoch() > ctx.routeEpoch
                && !snapshot.getCommittedAssignmentId().isBlank()) {
            PreparedClientRoute prepared = ctx.preparedChannels.get(snapshot.getCommittedAssignmentId());
            if (prepared != null) {
                handleRouteCommit(RouteCommitV2.newBuilder().setSessionId(ctx.sessionId)
                        .setRouteEpoch(snapshot.getCommittedRouteEpoch())
                        .setAssignmentId(snapshot.getCommittedAssignmentId())
                        .setEndpoint(snapshot.getCommittedEndpoint()).build());
            }
        }
    }

    private void handleSessionEnd(SessionEnd end) {
        SessionContext ctx = sessions.get(end.getSessionId());
        if (ctx != null) {
            endSession(ctx, end.getReason());
        }
    }

    /** 主动挂断当前会话：发送 SessionEnd 并回收本地资源。 */
    public void hangup() {
        SessionContext ctx = activeSession;
        if (ctx == null || ctx.status == SessionStatus.ENDED) {
            return;
        }
        sendSessionEnd(ctx);
        endSession(ctx, "local hangup");
    }

    private void sendSessionEnd(SessionContext ctx) {
        SessionEnd end = SessionEnd.newBuilder()
                .setSessionId(ctx.sessionId)
                .setReason("hangup")
                .build();
        signaling.send(Signal.newBuilder()
                .setSessionId(ctx.sessionId)
                .setTimestamp(System.currentTimeMillis())
                .setSessionEnd(end)
                .build());
    }

    private void endSession(SessionContext ctx, String reason) {
        if (sessions.remove(ctx.sessionId) == null) {
            return;
        }
        transition(ctx, SessionStatus.ENDED);
        ctx.close();
        if (activeSession == ctx) {
            activeSession = null;
        }
        sessionListener.onRemoteEnded(reason);
    }

    private void startHeartbeat() {
        heartbeat.scheduleAtFixedRate(() -> {
            long id = deviceId;
            if (id == 0 || signaling == null || !signaling.isConnected()) {
                return;
            }
            signaling.send(Signal.newBuilder()
                    .setTimestamp(System.currentTimeMillis())
                    .setHeartbeat(Heartbeat.newBuilder()
                            .setDeviceId(id)
                            .setClientTime(System.currentTimeMillis())
                            .build())
                    .build());
        }, Thresholds.HEARTBEAT_INTERVAL_MS, Thresholds.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void transition(SessionContext ctx, SessionStatus to) {
        SessionStatus from = ctx.status;
        ctx.status = to;
        log.info("session state: {} -> {} (session={})", from, to, ctx.sessionId);
    }

    /** 维护当前数据面路径分布 gauge：旧路径计数 -1，新路径计数 +1。 */
    private void trackPath(SessionContext ctx, PathType now) {
        PathType old = ctx.dataPath;
        if (old == now) {
            return;
        }
        decrementPath(old);
        incrementPath(now);
        ctx.dataPath = now;
    }

    private void activateInitialChannel(SessionContext ctx, TransportChannel channel, PathType path) {
        ctx.routeEpoch = 0;
        SecureTransportChannel.LocalRole localRole = ctx.role == Role.CONTROLLER
                ? SecureTransportChannel.LocalRole.CONTROLLER : SecureTransportChannel.LocalRole.AGENT;
        TransportChannel secure = new SecureTransportChannel(channel, ctx.sessionKey,
                ctx.sessionId, ctx.routeEpoch, path, localRole);
        ctx.channel = secure;
        ctx.stableChannel = new SwitchableTransportChannel(ctx.routeEpoch, secure);
    }

    private void incrementPath(PathType p) {
        if (p == null || p == PathType.PATH_UNKNOWN) {
            return;
        }
        pathCounts.computeIfAbsent(p, k -> new AtomicInteger()).incrementAndGet();
    }

    private void decrementPath(PathType p) {
        if (p == null || p == PathType.PATH_UNKNOWN) {
            return;
        }
        AtomicInteger c = pathCounts.get(p);
        if (c != null) {
            c.decrementAndGet();
        }
    }

    private void fail(SessionContext ctx, ErrorCode code, String message) {
        if (ctx != null) {
            transition(ctx, SessionStatus.ENDED);
            sessions.remove(ctx.sessionId);
            if (activeSession == ctx) {
                activeSession = null;
            }
            ctx.close();
        }
        log.warn("session failed: {} {}", code.rcCode(), message);
        sessionListener.onFailed(code, message);
    }

    private static ScheduledExecutorService newScheduledExecutor(String name, int threads) {
        return Executors.newScheduledThreadPool(threads, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        });
    }
}
