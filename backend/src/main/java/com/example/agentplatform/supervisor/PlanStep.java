package com.example.agentplatform.supervisor;

import com.example.agentplatform.dto.ToolTrace;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class PlanStep {
    private final int no;
    private final String agent;
    private final Long agentId;
    private final String intent;
    /** PENDING | RUNNING | WAITING_APPROVAL | DONE | FAILED | REJECTED | SKIPPED */
    private String status = "PENDING";
    private String result;
    private final List<ToolTrace> traces = new ArrayList<>();

    public PlanStep(int no, String agent, Long agentId, String intent) {
        this.no = no;
        this.agent = agent;
        this.agentId = agentId;
        this.intent = intent;
    }
}
