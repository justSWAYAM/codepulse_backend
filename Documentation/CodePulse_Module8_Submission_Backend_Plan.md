# CodePulse Enterprise — Module 8 Backend Build Plan
### Submission Service

**Purpose:** Persist what a candidate actually did — every code **Run** and **Submit**, with per-test-case results — and turn Module 7's raw execution outcomes into verdicts and scores. This is the module that exposes `POST /api/submissions/run` and `POST /api/submissions/submit`; Module 7 is the engine it calls. Module 8 owns **all** persistence, scoring, object-level authorization and result visibility rules for submissions. Module 7 stays database-free.

**Depends on:** Module 0 (Foundation) + Module 1 (Auth) + Module 3 (`allowedLanguages`) + Module 4 (`Question` limits and points) + Module 5 (`TestCase`, sample/hidden flag, weight) + Module 6 (`AssessmentSession`, `SessionFinalizedEvent`) + Module 7 (`CodeExecutionService`, `SubmissionQueueService`, `SubmissionEvaluatedEvent`). Judge0 and Redis must be running.

> **Blocker check before any Module 8 code:** complete Step 0 (Module 7 patch for `expected_output`, Section 3). Without it, Module 7 cannot produce `WRONG_ANSWER`, and every program that merely runs without crashing is reported as `PASSED`.

---

## 1. What This Module Inherits (Do Not Rebuild)

| From | Component | How it's used here |
|---|---|---|
| Module 0 | `BaseEntity` | `Submission` and `SubmissionTestCaseResult` extend it (UUID id, audit columns) |
| Module 0 | `ApiResponse<T>`, `PagedResponse<T>` | All endpoints; history and evaluator listings use `PagedResponse` |
| Module 0 | `GlobalExceptionHandler` + `AppException` hierarchy | `ResourceNotFoundException`, `InvalidStateException`, `AccessDeniedException` reused. New error codes in Section 11 |
| Module 0 | `RateLimitingFilter` | Extended to cover `/api/submissions/run` **and** `/api/submissions/submit` (Section 12.3) |
| Module 0 | `CorrelationIdFilter` + MDC | Trace ID flows HTTP → persistence → queue → worker logs. Log `submissionId` in MDC inside the worker listener |
| Module 0 | `AuditLog` / `AuditService` | Log `SUBMISSION_REJUDGED` (who, which submission). Do not audit every Run/Submit, since the `submissions` table is already the record |
| Module 1 | `SecurityConfig`, `@PreAuthorize` | Add `/api/submissions/**` matchers (authenticated); role checks via `@PreAuthorize` on each method |
| Module 3 | `Contest.allowedLanguages` (`List<String>`) | Language check on every Run/Submit (strings equal `SupportedLanguage.name()`) |
| Module 4 | `Question` (`points`, `timeLimitMs`, `memoryLimitKb`, `contestId`) | Limits are read here and passed to Module 7 via `ExecutionRequest`. **Verify the exact field names** (Step 1) |
| Module 5 | `TestCaseRepository` | Loaded **server-side only** to build jobs and score. Never reachable from a controller return path |
| Module 6 | `AssessmentSession`, `SessionFinalizedEvent(sessionId, contestId, candidateId)` | Active-session lookup and deadline check; replaces the Module 7 stub listener |
| Module 7 | `CodeExecutionService.execute(ExecutionRequest)` | "Run" path. Never throws; returns `SYSTEM_ERROR` on failure |
| Module 7 | `SubmissionQueueService.push(QueuedSubmissionJob)` | "Submit" path |
| Module 7 | `SubmissionEvaluatedEvent(submissionId, outcomes)` | Consumed by the real listener (Section 9.6). **Not transactional**, so the listener opens its own transaction |
| Module 7 | `SubmissionStatus`, `TestCaseResultStatus`, `SupportedLanguage` | Already exist in `common/enums`. Only `SubmissionType` is new |
| Flyway | Next migration is `V10` | `V10__create_submission_tables.sql` (Section 6) |

---

## 2. Decisions to Make Before Writing Any Code

### 2.1 Who decides Accepted vs Wrong Answer — Judge0, via `expected_output`
Judge0 returns status 3 (Accepted) for any run that exits cleanly **unless** it is given `expected_output` to compare against. Module 7's `ExecutionRequest`, `Judge0SubmissionRequest` and `TestCasePayload` currently carry no expected output. **Fix it in Module 7 (Step 0)** rather than re-comparing output in Module 8:
- Judge0 compares full-size output in the sandbox, while Module 7 truncates `stdout` to 64 KB, so comparing in Java would produce false `WRONG_ANSWER` on large outputs.
- Module 7's status mapping (4 → `WRONG_ANSWER`) already assumes this.
- Trade-off: expected outputs travel through Redis inside `QueuedSubmissionJob`. That is acceptable only with Redis closed to the network (Section 13).

### 2.2 Run and Submit follow Module 7 §2.2
- **Run** is synchronous, against **sample** test cases only, sequentially, and the HTTP request blocks until done. Persisted once, *after* execution, as a single complete row (see 2.3).
- **Submit** is asynchronous, against **all** test cases. The HTTP request returns `202` with status `PENDING` after the row is committed and the job is pushed. Candidates poll `GET /api/submissions/{id}` (WebSocket push arrives in Module 11 and can hook the same state change).

### 2.3 Never hold a database transaction open during a Judge0 call
Module 7 §9.2 suggests `Judge0ClientService` is called from "a regular `@Transactional` method". Do **not** do that. A Run can block for several seconds and a transaction holds a pooled DB connection the whole time. At 30 candidates pressing Run, the connection pool is exhausted before Judge0 is.

Pattern used everywhere in this module:
1. Validate and authorize (read-only).
2. **Short transaction** to write state, committed.
3. Call Judge0 or the queue **outside** any transaction.
4. **Second short transaction** to write results.

To make `@Transactional` actually apply (self-invocation bypasses the proxy), all transactional units live in a **separate bean**: `SubmissionPersistenceService`. `SubmissionService` itself is **not** `@Transactional`.

### 2.4 Run rows have no `PENDING` state
A Run is written once, complete, after execution. If the request dies midway, nothing is persisted, so there are no orphan rows to clean up. Only Submit rows go through `PENDING → final`.

### 2.5 Which Submit counts: **best score per question, ties → earliest**
The roadmap says to decide and document this rule. Rule for v1:

> For each question in a session, the counted submission is the `SUBMIT` row with the **highest score**; if tied, the **earliest `submitted_at`**. Rows in `PENDING` or `SYSTEM_ERROR` are never counted.

Why: a candidate who scored 80 and then breaks their solution in the last minute is not punished, and "earliest wins ties" matches Module 9's tie-break ("faster submission wins"). The rule lives in **one query** (Section 9.3); switching to "last submission counts" is a one-line `ORDER BY` change. The counted flag is **computed, never stored**, so it cannot go stale.
> Note: Module 7 §16 loosely said "last SUBMIT submission per question". This plan supersedes that wording. Confirm the rule you actually want before building.

### 2.6 Scoring formula — weighted partial credit
```
score = question.points × (Σ weight of PASSED test cases) ÷ (Σ weight of ALL test cases evaluated)
```
Rounded `HALF_UP` to 2 decimals. Edge cases: total weight 0 → treat every weight as 1; `COMPILATION_ERROR` → score 0; any `SYSTEM_ERROR` test case → the submission is **not scored** (Section 2.8). Weights and sample flags are **snapshotted** into each result row at evaluation time, so later edits to test cases can't silently change an old score (use rejudge to apply edits deliberately).

### 2.7 Overall submission status from per-test-case results
Deterministic rule, implemented once in `ScoringService.resolveStatus()`:
1. Any `SYSTEM_ERROR` → `SYSTEM_ERROR`.
2. Any `COMPILATION_ERROR` → `COMPILATION_ERROR`.
3. All `PASSED` → `ACCEPTED`.
4. Otherwise the status of the **first non-passed test case by test-case order** (TLE, MLE, RE, WA), the same convention as most judges ("wrong answer on test 3").

`SubmissionStatus.RUNNING` stays **unused** in v1: Module 7's worker doesn't write to the DB, so `PENDING` covers both "queued" and "executing". Don't invent a half-working RUNNING state.

### 2.8 A Judge0 failure must never cost the candidate marks
If any test case returns `SYSTEM_ERROR`, the submission is saved with status `SYSTEM_ERROR`, `score = NULL`, and is excluded from "counted". An Admin/Evaluator can **rejudge** it (Section 8, `POST /api/submissions/{id}/rejudge`). This is a small addition beyond the roadmap, justified by exam integrity: without it, a Judge0 restart mid-exam silently zeroes candidates.

### 2.9 What candidates may see — the hidden-test-case boundary
Same security principle as Modules 4 and 5: **two separate DTO classes**, so a shared serializer can never leak.

| Data | Candidate view | Evaluator/Admin view |
|---|---|---|
| Overall status, score, passed/total counts | Yes (while session `IN_PROGRESS`, see 2.10) | Yes |
| Compile output (their own code's compiler messages) | Yes | Yes |
| **Sample** test case: input, actual output, status, time, memory | Yes | Yes |
| Sample `expectedOutput` | **No** (Module 5 rule) | Yes |
| **Hidden** test case: input, expected, actual output, stderr | **Never** | Yes |
| Hidden test case per-case status | **No**, aggregate `passed/total` only (prevents probing hidden cases one by one) | Yes |
| Source code | Own only | Yes |

### 2.10 Score visibility vs Module 9's "no result data before publish"
Module 8's Definition of Done says candidates see a score after Submit. Module 9 says candidates see nothing before publish. Reconciled as follows:
- **While the session is `IN_PROGRESS`:** candidate sees verdict + score on their own submissions (exam feedback loop).
- **After the session ends:** candidate still sees their source code and submission times, but `status`, `score` and results are **nulled** until Module 9 publishes results.

Implemented in **one place**: `SubmissionMapper.toCandidateView(submission, boolean resultsVisible)`, with `resultsVisible = session.status == IN_PROGRESS` in v1. Module 9 will extend the condition with `|| resultsPublished`. No other code needs to know.

### 2.11 Deadline and abuse limits
- Reject Run/Submit unless the session is `IN_PROGRESS` **and** `now ≤ ends_at + graceSeconds` (default 5 s). The grace covers network latency and `SessionExpiryScheduler`'s scan interval. Server clock only, never a client timestamp.
- Reject a new Submit while a `PENDING` Submit exists for the same (session, question). Stops double-clicks and queue flooding.
- Caps per (session, question): 30 Submits, 100 Runs (configurable).
- Source code ≤ 64 KB (Bean Validation on the DTO).
- Concurrent synchronous Runs capped by a semaphore (Section 9.7) so a burst can't pin every servlet thread on Judge0.

### 2.12 The session is derived on the server, never sent by the client
Request bodies carry `questionId`, `language`, `sourceCode` only. The candidate comes from the JWT; the session is looked up as "this candidate's active session for this question's contest". A client can never name someone else's session.

### 2.13 Idempotent persistence
Events can be delivered twice (recovery re-push, slow worker). Applying an evaluation must be safe to repeat: lock the row, **skip unless status is `PENDING`**, and back it with `UNIQUE (submission_id, test_case_id)` on results.

---

## 3. Step 0 — Patch Module 7 for `expected_output` (prerequisite)

Small, mechanical changes in Module 7's files:

| File | Change |
|---|---|
| `ExecutionRequest` | add `String expectedOutput` |
| `Judge0SubmissionRequest` | add `@JsonProperty("expected_output") String expectedOutput` |
| `CodeExecutionService` | copy `expectedOutput` into the Judge0 payload |
| `QueuedSubmissionJob.TestCasePayload` | add `String expectedOutput` |
| `SubmissionQueueWorker` | copy it into each `ExecutionRequest` |
| Module 7 unit tests | update constructors; add a case asserting status 4 → `WRONG_ANSWER` |

**Verify with `curl` before touching Java:**
```bash
# Wrong answer: expected "bye", program prints "hello" -> status.id should be 4
curl -X POST "http://localhost:2358/submissions?wait=true" \
  -H "Content-Type: application/json" \
  -d '{"source_code":"print(\"hello\")","language_id":71,"stdin":"","expected_output":"bye"}'

# Correct, with trailing newline difference -> status.id should be 3
curl -X POST "http://localhost:2358/submissions?wait=true" \
  -H "Content-Type: application/json" \
  -d '{"source_code":"print(\"hello\")","language_id":71,"stdin":"","expected_output":"hello"}'
```
Confirm on **your** instance how trailing whitespace/newlines are treated, since this defines your platform's comparison rule; document the result in a comment on `CodeExecutionService`.

---

## 4. Other Gaps in Module 7 Found While Planning (Fix as Noted)

| Gap | Impact | Where to fix |
|---|---|---|
| Worker pops a job, then the app crashes or Redis restarts → job lost | Submission stuck `PENDING` forever | Module 8 `SubmissionRecoveryScheduler` (Section 9.8) |
| `@Scheduled` uses a **single-thread pool** by default; one worker tick (up to 5 jobs × N tests) can run for a minute | `SessionExpiryScheduler`, `ContestSchedulerService` and the new recovery scheduler are starved, so sessions expire late | Config: `spring.task.scheduling.pool.size: 4` (Section 12.1) |
| Worker runs **every** test case even after a `COMPILATION_ERROR` (N identical failures) | Wasted Judge0 calls at peak | Optional Module 7 tweak: on the first compile error, fill the remaining outcomes without calling Judge0. Module 8's Run path already does this (Section 9.4) |
| Queue throughput is low (sequential, one worker thread) | Stale threshold must exceed worst-case queue wait | Section 9.8 uses a 10-minute default; revisit after the load test |

---

## 5. New Common Enum

**File:** `common/enums/SubmissionType.java`

```java
package com.codepulse_backend.common.enums;

/** RUN = sample test cases only, synchronous, never counted. SUBMIT = all test cases, async, counted. */
public enum SubmissionType { RUN, SUBMIT }
```

New error codes go into the existing error-code enum (Section 11).

---

## 6. Database Migration

**File:** `src/main/resources/db/migration/V10__create_submission_tables.sql`
> Match column types to your earlier migrations (`weight`, `points` types and `users`/`questions` ID types). The audit columns below follow `BaseEntity`; adjust to the exact names you used in V1.

```sql
CREATE TABLE submissions (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    session_id      UUID        NOT NULL REFERENCES assessment_sessions(id),
    question_id     UUID        NOT NULL REFERENCES questions(id),
    candidate_id    UUID        NOT NULL REFERENCES users(id),

    language        VARCHAR(30) NOT NULL,              -- SupportedLanguage name, e.g. PYTHON
    source_code     TEXT        NOT NULL,
    submission_type VARCHAR(10) NOT NULL CHECK (submission_type IN ('RUN','SUBMIT')),
    status          VARCHAR(30) NOT NULL,              -- SubmissionStatus
    score           NUMERIC(8,2),                      -- NULL for RUN, PENDING, SYSTEM_ERROR
    passed_count    INT,
    total_count     INT,
    compile_output  TEXT,

    submitted_at    TIMESTAMPTZ NOT NULL,
    evaluated_at    TIMESTAMPTZ,

    queued_at       TIMESTAMPTZ,                       -- last time a job was pushed (SUBMIT only)
    queue_attempts  INT         NOT NULL DEFAULT 0,    -- recovery bookkeeping

    version         BIGINT      NOT NULL DEFAULT 0     -- optimistic lock
);

CREATE INDEX idx_submissions_session_question ON submissions (session_id, question_id, submission_type, submitted_at DESC);
CREATE INDEX idx_submissions_candidate_question ON submissions (candidate_id, question_id, submitted_at DESC);
CREATE INDEX idx_submissions_question ON submissions (question_id);
CREATE INDEX idx_submissions_pending ON submissions (queued_at) WHERE status = 'PENDING';

CREATE TABLE submission_test_case_results (
    id                 UUID PRIMARY KEY,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    created_by         UUID,
    updated_by         UUID,

    submission_id      UUID        NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
    test_case_id       UUID        NOT NULL REFERENCES test_cases(id),   -- RESTRICT: see note below
    status             VARCHAR(30) NOT NULL,           -- TestCaseResultStatus
    actual_output      TEXT,                           -- truncated to 10,000 chars on save
    stderr             TEXT,                           -- truncated to 4,000 chars on save
    execution_time_ms  NUMERIC(10,2),
    memory_used_kb     INT,

    weight             INT         NOT NULL,           -- snapshot of test_cases.weight at evaluation
    is_sample          BOOLEAN     NOT NULL,           -- snapshot of test_cases.is_sample

    CONSTRAINT uq_result_submission_testcase UNIQUE (submission_id, test_case_id)
);

CREATE INDEX idx_results_submission ON submission_test_case_results (submission_id);
```

**Note on the `test_case_id` FK:** the default `RESTRICT` means Module 5's `DELETE /api/test-cases/{id}` will fail once any submission references that test case. Make Module 5's delete handler translate that into a clean `409` (new code `TEST_CASE_IN_USE`) instead of a 500. To correct a wrong test case mid-contest, **edit it and rejudge**, don't delete it.

---

## 7. Configuration Properties

**File:** `config/SubmissionProperties.java` (`@ConfigurationProperties(prefix = "submission")`, same style as `Judge0Properties`)

```yaml
submission:
  grace-seconds: 5
  max-submit-per-question: 30
  max-run-per-question: 100
  max-concurrent-runs: 10
  run-permit-wait-ms: 2000
  stored-output-max-chars: 10000
  stored-stderr-max-chars: 4000
  recovery:
    interval-ms: 60000
    stale-after-minutes: 10
    max-queue-attempts: 3        # initial push counts as attempt 1
  rate-limit:
    run-per-minute: 20
    submit-per-minute: 10
```

---

## 8. API Surface

All responses use `ApiResponse<T>`; lists use `PagedResponse<T>`.

| Method | Endpoint | Description | Access | Success |
|---|---|---|---|---|
| POST | `/api/submissions/run` | Run against sample test cases, returns results immediately | Candidate | `200` |
| POST | `/api/submissions/submit` | Submit for scoring against all test cases | Candidate | `202`, status `PENDING` |
| GET | `/api/submissions/{id}` | Detail. Candidate: own only (candidate view). Evaluator/Admin: full view | Owner / Evaluator / Admin | `200` |
| GET | `/api/questions/{questionId}/submissions/me` | Own history for a question, paged, optional `?type=RUN\|SUBMIT` | Candidate | `200` |
| GET | `/api/contests/{contestId}/submissions` | All submissions in a contest, paged, filters: `candidateId`, `questionId`, `type`, `status` | Evaluator / Admin | `200` |
| POST | `/api/submissions/{id}/rejudge` | Re-queue a SUBMIT (used after `SYSTEM_ERROR` or a test-case fix) | Evaluator / Admin | `202` |

Rules:
- A candidate requesting someone else's submission gets **404**, not 403, so IDs can't be probed for existence.
- The contest listing returns **summaries only** (no `sourceCode`, no per-test results). Detail endpoint returns those.
- **Evaluator scope:** the roadmap's Module 3 schema has no evaluator↔contest assignment (only `contest_candidates`). Check your code. If there's no such link, v1 = any `EVALUATOR`/`ADMIN` can read any contest's submissions. Note it as a known limitation rather than faking a scope check.

---

## 9. Backend Components

### 9.1 Package Structure

```
com.codepulse_backend/
├── common/enums/
│   └── SubmissionType.java                     ← NEW
├── config/
│   └── SubmissionProperties.java               ← NEW
└── submission/
    ├── entity/
    │   ├── Submission.java
    │   └── SubmissionTestCaseResult.java
    ├── repository/
    │   ├── SubmissionRepository.java
    │   └── SubmissionTestCaseResultRepository.java
    ├── dto/
    │   ├── RunCodeRequest.java
    │   ├── SubmitCodeRequest.java
    │   ├── SubmissionSummaryResponse.java
    │   ├── SubmissionCandidateView.java        ← candidate-facing detail (no hidden data, no expected output)
    │   ├── SubmissionEvaluatorView.java        ← evaluator-facing detail
    │   └── SubmissionListFilter.java
    ├── service/
    │   ├── SubmissionService.java              ← orchestration + authz, NOT @Transactional
    │   ├── SubmissionPersistenceService.java   ← all @Transactional units
    │   ├── SubmissionAccessGuard.java          ← object-level authorization
    │   ├── ScoringService.java                 ← pure: score + status, no DB
    │   ├── SubmissionMapper.java               ← the ONLY place candidate/evaluator views are built
    │   └── RunConcurrencyLimiter.java
    ├── event/
    │   ├── SubmissionEvaluatedListener.java    ← REPLACES Module 7 stub
    │   ├── SessionFinalizedHandler.java        ← REPLACES Module 7 stub
    │   └── SessionScoringCompletedEvent.java   ← NEW, Module 9 consumes
    ├── scheduler/
    │   └── SubmissionRecoveryScheduler.java
    └── controller/
        └── SubmissionController.java
```
The question-scoped history route (`/api/questions/{id}/submissions/me`) and the contest-scoped listing route can live on `SubmissionController` for simplicity.

### 9.2 Entities

- `Submission extends BaseEntity`: fields mirror the table; `@Enumerated(EnumType.STRING)` on `submissionType` and `status`; `@Version private Long version`; `score` as `BigDecimal`; LAZY `@ManyToOne` (or plain UUID columns) for `session`, `question`, `candidate`. **Plain UUID columns are recommended**, since you never need the object graph and it avoids accidental lazy-loading in mappers.
- `SubmissionTestCaseResult extends BaseEntity`: `submissionId` (LAZY `@ManyToOne` to `Submission`), `testCaseId` (UUID), plus status, truncated outputs, time, memory, `weight`, `isSample`.

### 9.3 Repositories

`SubmissionRepository` needs:
- `@Lock(PESSIMISTIC_WRITE) @Query("select s from Submission s where s.id = :id") Optional<Submission> findByIdForUpdate(UUID id)`, used by `applyEvaluation` and `rejudge`.
- `boolean existsBySessionIdAndQuestionIdAndSubmissionTypeAndStatus(...)`, for the in-flight PENDING guard.
- `long countBySessionIdAndQuestionIdAndSubmissionType(...)`, for caps.
- `Page<Submission> findByCandidateIdAndQuestionId(...)` with optional type, sorted `submittedAt DESC`.
- `Page<Submission>` contest listing through a `JpaSpecificationExecutor` or a JPQL query with optional filters, joining `questions` to filter by `contestId`.
- `List<Submission> findStalePending(Instant cutoff, int maxAttempts)`, for recovery.
- `long countBySessionIdAndSubmissionTypeAndStatus(sessionId, SUBMIT, PENDING)`, for session-completion checks.
- **The counted query** (single source of truth for rule 2.5):

```sql
-- native query; Postgres-specific (DISTINCT ON)
SELECT DISTINCT ON (question_id) *
FROM submissions
WHERE session_id = :sessionId
  AND submission_type = 'SUBMIT'
  AND status NOT IN ('PENDING', 'SYSTEM_ERROR')
ORDER BY question_id, score DESC, submitted_at ASC;
```
To flag "counted" on the history list, call this once per session and compare IDs, not one query per row.

`SubmissionTestCaseResultRepository`: `findBySubmissionId(...)`, `deleteBySubmissionId(...)` (rejudge).

### 9.4 `SubmissionService` — orchestration (no `@Transactional`)

**Shared pre-checks** (private method `validateAndResolveContext(candidateId, questionId, language, type)`):
1. Load `Question` → `ResourceNotFoundException`.
2. Find candidate's **active session** for `question.contestId` via Module 6's service/repository → else `InvalidStateException(NO_ACTIVE_SESSION)`.
3. `SubmissionAccessGuard.assertWithinDeadline(session)` → `SESSION_DEADLINE_PASSED`.
4. Language: `SupportedLanguage.fromName(language)` and must be in `contest.allowedLanguages` → `LANGUAGE_NOT_ALLOWED`.
5. Per-(session, question) count caps → `SUBMISSION_LIMIT_REACHED`.

**`run(candidateId, RunCodeRequest)`**
1. Pre-checks above.
2. Load **sample** test cases ordered → none? `InvalidStateException(NO_SAMPLE_TEST_CASES)`.
3. `RunConcurrencyLimiter.tryAcquire()` → else `EXECUTION_BUSY` (503). Release in `finally`.
4. For each sample test case, sequentially: build `ExecutionRequest` (limits from `Question`, `expectedOutput` from the test case, a fresh `correlationId` UUID for logging) → `CodeExecutionService.execute()`.
   **Short-circuit:** if a result is `COMPILATION_ERROR`, stop calling Judge0 and mark the remaining cases `COMPILATION_ERROR` with no output.
5. `ScoringService.resolveStatus(...)` and pass/total counts. **No score** for Run.
6. `SubmissionPersistenceService.saveCompletedRun(...)`: one short transaction inserting the `Submission` (type `RUN`, status final, `evaluatedAt = now`) + result rows.
7. Return `SubmissionCandidateView` with `resultsVisible = true` (the session is necessarily in progress).

**`submit(candidateId, SubmitCodeRequest)`**
1. Pre-checks above.
2. In-flight guard: existing `PENDING` SUBMIT for (session, question) → `SUBMISSION_IN_PROGRESS` (422).
3. Load **all** test cases ordered → none? `InvalidStateException(QUESTION_HAS_NO_TEST_CASES)`.
4. `SubmissionPersistenceService.createPendingSubmit(...)` — short tx, **commit**: status `PENDING`, `totalCount = testCases.size()`, `queuedAt = now`, `queueAttempts = 1`.
5. Build `QueuedSubmissionJob` (per-test-case `timeLimitMs`/`memoryLimitKb` from `Question`, `input`, `expectedOutput`) and `SubmissionQueueService.push(job)`, **after** the commit so the worker can always read the row.
6. If `push` throws (Redis down): `SubmissionPersistenceService.markSystemError(id)` in a new tx, then throw `SUBMISSION_QUEUE_UNAVAILABLE` (503). The candidate retries; the failed row is never counted.
7. Return `202` with `SubmissionSummaryResponse` (status `PENDING`).

**`rejudge(id, actorId)`**
Only for `SUBMIT` rows whose status isn't `PENDING` → else `SUBMISSION_NOT_REJUDGEABLE`. In one tx (`findByIdForUpdate`): delete result rows, set `PENDING`, `score = null`, reset `queueAttempts = 1`, `queuedAt = now`. Commit, then rebuild the job from **current** DB state and push. Write an `AuditLog` entry.

**Queries** (`getDetail`, `getMyHistory`, `listForContest`) call `SubmissionAccessGuard`, then `SubmissionMapper`. The mapper receives `resultsVisible` (Section 2.10); the service resolves it from the session status.

### 9.5 `ScoringService` — pure functions, no repositories

```java
public BigDecimal computeScore(BigDecimal questionPoints, List<ScoredResult> results) { ... }
public SubmissionStatus resolveStatus(List<ScoredResult> resultsInTestCaseOrder) { ... }

public record ScoredResult(TestCaseResultStatus status, int weight) {}
```
Implements rules 2.6 and 2.7 exactly. It takes plain values so it is trivially unit-testable (Step 4). Changing to all-or-nothing scoring later means editing this class only.

### 9.6 `SubmissionEvaluatedListener` (replaces `SubmissionEvaluatedStubListener`)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SubmissionEvaluatedListener {

    private final SubmissionPersistenceService persistence;
    private final ApplicationEventPublisher publisher;

    @EventListener
    public void onSubmissionEvaluated(SubmissionEvaluatedEvent event) {
        MDC.put("submissionId", event.submissionId().toString());
        try {
            Optional<SessionScoringCompletedEvent> done = persistence.applyEvaluation(event);
            done.ifPresent(publisher::publishEvent);   // published AFTER the tx has committed
        } catch (Exception e) {
            // Never propagate into the worker. The row stays PENDING and recovery (9.8) takes over.
            log.error("Failed to apply evaluation for submission {}", event.submissionId(), e);
        } finally {
            MDC.remove("submissionId");
        }
    }
}
```

`SubmissionPersistenceService.applyEvaluation(event)` (`@Transactional`):
1. `findByIdForUpdate`; **if status ≠ `PENDING` → log and return empty** (idempotency, 2.13).
2. Load the test cases by IDs from `event.outcomes()` to get `weight`, `isSample`.
3. Build result rows: truncate `actual_output`/`stderr` per config, snapshot `weight`/`isSample`; save.
4. `ScoringService.resolveStatus(...)`. If `SYSTEM_ERROR` → status `SYSTEM_ERROR`, `score = null`. Otherwise `computeScore(question.points, ...)`.
5. Set `passedCount`, `totalCount`, `compileOutput` (from the first outcome that has one), `evaluatedAt`, status, score.
6. If the session is no longer `IN_PROGRESS` **and** no `PENDING` SUBMIT rows remain for that session → return `SessionScoringCompletedEvent`; else empty.

### 9.7 `RunConcurrencyLimiter`
A `Semaphore(maxConcurrentRuns)` bean with `tryAcquire(runPermitWaitMs)`. It protects servlet threads and Judge0 from a burst of synchronous Runs. On timeout, throw an `AppException` mapped to `503 EXECUTION_BUSY`.

### 9.8 `SubmissionRecoveryScheduler`

`@Scheduled(fixedDelayString = "${submission.recovery.interval-ms}")`:
1. `findStalePending(now - staleAfterMinutes, maxQueueAttempts)` → for each: rebuild the job from the DB (source, language, **current** test cases), `push`, then `queueAttempts++`, `queuedAt = now` (short tx).
2. Rows still `PENDING` with `queueAttempts ≥ maxQueueAttempts` and stale → mark `SYSTEM_ERROR` (Admin/Evaluator can rejudge).
3. Log at `WARN` with counts. A non-zero count means something was lost and should be investigated.

Duplicate processing caused by a merely slow queue is harmless because of idempotency (2.13). The only cost is wasted Judge0 work, which is why the stale threshold defaults to 10 minutes.

### 9.9 `SessionFinalizedHandler` (replaces Module 7's `SessionFinalizedListener` stub)

`@TransactionalEventListener(phase = AFTER_COMMIT)` on `SessionFinalizedEvent`: if the session has **zero** `PENDING` SUBMIT rows, publish `SessionScoringCompletedEvent` immediately; otherwise do nothing, because `applyEvaluation` (9.6, step 6) publishes it when the last pending row finishes. Submissions made before the deadline keep being scored after the session locks; this is intentional.

```java
/** Published once every SUBMIT of a finalized session has a final status. Module 9 aggregates results on this. */
public record SessionScoringCompletedEvent(UUID sessionId, UUID contestId, UUID candidateId) {}
```
The handler must open its own transaction (it runs after the publisher's commit).

### 9.10 `SubmissionAccessGuard`
Central object-level checks so controllers and services never hand-roll them:
- `assertCandidateOwns(submission, candidateId)` → throws `ResourceNotFoundException` (not 403).
- `assertWithinDeadline(session)` — `IN_PROGRESS` and `now ≤ endsAt + grace`.
- `resolveViewerRole(authentication)` → decides candidate vs evaluator view.

### 9.11 Controller
`SubmissionController` — thin. `@PreAuthorize("hasRole('CANDIDATE')")` on run/submit/history; `hasAnyRole('EVALUATOR','ADMIN')` on contest listing and rejudge; the detail endpoint is authenticated and delegates to the guard. The candidate ID always comes from the authenticated principal, never from the body. `POST /submit` and `POST /rejudge` return `ResponseEntity.accepted()`.

---

## 10. DTO Notes

```java
public record RunCodeRequest(
    @NotNull UUID questionId,
    @NotBlank String language,                 // validated against SupportedLanguage + contest list in the service
    @NotBlank @Size(max = 65536) String sourceCode
) {}
// SubmitCodeRequest: identical fields (separate type so the two endpoints can diverge later)
```

- `SubmissionSummaryResponse`: `id, questionId, type, language, status, score, passedCount, totalCount, submittedAt, counted`.
- `SubmissionCandidateView`: summary fields + `sourceCode`, `compileOutput`, `List<SampleResultView> sampleResults` (testCaseId, input, actualOutput, stderr, status, timeMs, memoryKb; **no `expectedOutput`**), and `hiddenSummary { passed, total }`. When `resultsVisible == false`, status/score/results are `null`.
- `SubmissionEvaluatorView`: everything, including per-test-case `expectedOutput` and all hidden results.
- **No shared base class or shared serializer between the two views.**

---

## 11. New Error Codes

| Code | HTTP | Thrown when |
|---|---|---|
| `SUBMISSION_NOT_FOUND` | 404 | Missing, or not owned by the calling candidate |
| `NO_ACTIVE_SESSION` | 409 | No `IN_PROGRESS` session for the question's contest |
| `SESSION_DEADLINE_PASSED` | 409 | Past `ends_at + grace` |
| `LANGUAGE_NOT_ALLOWED` | 400 | Unknown language, or not in `contest.allowedLanguages` |
| `NO_SAMPLE_TEST_CASES` | 409 | Run on a question with no sample tests |
| `QUESTION_HAS_NO_TEST_CASES` | 409 | Submit on a question with no tests |
| `SUBMISSION_IN_PROGRESS` | 409 | A `PENDING` Submit already exists for this (session, question) |
| `SUBMISSION_LIMIT_REACHED` | 409 | Per-question Run/Submit cap hit |
| `SUBMISSION_QUEUE_UNAVAILABLE` | 503 | Redis push failed |
| `EXECUTION_BUSY` | 503 | Run concurrency limiter timed out |
| `SUBMISSION_NOT_REJUDGEABLE` | 409 | Rejudge on a RUN, or on a `PENDING` row |
| `TEST_CASE_IN_USE` | 409 | Module 5 delete blocked by the results FK |

---

## 12. Cross-Module Changes Required in This Module

### 12.1 Scheduler thread pool
`application-local.yml`: `spring.task.scheduling.pool.size: 4`. Without it, Module 7's worker can starve Module 3/6 schedulers and this module's recovery job.

### 12.2 Remove Module 7 stubs
Delete `SubmissionEvaluatedStubListener` and `SessionFinalizedListener` from `execution/event/` in the same commit that adds the real listeners. Two `SessionFinalizedEvent` listeners are harmless but confusing in logs.

### 12.3 `RateLimitingFilter` matchers
Add `/api/submissions/submit` alongside `/api/submissions/run`; read limits from `submission.rate-limit.*`. Key the bucket by user ID (not IP), because a whole lab can share one NAT address.

### 12.4 Module 5 — `TestCaseRepository`
Add ordered finders if missing: `findByQuestionIdOrderByOrderIndexAsc` and `findByQuestionIdAndIsSampleTrueOrderByOrderIndexAsc`. Use them **only** inside `SubmissionService` / `SubmissionPersistenceService`.

### 12.5 Module 6 — active-session lookup
Expose (or confirm) one method returning the candidate's `IN_PROGRESS` session for a contest. Don't re-implement the query in Module 8.

### 12.6 Module 4 — verify field names
Confirm `Question.points`, `timeLimitMs`, `memoryLimitKb` and `contestId` exactly as named in the entity. Write the confirmed names in a comment at the top of `SubmissionService`.

---

## 13. Security Considerations

- **Object-level authorization everywhere**, not just roles: session derived server-side (2.12), ownership → 404 (9.10), deadline enforced on the server clock.
- **Hidden-test-case isolation:** two view DTOs, one mapper, and **explicit tests that assert the JSON of every candidate-facing response lacks `expectedOutput` and any hidden-case field** (assert absence in the serialized JSON, not just in the UI). This mirrors the Module 4/5 definition of done.
- **Redis now holds sensitive data:** `QueuedSubmissionJob` contains source code, hidden inputs **and expected outputs** (after Step 0). Your current `docker-compose.yml` publishes `6379:6379` with no password. Before any shared-network use, remove the host port mapping (or bind to `127.0.0.1:6379`) and set `requirepass`. Judge0's Redis is already not exposed; treat the app's Redis the same way. Module 14 must keep this.
- **Candidate source code is never logged at INFO or above** (same rule as Module 7). Log `submissionId`, language, status, counts only.
- **Rejudge is privileged and audited** (`AuditLog`).
- **Limits are layered:** request rate (filter), per-question caps (service), Run concurrency (semaphore), payload size (DTO validation).
- Stored outputs are truncated (10 KB / 4 KB) so a program that prints 64 KB per test can't bloat the table.

---

## 14. Sequence of Implementation

Follow strictly. Each step must be provably working before proceeding.

**Step 0 — Module 7 patch**
- Apply Section 3. Verify both `curl` calls (status 4 and status 3). Update Module 7 unit tests; `./mvnw test` green.

**Step 1 — Cross-module verification (no new code)**
- Confirm names/signatures from Section 12.4–12.6, that `SessionFinalizedEvent` has `sessionId, contestId, candidateId`, and how `BaseEntity` generates IDs.
- Add `spring.task.scheduling.pool.size: 4`.

**Step 2 — Schema and entities**
- Add `SubmissionType`, `V10__create_submission_tables.sql`, both entities, `SubmissionProperties`.
- Start the app against a **fresh** database: Flyway runs V10 and Hibernate schema validation (if enabled) passes.

**Step 3 — Repositories**
- Implement Section 9.3. Test the **counted query** with an integration test on **real Postgres** (Testcontainers or the compose DB). `DISTINCT ON` will not work on H2. Cases: best wins; tie → earliest; `PENDING`/`SYSTEM_ERROR` ignored; per-question isolation.

**Step 4 — `ScoringService` + unit tests (pure, fast)**
- All pass → full points. Partial with unequal weights → exact expected decimal. Total weight 0 → equal weights. `COMPILATION_ERROR` → 0. Any `SYSTEM_ERROR` → status `SYSTEM_ERROR`. Status precedence: CE beats RE; first non-passed by order wins; SYSTEM_ERROR beats everything.

**Step 5 — DTOs + `SubmissionMapper` + leak tests**
- Build both views. Test: candidate view JSON has no `expectedOutput`, no hidden-case input/output/stderr, only `hiddenSummary`. Test `resultsVisible=false` nulls status/score/results.

**Step 6 — `SubmissionAccessGuard`**
- Unit tests: other candidate's submission → 404; deadline boundary cases (`ends_at`, `ends_at + 5 s`, `ends_at + 6 s`); session not `IN_PROGRESS` → rejected.

**Step 7 — `SubmissionPersistenceService`**
- `saveCompletedRun`, `createPendingSubmit`, `applyEvaluation`, `markSystemError`. Test `applyEvaluation` **twice** with the same event: second call is a no-op and produces no duplicate result rows.

**Step 8 — Run flow**
- `SubmissionService.run`, `RunConcurrencyLimiter`, `POST /api/submissions/run`.
- With Judge0 running: correct Python → `ACCEPTED`; wrong output → `WRONG_ANSWER`; syntax error → `COMPILATION_ERROR` with only **one** Judge0 call made (short-circuit); infinite loop → TLE; over-language → 400.
- Run row persisted once, complete, and **no DB transaction is open during the Judge0 call** (check Hikari active-connection count during a slow run).

**Step 9 — Submit flow and real listener**
- `SubmissionService.submit`, `SubmissionEvaluatedListener`, `POST /api/submissions/submit`; delete `SubmissionEvaluatedStubListener`.
- End-to-end: submit → `202 PENDING` → worker executes → row becomes `ACCEPTED`/etc. with a correct weighted score. Double-click test: second Submit while the first is `PENDING` → 409.
- Kill Redis before pushing: expect `503` and a `SYSTEM_ERROR` row, not a stuck `PENDING` one.

**Step 10 — Read endpoints**
- `GET /api/submissions/{id}`, `GET /api/questions/{id}/submissions/me` (with `counted` flag from a single counted-query call). MockMvc authz matrix: owner vs other candidate (404) vs evaluator vs admin vs anonymous (401).

**Step 11 — Evaluator listing and rejudge**
- `GET /api/contests/{id}/submissions` with filters and paging; `POST /api/submissions/{id}/rejudge`. Scenario: force a `SYSTEM_ERROR` (stop Judge0 mid-submit), restart Judge0, rejudge → scored correctly, no duplicate result rows.

**Step 12 — Session finalization hook**
- `SessionFinalizedHandler` + `SessionScoringCompletedEvent`; delete the Module 7 `SessionFinalizedListener` stub.
- Scenarios with a 1-minute contest: (a) no pending submits at expiry → event fires immediately; (b) a submit still `PENDING` at expiry → event fires only after it completes; (c) submit attempted after `ends_at + grace` → `SESSION_DEADLINE_PASSED`.

**Step 13 — Recovery scheduler**
- Push a Submit job, then delete it from Redis (simulating a crash). With `stale-after-minutes` temporarily set to 1, the scheduler re-pushes it and it completes. With `max-queue-attempts` exhausted, the row becomes `SYSTEM_ERROR`. Restore production values.

**Step 14 — Rate limits**
- Extend `RateLimitingFilter`; verify the 21st Run in a minute returns 429 while a different user is unaffected.

**Step 15 — Load test and full build**
- 10 candidates × 3 questions submitting at once through the real endpoint. Expect: all rows reach a final status, queue size returns to 0, zero `SYSTEM_ERROR`, no leaked DB connections, and the contest/session schedulers still tick on time during the burst (proves the thread-pool fix).
- `./mvnw compile` and `./mvnw test` both green.

---

## 15. Definition of Done

- [ ] Step 0 done: Judge0 returns status 4 for a wrong answer; Module 7 tests updated and green
- [ ] Flyway `V10` applies cleanly on a fresh DB; schema validation passes
- [ ] Candidate can Run and sees sample results within a few seconds (correct, wrong answer, compile error, TLE all verified)
- [ ] Compile error on Run makes exactly one Judge0 call
- [ ] Candidate can Submit: `202 PENDING` immediately, then final status + weighted score from **all** test cases
- [ ] Counted rule (best score, ties → earliest) verified by an integration test on real Postgres; history marks the counted row
- [ ] Candidate-facing JSON never contains hidden-case data or any `expectedOutput`, proven by explicit tests
- [ ] Another candidate's submission returns 404; Run/Submit without an active session or past deadline are rejected
- [ ] `applyEvaluation` is idempotent: duplicate event produces no duplicate rows and no score change
- [ ] A Judge0/Redis failure yields `SYSTEM_ERROR` with `score = NULL` (never a silent zero); rejudge fixes it
- [ ] Recovery scheduler re-queues a lost job and eventually marks unrecoverable rows `SYSTEM_ERROR`
- [ ] `SessionScoringCompletedEvent` fires exactly once per finalized session, after the last pending Submit completes
- [ ] No DB transaction is open during any Judge0 call
- [ ] Rate limiting active on Run and Submit; Run concurrency cap returns 503 under burst
- [ ] Candidate source code not logged at INFO/WARN in any path (log review)
- [ ] Both Module 7 stub listeners removed
- [ ] 10 concurrent candidates load test passes (Step 15)
- [ ] `./mvnw compile` — BUILD SUCCESS; `./mvnw test` — all unit tests pass (integration tests run separately with Docker)

---

## 16. What Module 9 Will Add (Do Not Build Yet)

Module 9 (Result & Evaluation) will:
- Add `V11` with `results` and `manual_evaluations`.
- Listen to `SessionScoringCompletedEvent`, read the counted submission per question (the Section 9.3 query), sum scores into `results.total_score`, then rank.
- Extend `SubmissionMapper`'s `resultsVisible` condition with "results published" (Section 2.10).
- Handle manual evaluator overrides through `POST /api/submissions/{id}/evaluate`, which sits in Module 9 even though the URL is under `/submissions`.
- Treat a `SYSTEM_ERROR` counted-candidate gap explicitly: a session with an unresolved `SYSTEM_ERROR` Submit should be flagged for evaluator attention before publishing.

Module 8 is complete and independently testable when a candidate can Run and Submit, every Submit ends in a correct final state, and nothing hidden can leak. Module 9 turns those rows into a leaderboard.

---

## 17. File Summary

| File | Action | Notes |
|---|---|---|
| `execution/dto/ExecutionRequest.java` | **Modified** (Step 0) | + `expectedOutput` |
| `execution/dto/Judge0SubmissionRequest.java` | **Modified** (Step 0) | + `expected_output` |
| `execution/dto/QueuedSubmissionJob.java` | **Modified** (Step 0) | + `expectedOutput` on `TestCasePayload` |
| `execution/CodeExecutionService.java` | **Modified** (Step 0) | pass expected output through |
| `execution/SubmissionQueueWorker.java` | **Modified** (Step 0) | copy expected output into requests |
| `execution/event/SubmissionEvaluatedStubListener.java` | **Deleted** | replaced by real listener |
| `execution/event/SessionFinalizedListener.java` | **Deleted** | replaced by `SessionFinalizedHandler` |
| `db/migration/V10__create_submission_tables.sql` | **New** | Section 6 |
| `application-local.yml` | **Modified** | `submission.*`, scheduler pool size |
| `common/enums/SubmissionType.java` | **New** | |
| `config/SubmissionProperties.java` | **New** | |
| `config/RateLimitingFilter` (Module 0) | **Modified** | add `/submit`, per-user keying |
| `testcase/repository/TestCaseRepository.java` (Module 5) | **Modified** | ordered finders if missing |
| `testcase` delete handler (Module 5) | **Modified** | translate FK violation → `TEST_CASE_IN_USE` (409) |
| `submission/entity/Submission.java` | **New** | |
| `submission/entity/SubmissionTestCaseResult.java` | **New** | |
| `submission/repository/SubmissionRepository.java` | **New** | includes counted query |
| `submission/repository/SubmissionTestCaseResultRepository.java` | **New** | |
| `submission/dto/*` | **New** | Run/Submit requests, summary, candidate view, evaluator view, list filter |
| `submission/service/SubmissionService.java` | **New** | orchestration, no `@Transactional` |
| `submission/service/SubmissionPersistenceService.java` | **New** | all transactional units |
| `submission/service/SubmissionAccessGuard.java` | **New** | |
| `submission/service/ScoringService.java` | **New** | pure scoring + status |
| `submission/service/SubmissionMapper.java` | **New** | only place views are built |
| `submission/service/RunConcurrencyLimiter.java` | **New** | |
| `submission/event/SubmissionEvaluatedListener.java` | **New** | |
| `submission/event/SessionFinalizedHandler.java` | **New** | |
| `submission/event/SessionScoringCompletedEvent.java` | **New** | Module 9 consumes |
| `submission/scheduler/SubmissionRecoveryScheduler.java` | **New** | |
| `submission/controller/SubmissionController.java` | **New** | 6 endpoints |
| error-code enum (Module 0) | **Modified** | Section 11 codes |
| `docker-compose.yml` | **Modified** | close/bind app Redis port, set `requirepass` |
