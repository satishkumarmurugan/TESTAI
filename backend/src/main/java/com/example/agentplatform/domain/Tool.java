package com.example.agentplatform.domain;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tool", uniqueConstraints = @UniqueConstraint(columnNames = {"agent_id", "name"}))
@Getter @Setter
public class Tool {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agent_id")
    private Agent agent;

    /** LLM-visible function name, e.g. "createUser". Use [a-zA-Z0-9_]. */
    @Column(nullable = false)
    private String name;

    /** LLM-visible description. Be specific: it drives tool selection. */
    @Column(nullable = false, length = 2000)
    private String description;

    /** GET, POST, PUT, PATCH, DELETE */
    @Column(nullable = false)
    private String httpMethod;

    /** Path relative to agent.baseUrl. May contain {placeholders}, e.g. /{realm}/create-user */
    @Column(nullable = false)
    private String contextPath;

    /** Optional static headers as JSON object: {"X-Source":"agent-platform"} */
    @Column(length = 2000)
    private String headersJson;

    /** If true, the caller must get user approval before executing. */
    private boolean requiresConfirmation;

    @OneToMany(mappedBy = "tool", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<ToolParam> params = new ArrayList<>();

    public void addParam(ToolParam p) {
        p.setTool(this);
        params.add(p);
    }
}
