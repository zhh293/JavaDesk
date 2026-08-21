package com.rc.client.relay;

public record RelayAssignment(long sessionId, long routeEpoch, String assignmentId,
                              RelayEndpoint endpoint, String ticket, long deadlineAt) {
    public RelayAssignment {
        if (sessionId <= 0 || routeEpoch <= 0 || assignmentId == null || assignmentId.isBlank()
                || ticket == null || ticket.isBlank()) throw new IllegalArgumentException("invalid relay assignment");
    }
}
