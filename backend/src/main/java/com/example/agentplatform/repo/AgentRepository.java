package com.example.agentplatform.repo;

import com.example.agentplatform.domain.Agent;
import com.example.agentplatform.domain.AgentStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    List<Agent> findByStatus(AgentStatus status);

    boolean existsByName(String name);
}
