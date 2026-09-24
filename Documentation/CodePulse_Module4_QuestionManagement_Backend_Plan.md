# CodePulse Enterprise — Module 4 Backend Build Plan
### Question Management Service

**Purpose:** Questions are the intellectual content of a contest. This module allows Admins to author coding problems (with rich markdown descriptions, difficulty ratings, points, and resource limits) scoped to a specific contest, with a strict two-DTO security boundary that guarantees hidden test case data can never leak into candidate-facing API responses.

**Depends on:** Module 0 (Cross-Cutting Foundation) + Module 1 (Authentication) + Module 2 (User Management) + Module 3 (Contest Management) — all must be functionally complete. You need an existing Contest to attach questions to.

---

## 1. What This Module Inherits (Do Not Rebuild)

| From | Component | How it's used here |
|---|---|---|
| Module 0 | `BaseEntity` | `Question` extends this — id (UUID), createdAt, updatedAt, createdBy, updatedBy |
| Module 0 | `ApiResponse<T>` | Every endpoint wraps its response — no raw returns |
| Module 0 | `PagedResponse<T>` | Used by `GET /api/contests/{contestId}/questions` paginated list |
| Module 0 | `GlobalExceptionHandler` | Handles `ResourceNotFoundException`, `AccessDeniedException`, `InvalidStateException` |
| Module 0 | Exception hierarchy | `ResourceNotFoundException` (question/contest not found), `AccessDeniedException` (candidate outside window) |
| Module 0 | `AuditService` | Log question creation and updates — admin authoring trail |
| Module 0 | `Role` enum | `ADMIN`, `EVALUATOR`, `CANDIDATE` — access branching |
| Module 1 | `SecurityConfig` | Already has `@EnableMethodSecurity` — use `@PreAuthorize` on write endpoints |
| Module 1 | `JwtAuthenticationFilter` | Untouched — all endpoints sit behind auth |
| Module 2 | `User` entity + `UserRepository` | Used in `getCurrentUser()` helper |
| Module 3 | `Contest` entity + `ContestRepository` | Questions must belong to an existing contest |
| Module 3 | `ContestCandidateRepository` | `existsByContestIdAndCandidateId` — candidate assignment check |
| Module 3 | `ContestStatus` enum | Candidate can only read questions when contest is `ONGOING` |

**Blocker check:** `POST /api/contests` (Module 3) must return a valid contest `id` before you can write a question. Run Module 3's DoD checklist before starting here.

---

## 2. Decisions to Make Before Writing Any Code

### 2.1 `description` storage format
Store raw markdown as a `TEXT` column. The backend never processes or renders it. The frontend will render it using `react-markdown`. This gives rich formatting without any backend markdown dependency.

### 2.2 Two-DTO security boundary (CRITICAL — decide before writing any code)
This is the single most important design decision in this module. The split must be established now so Module 5 (Test Cases) doesn't have to retrofit a security boundary later.

- **`QuestionAdminResponse`** — full details including all metadata. In Module 5, this will also include all test cases (sample + hidden).
- **`QuestionCandidateResponse`** — restricted view: no `createdBy`, no `createdAt`, no internal fields. In Module 5, this will include **only sample** test cases with no `expectedOutput`.

The service layer must call a different mapper based on the caller's role. **Do not use a single DTO with `@JsonIgnore`** — that pattern has a track record of accidental field exposure in refactors. Use two completely separate Java record types.

### 2.3 `order_index` management
- On **create**: default to `MAX(order_index) + 1` (i.e., `count + 1`), appending to the end.
- On **reorder**: `PATCH /api/contests/{contestId}/questions/reorder` accepts an ordered list of question IDs and reassigns `order_index` values (1, 2, 3...) in a single transaction.
- No automatic re-compaction on delete — gaps are acceptable since the list sorts by `order_index`.

### 2.4 `difficulty` enum
Java enum `Difficulty { EASY, MEDIUM, HARD }` stored as `VARCHAR(20)`. Validated at the service layer. Lives in `com.codepulse_backend.common.enums`.

---

## 3. Database Migration

**File:** `src/main/resources/db/migration/V6__create_question_table.sql`

> Note: V5 is the contest tables from Module 3. This is V6.

```sql
CREATE TABLE questions (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    contest_id        UUID         NOT NULL REFERENCES contests(id) ON DELETE CASCADE,
    title             VARCHAR(255) NOT NULL,
    description       TEXT         NOT NULL,
    difficulty        VARCHAR(20)  NOT NULL CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    points            INT          NOT NULL DEFAULT 100,
    time_limit_ms     INT          NOT NULL DEFAULT 2000,
    memory_limit_kb   INT          NOT NULL DEFAULT 262144,
    order_index       INT          NOT NULL DEFAULT 0,
    created_by        UUID         NOT NULL REFERENCES users(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by        UUID         REFERENCES users(id)
);

-- Prevents two questions from occupying the same position in a contest
CREATE UNIQUE INDEX ux_questions_contest_order ON questions(contest_id, order_index);

-- Fast lookup of all questions in a contest, sorted
CREATE INDEX idx_questions_contest_id ON questions(contest_id, order_index ASC);
```

**`ON DELETE CASCADE` on `contest_id`:** Deleting a contest automatically removes all its questions. Orphaned questions are meaningless.

**`memory_limit_kb` default = 262144:** That is 256 MB, which is a standard competitive-programming memory limit.

---

## 4. Java Package Structure

All new code lives under `com.codepulse_backend.question`. Do not put question-related classes inside the `contest` package.

```
com.codepulse_backend/
├── common/
│   └── enums/
│       └── Difficulty.java                     ← NEW enum (add here)
└── question/
    ├── entity/
    │   └── Question.java
    ├── repository/
    │   └── QuestionRepository.java
    ├── dto/
    │   ├── CreateQuestionRequest.java
    │   ├── UpdateQuestionRequest.java
    │   ├── ReorderQuestionsRequest.java
    │   ├── QuestionAdminResponse.java
    │   └── QuestionCandidateResponse.java
    ├── service/
    │   └── QuestionService.java
    └── controller/
        └── QuestionController.java
```

---

## 5. Enum

### `Difficulty.java` (add to `com.codepulse_backend.common.enums`)

```java
package com.codepulse_backend.common.enums;

public enum Difficulty {
    EASY,
    MEDIUM,
    HARD
}
```

---

## 6. Entity

### `Question.java`

```java
package com.codepulse_backend.question.entity;

import com.codepulse_backend.common.entity.BaseEntity;
import com.codepulse_backend.common.enums.Difficulty;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.util.UUID;

@Entity
@Table(name = "questions")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Question extends BaseEntity {

    @Column(name = "contest_id", nullable = false)
    private UUID contestId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Difficulty difficulty;

    @Column(nullable = false)
    private int points;

    @Column(name = "time_limit_ms", nullable = false)
    private int timeLimitMs;

    @Column(name = "memory_limit_kb", nullable = false)
    private int memoryLimitKb;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;
}
```

**Key design choice:** `contestId` is a plain `UUID` column — not a `@ManyToOne` relation. This avoids lazy-loading pitfalls and bidirectional-relation StackOverflowErrors (as happened in Module 3 publish). The service does an explicit `contestRepository.existsById(contestId)` check.

---

## 7. Repository

### `QuestionRepository.java`

```java
package com.codepulse_backend.question.repository;

import com.codepulse_backend.question.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface QuestionRepository extends JpaRepository<Question, UUID> {

    List<Question> findAllByContestIdOrderByOrderIndex(UUID contestId);

    boolean existsByIdAndContestId(UUID id, UUID contestId);

    long countByContestId(UUID contestId);

    @Query("SELECT q FROM Question q WHERE q.id IN :ids AND q.contestId = :contestId")
    List<Question> findAllByIdInAndContestId(@Param("ids") List<UUID> ids,
                                              @Param("contestId") UUID contestId);
}
```

---

## 8. DTOs

### `CreateQuestionRequest.java`

```java
package com.codepulse_backend.question.dto;

import com.codepulse_backend.common.enums.Difficulty;
import jakarta.validation.constraints.*;

public record CreateQuestionRequest(

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    String title,

    @NotBlank(message = "Description is required")
    String description,

    @NotNull(message = "Difficulty is required")
    Difficulty difficulty,

    @Min(value = 1, message = "Points must be at least 1")
    @Max(value = 1000, message = "Points must not exceed 1000")
    int points,

    @Min(value = 100, message = "Time limit must be at least 100ms")
    @Max(value = 10000, message = "Time limit must not exceed 10000ms")
    int timeLimitMs,

    @Min(value = 4096, message = "Memory limit must be at least 4096 KB (4 MB)")
    @Max(value = 1048576, message = "Memory limit must not exceed 1048576 KB (1 GB)")
    int memoryLimitKb
) {}
```

### `UpdateQuestionRequest.java`

```java
package com.codepulse_backend.question.dto;

import com.codepulse_backend.common.enums.Difficulty;
import jakarta.validation.constraints.*;

public record UpdateQuestionRequest(
    @Size(max = 255)       String title,
                           String description,
                           Difficulty difficulty,
    @Min(1) @Max(1000)     Integer points,
    @Min(100) @Max(10000)  Integer timeLimitMs,
    @Min(4096)             Integer memoryLimitKb
) {}
```

All fields nullable — only provided fields are updated (partial update pattern, same as Module 3).

### `ReorderQuestionsRequest.java`

```java
package com.codepulse_backend.question.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record ReorderQuestionsRequest(
    @NotEmpty(message = "Question IDs list must not be empty")
    List<UUID> orderedIds    // index 0 → orderIndex 1, index 1 → orderIndex 2, etc.
) {}
```

### `QuestionAdminResponse.java`

```java
package com.codepulse_backend.question.dto;

import com.codepulse_backend.common.enums.Difficulty;
import java.time.Instant;
import java.util.UUID;

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
    UUID createdBy
    // Module 5 will add: List<TestCaseAdminResponse> testCases
) {}
```

### `QuestionCandidateResponse.java`

```java
package com.codepulse_backend.question.dto;

import com.codepulse_backend.common.enums.Difficulty;
import java.util.UUID;

// Strict candidate view — no internal metadata, no hidden test cases
public record QuestionCandidateResponse(
    UUID id,
    UUID contestId,
    String title,
    String description,
    Difficulty difficulty,
    int points,
    int timeLimitMs,
    int memoryLimitKb,
    int orderIndex
    // Module 5 will add: List<TestCaseSampleResponse> sampleTestCases
) {}
```

---

## 9. Service

### `QuestionService.java` (method signatures and logic outline)

```java
package com.codepulse_backend.question.service;

// Full implementation provided in Section 8 of this plan above.
// Method signatures:

public QuestionAdminResponse createQuestion(UUID contestId, CreateQuestionRequest request)
public QuestionAdminResponse updateQuestion(UUID contestId, UUID questionId, UpdateQuestionRequest request)
public void deleteQuestion(UUID contestId, UUID questionId)
public List<?> getQuestions(UUID contestId)          // returns List<QuestionAdminResponse> or List<QuestionCandidateResponse>
public Object getQuestionDetail(UUID contestId, UUID questionId)  // returns admin or candidate DTO
public List<QuestionAdminResponse> reorderQuestions(UUID contestId, ReorderQuestionsRequest request)
```

**Access control logic inside `getQuestions` and `getQuestionDetail`:**
```
if (currentUser.role == CANDIDATE) {
    check contestCandidateRepository.existsByContestIdAndCandidateId → 403 if false
    check contest.status == ONGOING → 403 if not
    return toCandidateResponse(question)
} else {
    return toAdminResponse(question)
}
```

---

## 10. Controller

### `QuestionController.java`

```java
@RestController
@RequestMapping("/api/contests/{contestId}/questions")
@RequiredArgsConstructor
public class QuestionController {

    @GetMapping
    public ApiResponse<List<?>> getQuestions(@PathVariable UUID contestId)

    @GetMapping("/{questionId}")
    public ApiResponse<Object> getQuestionDetail(@PathVariable UUID contestId,
                                                  @PathVariable UUID questionId)

    @PostMapping                   // @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<QuestionAdminResponse> createQuestion(...)

    @PutMapping("/{questionId}")   // @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<QuestionAdminResponse> updateQuestion(...)

    @DeleteMapping("/{questionId}") // @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteQuestion(...)

    @PatchMapping("/reorder")       // @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<QuestionAdminResponse>> reorderQuestions(...)
}
```

---

## 11. API Reference

| Method | Endpoint | Description | Access |
|---|---|---|---|
| `GET` | `/api/contests/{contestId}/questions` | List all questions (role-scoped DTO) | Admin/Evaluator: full; Candidate: restricted (ONGOING only) |
| `GET` | `/api/contests/{contestId}/questions/{questionId}` | Question detail (role-scoped) | Admin/Evaluator: full; Candidate: restricted (ONGOING only) |
| `POST` | `/api/contests/{contestId}/questions` | Create a question | Admin only |
| `PUT` | `/api/contests/{contestId}/questions/{questionId}` | Update a question (partial) | Admin only |
| `DELETE` | `/api/contests/{contestId}/questions/{questionId}` | Delete a question | Admin only |
| `PATCH` | `/api/contests/{contestId}/questions/reorder` | Reorder questions in a contest | Admin only |

---

## 12. Security Rules Summary

| Caller | Condition | Result |
|---|---|---|
| `ADMIN` | Any contest | Full `QuestionAdminResponse` on all endpoints |
| `EVALUATOR` | Any contest | Full `QuestionAdminResponse` (read-only; `@PreAuthorize` blocks write endpoints) |
| `CANDIDATE` | Assigned to contest AND contest is `ONGOING` | `QuestionCandidateResponse` (no metadata) |
| `CANDIDATE` | Not assigned to contest | `403 AccessDeniedException` |
| `CANDIDATE` | Contest not `ONGOING` | `403 AccessDeniedException` |

---

## 13. Build Sequence

Follow this order strictly.

1. **Add `Difficulty.java` enum** to `com.codepulse_backend.common.enums`.
2. **Write `V6__create_question_table.sql`** and start the app — verify Flyway applies it cleanly (check logs for `Successfully applied 1 migration`).
3. **Implement `Question.java` entity** — verify Hibernate doesn't try to auto-create the table.
4. **Implement `QuestionRepository.java`** with all four methods.
5. **Implement both response DTOs** (`QuestionAdminResponse`, `QuestionCandidateResponse`).
6. **Implement request DTOs** (`CreateQuestionRequest`, `UpdateQuestionRequest`, `ReorderQuestionsRequest`).
7. **Implement `QuestionService.java`** — start with `createQuestion` + `getQuestions`, then add update/delete/reorder.
8. **Implement `QuestionController.java`**.
9. **Restart the backend** and test each endpoint in order (see DoD below).
10. **Verify the DTO security boundary** — call the list endpoint with a Candidate JWT on an ONGOING contest and confirm `createdBy` is absent from the raw JSON response.

---

## 14. Integration With Module 5 (Test Cases — Future)

When Module 5 is built, these two DTOs will be extended at the mapper methods in `QuestionService`:

| DTO | What Module 5 adds |
|---|---|
| `QuestionAdminResponse` | `List<TestCaseAdminResponse> testCases` (all — sample + hidden) |
| `QuestionCandidateResponse` | `List<TestCaseSampleResponse> sampleTestCases` (sample only, no `expectedOutput`) |

The mapper methods `toAdminResponse()` and `toCandidateResponse()` are the single injection points. No other files need to change.

---

## 15. Definition of Done

- [ ] `V6__create_question_table.sql` applies cleanly with no Flyway errors
- [ ] Admin can create a question and receives `201 Created` with full `QuestionAdminResponse`
- [ ] Admin can update a question — only provided fields change, others stay the same
- [ ] Admin can delete a question — it disappears from subsequent `GET` list
- [ ] Admin can reorder questions — `GET` after reorder returns them in the new order
- [ ] `QuestionAdminResponse` JSON contains `createdBy` and `createdAt` fields
- [ ] Candidate assigned to an `ONGOING` contest receives `QuestionCandidateResponse` with **no** `createdBy` field (verify in raw JSON)
- [ ] Candidate not assigned → `403`
- [ ] Candidate on non-ONGOING contest → `403`
- [ ] `audit_logs` table shows a row for each question creation event

**Future Enhancements (do not build now):** Question bank with tags, cross-contest question reuse, bulk question import via CSV/ZIP, markdown preview in admin UI, question versioning, difficulty-based automatic point suggestion.
