package com.example.agentplatform.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmRequest(@NotBlank String sessionId, boolean approve) {}
