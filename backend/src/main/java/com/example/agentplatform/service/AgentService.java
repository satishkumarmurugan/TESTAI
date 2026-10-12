package com.example.agentplatform.service;

import com.example.agentplatform.config.PlatformProperties;
import com.example.agentplatform.domain.*;
import com.example.agentplatform.dto.AgentDto;
import com.example.agentplatform.dto.ParamDto;
import com.example.agentplatform.dto.ToolDto;
import com.example.agentplatform.repo.AgentRepository;
import com.example.agentplatform.web.ApiException;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    private final AgentRepository repo;
    private final PlatformProperties props;
    private final ObjectMapper mapper;

    public AgentService(AgentRepository repo, PlatformProperties props, ObjectMapper mapper) {
        this.repo = repo;
        this.props = props;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<AgentDto> list(AgentStatus status) {
        List<Agent> agents = status == null ? repo.findAll(Sort.by("name")) : repo.findByStatus(status);
        return agents.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public AgentDto get(Long id) {
        return toDto(find(id));
    }

    @Transactional
    public AgentDto create(AgentDto dto) {
        validate(dto);
        if (repo.existsByName(dto.name())) {
            throw new ApiException(HttpStatus.CONFLICT, "An agent named '" + dto.name() + "' already exists");
        }
        Agent a = new Agent();
        a.setName(dto.name());
        a.setDescription(dto.description());
        a.setBaseUrl(dto.baseUrl().replaceAll("/+$", ""));
        a.setStatus(dto.status() == null ? AgentStatus.ACTIVE : dto.status());

        for (ToolDto t : dto.tools()) {
            Tool tool = new Tool();
            tool.setName(t.name());
            tool.setDescription(t.description());
            tool.setHttpMethod(t.httpMethod().toUpperCase());
            tool.setContextPath(t.contextPath());
            tool.setHeadersJson(blankToNull(t.headersJson()));
            tool.setRequiresConfirmation(t.requiresConfirmation());
            for (ParamDto p : orEmpty(t.params())) {
                ToolParam param = new ToolParam();
                param.setName(p.name());
                param.setType(p.type() == null || p.type().isBlank() ? "string" : p.type());
                param.setLocation(p.location());
                param.setRequired(p.required());
                param.setDescription(p.description());
                tool.addParam(param);
            }
            a.addTool(tool);
        }
        return toDto(repo.save(a));
    }

    @Transactional
    public AgentDto setStatus(Long id, AgentStatus status) {
        Agent a = find(id);
        a.setStatus(status);
        return toDto(a);
    }

    @Transactional
    public void delete(Long id) {
        repo.delete(find(id));
    }

    // ------------------------------------------------------------------ helpers

    private Agent find(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Agent " + id + " not found"));
    }

    private void validate(AgentDto d) {
        URI uri;
        try {
            uri = URI.create(d.baseUrl());
        } catch (Exception e) {
            throw bad("baseUrl is not a valid URL");
        }
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw bad("baseUrl must be an http(s) URL");
        }
        if (!props.getAllowedHosts().isEmpty() && !props.getAllowedHosts().contains(uri.getHost())) {
            throw bad("Host '" + uri.getHost() + "' is not in the allowed host list");
        }

        List<ToolDto> tools = orEmpty(d.tools());
        if (tools.isEmpty()) throw bad("Add at least one tool");

        Set<String> toolNames = new HashSet<>();
        for (ToolDto t : tools) {
            if (!toolNames.add(t.name())) throw bad("Duplicate tool name: " + t.name());
            if (!METHODS.contains(t.httpMethod().toUpperCase())) {
                throw bad("Tool '" + t.name() + "': unsupported HTTP method " + t.httpMethod());
            }
            if (!t.contextPath().startsWith("/")) {
                throw bad("Tool '" + t.name() + "': contextPath must start with '/'");
            }

            Set<String> names = new HashSet<>();
            Set<String> pathParams = new HashSet<>();
            for (ParamDto p : orEmpty(t.params())) {
                if (!names.add(p.name())) throw bad("Tool '" + t.name() + "': duplicate parameter " + p.name());
                String type = p.type() == null || p.type().isBlank() ? "string" : p.type();
                if (!TYPES.contains(type)) {
                    throw bad("Tool '" + t.name() + "': parameter type must be one of " + TYPES);
                }
                if (p.location() == ParamLocation.PATH) pathParams.add(p.name());
            }
            Set<String> placeholders = new HashSet<>();
            Matcher m = PLACEHOLDER.matcher(t.contextPath());
            while (m.find()) placeholders.add(m.group(1));
            if (!placeholders.equals(pathParams)) {
                throw bad("Tool '" + t.name() + "': {placeholders} in contextPath " + placeholders
                        + " must match the PATH parameters " + pathParams);
            }

            if (t.headersJson() != null && !t.headersJson().isBlank()) {
                try {
                    if (!mapper.readTree(t.headersJson()).isObject()) throw new IllegalArgumentException();
                } catch (Exception e) {
                    throw bad("Tool '" + t.name() + "': headersJson must be a JSON object");
                }
            }
        }
    }

    private AgentDto toDto(Agent a) {
        List<ToolDto> tools = a.getTools().stream().map(t -> new ToolDto(
                t.getId(), t.getName(), t.getDescription(), t.getHttpMethod(), t.getContextPath(),
                t.getHeadersJson(), t.isRequiresConfirmation(),
                t.getParams().stream().map(p -> new ParamDto(
                        p.getName(), p.getType(), p.getLocation(), p.isRequired(), p.getDescription()))
                        .collect(Collectors.toList())))
                .collect(Collectors.toList());
        return new AgentDto(a.getId(), a.getName(), a.getDescription(), a.getBaseUrl(), a.getStatus(), tools);
    }

    private static <T> List<T> orEmpty(List<T> l) { return l == null ? List.of() : l; }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static ApiException bad(String msg) { return new ApiException(HttpStatus.BAD_REQUEST, msg); }
}
