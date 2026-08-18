package com.rc.client.ice;

import com.rc.common.model.Endpoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 对称 NAT 端口预测：对称 NAT 对不同目标分配相邻外网端口，
 * 在 srflx 端口附近做增量猜测，生成额外候选供打洞探测（文档 §7.1）。
 */
public final class PortPredictor {

    private PortPredictor() {
    }

    /** 返回 srflx 端口 ± {@code range} 的预测端点（跳过 < 1024 的低端口）。 */
    public static List<Endpoint> predict(Endpoint srflx, int range) {
        List<Endpoint> out = new ArrayList<>();
        for (int delta = 1; delta <= range; delta++) {
            out.add(new Endpoint(srflx.ip(), srflx.port() + delta));
            int lower = srflx.port() - delta;
            if (lower >= 1024) {
                out.add(new Endpoint(srflx.ip(), lower));
            }
        }
        return out;
    }
}
