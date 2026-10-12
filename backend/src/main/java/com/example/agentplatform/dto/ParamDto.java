package com.example.agentplatform.dto;

import com.example.agentplatform.domain.ParamLocation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ParamDto(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{1,40}", message = "must be letters, digits or _ (max 40)") String name,
        String type,
        @NotNull ParamLocation location,
        boolean required,
        String description) {}
