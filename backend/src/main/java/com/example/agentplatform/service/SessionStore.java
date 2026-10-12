package com.example.agentplatform.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class SessionStore {

    private static final Duration IDLE_TTL = Duration.ofHours(1);
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    public ChatSession getOrCreate(String id) {
        purgeIdle();
        if (id != null) {
            ChatSession existing = sessions.get(id);
            if (existing != null) {
                existing.setLastUsed(Instant.now());
                return existing;
            }
        }
        ChatSession s = new ChatSession(UUID.randomUUID().toString());
        sessions.put(s.getId(), s);
        return s;
    }

    public Optional<ChatSession> find(String id) {
        ChatSession s = sessions.get(id);
        if (s != null) s.setLastUsed(Instant.now());
        return Optional.ofNullable(s);
    }

    private void purgeIdle() {
        Instant cutoff = Instant.now().minus(IDLE_TTL);
        sessions.values().removeIf(s -> s.getLastUsed().isBefore(cutoff));
    }
}
