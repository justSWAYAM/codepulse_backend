# CodePulse Enterprise — Module 5 Backend Build Plan
### Test Case Service

**Purpose:** Test cases are the ground truth used to grade every submission. This module lets Admins attach input/expected-output pairs to a Question, marked sample (visible to candidates before they submit) or hidden (server-side only, used for real scoring). It also completes the two-DTO security boundary that Module 4 deliberately left open slots for — `QuestionAdminResponse.testCases` and `QuestionCandidateResponse.sampleTestCases` — so this module's most important job is closing that loop correctly, not just building CRUD.

**Depends on:** Module 0 (Cross-Cutting Foundation) + Module 1 (Authentication) + Module 2 (User Management) + Module 3 (Contest Management) + Module 4 (Question Management) — all must be functionally complete. You need an existing Question to attach test cases to, and Module 4's two response DTOs must already exist so you can extend them rather than redesign them.

---

## 1. What This Module Inherits (Do Not Rebuild)

| From | Component | How it's used here |
|---|---|---|
| Module 0 | `BaseEntity` | `TestCase` extends this — id (UUID), createdAt, updatedAt, createdBy, updatedBy |
| Module 0 | `ApiResponse<T>` | Every endpoint wraps its response — no raw returns |
| Module 0 | `GlobalExceptionHandler` | Handles all exceptions this module throws — no new handler needed |
| Module 0 | Exception hierarchy | `ResourceNotFoundException` (question/test case not found), `AccessDeniedException` (candidate outside window or unassigned) |
| Module 0 | `AuditService` | Log test case creation and deletion — admin authoring trail |
| Module 0 | `Role` enum | `ADMIN`, `EVALUATOR`, `CANDIDATE` — access branching |
| Module 1 | `SecurityConfig` | Already has `@EnableMethodSecurity` — use `@PreAuthorize` on write endpoints |
| Module 1 | `JwtAuthenticationFilter` | Untouched — all endpoints sit behind auth |
| Module 2 | `CsvImportService` (generic) | Reused as-is for bulk test case upload — this is the exact reuse case Module 2's plan flagged when it said "Module 4 reuses the exact same pattern" (it actually meant this module; see note in Section 2.2) |
| Module 3 | `ContestCandidateRepository` | `existsByContestIdAndCandidateId` — candidate assignment check, same call Module 4 makes |
| Module 3 | `ContestStatus` enum | Candidate can only read sample test cases when the parent contest is `ONGOING` |
| Module 4 | `Question` entity + `QuestionRepository` | Test cases must belong to an existing question; existence check before create |
| Module 4 | `QuestionAdminResponse`, `QuestionCandidateResponse` | **Extended, not rebuilt** — this module adds the fields Module 4 explicitly reserved for it (see Section 8) |
| Module 4 | `QuestionService.toAdminResponse()` / `toCandidateResponse()` | The two mapper methods Module 4 named as "the single injection points" — this module modifies them, nothing else in Module 4 |

**Blocker check:** open `QuestionAdminResponse.java` and `QuestionCandidateResponse.java` before writing anything. If the commented placeholders (`// Module 5 will add: ...`) aren't there, Module 4 wasn't finished as planned — fix that first, don't improvise a third DTO shape here.

---

## 2. Decisions to Make Before Writing Any Code

### 2.1 Bulk upload format: CSV vs. zip of file pairs
The roadmap allows either. Use **CSV** as the only supported format for this module:

- One row per test case: `input,expected_output,is_sample,weight`.
- Reuses `CsvImportService` exactly as built for Module 2's user import — no new generic infrastructure needed.
- A zip-of-file-pairs format (common in real judges, useful for huge inputs) is a real future need but a different upload pipeline entirely. Don't build both now — note it in Future Enhancements and move on.

### 2.2 Where `CsvImportService` actually lives
Module 2's plan describes `CsvImportService` as a shared/common-package utility designed generically "because Module 4 reuses the exact same pattern." That forward reference was written before question/test-case scope was finalized — **this module is the real second consumer**, not Module 4 (Question Management has no bulk-import requirement in the roadmap). If `CsvImportService` was built with only `User`-shaped assumptions baked in, generalize it now rather than duplicating it — that's the whole point of building it generic in Module 2.

### 2.3 Candidates never see `weight`
The roadmap says candidates should see sample input/output only — not even a hint at scoring. So `TestCaseSampleResponse` excludes `weight` as well as `expectedOutput`. Don't add "just the weight, not the answer" as a compromise; leaking relative weight lets candidates infer which hidden cases matter most.

### 2.4 `order_index` scope
Test case order only matters for display consistency (sample cases shown to a candidate in a stable order). Unlike Module 4's question ordering, don't enforce a `UNIQUE(question_id, order_index)` constraint — it adds friction (reorder-on-delete bookkeeping) for a field with no functional consequence. Default to `MAX(order_index) + 1` on create and leave gaps alone.

### 2.5 `weight` validation
Don't enforce "weights across a question's test cases must sum to 100" at the database or service layer for this module — `ScoringService` (Module 8) is what actually turns weights into a score, and it can normalize however it wants (sum-to-100, sum-to-points, etc.). Enforcing a specific total here would hardcode Module 8's scoring model into Module 5. Just validate `weight >= 0` on create.

---

## 3. Database Migration

**File:** `src/main/resources/db/migration/V7__create_test_case_table.sql`

> Note: V6 is the `questions` table from Module 4. This is V7.

```sql
CREATE TABLE test_cases (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id       UUID         NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    input             TEXT         NOT NULL,
    expected_output   TEXT         NOT NULL,
    is_sample         BOOLEAN      NOT NULL DEFAULT false,
    weight            INT          NOT NULL DEFAULT 0 CHECK (weight >= 0),
    order_index       INT          NOT NULL DEFAULT 0,
    created_by        UUID         NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by        UUID         REFERENCES users(id)
);

-- Fast fetch of every test case for admin/grading paths
CREATE INDEX idx_test_cases_question_id ON test_cases(question_id, order_index ASC);

-- Partial index backing the candidate-facing query specifically —
-- this is the index that makes "never fetch all test cases in a candidate-facing
-- code path" (Module 4's rule, inherited here) fast as well as correct.
CREATE INDEX idx_test_cases_question_sample ON test_cases(question_id) WHERE is_sample = true;
```

**`ON DELETE CASCADE` on `question_id`:** deleting a question removes its test cases. This matches Module 4's own `contest_id` cascade — orphaned test cases are meaningless, same reasoning.

**Why `test_cases` gets full `BaseEntity` columns even though the roadmap's schema sketch lists fewer:** the roadmap's per-module schema tables are minimal sketches, not final DDL — Modules 3 and 4 both expanded theirs to include the audit columns every other entity in the system has (`created_by`/`updated_by`/`updated_at`), for the same "who did what" audit-trail narrative Module 0 established. Do the same here for consistency; don't special-case this one table.

---

## 4. Java Package Structure

All new code lives under `com.codepulse_backend.testcase`. Do not put test-case classes inside the `question` package, even though they're tightly coupled — same separation Module 4 kept from `contest`.

```
com.codepulse_backend/
└── testcase/
    ├── entity/
    │   └── TestCase.java
    ├── repository/
    │   └── TestCaseRepository.java
    ├── dto/
    │   ├── CreateTestCaseRequest.java
    │   ├── TestCaseAdminResponse.java
    │   ├── TestCaseSampleResponse.java
    │   ├── TestCaseCsvRow.java
    │   └── TestCaseBulkUploadResult.java
    ├── service/
    │   ├── TestCaseService.java
    │   └── TestCaseBulkUploadService.java
    └── controller/
        ├── TestCaseController.java
        └── TestCaseAdminController.java
```

Two controllers, not one — the roadmap's API table has one endpoint (`DELETE /api/test-cases/{id}`) that doesn't nest under `/api/questions/{questionId}/...` like the rest. Forcing it into `TestCaseController`'s `@RequestMapping` would require an awkward full-path override on a single method; a small second controller is cleaner and mirrors how the roadmap itself lists it as a separate row.

---

## 5. Entity

### `TestCase.java`

```java
package com.codepulse_backend.testcase.entity;

import com.codepulse_backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.util.UUID;

@Entity
@Table(name = "test_cases")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TestCase extends BaseEntity {

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String input;

    @Column(name = "expected_output", columnDefinition = "TEXT", nullable = false)
    private String expectedOutput;

    @Column(name = "is_sample", nullable = false)
    private boolean isSample;

    @Column(nullable = false)
    private int weight;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;
}
```

**Key design choice:** `questionId` is a plain `UUID` column, not a `@ManyToOne` — same reasoning Module 4 gave for `Question.contestId`: avoids lazy-loading pitfalls, and the service does an explicit `questionRepository.existsById(questionId)` check instead.

---

## 6. Repository

### `TestCaseRepository.java`

```java
package com.codepulse_backend.testcase.repository;

import com.codepulse_backend.testcase.entity.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {

    // Admin / grading path — every test case, sample and hidden.
    // Only call this from Admin-facing code or Module 8's grading flow.
    List<TestCase> findByQuestionIdOrderByOrderIndexAsc(UUID questionId);

    // Candidate-facing path — sample only. This is the one rule that
    // matters most in this whole module: never let a candidate-facing
    // service method call the method above.
    List<TestCase> findByQuestionIdAndIsSampleTrueOrderByOrderIndexAsc(UUID questionId);

    long countByQuestionId(UUID questionId);
}
```

---

## 7. DTOs

### `CreateTestCaseRequest.java`

```java
package com.codepulse_backend.testcase.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateTestCaseRequest(
    @NotBlank(message = "Input is required") String input,
    @NotBlank(message = "Expected output is required") String expectedOutput,
    boolean isSample,
    @Min(value = 0, message = "Weight cannot be negative") int weight
) {}
```

### `TestCaseAdminResponse.java`

```java
package com.codepulse_backend.testcase.dto;

import java.util.UUID;

// Full shape — Admin/Evaluator only. Embedded into QuestionAdminResponse.testCases.
public record TestCaseAdminResponse(
    UUID id,
    String input,
    String expectedOutput,
    boolean isSample,
    int weight,
    int orderIndex
) {}
```

### `TestCaseSampleResponse.java`

```java
package com.codepulse_backend.testcase.dto;

import java.util.UUID;

// Candidate-facing shape. No expectedOutput, no weight — a candidate should
// see input and their own actual output after a Run, nothing else. This is
// a separate record, not TestCaseAdminResponse with fields nulled out or
// @JsonIgnore'd, for the exact reason Module 4 gave for its own two DTOs:
// that pattern has a track record of accidental exposure in refactors.
public record TestCaseSampleResponse(
    UUID id,
    String input,
    int orderIndex
) {}
```

### `TestCaseCsvRow.java`

```java
package com.codepulse_backend.testcase.dto;

// Raw parsed shape of one CSV row, before validation/creation.
// Column order: input, expected_output, is_sample, weight
public record TestCaseCsvRow(
    String input,
    String expectedOutput,
    boolean isSample,
    int weight
) {}
```

### `TestCaseBulkUploadResult.java`

```java
package com.codepulse_backend.testcase.dto;

import com.codepulse_backend.common.csv.RowError;
import java.util.List;

// Same shape as Module 2's BulkImportResult, reusing the shared RowError
// type rather than redefining rowNumber/reason locally.
public record TestCaseBulkUploadResult(
    int totalRows,
    int succeededCount,
    int failedCount,
    List<RowError> errors
) {}
```

---

## 8. Modifying Module 4's Files (the part this module exists to do)

This is the step it's easiest to treat as an afterthought and easiest to get wrong. Do it deliberately, as its own step, not folded into Section 9's service work.

### 8.1 Extend `QuestionAdminResponse.java`

Replace the placeholder comment with the real field:

```java
public record QuestionAdminResponse(
    UUID id,
    UUID contestId,
    String title,
    String description,
    Difficulty difficulty,
    int points,
    int timeLimitMs,
    int memoryLimitKb,
    int orderIndex,
    Instant createdAt,
    UUID createdBy,
    List<TestCaseAdminResponse> testCases   // ← was the Module 5 placeholder
) {}
```

### 8.2 Extend `QuestionCandidateResponse.java`

```java
public record QuestionCandidateResponse(
    UUID id,
    UUID contestId,
    String title,
    String description,
    Difficulty difficulty,
    int points,
    int timeLimitMs,
    int memoryLimitKb,
    int orderIndex,
    List<TestCaseSampleResponse> sampleTestCases   // ← was the Module 5 placeholder
) {}
```

### 8.3 Update `QuestionService`'s two mapper methods

```java
// QuestionService.java — add a TestCaseService dependency, then:

private QuestionAdminResponse toAdminResponse(Question q) {
    List<TestCaseAdminResponse> testCases =
        testCaseService.getAllTestCasesForEmbedding(q.getId());
    return new QuestionAdminResponse(
        q.getId(), q.getContestId(), q.getTitle(), q.getDescription(),
        q.getDifficulty(), q.getPoints(), q.getTimeLimitMs(), q.getMemoryLimitKb(),
        q.getOrderIndex(), q.getCreatedAt(), q.getCreatedBy(),
        testCases
    );
}

private QuestionCandidateResponse toCandidateResponse(Question q) {
    List<TestCaseSampleResponse> sampleTestCases =
        testCaseService.getSampleTestCasesForEmbedding(q.getId());
    return new QuestionCandidateResponse(
        q.getId(), q.getContestId(), q.getTitle(), q.getDescription(),
        q.getDifficulty(), q.getPoints(), q.getTimeLimitMs(), q.getMemoryLimitKb(),
        q.getOrderIndex(),
        sampleTestCases
    );
}
```

**Dependency direction — avoid a cycle:** `QuestionService` now depends on `TestCaseService`. `TestCaseService` must depend only on `QuestionRepository` (for the existence check), never on `QuestionService` — if it needs question data, it goes straight to the repository. Wiring it the other way round creates a Spring circular-dependency startup failure.

No other Module 4 file changes. The controller, entity, repository, and request DTOs from Module 4 are untouched.

---

## 9. Service

### `TestCaseService.java` (method signatures and logic outline)

```java
package com.codepulse_backend.testcase.service;

public TestCaseAdminResponse createTestCase(UUID questionId, CreateTestCaseRequest request)
public void deleteTestCase(UUID testCaseId)
public Object getTestCasesForQuestion(UUID questionId)   // role-dependent: List<TestCaseAdminResponse> or List<TestCaseSampleResponse>
public TestCaseBulkUploadResult bulkUploadTestCases(UUID questionId, MultipartFile file)

// Internal helpers — called only from QuestionService's mappers (Section 8),
// never exposed via a controller. No auth check inside these two: the caller
// (QuestionService) has already resolved the correct role branch before calling.
public List<TestCaseAdminResponse> getAllTestCasesForEmbedding(UUID questionId)
public List<TestCaseSampleResponse> getSampleTestCasesForEmbedding(UUID questionId)
```

**Access control logic inside `getTestCasesForQuestion`** (this is the one publicly-exposed method that does its own role check, mirroring `QuestionService.getQuestions` from Module 4):

```
question = questionRepository.findById(questionId) or throw ResourceNotFoundException

if (currentUser.role == CANDIDATE) {
    check contestCandidateRepository.existsByContestIdAndCandidateId(question.contestId, currentUser.id) → 403 if false
    check contest.status == ONGOING → 403 if not
    return testCaseRepository.findByQuestionIdAndIsSampleTrueOrderByOrderIndexAsc(questionId)
             .map(this::toSampleResponse)
} else {
    return testCaseRepository.findByQuestionIdOrderByOrderIndexAsc(questionId)
             .map(this::toAdminResponse)
}
```

**`createTestCase`:**
1. `questionRepository.existsById(questionId)` → `ResourceNotFoundException` if not found.
2. Compute `orderIndex = testCaseRepository.countByQuestionId(questionId) + 1`.
3. Persist, log via `AuditService` (`TEST_CASE_CREATED`).
4. Return `TestCaseAdminResponse` — never the entity, never through the candidate mapper.

**`deleteTestCase`:** straightforward lookup-or-404, then delete, then audit log (`TEST_CASE_DELETED`). No question-scoping needed here since the ID is globally unique and Admin-only.

### `TestCaseBulkUploadService.java`

Wraps `CsvImportService<TestCaseCsvRow, TestCase>`:
- **Row parser:** raw CSV line → `TestCaseCsvRow` (parse `is_sample` as `true`/`false`, `weight` as int; a malformed boolean or non-numeric weight is a row failure, not a whole-file failure).
- **Per-row creation function:** delegates to the *same* `createTestCase()` path used by the single-create endpoint — don't duplicate validation or order-index logic here, exactly as Module 2's plan insists for its own bulk import.
- Each row wrapped in its own try/catch by `CsvImportService` itself; `TestCaseBulkUploadService` just maps the generic result into `TestCaseBulkUploadResult`.

---

## 10. Controllers

### `TestCaseController.java`

```java
@RestController
@RequestMapping("/api/questions/{questionId}/test-cases")
@RequiredArgsConstructor
public class TestCaseController {

    @GetMapping
    public ApiResponse<Object> getTestCases(@PathVariable UUID questionId)
    // role branching happens inside the service, same pattern as Module 4's question GET

    @PostMapping                      // @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TestCaseAdminResponse> createTestCase(
        @PathVariable UUID questionId,
        @Valid @RequestBody CreateTestCaseRequest request)

    @PostMapping("/bulk")             // @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<TestCaseBulkUploadResult> bulkUpload(
        @PathVariable UUID questionId,
        @RequestParam("file") MultipartFile file)
}
```

### `TestCaseAdminController.java`

```java
@RestController
@RequestMapping("/api/test-cases")
@RequiredArgsConstructor
public class TestCaseAdminController {

    @DeleteMapping("/{id}")           // @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteTestCase(@PathVariable UUID id)
}
```

---

## 11. API Reference

| Method | Endpoint | Description | Access |
|---|---|---|---|
| `GET` | `/api/questions/{questionId}/test-cases` | List (role-scoped: admin/evaluator see all, candidate sees sample only) | Role-dependent |
| `POST` | `/api/questions/{questionId}/test-cases` | Create a test case | Admin only |
| `POST` | `/api/questions/{questionId}/test-cases/bulk` | Bulk upload via CSV | Admin only |
| `DELETE` | `/api/test-cases/{id}` | Delete | Admin only |

---

## 12. Security Rules Summary

| Caller | Condition | Result |
|---|---|---|
| `ADMIN` | Any question | Full `TestCaseAdminResponse` list on `GET`; all write endpoints allowed |
| `EVALUATOR` | Any question | Full `TestCaseAdminResponse` list (read-only; `@PreAuthorize` blocks the three write endpoints) |
| `CANDIDATE` | Assigned to the question's contest AND contest is `ONGOING` | `TestCaseSampleResponse` list — sample cases only, no `expectedOutput`, no `weight` |
| `CANDIDATE` | Not assigned to the contest | `403 AccessDeniedException` |
| `CANDIDATE` | Contest not `ONGOING` | `403 AccessDeniedException` |

---

## 13. Build Sequence

Follow this order strictly.

1. **Write `V7__create_test_case_table.sql`** and start the app — verify Flyway applies it cleanly.
2. **Check `CsvImportService`** for generic reusability (Section 2.2) — generalize now if Module 2 left it `User`-specific.
3. **Implement `TestCase.java`** entity.
4. **Implement `TestCaseRepository.java`** with both query methods — write a quick manual check that the sample-only query genuinely excludes hidden rows before moving on.
5. **Implement all five DTOs** (`CreateTestCaseRequest`, `TestCaseAdminResponse`, `TestCaseSampleResponse`, `TestCaseCsvRow`, `TestCaseBulkUploadResult`).
6. **Implement `TestCaseService.java`** — `createTestCase` and `getTestCasesForQuestion` first, then `deleteTestCase`, then the two embedding helpers.
7. **Implement `TestCaseBulkUploadService.java`** on top of `CsvImportService`.
8. **Implement `TestCaseController.java`** and `TestCaseAdminController.java`.
9. **Now do Section 8** — extend `QuestionAdminResponse`, `QuestionCandidateResponse`, and `QuestionService`'s two mappers. Restart and confirm the app still boots (this is where a circular dependency, if introduced, will surface immediately).
10. **Test each endpoint** in order — see Definition of Done below.
11. **Verify the DTO boundary end-to-end**, not just at the test-case endpoint: call `GET /api/questions/{questionId}` (Module 4's own endpoint) with a Candidate JWT and confirm the raw JSON's `sampleTestCases` array has no `expectedOutput` or `weight` key anywhere, and that hidden test cases don't appear in the array at all.

---

## 14. Tests

| Test | Covers |
|---|---|
| `TestCaseServiceTest` | Create happy path, negative weight rejected, delete happy path, `getTestCasesForQuestion` returns all for Admin vs. sample-only for Candidate |
| `TestCaseBulkUploadServiceTest` | Valid file, file with a malformed `is_sample`/`weight` value, file with a blank `input` row — assert partial success + row-level errors, not all-or-nothing failure |
| `TestCaseControllerTest` (MockMvc) | Non-Admin `POST`/`DELETE` → 403; Candidate `GET` on unassigned contest → 403; Candidate `GET` on non-`ONGOING` contest → 403 |
| `QuestionServiceIntegrationTest` (extends Module 4's) | `QuestionAdminResponse.testCases` contains hidden cases with `expectedOutput` populated; `QuestionCandidateResponse.sampleTestCases` never contains a hidden case and never contains an `expectedOutput` field, verified against raw serialized JSON, not just the Java object |

---

## 15. Definition of Done

- [ ] `V7__create_test_case_table.sql` applies cleanly with no Flyway errors
- [ ] Admin can add a test case to a question, marked sample or hidden, and it appears in the Admin-facing list
- [ ] A question has a mix of sample and hidden test cases
- [ ] Bulk CSV upload correctly creates N test cases and returns a per-row error report for malformed rows, without aborting the whole file on one bad row
- [ ] `GET /api/questions/{questionId}/test-cases` returns full data for Admin/Evaluator and sample-only (no `expectedOutput`, no `weight`) for Candidate
- [ ] `QuestionAdminResponse.testCases` and `QuestionCandidateResponse.sampleTestCases` are both populated correctly through the Module 4 endpoints, not just the Module 5 endpoints
- [ ] API contract test confirms hidden test case data never appears in any candidate-accessible response, in either module's endpoints
- [ ] Candidate not assigned to the contest → `403`
- [ ] Candidate on a non-`ONGOING` contest → `403`
- [ ] Non-Admin `POST`/`DELETE` on test-case endpoints → `403`
- [ ] `audit_logs` shows a row for each test case creation and deletion

---

## 16. Package Structure After This Module

```
com.codepulse_backend
├── auth/                    (from Module 1 — untouched)
├── common/
│   ├── converter/
│   │   └── StringListConverter.java    (from Module 3 — untouched)
│   ├── csv/
│   │   ├── CsvImportService.java       (from Module 2 — generalized if needed)
│   │   └── RowError.java               (from Module 2 — reused, not redefined)
│   └── enums/
│       ├── Role.java
│       ├── ContestStatus.java
│       ├── ContestCandidateStatus.java
│       └── Difficulty.java
├── contest/                 (from Module 3 — untouched)
├── question/                (from Module 4 — extended: two DTOs + two mapper methods, see Section 8)
├── testcase/                ← NEW PACKAGE
│   ├── controller/
│   │   ├── TestCaseController.java
│   │   └── TestCaseAdminController.java
│   ├── dto/
│   │   ├── CreateTestCaseRequest.java
│   │   ├── TestCaseAdminResponse.java
│   │   ├── TestCaseSampleResponse.java
│   │   ├── TestCaseCsvRow.java
│   │   └── TestCaseBulkUploadResult.java
│   ├── entity/
│   │   └── TestCase.java
│   ├── repository/
│   │   └── TestCaseRepository.java
│   └── service/
│       ├── TestCaseService.java
│       └── TestCaseBulkUploadService.java
└── user/                    (from Modules 1 & 2 — untouched)
```

---

## 17. What Later Modules Need From You

Module 6 (Assessment Session) has no direct dependency on this module — it only needs Modules 2 and 3. The real downstream consumers are further out:

- **Module 7 (Judge0 Execution):** will call `TestCaseRepository.findByQuestionIdOrderByOrderIndexAsc()` internally (via `TestCaseService`, not the repository directly — keep the layering) to get every input/expected-output pair to run candidate code against during a real "Submit."
- **Module 8 (Submission Service):** `submission_test_case_results.test_case_id` is a direct FK to this module's `test_cases.id` — that table cannot be built until this one exists. `ScoringService` will read `TestCase.weight` to compute a weighted score; it must not assume weights sum to any particular total (Section 2.5).

---

## 18. Things That Can Go Wrong — Watch Out For These

| Pitfall | What to do |
|---|---|
| Candidate-facing code accidentally calls `findByQuestionIdOrderByOrderIndexAsc` | This is the one bug that actually defeats the platform's purpose. Grep for that method name before shipping and confirm every call site is Admin/Evaluator-only or Module 7's internal grading path. |
| `QuestionCandidateResponse` built with `@JsonIgnore` instead of a separate field set | Don't do this even under time pressure — it's exactly the pattern Module 4 called out as having "a track record of accidental field exposure in refactors." |
| Circular dependency between `QuestionService` and `TestCaseService` | Keep it one-directional (Section 8.3). If `TestCaseService` ever seems to need something from `QuestionService`, get it from `QuestionRepository` instead. |
| Bulk upload treats one bad row as a whole-file failure | `CsvImportService`'s per-row try/catch (built in Module 2) must actually be used — don't wrap the whole file parse in a single try/catch inside `TestCaseBulkUploadService`. |
| `weight` exposed anywhere in `TestCaseSampleResponse` or in the embedded `sampleTestCases` list | Re-check the record definition, not just the mapping code — a stray field added later during a refactor is easy to miss since it doesn't throw anything. |
| Forgetting Section 8 entirely and only building the standalone `/api/questions/{id}/test-cases` endpoints | The module's Definition of Done explicitly checks the Module 4 endpoints too — a reviewer testing only the new endpoints would miss this. |

---

## 19. Future Enhancements (Out of Scope for This Module)

- Zip-of-file-pairs bulk upload for large I/O (Section 2.1) — file-reference storage instead of inline `TEXT` columns, for problems whose expected output is megabytes
- Partial-credit weighting UI with live "weights sum to X%" feedback in the Admin question editor (backend intentionally does not enforce this — see Section 2.5)
- Test case versioning / history if a hidden case is edited after candidates have already submitted against it
