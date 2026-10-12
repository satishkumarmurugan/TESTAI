package com.example.agentplatform.dto;

import java.util.List;

public record ChatResult(
        String sessionId,
        String status,            // DONE | PENDING_CONFIRMATION
        String reply,
        PendingTool pendingTool,
        List<ToolTrace> toolCalls,
        PlanView plan) {          // only set in AUTO mode

    public static ChatResult done(String sessionId, List<ToolTrace> trace, String reply) {
        return new ChatResult(sessionId, "DONE", reply, null, trace, null);
    }

    public static ChatResult done(String sessionId, List<ToolTrace> trace, String reply, PlanView plan) {
        return new ChatResult(sessionId, "DONE", reply, null, trace, plan);
    }

    public static ChatResult pending(String sessionId, List<ToolTrace> trace, PendingTool p) {
        return new ChatResult(sessionId, "PENDING_CONFIRMATION", null, p, trace, null);
    }

    public static ChatResult pending(String sessionId, List<ToolTrace> trace, PendingTool p, PlanView plan) {
        return new ChatResult(sessionId, "PENDING_CONFIRMATION", null, p, trace, plan);
    }
}
