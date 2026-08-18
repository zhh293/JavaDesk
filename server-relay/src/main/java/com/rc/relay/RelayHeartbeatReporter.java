package com.rc.relay;

import com.rc.relay.config.RelayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 中继节点心跳上报：周期性向信令服务器内部接口 POST 节点身份 / 端口 / 负载，
 * 供多地域就近调度。plain main 环境（无 Spring），用 JDK 内置 {@link HttpClient}。
 *
 * <p>负载比 {@code loadRatio = activeSessions / capacity}，由 {@code activeSessions}
 * 供应商实时聚合 UDP / TCP / WS 三数据面的活跃会话数。</p>
 */
public final class RelayHeartbeatReporter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RelayHeartbeatReporter.class);

    private final RelayConfig config;
    private final Supplier<Integer> activeSessions;
    private final int capacity;
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;

    public RelayHeartbeatReporter(RelayConfig config, Supplier<Integer> activeSessions, int capacity) {
        this.config = config;
        this.activeSessions = activeSessions;
        this.capacity = Math.max(1, capacity);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rc-relay-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::report, 0, config.heartbeatIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void report() {
        double load = Math.min(1.0, (double) activeSessions.get() / capacity);
        String json = String.format(
                "{\"nodeId\":\"%s\",\"host\":\"%s\",\"region\":\"%s\",\"udpPort\":%d,\"tcpPort\":%d,"
                        + "\"wsPort\":%d,\"tls\":%s,\"loadRatio\":%.4f}",
                esc(config.nodeId()), esc(config.advertiseHost()), esc(config.region()),
                config.udpPort(), config.tcpPort(), config.wsPort(), config.tls(), load);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.signalingUrl()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("relay heartbeat rejected: http={} body={}", resp.statusCode(), resp.body());
            } else {
                log.debug("relay heartbeat ok: node={} region={} load={} sessions={}",
                        config.nodeId(), config.region(), load, activeSessions.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("relay heartbeat failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
