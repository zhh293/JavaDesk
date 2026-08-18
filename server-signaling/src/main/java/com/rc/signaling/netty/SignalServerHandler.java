package com.rc.signaling.netty;

import com.rc.common.constant.ErrorCode;
import com.rc.common.protocol.RegisterReq;
import com.rc.common.protocol.RegisterResp;
import com.rc.common.protocol.Signal;
import com.rc.signaling.config.SignalingProperties;
import com.rc.signaling.service.DeviceRegistrationException;
import com.rc.signaling.service.DeviceService;
import com.rc.signaling.service.SessionService;
import com.rc.signaling.session.ConnectionRegistry;
import io.jsonwebtoken.JwtException;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    public SignalServerHandler(DeviceService deviceService, SignalingProperties props,
                               ConnectionRegistry connections, SessionService sessionService) {
        this.deviceService = deviceService;
        this.props = props;
        this.connections = connections;
        this.sessionService = sessionService;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.channel().attr(ATTACHMENT).set(new Attachment());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Signal signal) {
        if (signal.hasRegisterReq()) {
            handleRegister(ctx, signal.getRegisterReq());
        } else if (signal.hasHeartbeat()) {
            handleHeartbeat(ctx, signal.getHeartbeat());
        } else if (signal.hasInviteReq()) {
            Attachment att = ctx.channel().attr(ATTACHMENT).get();
            sessionService.handleInvite(ctx.channel(), att.deviceId, signal);
        } else if (signal.hasInviteResp()) {
            sessionService.handleInviteResp(ctx.channel(), signal);
        } else if (signal.hasCandidateMsg()) {
            sessionService.handleCandidate(ctx.channel(), signal);
        } else if (signal.hasSessionEnd()) {
            sessionService.handleSessionEnd(ctx.channel(), signal);
        } else if (signal.hasRelayAllocReq()) {
            sessionService.handleRelayAlloc(ctx.channel(), signal);
        } else if (signal.hasPathSwitch()) {
            sessionService.handlePathSwitch(ctx.channel(), signal);
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
            deviceService.markOffline(att.deviceId, System.currentTimeMillis());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("signaling channel exception from {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }

    private void handleRegister(ChannelHandlerContext ctx, RegisterReq req) {
        long userId;
        try {
            userId = deviceService.authenticateUserId(req.getToken());
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
        connections.register(reg.deviceId(), ctx.channel());
        log.info("device registered: deviceId={} code={} userId={} created={}",
                reg.deviceId(), req.getDeviceCode(), userId, reg.created());
        sendRegisterResp(ctx, true, 0, "", reg.deviceId());
    }

    private void handleHeartbeat(ChannelHandlerContext ctx, com.rc.common.protocol.Heartbeat hb) {
        Attachment att = ctx.channel().attr(ATTACHMENT).get();
        if (att == null || att.deviceId == null) {
            log.debug("heartbeat before register, ignored");
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
        RegisterResp resp = RegisterResp.newBuilder()
                .setOk(ok)
                .setErrorCode(errorCode)
                .setErrorMessage(message == null ? "" : message)
                .setDeviceId(deviceId)
                .setSignalingNodeId(props.getNodeId())
                .setServerTime(System.currentTimeMillis())
                .build();
        ctx.writeAndFlush(Signal.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setRegisterResp(resp)
                .build());
    }

    private static final class Attachment {
        private Long userId;
        private Long deviceId;
        private long lastDbFlush;
    }
}
