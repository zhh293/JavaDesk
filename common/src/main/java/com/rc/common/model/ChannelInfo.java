package com.rc.common.model;

import com.rc.common.protocol.PathType;

/**
 * 传输通道实时状态（路径类型 + QoS 快照），供 {@code TransportChannel#info()} 返回。
 */
public class ChannelInfo {

    private PathType pathType = PathType.PATH_UNKNOWN;
    private int rttMs;
    private double lossRate;
    private double jitterMs;

    public ChannelInfo() {
    }

    public ChannelInfo(PathType pathType, int rttMs, double lossRate, double jitterMs) {
        this.pathType = pathType;
        this.rttMs = rttMs;
        this.lossRate = lossRate;
        this.jitterMs = jitterMs;
    }

    public PathType getPathType() {
        return pathType;
    }

    public void setPathType(PathType pathType) {
        this.pathType = pathType;
    }

    public int getRttMs() {
        return rttMs;
    }

    public void setRttMs(int rttMs) {
        this.rttMs = rttMs;
    }

    public double getLossRate() {
        return lossRate;
    }

    public void setLossRate(double lossRate) {
        this.lossRate = lossRate;
    }

    public double getJitterMs() {
        return jitterMs;
    }

    public void setJitterMs(double jitterMs) {
        this.jitterMs = jitterMs;
    }

    @Override
    public String toString() {
        return "ChannelInfo{pathType=" + pathType
                + ", rttMs=" + rttMs
                + ", lossRate=" + lossRate
                + ", jitterMs=" + jitterMs + '}';
    }
}
