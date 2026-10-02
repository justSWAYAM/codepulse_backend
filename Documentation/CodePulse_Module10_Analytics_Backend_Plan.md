# CodePulse Enterprise — Module 10 Backend Build Plan
### Analytics Service

**Purpose:** Turn a contest's results and submissions into a small set of insights for Admins and Evaluators:
- how scores are distributed;
- whether each question was as hard as its label claims;
- how long candidates took;
- which test cases look broken;
- which results need attention.

Module 10 is a **read-only aggregation layer**. It owns no business state and writes nothing, apart from one index migration (Section 6). Every number it returns is derived from tables owned by Modules 3–9.

**Depends on:** Module 0 (Foundation) + Module 1 (Auth) + Module 3 (`contests`, `contest_candidates`) + Module 4 (`questions`, `difficulty`, `points`) + Module 5 (`test_cases`) + Module 6 (`assessment_sessions`) + Module 8 (`submissions`, `submission_test_case_results`) + Module 9 (`results`, `result_question_scores`, `ResultReadinessService`).

**Status:** plan, not yet built (written 2026-10-03 against backend `feature/module-9-results` @ `4f58c75`). Module 10 must be built on top of Module 9.

> **Blocker check before any Module 10 code:** Module 9 must be producing one result row per assigned candidate (`SCORED`, `NEEDS_REVIEW` or `ABSENT`). Run the Module 9 browser scenario (one short contest, three candidates) first. It leaves a completed contest whose real data you can check every Module 10 number against. Then run `./mvnw test -DargLine=-Duser.timezone=UTC`; it must be green.

---

## 1. What This Module Inherits (Do Not Rebuild)

| From | Component | How it's used here |
|---|---|---|
| Module 0 | `ApiResponse<T>` | Every endpoint is wrapped, built the way `ContestController` builds it |
| Module 0 | `GlobalExceptionHandler`, `ConflictException`, `ResourceNotFoundException` | One new code (Section 11). Arrives as `"<CODE>: <message>"` |
| Module 1 | `@PreAuthorize`, `SecurityConfig` | `/api/contests/**` is already `authenticated()`; every Module 10 method is `hasAnyRole('EVALUATOR','ADMIN')` |
| Module 3 | `ContestRepository.findById`, `Contest.status`, `durationMinutes` | The contest gate (2.6) and the time-bucket width (2.4) |
| Module 4 | `questions` (`points`, `difficulty`, `order_index`) | Per-question columns and the difficulty check (2.5) |
| Module 5 | `test_cases` (`is_sample`, `weight`, `order_index`) | Per-test-case pass rates (2.7) |
| Module 6 | `assessment_sessions` (`started_at`, `submitted_at`, `status`) | Session outcomes and time to first Accepted |
| Module 8 | `submissions`, `submission_test_case_results` | Activity metrics: attempts, verdict mix, languages, Runs |
| Module 9 | `results`, `result_question_scores` | **The** source of every score number (2.1) |
| Module 9 | `ResultReadinessService.build(contest, results)` | The `coverage` block (2.3); readiness is not re-derived |
| Module 9 | `ReviewReason`, `ResultStatus` | The attention summary (2.8) |
| Flyway | Next migration is **`V13`** | `V13__analytics_indexes.sql` (Section 6) |

---

## 2. Decisions to Make Before Writing Any Code

### 2.1 Scores come from Module 9, activity comes from Module 8
There are two kinds of numbers, and they come from two places:

| Kind | Source | Why |
|---|---|---|
| **Scores** (distribution, averages, full/partial/zero) | `results.total_score`, `result_question_scores.final_score` | These are the numbers on the leaderboard, *after* manual overrides. Analytics must never disagree with the leaderboard. Recomputing from `submissions.score` would ignore overrides and the counted rule |
| **Activity** (attempts, verdict mix, languages, time to first Accepted, Runs) | `submissions` (+ `assessment_sessions`) | Describes what candidates *did*, including attempts that don't count |
| **Test-case pass rates** | `submission_test_case_results` of **counted** submissions only (2.7) | One row per candidate, so a candidate with 20 failed attempts doesn't swamp the rate |

`autoScore` (before overrides) is shown only as a secondary figure ("3 results adjusted"). The headline is always the final score.

### 2.2 Aggregate in SQL, not in Java
The roadmap says to avoid pulling raw rows into Java. Module 10 follows that: `AnalyticsRepository` runs **native PostgreSQL aggregate queries** through `NamedParameterJdbcTemplate`. It uses `COUNT(*) FILTER (WHERE …)`, `percentile_cont`, `width_bucket` and `stddev_pop`. Each query returns one row per contest, per bucket, per question or per test case.

- **Why not JPA/JPQL:** JPQL has no `FILTER`, `percentile_cont` or `width_bucket`, and these results are not entities.
- **Why not SQL views:** a view moves query logic into migrations, where it is harder to change and to test. The queries live in one repository class, versioned with the code that reads them. Revisit only if another module needs the same aggregates.
- **Consequence:** like Module 8's counted query, these queries are **PostgreSQL-only**. They are tested on real Postgres (Section 14), never on the H2 test profile.

### 2.3 Coverage: analytics during a live contest are provisional
Analytics are allowed once the contest is `ONGOING`. Results only exist for candidates who have finished, though, so every response carries a `coverage` block built by **Module 9's** `ResultReadinessService`:

```
coverage = { totalCandidates, withResult, inProgress, judging, absent, contestCompleted, provisional }
provisional = !contestCompleted || inProgress > 0 || judging > 0
```

The frontend shows "Provisional — 12 of 40 candidates finished" while `provisional` is true. Publishing (Module 9) has no effect on analytics. Analytics are staff-only, and staff can already see unpublished results.

### 2.4 Distributions are fixed 10 % buckets
- **Score distribution:** each non-absent result's `total_score / max_score` goes into 10 buckets (0–10 %, 10–20 %, …, 90–100 %). 100 % falls into the last bucket (`LEAST(width_bucket(…), 10)`). Percentages rather than raw points keep contests comparable, and the frontend can still label buckets in points (`bucket × maxScore`).
- **Time distribution:** `results.time_taken_seconds` (Module 9: time from the candidate's own start to their last counted submission) as a share of `contest.durationMinutes`, in the same 10 buckets. Results without a time (nothing counted) are left out of the time chart and counted in `noTimeCount`.
- **All 10 buckets are always returned**, including empty ones, so charts never shift.

### 2.5 Difficulty validation: label vs observed
The roadmap asks "are your easy questions actually easy?". The rule:

```
averageRatio = average final score ÷ question points      (over non-absent results)
observedDifficulty = EASY if averageRatio ≥ 0.70, MEDIUM if ≥ 0.40, else HARD
difficultyMatches  = observedDifficulty == question.difficulty
```

- There is **no verdict when there are too few attempts**: below `MIN_SAMPLE = 5` candidates who attempted the question, `observedDifficulty` is `null` ("not enough data").
- The thresholds and `MIN_SAMPLE` are constants in `AnalyticsCalculator`. They are not configuration, because two configurations would give two different answers for the same contest.

### 2.6 When analytics are available
| Contest status | Response |
|---|---|
| `DRAFT`, `PUBLISHED` | **409 `ANALYTICS_NOT_AVAILABLE`**: "Analytics are available once the contest starts." Nothing has happened yet |
| `ONGOING` | 200, `coverage.provisional = true` |
| `COMPLETED` | 200, provisional only while something is still being judged |

A contest that is `ONGOING` but has no results yet returns zeros, all-empty buckets and full coverage numbers. That's an empty state, not an error.

### 2.7 Test-case pass rates use counted submissions only
For a question, the pass rate of each test case is computed over the **counted** SUBMIT of each candidate (`result_question_scores.counted_submission_id`). Each candidate is counted once, with the answer that actually scored.

A test case is flagged **`suspicious`** when, with at least `MIN_SAMPLE` candidates evaluated:
- nobody passed it (0 %), **and**
- the question has at least one other test case that more than 50 % of candidates passed.

A 0 % case next to cases most people pass usually means a wrong expected output. The fix is Module 5 (correct the test case) plus a Module 8 rejudge, before results are published. This check is the most practically useful output of the module for evaluators.

### 2.8 The flagged-candidate summary, pass 1 (before proctoring)
The roadmap ties the "flagged-candidate summary" to Module 13's proctoring events, and allows building analytics in two passes. Pass 1 (this module) reports what already exists, as **counts only** (no names; names live on the leaderboard):

| Item | Source |
|---|---|
| Needs review (by reason) | `results.status = NEEDS_REVIEW`, `review_reasons` |
| Adjusted by an evaluator | `results.adjusted` |
| Time ran out | `assessment_sessions.status = AUTO_SUBMITTED` |
| Absent | `results.status = ABSENT` |
| Finished with zero | non-absent results with `total_score = 0` |

Module 13 adds a `proctoringFlags` count to the same block (Section 16). The frontend links each count to the Module 9 leaderboard filter (`?tab=results&rf=review`, `rf=adjusted`, `rf=absent`), so the actual list of names stays in one place.

### 2.9 Staff only, always
Candidates get **403** on every Module 10 endpoint, before and after results are published. A candidate-facing percentile ("you scored better than 70 %") is a listed future enhancement, not part of v1. When it comes, it goes through Module 9's candidate view, which enforces publish visibility.

### 2.10 No caching in v1
Each endpoint runs 2–5 aggregate queries over at most a few thousand rows, which takes milliseconds with the Section 6 indexes. Caching would mean invalidating on every recompute, override and rejudge, which is complexity without a measured need. The performance test (Step 7) is the evidence. Revisit only if it fails.

---

## 3. Step 0 — Verify Module 9 (no new code)

| Check | How |
|---|---|
| Every assigned candidate of a completed contest has exactly one `results` row | `SELECT status, COUNT(*) FROM results WHERE contest_id = :id GROUP BY status` against the Module 9 browser-test contest |
| `result_question_scores` has one row per (result, question) | `SELECT result_id, COUNT(*) … GROUP BY result_id`, which should equal the number of questions |
| `counted_submission_id` points at a SUBMIT with test-case rows | Join it to `submission_test_case_results` |
| `time_taken_seconds` is set for candidates with a counted submission | Spot-check one candidate against the grading page |

---

## 4. Gaps in Earlier Modules Found While Planning (Fix as Noted)

| Gap | Impact | Where to fix |
|---|---|---|
| `submission_test_case_results` has no index on `test_case_id` | The per-test-case query (2.7) scans the table | `V13` (Section 6) |
| No index on `submissions (session_id, submission_type, status)` | Activity queries filter by these three columns for every session in a contest | `V13` |
| `results` has no index on `(contest_id, status)` | Most overview queries filter by contest and status | `V13` |
| The roadmap's flagged-candidate summary needs Module 13 data | Can't be complete now | Two passes (2.8) |

---

## 5. New Types

No new enums. `Difficulty`, `ResultStatus`, `ReviewReason`, `SubmissionStatus` and `SessionStatus` are reused.

Constants live in `analytics/service/AnalyticsCalculator`:

```java
public static final int BUCKETS = 10;
public static final int MIN_SAMPLE = 5;
public static final BigDecimal EASY_AT = new BigDecimal("0.70");
public static final BigDecimal MEDIUM_AT = new BigDecimal("0.40");
public static final BigDecimal SUSPICIOUS_PEER_PASS_RATE = new BigDecimal("0.50");
```

---

## 6. Database Migration

**File:** `src/main/resources/db/migration/V13__analytics_indexes.sql`
> Indexes only. Module 10 creates no tables and writes no rows.

```sql
-- Per-test-case pass rates (plan 2.7)
CREATE INDEX idx_stcr_test_case ON submission_test_case_results (test_case_id);

-- Activity aggregates: SUBMIT/RUN counts and verdicts per session
CREATE INDEX idx_submissions_session_type_status ON submissions (session_id, submission_type, status);

-- Overview aggregates filter results by contest and status
CREATE INDEX idx_results_contest_status ON results (contest_id, status);
```

Check each index with `EXPLAIN ANALYZE` on the seeded performance data (Step 7). Drop any the planner doesn't use; an unused index only slows writes.

---

## 7. Configuration

None. The thresholds are code constants (2.5). There are no feature flags.

---

## 8. API Surface

All responses use `ApiResponse<T>`. All endpoints require `hasAnyRole('EVALUATOR','ADMIN')`.

| Method | Endpoint | Description | Success |
|---|---|---|---|
| GET | `/api/contests/{contestId}/analytics/overview` | Coverage, score summary, score and time distributions, session outcomes, attention summary | `200` `ContestAnalyticsResponse` |
| GET | `/api/contests/{contestId}/analytics/questions` | One entry per question: score breakdown, difficulty check, attempts, verdict mix, languages, time to first Accepted | `200` `QuestionAnalyticsResponse` |
| GET | `/api/contests/{contestId}/analytics/questions/{questionId}/test-cases` | Pass rate and failure mix per test case for one question, with `suspicious` flags | `200` `TestCaseAnalyticsResponse` |

Rules:
- The first two endpoints are the roadmap's. The third supports 2.7, which is too detailed to include for every question in the questions response.
- A `questionId` that doesn't belong to the contest → **404**, not an empty list.
- Responses contain **no candidate identities**, only counts. Test-case responses contain IDs, order, sample flag and weight, but **no input or expected output**. Staff open the test case in Module 5's screens to see those.

---

## 9. Backend Components

### 9.1 Package Structure

```
com.codepulse_backend/
└── analytics/
    ├── repository/
    │   └── AnalyticsRepository.java              ← @Repository, NamedParameterJdbcTemplate, native SQL only
    ├── dto/
    │   ├── ContestAnalyticsResponse.java         ← includes Coverage, ScoreSummary, Bucket, SessionOutcomes, AttentionSummary
    │   ├── QuestionAnalyticsResponse.java        ← includes QuestionStats, VerdictCount, LanguageCount
    │   └── TestCaseAnalyticsResponse.java        ← includes TestCaseStats
    ├── service/
    │   ├── AnalyticsService.java                 ← contest gate, orchestration, coverage via Module 9
    │   └── AnalyticsCalculator.java              ← pure: buckets, difficulty, suspicious flags, rounding
    └── controller/
        └── AnalyticsController.java              ← /api/contests/{contestId}/analytics/**
```

The roadmap lists `AnalyticsRepository`, `AnalyticsService`, `AnalyticsController` and the two response DTOs. `AnalyticsCalculator` is split out for the same reason as Module 8's `ScoringService` and Module 9's `ResultCalculator`: the rules (2.4, 2.5, 2.7) become pure functions you can unit-test without a database. `TestCaseAnalyticsResponse` belongs to the third endpoint.

### 9.2 `AnalyticsRepository`: the queries

A plain `@Repository` class (not a Spring Data interface; there's no entity). Every method takes `contestId` (and `questionId` where relevant) as named parameters and maps rows with a small lambda `RowMapper` into package-private records.

**Score summary (one row):**
```sql
SELECT COUNT(*) FILTER (WHERE status <> 'ABSENT')                       AS participants,
       COUNT(*) FILTER (WHERE status = 'ABSENT')                        AS absent,
       COUNT(*) FILTER (WHERE status = 'NEEDS_REVIEW')                  AS needs_review,
       COUNT(*) FILTER (WHERE adjusted)                                 AS adjusted,
       COUNT(*) FILTER (WHERE status <> 'ABSENT' AND total_score = 0)   AS zero_scores,
       AVG(total_score)        FILTER (WHERE status <> 'ABSENT')        AS mean,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY total_score)
                               FILTER (WHERE status <> 'ABSENT')        AS median,
       MIN(total_score)        FILTER (WHERE status <> 'ABSENT')        AS min,
       MAX(total_score)        FILTER (WHERE status <> 'ABSENT')        AS max,
       stddev_pop(total_score) FILTER (WHERE status <> 'ABSENT')        AS std_dev,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY time_taken_seconds)
                               FILTER (WHERE status <> 'ABSENT')        AS median_time_seconds
FROM results
WHERE contest_id = :contestId;
```
`percentile_cont` ignores NULLs, so candidates with no counted submission don't skew the median time.

**Review reasons (rows: reason, count):** `review_reasons` is a JSON array stored as text by `StringListConverter`, so use
`SELECT reason, COUNT(*) FROM results, jsonb_array_elements_text(review_reasons::jsonb) AS reason WHERE contest_id = :contestId AND status = 'NEEDS_REVIEW' GROUP BY reason`.

**Score distribution (rows: bucket 1..10, count):**
```sql
SELECT LEAST(width_bucket(total_score / max_score, 0, 1, 10), 10) AS bucket, COUNT(*) AS candidates
FROM results
WHERE contest_id = :contestId AND status <> 'ABSENT' AND max_score > 0
GROUP BY bucket;
```

**Time distribution (rows: bucket, count):** the same shape with `time_taken_seconds / (:durationMinutes * 60.0)` and `time_taken_seconds IS NOT NULL`. A candidate can't take longer than the duration, but clamp anyway with `LEAST(…, 10)`.

**Session outcomes (one row):**
```sql
SELECT COUNT(*) FILTER (WHERE status = 'SUBMITTED')      AS submitted,
       COUNT(*) FILTER (WHERE status = 'AUTO_SUBMITTED') AS auto_submitted,
       COUNT(*) FILTER (WHERE status = 'IN_PROGRESS')    AS in_progress
FROM assessment_sessions
WHERE contest_id = :contestId;
```

**Per-question scores (one row per question):**
```sql
SELECT q.id, q.title, q.order_index, q.points, q.difficulty,
       COUNT(rqs.id)                                                     AS participants,
       COUNT(rqs.counted_submission_id)                                  AS attempted,
       COUNT(*) FILTER (WHERE rqs.final_score >= rqs.max_points AND rqs.max_points > 0) AS full_marks,
       COUNT(*) FILTER (WHERE rqs.final_score > 0 AND rqs.final_score < rqs.max_points) AS partial,
       COUNT(*) FILTER (WHERE rqs.id IS NOT NULL AND rqs.final_score = 0)               AS zero,
       AVG(rqs.final_score)                                              AS avg_score,
       COUNT(rqs.manual_evaluation_id)                                   AS adjusted
FROM questions q
LEFT JOIN (result_question_scores rqs
           JOIN results r ON r.id = rqs.result_id AND r.status <> 'ABSENT')
       ON rqs.question_id = q.id
WHERE q.contest_id = :contestId
GROUP BY q.id
ORDER BY q.order_index;
```
`zero` includes candidates who didn't attempt the question. The frontend shows "not attempted" as `participants − attempted`, and "attempted but scored 0" as `zero − (participants − attempted)`.

**Per-question activity (rows: question_id, submission_type, status, language, count):**
```sql
SELECT s.question_id, s.submission_type, s.status, s.language, COUNT(*) AS n
FROM submissions s
JOIN assessment_sessions a ON a.id = s.session_id
WHERE a.contest_id = :contestId AND s.status <> 'PENDING'
GROUP BY s.question_id, s.submission_type, s.status, s.language;
```
One query feeds the verdict mix (SUBMIT by status), the language mix (SUBMIT by language), SUBMIT and RUN totals, and attempts per candidate (SUBMITs ÷ attempted). The service folds the rows per question.

**Time to first Accepted (one row per question):**
```sql
SELECT question_id,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY seconds) AS median_seconds,
       COUNT(*)                                             AS solvers
FROM (SELECT s.question_id, EXTRACT(EPOCH FROM MIN(s.submitted_at) - a.started_at) AS seconds
      FROM submissions s
      JOIN assessment_sessions a ON a.id = s.session_id
      WHERE a.contest_id = :contestId AND s.submission_type = 'SUBMIT' AND s.status = 'ACCEPTED'
      GROUP BY s.question_id, s.session_id, a.started_at) firsts
GROUP BY question_id;
```

**Per-test-case pass rates (one row per test case of a question):**
```sql
SELECT tc.id, tc.order_index, tc.is_sample, tc.weight,
       COUNT(stcr.id)                                                       AS evaluated,
       COUNT(*) FILTER (WHERE stcr.status = 'PASSED')                       AS passed,
       COUNT(*) FILTER (WHERE stcr.status = 'WRONG_ANSWER')                 AS wrong_answer,
       COUNT(*) FILTER (WHERE stcr.status = 'TIME_LIMIT_EXCEEDED')          AS time_limit,
       COUNT(*) FILTER (WHERE stcr.status = 'MEMORY_LIMIT_EXCEEDED')        AS memory_limit,
       COUNT(*) FILTER (WHERE stcr.status = 'RUNTIME_ERROR')                AS runtime_error,
       COUNT(*) FILTER (WHERE stcr.status = 'COMPILATION_ERROR')            AS compilation_error
FROM test_cases tc
LEFT JOIN submission_test_case_results stcr
       ON stcr.test_case_id = tc.id
      AND stcr.submission_id IN (
            SELECT rqs.counted_submission_id
            FROM result_question_scores rqs
            JOIN results r ON r.id = rqs.result_id
            WHERE r.contest_id = :contestId AND rqs.question_id = :questionId
              AND rqs.counted_submission_id IS NOT NULL)
WHERE tc.question_id = :questionId
GROUP BY tc.id
ORDER BY tc.order_index;
```

> **Write and run every query in `psql` against the Module 9 test contest before wrapping it in Java** (roadmap Step 2). Paste the verified SQL into the repository unchanged.

### 9.3 `AnalyticsCalculator`: pure functions, no repositories

```java
public List<Bucket> fillBuckets(Map<Integer, Long> countsByBucket)          // always 10 entries, 1..10
public Difficulty observedDifficulty(BigDecimal avgScore, int points, long attempted)   // null below MIN_SAMPLE
public boolean suspicious(TestCaseRow row, List<TestCaseRow> sameQuestion)  // plan 2.7
public BigDecimal ratio(long part, long whole)                              // 4 decimals, 0 when whole = 0
public BigDecimal round2(BigDecimal value)                                  // HALF_UP, null-safe
```
It implements 2.4, 2.5 and 2.7 exactly, and every edge case is unit-testable (Step 2).

### 9.4 `AnalyticsService`

`@Transactional(readOnly = true)` on every method.

**`getOverview(contestId)`:**
1. `contestRepository.findById` → 404; DRAFT/PUBLISHED → `ANALYTICS_NOT_AVAILABLE` (2.6).
2. `coverage` from `resultReadinessService.build(contest, resultRepository.findAllByContestId(contestId))` (Module 9; one indexed read).
3. Score summary, review reasons, score buckets, time buckets and session outcomes from the repository.
4. `AnalyticsCalculator` fills buckets, rounds, and computes `provisional`.
5. Return `ContestAnalyticsResponse`.

**`getQuestions(contestId)`:** contest gate; three queries (per-question scores, per-question activity, time to first Accepted). Fold the activity rows per question in one pass. Apply `observedDifficulty`. Return the entries in `order_index` order, with `coverage` included as well, so the questions view can show the provisional banner on its own.

**`getTestCases(contestId, questionId)`:** contest gate; `questionRepository.existsByIdAndContestId` → else 404; one query; `AnalyticsCalculator.suspicious` per row.

**Query budget:** overview ≤ 6 queries, questions ≤ 4, test cases ≤ 2, **regardless of contest size**.

### 9.5 `AnalyticsController`
`@RestController @RequestMapping("/api/contests/{contestId}/analytics")`, with `@PreAuthorize("hasAnyRole('EVALUATOR','ADMIN')")` on the class. Three `@GetMapping`s. Each wraps the result with `new ApiResponse<>(true, data, "...", Instant.now(), null)`, like `ContestController`.

---

## 10. DTO Notes

All numbers are JSON numbers. Ratios are `0..1` with 4 decimals; scores have 2 decimals. Fields that can be absent are `null` and, with Jackson's `non_null` setting, **omitted** from the JSON. The frontend plan types them as optional.

```java
public record ContestAnalyticsResponse(
        UUID contestId, String contestTitle, ContestStatus contestStatus, int durationMinutes,
        Coverage coverage, ScoreSummary scores,
        List<Bucket> scoreDistribution, List<Bucket> timeDistribution, long noTimeCount,
        SessionOutcomes sessions, AttentionSummary attention) {

    public record Coverage(int totalCandidates, int withResult, int inProgress, int judging,
                           int absent, boolean contestCompleted, boolean provisional) {}

    public record ScoreSummary(BigDecimal maxScore, long participants, BigDecimal mean, BigDecimal median,
                               BigDecimal min, BigDecimal max, BigDecimal stdDev, Long medianTimeSeconds) {}

    /** index 1..10; fromRatio/toRatio 0.0..1.0 */
    public record Bucket(int index, BigDecimal fromRatio, BigDecimal toRatio, long count) {}

    public record SessionOutcomes(long submitted, long autoSubmitted, long inProgress, long notStarted) {}

    public record AttentionSummary(long needsReview, Map<ReviewReason, Long> needsReviewByReason,
                                   long adjusted, long autoSubmitted, long absent, long zeroScores) {}
}

public record QuestionAnalyticsResponse(Coverage coverage, List<QuestionStats> questions) {
    public record QuestionStats(
            UUID questionId, String title, int orderIndex, int points, Difficulty difficulty,
            long participants, long attempted, long fullMarks, long partial, long zero,
            BigDecimal averageScore, BigDecimal averageRatio,
            Difficulty observedDifficulty /* null = not enough data */, boolean difficultyMatches,
            long adjusted, long submitCount, long runCount, BigDecimal attemptsPerCandidate,
            Long medianSecondsToAccepted, long solvers,
            List<VerdictCount> verdicts, List<LanguageCount> languages) {}
    public record VerdictCount(SubmissionStatus status, long count) {}
    public record LanguageCount(String language, long count) {}
}

public record TestCaseAnalyticsResponse(UUID questionId, String questionTitle, long candidatesEvaluated,
                                        List<TestCaseStats> testCases) {
    public record TestCaseStats(UUID testCaseId, int orderIndex, boolean sample, int weight,
                                long evaluated, long passed, BigDecimal passRate,
                                long wrongAnswer, long timeLimit, long memoryLimit,
                                long runtimeError, long compilationError, boolean suspicious) {}
}
```

`SessionOutcomes.notStarted = totalCandidates − (submitted + autoSubmitted + inProgress)`.

---

## 11. New Error Codes

| Code | HTTP | Thrown when |
|---|---|---|
| `ANALYTICS_NOT_AVAILABLE` | 409 | The contest is `DRAFT` or `PUBLISHED` (2.6) |

A missing contest or a question outside the contest uses the existing `ResourceNotFoundException` (404). The error arrives as `{ success: false, message: "ANALYTICS_NOT_AVAILABLE: Analytics are available once the contest starts" }`.

---

## 12. Cross-Module Changes Required in This Module

### 12.1 Module 9: none in code
`ResultReadinessService.build` is reused as is. If its signature changes during the build, keep Module 9's own callers working; Module 10 adapts.

### 12.2 Module 4: confirm the repository method
`QuestionRepository.existsByIdAndContestId` already exists. Reuse it for the question-in-contest check.

### 12.3 Migration only: `V13`
No entity changes, so `ddl-auto: validate` is unaffected.

---

## 13. Security Considerations

- **Staff only** (2.9), checked by `@PreAuthorize` on the controller class, plus a MockMvc test that a candidate gets 403 on all three endpoints, before **and** after results are published.
- **No identities** in any analytics response (counts only), so analytics can't be a side door around Module 9's leaderboard permissions.
- **No hidden test data:** test-case analytics never include input, expected output or actual output. Verify with a JSON assertion in the integration test.
- **Native SQL uses named parameters only:** no string concatenation of IDs or values into SQL.
- **No source code is read at all** by this module.

---

## 14. Sequence of Implementation

Follow strictly. Each step must be provably working before you go on. Run tests with `./mvnw test -DargLine=-Duser.timezone=UTC`.

**Step 0 — Verify Module 9** (Section 3), on the browser-test contest.

**Step 1 — SQL first, in `psql`**
- Run every Section 9.2 query against the Module 9 test contest. Check the numbers by hand against the leaderboard: mean, buckets, per-question full marks, and the test case nobody passed.
- Add `V13__analytics_indexes.sql`. Restart the app so Flyway applies it.

**Step 2 — `AnalyticsCalculator` + unit tests (pure, fast)**
- Buckets: empty input → 10 zero buckets; counts land in the right index; ratios at the edges.
- Difficulty: 0.70 → EASY, 0.6999 → MEDIUM, 0.40 → MEDIUM, 0.3999 → HARD; attempted 4 → `null`; points 0 → `null`.
- Suspicious: 0 % with a peer at 60 % and 5 evaluated → true; 0 % with 4 evaluated → false; 0 % with no peer above 50 % → false.
- `ratio(…, 0)` → 0; rounding is HALF_UP.

**Step 3 — `AnalyticsRepository`**
- Paste the verified queries. Integration tests on **real Postgres** (`@SpringBootTest`, local profile, committed data and JDBC cleanup, the same pattern as `ResultFlowIntegrationTest`). Seed a 4-candidate contest with known scores and assert each query's raw output.

**Step 4 — `AnalyticsService` + DTOs**
- Contest gate (DRAFT/PUBLISHED → 409), coverage from Module 9, activity folding, and the "not enough data" path.

**Step 5 — Controller + authorization**
- MockMvc matrix: admin 200, evaluator 200, candidate 403 (before and after publish), anonymous 401, unknown contest 404, question from another contest 404.
- JSON assertions: no `email`, `candidateName`, `input` or `expectedOutput` anywhere in the three responses.

**Step 6 — End-to-end on the Module 9 test contest**
- Call all three endpoints for the browser-test contest. Every number must match the leaderboard and the grading pages. For example, if Ravi's Q1 is adjusted, Q1's `adjusted` = 1 and the average uses the adjusted score.

**Step 7 — Performance**
- Seed 200 candidates × 3 questions × about 3 SUBMITs (≈ 1,800 submissions, ≈ 7,000 test-case rows) through JDBC batch inserts in an integration test.
- Assert each endpoint answers in **< 500 ms** (warm), and record the timings in a comment in the test.
- Run `EXPLAIN ANALYZE` on the three heaviest queries and keep only the indexes the planner uses. This is the roadmap's "performance consideration" for the report.

**Step 8 — Full build**
- `./mvnw compile` and `./mvnw test -DargLine=-Duser.timezone=UTC` both green.

---

## 15. Definition of Done

- [ ] `V13` applies cleanly on top of V12; schema validation passes
- [ ] Overview, questions and test-case endpoints return correct numbers for the Module 9 browser-test contest (checked by hand against the leaderboard)
- [ ] Every score number equals what the leaderboard shows, overrides included
- [ ] Score and time distributions always have 10 buckets; 100 % lands in the last bucket
- [ ] Difficulty check gives "not enough data" below 5 attempts, and the label-vs-observed verdict otherwise
- [ ] Test-case pass rates count each candidate once (counted submission) and flag 0 % outliers as suspicious
- [ ] Analytics during a live contest are marked provisional with exact coverage numbers; DRAFT/PUBLISHED return `ANALYTICS_NOT_AVAILABLE`
- [ ] Candidates get 403 on every endpoint, before and after publish; responses contain no identities and no test data
- [ ] Each endpoint stays within its query budget and answers in < 500 ms with ~1,800 seeded submissions
- [ ] `./mvnw compile` — BUILD SUCCESS; `./mvnw test -DargLine=-Duser.timezone=UTC` — all tests pass

---

## 16. What Later Modules Will Add (Do Not Build Yet)

- **Module 11 (WebSocket):** push "results changed" so the analytics view refreshes without polling during a live contest.
- **Module 13 (Live Monitoring), analytics pass 2:** add `proctoringFlags` (and per-type counts) to `AttentionSummary` from `proctoring_events`, and a "flagged candidates" count linking to the monitoring dashboard.
- **Future enhancements (roadmap):**
  - a candidate's performance trend across contests (`/api/users/{id}/analytics`);
  - exportable analytics reports (CSV/PDF);
  - a candidate-facing percentile through Module 9's candidate view, after publish;
  - per-branch and per-division comparisons, using Module 2's academic fields.

Module 10 is complete when an Admin or Evaluator can open one screen and see, with numbers that match the leaderboard, how a contest went, which questions were mislabelled and which test cases look broken.

---

## 17. File Summary

| File | Action | Notes |
|---|---|---|
| `db/migration/V13__analytics_indexes.sql` | **New** | 3 indexes (Section 6) |
| `analytics/repository/AnalyticsRepository.java` | **New** | native PostgreSQL aggregates via `NamedParameterJdbcTemplate` |
| `analytics/dto/ContestAnalyticsResponse.java` | **New** | coverage, scores, buckets, sessions, attention |
| `analytics/dto/QuestionAnalyticsResponse.java` | **New** | per-question stats |
| `analytics/dto/TestCaseAnalyticsResponse.java` | **New** | per-test-case pass rates |
| `analytics/service/AnalyticsService.java` | **New** | contest gate, orchestration |
| `analytics/service/AnalyticsCalculator.java` | **New** | pure rules (2.4, 2.5, 2.7) |
| `analytics/controller/AnalyticsController.java` | **New** | 3 endpoints, staff only |
| Tests | **New** | calculator (unit), repository + service (Postgres), MockMvc authorization + JSON leak checks, performance seed test |
