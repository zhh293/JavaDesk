package com.rc.signaling.service;

import com.rc.common.constant.ErrorCode;
import com.rc.common.crypto.RelayTicketV2;
import com.rc.common.crypto.RelayTicketV2.PeerRole;
import com.rc.common.metrics.QosMetricNames;
import com.rc.common.metrics.QosMetrics;
import com.rc.common.model.Device;
import com.rc.common.model.RelayNode;
import com.rc.common.protocol.*;
import com.rc.common.util.IdGenerator;
import com.rc.signaling.config.NacosProperties;
import com.rc.signaling.config.SignalingProperties;
import com.rc.signaling.connection.ConnectionLease;
import com.rc.signaling.connection.ConnectionLeaseStore;
import com.rc.signaling.dao.DeviceMapper;
import com.rc.signaling.messaging.ClusterSignalDispatcher;
import com.rc.signaling.relay.RelayObservation;
import com.rc.signaling.route.*;
import com.rc.signaling.security.RelayTicketKeyService;
import com.rc.signaling.session.SessionMetadata;
import com.rc.signaling.session.SessionState;
import com.rc.signaling.session.SessionStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Cluster-safe signaling orchestration with server-authoritative route migration. */
@Service
public final class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);
    private static final Duration PREPARE_TIMEOUT = Duration.ofSeconds(12);
    private static final long TICKET_TTL_SECONDS = 30;

    private final SessionStore sessions;
    private final SessionRouteCoordinator coordinator;
    private final SessionRouteStore routes;
    private final ClusterSignalDispatcher dispatcher;
    private final ConnectionLeaseStore leases;
    private final DeviceMapper devices;
    private final RelayManager relays;
    private final RelayTicketKeyService ticketKeys;
    private final SignalingProperties signaling;
    private final NacosProperties nacos;
    private final AuditService audit;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rc-route-timeouts"); t.setDaemon(true); return t;
    });

    public SessionService(SessionStore sessions, SessionRouteCoordinator coordinator, SessionRouteStore routes,
                          ClusterSignalDispatcher dispatcher, ConnectionLeaseStore leases,
                          DeviceMapper devices, RelayManager relays, RelayTicketKeyService ticketKeys,
                          SignalingProperties signaling, NacosProperties nacos, AuditService audit) {
        this.sessions = sessions; this.coordinator = coordinator; this.routes = routes;
        this.dispatcher = dispatcher; this.leases = leases; this.devices = devices; this.relays = relays;
        this.ticketKeys = ticketKeys; this.signaling = signaling; this.nacos = nacos; this.audit = audit;
    }

    public void handleInvite(long controllerDeviceId, long connectionEpoch, Signal signal) {
        InviteReq req = signal.getInviteReq();
        Device target = devices.findByDeviceCode(req.getTargetDeviceCode());
        if (target == null) {
            sendInviteResult(controllerDeviceId, null, req.getSessionId(), false,
                    ErrorCode.TARGET_OFFLINE.code(), "target device not found");
            return;
        }
        ConnectionLease targetLease = leases.find(target.getId()).orElse(null);
        if (targetLease == null || targetLease.leaseExpireAt() <= System.currentTimeMillis()) {
            sendInviteResult(controllerDeviceId, null, req.getSessionId(), false,
                    ErrorCode.TARGET_OFFLINE.code(), "target device offline");
            return;
        }
        long now = System.currentTimeMillis();
        SessionMetadata metadata = new SessionMetadata(req.getSessionId(), 0, SessionState.INVITING,
                controllerDeviceId, target.getId(), connectionEpoch, targetLease.connectionEpoch(),
                signaling.getNodeId(), 0, now, now, Math.addExact(now, SESSION_TTL.toMillis()), null, 0);
        try {
            metadata = sessions.create(metadata, requestId(signal));
        } catch (RuntimeException conflict) {
            sendInviteResult(controllerDeviceId, null, req.getSessionId(), false,
                    ErrorCode.AUTH_INVALID.code(), conflict.getMessage());
            return;
        }
        audit.record(null, controllerDeviceId, AuditService.ACTION_INVITE,
                "session=" + req.getSessionId() + " target=" + req.getTargetDeviceCode());
        if (!dispatcher.send(target.getId(), metadata, null, "SESSION_INVITE", signal)) {
            sessions.end(metadata.sessionId(), controllerDeviceId, "target delivery failed", ErrorCode.TARGET_OFFLINE.code());
            sendInviteResult(controllerDeviceId, metadata, metadata.sessionId(), false,
                    ErrorCode.TARGET_OFFLINE.code(), "target delivery failed");
        }
    }

    public void handleInviteResp(long actorDeviceId, long connectionEpoch, Signal signal) {
        SessionMetadata session = requireSession(signal.getInviteResp().getSessionId());
        requireRoleAndEpoch(session, actorDeviceId, connectionEpoch, false);
        InviteResp decision = signal.getInviteResp();
        if (decision.getAccepted()) {
            session = sessions.transition(session.sessionId(), session.version(), SessionState.INVITING,
                    SessionState.ACCEPTED, actorDeviceId);
            session = sessions.transition(session.sessionId(), session.version(), SessionState.ACCEPTED,
                    SessionState.NEGOTIATING, actorDeviceId);
            session = sessions.transition(session.sessionId(), session.version(), SessionState.NEGOTIATING,
                    SessionState.ACTIVE, actorDeviceId);
        } else {
            session = sessions.end(session.sessionId(), actorDeviceId,
                    decision.getErrorMessage(), decision.getErrorCode());
        }
        dispatcher.send(session.controllerDeviceId(), session, null, "INVITE_DECISION", signal);
    }

    public void handleCandidate(long actorDeviceId, long connectionEpoch, Signal signal) {
        SessionMetadata session = requireSession(signal.getCandidateMsg().getSessionId());
        requireMemberAndEpoch(session, actorDeviceId, connectionEpoch);
        dispatcher.send(peer(session, actorDeviceId), session, session.routeEpoch(), "ICE_CANDIDATE", signal);
    }

    public void handleSessionEnd(long actorDeviceId, long connectionEpoch, Signal signal) {
        SessionEnd end = signal.getSessionEnd();
        SessionMetadata session = requireSession(end.getSessionId());
        requireMemberAndEpoch(session, actorDeviceId, connectionEpoch);
        session = sessions.end(session.sessionId(), actorDeviceId, end.getReason(), end.getErrorCode());
        dispatcher.send(peer(session, actorDeviceId), session, session.routeEpoch(), "SESSION_END", signal);
        audit.record(null, session.controllerDeviceId(), AuditService.ACTION_SESSION_END,
                "session=" + session.sessionId() + " reason=" + end.getReason() + " code=" + end.getErrorCode());
    }

    /** Legacy RelayAlloc is normalized into the authoritative V2 migration flow. */
    public void handleRelayAlloc(long actorDeviceId, long connectionEpoch, Signal signal) {
        RelayAllocReq request = signal.getRelayAllocReq();
        SessionMetadata session = requireSession(request.getSessionId());
        requireMemberAndEpoch(session, actorDeviceId, connectionEpoch);
        PathType path = request.getPathType() == PathType.PATH_UNKNOWN ? PathType.RELAY_UDP : request.getPathType();
        prepareAndDispatch(session, actorDeviceId, requestId(signal), path, request.getRegion(), "unknown", Set.of());
    }

    public void handleRelayFailure(long actorDeviceId, long connectionEpoch, Signal signal) {
        RelayFailureReport report = signal.getRelayFailureReport();
        SessionMetadata session = requireSession(report.getSessionId());
        requireMemberAndEpoch(session, actorDeviceId, connectionEpoch);
        SessionRouteSnapshot routeSnapshot = routes.snapshot(session.sessionId());
        SessionRoute failedPreparation = routeSnapshot.preparing();
        boolean preparingFailure = failedPreparation != null
                && failedPreparation.routeEpoch() == report.getRouteEpoch()
                && failedPreparation.assignmentId().equals(report.getAssignmentId());
        if (!preparingFailure && report.getRouteEpoch() != session.routeEpoch()) {
            sendSnapshot(actorDeviceId, session); return;
        }
        if (!report.getRelayNodeId().isBlank()) {
            relays.recordObservation(new RelayObservation(report.getRelayNodeId(), report.getRegion(),
                    report.getNetworkProvider(), report.getPathType(), false, report.getObservedRttMs(),
                    report.getObservedLossRate(), report.getFailureType(), System.currentTimeMillis()));
        }
        Set<String> excluded = new HashSet<>();
        if (!report.getRelayNodeId().isBlank()) excluded.add(report.getRelayNodeId());
        SessionRoute committed = routeSnapshot.committed();
        if (committed != null) excluded.addAll(committed.excludedRelayNodeIds());
        if (preparingFailure) {
            SessionRoute aborted = routes.abort(session.sessionId(), failedPreparation.routeEpoch(),
                    failedPreparation.assignmentId(), report.getFailureType());
            sendAbort(session, aborted);
        }
        String requestId = preparingFailure ? UUID.randomUUID().toString()
                : (report.getRequestId().isBlank() ? requestId(signal) : report.getRequestId());
        PathType desired = report.getPathType() == PathType.P2P || report.getPathType() == PathType.PATH_UNKNOWN
                ? PathType.RELAY_UDP : report.getPathType();
        prepareAndDispatch(session, actorDeviceId, requestId, desired, report.getRegion(),
                report.getNetworkProvider(), excluded);
    }

    public void handleRelayReady(long actorDeviceId, long connectionEpoch, Signal signal) {
        RelayReadyV2 ready = signal.getRelayReadyV2();
        SessionMetadata session = requireSession(ready.getSessionId());
        requireMemberAndEpoch(session, actorDeviceId, connectionEpoch);
        SessionRoute route = coordinator.ready(session.sessionId(), actorDeviceId, connectionEpoch,
                ready.getRouteEpoch(), ready.getAssignmentId(), ready.getRequestId());
        if (route.state() != RouteState.COMMITTED) return;
        if (session.routeEpoch() >= route.routeEpoch()) return;
        SessionMetadata updated = requireSession(session.sessionId());
        Signal commit = Signal.newBuilder().setSessionId(updated.sessionId()).setTimestamp(System.currentTimeMillis())
                .setTraceId(IdGenerator.newTraceId()).setRouteCommitV2(RouteCommitV2.newBuilder()
                        .setSessionId(updated.sessionId()).setRouteEpoch(route.routeEpoch())
                        .setAssignmentId(route.assignmentId()).setEndpoint(endpoint(route))).build();
        sendBoth(updated, route.routeEpoch(), "ROUTE_COMMIT", commit);
        timers.schedule(() -> sendRetire(updated, route.baseEpoch()), 2, TimeUnit.SECONDS);
        relays.recordObservation(new RelayObservation(route.relayNodeId(), "unknown", "unknown",
                route.pathType(), true, 0, 0, "committed", System.currentTimeMillis()));
        audit.record(null, updated.controllerDeviceId(), AuditService.ACTION_PATH_SWITCH,
                "session=" + updated.sessionId() + " committedEpoch=" + route.routeEpoch()
                        + " relay=" + route.relayNodeId() + " path=" + route.pathType());
    }

    public void handleSnapshot(long actorDeviceId, long connectionEpoch, Signal signal) {
        long sessionId = signal.getSessionSnapshotReqV2().getSessionId();
        SessionMetadata session = requireSession(sessionId);
        if (!session.isMember(actorDeviceId)) throw new SecurityException("actor is not a session member");
        session = sessions.refreshConnectionEpoch(sessionId, actorDeviceId, connectionEpoch);
        sendSnapshot(actorDeviceId, session);
        SessionRoute preparing = routes.snapshot(sessionId).preparing();
        if (preparing != null && preparing.prepareDeadlineAt() > System.currentTimeMillis()) {
            sendAssignment(session, preparing, session.isController(actorDeviceId));
        }
    }

    /** Legacy path notification is telemetry only and cannot mutate the authoritative route. */
    public void handlePathSwitch(long actorDeviceId, long connectionEpoch, Signal signal) {
        PathSwitchNotify notify = signal.getPathSwitch();
        SessionMetadata session = requireSession(notify.getSessionId());
        requireMemberAndEpoch(session, actorDeviceId, connectionEpoch);
        audit.record(null, session.controllerDeviceId(), AuditService.ACTION_PATH_SWITCH,
                "telemetry session=" + session.sessionId() + " from=" + notify.getFromPath()
                        + " to=" + notify.getToPath() + " reason=" + notify.getReason());
    }

    private void prepareAndDispatch(SessionMetadata session, long actorDeviceId, String requestId,
                                    PathType desired, String region, String provider, Set<String> excluded) {
        PathType path = desired;
        RelayNode node = null;
        while (path != null && node == null) {
            node = relays.selectBest(region, provider, path, excluded);
            if (node == null) path = nextRelayPath(path);
        }
        if (node == null && !nacos.isEnabled()) {
            path = path == null ? desired : path;
            node = staticDevRelay();
        }
        if (node == null) {
            sendAbortWithoutPreparation(session, "no healthy Relay candidate");
            return;
        }
        SessionRoute route;
        try {
            route = coordinator.prepare(session.sessionId(), actorDeviceId, session.routeEpoch(), requestId,
                    path, node.getNodeId(), node.getHost(), node.portFor(path), node.isTls(), excluded, PREPARE_TIMEOUT);
        } catch (RouteConflictException conflict) {
            route = conflict.current();
            if (route == null || route.state() != RouteState.PREPARING) {
                sendSnapshot(actorDeviceId, requireSession(session.sessionId())); return;
            }
        }
        dispatchAssignments(session, route);
        scheduleExpiry(session.sessionId(), route.routeEpoch(), route.assignmentId(), route.prepareDeadlineAt());
    }

    private void dispatchAssignments(SessionMetadata session, SessionRoute route) {
        sendAssignment(session, route, true);
        sendAssignment(session, route, false);
    }

    private void sendAssignment(SessionMetadata session, SessionRoute route, boolean controllerRole) {
        long deviceId = controllerRole ? session.controllerDeviceId() : session.agentDeviceId();
        long connectionEpoch = controllerRole ? session.controllerConnectionEpoch() : session.agentConnectionEpoch();
        PeerRole role = controllerRole ? PeerRole.CONTROLLER : PeerRole.AGENT;
        long now = System.currentTimeMillis() / 1_000;
        RelayTicketV2 ticket = new RelayTicketV2(ticketKeys.issuer(), ticketKeys.keyId(), UUID.randomUUID().toString(),
                session.sessionId(), route.routeEpoch(), route.assignmentId(), route.relayNodeId(), route.pathType(),
                role, deviceId, connectionEpoch, now, now, now + TICKET_TTL_SECONDS);
        RelayAssignmentV2 assignment = RelayAssignmentV2.newBuilder().setSessionId(session.sessionId())
                .setRouteEpoch(route.routeEpoch()).setBaseEpoch(route.baseEpoch())
                .setAssignmentId(route.assignmentId()).setEndpoint(endpoint(route))
                .setRelayTicket(ticketKeys.sign(ticket))
                .setRole(controllerRole ? RelayPeerRole.RELAY_ROLE_CONTROLLER : RelayPeerRole.RELAY_ROLE_AGENT)
                .setDeadlineAt(route.prepareDeadlineAt()).setRequestId(route.switchRequestId()).build();
        Signal signal = Signal.newBuilder().setSessionId(session.sessionId()).setTimestamp(System.currentTimeMillis())
                .setTraceId(IdGenerator.newTraceId()).setRelayAssignmentV2(assignment).build();
        dispatcher.send(deviceId, session, route.routeEpoch(), "RELAY_ASSIGNMENT", signal);
    }

    private void scheduleExpiry(long sessionId, long epoch, String assignmentId, long deadline) {
        long delay = Math.max(1, deadline - System.currentTimeMillis());
        timers.schedule(() -> {
            try {
                SessionRoute aborted = routes.expirePreparation(sessionId, System.currentTimeMillis()).orElse(null);
                if (aborted == null || aborted.routeEpoch() != epoch || !aborted.assignmentId().equals(assignmentId)) return;
                SessionMetadata session = sessions.find(sessionId).orElse(null);
                if (session != null) sendAbort(session, aborted);
            } catch (RuntimeException e) {
                log.warn("route expiry failed: session={} epoch={}", sessionId, epoch, e);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void sendAbort(SessionMetadata session, SessionRoute route) {
        Signal signal = Signal.newBuilder().setSessionId(session.sessionId()).setTimestamp(System.currentTimeMillis())
                .setTraceId(IdGenerator.newTraceId()).setRouteAbortV2(RouteAbortV2.newBuilder()
                        .setSessionId(session.sessionId()).setRouteEpoch(route.routeEpoch())
                        .setAssignmentId(route.assignmentId()).setReason(route.failureReason())).build();
        sendBoth(session, route.routeEpoch(), "ROUTE_ABORT", signal);
    }

    private void sendAbortWithoutPreparation(SessionMetadata session, String reason) {
        Signal signal = Signal.newBuilder().setSessionId(session.sessionId()).setTimestamp(System.currentTimeMillis())
                .setRouteAbortV2(RouteAbortV2.newBuilder().setSessionId(session.sessionId())
                        .setRouteEpoch(session.routeEpoch() + 1).setReason(reason)).build();
        sendBoth(session, session.routeEpoch() + 1, "ROUTE_ABORT", signal);
    }

    private void sendRetire(SessionMetadata session, long epoch) {
        Signal signal = Signal.newBuilder().setSessionId(session.sessionId()).setTimestamp(System.currentTimeMillis())
                .setRouteRetireV2(RouteRetireV2.newBuilder().setSessionId(session.sessionId())
                        .setRouteEpoch(epoch)).build();
        sendBoth(session, epoch, "ROUTE_RETIRE", signal);
    }

    private void sendSnapshot(long targetDeviceId, SessionMetadata session) {
        SessionRouteSnapshot snapshot = routes.snapshot(session.sessionId());
        SessionSnapshotRespV2.Builder response = SessionSnapshotRespV2.newBuilder()
                .setSessionId(session.sessionId()).setSessionVersion(session.version())
                .setSessionState(session.state().name())
                .setEndReason(session.endReason() == null ? "" : session.endReason()).setEndCode(session.endCode());
        if (snapshot.committed() != null) {
            response.setCommittedRouteEpoch(snapshot.committed().routeEpoch())
                    .setCommittedAssignmentId(snapshot.committed().assignmentId())
                    .setCommittedEndpoint(endpoint(snapshot.committed()));
        }
        if (snapshot.preparing() != null) {
            response.setPreparingRouteEpoch(snapshot.preparing().routeEpoch())
                    .setPreparingAssignmentId(snapshot.preparing().assignmentId())
                    .setPreparingEndpoint(endpoint(snapshot.preparing()));
        }
        Signal out = Signal.newBuilder().setSessionId(session.sessionId()).setTimestamp(System.currentTimeMillis())
                .setSessionSnapshotRespV2(response).build();
        dispatcher.send(targetDeviceId, session, session.routeEpoch(), "SESSION_SNAPSHOT", out);
    }

    private void sendBoth(SessionMetadata session, Long epoch, String type, Signal signal) {
        dispatcher.send(session.controllerDeviceId(), session, epoch, type, signal);
        dispatcher.send(session.agentDeviceId(), session, epoch, type, signal);
    }

    private void sendInviteResult(long target, SessionMetadata session, long sessionId,
                                  boolean accepted, int code, String message) {
        InviteResp response = InviteResp.newBuilder().setSessionId(sessionId).setAccepted(accepted)
                .setErrorCode(code).setErrorMessage(message == null ? "" : message).build();
        Signal out = Signal.newBuilder().setSessionId(sessionId).setTimestamp(System.currentTimeMillis())
                .setInviteResp(response).build();
        dispatcher.send(target, session, null, "INVITE_RESULT", out);
    }

    private SessionMetadata requireSession(long id) {
        return sessions.find(id).orElseThrow(() -> new IllegalArgumentException("session not found"));
    }
    private static long peer(SessionMetadata session, long actor) {
        if (actor == session.controllerDeviceId()) return session.agentDeviceId();
        if (actor == session.agentDeviceId()) return session.controllerDeviceId();
        throw new SecurityException("actor is not a session member");
    }
    private static void requireMemberAndEpoch(SessionMetadata session, long actor, long epoch) {
        if (!session.isMember(actor)) throw new SecurityException("actor is not a session member");
        long expected = session.isController(actor) ? session.controllerConnectionEpoch() : session.agentConnectionEpoch();
        if (expected > 0 && expected != epoch) throw new SecurityException("stale connection epoch");
        if (session.state().terminal()) throw new IllegalStateException("session already ended");
    }
    private static void requireRoleAndEpoch(SessionMetadata session, long actor, long epoch, boolean controller) {
        requireMemberAndEpoch(session, actor, epoch);
        if (controller != session.isController(actor)) throw new SecurityException("wrong session role");
    }
    private static String requestId(Signal signal) {
        return signal.getTraceId().isBlank() ? UUID.randomUUID().toString() : signal.getTraceId();
    }
    private static RelayEndpointAssignment endpoint(SessionRoute route) {
        return RelayEndpointAssignment.newBuilder().setRelayNodeId(route.relayNodeId())
                .setHost(route.relayHost()).setPort(route.relayPort()).setTls(route.tls())
                .setPathType(route.pathType()).build();
    }
    private RelayNode staticDevRelay() {
        RelayNode node = new RelayNode(); node.setNodeId("static-dev-relay"); node.setHost(signaling.getRelayHost());
        node.setUdpPort(signaling.getRelayPort()); node.setTcpPort(signaling.getRelayTcpPort());
        node.setWsPort(signaling.getRelayWsPort()); node.setTls(signaling.isRelayTls());
        node.setRegion("dev"); node.setStatus(RelayNode.STATUS_ONLINE); return node;
    }
    private static PathType nextRelayPath(PathType path) {
        return switch (path) {
            case RELAY_UDP -> PathType.RELAY_TCP;
            case RELAY_TCP -> PathType.RELAY_WS;
            default -> null;
        };
    }

    @PreDestroy public void close() { timers.shutdownNow(); }
}
