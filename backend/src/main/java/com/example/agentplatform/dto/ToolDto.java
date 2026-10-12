package com.example.agentplatform.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record ToolDto(
        Long id,
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{1,30}", message = "must be letters, digits or _ (max 30)") String name,
        @NotBlank String description,
        @NotBlank String httpMethod,
        @NotBlank String contextPath,
        String headersJson,
        boolean requiresConfirmation,
        @Valid List<ParamDto> params) {}
