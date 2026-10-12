package com.example.agentplatform.tool;

import com.example.agentplatform.domain.Tool;
import com.example.agentplatform.dto.ToolTrace;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Spring AI {@link ToolCallback} whose definition and behaviour come from a {@link Tool} row.
 * The model sees {@code getToolDefinition()}; Spring AI's ToolCallingManager calls {@code call(..)}.
 */
public class DynamicToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(DynamicToolCallback.class);
    static final String REJECTED = "The user rejected this action. Do not retry it.";

    private final String key;                    // <agent>__<tool>, the name the model uses
    private final Tool tool;
    private final DynamicToolExecutor executor;
    private final ObjectMapper mapper;
    private final String bearerToken;
    private final Set<String> rejectedKeys;      // "<name>|<arguments>" the user rejected
    private final List<ToolTrace> sink;          // collects executed calls for the UI

    private final ToolDefinition definition;
    private final String agentName;
    private final String toolName;
    private final String description;
    private final boolean requiresConfirmation;

    public DynamicToolCallback(String key, Tool tool, DynamicToolExecutor executor, ToolSchemaBuilder schemaBuilder,
                               ObjectMapper mapper, String bearerToken, Set<String> rejectedKeys,
                               List<ToolTrace> sink) {
        this.key = key;
        this.tool = tool;
        this.executor = executor;
        this.mapper = mapper;
        this.bearerToken = bearerToken;
        this.rejectedKeys = rejectedKeys;
        this.sink = sink;

        // Read lazy JPA data now, inside the request, so later execution never hits a closed session.
        this.agentName = tool.getAgent().getName();
        this.toolName = tool.getName();
        this.description = tool.getDescription();
        this.requiresConfirmation = tool.isRequiresConfirmation();
        tool.getParams().size();

        this.definition = ToolDefinition.builder()
                .name(key)
                .description("[" + agentName + "] " + description)
                .inputSchema(schemaBuilder.buildJson(tool))
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() { return definition; }

    @Override
    public String call(String toolInput) {
        if (rejectedKeys.contains(key + "|" + toolInput)) {
            return REJECTED;
        }
        Map<String, Object> args = parseArgs(toolInput);
        String result = executor.execute(tool, args, bearerToken);
        synchronized (sink) {
            sink.add(new ToolTrace(agentName, toolName, args, result));
        }
        return result;
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }

    public String agentName() { return agentName; }
    public String toolName() { return toolName; }
    public String description() { return description; }
    public boolean requiresConfirmation() { return requiresConfirmation; }

    public Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Could not parse tool arguments: {}", json);
            return new LinkedHashMap<>();
        }
    }
}
