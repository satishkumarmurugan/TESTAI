package com.example.agentplatform.web;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Fake "Kawach" and "Notification" services for demos and local testing.
 * Disable with platform.mock-services-enabled=false.
 */
@RestController
@RequestMapping("/mock")
@ConditionalOnProperty(name = "platform.mock-services-enabled", havingValue = "true", matchIfMissing = true)
public class MockServicesController {

    private final Map<String, Map<String, Map<String, Object>>> users = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> groups = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> roles = new ConcurrentHashMap<>();
    private final Map<String, String> notifications = new ConcurrentHashMap<>();
    private final AtomicLong notificationSeq = new AtomicLong(1000);

    // ---------------------------------------------------------------- kawach

    @PostMapping("/kawach/{realm}/create-user")
    public ResponseEntity<Map<String, Object>> createUser(@PathVariable String realm,
                                                          @RequestBody Map<String, Object> body) {
        String username = String.valueOf(body.getOrDefault("username", ""));
        if (username.isBlank()) return error(HttpStatus.BAD_REQUEST, "username is required");
        Map<String, Map<String, Object>> realmUsers = users.computeIfAbsent(realm, r -> new ConcurrentHashMap<>());
        if (realmUsers.containsKey(username)) return error(HttpStatus.CONFLICT, "user already exists");
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", "u-" + (1000 + realmUsers.size() + 1));
        user.put("username", username);
        user.put("email", body.get("email"));
        realmUsers.put(username, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "created", "user", user));
    }

    @PostMapping("/kawach/{realm}/addGroup")
    public ResponseEntity<Map<String, Object>> addGroup(@PathVariable String realm,
                                                        @RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("groupName", ""));
        if (name.isBlank()) return error(HttpStatus.BAD_REQUEST, "groupName is required");
        boolean added = groups.computeIfAbsent(realm, r -> ConcurrentHashMap.newKeySet()).add(name);
        if (!added) return error(HttpStatus.CONFLICT, "group already exists");
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "created", "group", name));
    }

    @PostMapping("/kawach/{realm}/addRole")
    public ResponseEntity<Map<String, Object>> addRole(@PathVariable String realm,
                                                       @RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("roleName", ""));
        if (name.isBlank()) return error(HttpStatus.BAD_REQUEST, "roleName is required");
        boolean added = roles.computeIfAbsent(realm, r -> ConcurrentHashMap.newKeySet()).add(name);
        if (!added) return error(HttpStatus.CONFLICT, "role already exists");
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "created", "role", name));
    }

    @GetMapping("/kawach/{realm}/users")
    public Map<String, Object> listUsers(@PathVariable String realm) {
        Collection<Map<String, Object>> list = users.getOrDefault(realm, Map.of()).values();
        return Map.of("realm", realm, "count", list.size(), "users", list);
    }

    // ---------------------------------------------------------------- notification

    @PostMapping("/notify/email/send")
    public Map<String, Object> sendEmail(@RequestBody Map<String, Object> body) {
        return queue("EMAIL");
    }

    @PostMapping("/notify/sms/send")
    public Map<String, Object> sendSms(@RequestBody Map<String, Object> body) {
        return queue("SMS");
    }

    @GetMapping("/notify/status/{id}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String id) {
        String type = notifications.get(id);
        if (type == null) return error(HttpStatus.NOT_FOUND, "unknown notification id");
        return ResponseEntity.ok(Map.of("notificationId", id, "type", type, "status", "DELIVERED"));
    }

    private Map<String, Object> queue(String type) {
        String id = "n-" + notificationSeq.incrementAndGet();
        notifications.put(id, type);
        return Map.of("notificationId", id, "status", "QUEUED");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
