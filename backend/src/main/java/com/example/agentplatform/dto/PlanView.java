package com.example.agentplatform.dto;

import java.util.List;

public record PlanView(String goal, List<StepView> steps) {}
