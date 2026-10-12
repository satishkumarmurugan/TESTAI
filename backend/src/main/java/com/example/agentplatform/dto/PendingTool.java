package com.example.agentplatform.dto;

import java.util.Map;

/** A tool call waiting for the user's approval. */
public record PendingTool(String agent, String tool, String description, Map<String, Object> arguments) {}
