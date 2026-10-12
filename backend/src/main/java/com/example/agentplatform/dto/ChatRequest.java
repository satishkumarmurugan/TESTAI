package com.example.agentplatform.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * @param mode     "AUTO" (supervisor plans and picks agents) or "MANUAL" (default; uses agentIds as-is)
 * @param agentIds MANUAL: required, the agents the model may use.
 *                 AUTO: optional, restricts which agents the supervisor may use (empty = all active).
 */
public record ChatRequest(String sessionId, @NotBlank String query, List<Long> agentIds, String mode) {

    public boolean autoMode() {
        return "AUTO".equalsIgnoreCase(mode);
    }
}
