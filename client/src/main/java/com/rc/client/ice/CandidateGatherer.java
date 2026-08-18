package com.rc.client.ice;

import com.rc.common.model.Endpoint;
import com.rc.common.model.IceCandidate;
import com.rc.common.protocol.CandidateType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 候选收集：host（本地网卡）+ srflx（STUN 反射）。
 * 所有候选复用 {@link UdpSocket} 的本地端口，保证与数据面同一 socket。
 */
public final class CandidateGatherer {

    private static final Logger log = LoggerFactory.getLogger(CandidateGatherer.class);

    private static final long HOST_PRIORITY = 126L << 24;
    private static final long SRFLX_PRIORITY = 100L << 24;

    public List<IceCandidate> gather(UdpSocket socket, List<Endpoint> stunServers,
                                     long stunTimeoutMs, String ufrag, String password) {
        List<IceCandidate> candidates = new ArrayList<>();
        int localPort = socket.localEndpoint().port();

        for (String ip : localAddresses()) {
            candidates.add(candidate(CandidateType.CAND_HOST, ip, localPort, HOST_PRIORITY, ufrag, password));
        }

        for (Endpoint server : stunServers) {
            try {
                Endpoint mapped = StunClient.query(socket, server, stunTimeoutMs);
                if (mapped != null) {
                    candidates.add(candidate(CandidateType.CAND_SRFLX, mapped.ip(), mapped.port(),
                            SRFLX_PRIORITY, ufrag, password));
                }
            } catch (Exception e) {
                log.debug("STUN query failed against {}: {}", server, e.getMessage());
            }
        }
        return candidates;
    }

    private static IceCandidate candidate(CandidateType type, String ip, int port,
                                          long priority, String ufrag, String password) {
        return new IceCandidate(type, ip, port, priority, ufrag, password, "0", 0);
    }

    private static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        out.add(addr.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            log.warn("enumerate local interfaces failed: {}", e.getMessage());
        }
        if (out.isEmpty()) {
            out.add("127.0.0.1"); // 仅回环环境兜底，支持同机自测
        }
        return out;
    }
}
