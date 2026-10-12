package com.example.agentplatform.web;

import com.example.agentplatform.domain.AgentStatus;
import com.example.agentplatform.dto.AgentDto;
import com.example.agentplatform.service.AgentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService service;

    public AgentController(AgentService service) { this.service = service; }

    @GetMapping
    public List<AgentDto> list(@RequestParam(required = false) AgentStatus status) {
        return service.list(status);
    }

    @GetMapping("/{id}")
    public AgentDto get(@PathVariable Long id) { return service.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentDto create(@Valid @RequestBody AgentDto dto) { return service.create(dto); }

    @PatchMapping("/{id}/status")
    public AgentDto setStatus(@PathVariable Long id, @RequestParam AgentStatus value) {
        return service.setStatus(id, value);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) { service.delete(id); }
}
