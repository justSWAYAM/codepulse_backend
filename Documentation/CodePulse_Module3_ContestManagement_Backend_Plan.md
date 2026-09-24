# CodePulse Enterprise — Module 3 Backend Build Plan
### Contest Management Service

**Purpose:** The Contest is the central entity of the entire platform — every other domain module (Questions, Test Cases, Sessions, Submissions, Results) anchors to it. This module creates and manages contests with a time-bounded lifecycle, controls which candidates are assigned, and automates status transitions so nothing depends on manual admin clicks.

**Depends on:** Module 0 (Cross-Cutting Foundation) + Module 1 (Authentication) + Module 2 (User Management) — all must be functionally complete before starting. You need working Admin + Candidate accounts to create contests and assign participants.

---

## 1. What This Module Inherits (Do Not Rebuild)

| From | Component | How it's used here |
|---|---|---|
| Module 0 | `BaseEntity` | `Contest` extends this — id (UUID), createdAt, updatedAt, createdBy, updatedBy |
| Module 0 | `ApiResponse<T>` | Every endpoint wraps its response in this envelope — no raw returns |
| Module 0 | `PagedResponse<T>` | Used by `GET /api/contests` paginated list endpoint |
| Module 0 | `GlobalExceptionHandler` | Handles all exceptions this module throws — no new handler needed |
| Module 0 | Exception hierarchy | Reuse `ResourceNotFoundException` (contest not found), `DuplicateResourceException`, `AccessDeniedException`, `InvalidStateException` (e.g., transitioning from COMPLETED back to DRAFT) |
| Module 0 | `AuditService` | Log contest creation, publishing, candidate assignment — important for the report's audit trail narrative |
| Module 0 | `Role` enum | Used for access control checks: `ADMIN`, `EVALUATOR`, `CANDIDATE` |
| Module 1 | `SecurityConfig` | Extended (not rebuilt) — `@PreAuthorize` is already enabled via `@EnableMethodSecurity` |
| Module 1 | `JwtAuthenticationFilter` | Untouched — all contest endpoints sit behind auth by default |
| Module 2 | `User` entity + `UserRepository` | Needed to validate candidate existence before assignment |
| Module 2 | `UserSummaryResponse` | Reused in assignment response DTOs — don't define a new user shape here |

**Blocker check:** if `GET /api/users` (Module 2) doesn't return users that can be assigned, you can't fully test this module. Fix Module 2 first.

---

## 2. Decisions to Make Before Writing Any Code

### 2.1 `allowed_languages` storage strategy
The contest defines which programming languages are permitted. Two options:

- **Option A — PostgreSQL array column.** Store as `text[]` in Postgres. Needs a custom JPA converter. Clean at the DB level; harder to query.
- **Option B — JSON string column.** Store as `VARCHAR`/`TEXT` with a Jackson-based `@Convert` — `List<String>` ↔ JSON string. Simpler JPA mapping, still validated at the service layer.

Option B is lower friction for this scope. Whichever you pick, **decide now** — changing the column type after migrations exist is pain.

### 2.2 `duration_minutes` vs computed duration
The roadmap includes both `start_time`, `end_time`, and `duration_minutes`. In practice, `duration_minutes` is redundant if `end_time - start_time` is always the duration. Make a call:
- Keep all three and validate at the service layer that `start_time + duration_minutes == end_time` (or close enough)
- OR drop `duration_minutes` from the entity and compute it on read

Recommendation: keep `duration_minutes` explicitly — it's used by the Assessment Session module to set candidate-specific session end times regardless of actual contest end time. Document this in a comment on the entity.

### 2.3 `ContestCandidate` enrollment status
The join table `contest_candidates` has a `status` column. Define what values this takes now, before Module 6 (Assessment Session) builds on it:
- `INVITED` — assigned but hasn't started
- `IN_PROGRESS` — session started
- `COMPLETED` — submitted or auto-submitted

If you don't define this now, Module 6 will improvise something inconsistent. Add a `ContestCandidateStatus` enum to `com.codepulse_backend.common.enums`.

---

## 3. Database Migration

**File:** `src/main/resources/db/migration/V5__create_contest_tables.sql`

> Note: V4 is the `refresh_tokens` table from Module 1. This is V5.

```sql
CREATE TYPE contest_status AS ENUM ('DRAFT', 'PUBLISHED', 'ONGOING', 'COMPLETED');
CREATE TYPE contest_candidate_status AS ENUM ('INVITED', 'IN_PROGRESS', 'COMPLETED');

CREATE TABLE contests (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title               VARCHAR(255) NOT NULL,
    description         TEXT,
    start_time          TIMESTAMPTZ  NOT NULL,
    end_time            TIMESTAMPTZ  NOT NULL,
    duration_minutes    INT          NOT NULL,
    allowed_languages   TEXT         NOT NULL,     -- JSON array: ["JAVA","PYTHON","CPP"]
    status              VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
    created_by          UUID         NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by          UUID
);

CREATE TABLE contest_candidates (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    contest_id   UUID         NOT NULL REFERENCES contests(id) ON DELETE CASCADE,
    candidate_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status       VARCHAR(50)  NOT NULL DEFAULT 'INVITED',
    invited_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (contest_id, candidate_id)    -- prevent double-assignment
);

CREATE INDEX idx_contests_status      ON contests(status);
CREATE INDEX idx_contests_created_by  ON contests(created_by);
CREATE INDEX idx_cc_contest_id        ON contest_candidates(contest_id);
CREATE INDEX idx_cc_candidate_id      ON contest_candidates(candidate_id);
```

**Things to handle:**
- The `UNIQUE (contest_id, candidate_id)` constraint prevents the same candidate being assigned twice — handle the resulting `DataIntegrityViolationException` in the service layer and convert it to `DuplicateResourceException`.
- If you're using native PostgreSQL `ENUM` types, map them in JPA with `@Enumerated(EnumType.STRING)` + `columnDefinition = "contest_status"`. If this causes friction with JPA, switch to a `VARCHAR(50)` column with the enum stored as its string name — simpler and still safe.

---

## 4. Common Enums

**File:** `src/main/java/com/codepulse_backend/common/enums/ContestStatus.java`
```java
public enum ContestStatus {
    DRAFT,
    PUBLISHED,
    ONGOING,
    COMPLETED
}
```

**File:** `src/main/java/com/codepulse_backend/common/enums/ContestCandidateStatus.java`
```java
public enum ContestCandidateStatus {
    INVITED,
    IN_PROGRESS,
    COMPLETED
}
```

Add these to the existing `com.codepulse_backend.common.enums` package alongside `Role`. These are the single source of truth — do not define local enums inside the contest package.

---

## 5. File-by-File Build Order

### Step 1 — Entities

#### `Contest` Entity

**File:** `com/codepulse_backend/contest/entity/Contest.java`

```java
@Entity
@Table(name = "contests")
@EntityListeners(AuditingEntityListener.class)
public class Contest extends BaseEntity {

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "allowed_languages", nullable = false)
    @Convert(converter = StringListConverter.class)  // JSON <-> List<String>
    private List<String> allowedLanguages = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContestStatus status = ContestStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    private User createdBy;

    // getters, setters or Lombok @Data
}
```

**Things to handle:**
- `StringListConverter` — create a `@Converter` class in `com.codepulse_backend.common.converter` that serializes `List<String>` to a JSON string and back using Jackson `ObjectMapper`. This is reused by Question module's `allowed_languages` too.
- Do NOT add a `@OneToMany` to `ContestCandidate` here unless you genuinely need reverse traversal. Omit it and query from the repository — avoids N+1 problems.
- `createdBy` is set in the service layer from the `SecurityContext`, not from client input — never let the client send a `createdBy` field.

#### `ContestCandidate` Entity

**File:** `com/codepulse_backend/contest/entity/ContestCandidate.java`

```java
@Entity
@Table(name = "contest_candidates",
       uniqueConstraints = @UniqueConstraint(columnNames = {"contest_id", "candidate_id"}))
public class ContestCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contest_id", nullable = false)
    private Contest contest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false)
    private User candidate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContestCandidateStatus status = ContestCandidateStatus.INVITED;

    @Column(name = "invited_at", nullable = false, updatable = false)
    private Instant invitedAt = Instant.now();

    // getters, setters
}
```

**Things to handle:**
- `ContestCandidate` does NOT extend `BaseEntity` — it has its own simple ID + `invitedAt` timestamp. It's a join table, not a first-class domain entity.
- Both `contest` and `candidate` use `FetchType.LAZY` — you never need both in the same query path.

---

### Step 2 — DTOs

Lock these contracts before writing any service logic.

| File | Contents / Purpose |
|---|---|
| `CreateContestRequest` | title, description, startTime (`Instant`), endTime, durationMinutes, allowedLanguages (`List<String>`). Validated with `@NotBlank`, `@NotNull`, `@Future` (startTime must be in the future), `@Size(min = 1)` on allowedLanguages. |
| `UpdateContestRequest` | Same fields as `CreateContestRequest` but all optional (use `@Valid` + nullable fields). Only allowed in `DRAFT` status — enforced in service layer. |
| `ContestResponse` | Summary for list view: id, title, description, startTime, endTime, durationMinutes, allowedLanguages, status, candidateCount (computed). Used for list endpoints. |
| `ContestDetailResponse` | Extended view: everything in `ContestResponse` + the list of assigned candidates (as `List<UserSummaryResponse>`). Used for the detail endpoint. Admins see full detail; Candidates see detail only for their assigned contests. |
| `AssignCandidatesRequest` | `List<UUID> candidateIds` — batch assignment. `@NotEmpty` to prevent empty requests. |
| `AssignCandidatesResult` | assignedCount, alreadyAssignedCount, notFoundCount, `List<UUID> failedIds` — reports per-candidate outcome, not all-or-nothing. |

**Why DTOs first:** the scheduler, service, and controller are all written against these shapes. Lock them now.

---

### Step 3 — Repositories

**File:** `com/codepulse_backend/contest/repository/ContestRepository.java`

```java
public interface ContestRepository extends JpaRepository<Contest, UUID> {

    // Admin view — all contests, paginated + filtered by status
    Page<Contest> findAllByStatusIn(List<ContestStatus> statuses, Pageable pageable);

    // Candidate view — only contests they're assigned to
    @Query("""
        SELECT c FROM Contest c
        JOIN ContestCandidate cc ON cc.contest.id = c.id
        WHERE cc.candidate.id = :candidateId
        AND (:status IS NULL OR c.status = :status)
        """)
    Page<Contest> findAllByCandidateId(
        @Param("candidateId") UUID candidateId,
        @Param("status") ContestStatus status,
        Pageable pageable
    );

    // For the scheduler — find all PUBLISHED contests whose start_time has passed
    List<Contest> findAllByStatusAndStartTimeBefore(ContestStatus status, Instant now);

    // For the scheduler — find all ONGOING contests whose end_time has passed
    List<Contest> findAllByStatusAndEndTimeBefore(ContestStatus status, Instant now);
}
```

**File:** `com/codepulse_backend/contest/repository/ContestCandidateRepository.java`

```java
public interface ContestCandidateRepository extends JpaRepository<ContestCandidate, UUID> {

    // Check if a specific candidate is assigned to a specific contest
    boolean existsByContestIdAndCandidateId(UUID contestId, UUID candidateId);

    // Get all candidates for a contest (admin/evaluator view)
    List<ContestCandidate> findAllByContestId(UUID contestId);

    // Remove all candidates from a contest (used in contest deletion, if supported)
    void deleteAllByContestId(UUID contestId);
}
```

---

### Step 4 — `ContestService`

**File:** `com/codepulse_backend/contest/service/ContestService.java`

The core logic layer. Keep methods single-responsibility:

| Method | Responsibility |
|---|---|
| `createContest(CreateContestRequest, User createdBy)` | Validate time range (`startTime < endTime`), validate `durationMinutes` consistency, persist. Log via `AuditService`. Return `ContestResponse`. |
| `updateContest(UUID id, UpdateContestRequest, User currentUser)` | Only allowed in `DRAFT` status — throw `InvalidStateException` if not DRAFT. Only the creator or Admin can update — throw `AccessDeniedException` otherwise. |
| `publishContest(UUID id, User currentUser)` | Transition `DRAFT → PUBLISHED`. Validate: at least 1 candidate assigned, start time still in the future. Throw `InvalidStateException` if already PUBLISHED/ONGOING/COMPLETED. |
| `getContests(User currentUser, ContestStatus filter, Pageable pageable)` | Role-branching: Admin gets all, Candidate gets only assigned contests. Returns `PagedResponse<ContestResponse>`. |
| `getContestDetail(UUID id, User currentUser)` | Role-based scoping: Admin always gets detail, Candidate only gets detail if assigned. Throw `AccessDeniedException` if unassigned candidate requests. |
| `assignCandidates(UUID contestId, AssignCandidatesRequest, User currentUser)` | Validate each candidate ID exists and has `CANDIDATE` role. Skip duplicates gracefully. Persist `ContestCandidate` rows. Return `AssignCandidatesResult` with a per-candidate outcome summary. |
| `getCandidates(UUID contestId, User currentUser)` | Admin/Evaluator only. Returns list of assigned candidates as `List<UserSummaryResponse>`. |
| `transitionToOngoing(Contest contest)` | Internal — called by the scheduler. Flips `PUBLISHED → ONGOING`. |
| `transitionToCompleted(Contest contest)` | Internal — called by the scheduler. Flips `ONGOING → COMPLETED`. |

**Key rule on object-level authorization:** for `updateContest`, `publishContest`, `assignCandidates`, and `getContestDetail`, verify ownership or role at the service layer, not just the controller. The `@PreAuthorize` annotation at the controller level checks role (Admin vs Candidate) — but it cannot check whether a specific Candidate is assigned to a specific Contest. That check must be explicit code in the service method.

---

### Step 5 — `ContestSchedulerService`

**File:** `com/codepulse_backend/contest/service/ContestSchedulerService.java`

This is one of the most important parts of the module. It removes human dependency from the contest lifecycle.

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ContestSchedulerService {

    private final ContestRepository contestRepository;
    private final ContestService contestService;

    // Runs every minute — check for PUBLISHED contests whose start_time has passed
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void transitionPublishedToOngoing() {
        List<Contest> contests = contestRepository
            .findAllByStatusAndStartTimeBefore(ContestStatus.PUBLISHED, Instant.now());

        contests.forEach(contest -> {
            contestService.transitionToOngoing(contest);
            log.info("Contest {} transitioned PUBLISHED -> ONGOING", contest.getId());
        });
    }

    // Runs every minute — check for ONGOING contests whose end_time has passed
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void transitionOngoingToCompleted() {
        List<Contest> contests = contestRepository
            .findAllByStatusAndEndTimeBefore(ContestStatus.ONGOING, Instant.now());

        contests.forEach(contest -> {
            contestService.transitionToCompleted(contest);
            log.info("Contest {} transitioned ONGOING -> COMPLETED", contest.getId());
        });
    }
}
```

**Things to handle:**
- Add `@EnableScheduling` to `CodepulseBackendApplication.java` (or a dedicated `@Configuration` class). Without this, `@Scheduled` is silently ignored.
- `fixedDelay = 60_000` runs the method 60 seconds after the previous invocation finishes (not a fixed rate) — this prevents pile-up if the job takes longer than 60s under load.
- **Test this early** with a short-duration contest (start_time = now + 1 minute, end_time = now + 2 minutes). Don't wait until demo week to discover the scheduler isn't firing.
- The scheduler does NOT trigger `SessionExpiryScheduler` (Module 6) — that's a separate scheduled job in its own module. But note that when a contest transitions to `COMPLETED`, Module 6's sessions for that contest should also be force-submitted. Plan for this coupling now — a `ContestCompletedEvent` (Spring's `ApplicationEvent`) published here and consumed there is the clean decoupling pattern.

---

### Step 6 — `ContestController`

**File:** `com/codepulse_backend/contest/controller/ContestController.java`

Every response is wrapped in `ApiResponse<T>`. Every admin-only method has `@PreAuthorize`.

```java
@RestController
@RequestMapping("/api/contests")
@RequiredArgsConstructor
public class ContestController {

    private final ContestService contestService;

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ContestResponse>>> listContests(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) ContestStatus status,
            Pageable pageable) { ... }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ContestResponse>> createContest(
            @Valid @RequestBody CreateContestRequest request,
            @AuthenticationPrincipal UserDetails userDetails) { ... }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ContestDetailResponse>> getContest(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) { ... }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ContestResponse>> updateContest(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateContestRequest request,
            @AuthenticationPrincipal UserDetails userDetails) { ... }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ContestResponse>> publishContest(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) { ... }

    @PostMapping("/{id}/candidates")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AssignCandidatesResult>> assignCandidates(
            @PathVariable UUID id,
            @Valid @RequestBody AssignCandidatesRequest request,
            @AuthenticationPrincipal UserDetails userDetails) { ... }

    @GetMapping("/{id}/candidates")
    @PreAuthorize("hasRole('ADMIN') or hasRole('EVALUATOR')")
    public ResponseEntity<ApiResponse<List<UserSummaryResponse>>> getCandidates(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) { ... }
}
```

**API table (for your report):**

| Method | Endpoint | Description | Access |
|---|---|---|---|
| GET | `/api/contests` | Paginated list (role-scoped) | All authenticated |
| POST | `/api/contests` | Create new contest | Admin |
| GET | `/api/contests/{id}` | Contest detail (role-scoped) | All (scoped) |
| PUT | `/api/contests/{id}` | Update contest (DRAFT only) | Admin |
| POST | `/api/contests/{id}/publish` | DRAFT → PUBLISHED | Admin |
| POST | `/api/contests/{id}/candidates` | Batch-assign candidates | Admin |
| GET | `/api/contests/{id}/candidates` | List assigned candidates | Admin / Evaluator |

---

### Step 7 — Configuration

**In `CodepulseBackendApplication.java` or a `@Configuration` class:**
```java
@EnableScheduling
```

**In `application-local.yml`:** no new keys needed unless you want to make the scheduler interval configurable:
```yaml
contest:
  scheduler:
    fixed-delay-ms: 60000   # optional — externalize if you want to tweak per profile
```

**In `application-local.yml`:** ensure `multipart.max-file-size` is already set from Module 2's CSV import. Nothing new needed here.

---

### Step 8 — `StringListConverter` (shared utility)

**File:** `com/codepulse_backend/common/converter/StringListConverter.java`

```java
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) return "[]";
        try {
            return mapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize list to JSON", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return new ArrayList<>();
        try {
            return mapper.readValue(dbData, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize JSON to list", e);
        }
    }
}
```

Put this in `common/converter/` — it is reused by Questions module for `allowed_languages` and potentially by Test Case module for tag lists.

---

### Step 9 — Tests

| Test | Covers |
|---|---|
| `ContestServiceTest` | Create happy path, time-range validation (end before start → reject), update on non-DRAFT contest → `InvalidStateException`, publish with no candidates → `InvalidStateException`, assignCandidates with non-existent ID → graceful skip, duplicate assignment → skipped not error |
| `ContestSchedulerServiceTest` | Scheduler moves PUBLISHED to ONGOING when `startTime` has passed, moves ONGOING to COMPLETED when `endTime` has passed, does NOT move if time hasn't passed |
| `ContestControllerTest` (MockMvc) | Non-Admin `POST /api/contests` → 403, Candidate `GET /api/contests/{id}` for unassigned contest → 403 or 404, Admin `GET /api/contests` → sees all, Candidate `GET /api/contests` → sees only assigned |
| `StringListConverterTest` | Round-trip: `List<String>` → JSON → `List<String>` is lossless. Null input handled. Empty list handled. |

---

## 6. Sequence Summary (Build in This Order)

1. Add `ContestStatus` and `ContestCandidateStatus` enums to `common/enums`
2. Write `V5__create_contest_tables.sql` migration and verify it applies cleanly
3. Build `StringListConverter` in `common/converter`
4. Build `Contest` entity + `ContestCandidate` entity
5. Build `ContestRepository` + `ContestCandidateRepository`
6. Lock all DTOs before touching service logic
7. Build `ContestService` — CRUD methods first, then assign/publish
8. Add `@EnableScheduling` to config, build `ContestSchedulerService`
9. Build `ContestController` with `@PreAuthorize` role checks
10. Manually test: create → publish → verify scheduler transitions with a 2-minute contest window
11. Write tests

---

## 7. Definition of Done

- [ ] Admin can create a contest, and it appears in `GET /api/contests` with `DRAFT` status
- [ ] Admin can assign 3+ candidates by UUID batch and `GET /api/contests/{id}/candidates` returns them
- [ ] Admin can publish a contest (DRAFT → PUBLISHED) and it is visible to assigned candidates
- [ ] Contest auto-transitions to `ONGOING` within 60 seconds of `startTime` — verified with a short test window
- [ ] Contest auto-transitions to `COMPLETED` within 60 seconds of `endTime`
- [ ] Candidate calling `GET /api/contests` sees only their assigned contests — verified explicitly
- [ ] Candidate calling `GET /api/contests/{id}` for an unassigned contest gets `403` — verified explicitly
- [ ] Non-Admin calling `POST /api/contests` gets `403` — verified explicitly
- [ ] Assigning a candidate who is already assigned returns a partial success result (not an error for the whole batch)
- [ ] `StringListConverter` round-trip is lossless

---

## 8. Package Structure After This Module

```
com.codepulse_backend
├── auth/                    (from Module 1 — untouched)
├── common/
│   ├── converter/
│   │   └── StringListConverter.java    ← NEW
│   └── enums/
│       ├── Role.java
│       ├── ContestStatus.java          ← NEW
│       └── ContestCandidateStatus.java ← NEW
├── config/
│   └── SecurityConfig.java             (add @EnableScheduling here or in main class)
├── contest/                            ← NEW PACKAGE
│   ├── controller/
│   │   └── ContestController.java
│   ├── dto/
│   │   ├── CreateContestRequest.java
│   │   ├── UpdateContestRequest.java
│   │   ├── ContestResponse.java
│   │   ├── ContestDetailResponse.java
│   │   ├── AssignCandidatesRequest.java
│   │   └── AssignCandidatesResult.java
│   ├── entity/
│   │   ├── Contest.java
│   │   └── ContestCandidate.java
│   ├── repository/
│   │   ├── ContestRepository.java
│   │   └── ContestCandidateRepository.java
│   └── service/
│       ├── ContestService.java
│       └── ContestSchedulerService.java
└── user/                    (from Modules 1 & 2 — untouched)
```

---

## 9. What Module 4 Needs From You

When you move to Module 4 (Question Management), the following must already work:
- `ContestRepository.findById()` — Question module does an existence check before creating a question under a contest
- `ContestService.getContestDetail()` — reused for the question list endpoint scoped to a contest
- `ContestCandidateRepository.existsByContestIdAndCandidateId()` — used by Question module to gate candidate access to questions (candidate must be assigned to the contest)
- `ContestStatus.ONGOING` — Question module uses this to gate candidate reads (can only view questions during an ONGOING contest)
- `StringListConverter` — Question module reuses it for language/tag lists

---

## 10. Things That Can Go Wrong — Watch Out For These

| Pitfall | What to do |
|---|---|
| `@EnableScheduling` missing | Scheduler methods are silently ignored — both `@Scheduled` methods do nothing. Verify by adding a one-time log on startup. |
| Scheduler running on every node in production | If you ever deploy multiple instances, the scheduler runs on all of them simultaneously, causing double-transitions. Not a problem for single-node student deployment, but worth noting. Use a distributed lock (ShedLock) if this becomes multi-node. |
| `TransactionRequiredException` in scheduler | Each scheduler method needs its own `@Transactional` — don't assume the outer `ContestService` method's transaction propagates into the scheduler. |
| `DataIntegrityViolationException` on double-assign | The `UNIQUE (contest_id, candidate_id)` DB constraint will throw this if you try to insert a duplicate. Catch it in `ContestService.assignCandidates()` per row and add to the `failedIds` list instead of aborting the whole batch. |
| Candidate sees DRAFT/COMPLETED contests | The `findAllByCandidateId` query must also filter by status — a Candidate's contest list should only show PUBLISHED + ONGOING by default unless explicitly filtered. Apply a default status filter in `ContestService.getContests()` for the CANDIDATE role path. |
| Empty `allowedLanguages` stored as `null` | Ensure `StringListConverter` handles `null` gracefully and your `CreateContestRequest` validates `@Size(min = 1)` to prevent an empty list reaching the DB. |

---

## 11. Future Enhancements (Out of Scope for This Module)

- Contest templates/duplication — clone a fully-configured contest with all its questions and test cases into a new DRAFT
- Public contest links with self-registration — open enrollment without Admin assignment
- Per-contest proctoring configuration (webcam required, tab-switch detection enabled) — stored as a JSON config column, consumed by Module 13 (Proctoring)
- `ContestCompletedEvent` published to trigger Module 6's session auto-submission — deferred to Module 6 but plan the event shape now
