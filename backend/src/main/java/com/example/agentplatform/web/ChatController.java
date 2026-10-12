package com.example.agentplatform.web;

import com.example.agentplatform.dto.ChatRequest;
import com.example.agentplatform.dto.ChatResult;
import com.example.agentplatform.dto.ConfirmRequest;
import com.example.agentplatform.service.ChatService;
import com.example.agentplatform.supervisor.SupervisorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chat;
    private final SupervisorService supervisor;

    public ChatController(ChatService chat, SupervisorService supervisor) {
        this.chat = chat;
        this.supervisor = supervisor;
    }

    /**
     * MANUAL mode: one model call loop over the agents the user selected.
     * AUTO mode: the supervisor plans, picks agents and runs sub-agents.
     * X-Service-Token (optional) is forwarded as a Bearer token to the target services.
     */
    @PostMapping
    public ChatResult chat(@Valid @RequestBody ChatRequest req,
                           @RequestHeader(value = "X-Service-Token", required = false) String token) {
        return req.autoMode() ? supervisor.handle(req, token) : chat.chat(req, token);
    }

    @PostMapping("/confirm")
    public ChatResult confirm(@Valid @RequestBody ConfirmRequest req,
                              @RequestHeader(value = "X-Service-Token", required = false) String token) {
        return supervisor.owns(req.sessionId()) ? supervisor.confirm(req, token) : chat.confirm(req, token);
    }
}
