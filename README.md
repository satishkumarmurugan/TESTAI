# Agent Platform

Onboard any HTTP service as an **agent**. Each API of the service is a **tool** (a WebClient call).
Users pick agents in a chat UI, ask a question, and the LLM calls the tools. Write actions
(`requiresConfirmation`) pause until the user approves.

```
frontend/  Angular 20 (standalone, no UI library)   http://localhost:4200
backend/   Spring Boot 4.1 / Spring AI 2.0 / Java 17+ / JPA / H2   http://localhost:8080
```

## Run

### 1. Backend
The LLM layer uses **Spring AI** (Ollama by default).
```
ollama pull qwen2.5:3b-instruct        (any tool-capable model; check `ollama list`)
cd backend
set OLLAMA_MODEL=qwen2.5:3b-instruct   (use `export` on Linux/macOS)
mvn spring-boot:run
```
`OLLAMA_BASE_URL` defaults to http://localhost:11434. Models must support tool calling.

Versions: Spring Boot 4.1.1, Spring AI 2.0.1 (Jackson 3, Hibernate 7). In Spring AI 2.0 a `ChatModel` never
runs tools itself, so `ChatService` drives the loop with `ToolCallingManager` (see `service/ChatService`).
If the first build reports a compile error in a Spring AI class or package, send it over; those names are the
most likely to differ. A Spring AI 1.1 / Boot 3.5 variant is kept in `agent-platform-spring-ai-1.1.zip`.

**Using OpenAI / OpenRouter instead:** in `backend/pom.xml` replace `spring-ai-starter-model-ollama`
with `spring-ai-starter-model-openai`, remove the `spring.ai.ollama` block in `application.yml`, and set:
```
spring.ai.openai.api-key=${LLM_API_KEY}
spring.ai.openai.base-url=https://openrouter.ai/api      # or https://api.openai.com
spring.ai.openai.chat.options.model=openai/gpt-4o-mini   # OpenRouter id; plain gpt-4o-mini for OpenAI
```
Use only one chat starter at a time. Spring AI 2.x renamed some provider properties; check the
OpenAI page of the Spring AI 2.0 reference for the exact names.

Or open `backend/` in IntelliJ and run `AgentPlatformApplication`.

### 2. Frontend
```
cd frontend
npm install
npm start
```
Open http://localhost:4200. The backend URL is `API_BASE` in `frontend/src/app/api.service.ts`.

## Try it (no external services needed)
On first start the backend seeds two demo agents, `kawach` and `notification`, that point at
built-in fake endpoints under `/mock/**`. In the Chat page select both and ask:

> Create user ravi.k in AppDesigner and email him that his account is ready

You will be asked to approve `createUser` and then `sendEmail`. Ask "was it delivered?" afterwards.

Set `platform.seed-examples=false` and `platform.mock-services-enabled=false` to turn the demo off.

## Manual vs Auto mode
Switch in the chat sidebar.

| | Manual | Auto |
|---|---|---|
| Who picks agents/tools | you (tick agents) | the supervisor |
| LLM calls per request | 2+ | about 4 to 8 |
| Needs | any tool-capable model | a model that can follow JSON instructions (a larger model is much better) |

Auto flow (`supervisor/SupervisorService`):
1. **Understand + plan:** the model returns JSON `{goal, missing, steps[{agent, intent}]}`. If something is
   missing it asks you instead of acting.
2. **Execute:** each step runs a sub-agent that only sees that agent's tools (it reuses `ChatService`, so
   approvals for tools flagged `requiresConfirmation` still appear). Earlier step results are passed to later steps.
3. **Stop on trouble:** if a step's tool call returns ERROR, or you reject an action, remaining steps are skipped.
4. **Verify + answer:** the model checks the results against the goal, can add retry steps once, and writes the answer.

The plan and per-step status are shown above the message box. Ticked agents limit what Auto may use
(none ticked = all active agents).

## Onboard your own service
UI: **Onboard service** page. API: `POST /api/agents` (see `AgentDto`).
Rules enforced by the backend:
- agent/tool names: letters, digits, `_` (tool function name sent to the LLM is `<agent>__<tool>`)
- `{placeholders}` in the context path must match the PATH parameters exactly
- parameter types: string, integer, number, boolean
- `headersJson` (optional) must be a JSON object of static headers

## API
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/agents[?status=ACTIVE]` | list agents with tools |
| POST | `/api/agents` | onboard a service |
| PATCH | `/api/agents/{id}/status?value=ACTIVE\|DISABLED` | enable/disable |
| DELETE | `/api/agents/{id}` | remove |
| POST | `/api/chat` | `{sessionId?, query, agentIds[], mode: MANUAL\|AUTO}` -> `DONE` or `PENDING_CONFIRMATION` (+ `plan` in AUTO) |
| POST | `/api/chat/confirm` | `{sessionId, approve}` |

Header `X-Service-Token` on chat calls is forwarded as `Authorization: Bearer ...` to the target services.

## Code map (backend)
- `domain/` Agent, Tool, ToolParam entities
- `tool/DynamicToolExecutor` builds and sends the WebClient request from a Tool + LLM arguments
- `tool/ToolSchemaBuilder` JSON Schema for each tool
- `service/ChatService` Spring AI loop: `ChatModel` call, confirmation pause, `ToolCallingManager` executes tools
- `tool/DynamicToolCallback` Spring AI `ToolCallback` built from a DB `Tool` row
- `service/SessionStore` in-memory sessions
- `supervisor/` AUTO mode: planner, sub-agent runner, verifier (`SupervisorService`, `SupervisorRun`, `PlanStep`)
- `service/AgentService` validation + CRUD; `bootstrap/DataSeeder`, `web/MockServicesController` demo data

## Before production
- **Auth:** the API has no login. Add Spring Security/JWT (e.g. Kawach) and restrict who can onboard agents.
- **SSRF:** set `platform.allowed-hosts` so agents can only target approved hosts.
- **Sessions:** `SessionStore` is in-memory and single-instance; use Redis/DB when scaling out.
- **Database:** replace H2 with PostgreSQL/Oracle and manage schema with Flyway/Liquibase.
- **Secrets:** keep `LLM_API_KEY` in a secret store, not in files.
- **Audit:** log tool calls with user, agent, tool, arguments and outcome.
