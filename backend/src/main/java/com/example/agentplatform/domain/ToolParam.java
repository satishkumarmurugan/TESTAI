package com.example.agentplatform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tool_param")
@Getter @Setter
public class ToolParam {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tool_id")
    private Tool tool;

    @Column(nullable = false)
    private String name;

    /** JSON Schema type: string, integer, number, boolean, array, object */
    @Column(nullable = false)
    private String type = "string";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ParamLocation location = ParamLocation.BODY;

    private boolean required;

    @Column(length = 1000)
    private String description;
}
