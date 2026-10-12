package com.example.agentplatform.supervisor;

/** One step as proposed by the planner LLM. */
public record PlanStepDraft(String agent, String intent) {}
