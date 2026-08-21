package com.rc.relay;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.rc.relay.config.RelayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Registers this Relay as an ephemeral Nacos instance; runtime metrics stay on the metrics channel. */
public final class RelayNacosRegistrar implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RelayNacosRegistrar.class);
    private final RelayConfig config;
    private NamingService naming;
    private Instance instance;

    public RelayNacosRegistrar(RelayConfig config) { this.config = config; }

    public void start() {
        if (!config.nacosEnabled()) {
            log.info("Nacos Relay registration disabled (development mode)");
            return;
        }
        try {
            Properties properties = new Properties();
            properties.setProperty("serverAddr", config.nacosServerAddr());
            properties.setProperty("namespace", config.nacosNamespace());
            properties.setProperty("username", config.nacosUsername());
            properties.setProperty("password", config.nacosPassword());
            naming = NacosFactory.createNamingService(properties);
            instance = new Instance();
            instance.setInstanceId(config.nodeId());
            instance.setIp(config.advertiseHost());
            instance.setPort(config.udpPort());
            instance.setClusterName(config.region());
            instance.setEphemeral(true);
            instance.setEnabled(true);
            instance.setHealthy(true);
            instance.setWeight(Math.max(1, Math.min(100, config.capacity() / 100.0)));
            Map<String, String> metadata = new HashMap<>();
            metadata.put("nodeId", config.nodeId()); metadata.put("region", config.region());
            metadata.put("udpPort", Integer.toString(config.udpPort()));
            metadata.put("tcpPort", Integer.toString(config.tcpPort()));
            metadata.put("wsPort", Integer.toString(config.wsPort()));
            metadata.put("tls", Boolean.toString(config.tls()));
            metadata.put("capacity", Integer.toString(config.capacity()));
            metadata.put("protocolVersion", "2.0");
            instance.setMetadata(metadata);
            naming.registerInstance(config.nacosServiceName(), config.nacosGroupName(), instance);
            log.info("Relay registered in Nacos: service={} group={} node={} region={}",
                    config.nacosServiceName(), config.nacosGroupName(), config.nodeId(), config.region());
        } catch (NacosException e) {
            throw new IllegalStateException("cannot register Relay in Nacos", e);
        }
    }

    @Override
    public void close() {
        if (naming == null || instance == null) return;
        try { naming.deregisterInstance(config.nacosServiceName(), config.nacosGroupName(), instance); }
        catch (NacosException e) { log.warn("Relay Nacos deregistration failed: {}", e.getMessage()); }
        try { naming.shutDown(); } catch (NacosException e) { log.warn("Nacos shutdown failed: {}", e.getMessage()); }
    }
}
