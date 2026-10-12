package com.example.agentplatform.supervisor;

import com.example.agentplatform.domain.Agent;
import com.example.agentplatform.domain.AgentStatus;
import com.example.agentplatform.domain.Tool;
import com.example.agentplatform.dto.*;
import com.example.agentplatform.repo.AgentRepository;
import com.example.agentplatform.service.ChatService;
import com.example.agentplatform.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * AUTO mode. Turns a user prompt into a goal and a plan, picks the agent for each step,
 * runs each step with a sub-agent that only sees that agent's tools, verifies the outcome
 * (replanning once if needed) and writes the final answer.
 *
 * Sub-agents reuse {@link ChatService} (one session per step), so tool confirmations still apply.
 */
@Service
public class SupervisorService {

    private static final Logger log = LoggerFactory.getLogger(SupervisorService.class);
    private static final int MAX_INITIAL_STEPS = 6;
    private static final int MAX_TOTAL_STEPS = 8;
    private static final int MAX_REPLANS = 1;
    private static final Duration IDLE_TTL = Duration.ofHours(1);

    private static final String PLANNER_PROMPT = """
            You are the planner of an agent platform. Turn the user's request into a goal and a short plan.
            Reply with ONLY one JSON object and no other text:
            {"goal":"<one sentence>","missing":["<info the user must still provide>"],"steps":[{"agent":"<exact agent name from the list>","intent":"<what this agent must do, including all concrete values>"}]}
            Rules:
            - Use only agents from the list. Use the fewest steps that achieve the goal.
            - Each step must be something that agent's tools can do. Put concrete values (names, ids, emails) into the intent.
            - Later steps may rely on the results of earlier steps.
            - If a required value is missing and cannot be inferred, list it in "missing" and leave "steps" empty.
            - If no agent can do the request, return empty "missing" and empty "steps".
            """;

    private static final String VERIFIER_PROMPT = """
            You verify whether an automated plan achieved the user's goal.
            Reply with ONLY one JSON object and no other text:
            {"achieved":true,"answer":"<short answer for the user>","retry":[{"agent":"<agent name>","intent":"<what to do>"}]}
            Rules:
            - Base the answer strictly on the step results and tool calls. Never claim success for a failed step.
            - "retry" must be [] unless the goal was not achieved AND another attempt with the listed agents could fix it.
            - A user rejection means the action was intentionally not done: say so, and keep "retry" empty.
            """;

    private final AgentRepository agents;
    private final ChatService chatService;
    private final ChatModel chatModel;
    private final ObjectMapper mapper;
    private final Map<String, SupervisorRun> runs = new ConcurrentHashMap<>();

    public SupervisorService(AgentRepository agents, ChatService chatService, ChatModel chatModel,
                             ObjectMapper mapper) {
        this.agents = agents;
        this.chatService = chatService;
        this.chatModel = chatModel;
        this.mapper = mapper;
    }

    public boolean owns(String sessionId) {
        return sessionId != null && runs.containsKey(sessionId);
    }

    // ------------------------------------------------------------------ entry points

    public ChatResult handle(ChatRequest req, String token) {
        purgeIdle();
        SupervisorRun run = owns(req.sessionId()) ? runs.get(req.sessionId()) : newRun();
        synchronized (run) {
            if (run.isAwaitingApproval()) {
                throw new ApiException(HttpStatus.CONFLICT, "Approve or reject the pending action first");
            }
            run.touch();
            run.setAllowedAgentIds(req.agentIds() == null ? new ArrayList<>() : new ArrayList<>(req.agentIds()));

            List<Agent> allowed = allowedAgents(run);
            if (allowed.isEmpty()) {
                return ChatResult.done(run.getId(), List.of(), "No active agents are available. Onboard a service first.");
            }

            // 1 + 2. Understand the request and plan.
            PlanDraft draft = plan(run, req.query(), allowed);
            run.addHistory("User: " + req.query());

            List<String> missing = nz(draft.missing());
            if (!missing.isEmpty()) {
                String reply = "I need a bit more information before I can do this: " + String.join("; ", missing);
                run.addHistory("Assistant: " + reply);
                return ChatResult.done(run.getId(), List.of(), reply);
            }

            run.getSteps().clear();
            for (PlanStepDraft d : nz(draft.steps())) {
                if (run.getSteps().size() >= MAX_INITIAL_STEPS) break;
                Agent a = findAgent(allowed, d.agent());
                if (a == null || d.intent() == null || d.intent().isBlank()) continue;   // drop invalid steps
                run.getSteps().add(new PlanStep(run.getSteps().size() + 1, a.getName(), a.getId(), d.intent()));
            }
            if (run.getSteps().isEmpty()) {
                String reply = "None of the available agents can do that.";
                run.addHistory("Assistant: " + reply);
                return ChatResult.done(run.getId(), List.of(), reply);
            }

            run.setGoal(draft.goal() == null ? req.query() : draft.goal());
            run.setCurrent(0);
            run.setReplans(0);
            run.setChildSessionId(null);
            run.setUserRejected(false);

            // 3 + 4 + 5. Execute, verify, answer.
            return drive(run, token, new ArrayList<>(), null);
        }
    }

    public ChatResult confirm(ConfirmRequest req, String token) {
        SupervisorRun run = runs.get(req.sessionId());
        if (run == null) throw new ApiException(HttpStatus.NOT_FOUND, "Unknown session");
        synchronized (run) {
            if (!run.isAwaitingApproval()) {
                throw new ApiException(HttpStatus.CONFLICT, "Nothing is waiting for confirmation");
            }
            run.touch();
            run.setUserRejected(!req.approve());
            ChatResult child = chatService.confirm(new ConfirmRequest(run.getChildSessionId(), req.approve()), token);
            run.setAwaitingApproval(false);
            return drive(run, token, new ArrayList<>(), child);
        }
    }

    // ------------------------------------------------------------------ execution

    /**
     * Runs the remaining steps. {@code resume} is the sub-agent result to continue from when we are
     * coming back from an approval; otherwise the current step is started fresh.
     */
    private ChatResult drive(SupervisorRun run, String token, List<ToolTrace> trace, ChatResult resume) {
        while (run.getCurrent() < run.getSteps().size()) {
            PlanStep st = run.getSteps().get(run.getCurrent());

            ChatResult child = resume;
            resume = null;
            if (child == null) {
                st.setStatus("RUNNING");
                child = chatService.chat(
                        new ChatRequest(null, taskMessage(run, st), List.of(st.getAgentId()), "MANUAL"), token);
                run.setChildSessionId(child.sessionId());
            }

            st.getTraces().addAll(child.toolCalls());
            trace.addAll(child.toolCalls());

            if ("PENDING_CONFIRMATION".equals(child.status())) {
                st.setStatus("WAITING_APPROVAL");
                run.setAwaitingApproval(true);
                return ChatResult.pending(run.getId(), trace, child.pendingTool(), planView(run));
            }

            run.setChildSessionId(null);
            st.setResult(child.reply());

            if (run.isUserRejected()) {
                st.setStatus("REJECTED");
                skipRemaining(run, run.getCurrent() + 1);
                run.setUserRejected(false);
                break;
            }

            boolean failed = st.getTraces().stream()
                    .anyMatch(t -> t.result() != null && t.result().startsWith("ERROR"));
            st.setStatus(failed ? "FAILED" : "DONE");
            run.setCurrent(run.getCurrent() + 1);
            if (failed) {
                skipRemaining(run, run.getCurrent());   // don't run later steps that depend on a failed one
            }
        }
        return finish(run, token, trace);
    }

    private void skipRemaining(SupervisorRun run, int from) {
        for (int i = from; i < run.getSteps().size(); i++) {
            PlanStep s = run.getSteps().get(i);
            if ("PENDING".equals(s.getStatus())) s.setStatus("SKIPPED");
        }
        run.setCurrent(run.getSteps().size());
    }

    /** Verify the outcome, optionally replan once, and produce the final answer. */
    private ChatResult finish(SupervisorRun run, String token, List<ToolTrace> trace) {
        Verdict verdict = verify(run);

        boolean canRetry = !verdict.achieved() && run.getReplans() < MAX_REPLANS && !nz(verdict.retry()).isEmpty();
        if (canRetry) {
            List<Agent> allowed = allowedAgents(run);
            int first = run.getSteps().size();
            for (PlanStepDraft d : nz(verdict.retry())) {
                if (run.getSteps().size() >= MAX_TOTAL_STEPS) break;
                Agent a = findAgent(allowed, d.agent());
                if (a == null || d.intent() == null || d.intent().isBlank()) continue;
                run.getSteps().add(new PlanStep(run.getSteps().size() + 1, a.getName(), a.getId(), d.intent()));
            }
            if (run.getSteps().size() > first) {
                run.setReplans(run.getReplans() + 1);
                run.setCurrent(first);
                return drive(run, token, trace, null);
            }
        }

        String answer = verdict.answer() == null || verdict.answer().isBlank()
                ? fallbackAnswer(run) : verdict.answer();
        run.addHistory("Assistant: " + answer);
        return ChatResult.done(run.getId(), trace, answer, planView(run));
    }

    // ------------------------------------------------------------------ LLM steps

    private PlanDraft plan(SupervisorRun run, String query, List<Agent> allowed) {
        String user = "Available agents:\n" + catalog(allowed)
                + "\n\nConversation so far:\n"
                + (run.getHistory().isEmpty() ? "(none)" : String.join("\n", run.getHistory()))
                + "\n\nNew user request:\n" + query;
        JsonNode json = askJson(PLANNER_PROMPT, user);
        try {
            return mapper.treeToValue(json, PlanDraft.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The planner returned an unexpected format");
        }
    }

    private Verdict verify(SupervisorRun run) {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(run.getGoal()).append("\n\nSteps:\n");
        for (PlanStep s : run.getSteps()) {
            sb.append("Step ").append(s.getNo()).append(" [").append(s.getAgent()).append("] ")
              .append(s.getStatus()).append(": ").append(s.getIntent()).append("\n");
            if (s.getResult() != null) sb.append("  result: ").append(cut(s.getResult(), 400)).append("\n");
            for (ToolTrace t : s.getTraces()) {
                sb.append("  tool call: ").append(t.agent()).append(".").append(t.tool())
                  .append(" ").append(t.arguments()).append(" -> ").append(cut(t.result(), 300)).append("\n");
            }
        }
        try {
            JsonNode json = askJson(VERIFIER_PROMPT, sb.toString());
            return mapper.treeToValue(json, Verdict.class);
        } catch (Exception e) {
            log.warn("Verification failed, using fallback: {}", e.getMessage());
            boolean allDone = run.getSteps().stream().allMatch(s -> "DONE".equals(s.getStatus()));
            return new Verdict(allDone, fallbackAnswer(run), List.of());
        }
    }

    /** Asks the model for a JSON object; tolerates <think> blocks and code fences; retries once. */
    private JsonNode askJson(String system, String user) {
        String lastError = "";
        for (int attempt = 0; attempt < 2; attempt++) {
            String prompt = attempt == 0 ? user
                    : user + "\n\nYour previous reply was not valid JSON. Reply with the JSON object only.";
            String raw = ask(system, prompt);
            try {
                String s = raw == null ? "" : raw.replaceAll("(?s)<think>.*?</think>", "").trim();
                int a = s.indexOf('{');
                int b = s.lastIndexOf('}');
                if (a < 0 || b <= a) throw new IllegalArgumentException("no JSON object found");
                return mapper.readTree(s.substring(a, b + 1));
            } catch (Exception e) {
                lastError = e.getMessage();
            }
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY,
                "The model did not return valid JSON (" + lastError + "). Try again or use a stronger model.");
    }

    private String ask(String system, String user) {
        List<Message> messages = List.of(new SystemMessage(system), new UserMessage(user));
        try {
            ChatResponse response = chatModel.call(new Prompt(messages));
            return response.getResult() == null ? "" : response.getResult().getOutput().getText();
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM call failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private String taskMessage(SupervisorRun run, PlanStep st) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(st.getIntent()).append("\n");
        sb.append("Overall goal: ").append(run.getGoal()).append("\n");
        boolean header = false;
        for (PlanStep s : run.getSteps()) {
            if (s.getNo() >= st.getNo() || s.getResult() == null) continue;
            if (!header) { sb.append("Results of earlier steps:\n"); header = true; }
            sb.append("- Step ").append(s.getNo()).append(" (").append(s.getAgent()).append(", ")
              .append(s.getStatus()).append("): ").append(cut(s.getResult(), 400)).append("\n");
        }
        sb.append("Do only this task with your tools. When finished, reply in one short sentence "
                + "saying what happened, including any ids returned.");
        return sb.toString();
    }

    private String catalog(List<Agent> list) {
        StringBuilder sb = new StringBuilder();
        for (Agent a : list) {
            sb.append("- ").append(a.getName()).append(": ").append(a.getDescription() == null ? "" : a.getDescription()).append("\n");
            sb.append("    tools: ").append(a.getTools().stream()
                    .map((Tool t) -> t.getName() + " (" + t.getDescription() + ")")
                    .collect(Collectors.joining("; "))).append("\n");
        }
        return sb.toString();
    }

    private List<Agent> allowedAgents(SupervisorRun run) {
        List<Agent> active = agents.findByStatus(AgentStatus.ACTIVE);
        if (run.getAllowedAgentIds().isEmpty()) return active;
        return active.stream().filter(a -> run.getAllowedAgentIds().contains(a.getId())).toList();
    }

    private Agent findAgent(List<Agent> list, String name) {
        if (name == null) return null;
        return list.stream().filter(a -> a.getName().equalsIgnoreCase(name.trim())).findFirst().orElse(null);
    }

    private PlanView planView(SupervisorRun run) {
        List<StepView> steps = run.getSteps().stream()
                .map(s -> new StepView(s.getNo(), s.getAgent(), s.getIntent(), s.getStatus(), s.getResult()))
                .toList();
        return new PlanView(run.getGoal(), steps);
    }

    private String fallbackAnswer(SupervisorRun run) {
        String text = run.getSteps().stream()
                .filter(s -> s.getResult() != null)
                .map(s -> "Step " + s.getNo() + " (" + s.getAgent() + "): " + s.getResult())
                .collect(Collectors.joining("\n"));
        return text.isBlank() ? "Nothing was done." : text;
    }

    private SupervisorRun newRun() {
        SupervisorRun r = new SupervisorRun();
        runs.put(r.getId(), r);
        return r;
    }

    private void purgeIdle() {
        Instant cutoff = Instant.now().minus(IDLE_TTL);
        runs.values().removeIf(r -> r.getLastUsed().isBefore(cutoff));
    }

    private static <T> List<T> nz(List<T> l) { return l == null ? List.of() : l; }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
