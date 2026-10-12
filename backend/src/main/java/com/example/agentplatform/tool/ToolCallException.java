package com.example.agentplatform.tool;

public class ToolCallException extends RuntimeException {
    private final int status;

    public ToolCallException(int status, String body) {
        super("HTTP " + status + ": " + body);
        this.status = status;
    }

    public int getStatus() { return status; }
}
