package com.example.agentplatform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "agent")
@Getter @Setter
public class Agent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Application name, e.g. "kawach". Must be unique. */
    @Column(nullable = false, unique = true)
    private String name;

    /** What the agent does. Shown in the UI and given to the LLM. */
    @Column(length = 2000)
    private String description;

    /** e.g. https://kawach.internal/api */
    @Column(nullable = false)
    private String baseUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentStatus status = AgentStatus.DRAFT;

    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "agent", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<Tool> tools = new ArrayList<>();

    public void addTool(Tool t) {
        t.setAgent(this);
        tools.add(t);
    }
}
