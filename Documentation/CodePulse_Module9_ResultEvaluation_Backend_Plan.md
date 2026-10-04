# CodePulse Enterprise — Module 9 Backend Build Plan
### Result & Evaluation Service

**Purpose:** Turn Module 8's scored submissions into one **result per candidate per contest**: total score, per-question breakdown and rank. Let an Evaluator **manually adjust** a question's score with a written reason. Let an Admin **publish** results, and until then keep every score hidden from candidates. Module 9 owns the `results`, `result_question_scores` and `manual_evaluations` tables, the ranking rule, the publish state, and the one condition Module 8 uses to decide whether a candidate may see verdicts again.

**Depends on:** Module 0 (Foundation) + Module 1 (Auth) + Module 3 (`Contest`, `ContestCandidate`, `ContestCompletedEvent`) + Module 4 (`Question.points`, questions locked once `ONGOING`) + Module 6 (`AssessmentSession`, `startedAt`) + Module 8 (`Submission`, counted query, `SessionScoringCompletedEvent`, rejudge, `SubmissionMapper.toCandidateView(…, resultsVisible)`).

**Status:** built on branch `feature/module-9-results` (2026-10-03). Plan written against backend `master` @ `41db676`; notes marked *As built* record where the code differs.

> **Blocker check before any Module 9 code:** Module 8 must be producing final statuses for every SUBMIT (no rows stuck in `PENDING`), and `SessionScoringCompletedEvent` must fire once per finalized session. Both are verified by the Module 8 Definition of Done. Run `./mvnw test -DargLine=-Duser.timezone=UTC` first: it must be green before you start.

---

## 1. What This Module Inherits (Do Not Rebuild)

| From | Component | How it's used here |
|---|---|---|
| Module 0 | `BaseEntity` | `Result`, `ResultQuestionScore`, `ManualEvaluation` extend it (UUID id, audit columns) |
| Module 0 | `ApiResponse<T>` | **Every** Module 9 endpoint is wrapped, including `POST /api/submissions/{id}/evaluate` (see 2.12) |
| Module 0 | `GlobalExceptionHandler` + `AppException` hierarchy | `ConflictException(code, msg)` → 409, `BadRequestException` → 400, `ResourceNotFoundException` → 404. New codes in Section 11 |
| Module 0 | `AuditService.log(actorId, action, entityType, entityId, details)` | `RESULTS_PUBLISHED`, `RESULTS_UNPUBLISHED`, `RESULTS_RECOMPUTED`, `SUBMISSION_EVALUATED` |
| Module 0 | `StringListConverter` | Stores `results.review_reasons` as a list (same converter as `Contest.allowedLanguages`) |
| Module 1 | `SecurityConfig`, `@PreAuthorize`, `CustomUserDetails.getId()` | `/api/contests/**` and `/api/submissions/**` are already `authenticated()`; role checks per method |
| Module 3 | `Contest` (`status`, `title`), `ContestCandidateRepository.findAllByContestId` / `existsByContestIdAndCandidateId` | The roster a result is computed for, and the candidate's "am I assigned" check |
| Module 3 | `ContestCompletedEvent(contestId)` | Creates `ABSENT` results for candidates who never started (Section 9.7) |
| Module 4 | `QuestionRepository.findAllByContestIdOrderByOrderIndex`, `Question.points` | Per-question max points. Questions can't change once a contest is `ONGOING` (`QuestionService.assertContestEditable`), so `points` is stable by the time results exist |
| Module 6 | `AssessmentSession` (`startedAt`, `submittedAt`, `status`), `AssessmentSessionRepository.findByContestIdAndCandidateId` / `findAllByContestIdAndStatus` | Who took the exam, who is still taking it, and the start time for the tie-break |
| Module 8 | `SubmissionRepository.findCountedSubmissions(sessionId)` | **The** counted rule (best score per question, ties → earliest). Module 9 never re-implements it |
| Module 8 | `SessionScoringCompletedEvent(sessionId, contestId, candidateId)` | Trigger to (re)compute one candidate's result. Also fires again after a rejudge finishes |
| Module 8 | `SubmissionMapper.toCandidateView(…, resultsVisible)`, `SubmissionSummaryResponse.withoutResults` | Module 9 extends the `resultsVisible` condition with "results published" (Section 12.1) |
| Module 8 | `SubmissionService.rejudge` | Blocked once results are published (Section 12.2) |
| Module 8 | `GET /api/submissions/{id}` evaluator view, `GET /api/contests/{id}/submissions?candidateId=&questionId=` | The evaluation screen reads code and test results through these; Module 9 adds no second way to read a submission |
| Flyway | Next migration is **`V12`** | `V12__create_result_tables.sql` (Section 6). Module 8 §16 said "V11", but `V11__one_pending_submit_per_question.sql` already took that number |

---

## 2. Decisions to Make Before Writing Any Code

### 2.1 Publish state lives on the contest, not on each result row
The roadmap sketches `results.published` and `results.published_at` per row. Results are published **per contest, all at once**, so a per-row flag can only ever be "all true" or "all false", and any bug that updates some rows but not others leaves a contest half-published. Instead:

- `contests.results_published_at TIMESTAMPTZ NULL` and `contests.results_published_by UUID NULL`.
- "Published" means `results_published_at IS NOT NULL`. That one column is the truth, and nothing else stores it.
- `ContestResponse` / `ContestDetailResponse` expose `resultsPublished` and `resultsPublishedAt`, so the frontend learns the state from data it already fetches.

### 2.2 One result row per assigned candidate, materialized
`results` holds one row per (contest, candidate), **including candidates who never started** (`ABSENT`). It is a **materialized** aggregate rather than a view, because:
- ranking needs every candidate's total at once, and a stored `rank` makes the leaderboard a single indexed read;
- the per-question breakdown (`result_question_scores`) records exactly which submission and which override produced each number, so an Evaluator can explain any score.

The cost is staleness, so the rule is that **every event that can change a score recomputes the result** (2.5). Recompute is idempotent and cheap, so it is always safe to run it again.

### 2.3 What a candidate's total is
For each question in the contest:

```
autoScore(q)  = counted SUBMIT's score (Module 8 rule), or 0 if there is none
finalScore(q) = the active manual override's adjustedScore if there is one, else autoScore(q)

totalScore = Σ finalScore(q)
autoScore  = Σ autoScore(q)      (kept so the UI can show what was adjusted)
maxScore   = Σ question.points   (snapshot at compute time)
```

RUN submissions never count, and `PENDING` / `SYSTEM_ERROR` SUBMITs never count. All of this already follows from `findCountedSubmissions`.

### 2.4 Result status: `SCORED`, `NEEDS_REVIEW`, `ABSENT`
New enum `common/enums/ResultStatus`:

| Status | When | Ranked? |
|---|---|---|
| `SCORED` | Session finalized, nothing pending, nothing suspicious | Yes |
| `NEEDS_REVIEW` | Scored, but one or more `reviewReasons` apply (below). Rank is provisional | Yes |
| `ABSENT` | Assigned, contest `COMPLETED`, never started a session | No (`rank = NULL`), listed last |

`reviewReasons` (new enum `ReviewReason`, stored as a list):
- `UNRESOLVED_SYSTEM_ERROR`: the session has at least one `SYSTEM_ERROR` SUBMIT. Module 8 §2.8 guarantees a judge failure is never a silent zero, and Module 8 §16 asks Module 9 to flag exactly this before publishing. The fix is a rejudge (Module 8), which then triggers a recompute.
- `OVERRIDE_OUTDATED`: a manual override was made against a submission that is **no longer** the counted one (a rejudge changed the best submission). The override still applies (2.6), but an Evaluator should look again.

A candidate whose session is still `IN_PROGRESS`, or whose SUBMITs are still `PENDING`, has **no result row yet**. These are counted in the readiness summary (2.9), not stored.

### 2.5 When results are (re)computed
| Trigger | Action |
|---|---|
| `SessionScoringCompletedEvent` (Module 8: session finalized **and** no SUBMIT pending; fires again after a rejudge completes) | `recomputeCandidate(contestId, candidateId)` |
| `ContestCompletedEvent` | `createAbsentResults(contestId)`: an `ABSENT` row for each assigned candidate without a session |
| Manual evaluation saved | Recompute that candidate, in the same transaction as the evaluation |
| Admin presses **Recompute** (`POST …/results/recompute`) | `recomputeContest(contestId)` (safety net if an event was lost) |
| Publish | `recomputeContest` runs first, inside the publish transaction, so published numbers are fresh by construction |

Every recompute of one candidate **re-ranks the whole contest** in the same transaction (a few hundred rows at most).

### 2.6 Manual evaluation is a per-question override, anchored to the counted submission
The roadmap stores `manual_evaluations` per submission. The subtle part is what an override *means* when a candidate has several SUBMITs:

> An override sets **the candidate's final score for that question**. It is recorded against the submission the Evaluator reviewed, which must be the **counted** submission at that moment.

Why not "override only that submission's score and keep picking the best"? If an Evaluator zeroes a hard-coded 100-point answer, the counted rule would quietly fall back to the candidate's next-best SUBMIT and the penalty would be evaded. A per-question override can't be dodged that way.

Rules:
- **Append-only.** Every save inserts a new `manual_evaluations` row, so the history is the audit trail. The **active** override for (session, question) is the newest row.
- **Revert** = a new row with `adjusted_score = NULL` ("back to the automatic score"), still with a required comment.
- Allowed only when: the submission is a `SUBMIT` with a final status other than `SYSTEM_ERROR`, its session is no longer `IN_PROGRESS`, it is the currently counted submission for its question, and the contest's results are **not published**.
- `0 ≤ adjustedScore ≤ question.points`, with at most 2 decimals (matches `NUMERIC(8,2)` and `ScoringService`'s rounding).
- `comments` is required (1–2000 chars). It is **internal**: candidates see that a score was adjusted, never the comment or who wrote it (2.10).
- A question with **no** counted submission can't be overridden (nothing to anchor to; it scores 0). A question whose only SUBMITs are `SYSTEM_ERROR` must be rejudged first.

### 2.7 Ranking rule (lives only in `RankingService`)
1. `ABSENT` results are unranked (`rank = NULL`).
2. All other results are sorted by **`totalScore` DESC**, then **`timeTakenSeconds` ASC** (`NULL` last).
3. **Competition ranking ("1, 2, 2, 4")**: results equal on both keys share a rank, and the next rank skips.

`timeTakenSeconds` = latest `submitted_at` among the counted submissions − `session.started_at`, floored to whole seconds; `NULL` if nothing counted. This is the roadmap's "faster submission wins ties", measured from **each candidate's own start**, because sessions start individually and wall-clock times would favour whoever clicked Start first. It also matches Module 8 §2.5, where ties within a question go to the earliest submission.

Manual overrides change `totalScore` but never `timeTakenSeconds`. Changing the tie-break later means editing `RankingService` only.

### 2.8 Publishing and unpublishing
**Publish** (`POST /api/contests/{id}/results/publish`, Admin) succeeds only if:
1. the contest is `COMPLETED` (else `RESULTS_NOT_READY`);
2. it isn't already published (else `RESULTS_ALREADY_PUBLISHED`);
3. after a fresh `recomputeContest`: no session `IN_PROGRESS`, no SUBMIT `PENDING`, and every assigned candidate has a result row (else `RESULTS_NOT_READY`, with the counts in the message);
4. no result is `NEEDS_REVIEW`, **unless** the request has `acknowledgeFlagged: true` (else `RESULTS_NEED_REVIEW`). The Admin can knowingly publish provisional results, and the audit entry records that they did.

**Unpublish** (`POST …/results/unpublish`, Admin, body `{ reason }`) clears the publish columns. It exists because a mistake found after publishing (a wrong test case, a missed override) must be fixable. While results are published, **both manual evaluation and rejudge are refused** (`RESULTS_PUBLISHED_LOCKED`), so numbers a candidate has seen never change silently. The workflow is: unpublish → fix → publish again.

A recompute triggered while published (only possible from a late, duplicate event) is **skipped with a WARN log**, not applied.

### 2.9 Readiness is computed, never stored
`ResultReadiness` is built on every leaderboard read from the roster, sessions and submissions:

```
totalCandidates  assigned to the contest
inProgress       sessions IN_PROGRESS
judging          finalized sessions that still have a PENDING SUBMIT
missing          finalized sessions (or, once COMPLETED, assigned candidates) with no result row
scored / needsReview / absent   counts by ResultStatus
contestCompleted, published
readyToPublish = contestCompleted && !published && inProgress == 0 && judging == 0 && missing == 0
```
It drives the Admin's publish button and checklist. `missing > 0` means an event was lost, and **Recompute** fixes it.

### 2.10 What candidates may see: the publish boundary
Same principle as Modules 4, 5 and 8: **separate DTO classes** for the candidate and staff views, built in **one** mapper (`ResultMapper`), so a shared serializer can't leak.

| Data | Candidate, before publish | Candidate, after publish | Evaluator / Admin |
|---|---|---|---|
| That results are/aren't published | Yes | Yes | Yes |
| Own total, max, rank, "out of N ranked" | **No** | Yes | Yes |
| Own per-question final score, counted verdict, passed/total | **No** | Yes | Yes |
| "Adjusted by an evaluator" flag per question | **No** | Yes | Yes |
| Override comment, evaluator identity, auto score before adjustment | **No** | **No** | Yes |
| Other candidates' results, the leaderboard | **No** | **No** | Yes |
| Own submission verdicts in history/detail (Module 8) | Hidden after session end | Visible again | Yes |

`GET …/results/me` before publish returns **200** with `published: false` and every result field `null`, so "not published yet" is a normal state rather than an error (the same choice Module 6 made for `NOT_YET_STARTED`).

### 2.11 Concurrency: one lock per contest, always taken first
Recomputes run on judge threads (the event comes from `SubmissionEvaluatedListener`), the request thread (evaluate, publish) and the AFTER_COMMIT listener (contest completed), sometimes simultaneously. Re-ranking reads and writes every row of a contest, so two interleaved recomputes would overwrite each other's ranks.

**Rule:** every Module 9 write transaction starts with `ContestRepository.findByIdForUpdate(contestId)` (`PESSIMISTIC_WRITE`). This serializes all result writes **per contest**. Different contests don't block each other. Lock order is always **contest row → result rows**, so there is no deadlock. The contest scheduler only updates the contest row and commits before its AFTER_COMMIT listeners run, so it never holds the lock while waiting on Module 9.

The lock is held for milliseconds (no Judge0 calls happen in Module 9), so blocking a judge thread on it is acceptable.

### 2.12 Responses are wrapped in `ApiResponse<T>`
Module 8's `/api/submissions/*` endpoints return bare DTOs, which deviates from the roadmap's foundation rule. Module 9 **follows the foundation rule** for all its endpoints, including `POST /api/submissions/{id}/evaluate`. The frontend plan calls this out explicitly, because the evaluate endpoint is the only wrapped one under `/submissions`.

### 2.13 Evaluator scope
There is still no evaluator↔contest assignment (Module 8 §8 limitation). In v1, any `EVALUATOR` or `ADMIN` can view any contest's results and evaluate. Note it as a known limitation; don't fake a scope check.

---

## 3. Step 0 — Verify Module 8 (no new code)

| Check | How |
|---|---|
| Counted query returns one row per question, best score, ties → earliest | Existing `SubmissionRepositoryIntegrationTest` is green |
| `SessionScoringCompletedEvent` fires after the last pending SUBMIT of a finalized session, and again after a rejudge of that session completes | Read `SubmissionPersistenceService.scoringCompletedEvent` (it filters `status != IN_PROGRESS` and zero pending). Add a log line temporarily and run a short contest |
| A session that ends with zero submissions still fires the event | `SessionFinalizedHandler` publishes immediately when `pending == 0` |
| Questions and test cases are locked once a contest is `ONGOING` | `QuestionService.assertContestEditable`, and the test-case equivalent |
| `ContestCompletedEvent` is published on `transitionToCompleted` | `ContestService.transitionToCompleted` |

Write the confirmed event semantics in a comment at the top of `ResultService`.

---

## 4. Gaps in Earlier Modules Found While Planning (Fix as Noted)

| Gap | Impact | Where to fix |
|---|---|---|
| Module 8 `resultsVisible` is `session.status == IN_PROGRESS` only | After publishing, candidates still can't see their own verdicts | Section 12.1 |
| Module 8 rejudge has no publish guard | A rejudge after publishing silently changes a published score | Section 12.2 |
| `ContestRepository` has no locking finder | Concurrent recomputes corrupt ranks | Section 12.3: add `findByIdForUpdate` |
| No query counts "finalized sessions with a pending SUBMIT" per contest | Readiness can't tell "still judging" from "missing" | Section 9.3: new `SubmissionRepository` query |
| The Module 8 plan said Module 9 adds `V11` | Number already used | Use `V12` |

---

## 5. New Common Enums

**Files:** `common/enums/ResultStatus.java`, `common/enums/ReviewReason.java`

```java
package com.codepulse_backend.common.enums;

/** SCORED and NEEDS_REVIEW are ranked; ABSENT (never started) is not. */
public enum ResultStatus { SCORED, NEEDS_REVIEW, ABSENT }
```

```java
package com.codepulse_backend.common.enums;

/** Why a result is NEEDS_REVIEW. Rank is provisional until these are resolved or acknowledged. */
public enum ReviewReason { UNRESOLVED_SYSTEM_ERROR, OVERRIDE_OUTDATED }
```

Audit action names go in `result/ResultAuditAction.java`, as constants (same style as `session/SessionAuditAction`).

---

## 6. Database Migration

**File:** `src/main/resources/db/migration/V12__create_result_tables.sql`
> Column types follow `V10__create_submission_tables.sql`: `TIMESTAMPTZ`, `UUID` ids and `NUMERIC` scores. `manual_evaluations` is created before `result_question_scores` because the latter references it.

```sql
-- Publish state is per contest (plan 2.1)
ALTER TABLE contests
    ADD COLUMN results_published_at TIMESTAMPTZ,
    ADD COLUMN results_published_by UUID REFERENCES users(id);

CREATE TABLE results (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    created_by          UUID,
    updated_by          UUID,

    contest_id          UUID          NOT NULL REFERENCES contests(id),
    candidate_id        UUID          NOT NULL REFERENCES users(id),
    session_id          UUID          REFERENCES assessment_sessions(id),   -- NULL = ABSENT

    status              VARCHAR(20)   NOT NULL CHECK (status IN ('SCORED','NEEDS_REVIEW','ABSENT')),
    review_reasons      TEXT,                                               -- StringListConverter
    auto_score          NUMERIC(10,2) NOT NULL DEFAULT 0,                   -- before manual overrides
    total_score         NUMERIC(10,2) NOT NULL DEFAULT 0,                   -- what is ranked
    max_score           NUMERIC(10,2) NOT NULL DEFAULT 0,
    adjusted            BOOLEAN       NOT NULL DEFAULT FALSE,               -- any active override
    time_taken_seconds  BIGINT,                                             -- tie-break (plan 2.7)
    rank                INT,                                                -- NULL for ABSENT
    computed_at         TIMESTAMPTZ   NOT NULL,

    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uq_results_contest_candidate UNIQUE (contest_id, candidate_id)
);

CREATE INDEX idx_results_contest_rank ON results (contest_id, rank);

CREATE TABLE manual_evaluations (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    submission_id   UUID         NOT NULL REFERENCES submissions(id),       -- the counted SUBMIT reviewed
    session_id      UUID         NOT NULL REFERENCES assessment_sessions(id),
    question_id     UUID         NOT NULL REFERENCES questions(id),
    evaluator_id    UUID         NOT NULL REFERENCES users(id),

    adjusted_score  NUMERIC(8,2) CHECK (adjusted_score IS NULL OR adjusted_score >= 0),  -- NULL = revert
    comments        TEXT         NOT NULL,
    evaluated_at    TIMESTAMPTZ  NOT NULL
);

-- "Active override" lookup: newest row per (session, question)
CREATE INDEX idx_manual_eval_session_question
    ON manual_evaluations (session_id, question_id, evaluated_at DESC);

CREATE TABLE result_question_scores (
    id                     UUID PRIMARY KEY,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    created_by             UUID,
    updated_by             UUID,

    result_id              UUID         NOT NULL REFERENCES results(id) ON DELETE CASCADE,
    question_id            UUID         NOT NULL REFERENCES questions(id),
    counted_submission_id  UUID         REFERENCES submissions(id),        -- NULL = no counted SUBMIT
    manual_evaluation_id   UUID         REFERENCES manual_evaluations(id), -- active override, if any

    max_points             INT          NOT NULL,
    auto_score             NUMERIC(8,2) NOT NULL DEFAULT 0,
    final_score            NUMERIC(8,2) NOT NULL DEFAULT 0,
    override_outdated      BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT uq_result_question UNIQUE (result_id, question_id)
);
```

**FK notes:**
- `submissions` and `questions` are referenced with the default `RESTRICT`. Contests with sessions can't be deleted, and questions are locked once `ONGOING`, so nothing legitimate is blocked.
- `rank` is a non-reserved word in PostgreSQL and is fine as a column name. Map it with `@Column(name = "rank")`.

---

## 7. Configuration

No new properties. Ranking and visibility rules are code (`RankingService`, `ResultMapper`), not config. A configurable tie-break would only be a way to get two different leaderboards for the same data.

---

## 8. API Surface

All responses use `ApiResponse<T>` (2.12).

| Method | Endpoint | Description | Access | Success |
|---|---|---|---|---|
| GET | `/api/contests/{contestId}/results` | Leaderboard: every result, ranked, plus readiness and question columns | Evaluator / Admin | `200` `LeaderboardResponse` |
| GET | `/api/contests/{contestId}/results/candidates/{candidateId}` | One candidate's full breakdown, with overrides and override history | Evaluator / Admin | `200` `ResultResponse` |
| GET | `/api/contests/{contestId}/results/me` | Own result; `published: false` and null fields before publish | Candidate (assigned) | `200` `MyResultResponse` |
| POST | `/api/contests/{contestId}/results/recompute` | Recompute every candidate, then re-rank | Admin | `200` `LeaderboardResponse` |
| POST | `/api/contests/{contestId}/results/publish` | Publish (body `PublishResultsRequest`) | Admin | `200` `LeaderboardResponse` |
| POST | `/api/contests/{contestId}/results/unpublish` | Unpublish (body `UnpublishResultsRequest`) | Admin | `200` `LeaderboardResponse` |
| POST | `/api/submissions/{submissionId}/evaluate` | Save an override or a revert (body `ManualEvaluationRequest`) | Evaluator / Admin | `200` `ResultResponse` (the candidate's updated result) |

Rules:
- A candidate who **isn't assigned** to the contest gets **404** on `/results/me`, not 403, so contest IDs can't be probed.
- `/results/candidates/{candidateId}` for someone not assigned to the contest → 404 `RESULT_NOT_FOUND`. For an assigned candidate with no row yet (still in progress), it also returns 404 `RESULT_NOT_FOUND`, and the leaderboard's readiness explains why.
- The leaderboard is **not paged**: a contest has tens to a few hundred candidates, and ranking and filtering are clearer client-side. Revisit if contests grow past ~1000.
- Evaluate returns the **whole updated `ResultResponse`**, so the frontend replaces its cache entry instead of refetching.

---

## 9. Backend Components

### 9.1 Package Structure

```
com.codepulse_backend/
├── common/enums/
│   ├── ResultStatus.java                         ← NEW
│   └── ReviewReason.java                         ← NEW
└── result/
    ├── ResultAuditAction.java                    ← constants: RESULTS_PUBLISHED, …
    ├── entity/
    │   ├── Result.java
    │   ├── ResultQuestionScore.java
    │   └── ManualEvaluation.java
    ├── repository/
    │   ├── ResultRepository.java
    │   ├── ResultQuestionScoreRepository.java
    │   └── ManualEvaluationRepository.java
    ├── dto/
    │   ├── PublishResultsRequest.java
    │   ├── UnpublishResultsRequest.java
    │   ├── ManualEvaluationRequest.java
    │   ├── ManualEvaluationResponse.java
    │   ├── ResultReadiness.java
    │   ├── LeaderboardResponse.java              ← includes QuestionColumn
    │   ├── LeaderboardEntry.java                 ← includes QuestionScoreCell
    │   ├── ResultResponse.java                   ← staff detail; includes QuestionResultView, CountedSubmissionView
    │   └── MyResultResponse.java                 ← candidate view; includes MyQuestionResult
    ├── service/
    │   ├── ResultService.java                    ← recompute, re-rank, publish/unpublish, reads
    │   ├── ResultCalculator.java                 ← pure: totals, review reasons, time taken (no DB)
    │   ├── RankingService.java                   ← pure: rank assignment + tie-break (no DB)
    │   ├── ManualEvaluationService.java          ← override flow
    │   ├── ResultReadinessService.java           ← builds ResultReadiness from roster/sessions/submissions
    │   ├── ResultVisibilityService.java          ← isPublished(contestId); used by Module 8
    │   └── ResultMapper.java                     ← the ONLY place candidate/staff views are built
    ├── event/
    │   ├── SessionScoringCompletedResultListener.java
    │   ├── ContestCompletedResultListener.java
    │   └── ResultsPublishedEvent.java            ← NEW, for Module 11 (no listener yet)
    └── controller/
        ├── ResultController.java                 ← /api/contests/{contestId}/results/**
        └── ManualEvaluationController.java       ← /api/submissions/{id}/evaluate
```

`ResultCalculator` is not in the roadmap's component list. It is split out of `ResultService` for the same reason Module 8 split `ScoringService` out of `SubmissionService`: the arithmetic and review rules become pure functions you can unit-test without a database.

### 9.2 Entities

- **`Result extends BaseEntity`:** plain UUID columns `contestId`, `candidateId`, `sessionId` (nullable); `@Enumerated(STRING) status`; `@Convert(converter = StringListConverter.class) List<String> reviewReasons` (store `ReviewReason.name()` values); `BigDecimal autoScore, totalScore, maxScore`; `boolean adjusted`; `Long timeTakenSeconds`; `Integer rank`; `Instant computedAt`; `@Version Long version`. **Plain UUIDs, not `@ManyToOne`**, the same reasoning as Module 8 §9.2.
- **`ResultQuestionScore extends BaseEntity`:** `resultId`, `questionId`, `countedSubmissionId` (nullable), `manualEvaluationId` (nullable), `maxPoints`, `autoScore`, `finalScore`, `overrideOutdated`.
- **`ManualEvaluation extends BaseEntity`:** `submissionId`, `sessionId`, `questionId`, `evaluatorId`, `BigDecimal adjustedScore` (nullable = revert), `comments`, `evaluatedAt`. **No setters are used after insert**: it's append-only.
- **`Contest`** (Module 3, modified): `Instant resultsPublishedAt`, `UUID resultsPublishedBy`, plus `boolean isResultsPublished()` returning `resultsPublishedAt != null`.

### 9.3 Repositories

`ResultRepository`:
- `Optional<Result> findByContestIdAndCandidateId(UUID contestId, UUID candidateId)`
- `List<Result> findAllByContestId(UUID contestId)` (ranking and leaderboard)
- `long countByContestIdAndStatus(UUID contestId, ResultStatus status)`
- `long countByContestIdAndRankIsNotNull(UUID contestId)`, i.e. "ranked out of N"

`ResultQuestionScoreRepository`:
- `List<ResultQuestionScore> findAllByResultIdIn(Collection<UUID> resultIds)` (leaderboard cells, **one** query for the whole contest)
- `List<ResultQuestionScore> findAllByResultId(UUID resultId)`
- `@Modifying void deleteAllByResultId(UUID resultId)` (recompute replaces a result's rows)

`ManualEvaluationRepository`:
- `List<ManualEvaluation> findAllBySessionIdOrderByEvaluatedAtDescCreatedAtDesc(UUID sessionId)`: the history for the detail view, and the source of **active overrides**: the first row per question wins, and a row with `adjusted_score IS NULL` is a revert ("no active override").
  > *As built:* the active override is picked in Java from this list rather than with a `DISTINCT ON` query. A session has a handful of evaluations, and this keeps the code path working on the H2 test profile too.

`SubmissionRepository` (Module 8, **added**):
- `long countBySessionIdAndSubmissionTypeAndStatus(...)` already exists. Reuse it with `SYSTEM_ERROR` for `UNRESOLVED_SYSTEM_ERROR`.
- **New**, for readiness: `List<UUID> findSessionIdsInContestWithStatus(contestId, SUBMIT, PENDING)`, the sessions that still have a SUBMIT judging. Readiness compares it with the contest's sessions (`AssessmentSessionRepository.findAllByContestId`, also new) so "still judging" and "missing" are counted exactly.
- **New**, for the detail view: `List<Object[]> countBySessionGroupedByQuestionAndStatus(sessionId, SUBMIT)`, i.e. `(questionId, status, count)`, so each question can show "3 attempts, 1 judge error" without N queries.

`ContestRepository` (Module 3, **added**): `@Lock(PESSIMISTIC_WRITE) @Query("select c from Contest c where c.id = :id") Optional<Contest> findByIdForUpdate(UUID id)`.

### 9.4 `ResultCalculator`: pure functions, no repositories

```java
public CandidateResult calculate(
        List<QuestionInput> questionsInOrder,        // id, points
        Map<UUID, CountedInput> countedByQuestion,   // submissionId, score, submittedAt
        Map<UUID, OverrideInput> activeOverrides,    // evaluationId, submissionId, adjustedScore (non-null only)
        long systemErrorSubmits,
        Instant sessionStartedAt) { ... }

public record QuestionInput(UUID questionId, int points) {}
public record CountedInput(UUID submissionId, BigDecimal score, Instant submittedAt) {}
public record OverrideInput(UUID evaluationId, UUID submissionId, BigDecimal adjustedScore) {}

public record CandidateResult(
        BigDecimal autoScore, BigDecimal totalScore, BigDecimal maxScore,
        boolean adjusted, Long timeTakenSeconds,
        ResultStatus status, List<ReviewReason> reviewReasons,
        List<QuestionOutcome> questions) {}

public record QuestionOutcome(
        UUID questionId, int maxPoints, UUID countedSubmissionId, UUID manualEvaluationId,
        BigDecimal autoScore, BigDecimal finalScore, boolean overrideOutdated) {}
```
It implements 2.3, 2.4 and 2.7's `timeTakenSeconds` exactly. `override_outdated = override.submissionId != counted.submissionId`. An override on a question that now has **no** counted submission still applies (an Evaluator decided that question's score) and is marked outdated. Totals use `setScale(2, HALF_UP)`, the same as `ScoringService`.

### 9.5 `RankingService`: pure

```java
public Map<UUID, Integer> rank(List<RankInput> results) { ... }   // resultId → rank (absent ids omitted)

public record RankInput(UUID resultId, ResultStatus status, BigDecimal totalScore, Long timeTakenSeconds) {}
```
It implements 2.7: filter out `ABSENT`, sort, then competition ranking. Compare `BigDecimal`s with `compareTo` (not `equals`, which treats `10.0` and `10.00` as different).

### 9.6 `ResultService`

All write methods take the **contest lock first** (2.11).

**`recomputeCandidate(contestId, candidateId)`**, `@Transactional(propagation = REQUIRES_NEW)` (it's called from event listeners that may or may not be inside a transaction; see the `SessionScoringCompletedEvent` javadoc):
1. `contestRepository.findByIdForUpdate(contestId)`; if published → WARN and return (2.8).
2. `recomputeCandidateLocked(contest, candidateId)` (below), then `rerank(contestId)`.

**`recomputeCandidateLocked(contest, candidateId)`** (private, no transaction annotation; always called inside a locked transaction):
1. Session = `findByContestIdAndCandidateId`.
   - None: if the contest is `COMPLETED`, upsert an `ABSENT` row (scores 0, `maxScore` from the questions, one zeroed `ResultQuestionScore` per question so every result has the same shape, `rank = NULL`); otherwise do nothing.
   - `IN_PROGRESS`, or it has a `PENDING` SUBMIT: do nothing (no row yet; a later event comes).
2. Load questions (ordered), `findCountedSubmissions(sessionId)`, active overrides, and the `SYSTEM_ERROR` SUBMIT count.
3. `ResultCalculator.calculate(...)`.
4. Upsert the `Result` row (`findByContestIdAndCandidateId` or new), set every field, `computedAt = now`.
5. `deleteAllByResultId` then insert one `ResultQuestionScore` per question. Flush the delete before inserting so the `uq_result_question` constraint can't trip.

**`recomputeContest(contestId, actorId)`** (`@Transactional`): lock, run `recomputeCandidateLocked` for every assigned candidate, `rerank`, audit `RESULTS_RECOMPUTED` (manual trigger only, not the internal call made by publish).

**`createAbsentResults(contestId)`** (`REQUIRES_NEW`): lock, then `recomputeCandidateLocked` for each assigned candidate **without** a session, then `rerank`.

**`rerank(contestId)`** (private): `findAllByContestId` → `RankingService.rank` → set `rank` on each entity. Dirty checking saves only the rows whose rank changed.

**`publish(contestId, PublishResultsRequest, actorId)`** and **`unpublish(contestId, UnpublishResultsRequest, actorId)`**, `@Transactional`: implement 2.8 in that order, set or clear `contest.resultsPublishedAt/By`, and audit (details: counts, `acknowledgeFlagged`, the unpublish reason). Publish registers `ResultsPublishedEvent(contestId)` to be published **after commit** (`TransactionSynchronization.afterCommit`, or publish it and consume it with `@TransactionalEventListener` later in Module 11).

**Reads** (`@Transactional(readOnly = true)`): `getLeaderboard(contestId)`, `getCandidateResult(contestId, candidateId)` and `getMyResult(contestId, candidateId)` load the rows, call `ResultReadinessService` where needed, and hand everything to `ResultMapper`. Leaderboard loads: results (1 query), question scores for all of them (1), users for identities (1, `findAllById`), questions (1). That's **four queries regardless of contest size**.

### 9.7 Event listeners

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionScoringCompletedResultListener {

    private final ResultService resultService;

    @EventListener
    public void onScoringCompleted(SessionScoringCompletedEvent event) {
        try {
            resultService.recomputeCandidate(event.contestId(), event.candidateId());
        } catch (Exception e) {
            // Never propagate into the judge worker or the session finalizer.
            // Readiness will show the candidate as "missing"; Recompute fixes it.
            log.error("Failed to compute result for session {}", event.sessionId(), e);
        }
    }
}
```

`ContestCompletedResultListener`: `@TransactionalEventListener(phase = AFTER_COMMIT)` on `ContestCompletedEvent`, calling `resultService.createAbsentResults(contestId)`, with the same catch-and-log. Its ordering relative to Module 3's `ContestCompletedSessionListener` doesn't matter: each finalized session fires its own `SessionScoringCompletedEvent`, and every path is idempotent.

### 9.8 `ManualEvaluationService.evaluate(submissionId, request, evaluatorId)`

`@Transactional`:
1. Load the submission → 404 `SUBMISSION_NOT_FOUND`. Load its session and question.
2. `contestRepository.findByIdForUpdate(session.contestId)` (**lock first**, so publish can't slip in between the check and the insert).
3. Contest published → `RESULTS_PUBLISHED_LOCKED`.
4. Not a `SUBMIT`, status `PENDING` or `SYSTEM_ERROR`, or session still `IN_PROGRESS` → `SUBMISSION_NOT_EVALUABLE`.
5. Not in `findCountedSubmissions(sessionId)` → `SUBMISSION_NOT_COUNTED`.
6. `adjustedScore != null` and (`> question.points` or more than 2 decimals) → `ADJUSTED_SCORE_OUT_OF_RANGE` (400). Bean Validation covers `≥ 0` and `@NotBlank comments`.
7. Insert `ManualEvaluation` (`evaluatedAt = now`).
8. Audit `SUBMISSION_EVALUATED` (details: questionId, candidateId, auto → adjusted, or "reverted").
9. `resultService.recomputeCandidateLocked(contest, candidateId)` + `rerank` (same transaction, lock already held; expose them package-private or through a `ResultService` method that assumes the lock).
10. Return `ResultMapper.toStaffView(...)` for that candidate.

### 9.9 `ResultReadinessService`
`ResultReadiness build(Contest contest, List<Result> results)` uses `countByContestId` (roster), `findAllByContestIdAndStatus(IN_PROGRESS)`, `countFinalizedSessionsWithPendingSubmit`, and the session count. It computes 2.9 exactly. It's pure apart from those count queries, so test it with mocked repositories.

### 9.10 `ResultVisibilityService`
```java
public boolean isPublished(UUID contestId)   // contestRepository.findById(...).map(Contest::isResultsPublished).orElse(false)
```
This is the **single** place Module 8 asks "may a candidate see verdicts after the exam?" (Section 12.1). No other code reads `results_published_at` for visibility.

### 9.11 `ResultMapper`
- `toLeaderboard(contest, questions, results, scoresByResult, users, readiness, publishedByName)` → `LeaderboardResponse`
- `toStaffView(result, scores, questions, user, session, countedSubmissions, attemptStats, evaluations, evaluatorNames)` → `ResultResponse`
- `toCandidateView(contest, result, scores, questions, countedSubmissions, rankedCount)` → `MyResultResponse`. **When the contest isn't published, it returns `published: false` and nulls before looking at `result` at all.**
- **No shared base class or shared serializer between staff and candidate views.**

### 9.12 Controllers
- **`ResultController`** (`@RequestMapping("/api/contests/{contestId}/results")`): `@PreAuthorize("hasAnyRole('EVALUATOR','ADMIN')")` on the leaderboard and candidate detail; `hasRole('ADMIN')` on recompute, publish and unpublish; `hasRole('CANDIDATE')` on `/me`. The candidate and actor IDs always come from `CustomUserDetails`, never from the path or body (except the staff `candidateId` path variable).
- **`ManualEvaluationController`**: `POST /api/submissions/{submissionId}/evaluate`, `hasAnyRole('EVALUATOR','ADMIN')`. Keep it **out of** `SubmissionController`: the URL sits under `/submissions`, but the behaviour belongs to Module 9 (Module 8 plan §16).
- Wrap with `ApiResponse.success(data, message, traceId)`, or construct it the way `ContestController` does.

---

## 10. DTO Notes

```java
public record PublishResultsRequest(boolean acknowledgeFlagged) {}

public record UnpublishResultsRequest(@NotBlank @Size(max = 500) String reason) {}

public record ManualEvaluationRequest(
        @DecimalMin("0.00") @Digits(integer = 6, fraction = 2)
        BigDecimal adjustedScore,                         // null = revert to the automatic score
        @NotBlank @Size(max = 2000) String comments
) {}
```

- **`ResultReadiness`**: `totalCandidates, inProgress, judging, missing, scored, needsReview, absent, contestCompleted, published, readyToPublish`.
- **`LeaderboardResponse`**: `contestId, contestTitle, contestStatus, published, publishedAt, publishedByName, maxScore, List<QuestionColumn> questions, ResultReadiness readiness, List<LeaderboardEntry> entries` (entries ordered by rank, ABSENT last by name).
  - `QuestionColumn`: `questionId, title, orderIndex, points`.
- **`LeaderboardEntry`**: `resultId, rank, candidateId, candidateName, candidateEmail, candidateRollNumber, status, reviewReasons, totalScore, autoScore, maxScore, adjusted, timeTakenSeconds, List<QuestionScoreCell> questionScores`.
  - `QuestionScoreCell`: `questionId, finalScore, maxPoints, attempted` (has a counted submission), `adjusted`.
- **`ResultResponse`** (staff): `resultId, contestId, candidateId, candidateName, candidateEmail, candidateRollNumber, sessionId, sessionStatus, startedAt, submittedAt, status, reviewReasons, totalScore, autoScore, maxScore, rank, rankedCount, timeTakenSeconds, published, computedAt, List<QuestionResultView> questions`.
  - `QuestionResultView`: `questionId, title, orderIndex, maxPoints, autoScore, finalScore, CountedSubmissionView countedSubmission` (nullable: `id, language, status, passedCount, totalCount, submittedAt`), `submitAttempts, systemErrorCount, ManualEvaluationResponse activeOverride` (nullable), `overrideOutdated, List<ManualEvaluationResponse> history` (newest first).
- **`ManualEvaluationResponse`**: `id, submissionId, questionId, adjustedScore` (null = revert), `comments, evaluatorId, evaluatorName, evaluatedAt`.
- **`MyResultResponse`** (candidate): `contestId, contestTitle, published, publishedAt, status, totalScore, maxScore, rank, rankedCount, adjusted, List<MyQuestionResult> questions`. Every field after `publishedAt` is `null` (or an empty list) when unpublished.
  - `MyQuestionResult`: `questionId, title, orderIndex, maxPoints, finalScore, verdict` (counted submission's `SubmissionStatus`, `null` = not attempted), `passedCount, totalCount, countedSubmissionId, adjusted`. **No `autoScore`, no comment, no evaluator.**
- `ContestResponse` / `ContestDetailResponse` (Module 3): add `boolean resultsPublished, Instant resultsPublishedAt`.

---

## 11. New Error Codes

| Code | HTTP | Thrown when |
|---|---|---|
| `RESULT_NOT_FOUND` | 404 | No result row for that (contest, candidate), or the candidate isn't assigned |
| `RESULTS_NOT_READY` | 409 | Publish while the contest isn't `COMPLETED`, or sessions are in progress, submissions judging, or results missing. **The message carries the counts** |
| `RESULTS_NEED_REVIEW` | 409 | Publish with `NEEDS_REVIEW` results and `acknowledgeFlagged = false` |
| `RESULTS_ALREADY_PUBLISHED` | 409 | Publish when already published |
| `RESULTS_NOT_PUBLISHED` | 409 | Unpublish when not published |
| `RESULTS_PUBLISHED_LOCKED` | 409 | Evaluate or rejudge (Module 8) while published |
| `SUBMISSION_NOT_EVALUABLE` | 409 | Evaluate a RUN, a `PENDING`/`SYSTEM_ERROR` row, or during an in-progress session |
| `SUBMISSION_NOT_COUNTED` | 409 | Evaluate a SUBMIT that isn't the counted one for its question |
| `ADJUSTED_SCORE_OUT_OF_RANGE` | 400 | `adjustedScore` above the question's points, or more than 2 decimals |

All are `ConflictException(code, message)` / `BadRequestException` / `ResourceNotFoundException`, so they arrive as `{ success: false, message: "<CODE>: <message>" }` and the frontend's `getErrorCode` picks them up unchanged.

---

## 12. Cross-Module Changes Required in This Module

### 12.1 Module 8: verdict visibility after publish
In `SubmissionService.getSubmission` and `getMyHistory`, replace

```java
session.getStatus() == SessionStatus.IN_PROGRESS
```
with
```java
session.getStatus() == SessionStatus.IN_PROGRESS
        || resultVisibilityService.isPublished(session.getContestId())
```
Nothing else in Module 8 changes: `SubmissionMapper` already takes `resultsVisible`. Hidden test cases stay a `passed/total` summary even after publishing; that is a Module 8 rule, not a timing rule.

### 12.2 Module 8: block rejudge while published
At the start of `SubmissionPersistenceService.prepareForRejudge` (inside its transaction), load the submission's contest **with `findByIdForUpdate`** and throw `ConflictException("RESULTS_PUBLISHED_LOCKED", …)` if it's published. Taking the contest lock here also orders rejudge against publish.

### 12.3 Module 3: contest lock + publish fields
- `ContestRepository.findByIdForUpdate` (Section 9.3).
- `Contest.resultsPublishedAt`, `resultsPublishedBy`, `isResultsPublished()`.
- `ContestResponse` / `ContestDetailResponse` (and `ContestService.toContestResponse`) gain `resultsPublished`, `resultsPublishedAt`.

### 12.4 Module 8: new repository queries
`countFinalizedSessionsWithPendingSubmit` and the per-question attempt stats (Section 9.3). Place them in `SubmissionRepository`; Module 9 only calls them.

### 12.5 Nothing in Module 6 changes
Sessions, the auto-submit scheduler and `SessionFinalizedEvent` are used as they are.

---

## 13. Security Considerations

- **Publish boundary in one place:** `ResultMapper.toCandidateView` nulls everything when unpublished, and `ResultVisibilityService` is the only visibility switch for Module 8. Prove it with **JSON-level tests**: the serialized `/results/me` response before publish contains no score, rank or question data, and after publish it contains no `autoScore`, `comments`, `evaluatorName` or any other candidate's ID.
- **Object-level authorization:** candidate ID from the JWT; unassigned → 404; staff-only endpoints by `@PreAuthorize`. A candidate can never reach `/results/candidates/{id}`, even with their own ID.
- **Published numbers can't move silently:** evaluate and rejudge are refused while published; a stray recompute is skipped; unpublishing is an audited, reasoned action.
- **Every privileged action is audited:** publish (with `acknowledgeFlagged`), unpublish (with reason), manual recompute, every manual evaluation (auto → adjusted).
- **Override comments are internal** and never serialized into a candidate DTO.
- **No source code in logs** (Module 7/8 rule). Log result IDs, totals and counts only.

---

## 14. Sequence of Implementation

Follow strictly. Each step must be provably working before you go on. Run tests with `./mvnw test -DargLine=-Duser.timezone=UTC` (Postgres rejects the JVM default `Asia/Calcutta`).

**Step 0 — Verify Module 8** (Section 3). No code.

**Step 1 — Schema, enums and entities**
- `ResultStatus`, `ReviewReason`, `ResultAuditAction`; `V12__create_result_tables.sql`; the three entities; the `Contest` fields.
- Start the app on a **fresh** database: Flyway applies V12 and `ddl-auto: validate` passes. Then start it on your existing local database: V12 applies on top of V11 with existing contests unaffected (`results_published_at` NULL).

**Step 2 — Repositories**
- Section 9.3, including the new Module 8 and Module 3 queries. Integration-test the active-override query on **real Postgres** (`DISTINCT ON`): newest wins; a revert row means none is active; per-question isolation.

**Step 3 — `ResultCalculator` + `RankingService` + unit tests (pure, fast)**
- Calculator: no submissions → all zeros; counted scores summed; override replaces a question's score; revert restores auto; override on a non-counted (outdated) submission → applies + `OVERRIDE_OUTDATED`; system-error count > 0 → `UNRESOLVED_SYSTEM_ERROR`; `timeTakenSeconds` from the latest counted submission minus `startedAt`; 2-decimal rounding.
- Ranking: strict order; equal score → faster wins; equal score and time → shared rank and the next one skips (1, 2, 2, 4); `ABSENT` unranked; `NULL` time sorts last; `10.0` equals `10.00`.

**Step 4 — `ResultService` recompute + listeners**
- `recomputeCandidate`, `recomputeContest`, `createAbsentResults`, `rerank`, both listeners.
- Integration test on real Postgres: seed a contest with three candidates (two finished, one absent), run recompute twice → identical rows (idempotent), correct ranks, question rows replaced rather than duplicated.

**Step 5 — Read endpoints + `ResultMapper` + leak tests**
- Leaderboard, candidate detail, `/me`. MockMvc authorization matrix: admin, evaluator, assigned candidate, unassigned candidate (404), anonymous (401).
- JSON leak tests from Section 13, before and after publish.

**Step 6 — Readiness + publish/unpublish**
- Every 2.8 precondition has its own test: not completed, already published, in progress, judging, missing, needs review without and with acknowledgement. Audit rows are written.

**Step 7 — Manual evaluation**
- `ManualEvaluationService` + controller. Tests: override → total and rank update in the response; revert → back to auto; non-counted → 409; RUN/PENDING/SYSTEM_ERROR → 409; in-progress session → 409; above points → 400; three decimals → 400; blank comment → 400; published → 409; history lists newest first.

**Step 8 — Module 8 integration**
- Section 12.1 and 12.2. Tests: after the session ends and before publish, a candidate's history and detail hide verdicts; after publish, they show them, with hidden cases still a summary; rejudge while published → 409.

**Step 9 — End-to-end with a short contest**
- A 3-minute contest with three candidates: A solves everything, B solves one question, C never starts. Let it complete. Expect: B and A get results as their sessions finalize; C becomes `ABSENT` at completion; readiness becomes `readyToPublish`; an Evaluator overrides B's question → B's total and rank change; Admin publishes; A and B see their results on `/me`, and C sees `ABSENT`; evaluate and rejudge are now refused; unpublish → `/me` hides everything again.
- Force a judge failure on one SUBMIT (stop Judge0 mid-submit): that candidate is `NEEDS_REVIEW`, and publishing without acknowledgement → 409. Rejudge after restarting Judge0 → result recomputes → `SCORED`.

**Step 10 — Full build**
- `./mvnw compile` and `./mvnw test -DargLine=-Duser.timezone=UTC` both green.

---

## 15. Definition of Done

- [ ] Flyway `V12` applies on a fresh DB and on top of an existing V11 DB; schema validation passes
- [ ] Every assigned candidate ends up with exactly one result row once the contest completes (`SCORED`, `NEEDS_REVIEW` or `ABSENT`)
- [ ] Totals equal the sum of counted SUBMIT scores (Module 8 rule), with active overrides replacing a question's score
- [ ] Leaderboard ranks by total score, then time taken from each candidate's own start; equal results share a rank (1, 2, 2, 4); absentees are unranked
- [ ] Recompute is idempotent and safe to run at any time before publishing; concurrent recomputes in one contest never corrupt ranks (contest lock)
- [ ] Evaluator override updates the total and re-ranks in the same request; revert restores the automatic score; history is append-only and audited
- [ ] Overrides are refused on non-counted, pending, judge-error and RUN submissions, during a live session, above the question's points, and while results are published
- [ ] Publish is refused until the contest is completed, nothing is in progress or judging, and no result is missing; flagged results need explicit acknowledgement
- [ ] Candidates see nothing about their result before publish (`published: false`, nulls), proven by a JSON-level test
- [ ] After publish, candidates see only their own total, rank, per-question scores and "adjusted" flags, never comments, evaluators, auto scores or other candidates
- [ ] After publish, Module 8 shows candidates their own verdicts again; hidden test cases remain summarized
- [ ] Rejudge and evaluate are refused while published; unpublish (with a reason) reopens them
- [ ] Publish, unpublish, manual recompute and every evaluation write an audit entry
- [ ] Leaderboard read is a constant number of queries regardless of contest size
- [ ] `./mvnw compile` — BUILD SUCCESS; `./mvnw test -DargLine=-Duser.timezone=UTC` — all tests pass

---

## 16. What Later Modules Will Add (Do Not Build Yet)

- **Module 10 (Analytics):** reads `results` and `result_question_scores` for score distribution, per-question averages and time-taken analysis. `result_question_scores` exists partly so Module 10 doesn't need to re-derive per-question scores from raw submissions.
- **Module 11 (WebSocket):** listens to `ResultsPublishedEvent` and pushes "results are out" to that contest's candidates, and pushes leaderboard refreshes to staff instead of polling.
- **Module 13 (Live Monitoring):** proctoring flags can become a third `ReviewReason` (for example `PROCTORING_FLAGGED`) without changing the publish flow.
- **Future enhancements (roadmap):** CSV/PDF export of the leaderboard (`GET …/results/export`), candidate-facing feedback separate from internal comments, certificates, and evaluator↔contest assignment to replace 2.13's "any evaluator" rule.

Module 9 is complete when every candidate has a correct, explainable, ranked result, Evaluators can adjust it with a reason, and nothing reaches a candidate before an Admin publishes.

---

## 17. File Summary

| File | Action | Notes |
|---|---|---|
| `db/migration/V12__create_result_tables.sql` | **New** | Section 6 |
| `common/enums/ResultStatus.java` | **New** | |
| `common/enums/ReviewReason.java` | **New** | |
| `result/ResultAuditAction.java` | **New** | audit action constants |
| `result/entity/Result.java` | **New** | |
| `result/entity/ResultQuestionScore.java` | **New** | |
| `result/entity/ManualEvaluation.java` | **New** | append-only |
| `result/repository/ResultRepository.java` | **New** | |
| `result/repository/ResultQuestionScoreRepository.java` | **New** | |
| `result/repository/ManualEvaluationRepository.java` | **New** | history, newest first (active override picked in Java) |
| `result/dto/*` | **New** | requests, readiness, leaderboard, staff view, candidate view, evaluation response |
| `result/service/ResultService.java` | **New** | recompute, rerank, publish/unpublish, reads |
| `result/service/ResultCalculator.java` | **New** | pure totals and review reasons |
| `result/service/RankingService.java` | **New** | pure ranking + tie-break |
| `result/service/ManualEvaluationService.java` | **New** | |
| `result/service/ResultReadinessService.java` | **New** | |
| `result/service/ResultVisibilityService.java` | **New** | used by Module 8 |
| `result/service/ResultMapper.java` | **New** | only place views are built |
| `result/event/SessionScoringCompletedResultListener.java` | **New** | |
| `result/event/ContestCompletedResultListener.java` | **New** | |
| `result/event/ResultsPublishedEvent.java` | **New** | consumed in Module 11 |
| `result/controller/ResultController.java` | **New** | 6 endpoints |
| `result/controller/ManualEvaluationController.java` | **New** | `POST /api/submissions/{id}/evaluate` |
| `contest/entity/Contest.java` (Module 3) | **Modified** | publish fields |
| `contest/repository/ContestRepository.java` (Module 3) | **Modified** | `findByIdForUpdate` |
| `contest/dto/ContestResponse.java`, `ContestDetailResponse.java`, `ContestService` (Module 3) | **Modified** | `resultsPublished`, `resultsPublishedAt` |
| `submission/repository/SubmissionRepository.java` (Module 8) | **Modified** | readiness + attempt-stat queries |
| `submission/service/SubmissionService.java` (Module 8) | **Modified** | `resultsVisible` includes "published" |
| `submission/service/SubmissionPersistenceService.java` (Module 8) | **Modified** | rejudge refused while published |
| Tests | **New** | calculator, ranking, readiness, repository (Postgres), MockMvc authz, JSON leak, Module 8 visibility regression |
