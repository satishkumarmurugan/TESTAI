package com.example.agentplatform.dto;

/** status: PENDING | RUNNING | WAITING_APPROVAL | DONE | FAILED | REJECTED | SKIPPED */
public record StepView(int step, String agent, String intent, String status, String result) {}
