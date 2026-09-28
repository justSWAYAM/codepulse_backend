# CodePulse Enterprise — Module 6 Backend Build Plan
### Candidate Assessment Session Service

**Purpose:** A contest is a window; a session is one candidate's personal, timed attempt inside it. This module owns the exam clock — when a candidate starts, when their time ends, whether they can resume, and what happens when time runs out. It sits between "Contest exists" (Module 3) and "Submissions exist" (Module 8), and it is the first module where **object-level authorization** (a candidate touching only *their own* record) is the main security concern, not role checks.

**Depends on:** Module 0 (Cross-Cutting Foundation) + Module 1 (Authentication) + Module 2 (User Management) + Module 3 (Contest Management). Modules 4 and 5 are not prerequisites, but this module makes a small, recommended change to their candidate read paths (Section 9.3).

---

## 1. What This Module Inherits (Do Not Rebuild)

| From | Component | How it's used here |
|---|---|---|
| Module 0 | `BaseEntity` | `AssessmentSession` extends it — id, createdAt, updatedAt, createdBy, updatedBy |
| Module 0 | `ApiResponse<T>` | Every endpoint wraps its response |
| Module 0 | `GlobalExceptionHandler` + exception hierarchy | `ResourceNotFoundException` (no session), `AccessDeniedException` (not assigned), `InvalidStateException` (contest not live, session already ended). Import the **app's own** `AccessDeniedException`, not Spring Security's |
| Module 0 | `AuditService` + shared action enum | Log session start / submit / auto-submit. Add the new action values to the shared enum, as Module 2's plan requires |
| Module 0 | `Role` enum | `@PreAuthorize("hasRole('CANDIDATE')")` on every endpoint |
| Module 1 | `SecurityConfig`, `JwtAuthenticationFilter` | Untouched — `@EnableMethodSecurity` already on |
| Module 2 | `getCurrentUser()` helper | Resolves the candidate from the `SecurityContext`, same as Modules 4 and 5 |
| Module 3 | `Contest` entity | `status`, `endTime`, `durationMinutes` drive session timing. Module 3's plan kept `durationMinutes` explicitly *for this module* |
| Module 3 | `ContestCandidate` + `ContestCandidateStatus` | The `INVITED → IN_PROGRESS → COMPLETED` lifecycle Module 3 defined "so Module 6 doesn't improvise" — this module drives those transitions |
| Module 3 | `ContestCandidateRepository.existsByContestIdAndCandidateId` | Assignment check (object-level) |
| Module 3 | `@EnableScheduling` (already added) | Do **not** add it again; just add a second `@Scheduled` job |
| Module 3 | `ContestService.transitionToCompleted()` | **Modified** to publish `ContestCompletedEvent` — the coupling Module 3's plan told you to prepare for (Section 9.1) |

**Blocker check:** confirm Module 3's scheduler actually moves contests to `ONGOING`/`COMPLETED` before starting. This module has nothing to test against otherwise.

---

## 2. Decisions to Make Before Writing Any Code

### 2.1 Session end time: capped by the contest window
`ends_at = MIN(started_at + contest.durationMinutes, contest.endTime)`.

Module 3's plan says `durationMinutes` sets "candidate-specific session end times regardless of actual contest end time." Read literally, a candidate starting at 09:50 in a contest ending 10:00 with a 60-minute duration would get until 10:50 — but the contest goes `COMPLETED` at 10:00 and the event in Section 9.1 would cut them off anyway, leaving the timer lying to them. **Cap at contest end** so the countdown shows the time they actually have. Document this on the entity, as Module 3's plan did for `durationMinutes`.

### 2.2 Question reads should require an active session (recommended tightening)
Module 4's candidate path only checks *assigned + contest `ONGOING`*. That lets a candidate read every question for hours **before** pressing Start, then start a 60-minute clock already knowing the problems — and keep reading after their session ends until the contest closes. That defeats the point of a personal timer.

**Recommendation:** candidate reads in `QuestionService` and `TestCaseService` additionally require an unexpired `IN_PROGRESS` session (Section 9.3). Decide now; the alternative is leaving Modules 4/5 untouched and accepting the gap. **Frontend consequence:** the candidate "Questions" tab on `ContestDetailPage` (Module 4 frontend) would return 403 before Start — candidates must go through the Assessment page.

### 2.3 `EXPIRED` is reserved, not produced
The roadmap lists four statuses. Timeout produces `AUTO_SUBMITTED`; manual submit produces `SUBMITTED`; nothing in this module needs `EXPIRED`. Keep it in the enum (no future migration), but **do not write any code path that sets it** — inventing a meaning now would conflict with Modules 8/9. Add a comment on the enum: "reserved".

### 2.4 Scoring hand-off is an event, not a method call
The roadmap says auto-submit "triggers final scoring via Submission Service." Module 8 depends on Module 6, so Module 6 cannot call it (it doesn't exist yet, and it would be a circular module dependency). Publish `SessionFinalizedEvent`; Module 8 adds the listener. Until then, ship a tiny logging listener so the event is verifiable.

### 2.5 Start and Submit are idempotent
- `POST /start` = **start or resume**. Existing `IN_PROGRESS` session → return it with `resumed = true`. Never create a second one.
- `POST /submit` on an already-ended session → return its final status, no second event. A double-click or a submit that races the auto-submit should not surface as an error.

### 2.6 The scheduler is a backstop, not the source of truth
Correctness must not depend on a 10-second tick. Every session endpoint checks `ends_at` against the server clock itself (lazy enforcement); the scheduler exists so that sessions of candidates who simply walked away still get finalized.

### 2.7 "Resume without losing progress" — what that means here
The session (clock, status) persists across refresh/reconnect. Submitted Run/Submit history lives in Module 8. **Unsaved editor text is not persisted** — the roadmap lists draft auto-save as a future enhancement. State this in the viva; don't imply more.

---

## 3. Database Migration

**File:** `src/main/resources/db/migration/V8__create_assessment_session_table.sql`

> Note: V7 is `test_cases` from Module 5. This is V8.

```sql
CREATE TABLE assessment_sessions (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    contest_id    UUID         NOT NULL REFERENCES contests(id),
    candidate_id  UUID         NOT NULL REFERENCES users(id),
    started_at    TIMESTAMPTZ  NOT NULL,
    ends_at       TIMESTAMPTZ  NOT NULL,
    status        VARCHAR(50)  NOT NULL DEFAULT 'IN_PROGRESS',
    submitted_at  TIMESTAMPTZ,
    created_by    UUID         NOT NULL REFERENCES users(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    UUID         REFERENCES users(id),

    UNIQUE (contest_id, candidate_id),                    -- one attempt per candidate per contest
    CHECK (ends_at > started_at),
    CHECK (status IN ('IN_PROGRESS','SUBMITTED','AUTO_SUBMITTED','EXPIRED'))
);

-- Backs the expiry scheduler: only IN_PROGRESS rows are ever scanned
CREATE INDEX idx_sessions_expiry ON assessment_sessions(ends_at) WHERE status = 'IN_PROGRESS';
CREATE INDEX idx_sessions_contest_id ON assessment_sessions(contest_id);
```

**Design notes**
- **`UNIQUE (contest_id, candidate_id)` is the real guard against double-start** (two tabs, double-click), not application code.
- **No `ON DELETE CASCADE`** on either FK, unlike Modules 3–5. Modules 4/5 cascade because questions and test cases are meaningless without their parent; a session is exam evidence, and Module 8's submissions will hang off it. A contest with sessions should not be deletable by accident.
- **`VARCHAR` + `@Enumerated(EnumType.STRING)`, no Postgres `ENUM` type** — the same approach Module 3's entities actually use.
- Audit columns included for the same reason as Modules 4 and 5.

---

## 4. Common Enums and Events

**Enum** — `com/codepulse_backend/common/enums/SessionStatus.java` (single source of truth, like Module 3's enums):

```java
public enum SessionStatus {
    IN_PROGRESS,
    SUBMITTED,       // candidate pressed Submit
    AUTO_SUBMITTED,  // time ran out, or contest completed
    EXPIRED          // RESERVED — no code in Module 6 sets this (Section 2.3)
}
```

**Events** — in `com/codepulse_backend/common/event/` (shared, so `contest` and `session` packages don't depend on each other's internals):

```java
public record ContestCompletedEvent(UUID contestId) {}

public record SessionFinalizedEvent(
    UUID sessionId, UUID contestId, UUID candidateId, SessionStatus finalStatus
) {}
```

---

## 5. Java Package Structure

```
com.codepulse_backend/
└── session/
    ├── entity/      AssessmentSession.java
    ├── repository/  AssessmentSessionRepository.java
    ├── dto/         StartSessionResponse.java, SessionStatusResponse.java
    ├── service/     AssessmentSessionService.java
    ├── scheduler/   SessionExpiryScheduler.java
    ├── listener/    ContestCompletedListener.java, LoggingSessionFinalizedListener.java (temporary)
    └── controller/  AssessmentSessionController.java
```

---

## 6. Entity

```java
@Entity
@Table(name = "assessment_sessions")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssessmentSession extends BaseEntity {

    @Column(name = "contest_id", nullable = false)
    private UUID contestId;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    // Server-computed, never client-supplied: MIN(startedAt + durationMinutes, contest.endTime)
    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status;

    @Column(name = "submitted_at")
    private Instant submittedAt;
}
```

`contestId` / `candidateId` are plain `UUID` columns — the same convention as `Question.contestId` (Module 4) and `TestCase.questionId` (Module 5). `startedAt` and `endsAt` are `updatable = false`: nothing may ever extend a session by editing the row through JPA.

---

## 7. Repository

```java
public interface AssessmentSessionRepository extends JpaRepository<AssessmentSession, UUID> {

    Optional<AssessmentSession> findByContestIdAndCandidateId(UUID contestId, UUID candidateId);

    // Write paths (submit, finalize) lock the row so a manual submit and the
    // scheduler can never both finalize the same session.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AssessmentSession s where s.contestId = :contestId and s.candidateId = :candidateId")
    Optional<AssessmentSession> findForUpdate(@Param("contestId") UUID contestId,
                                              @Param("candidateId") UUID candidateId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AssessmentSession s where s.id = :id")
    Optional<AssessmentSession> findByIdForUpdate(@Param("id") UUID id);

    // Scheduler: ids only, so each session is then finalized in its own transaction
    @Query("select s.id from AssessmentSession s where s.status = com.codepulse_backend.common.enums.SessionStatus.IN_PROGRESS and s.endsAt <= :now")
    List<UUID> findExpiredInProgressIds(@Param("now") Instant now);

    @Query("select s.id from AssessmentSession s where s.contestId = :contestId and s.status = com.codepulse_backend.common.enums.SessionStatus.IN_PROGRESS")
    List<UUID> findInProgressIdsByContestId(@Param("contestId") UUID contestId);
}
```

---

## 8. DTOs

```java
public record StartSessionResponse(
    UUID sessionId,
    UUID contestId,
    SessionStatus status,
    Instant startedAt,
    Instant endsAt,
    Instant serverTime,        // the server's "now" at response time — lets the client compute its clock offset
    long remainingSeconds,
    boolean resumed            // false = brand-new session, true = existing one returned
) {}

public record SessionStatusResponse(
    UUID sessionId,
    UUID contestId,
    SessionStatus status,
    Instant startedAt,
    Instant endsAt,
    Instant submittedAt,       // null while IN_PROGRESS
    Instant serverTime,
    long remainingSeconds      // 0 unless IN_PROGRESS
) {}
```

**`serverTime` is not optional.** The frontend timer (roadmap: "synced against server `ends_at`, not counting independently") computes `offset = serverTime − clientNow` on every response and counts down to `endsAt` using it. Without `serverTime`, a candidate with a wrong system clock sees a wrong countdown even though the server is right.

Neither DTO exposes `candidateId`; the caller is always the owner.

---

## 9. Modifying Earlier Modules (do this deliberately, as its own step)

### 9.1 Module 3 — publish `ContestCompletedEvent`
In `ContestService.transitionToCompleted(Contest)`, after flipping the status:

```java
eventPublisher.publishEvent(new ContestCompletedEvent(contest.getId()));   // inject ApplicationEventPublisher
```

Nothing else in Module 3 changes. This is the "plan the event shape now" item from Module 3's Future Enhancements, now realized.

### 9.2 Module 3 — one repository method
Add to `ContestCandidateRepository`:

```java
Optional<ContestCandidate> findByContestIdAndCandidateId(UUID contestId, UUID candidateId);
```

Needed to flip `ContestCandidate.status` on start and on finalize. Module 3's repository only had `exists…`, `findAllByContestId` and `deleteAllByContestId`.

### 9.3 Modules 4 and 5 — require an active session on candidate reads (if you accept 2.2)
Add one method to `AssessmentSessionService` (Section 10) and call it in the **candidate branch only** of:
- `QuestionService.getQuestions` and `getQuestionDetail` (Module 4)
- `TestCaseService.getTestCasesForQuestion` (Module 5)

```
if (currentUser.role == CANDIDATE) {
    assigned check            → 403 if false           (unchanged)
    contest.status == ONGOING → 403 if not             (unchanged)
    sessionService.requireActiveSession(contestId, currentUser.id)   ← NEW, 403/409 if none or expired
    return candidate response
}
```

Admin/Evaluator branches are untouched. **Dependency direction:** `QuestionService` and `TestCaseService` depend on `AssessmentSessionService`; `AssessmentSessionService` depends only on repositories and `Clock` — never back on question/test-case code. Same one-way rule as Module 5's Section 8.3.

---

## 10. Service

### `AssessmentSessionService.java`

```java
public StartSessionResponse startOrResumeSession(UUID contestId)
public SessionStatusResponse getSessionStatus(UUID contestId)
public SessionStatusResponse submitSession(UUID contestId)

// Internal — used by the scheduler, the contest listener, and submit/lazy paths
public void finalizeSession(UUID sessionId, SessionStatus finalStatus)   // idempotent
public void finalizeAllForContest(UUID contestId)

// Internal — used by Section 9.3 and by Module 8; read-only, never mutates
public AssessmentSession requireActiveSession(UUID contestId, UUID candidateId)
```

All time comes from an injected `java.time.Clock` (bean added in Section 15) — **never call `Instant.now()` directly in this module**, or short-window tests become impossible.

### Ownership is structural
No endpoint takes a session id. Every lookup is `(contestId from URL, candidateId from the JWT-derived current user)`. Candidate B literally cannot address candidate A's session — a lookup by B's id simply finds nothing. The explicit assignment check is still done on start, but the read/submit paths are safe by construction. This is the object-level authorization the roadmap asks for, without an `if (session.candidateId != currentUser.id)` that someone can forget later.

### `startOrResumeSession(contestId)`
1. `currentUser = getCurrentUser()`; load contest → `ResourceNotFoundException` if missing.
2. `existsByContestIdAndCandidateId` → `AccessDeniedException` if not assigned.
3. Look for an existing session (`findForUpdate`):
   - **Found, `IN_PROGRESS`, not expired** → return it with `resumed = true`.
   - **Found, `IN_PROGRESS`, but `endsAt <= now`** → `finalizeSession(id, AUTO_SUBMITTED)`, then throw `InvalidStateException("Your time has ended")`.
   - **Found, any other status** → `InvalidStateException("Session already ended")`.
4. **Not found** — new session:
   - `contest.status != ONGOING` → `InvalidStateException` with a message that distinguishes "not started yet" from "already ended" (the UI needs both). *This deliberately differs from Module 4's 403:* Module 4 hides content from a reader; here the candidate is attempting an action and needs a specific state error.
   - `now = clock.instant()`; `endsAt = min(now + durationMinutes, contest.endTime)`; if `endsAt <= now` → `InvalidStateException`.
   - Persist with `status = IN_PROGRESS`, `startedAt = now`.
   - Set `ContestCandidate.status = IN_PROGRESS` (via the Section 9.2 method).
   - `AuditService` → `SESSION_STARTED`. Return with `resumed = false`.

**First-start race:** two simultaneous first calls both find nothing (a row lock can't lock a row that doesn't exist), and the `UNIQUE` constraint rejects the loser with `DataIntegrityViolationException`. That exception marks the surrounding transaction rollback-only, so catch it **outside** the transaction (run the body via `TransactionTemplate`, catch, then re-run the read path once and return the winner's session as `resumed = true`).

### `getSessionStatus(contestId)`
Find by `(contestId, currentUser.id)` → `ResourceNotFoundException` if none. If `IN_PROGRESS` and expired → `finalizeSession(..., AUTO_SUBMITTED)` first (lazy enforcement, Section 2.6), then return the final state with `remainingSeconds = 0`.

### `submitSession(contestId)`
Find with `findForUpdate`.
- `IN_PROGRESS`, not expired → `finalizeSession(id, SUBMITTED)`.
- `IN_PROGRESS`, expired → `finalizeSession(id, AUTO_SUBMITTED)` — the timer won the race; report it honestly rather than pretending the candidate submitted in time.
- Already final → return current status (idempotent, no event).

### `finalizeSession(sessionId, finalStatus)`
`@Transactional(propagation = REQUIRES_NEW)`, so each session commits on its own and callers in other transactions (scheduler, listener) can't roll it back.
1. `findByIdForUpdate`; if `status != IN_PROGRESS` → return (idempotent).
2. `status = finalStatus`; `submittedAt = MIN(now, endsAt)` — an auto-submit is stamped with the moment the timer expired, **not** the moment the scheduler noticed, so Module 8 can cleanly say "count what was saved before `submittedAt`."
3. `ContestCandidate.status = COMPLETED`.
4. `AuditService` → `SESSION_SUBMITTED` or `SESSION_AUTO_SUBMITTED`.
5. `eventPublisher.publishEvent(new SessionFinalizedEvent(...))`.

### `finalizeAllForContest(contestId)`
`findInProgressIdsByContestId` → `finalizeSession(id, AUTO_SUBMITTED)` for each, each in its own try/catch so one failure doesn't strand the rest.

### `requireActiveSession(contestId, candidateId)`
No session → `AccessDeniedException`; not `IN_PROGRESS` or `endsAt <= now` → `InvalidStateException`; else return the entity. **Read-only by design** — it never finalizes; only the three session endpoints, the scheduler, and the listener mutate state.

---

## 11. Scheduler and Listeners

### `SessionExpiryScheduler.java`

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionExpiryScheduler {

    private final AssessmentSessionRepository sessionRepository;
    private final AssessmentSessionService sessionService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.scheduler.session-expiry-interval-ms:10000}")
    public void autoSubmitExpiredSessions() {
        for (UUID id : sessionRepository.findExpiredInProgressIds(clock.instant())) {
            try {
                sessionService.finalizeSession(id, SessionStatus.AUTO_SUBMITTED);
                log.info("Session {} auto-submitted", id);
            } catch (Exception e) {
                log.error("Auto-submit failed for session {}", id, e);   // keep going; next tick retries
            }
        }
    }
}
```

- **Not** `@Transactional` itself, and it calls `finalizeSession` on a **different bean**. `@Transactional` on a method invoked from the same class bypasses the Spring proxy and silently does nothing.
- **10 seconds, not Module 3's 60.** The roadmap requires auto-submit "within a few seconds of expiry"; a 60-second tick can't deliver that. Lazy enforcement (Section 2.6) covers candidates who are still online, but the scheduler is what finalizes the ones who left.

### `ContestCompletedListener.java`

```java
@Component
@RequiredArgsConstructor
public class ContestCompletedListener {
    private final AssessmentSessionService sessionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContestCompleted(ContestCompletedEvent event) {
        sessionService.finalizeAllForContest(event.contestId());
    }
}
```

`AFTER_COMMIT` is essential: Module 3's scheduler publishes the event *inside* its own `@Transactional` method, and the listener must only act once the contest is durably `COMPLETED`. Because of the cap in Section 2.1 this is mostly a safety net — sessions normally expire on their own by contest end — but it guarantees no `IN_PROGRESS` session outlives its contest.

### `LoggingSessionFinalizedListener.java` (temporary)
A `@TransactionalEventListener(AFTER_COMMIT)` that logs the `SessionFinalizedEvent`. It exists so this module's Definition of Done can be verified before Module 8 exists. **Delete it when Module 8 adds the real scoring listener.**

---

## 12. Controller

```java
@RestController
@RequestMapping("/api/contests/{contestId}/session")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CANDIDATE')")
public class AssessmentSessionController {

    @PostMapping("/start")
    public ApiResponse<StartSessionResponse> start(@PathVariable UUID contestId)   // 200 for both start and resume

    @GetMapping
    public ApiResponse<SessionStatusResponse> getStatus(@PathVariable UUID contestId)

    @PostMapping("/submit")
    public ApiResponse<SessionStatusResponse> submit(@PathVariable UUID contestId)
}
```

None of the three accepts a request body or any time value — the server owns the clock. `start` returns `200` (not `201`) even for a new session, since the same endpoint also resumes; `resumed` in the body tells the client which happened.

---

## 13. API Reference

| Method | Endpoint | Description | Access |
|---|---|---|---|
| `POST` | `/api/contests/{contestId}/session/start` | Start or resume | Candidate |
| `GET` | `/api/contests/{contestId}/session` | Status + time remaining | Candidate |
| `POST` | `/api/contests/{contestId}/session/submit` | Manual final submit | Candidate |

Same three endpoints as the roadmap. No Admin/Evaluator session endpoints in this module (live monitoring belongs to Module 13).

---

## 14. Security Rules Summary

| Caller | Condition | Result |
|---|---|---|
| Non-`CANDIDATE` (Admin/Evaluator) | Any session endpoint | `403` via `@PreAuthorize` |
| `CANDIDATE` | Not assigned to contest, `start` | `403 AccessDeniedException` |
| `CANDIDATE` | Assigned, contest not `ONGOING`, no existing session | `409 InvalidStateException` (message: not started / ended) |
| `CANDIDATE` | Assigned, contest `ONGOING`, first `start` | New session, `endsAt` server-computed |
| `CANDIDATE` | `start` with an unexpired `IN_PROGRESS` session | Same session, `resumed = true` |
| `CANDIDATE` | `start` after `SUBMITTED` / `AUTO_SUBMITTED` | `409 InvalidStateException` |
| `CANDIDATE` | `GET`/`submit` with no session | `404 ResourceNotFoundException` |
| `CANDIDATE` A | Tries to read/submit candidate B's session | Impossible — lookup is keyed on A's own id, so B's row is never found |

---

## 15. Configuration

- **`Clock` bean** in a `@Configuration` class: `@Bean Clock clock() { return Clock.systemUTC(); }`. Tests replace it with a fixed/mutable clock.
- **`application-{profile}.yml`:**
  ```yaml
  app:
    scheduler:
      session-expiry-interval-ms: 10000
  ```
- **Shared audit enum:** add `SESSION_STARTED`, `SESSION_SUBMITTED`, `SESSION_AUTO_SUBMITTED`.
- **Verify** `Instant` values serialize as ISO-8601 strings (not epoch arrays or numbers) — the frontend timer parses `endsAt`/`serverTime` directly. Check Module 0's Jackson config.

---

## 16. Build Sequence

1. Write `V8__create_assessment_session_table.sql`; start the app and confirm Flyway applies it.
2. Add `SessionStatus`, `ContestCompletedEvent`, `SessionFinalizedEvent`; add the `Clock` bean and audit enum values.
3. `AssessmentSession` entity + `AssessmentSessionRepository`.
4. DTOs.
5. Module 3 changes (Sections 9.1, 9.2): the repository method and event publishing.
6. `AssessmentSessionService` — `startOrResumeSession` first, then `getSessionStatus`, `finalizeSession`, `submitSession`, `finalizeAllForContest`, `requireActiveSession`.
7. **`SessionExpiryScheduler` — build and test this now**, with a 1-minute contest, as the roadmap insists. Don't leave it for the end.
8. `ContestCompletedListener` + temporary `LoggingSessionFinalizedListener`.
9. `AssessmentSessionController`.
10. Apply Section 9.3 to `QuestionService` and `TestCaseService` (if you accepted 2.2). Restart and confirm no circular-dependency failure.
11. Manual pass through the Definition of Done below.

---

## 17. Tests

| Test | Covers |
|---|---|
| `AssessmentSessionServiceTest` (fixed `Clock`) | `endsAt` = start + duration; `endsAt` capped at contest `endTime`; resume returns the same row and creates no second one; not assigned → 403; contest not `ONGOING` → 409; start after final → 409; submit sets `SUBMITTED` + `ContestCandidate.COMPLETED` + publishes event; submit on ended session is idempotent with no second event; expired session is lazily auto-submitted on `GET` |
| `SessionExpirySchedulerTest` | Advance the mutable `Clock` past `endsAt` → session becomes `AUTO_SUBMITTED` with `submittedAt = endsAt`; not-yet-expired session untouched; one failing session doesn't block the others |
| Concurrency test | Two parallel first `start` calls → exactly one row, both callers get the same session |
| Concurrency test | Manual `submit` racing the scheduler → exactly one final status, exactly one event |
| `ContestCompletedListenerTest` | Contest completion finalizes every `IN_PROGRESS` session for that contest and leaves other contests' sessions alone |
| `AssessmentSessionControllerTest` (MockMvc) | Admin/Evaluator → 403 on all three; candidate B `GET` on a contest where only A has a session → 404 |
| Clock-independence test | Assert no endpoint accepts a time value, and `endsAt` in the response equals the server-computed value |
| Read-gating test (if 2.2 accepted) | Candidate with no session → questions/test cases return 403; with active session → 200; after expiry → blocked again |

Use `Clock` manipulation for expiry tests rather than sleeping; keep one real 1-minute end-to-end check as the final manual verification.

---

## 18. Definition of Done

- [ ] `V8__create_assessment_session_table.sql` applies cleanly
- [ ] Session `endsAt` is computed entirely from server time — no request carries any time value
- [ ] `endsAt` never exceeds the contest's `endTime`
- [ ] Refreshing the browser mid-exam and calling `start`/`GET` returns the **same** session with correct remaining time
- [ ] Two simultaneous first `start` calls produce exactly one session
- [ ] A candidate cannot read, submit, or otherwise reach another candidate's session
- [ ] Manual submit sets `SUBMITTED`, stamps `submittedAt`, and moves `ContestCandidate` to `COMPLETED`
- [ ] With a 1-minute contest, the session flips to `AUTO_SUBMITTED` within ~10 seconds of expiry and `SessionFinalizedEvent` fires (visible in the temporary logging listener)
- [ ] Contest going `COMPLETED` finalizes any remaining `IN_PROGRESS` sessions
- [ ] Starting after the session has ended, or on a contest that is not `ONGOING`, gives a clear, specific error
- [ ] (If 2.2 accepted) candidate question/test-case reads are blocked without an active session
- [ ] `audit_logs` shows a row for each start, submit, and auto-submit

---

## 19. Package Structure After This Module

```
com.codepulse_backend
├── auth/                      (Module 1 — untouched)
├── common/
│   ├── converter/             (Module 3)
│   ├── csv/                   (Module 2)
│   ├── enums/                 Role, ContestStatus, ContestCandidateStatus, Difficulty,
│   │                          SessionStatus ← NEW
│   └── event/                 ContestCompletedEvent ← NEW, SessionFinalizedEvent ← NEW
├── contest/                   (Module 3 — small change: event publish + 1 repository method)
├── question/                  (Module 4 — candidate read path gated on active session, if 2.2 accepted)
├── testcase/                  (Module 5 — same)
├── session/                   ← NEW PACKAGE (Section 5)
└── user/                      (Modules 1 & 2 — untouched)
```

---

## 20. What Later Modules Need From You

- **Module 8 (Submission Service):** `submissions.session_id` is a direct FK to `assessment_sessions.id` — that table can't be built before this one. Every Run/Submit must call `requireActiveSession(contestId, candidateId)` first (roadmap: "candidate can only submit within their own active session"). Module 8 adds the real `@TransactionalEventListener(AFTER_COMMIT)` on `SessionFinalizedEvent` to score whatever was last submitted, treating `submittedAt` as the cut-off, and deletes `LoggingSessionFinalizedListener`.
- **Module 9 (Results):** reads `SessionStatus` and `submittedAt` to distinguish a completed attempt from a timed-out one. A candidate whose `ContestCandidate` row is still `INVITED` after the contest ended never started — treat as "no attempt," not zero.
- **Modules 11/13 (WebSocket, Live Monitoring):** `SessionFinalizedEvent` is the natural hook for pushing "candidate finished" updates; no new plumbing needed in this module.

---

## 21. Things That Can Go Wrong — Watch Out For These

| Pitfall | What to do |
|---|---|
| Any code path uses the client's time or a client-supplied `endsAt` | Grep for request fields named like time/`endsAt`; there must be none. Only `Clock` produces timestamps |
| Correctness depends on the scheduler tick | Lazy `ends_at` checks in `start`/`GET`/`submit` (Section 2.6) must exist; the scheduler only cleans up abandoned sessions |
| `@Transactional` on a method called from within the same class | Silent no-op. Keep `finalizeSession` on `AssessmentSessionService` and call it from other beans (scheduler, listener) |
| First-start race surfaces as a 500 | Catch `DataIntegrityViolationException` outside the transaction and re-read (Section 10) |
| Submit and scheduler both finalize the same session | Row lock in `finalizeSession` + the `status != IN_PROGRESS → return` guard. Both are needed |
| `AuditorAware` fails in the scheduler thread (no authenticated user) | Confirm Module 0's implementation returns `Optional.empty()`; `updated_by` is nullable for exactly this reason |
| Importing Spring Security's `AccessDeniedException` | Use the app's own from Module 0, or `GlobalExceptionHandler` won't shape the response |
| `ContestCompletedEvent` listener fires before commit | Must be `AFTER_COMMIT`; Module 3's scheduler publishes inside its own transaction |
| Session outlives contest end | Section 2.1 cap + Section 11 listener. If the cap is skipped, the timer shows time the candidate doesn't actually have |
| Expiry tests take minutes | Use the injectable `Clock`; don't `Thread.sleep` |
| `Instant` serialized as a number/array | Verify ISO-8601 output; the frontend timer parses it directly |
| Section 9.3 changes the candidate Questions tab without warning the frontend | Candidates now need a session before any question read; the Module 4 frontend's candidate Questions tab must route through the Assessment page |

---

## 22. Future Enhancements (Out of Scope for This Module)

- Draft auto-save every N seconds so unsubmitted editor text survives a crash (roadmap)
- Per-question time tracking and analytics (roadmap)
- Admin-granted time extensions for an individual candidate (would require making `ends_at` mutable through an audited admin path)
- Heartbeat/disconnect tracking per session, feeding Module 13's live monitoring
- ShedLock on the expiry scheduler if the backend ever runs on more than one node (same caveat Module 3's plan raised)
