package com.rc.signaling.netty;

import com.rc.common.constant.ErrorCode;
import com.rc.common.protocol.RegisterReq;
import com.rc.common.protocol.RegisterResp;
import com.rc.common.protocol.Signal;
import com.rc.signaling.config.SignalingProperties;
import com.rc.signaling.connection.ConnectionContext;
import com.rc.signaling.connection.ConnectionLease;
import com.rc.signaling.connection.ConnectionLeaseStore;
import com.rc.signaling.service.DeviceRegistrationException;
import com.rc.signaling.service.DeviceService;
import com.rc.signaling.service.SessionService;
import com.rc.signaling.session.ConnectionRegistry;
import io.jsonwebtoken.JwtException;
import io.netty.channel.ChannelHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 信令长连接处理器：设备上报（RegisterReq）与心跳（Heartbeat）。
 * 无状态（@Sharable），每连接状态存放于 Channel Attribute。
 */
@Component
@ChannelHandler.Sharable
public class SignalServerHandler extends SimpleChannelInboundHandler<Signal> {

    private static final Logger log = LoggerFactory.getLogger(SignalServerHandler.class);
    private static final AttributeKey<Attachment> ATTACHMENT = AttributeKey.valueOf("rc.attachment");

    private final DeviceService deviceService;
    private final SignalingProperties props;
    private final ConnectionRegistry connections;
    private final SessionService sessionService;
    private final ConnectionLeaseStore leaseStore;

    public SignalServerHandler(DeviceService deviceService, SignalingProperties props,
                               ConnectionRegistry connections, SessionService sessionService,
                               ConnectionLeaseStore leaseStore) {
        this.deviceService = deviceService;
        this.props = props;
        this.connections = connections;
        this.sessionService = sessionService;
        this.leaseStore = leaseStore;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.channel().attr(ATTACHMENT).set(new Attachment());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Signal signal) {
        if (signal.hasRegisterReq()) {
            handleRegister(ctx, signal.getRegisterReq());
            return;
        }
        Attachment att = registeredAttachment(ctx);
        if (att == null) {
            log.warn("signal before registration from {}, body={}", ctx.channel().remoteAddress(), signal.getBodyCase());
            ctx.close();
            return;
        }
        if (signal.hasHeartbeat()) {
            handleHeartbeat(ctx, signal.getHeartbeat());
        } else if (signal.hasInviteReq()) {
            sessionService.handleInvite(att.deviceId, att.connectionEpoch, signal);
        } else if (signal.hasInviteResp()) {
            sessionService.handleInviteResp(att.deviceId, att.connectionEpoch, signal);
        } else if (signal.hasCandidateMsg()) {
            sessionService.handleCandidate(att.deviceId, att.connectionEpoch, signal);
        } else if (signal.hasSessionEnd()) {
            sessionService.handleSessionEnd(att.deviceId, att.connectionEpoch, signal);
        } else if (signal.hasRelayAllocReq()) {
            sessionService.handleRelayAlloc(att.deviceId, att.connectionEpoch, signal);
        } else if (signal.hasRelayFailureReport()) {
            sessionService.handleRelayFailure(att.deviceId, att.connectionEpoch, signal);
        } else if (signal.hasRelayReadyV2()) {
            sessionService.handleRelayReady(att.deviceId, att.connectionEpoch, signal);
        } else if (signal.hasSessionSnapshotReqV2()) {
            sessionService.handleSnapshot(att.deviceId, att.connectionEpoch, signal);
        } else if (signal.hasPathSwitch()) {
            sessionService.handlePathSwitch(att.deviceId, att.connectionEpoch, signal);
        } else {
            log.debug("ignoring unhandled signal body: {}", signal.getBodyCase());
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent event && event.state() == IdleState.READER_IDLE) {
            log.warn("heartbeat timeout, closing channel: {}", ctx.channel().remoteAddress());
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Attachment att = ctx.channel().attr(ATTACHMENT).get();
        if (att != null && att.deviceId != null) {
            connections.unregister(att.deviceId, ctx.channel());
            boolean removed = leaseStore == null || leaseStore.delete(
                    att.deviceId, att.connectionId, att.connectionEpoch);
            if (removed) {
                deviceService.markOffline(att.deviceId, System.currentTimeMillis());
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("signaling channel exception from {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }

    private void handleRegister(ChannelHandlerContext ctx, RegisterReq req) {
        long userId;
        String protocolVersion;
        try {
            userId = deviceService.authenticateUserId(req.getToken());
            protocolVersion = negotiateProtocol(req);
        } catch (JwtException | IllegalArgumentException e) {
            sendRegisterResp(ctx, false, ErrorCode.AUTH_INVALID.code(), ErrorCode.AUTH_INVALID.description(), 0);
            ctx.close();
            return;
        }

        DeviceService.Registration reg;
        try {
            reg = deviceService.registerDevice(userId, req, props.getNodeId());
        } catch (DeviceRegistrationException e) {
            sendRegisterResp(ctx, false, e.getErrorCode().code(), e.getMessage(), 0);
            ctx.close();
            return;
        }

        Attachment att = ctx.channel().attr(ATTACHMENT).get();
        att.userId = userId;
        att.deviceId = reg.deviceId();
        String connectionId = ctx.channel().id().asLongText();
        String clientInstanceId = req.getClientInstanceId().isBlank() ? connectionId : req.getClientInstanceId();
        ConnectionLease lease = leaseStore == null
                ? new ConnectionLease(reg.deviceId(), userId, props.getNodeId(), connectionId, 1,
                        clientInstanceId, System.currentTimeMillis(),
                        System.currentTimeMillis() + props.getDeviceTtlSeconds() * 1000,
                        req.getVersion(), protocolVersion)
                : leaseStore.register(reg.deviceId(), userId, props.getNodeId(), connectionId,
                        clientInstanceId, req.getVersion(), protocolVersion,
                        Duration.ofSeconds(props.getDeviceTtlSeconds()));
        att.connectionId = lease.connectionId();
        att.connectionEpoch = lease.connectionEpoch();
        Channel old = connections.register(
                new ConnectionContext(userId, reg.deviceId(), lease.connectionId(), lease.connectionEpoch()),
                ctx.channel());
        if (old != null && old != ctx.channel()) {
            old.close();
        }
        log.info("device registered: deviceId={} code={} userId={} created={}",
                reg.deviceId(), req.getDeviceCode(), userId, reg.created());
        sendRegisterResp(ctx, true, 0, "", reg.deviceId(), lease);
    }

    private void handleHeartbeat(ChannelHandlerContext ctx, com.rc.common.protocol.Heartbeat hb) {
        Attachment att = ctx.channel().attr(ATTACHMENT).get();
        if (att == null || att.deviceId == null) {
            log.debug("heartbeat before register, ignored");
            return;
        }
        if (leaseStore != null && !leaseStore.renew(att.deviceId, att.connectionId, att.connectionEpoch,
                Duration.ofSeconds(props.getDeviceTtlSeconds()))) {
            log.warn("fenced connection heartbeat rejected: deviceId={} epoch={}",
                    att.deviceId, att.connectionEpoch);
            ctx.close();
            return;
        }
        deviceService.renewHeartbeat(att.deviceId, props.getNodeId());
        long now = System.currentTimeMillis();
        if (now - att.lastDbFlush >= props.getHeartbeatDbFlushSeconds() * 1000L) {
            deviceService.touchOnline(att.deviceId, now);
            att.lastDbFlush = now;
        }
    }

    private void sendRegisterResp(ChannelHandlerContext ctx, boolean ok, int errorCode, String message, long deviceId) {
        sendRegisterResp(ctx, ok, errorCode, message, deviceId, null);
    }

    private void sendRegisterResp(ChannelHandlerContext ctx, boolean ok, int errorCode, String message,
                                  long deviceId, ConnectionLease lease) {
        RegisterResp.Builder builder = RegisterResp.newBuilder()
                .setOk(ok)
                .setErrorCode(errorCode)
                .setErrorMessage(message == null ? "" : message)
                .setDeviceId(deviceId)
                .setSignalingNodeId(props.getNodeId())
                .setServerTime(System.currentTimeMillis());
        if (lease != null) {
            builder.setConnectionId(lease.connectionId())
                    .setConnectionEpoch(lease.connectionEpoch())
                    .setNegotiatedProtocolVersion(lease.protocolVersion());
        }
        RegisterResp resp = builder.build();
        ctx.writeAndFlush(Signal.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setRegisterResp(resp)
                .build());
    }

    private static Attachment registeredAttachment(ChannelHandlerContext ctx) {
        Attachment att = ctx.channel().attr(ATTACHMENT).get();
        return att != null && att.deviceId != null ? att : null;
    }

    private static String negotiateProtocol(RegisterReq req) {
        String min = req.getMinProtocolVersion();
        String max = req.getMaxProtocolVersion();
        if (min.isBlank() && max.isBlank()) return "1.0";
        if (compareVersion(min.isBlank() ? "1.0" : min, "2.0") > 0
                || compareVersion(max.isBlank() ? "2.0" : max, "1.0") < 0) {
            throw new IllegalArgumentException("no compatible signaling protocol version");
        }
        return compareVersion(max.isBlank() ? "2.0" : max, "2.0") >= 0 ? "2.0" : max;
    }

    private static int compareVersion(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int av = i < a.length ? Integer.parseInt(a[i]) : 0;
            int bv = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static final class Attachment {
        private Long userId;
        private Long deviceId;
        private long lastDbFlush;
        private String connectionId;
        private long connectionEpoch;
    }
}
