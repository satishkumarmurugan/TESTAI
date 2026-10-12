package com.example.agentplatform.tool;

import com.example.agentplatform.domain.Agent;
import com.example.agentplatform.domain.Tool;
import com.example.agentplatform.domain.ToolParam;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Executes a Tool definition as an HTTP call using the arguments chosen by the LLM.
 * Returns the response body (or an error string the LLM can read and react to).
 */
@Component
public class DynamicToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DynamicToolExecutor.class);
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RESPONSE_CHARS = 8000;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper mapper;

    public DynamicToolExecutor(WebClient.Builder webClientBuilder, ObjectMapper mapper) {
        this.webClientBuilder = webClientBuilder;
        this.mapper = mapper;
    }

    /**
     * @param bearerToken token to forward to the target service (nullable)
     */
    public String execute(Tool tool, Map<String, Object> args, String bearerToken) {
        Agent agent = tool.getAgent();
        Map<String, Object> pathVars = new HashMap<>();
        Map<String, Object> queryVars = new LinkedHashMap<>();
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>(parseHeaders(tool.getHeadersJson()));

        for (ToolParam p : tool.getParams()) {
            Object value = args == null ? null : args.get(p.getName());
            if (value == null) {
                if (p.isRequired()) {
                    return "ERROR: missing required parameter '" + p.getName() + "'";
                }
                continue;
            }
            switch (p.getLocation()) {
                case PATH -> pathVars.put(p.getName(), value);
                case QUERY -> queryVars.put(p.getName(), value);
                case BODY -> body.put(p.getName(), value);
                case HEADER -> headers.put(p.getName(), String.valueOf(value));
            }
        }

        WebClient client = webClientBuilder.baseUrl(agent.getBaseUrl()).build();
        String method = tool.getHttpMethod().toUpperCase();

        // Query values go through URI variables so they are encoded safely.
        Map<String, Object> uriVars = new HashMap<>(pathVars);
        queryVars.forEach((k, v) -> uriVars.put("q_" + k, v));

        WebClient.RequestBodySpec spec = client
                .method(HttpMethod.valueOf(method))
                .uri(ub -> {
                    ub.path(tool.getContextPath());
                    queryVars.keySet().forEach(k -> ub.queryParam(k, "{q_" + k + "}"));
                    return ub.build(uriVars);
                })
                .headers(h -> {
                    headers.forEach(h::set);
                    if (bearerToken != null && !bearerToken.isBlank()) h.setBearerAuth(bearerToken);
                    h.setAccept(java.util.List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN));
                });

        WebClient.RequestHeadersSpec<?> request =
                (BODY_METHODS.contains(method) && !body.isEmpty())
                        ? spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                        : spec;

        log.info("Tool call {}.{} -> {} {}", agent.getName(), tool.getName(), method, tool.getContextPath());

        try {
            String response = request.retrieve()
                    .onStatus(s -> s.isError(), r -> r.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(b -> new ToolCallException(r.statusCode().value(), b)))
                    .bodyToMono(String.class)
                    .defaultIfEmpty("(empty response)")
                    .block(TIMEOUT);
            return truncate(response);
        } catch (ToolCallException e) {
            log.warn("Tool {} failed: {}", tool.getName(), e.getMessage());
            return "ERROR: " + truncate(e.getMessage());
        } catch (Exception e) {
            log.error("Tool {} crashed", tool.getName(), e);
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private Map<String, String> parseHeaders(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Invalid headersJson, ignoring: {}", json);
            return Map.of();
        }
    }

    private String truncate(String s) {
        return s != null && s.length() > MAX_RESPONSE_CHARS
                ? s.substring(0, MAX_RESPONSE_CHARS) + "...[truncated]"
                : s;
    }
}
