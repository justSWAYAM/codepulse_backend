# CodePulse Enterprise — Module 7 Backend Build Plan
### Judge0 Code Execution Service

**Purpose:** The technical core of the platform. This module integrates with a self-hosted Judge0 instance to compile and run candidate code against test cases. It is intentionally a **pure internal service layer** — no new public REST endpoints. The public-facing "Run" and "Submit" actions live in Module 8 (Submission Service); Module 7 is the engine those actions call. Keep this separation clean.

**Depends on:** Module 0 (Cross-Cutting Foundation) + Module 1 (Authentication) + Module 5 (Test Cases, for test case shapes) + Module 6 (Assessment Session, for `SessionFinalizedEvent` hook preparation). Redis infrastructure must be running.

> **Infrastructure prerequisite:** Judge0 must be running and reachable via Docker Compose before any Spring code is written. Verify with `curl` first. Do not write a single line of `Judge0ClientService` until you have a raw HTTP round-trip working in Postman or the terminal.

---

## 1. What This Module Inherits (Do Not Rebuild)

| From | Component | How it's used here |
|---|---|---|
| Module 0 | `GlobalExceptionHandler` + exception hierarchy | `Judge0IntegrationException` (already defined in the hierarchy) is thrown by `Judge0ClientService` on connectivity or unexpected status codes |
| Module 0 | `ApiResponse<T>` wrapper | N/A — this module has no controllers; but the `ExecutionResult` DTO returned to Module 8 follows the same normalize-and-wrap principle |
| Module 0 | `RedisConfig` (`RedisTemplate` bean) | Reused as-is — `SubmissionQueueService` writes/reads from the same Redis instance |
| Module 0 | `CorrelationIdFilter` + MDC | The trace ID from the HTTP request propagates into every Judge0 polling log line — critical for debugging which candidate's run caused a timeout |
| Module 0 | Common enums | New `SubmissionStatus`, `SupportedLanguage`, `TestCaseResultStatus` enums added here (Section 3); added to `com.codepulse_backend.common.enums`, same pattern as `ContestStatus`, `SessionStatus` |
| Module 1 | `SecurityConfig` | Untouched. This module has no controller, so it adds no security config |
| Module 5 | `TestCase` entity + `TestCaseAdminResponse` | `CodeExecutionService` receives test case data from Module 8; knows its shape but does NOT own a `TestCaseRepository` — dependency direction is always Module 7 → Module 5, never reverse |
| Module 6 | `SessionFinalizedEvent` | This module ships a stub listener for it (Section 10.2). Module 8 replaces the stub with real scoring logic |
| `application-local.yml` | `judge0.base-url: http://localhost:2358` | Already present. Module 7 binds this via `@ConfigurationProperties` — do not hardcode it |

**Blocker check:** Run `curl http://localhost:2358/about` after starting Judge0 via Docker Compose. If you don't get a JSON response, do not proceed with Spring code.

---

## 2. Decisions to Make Before Writing Any Code

### 2.1 Polling vs. Callbacks — use polling
Judge0 supports two modes: polling (client calls `GET /submissions/{token}` until `status.id >= 3`) and webhook callbacks (Judge0 POSTs to your server on completion). **Use polling** for this project. Reasons:
- Your backend and Judge0 run on the same Docker network — polling latency is negligible.
- Callbacks require a public-facing URL and a new endpoint in your security config — added complexity for no gain in this topology.
- The roadmap explicitly says "poll for result" in the Module 7 description.

Document this decision in a code comment in `Judge0ClientService`. If a future deployment moves Judge0 off-network, the switch to callbacks is a one-class change.

### 2.2 Synchronous execution for "Run", async queue for "Submit"
The user experience demand is:
- **"Run"** feels instant: candidate hits run, sees output in ~2 seconds. This must be synchronous from the HTTP request perspective (Module 8's `POST /run` blocks until Judge0 completes). Acceptable because "Run" only runs against 1–3 sample test cases.
- **"Submit"** may run against 10–30 hidden test cases concurrently. Blocking an HTTP thread for every test case at exam peak (30 candidates submitting simultaneously) would exhaust the thread pool. This must be async: `POST /submit` (Module 8) returns immediately with `status: PENDING`; a Redis-backed queue worker processes each test case sequentially per submission.

Module 7's `CodeExecutionService` handles both modes with a `RunMode` parameter (`SYNCHRONOUS` / `QUEUED`). Module 8 decides which to use.

### 2.3 Never leak Judge0's raw response schema
Judge0 returns fields like `status.id`, `status.description`, `stderr`, `compile_output`, `time`, `memory`. Your own DTO (`ExecutionResult`) must **wrap** all of this. Module 8 and Module 9 consume `ExecutionResult` — they must never import Judge0 types. If you ever change or replace Judge0 (e.g., to Piston or a custom runner), Module 7 is the only file that changes.

### 2.4 Language ID mapping — centralized enum
Judge0 uses integer IDs to identify languages (e.g., `62` = Java, `71` = Python 3, `54` = C++17, `63` = JavaScript). Your platform uses string names. Define a `SupportedLanguage` enum in `com.codepulse_backend.common.enums` that maps each supported language name to its Judge0 ID. Module 3 already stores `allowedLanguages` as a `List<String>` — these strings must match `SupportedLanguage.name()`. This enum is the single source of truth for all language-to-Judge0 mapping.

### 2.5 Retry policy — one retry, then fail
Judge0 calls are HTTP calls and can fail transiently (network blip, Judge0 restart during exam). Define a simple retry: on connection error or 5xx from Judge0, retry once after 500ms, then throw `Judge0IntegrationException`. Do **not** use Spring Retry (`@Retryable`) — the retry logic here is trivial enough that a manual `try/catch` + sleep loop is clearer and has no dependency overhead. If it grows complex later, add Spring Retry then.

### 2.6 Resource limits come from the Question, not from a global config
`time_limit_ms` and `memory_limit_kb` are stored on the `Question` entity (set in Module 4). Module 8 passes these to Module 7 when dispatching execution. Module 7 does not look up questions itself — it receives an `ExecutionRequest` DTO that already has limits set. This keeps the dependency arrow clean: Module 7 knows nothing about `Question`.

### 2.7 The queue is per-submission, not per-test-case
When a candidate submits, one queue job is pushed. The worker processes all test cases for that submission in sequence (one Judge0 call per test case, not parallel). This avoids flooding Judge0. Parallelism can be added later; sequential is correct first.

### 2.8 No new database tables
Module 7 is entirely stateless with respect to PostgreSQL. All persistent state (`Submission`, `SubmissionTestCaseResult`) belongs to Module 8. Module 7's only persistent medium is the Redis queue (transient by nature — if Redis restarts, in-flight submissions are re-queued by Module 8's recovery logic, which is Module 8's problem to define).

---

## 3. New Common Enums (Add to Existing Package)

**File:** `src/main/java/com/codepulse_backend/common/enums/SubmissionStatus.java`

```java
package com.codepulse_backend.common.enums;

/**
 * Lifecycle status of a Submission (Module 8).
 * Defined here in Module 7 because CodeExecutionService produces it.
 *
 * PENDING              — queued, not yet sent to Judge0
 * RUNNING              — dispatched to Judge0, polling for result
 * ACCEPTED             — all test cases passed
 * WRONG_ANSWER         — at least one test case produced incorrect output
 * TIME_LIMIT_EXCEEDED  — at least one test case exceeded time limit
 * MEMORY_LIMIT_EXCEEDED — at least one test case exceeded memory limit
 * RUNTIME_ERROR        — program crashed (non-zero exit, SIGSEGV, etc.)
 * COMPILATION_ERROR    — code did not compile
 * SYSTEM_ERROR         — Judge0 returned an unexpected status or was unreachable
 */
public enum SubmissionStatus {
    PENDING,
    RUNNING,
    ACCEPTED,
    WRONG_ANSWER,
    TIME_LIMIT_EXCEEDED,
    MEMORY_LIMIT_EXCEEDED,
    RUNTIME_ERROR,
    COMPILATION_ERROR,
    SYSTEM_ERROR
}
```

**File:** `src/main/java/com/codepulse_backend/common/enums/SupportedLanguage.java`

```java
package com.codepulse_backend.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Maps CodePulse language names (stored in contests.allowed_languages and
 * sent by the frontend) to Judge0 language IDs.
 *
 * Source: https://ce.judge0.com/languages (or your self-hosted instance's /languages).
 * Update this enum when adding new languages — nowhere else needs to change.
 */
@Getter
@RequiredArgsConstructor
public enum SupportedLanguage {
    JAVA("Java", 62),
    PYTHON("Python", 71),
    CPP("C++17", 54),
    C("C (GCC 9.2.0)", 50),
    JAVASCRIPT("JavaScript", 63);

    private final String displayName;
    private final int judge0Id;

    public static SupportedLanguage fromName(String name) {
        for (SupportedLanguage lang : values()) {
            if (lang.name().equalsIgnoreCase(name) || lang.displayName.equalsIgnoreCase(name)) {
                return lang;
            }
        }
        throw new IllegalArgumentException("Unsupported language: " + name);
    }
}
```

**File:** `src/main/java/com/codepulse_backend/common/enums/TestCaseResultStatus.java`

```java
package com.codepulse_backend.common.enums;

/**
 * Result of running a single test case through Judge0.
 * Mirrors SubmissionStatus but scoped to one test case (not an aggregate).
 */
public enum TestCaseResultStatus {
    PASSED,
    WRONG_ANSWER,
    TIME_LIMIT_EXCEEDED,
    MEMORY_LIMIT_EXCEEDED,
    RUNTIME_ERROR,
    COMPILATION_ERROR,
    SYSTEM_ERROR
}
```

---

## 4. Infrastructure Setup: Judge0 via Docker Compose

Extend the existing `docker-compose.yml` at the project root. Judge0's CE (Community Edition) requires three services: the server, its workers, and its own PostgreSQL + Redis instances (separate from the app's).

**Updated `docker-compose.yml`:**

```yaml
services:
  # ── Existing services ─────────────────────────────────────────────────────
  postgres:
    image: postgres:16
    container_name: codepulse-postgres
    environment:
      POSTGRES_DB: codepulse
      POSTGRES_USER: codepulse
      POSTGRES_PASSWORD: codepulse_local
    ports:
      - "5432:5432"
    volumes:
      - pg_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    container_name: codepulse-redis
    ports:
      - "6379:6379"

  # ── Judge0 CE (self-hosted) ───────────────────────────────────────────────
  judge0-server:
    image: judge0/judge0:1.13.1
    container_name: codepulse-judge0-server
    ports:
      - "2358:2358"
    volumes:
      - ./judge0.conf:/judge0.conf:ro
    environment:
      JUDGE0_CONFIG_FILE: /judge0.conf
    privileged: true           # required for isolate sandboxing
    restart: always
    depends_on:
      - judge0-db
      - judge0-redis

  judge0-worker:
    image: judge0/judge0:1.13.1
    container_name: codepulse-judge0-worker
    command: ["./scripts/workers"]
    volumes:
      - ./judge0.conf:/judge0.conf:ro
    environment:
      JUDGE0_CONFIG_FILE: /judge0.conf
    privileged: true
    restart: always
    depends_on:
      - judge0-db
      - judge0-redis

  judge0-db:
    image: postgres:16
    container_name: codepulse-judge0-db
    environment:
      POSTGRES_DB: judge0
      POSTGRES_USER: judge0
      POSTGRES_PASSWORD: judge0
    volumes:
      - judge0_pg_data:/var/lib/postgresql/data

  judge0-redis:
    image: redis:7-alpine
    container_name: codepulse-judge0-redis
    # NOT exposed on host — only reachable within the Docker network

volumes:
  pg_data:
  judge0_pg_data:
```

**`judge0.conf`** (create at project root alongside `docker-compose.yml`):

```ini
REDIS_HOST=judge0-redis
REDIS_PORT=6379

POSTGRES_HOST=judge0-db
POSTGRES_PORT=5432
POSTGRES_DB=judge0
POSTGRES_USER=judge0
POSTGRES_PASSWORD=judge0

# Sandbox limits
CPU_TIME_LIMIT=5
WALL_TIME_LIMIT=10
MEMORY_LIMIT=256000
STACK_LIMIT=128000
MAX_PROCESSES_AND_OR_THREADS=60
MAX_FILE_SIZE=4096
ENABLE_PER_PROCESS_AND_THREAD_MEMORY_LIMIT=false

# Authentication — empty = no auth for local dev. Set in production.
AUTHN_HEADER=X-Judge0-Token
AUTHN_TOKEN=
```

**Verification steps before writing any Spring code:**

```bash
# 1. Start all services
docker compose up -d

# 2. Wait ~15s for Judge0 to initialise, then:
curl http://localhost:2358/about

# 3. Submit a Python hello world manually:
curl -X POST http://localhost:2358/submissions \
  -H "Content-Type: application/json" \
  -d '{"source_code":"print(\"hello\")","language_id":71,"stdin":""}' \
  | python -m json.tool

# 4. Use the returned token to poll:
curl "http://localhost:2358/submissions/{TOKEN}?fields=stdout,stderr,status,time,memory"
```

---

## 5. New Maven Dependency

Add to `pom.xml` (inside `<dependencies>`):

```xml
<!-- WebClient for non-blocking HTTP to Judge0 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

> **Note:** `WebClient` requires `spring-boot-starter-webflux` even in a Servlet/MVC application. If this causes a classpath conflict (unlikely with Spring Boot 4.x), fall back to Apache HttpClient 5 wrapped in a custom `Judge0RestClient`. Check compiler output after adding this dependency.

---

## 6. Configuration Properties

**File:** `src/main/java/com/codepulse_backend/config/Judge0Properties.java`

```java
package com.codepulse_backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "judge0")
public class Judge0Properties {

    /** Base URL of the Judge0 instance, e.g. http://localhost:2358 */
    private String baseUrl = "http://localhost:2358";

    /** Optional auth token. Empty string = no authentication (local dev). */
    private String authToken = "";

    /** Header name for auth token (matches judge0.conf AUTHN_HEADER) */
    private String authHeader = "X-Judge0-Token";

    /** Max polling attempts before giving up and returning SYSTEM_ERROR */
    private int maxPollAttempts = 20;

    /** Milliseconds between each polling attempt */
    private long pollIntervalMs = 500;

    /** Connection timeout to Judge0 (milliseconds) */
    private long connectTimeoutMs = 5000;

    /** Read timeout for HTTP calls to Judge0 (milliseconds) */
    private long readTimeoutMs = 15000;
}
```

Extend `application-local.yml`:

```yaml
judge0:
  base-url: http://localhost:2358
  auth-token: ""
  max-poll-attempts: 20
  poll-interval-ms: 500
```

---

## 7. Internal DTOs (Package: `com.codepulse_backend.execution.dto`)

These DTOs are never serialized to HTTP responses directly. They are the internal contract between Module 7 and Module 8.

### 7.1 `ExecutionRequest` — what Module 8 passes to Module 7

```java
package com.codepulse_backend.execution.dto;

import java.util.UUID;

/**
 * Input to CodeExecutionService for a single test case execution.
 * Module 8 builds this; Module 7 consumes it.
 * All limits come from the Question entity — Module 8 resolves them before calling.
 */
public record ExecutionRequest(
    UUID    submissionId,      // for logging / correlation only
    UUID    testCaseId,        // so Module 8 can persist results by test case
    String  sourceCode,
    String  languageName,      // maps to SupportedLanguage enum
    String  stdin,             // test case input
    int     timeLimitMs,       // from Question.timeLimitMs
    int     memoryLimitKb      // from Question.memoryLimitKb
) {}
```

### 7.2 `ExecutionResult` — what Module 7 returns to Module 8

```java
package com.codepulse_backend.execution.dto;

import com.codepulse_backend.common.enums.TestCaseResultStatus;

/**
 * Normalised output from a single test case execution.
 * Module 8 persists this into SubmissionTestCaseResult.
 * Judge0-specific fields (token, status.id) are NOT exposed here.
 */
public record ExecutionResult(
    TestCaseResultStatus status,
    String  stdout,            // actual output (truncated to 64 KB if needed)
    String  stderr,            // runtime error output
    String  compileOutput,     // compilation error message
    Double  executionTimeMs,   // null if compilation error or system error
    Integer memoryUsedKb       // null if compilation error or system error
) {}
```

### 7.3 `Judge0SubmissionRequest` — internal DTO for Judge0 API call

```java
package com.codepulse_backend.execution.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Matches Judge0's POST /submissions body.
 * Used ONLY by Judge0ClientService — do NOT reference in other services.
 */
public record Judge0SubmissionRequest(
    @JsonProperty("source_code")    String sourceCode,
    @JsonProperty("language_id")    int languageId,
    @JsonProperty("stdin")          String stdin,
    @JsonProperty("cpu_time_limit") Double cpuTimeLimit,   // seconds (Judge0 uses seconds)
    @JsonProperty("memory_limit")   Integer memoryLimit    // KB
) {}
```

### 7.4 `Judge0StatusResponse` — internal DTO for polling response

```java
package com.codepulse_backend.execution.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Subset of Judge0's GET /submissions/{token} response.
 *
 * Judge0 status ID reference:
 *   1  = In Queue       2  = Processing     3  = Accepted
 *   4  = Wrong Answer   5  = TLE            6  = Compilation Error
 *   7-12, 14 = Runtime Error variants       15 = Memory Limit Exceeded
 *   13 = Internal Error  (treat as SYSTEM_ERROR)
 */
public record Judge0StatusResponse(
    @JsonProperty("token")          String token,
    @JsonProperty("status")         Judge0Status status,
    @JsonProperty("stdout")         String stdout,
    @JsonProperty("stderr")         String stderr,
    @JsonProperty("compile_output") String compileOutput,
    @JsonProperty("time")           String time,      // seconds as string, e.g. "0.041"
    @JsonProperty("memory")         Integer memory    // KB
) {
    public record Judge0Status(
        @JsonProperty("id")          int id,
        @JsonProperty("description") String description
    ) {}
}
```

### 7.5 `QueuedSubmissionJob` — Redis queue payload

```java
package com.codepulse_backend.execution.dto;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

/**
 * The object pushed into Redis for async "Submit" processing.
 * Must be JSON-serialisable (GenericJackson2JsonRedisSerializer).
 * Module 8 pushes this; SubmissionQueueWorker pops it.
 */
public record QueuedSubmissionJob(
    UUID   submissionId,
    UUID   questionId,
    UUID   sessionId,
    String sourceCode,
    String languageName,
    List<TestCasePayload> testCases
) implements Serializable {

    public record TestCasePayload(
        UUID   testCaseId,
        String input,
        int    timeLimitMs,
        int    memoryLimitKb
    ) implements Serializable {}
}
```

---

## 8. Package Structure

```
com.codepulse_backend/
├── common/enums/
│   ├── SubmissionStatus.java         ← NEW
│   ├── SupportedLanguage.java        ← NEW (language → Judge0 ID map)
│   └── TestCaseResultStatus.java     ← NEW
├── config/
│   ├── Judge0Properties.java         ← NEW
│   └── Judge0WebClientConfig.java    ← NEW
└── execution/
    ├── dto/
    │   ├── ExecutionRequest.java         ← NEW
    │   ├── ExecutionResult.java          ← NEW
    │   ├── Judge0SubmissionRequest.java  ← NEW (internal only)
    │   ├── Judge0StatusResponse.java     ← NEW (internal only)
    │   └── QueuedSubmissionJob.java      ← NEW
    ├── event/
    │   ├── SubmissionEvaluatedEvent.java ← NEW (Module 8 consumes)
    │   └── SessionFinalizedListener.java ← NEW (stub; Module 8 replaces)
    ├── Judge0ClientService.java          ← NEW
    ├── CodeExecutionService.java         ← NEW
    ├── SubmissionQueueService.java       ← NEW
    └── SubmissionQueueWorker.java        ← NEW
```

---

## 9. Service Specifications

### 9.1 `Judge0WebClientConfig`

**File:** `src/main/java/com/codepulse_backend/config/Judge0WebClientConfig.java`

```java
package com.codepulse_backend.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import java.time.Duration;

@Configuration
public class Judge0WebClientConfig {

    @Bean
    public WebClient judge0WebClient(Judge0Properties props) {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) props.getConnectTimeoutMs())
            .responseTimeout(Duration.ofMillis(props.getReadTimeoutMs()));

        return WebClient.builder()
            .baseUrl(props.getBaseUrl())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .defaultHeader(props.getAuthHeader(), props.getAuthToken())
            .build();
    }
}
```

---

### 9.2 `Judge0ClientService`

**File:** `src/main/java/com/codepulse_backend/execution/Judge0ClientService.java`

**Responsibility:** Only class that speaks HTTP to Judge0. No business logic — only HTTP, retry, and raw DTO mapping.

**Behaviour contract:**

| Method | Behaviour |
|---|---|
| `submitCode(request)` | POST `/submissions?wait=false` → returns token string. Retries once on connection failure. Throws `Judge0IntegrationException` on second failure. |
| `pollResult(token)` | GET `/submissions/{token}?fields=...`. Loops up to `maxPollAttempts` times. Status 1 or 2 → sleep `pollIntervalMs` → retry. Status ≥ 3 → return. Exhausted → throw `Judge0IntegrationException("Execution timed out")`. |

**Logging:** Log at `DEBUG` on each poll iteration (token + status description). Log at `INFO` when submission is accepted or result is terminal. Log at `ERROR` before throwing `Judge0IntegrationException`.

**Key implementation notes:**
- Use `WebClient.post().uri("/submissions").queryParam("wait", false).bodyValue(request).retrieve().bodyToMono(Map.class).block()` to get the token.
- Use `WebClient.get().uri("/submissions/{token}", token).retrieve().bodyToMono(Judge0StatusResponse.class).block()` for polling.
- The `block()` call is intentional — this service is used in a synchronous service layer (Module 8 calls it from a regular `@Transactional` method). The reactive chain does not need to be propagated here.
- Fields to request in the GET query: `token,status,stdout,stderr,compile_output,time,memory`.

---

### 9.3 `CodeExecutionService`

**File:** `src/main/java/com/codepulse_backend/execution/CodeExecutionService.java`

**Responsibility:** Orchestration. Builds the `Judge0SubmissionRequest` (converting ms → seconds, KB → KB, language name → ID), calls `Judge0ClientService`, maps the raw `Judge0StatusResponse` to `ExecutionResult`.

**Judge0 status ID → `TestCaseResultStatus` mapping:**

| Judge0 status ID | `TestCaseResultStatus` |
|---|---|
| 3 | `PASSED` |
| 4 | `WRONG_ANSWER` |
| 5 | `TIME_LIMIT_EXCEEDED` |
| 6 | `COMPILATION_ERROR` |
| 15 | `MEMORY_LIMIT_EXCEEDED` |
| 7, 8, 9, 10, 11, 12, 14 | `RUNTIME_ERROR` |
| 13 or any other | `SYSTEM_ERROR` |
| `Judge0IntegrationException` thrown | `SYSTEM_ERROR` (caught here, never re-thrown) |

**Output truncation rules:** `stdout` → 65,536 characters; `stderr` → 8,192 characters; `compileOutput` → 8,192 characters. Append `"\n... [truncated]"` if cut. Null input → null output (no NPE).

**Time conversion:** Judge0 returns `time` as a string of seconds (e.g., `"0.041"`). Convert to milliseconds: `Double.parseDouble(response.time()) * 1000.0`. On `NumberFormatException`, return `null` for `executionTimeMs`.

---

### 9.4 `SubmissionQueueService`

**File:** `src/main/java/com/codepulse_backend/execution/SubmissionQueueService.java`

**Redis key:** `codepulse:submission:queue`

**Operations:**

| Method | Redis operation | Notes |
|---|---|---|
| `push(job)` | `RPUSH` (right push) | Job serialised to JSON |
| `pop()` | `LPOP` (left pop) | Returns `null` if queue is empty |
| `size()` | `LLEN` | For monitoring logs only |

**Redis serialisation requirement:** `RedisTemplate` must use `GenericJackson2JsonRedisSerializer` for values (not `JdkSerializationRedisSerializer`). See Section 11.1.

---

### 9.5 `SubmissionQueueWorker`

**File:** `src/main/java/com/codepulse_backend/execution/SubmissionQueueWorker.java`

**Schedule:** `@Scheduled(fixedDelay = 1000)` — poll every 1 second.

**Per-tick budget:** Process at most 5 jobs per tick to avoid starving other `@Scheduled` jobs (e.g., `SessionAutoSubmitScheduler`).

**Per-job flow:**
1. Pop `QueuedSubmissionJob` from `SubmissionQueueService`.
2. For each `TestCasePayload` in `job.testCases()` (sequential, not parallel):
   a. Build `ExecutionRequest` from job + test case data.
   b. Call `CodeExecutionService.execute(request)`.
   c. Collect result into `List<TestCaseOutcome>`.
3. Publish `SubmissionEvaluatedEvent(submissionId, outcomes)`.
4. Log completion at `INFO` level.

**Error handling:** `CodeExecutionService.execute()` never throws — it returns `SYSTEM_ERROR` on failure. The worker therefore never catches from it. Any uncaught exception from Redis or event publishing is logged at `ERROR` and the job is considered lost (Module 8's recovery handles this via status check on restart).

---

## 10. Events

### 10.1 `SubmissionEvaluatedEvent` (NEW — consumed by Module 8)

**File:** `src/main/java/com/codepulse_backend/execution/event/SubmissionEvaluatedEvent.java`

```java
package com.codepulse_backend.execution.event;

import com.codepulse_backend.common.enums.TestCaseResultStatus;
import java.util.List;
import java.util.UUID;

/**
 * Published by SubmissionQueueWorker after all test cases for a submission have been executed.
 * Module 8 listens to this to persist SubmissionTestCaseResult rows and compute the score.
 *
 * NOT a Spring transactional event — it fires after the worker's processing, not after a DB tx.
 * Module 8's listener must open its own @Transactional context.
 */
public record SubmissionEvaluatedEvent(
    UUID submissionId,
    List<TestCaseOutcome> outcomes
) {
    public record TestCaseOutcome(
        UUID testCaseId,
        TestCaseResultStatus status,
        String stdout,
        String stderr,
        String compileOutput,
        Double executionTimeMs,
        Integer memoryUsedKb
    ) {}
}
```

Add a stub listener for it so the event is verifiable before Module 8:

**File:** `src/main/java/com/codepulse_backend/execution/event/SubmissionEvaluatedStubListener.java`

```java
package com.codepulse_backend.execution.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SubmissionEvaluatedStubListener {

    /**
     * Module 7 stub — Module 8 adds its own listener and this stub is removed.
     * Logs each outcome so the event can be verified during integration testing.
     */
    @EventListener
    public void onSubmissionEvaluated(SubmissionEvaluatedEvent event) {
        log.info("[Module 7 stub] SubmissionEvaluatedEvent received for submission {}. Outcomes: {}",
                event.submissionId(), event.outcomes().size());
        event.outcomes().forEach(o ->
            log.debug("  TestCase {} → {} ({} ms)", o.testCaseId(), o.status(), o.executionTimeMs())
        );
    }
}
```

### 10.2 `SessionFinalizedListener` stub (Module 6 event → Module 7 stub → Module 8 replaces)

**File:** `src/main/java/com/codepulse_backend/execution/event/SessionFinalizedListener.java`

```java
package com.codepulse_backend.execution.event;

import com.codepulse_backend.session.event.SessionFinalizedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

@Component
@Slf4j
public class SessionFinalizedListener {

    /**
     * Stub listener for SessionFinalizedEvent (published by Module 6).
     * Module 8 replaces this with real scoring-trigger logic.
     *
     * AFTER_COMMIT: ensures the session row is committed and readable by this handler.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionFinalized(SessionFinalizedEvent event) {
        log.info("[Module 7 stub] SessionFinalizedEvent received for session {} " +
                 "(contestId={}, candidateId={}) — Module 8 will trigger final scoring here.",
                event.sessionId(), event.contestId(), event.candidateId());
    }
}
```

---

## 11. Cross-Module Changes Required in This Module

### 11.1 `RedisConfig` — switch value serializer to JSON

Locate `RedisConfig.java` (in `com.codepulse_backend.common.config` or `com.codepulse_backend.config`). The `redisTemplate` bean's value serializer must be `GenericJackson2JsonRedisSerializer` so that `QueuedSubmissionJob` records serialise/deserialise correctly.

```java
@Bean
public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);

    GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
    StringRedisSerializer strSerializer = new StringRedisSerializer();

    template.setKeySerializer(strSerializer);
    template.setHashKeySerializer(strSerializer);
    template.setValueSerializer(jsonSerializer);
    template.setHashValueSerializer(jsonSerializer);
    template.afterPropertiesSet();
    return template;
}
```

**Impact assessment:** If any existing Module 6 code uses `RedisTemplate` to store anything, verify it still works after this change. The `SessionAutoSubmitScheduler` uses `AssessmentSessionRepository` (JPA), not Redis directly, so there should be no conflict. Confirm with a compile + run test.

### 11.2 Verify `SessionFinalizedEvent` fields

The stub listener in Section 10.2 references `event.contestId()` and `event.candidateId()` on `SessionFinalizedEvent`. Check the current `SessionFinalizedEvent` record definition in Module 6. If these fields are missing, add them now (they'll be needed by Module 8's real listener anyway). The fields are: `sessionId`, `contestId`, `candidateId`.

### 11.3 `Question` entity — verify `timeLimitMs` and `memoryLimitKb` fields exist

Module 4 defines `time_limit_ms` and `memory_limit_kb` on the `questions` table. Module 8 will read them and pass them to Module 7 via `ExecutionRequest`. Confirm the field names match the column names exactly before Module 8 is built. Look at `Question.java` now and document the exact field names at the top of `CodeExecutionService.java` as a comment.

---

## 12. Sequence of Implementation

Follow this order strictly. Each step must be provably working before proceeding.

**Step 1 — Infrastructure: Judge0 via Docker Compose**
- Extend `docker-compose.yml` with Judge0 services.
- Create `judge0.conf` at project root.
- Run `docker compose up -d`.
- Verify: `curl http://localhost:2358/about` returns JSON.
- Verify: Submit Hello World Python via `curl`, poll for `status.id == 3`.
- **Do not write any Spring code until these two `curl` commands work.**

**Step 2 — Maven dependency**
- Add `spring-boot-starter-webflux` to `pom.xml`.
- Run `./mvnw compile` — must succeed with no new errors.

**Step 3 — Common enums**
- Create `SubmissionStatus`, `SupportedLanguage`, `TestCaseResultStatus` in `common/enums/`.
- Run `./mvnw compile`.

**Step 4 — `Judge0Properties` + `Judge0WebClientConfig`**
- Create both config classes.
- Run the app and confirm no startup errors (the `WebClient` bean must appear in the context).

**Step 5 — Internal DTOs**
- Create all five DTO records in `execution/dto/`.
- Run `./mvnw compile`.

**Step 6 — `Judge0ClientService`**
- Implement `submitCode()` and `pollResult()`.
- Write a `@SpringBootTest` integration test (requires Judge0 running) that:
  - Submits Python `print("hello world")` (language ID 71, stdin `""`).
  - Polls for result.
  - Asserts `status.id == 3` and `stdout` contains `"hello world"`.
- **Do not proceed until this test passes.**

**Step 7 — `CodeExecutionService`**
- Implement `execute()` and `mapToExecutionResult()`.
- Write unit tests (mock `Judge0ClientService`) for each status branch:
  - `status.id == 3` → `PASSED`
  - `status.id == 4` → `WRONG_ANSWER`
  - `status.id == 5` → `TIME_LIMIT_EXCEEDED`
  - `status.id == 6` → `COMPILATION_ERROR`
  - `status.id == 15` → `MEMORY_LIMIT_EXCEEDED`
  - `status.id == 7` → `RUNTIME_ERROR`
  - `Judge0IntegrationException` thrown → `SYSTEM_ERROR`
- Also test output truncation: assert a 100,000-char stdout is capped at 65,536 + `"... [truncated]"`.

**Step 8 — Redis serialisation**
- Verify/update `RedisConfig` to use `GenericJackson2JsonRedisSerializer`.
- Write a `@SpringBootTest` test that pushes a `QueuedSubmissionJob` (with at least two `TestCasePayload` entries) into Redis and pops it back, asserting all fields survive intact (UUIDs, strings, lists).
- Run `./mvnw test`.

**Step 9 — `SubmissionQueueService`**
- Implement `push()`, `pop()`, `size()`.
- No additional test needed beyond Step 8 (this is just `RedisTemplate` delegation).

**Step 10 — Events**
- Create `SubmissionEvaluatedEvent` + stub listener.
- Create `SessionFinalizedListener` stub.
- Verify `SessionFinalizedEvent` fields are correct (Section 11.2).

**Step 11 — `SubmissionQueueWorker`**
- Implement the `@Scheduled` worker.
- Manual integration test:
  - Push a `QueuedSubmissionJob` directly into Redis (e.g., via a temporary `@PostConstruct` in a test bean or a one-off test).
  - Start the app.
  - Verify the worker picks it up, calls `CodeExecutionService`, and publishes `SubmissionEvaluatedEvent` (visible in stub listener log).

**Step 12 — Load test**
- Push 10 `QueuedSubmissionJob`s (each with 3 test cases) into Redis simultaneously.
- Let the worker drain the queue.
- Assert all 10 jobs are processed, Judge0 responds to all calls, no `SYSTEM_ERROR` results.
- Verify queue size reaches 0.

**Step 13 — Compile and run full test suite**
- `./mvnw compile` — must succeed.
- `./mvnw test` — all unit tests must pass (integration tests require Docker; run separately with a profile).

---

## 13. Flyway Migration

**No new database tables in Module 7.** No migration file is needed.

Next migration will be `V10` — Module 8 (`submissions` + `submission_test_case_results` tables).

---

## 14. Security Considerations

- **Judge0 is not exposed publicly.** Port `2358` is bound to `localhost` only in `docker-compose.yml`. Do not add a `/judge0/**` proxy in Nginx — only the Spring backend network reaches it.
- **No auth for local dev.** `AUTHN_TOKEN=` is empty. For production, set a random secret and pass it via env var to both `judge0.conf` and `application-prod.yml`. Never commit the token.
- **Candidate code is not logged at INFO level or below.** Only submission ID, language name, and result status are logged at INFO. Full source code may be logged at TRACE (disabled by default). This prevents exam content appearing in log files.
- **`privileged: true` in Docker Compose is required** for Judge0's `isolate` sandbox. This is a well-known Judge0 requirement. The `isolate` process uses Linux namespaces and cgroups to sandbox candidate code. Without `privileged`, code runs unsandboxed — do not remove it.
- **Time and memory limits are enforced by Judge0**, not by the Spring application. Module 7's job is to pass the correct limits from the `Question` entity. If a question has `timeLimitMs = 0` or `memoryLimitKb = 0`, fall back to the global defaults in `judge0.conf` rather than passing 0 to Judge0.

---

## 15. Definition of Done

- [ ] `docker compose up` starts Judge0 cleanly; `curl http://localhost:2358/about` returns JSON
- [ ] Python Hello World submitted via `curl` returns `status.id == 3` with correct stdout
- [ ] Integration test: `Judge0ClientService` submits Python print, polls, asserts ACCEPTED
- [ ] Unit tests: `CodeExecutionService` correctly maps all 7 status-ID branches (PASSED, WRONG_ANSWER, TLE, CE, MLE, RE, SYSTEM_ERROR)
- [ ] Output truncation test passes: 100,000-char stdout is capped at 65,536 chars
- [ ] Redis round-trip test: `QueuedSubmissionJob` serialises/deserialises without data loss
- [ ] `SubmissionQueueWorker` processes a manually-pushed job; `SubmissionEvaluatedEvent` is logged by stub
- [ ] `SessionFinalizedEvent` stub listener logs on session finalization
- [ ] 10 concurrent jobs processed without Judge0 crash or queue corruption
- [ ] Source code is not logged at INFO or WARNING in any path — verified by log review
- [ ] `./mvnw compile` — BUILD SUCCESS
- [ ] `./mvnw test` — all unit tests pass

---

## 16. What Module 8 Will Add (Do Not Build Yet)

Module 8 (Submission Service) will:
- Add `V10__create_submission_tables.sql` Flyway migration with `submissions` and `submission_test_case_results` tables.
- Add a real `SubmissionEvaluatedEvent` listener that persists `SubmissionTestCaseResult` rows and computes aggregate `Submission.score` via `ScoringService`.
- Call `CodeExecutionService.execute()` directly for **"Run"** (synchronous, sample test cases only, result returned immediately to the HTTP caller).
- Call `SubmissionQueueService.push()` for **"Submit"** (async, all test cases, HTTP returns `PENDING` immediately).
- Replace `SessionFinalizedListener` stub with real logic: on session finalisation, find the candidate's last `SUBMIT` submission per question and trigger scoring if not already scored.
- Remove `SubmissionEvaluatedStubListener` and `SessionFinalizedListener` stub from Module 7 package.
- Expose `POST /api/submissions/run` and `POST /api/submissions/submit` endpoints.

Module 7 is complete and independently testable when the execution engine works in isolation. Module 8 wires it into the exam flow.

---

## 17. File Summary

| File | Action | Notes |
|---|---|---|
| `docker-compose.yml` | **Modified** | Added Judge0 server, worker, DB, Redis services |
| `judge0.conf` | **New** (project root) | Judge0 configuration |
| `pom.xml` | **Modified** | Added `spring-boot-starter-webflux` |
| `application-local.yml` | **Modified** | Extended `judge0.*` properties |
| `common/enums/SubmissionStatus.java` | **New** | |
| `common/enums/SupportedLanguage.java` | **New** | Language → Judge0 ID enum |
| `common/enums/TestCaseResultStatus.java` | **New** | Per-test-case result enum |
| `config/Judge0Properties.java` | **New** | `@ConfigurationProperties` |
| `config/Judge0WebClientConfig.java` | **New** | `WebClient` bean |
| `config/RedisConfig.java` | **Modified** | Switch to JSON value serialiser |
| `execution/dto/ExecutionRequest.java` | **New** | Input record |
| `execution/dto/ExecutionResult.java` | **New** | Output record |
| `execution/dto/Judge0SubmissionRequest.java` | **New** | Internal — Judge0 API shape |
| `execution/dto/Judge0StatusResponse.java` | **New** | Internal — Judge0 API shape |
| `execution/dto/QueuedSubmissionJob.java` | **New** | Redis queue payload |
| `execution/Judge0ClientService.java` | **New** | Raw HTTP to Judge0 |
| `execution/CodeExecutionService.java` | **New** | Orchestration + status mapping |
| `execution/SubmissionQueueService.java` | **New** | Redis push/pop |
| `execution/SubmissionQueueWorker.java` | **New** | `@Scheduled` queue consumer |
| `execution/event/SubmissionEvaluatedEvent.java` | **New** | Module 8 consumes this |
| `execution/event/SubmissionEvaluatedStubListener.java` | **New** | Temporary stub — removed by Module 8 |
| `execution/event/SessionFinalizedListener.java` | **New** | Temporary stub — replaced by Module 8 |

