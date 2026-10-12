package com.example.agentplatform.tool;

import com.example.agentplatform.domain.Tool;
import com.example.agentplatform.domain.ToolParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Builds the JSON Schema for a tool's arguments, to hand to the LLM. */
@Component
public class ToolSchemaBuilder {

    private final ObjectMapper mapper;

    public ToolSchemaBuilder(ObjectMapper mapper) { this.mapper = mapper; }

    public Map<String, Object> build(Tool tool) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolParam p : tool.getParams()) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", p.getType());
            prop.put("description", p.getDescription() == null ? "" : p.getDescription());
            properties.put(p.getName(), prop);
            if (p.isRequired()) required.add(p.getName());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    public String buildJson(Tool tool) {
        return mapper.writeValueAsString(build(tool));
    }
}
