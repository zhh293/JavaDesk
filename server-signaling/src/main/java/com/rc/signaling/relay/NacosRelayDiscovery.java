package com.rc.signaling.relay;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.rc.common.model.RelayNode;
import com.rc.signaling.config.NacosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/** Production Relay discovery backed by Nacos ephemeral instances and push subscriptions. */
@Component
@Profile("prod")
public final class NacosRelayDiscovery implements RelayDiscovery, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(NacosRelayDiscovery.class);
    private final NacosProperties properties;
    private final AtomicReference<List<RelayNode>> snapshot = new AtomicReference<>(List.of());
    private final EventListener listener = event -> {
        if (event instanceof NamingEvent namingEvent) refresh(namingEvent.getInstances());
    };
    private NamingService naming;
    private volatile boolean running;

    public NacosRelayDiscovery(NacosProperties properties) { this.properties = properties; }

    @Override
    public void start() {
        if (running) return;
        if (!properties.isEnabled()) throw new IllegalStateException("rc.nacos.enabled must be true in prod");
        try {
            Properties p = new Properties();
            p.setProperty("serverAddr", properties.getServerAddr());
            p.setProperty("namespace", properties.getNamespace());
            p.setProperty("username", properties.getUsername());
            p.setProperty("password", properties.getPassword());
            naming = NacosFactory.createNamingService(p);
            refresh(naming.selectInstances(properties.getServiceName(), properties.getGroupName(), true, true));
            naming.subscribe(properties.getServiceName(), properties.getGroupName(), listener);
            running = true;
            log.info("Nacos Relay discovery subscribed: service={} group={} instances={}",
                    properties.getServiceName(), properties.getGroupName(), snapshot.get().size());
        } catch (NacosException e) {
            throw new IllegalStateException("cannot start Nacos Relay discovery", e);
        }
    }

    private void refresh(List<Instance> instances) {
        List<RelayNode> nodes = instances.stream().filter(Instance::isHealthy).filter(Instance::isEnabled)
                .map(this::safeFromInstance).filter(java.util.Objects::nonNull).toList();
        snapshot.set(nodes);
    }

    private RelayNode safeFromInstance(Instance instance) {
        try { return fromInstance(instance); }
        catch (RuntimeException malformed) {
            log.warn("ignoring malformed Nacos Relay instance {}: {}", instance.getInstanceId(),
                    malformed.getMessage());
            return null;
        }
    }

    static RelayNode fromInstance(Instance instance) {
        Map<String, String> m = instance.getMetadata();
        RelayNode node = new RelayNode();
        node.setNodeId(required(m, "nodeId"));
        node.setHost(instance.getIp());
        node.setRegion(value(m, "region", instance.getClusterName()));
        node.setUdpPort(number(m, "udpPort", instance.getPort()));
        node.setTcpPort(number(m, "tcpPort", 0));
        node.setWsPort(number(m, "wsPort", 0));
        node.setTls(Boolean.parseBoolean(value(m, "tls", "false")));
        node.setLoadRatio(0);
        node.setStatus(RelayNode.STATUS_ONLINE);
        node.setLastHeartbeatAt(System.currentTimeMillis());
        return node;
    }

    private static String required(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Nacos Relay missing " + key);
        return value;
    }
    private static String value(Map<String, String> metadata, String key, String fallback) {
        String value = metadata.get(key); return value == null || value.isBlank() ? fallback : value;
    }
    private static int number(Map<String, String> metadata, String key, int fallback) {
        String value = metadata.get(key); return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    @Override public List<RelayNode> healthyNodes() { return snapshot.get(); }

    @Override
    public void stop() {
        if (!running) return;
        try { naming.unsubscribe(properties.getServiceName(), properties.getGroupName(), listener); }
        catch (NacosException e) { log.warn("Nacos Relay unsubscribe failed: {}", e.getMessage()); }
        try { naming.shutDown(); } catch (NacosException e) { log.warn("Nacos shutdown failed: {}", e.getMessage()); }
        snapshot.set(List.of()); running = false;
    }
    @Override public boolean isRunning() { return running; }
}
