package com.rc.common.model;

import com.rc.common.constant.SessionStatus;
import com.rc.common.protocol.PathType;

/**
 * 会话实体（对应 MySQL {@code session} 表）。
 */
public class Session {

    private Long id;
    private String sessionId;
    private Long controllerId;
    private Long agentId;
    private SessionStatus status;
    private PathType pathType;
    private Long relayNodeId;
    private Long createdAt;
    private Long endedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getControllerId() {
        return controllerId;
    }

    public void setControllerId(Long controllerId) {
        this.controllerId = controllerId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }

    public PathType getPathType() {
        return pathType;
    }

    public void setPathType(PathType pathType) {
        this.pathType = pathType;
    }

    public Long getRelayNodeId() {
        return relayNodeId;
    }

    public void setRelayNodeId(Long relayNodeId) {
        this.relayNodeId = relayNodeId;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Long endedAt) {
        this.endedAt = endedAt;
    }
}
