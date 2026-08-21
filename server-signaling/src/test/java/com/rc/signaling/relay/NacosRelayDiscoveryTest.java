package com.rc.signaling.relay;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.rc.common.protocol.PathType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NacosRelayDiscoveryTest {
    @Test void mapsStaticCapabilitiesFromNacosMetadata() {
        Instance instance = new Instance();
        instance.setIp("203.0.113.10");
        instance.setPort(19090);
        instance.setClusterName("cn-east");
        instance.setMetadata(Map.of("nodeId", "relay-1", "region", "cn-east",
                "udpPort", "19090", "tcpPort", "19091", "wsPort", "19092", "tls", "true"));
        var node = NacosRelayDiscovery.fromInstance(instance);
        assertThat(node.getNodeId()).isEqualTo("relay-1");
        assertThat(node.portFor(PathType.RELAY_TCP)).isEqualTo(19091);
        assertThat(node.isTls()).isTrue();
    }
}
