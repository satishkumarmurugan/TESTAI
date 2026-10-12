package com.example.agentplatform.supervisor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** State of one AUTO-mode conversation. In-memory; use a shared store when running several instances. */
@Getter @Setter
public class SupervisorRun {
    private final String id = UUID.randomUUID().toString();
    /** Recent "User: ..." / "Assistant: ..." lines, so follow-up messages have context. */
    private final List<String> history = new ArrayList<>();
    private List<Long> allowedAgentIds = new ArrayList<>();

    private String goal = "";
    private final List<PlanStep> steps = new ArrayList<>();
    private int current;
    private int replans;

    /** Id of the manual-mode session that runs the current step. */
    private String childSessionId;
    private boolean awaitingApproval;
    private boolean userRejected;

    private Instant lastUsed = Instant.now();

    public void touch() { lastUsed = Instant.now(); }

    public void addHistory(String line) {
        history.add(line);
        while (history.size() > 12) history.remove(0);
    }
}
