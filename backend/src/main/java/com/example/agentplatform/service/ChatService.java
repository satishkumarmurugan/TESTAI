package com.example.agentplatform.service;

import com.example.agentplatform.domain.Agent;
import com.example.agentplatform.domain.AgentStatus;
import com.example.agentplatform.domain.Tool;
import com.example.agentplatform.dto.ChatRequest;
import com.example.agentplatform.dto.ChatResult;
import com.example.agentplatform.dto.ConfirmRequest;
import com.example.agentplatform.dto.PendingTool;
import com.example.agentplatform.dto.ToolTrace;
import com.example.agentplatform.repo.AgentRepository;
import com.example.agentplatform.tool.DynamicToolCallback;
import com.example.agentplatform.tool.DynamicToolExecutor;
import com.example.agentplatform.tool.ToolSchemaBuilder;
import com.example.agentplatform.web.ApiException;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Agent loop on Spring AI.
 *
 * Spring AI 2.0's ChatModel only returns the model's tool-call requests; it does not run them. When it asks for tools:
 *  - tools flagged requiresConfirmation pause the loop until the user approves/rejects each call;
 *  - then Spring AI's ToolCallingManager executes the calls (our DynamicToolCallback does the HTTP work)
 *    and gives back the updated conversation, and we ask the model again.
 */
@Service
public class ChatService {

    private static final int MAX_STEPS = 8;
    private static final String SYSTEM_PROMPT = """
            You operate internal services on behalf of the user by calling the tools provided.
            Rules:
            - Use only the provided tools. Each tool name is <agent>__<tool>.
            - Never invent parameter values. If a required value is missing, ask the user for it.
            - If a tool returns an ERROR, explain it briefly and suggest a fix; do not retry blindly.
            - If the user rejected an action, do not repeat it unless they ask again.
            - Keep answers short and factual.
            """;

    private final AgentRepository agents;
    private final SessionStore store;
    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final DynamicToolExecutor executor;
    private final ToolSchemaBuilder schemaBuilder;
    private final ObjectMapper mapper;

    public ChatService(AgentRepository agents, SessionStore store, ChatModel chatModel,
                       ToolCallingManager toolCallingManager, DynamicToolExecutor executor,
                       ToolSchemaBuilder schemaBuilder, ObjectMapper mapper) {
        this.agents = agents;
        this.store = store;
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.executor = executor;
        this.schemaBuilder = schemaBuilder;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ public API

    public ChatResult chat(ChatRequest req, String serviceToken) {
        if (req.agentIds() == null || req.agentIds().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Select at least one agent");
        }
        ChatSession s = store.getOrCreate(req.sessionId());
        synchronized (s) {
            if (s.isAwaitingConfirmation()) {
                throw new ApiException(HttpStatus.CONFLICT, "Approve or reject the pending action first");
            }
            s.setAgentIds(new ArrayList<>(req.agentIds()));
            if (s.getMessages().isEmpty()) s.getMessages().add(new SystemMessage(SYSTEM_PROMPT));
            s.getMessages().add(new UserMessage(req.query()));
            return runLoop(s, serviceToken, new ArrayList<>());
        }
    }

    public ChatResult confirm(ConfirmRequest req, String serviceToken) {
        ChatSession s = store.find(req.sessionId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Unknown session"));
        synchronized (s) {
            if (!s.isAwaitingConfirmation()) {
                throw new ApiException(HttpStatus.CONFLICT, "Nothing is waiting for confirmation");
            }
            AssistantMessage.ToolCall decided = s.getApprovalQueue().poll();
            if (decided != null && !req.approve()) {
                s.getRejectedKeys().add(key(decided));
            }

            List<ToolTrace> trace = new ArrayList<>();
            Map<String, DynamicToolCallback> callbacks = callbacks(s, serviceToken, trace);

            PendingTool next = nextPending(s, callbacks);
            if (next != null) {
                return ChatResult.pending(s.getId(), trace, next);   // still more approvals to ask
            }
            s.setAwaitingConfirmation(false);
            executePendingToolCalls(s, callbacks);
            return runLoop(s, serviceToken, trace);
        }
    }

    // ------------------------------------------------------------------ loop

    private ChatResult runLoop(ChatSession s, String token, List<ToolTrace> trace) {
        Map<String, DynamicToolCallback> callbacks = callbacks(s, token, trace);

        for (int step = 0; step < MAX_STEPS; step++) {
            ChatResponse response = callModel(s, callbacks);
            Generation generation = response.getResult();
            if (generation == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "The model returned no result");
            }
            AssistantMessage output = generation.getOutput();

            if (!response.hasToolCalls()) {
                s.getMessages().add(output);
                return ChatResult.done(s.getId(), trace, output.getText() == null ? "" : output.getText());
            }

            List<AssistantMessage.ToolCall> calls = output.getToolCalls();
            for (AssistantMessage.ToolCall c : calls) {
                if (!callbacks.containsKey(c.name())) {
                    // Small models sometimes invent tool names. Don't execute; tell the user.
                    return ChatResult.done(s.getId(), trace,
                            "I tried to use a tool that is not available ('" + c.name()
                                    + "'). Please rephrase, or select the right agents.");
                }
            }

            s.setPendingResponse(response);
            s.getRejectedKeys().clear();
            s.getApprovalQueue().clear();
            for (AssistantMessage.ToolCall c : calls) {
                if (callbacks.get(c.name()).requiresConfirmation()) s.getApprovalQueue().add(c);
            }

            PendingTool pending = nextPending(s, callbacks);
            if (pending != null) {
                s.setAwaitingConfirmation(true);
                return ChatResult.pending(s.getId(), trace, pending);
            }
            executePendingToolCalls(s, callbacks);
        }
        return ChatResult.done(s.getId(), trace, "Stopped: too many tool steps for one request.");
    }

    private ChatResponse callModel(ChatSession s, Map<String, DynamicToolCallback> callbacks) {
        Prompt prompt = new Prompt(new ArrayList<Message>(s.getMessages()), options(callbacks));
        try {
            return chatModel.call(prompt);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LLM call failed: " + e.getMessage());
        }
    }

    /** Lets Spring AI execute the pending tool calls and adopts the resulting conversation. */
    private void executePendingToolCalls(ChatSession s, Map<String, DynamicToolCallback> callbacks) {
        Prompt prompt = new Prompt(new ArrayList<Message>(s.getMessages()), options(callbacks));
        ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, s.getPendingResponse());
        List<Message> history = new ArrayList<>(result.conversationHistory());
        s.getMessages().clear();
        s.getMessages().addAll(history);
        s.setPendingResponse(null);
        s.getApprovalQueue().clear();
    }

    // ------------------------------------------------------------------ helpers

    /** In Spring AI 2.0 a ChatModel never executes tools itself, so we stay in control of when they run. */
    private ToolCallingChatOptions options(Map<String, DynamicToolCallback> callbacks) {
        List<ToolCallback> list = new ArrayList<ToolCallback>(callbacks.values());
        return ToolCallingChatOptions.builder()
                .toolCallbacks(list)
                .build();
    }

    private PendingTool nextPending(ChatSession s, Map<String, DynamicToolCallback> callbacks) {
        AssistantMessage.ToolCall head = s.getApprovalQueue().peek();
        if (head == null) return null;
        DynamicToolCallback cb = callbacks.get(head.name());
        if (cb == null) return null;
        return new PendingTool(cb.agentName(), cb.toolName(), cb.description(), cb.parseArgs(head.arguments()));
    }

    private Map<String, DynamicToolCallback> callbacks(ChatSession s, String token, List<ToolTrace> trace) {
        Map<String, DynamicToolCallback> out = new LinkedHashMap<>();
        for (Agent a : agents.findAllById(s.getAgentIds())) {
            if (a.getStatus() != AgentStatus.ACTIVE) continue;
            for (Tool t : a.getTools()) {
                String key = a.getName() + "__" + t.getName();
                out.put(key, new DynamicToolCallback(key, t, executor, schemaBuilder, mapper, token,
                        s.getRejectedKeys(), trace));
            }
        }
        return out;
    }

    private static String key(AssistantMessage.ToolCall call) {
        return call.name() + "|" + call.arguments();
    }
}
