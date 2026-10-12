package com.example.agentplatform.dto;

import java.util.Map;

/** One executed tool call, returned so the UI can show what happened. */
public record ToolTrace(String agent, String tool, Map<String, Object> arguments, String result) {}
