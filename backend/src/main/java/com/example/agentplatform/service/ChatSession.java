package com.example.agentplatform.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;

/** In-memory conversation state. Replace with a DB/Redis store for multi-instance deployments. */
@Getter @Setter
public class ChatSession {
    private final String id;
    /** Conversation so far (system, user, assistant, tool-response messages). */
    private final List<Message> messages = new ArrayList<>();
    /** The model response whose tool calls are waiting to be executed. */
    private ChatResponse pendingResponse;
    /** Tool calls from pendingResponse that need user approval; the head is the one being asked. */
    private final Deque<AssistantMessage.ToolCall> approvalQueue = new ArrayDeque<>();
    /** "<name>|<arguments>" keys the user rejected for the current pendingResponse. */
    private final Set<String> rejectedKeys = new HashSet<>();
    private boolean awaitingConfirmation;
    private List<Long> agentIds = new ArrayList<>();
    private Instant lastUsed = Instant.now();

    public ChatSession(String id) { this.id = id; }
}
