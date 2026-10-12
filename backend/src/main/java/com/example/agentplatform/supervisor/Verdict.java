package com.example.agentplatform.supervisor;

import java.util.List;

/** Structured verifier output: {"achieved":bool, "answer":"...", "retry":[{agent,intent}]} */
public record Verdict(boolean achieved, String answer, List<PlanStepDraft> retry) {}
