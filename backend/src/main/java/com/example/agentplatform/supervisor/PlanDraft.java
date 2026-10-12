package com.example.agentplatform.supervisor;

import java.util.List;

/** Structured planner output: {"goal":..., "missing":[...], "steps":[{agent,intent}]} */
public record PlanDraft(String goal, List<String> missing, List<PlanStepDraft> steps) {}
