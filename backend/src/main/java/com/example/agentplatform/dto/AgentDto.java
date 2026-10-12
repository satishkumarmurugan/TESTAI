package com.example.agentplatform.dto;

import com.example.agentplatform.domain.AgentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record AgentDto(
        Long id,
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{1,30}", message = "must be letters, digits or _ (max 30)") String name,
        String description,
        @NotBlank String baseUrl,
        AgentStatus status,
        @Valid List<ToolDto> tools) {}
