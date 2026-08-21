package com.rc.signaling.api;

import com.rc.common.model.RelayNode;
import com.rc.signaling.api.dto.RelayHeartbeatRequest;
import com.rc.signaling.api.dto.RelayNodeInfo;
import com.rc.signaling.service.RelayManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 中继节点内部接口：接收中继服务器心跳，提供在线节点查询（运维 / 调试）。
 *
 * <p>{@code /internal/**} 视为内网接口，生产应置于私有子网并由网络 ACL / mTLS 防护，
 * 不对外暴露。</p>
 */
@RestController
@RequestMapping("/internal/relay-nodes")
public class RelayController {

    private final RelayManager relayManager;

    public RelayController(RelayManager relayManager) {
        this.relayManager = relayManager;
    }

    @PostMapping("/heartbeat")
    public ApiResult<Void> heartbeat(@RequestBody RelayHeartbeatRequest req) {
        RelayNode node = new RelayNode();
        node.setNodeId(req.nodeId());
        node.setHost(req.host());
        node.setRegion(req.region());
        node.setUdpPort(req.udpPort());
        node.setTcpPort(req.tcpPort());
        node.setWsPort(req.wsPort());
        node.setTls(req.tls());
        node.setLoadRatio(req.loadRatio());
        relayManager.heartbeat(node, req.activeSessions(), req.capacity(), req.cpuRatio(),
                req.bandwidthRatio(), req.directMemoryRatio());
        return ApiResult.ok();
    }

    @GetMapping
    public ApiResult<List<RelayNodeInfo>> list() {
        List<RelayNodeInfo> nodes = relayManager.onlineNodes().stream()
                .map(n -> new RelayNodeInfo(n.getNodeId(), n.getHost(), n.getRegion(),
                        n.getUdpPort(), n.getTcpPort(), n.getWsPort(), n.isTls(),
                        n.getLoadRatio(), n.getStatus(), n.getLastHeartbeatAt()))
                .toList();
        return ApiResult.ok(nodes);
    }
}
