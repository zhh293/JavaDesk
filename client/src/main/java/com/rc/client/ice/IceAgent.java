package com.rc.client.ice;

import com.rc.common.constant.Thresholds;
import com.rc.common.model.Endpoint;
import com.rc.common.model.IceCandidate;
import com.rc.common.protocol.CandidateType;
import com.rc.common.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 简化 ICE 编排：候选收集 → 候选交换（由上层经信令完成）→ 打洞探测 → 连通性检查。
 *
 * <p>单一 socket 复用（候选发现与数据面同 socket）。连通性检查以 STUN Binding 探测，
 * 收到对端任意 Binding 响应/请求即判定打洞成功，锁定该对端端点。</p>
 */
public final class IceAgent implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IceAgent.class);

    /** 打洞成功结果：复用 socket + 对端端点 + 会话密钥（由 E2EE 邀请派生，透传）。 */
    public record IceResult(UdpSocket socket, Endpoint peer, byte[] sessionKey) {
    }

    private final UdpSocket socket;
    private final CandidateGatherer gatherer = new CandidateGatherer();
    private final List<Endpoint> stunServers;
    private final String ufrag;
    private final String password;

    public IceAgent(List<Endpoint> stunServers) {
        this.socket = new UdpSocket();
        this.stunServers = stunServers;
        this.ufrag = IdGenerator.newToken(8);
        this.password = IdGenerator.newToken(16);
    }

    public UdpSocket socket() {
        return socket;
    }

    public String ufrag() {
        return ufrag;
    }

    public String password() {
        return password;
    }

    /** 收集 host + srflx 候选（srflx 失败不影响 host 候选返回）。 */
    public List<IceCandidate> gatherCandidates(long stunTimeoutMs) {
        return gatherer.gather(socket, stunServers, stunTimeoutMs, ufrag, password);
    }

    /**
     * 打洞 + 连通性检查。返回的 future 在收到对端首帧 STUN 触点时完成，
     * 或在 {@code budgetMs} 耗尽时异常（{@link TimeoutException}）。
     */
    public CompletableFuture<IceResult> connect(List<IceCandidate> remote, byte[] sessionKey, long budgetMs) {
        List<Endpoint> endpoints = expandEndpoints(remote);
        if (endpoints.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("no remote candidates"));
        }

        CompletableFuture<IceResult> result = new CompletableFuture<>();
        socket.setContactListener(peer -> {
            if (!result.isDone()) {
                log.info("punch success, peer locked at {}", peer);
                result.complete(new IceResult(socket, peer, sessionKey));
            }
        });

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-punch");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(() -> {
            for (Endpoint endpoint : endpoints) {
                socket.send(endpoint, StunCodec.bindingRequest(StunCodec.newTransactionId(), ufrag));
            }
        }, 0, Thresholds.PUNCH_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);

        executor.schedule(() -> {
            if (!result.isDone()) {
                result.completeExceptionally(new TimeoutException("punch timeout"));
            }
        }, budgetMs, TimeUnit.MILLISECONDS);

        result.whenComplete((r, t) -> {
            executor.shutdownNow();
            socket.setContactListener(null);
        });
        return result;
    }

    @Override
    public void close() {
        socket.close();
    }

    /** 按优先级排序并去重候选端点，对 srflx 附加端口预测变体。 */
    private List<Endpoint> expandEndpoints(List<IceCandidate> remote) {
        List<IceCandidate> sorted = new ArrayList<>(remote);
        sorted.sort(Comparator.comparingLong(IceCandidate::priority).reversed());
        Set<Endpoint> seen = new LinkedHashSet<>();
        List<Endpoint> out = new ArrayList<>();
        for (IceCandidate candidate : sorted) {
            Endpoint endpoint = candidate.endpoint();
            if (seen.add(endpoint)) {
                out.add(endpoint);
            }
            if (candidate.type() == CandidateType.CAND_SRFLX) {
                for (Endpoint predicted : PortPredictor.predict(endpoint, 8)) {
                    if (seen.add(predicted)) {
                        out.add(predicted);
                    }
                }
            }
        }
        return out;
    }
}
