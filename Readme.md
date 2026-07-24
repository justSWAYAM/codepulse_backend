# CodePulse — Enterprise Implementation Roadmap
### Service-Oriented, Module-by-Module Build Plan

**Stack:** React 19 + TS + Vite + Tailwind + shadcn/ui + TanStack Query + Monaco | Spring Boot 3 (Java 21) + Spring Security + Spring Data JPA/Hibernate + Spring WebSocket | PostgreSQL | Redis | Judge0 (self-hosted) | Docker Compose + Nginx on self-hosted Ubuntu | JWT + Refresh Tokens | WebRTC (live only, no recording)

---

## How to Use This Document

Each module below is self-contained: purpose, requirements, schema, backend components, frontend components, APIs, relationships, build sequence, dependencies, and a Definition of Done. Work top to bottom — each module's "Dependencies" section tells you what must already be working before you start it. Open this doc daily, find the module you're on, and implement against its checklist. Don't jump ahead to Judge0 or WebRTC before the foundational modules are solid — those two are the highest-risk, highest-payoff parts of the system and deserve a stable base under them.

---

## 1. System Architecture Overview

```
                                   ┌─────────────────────────────┐
                                   │        Nginx (reverse proxy) │
                                   │   TLS termination, routing   │
                                   └───────────┬──────────────────┘
                                               │
                      ┌────────────────────────┼─────────────────────────┐
                      │                        │                         │
              ┌───────▼────────┐      ┌────────▼─────────┐     ┌─────────▼────────┐
              │  React 19 SPA   │      │  Spring Boot API  │     │  WebSocket/WebRTC │
              │  (Vite build,   │◄────►│  (REST + Security  │◄───►│  Signaling         │
              │  served static  │ REST │  + Business Logic) │ WS  │  (same Spring app  │
              │  via Nginx)     │      │                    │     │  or dedicated      │
              └─────────────────┘      └─────┬───────┬──────┘     │  module)           │
                                              │       │            └────────────────────┘
                                    ┌─────────▼─┐   ┌─▼──────────┐
                                    │ PostgreSQL │   │   Redis     │
                                    │ (primary   │   │ (Judge0     │
                                    │  data)     │   │  queue,     │
                                    └────────────┘   │  session/   │
                                                      │  presence   │
                                                      │  cache)     │
                                                      └─────┬───────┘
                                                            │
                                                    ┌───────▼────────┐
                                                    │ Judge0 (Docker) │
                                                    │ Self-hosted     │
                                                    │ code execution  │
                                                    └─────────────────┘
```

**Deployment topology:** everything (Spring Boot app, PostgreSQL, Redis, Judge0 stack, Nginx) runs as separate Docker Compose services on one Ubuntu server (your laptop). Nginx is the single entry point, reverse-proxying `/api/**` to Spring Boot, `/ws/**` to the WebSocket endpoint, and serving the built React static files directly.

---

## 2. Cross-Cutting Foundation (Build Once, Use Everywhere)

Build these **before** Module 1. Everything else depends on this layer being solid. Treat this as "Module 0."

### 2.1 Backend Shared Components

| Component | Purpose |
|---|---|
| `BaseEntity` (abstract `@MappedSuperclass`) | `id` (UUID), `createdAt`, `updatedAt`, `createdBy`, `updatedBy` — every entity extends this. Use Spring Data JPA Auditing (`@EnableJpaAuditing`, `@CreatedDate`, `@LastModifiedDate`). |
| `ApiResponse<T>` wrapper | Standard envelope for **every** endpoint: `{ success, data, message, timestamp, traceId }`. Never return raw entities/DTOs unwrapped. |
| `PagedResponse<T>` | Standard wrapper for list endpoints: `{ content, page, size, totalElements, totalPages }`. Used by Contest/Question/Submission listing endpoints. |
| `GlobalExceptionHandler` (`@ControllerAdvice`) | Central handler for `ResourceNotFoundException`, `ValidationException`, `UnauthorizedException`, `Judge0ServiceException`, generic `Exception`. Maps each to consistent HTTP status + `ApiError` body. |
| `ApiError` DTO | `{ code, message, fieldErrors[], traceId }`. `code` is an internal enum (e.g. `CONTEST_NOT_FOUND`), not just HTTP status — frontend can branch on this. |
| Custom exception hierarchy | `AppException` (base) → `ResourceNotFoundException`, `DuplicateResourceException`, `AccessDeniedException`, `InvalidStateException`, `Judge0IntegrationException`. |
| `CorrelationIdFilter` (Servlet Filter) | Generates/propagates a `X-Trace-Id` header per request; injected into MDC for logging. Critical for debugging distributed pieces (API → Redis → Judge0). |
| Logging config (Logback + SLF4J) | JSON-structured logs, correlation ID in every line, separate log levels per package, rolling file appender. Log every state transition (submission created → queued → executed → scored). |
| Bean Validation setup | `@Valid` on all request DTOs; custom validators for things like `@ValidTimeRange` (contest start < end), `@ValidLanguageId` (must exist in supported languages list). |
| `AuditLog` entity + `AuditService` | Cross-cutting: logs who did what (created contest, published result, evaluated submission) — required for a "professional evaluation platform" narrative and useful in your report. |
| `RedisConfig` | Shared connection factory + `RedisTemplate` bean, reused by Judge0 queue tracking, session/presence cache, and rate limiting. |
| `SecurityConfig` skeleton | Base `SecurityFilterChain`, JWT filter registration, CORS config, public vs protected endpoint matcher list — built once in Module 1, extended (not rebuilt) by every later module. |
| `RateLimitingFilter` | Simple Redis-backed token bucket per user/IP — protects `/auth/login` and `/submissions/run` from abuse. |
| `application-{profile}.yml` | `local`, `docker`, `prod` profiles — DB URLs, Judge0 base URL, Redis host, JWT secret (via env var, never hardcoded), CORS origins. |
| Common enums | `Role`, `ContestStatus`, `SubmissionStatus`, `TestCaseResultStatus`, `SessionStatus` — centralize in a `com.codepulse.common.enums` package, referenced everywhere, single source of truth. |

### 2.2 Frontend Shared Components

| Component | Purpose |
|---|---|
| `apiClient` (Axios instance) | Base URL, request interceptor (attaches JWT), response interceptor (auto-refreshes token on 401, redirects to login on refresh failure). Every API call module uses this, never raw `fetch`. |
| `AuthContext` + `useAuth()` hook | Holds current user, role, access token (in memory, not localStorage — refresh token in httpOnly cookie), login/logout functions. |
| `ProtectedRoute` component | Wraps React Router routes, checks auth + role, redirects unauthorized users. Config-driven: `<ProtectedRoute roles={['ADMIN','EVALUATOR']}>`. |
| `QueryClient` config (TanStack Query) | Central config: default `staleTime`, retry policy, global error handler (toasts on failed mutations). |
| Typed API hooks pattern | One hook per resource, e.g. `useContests()`, `useCreateContest()`, `useSubmission(id)` — thin wrappers around TanStack Query + apiClient, reused across pages. Establish this pattern in Module 1/2, replicate for every later module. |
| `AppShell` layout | Top navbar + role-based sidebar + content area. Three sidebar variants (Admin/Evaluator/Candidate) driven by the same shell component. |
| `DataTable` component (shadcn/ui + TanStack Table) | Reusable paginated/sortable table — reused for contest list, question list, submission list, candidate list, results list. Build once properly, save huge time later. |
| Toast/notification system (shadcn/ui `sonner` or `toast`) | Global success/error notifications, wired into the Axios interceptor and mutation `onError`/`onSuccess`. |
| Form pattern (React Hook Form + Zod) | Standard form + validation pattern established once, reused for contest/question/test-case/user forms. |
| `LoadingState` / `ErrorState` / `EmptyState` components | Consistent UI for TanStack Query's `isLoading`/`isError`/empty-array cases — avoid re-implementing per page. |
| Theme setup (Tailwind + shadcn/ui tokens) | Design tokens, dark/light mode toggle (optional but easy win for demo polish). |

**Why this section matters for your report/viva:** this is exactly the section to point to when an examiner asks "how did you avoid inconsistent code across a 4-person team?" — you built a shared foundation first, which is a real enterprise practice (not just a student-project shortcut).

---

## 3. Module Ordering — Rationale

Your proposed order is fundamentally sound and follows correct dependency direction (auth → domain data → execution → results → real-time → deployment). Two adjustments:

- **User Management merged conceptually with Authentication** (Modules 2–3 in your list): in practice these two are built almost simultaneously — you can't test auth without users existing, and user CRUD needs auth to protect it. Keeping them as two *documented* modules is fine (as below), just don't expect a clean handoff between them; build them together.
- **Testing moved from "only at the end" to continuous**, with Module 16 ("Testing") being specifically **integration/E2E/load testing** of the whole system, not the first time you write a test. Each module below includes its own testing expectations in its Definition of Done.

Final order used below: **Foundation → Auth → User Mgmt → Contest → Question → Test Case → Assessment Session → Judge0 Execution → Submission → Result/Evaluation → Analytics → WebSocket → WebRTC Signaling → Live Monitoring Dashboard → Deployment → Integration Testing → Documentation.**

---

## Module 1: Authentication Service

**Purpose:** Establish secure identity and session management for all three roles. Every other module depends on this.

**Functional Requirements**
- [ ] Register (Admin creates Evaluator/Candidate accounts — no public self-signup, since this is an institutional exam platform)
- [ ] Login with email + password → access token (short-lived, ~15 min) + refresh token (long-lived, ~7 days, httpOnly cookie)
- [ ] Refresh token rotation (issue new refresh token on each use, invalidate old one)
- [ ] Logout (revoke refresh token)
- [ ] Password hashing (BCrypt)
- [ ] Role-based access control enforced at the API gateway (Spring Security) level

**Database Tables**
- `users` (id, email, password_hash, full_name, role, is_active, created_at, updated_at)
- `refresh_tokens` (id, user_id FK, token_hash, expires_at, revoked, created_at)

**Backend Components**
- **Entities:** `User`, `RefreshToken`
- **Repositories:** `UserRepository`, `RefreshTokenRepository`
- **DTOs:** `LoginRequest`, `LoginResponse` (accessToken, user summary), `RefreshRequest`, `RegisterUserRequest`
- **Services:** `AuthService` (login, refresh, logout), `JwtService` (generate/validate/parse tokens), `PasswordService`
- **Controllers:** `AuthController` (`/api/auth/**`)
- **Security Components:** `JwtAuthenticationFilter`, `JwtAuthEntryPoint` (401 handler), `CustomUserDetailsService`, `SecurityConfig` (finalized here, extended later)
- **Configuration:** JWT secret/expiry via env vars, CORS allowed origins

**Frontend Components**
- **Pages:** `LoginPage`
- **Layouts:** `AuthLayout` (centered card, no sidebar)
- **Hooks:** `useLogin()`, `useLogout()`, `useRefreshToken()` (usually internal to Axios interceptor, not a page-level hook)
- **API Calls:** `authApi.login()`, `authApi.refresh()`, `authApi.logout()`
- **UI Components:** `LoginForm` (React Hook Form + Zod)

**APIs**

| Method | Endpoint | Description | Access |
|---|---|---|---|
| POST | `/api/auth/login` | Authenticate, return access token + set refresh cookie | Public |
| POST | `/api/auth/refresh` | Rotate refresh token, issue new access token | Public (cookie-based) |
| POST | `/api/auth/logout` | Revoke refresh token | Authenticated |

**Database Relationships**
- `refresh_tokens.user_id` → `users.id` (many-to-one)

**Sequence of Implementation**
1. `User` entity + `UserRepository` + BCrypt password encoder bean.
2. `SecurityConfig` skeleton: stateless session, permit `/api/auth/**`, deny everything else by default.
3. `JwtService`: generate/validate access + refresh tokens (separate secrets or claims to distinguish token type).
4. `JwtAuthenticationFilter`: reads `Authorization: Bearer`, validates, sets `SecurityContext`.
5. `AuthController` + `AuthService`: login flow, refresh flow, logout flow.
6. Seed one Admin user via a data migration/seed script (you need at least one account to bootstrap the system).
7. Frontend: `AuthContext`, `apiClient` interceptors, `LoginPage`, `ProtectedRoute`.

**Dependencies:** Cross-cutting Foundation (Section 2) must exist first.

**Definition of Done**
- [ ] Admin can log in and receive a working access token
- [ ] Expired access token auto-refreshes transparently via Axios interceptor
- [ ] Invalid/expired refresh token correctly forces re-login
- [ ] Protected endpoint returns 401 without a token, 200 with one
- [ ] Unit tests for `JwtService` (generate/validate/expired cases)

**Future Enhancements:** OAuth2/SSO (Google login for institutional accounts), account lockout after N failed attempts, email-based password reset.

---

## Module 2: User Management Service

**Purpose:** Admin-controlled creation and management of Evaluator and Candidate accounts, since there's no public registration.

**Functional Requirements**
- [ ] Admin creates users with assigned role
- [ ] Admin can deactivate/reactivate users (soft delete via `is_active`)
- [ ] Admin can bulk-import candidates (CSV upload — common real requirement for institutional exams)
- [ ] Users can view/update their own profile (name, password change)

**Database Tables**
- `users` (already created in Module 1 — this module builds the management layer on top)

**Backend Components**
- **Entities:** `User` (reused)
- **Repositories:** `UserRepository` (extended with search/filter queries)
- **DTOs:** `CreateUserRequest`, `UpdateUserRequest`, `UserSummaryResponse`, `BulkImportResult`
- **Services:** `UserService` (CRUD, bulk import, deactivate), `CsvImportService` (generic, reusable — you'll want this pattern again for bulk question import later)
- **Controllers:** `UserController` (`/api/users/**`)
- **Security Components:** Method-level `@PreAuthorize("hasRole('ADMIN')")` on management endpoints

**Frontend Components**
- **Pages:** `UserManagementPage` (Admin only), `ProfilePage` (all roles)
- **Layouts:** Uses shared `AppShell`
- **Hooks:** `useUsers()`, `useCreateUser()`, `useDeactivateUser()`, `useBulkImportUsers()`
- **API Calls:** `userApi.*`
- **UI Components:** `UserTable` (built on shared `DataTable`), `CreateUserDialog`, `CsvUploadInput`

**APIs**

| Method | Endpoint | Description | Access |
|---|---|---|---|
| GET | `/api/users` | Paginated, filterable user list | Admin |
| POST | `/api/users` | Create a user | Admin |
| PUT | `/api/users/{id}` | Update user | Admin |
| PATCH | `/api/users/{id}/deactivate` | Soft-delete | Admin |
| POST | `/api/users/bulk-import` | CSV candidate import | Admin |
| GET | `/api/users/me` | Current user profile | Authenticated |
| PUT | `/api/users/me` | Update own profile | Authenticated |

**Database Relationships:** None new — this module operates entirely on `users`.

**Sequence of Implementation**
1. `UserService` CRUD methods + `UserController`.
2. `@PreAuthorize` role checks (first real use of method-level security beyond the filter chain).
3. `CsvImportService` — generic enough to accept a mapping function (reused later for bulk question upload).
4. Frontend management page with `DataTable`.

**Dependencies:** Module 1 (Authentication).

**Definition of Done**
- [ ] Admin can create Evaluator and Candidate accounts and they can log in
- [ ] Deactivated users cannot log in (return clear error, not generic 401)
- [ ] CSV import correctly creates N candidates with a validation report of failures (e.g., duplicate emails)

**Future Enhancements:** Self-service password reset via email, user activity audit trail view in UI.

---

## Module 3: Contest Management Service

**Purpose:** Core entity around which everything else revolves — an exam/contest with a time window, allowed languages, and candidate roster.

**Functional Requirements**
- [ ] Admin creates a contest: title, description, start time, end time, duration, allowed languages, status
- [ ] Contest lifecycle: `DRAFT → PUBLISHED → ONGOING → COMPLETED`
- [ ] Admin assigns/invites candidates to a contest
- [ ] Contest listing filtered by role (Candidate sees only assigned contests; Admin sees all)

**Database Tables**
- `contests` (id, title, description, start_time, end_time, duration_minutes, allowed_languages, status, created_by FK, created_at)
- `contest_candidates` (id, contest_id FK, candidate_id FK, invited_at, status)

**Backend Components**
- **Entities:** `Contest`, `ContestCandidate`
- **Repositories:** `ContestRepository`, `ContestCandidateRepository`
- **DTOs:** `CreateContestRequest`, `ContestResponse`, `ContestDetailResponse`, `AssignCandidatesRequest`
- **Services:** `ContestService` (CRUD, lifecycle transitions, candidate assignment), `ContestSchedulerService` (a scheduled job — `@Scheduled` — that flips `PUBLISHED → ONGOING → COMPLETED` automatically based on start/end time; important, don't rely on manual admin clicks for state transitions)
- **Controllers:** `ContestController` (`/api/contests/**`)
- **Security Components:** `@PreAuthorize` — Admin creates/edits, Evaluator/Candidate read-only scoped to their own contests
- **Configuration:** `@EnableScheduling` for the scheduler service

**Frontend Components**
- **Pages:** `ContestListPage`, `ContestCreatePage`, `ContestDetailPage`
- **Layouts:** Shared `AppShell`
- **Hooks:** `useContests()`, `useContest(id)`, `useCreateContest()`, `useAssignCandidates()`
- **API Calls:** `contestApi.*`
- **UI Components:** `ContestCard`, `ContestForm`, `ContestStatusBadge`, `CandidateAssignmentPanel`

**APIs**

| Method | Endpoint | Description | Access |
|---|---|---|---|
| GET | `/api/contests` | List (role-scoped) | All |
| POST | `/api/contests` | Create | Admin |
| GET | `/api/contests/{id}` | Detail | All (scoped) |
| PUT | `/api/contests/{id}` | Update | Admin |
| POST | `/api/contests/{id}/publish` | DRAFT → PUBLISHED | Admin |
| POST | `/api/contests/{id}/candidates` | Assign candidates | Admin |
| GET | `/api/contests/{id}/candidates` | List assigned candidates | Admin/Evaluator |

**Database Relationships**
- `contest_candidates.contest_id` → `contests.id`
- `contest_candidates.candidate_id` → `users.id`
- `contests.created_by` → `users.id`

**Sequence of Implementation**
1. `Contest` entity + repository + basic CRUD.
2. Lifecycle enum + manual publish endpoint first (get this working before automating it).
3. `ContestCandidate` join entity + assignment endpoints.
4. `ContestSchedulerService` — automate the lifecycle transitions last, once manual flow is proven.
5. Frontend list/create/detail pages.

**Dependencies:** Modules 1 & 2 (need authenticated Admin and existing Candidate users to assign).

**Definition of Done**
- [ ] Admin can create a contest and assign 3+ candidates
- [ ] Contest auto-transitions to ONGOING at start time and COMPLETED at end time (verify with a short test contest, e.g. 2-minute window)
- [ ] Candidate only sees contests they're assigned to; Admin sees all

**Future Enhancements:** Contest templates/duplication, public contest links (self-registration), proctoring rule configuration per contest (e.g., "webcam required: yes/no").

---

## Module 4: Question Management Service

**Purpose:** Authoring coding problems within a contest.

**Functional Requirements**
- [ ] Admin adds questions to a contest: title, description (markdown/rich text), difficulty, points, time/memory limits
- [ ] Reorder questions within a contest
- [ ] Duplicate a question across contests (reuse question bank)

**Database Tables**
- `questions` (id, contest_id FK, title, description, difficulty, points, time_limit_ms, memory_limit_kb, order_index, created_by, created_at)

**Backend Components**
- **Entities:** `Question`
- **Repositories:** `QuestionRepository`
- **DTOs:** `CreateQuestionRequest`, `QuestionResponse`, `QuestionDetailResponse` (includes sample test cases, excludes hidden ones for candidate-facing variant — **two DTOs are important here**: `QuestionAdminView` vs `QuestionCandidateView`, to guarantee hidden test cases can never leak via a shared serializer)
- **Services:** `QuestionService`
- **Controllers:** `QuestionController` (`/api/contests/{contestId}/questions/**`)
- **Security Components:** Admin write; Candidate read restricted to contests they're assigned to and only during the assessment window (enforced via `ContestService` check)

**Frontend Components**
- **Pages:** `QuestionListPage` (within contest detail), `QuestionEditorPage` (markdown editor for description)
- **Hooks:** `useQuestions(contestId)`, `useCreateQuestion()`, `useReorderQuestions()`
- **API Calls:** `questionApi.*`
- **UI Components:** `QuestionCard`, `QuestionForm`, `MarkdownEditor` (e.g. `@uiw/react-md-editor`), `DifficultyBadge`

**APIs**

| Method | Endpoint | Description | Access |
|---|---|---|---|
| GET | `/api/contests/{contestId}/questions` | List | Admin/Evaluator; Candidate (restricted view) |
| POST | `/api/contests/{contestId}/questions` | Create | Admin |
| GET | `/api/questions/{id}` | Detail | Role-dependent DTO |
| PUT | `/api/questions/{id}` | Update | Admin |
| PATCH | `/api/contests/{contestId}/questions/reorder` | Reorder | Admin |

**Database Relationships**
- `questions.contest_id` → `contests.id`

**Sequence of Implementation**
1. `Question` entity + CRUD.
2. Two-DTO pattern (admin vs candidate view) — decide this now, it's much harder to retrofit after Test Case module is built on top.
3. Reordering endpoint.
4. Frontend authoring UI with markdown editor.

**Dependencies:** Module 3 (Contest Management).

**Definition of Done**
- [ ] Admin can author a question with full details
- [ ] Candidate-facing question detail endpoint never includes hidden test cases at the serialization level (test this explicitly — write a test that asserts the field is absent, not just hidden in UI)

**Future Enhancements:** Question bank/tagging (topics: DP, graphs, etc.), difficulty-based auto point allocation, question versioning.

---

## Module 5: Test Case Service

**Purpose:** Input/expected-output pairs used to grade submissions — the ground truth for scoring.

**Functional Requirements**
- [ ] Admin adds test cases per question: input, expected output, weight, sample vs hidden flag
- [ ] Bulk upload test cases (reuse `CsvImportService` pattern from Module 2, or a zip of input/output file pairs — common in real judges)
- [ ] Sample test cases visible to candidates pre-submission; hidden ones only used server-side

**Database Tables**
- `test_cases` (id, question_id FK, input, expected_output, is_sample, weight, order_index, created_at)

**Backend Components**
- **Entities:** `TestCase`
- **Repositories:** `TestCaseRepository` (with a method like `findByQuestionIdAndIsSampleTrue` for candidate-facing calls — **never** fetch all test cases in a candidate-facing code path)
- **DTOs:** `CreateTestCaseRequest`, `TestCaseAdminResponse`, `TestCaseSampleResponse` (only sample ones, no `expected_output` even — candidates should only see input + their own actual output after a Run)
- **Services:** `TestCaseService`, `TestCaseBulkUploadService`
- **Controllers:** `TestCaseController` (`/api/questions/{questionId}/test-cases/**`)
- **Security Components:** Admin-only write; strict serialization boundary as above

**Frontend Components**
- **Pages:** `TestCaseManagerPage` (within question editor)
- **Hooks:** `useTestCases(questionId)`, `useCreateTestCase()`, `useBulkUploadTestCases()`
- **API Calls:** `testCaseApi.*`
- **UI Components:** `TestCaseForm`, `TestCaseTable`, `BulkUploadDropzone`

**APIs**

| Method | Endpoint | Description | Access |
|---|---|---|---|
| GET | `/api/questions/{questionId}/test-cases` | List (admin: all; candidate: sample only, no expected output) | Role-dependent |
| POST | `/api/questions/{questionId}/test-cases` | Create | Admin |
| POST | `/api/questions/{questionId}/test-cases/bulk` | Bulk upload | Admin |
| DELETE | `/api/test-cases/{id}` | Delete | Admin |

**Database Relationships**
- `test_cases.question_id` → `questions.id`

**Sequence of Implementation**
1. `TestCase` entity + CRUD, sample/hidden flag.
2. Strict DTO separation (this is a security requirement, not a nice-to-have — a leaked hidden test case defeats the platform's purpose).
3. Bulk upload.
4. Frontend management UI.

**Dependencies:** Module 4 (Question Management).

**Definition of Done**
- [ ] A question has a mix of sample + hidden test cases
- [ ] API contract test confirms hidden test case data never appears in any candidate-accessible response
- [ ] Bulk upload correctly parses and rejects malformed entries with a clear report

**Future Enhancements:** Test case file upload (large I/O via file references instead of inline text, for problems with big inputs), partial-credit weighting UI.

---

## Module 6: Candidate Assessment Session Service

**Purpose:** Manages the lifecycle of a candidate actively taking a contest — start time, timer, state, auto-submit. This sits between "Contest exists" and "Submissions exist" and is easy to under-scope, so it gets its own module rather than being folded into Contest or Submission.

**Functional Requirements**
- [ ] Candidate starts an assessment (creates a session, records server-side start time — **never trust client clock**)
- [ ] Session tracks remaining time; auto-submits (locks session, triggers final scoring) when time expires
- [ ] Candidate can resume a session on reconnect (network drop, browser refresh) without losing progress
- [ ] Session status: `IN_PROGRESS`, `SUBMITTED`, `AUTO_SUBMITTED`, `EXPIRED`

**Database Tables**
- `assessment_sessions` (id, contest_id FK, candidate_id FK, started_at, ends_at, status, submitted_at)

**Backend Components**
- **Entities:** `AssessmentSession`
- **Repositories:** `AssessmentSessionRepository`
- **DTOs:** `StartSessionResponse` (includes server-computed `ends_at`), `SessionStatusResponse`
- **Services:** `AssessmentSessionService` (start, get active session, force-submit), `SessionExpiryScheduler` (`@Scheduled` job scanning for expired `IN_PROGRESS` sessions every N seconds, transitioning them to `AUTO_SUBMITTED` and triggering final scoring via Submission Service)
- **Controllers:** `AssessmentSessionController` (`/api/contests/{contestId}/session/**`)
- **Security Components:** Candidate can only access their own session (ownership check, not just role check — this is a common real bug source: **object-level authorization**, not just role-based)

**Frontend Components**
- **Pages:** `AssessmentPage` (the main exam-taking screen — hosts the Monaco editor, question list, timer)
- **Hooks:** `useAssessmentSession(contestId)`, `useSessionTimer()` (client-side countdown synced against server `ends_at`, not counting independently)
- **API Calls:** `sessionApi.start()`, `sessionApi.getStatus()`, `sessionApi.submit()`
- **UI Components:** `CountdownTimer`, `QuestionNavigator` (sidebar list of questions with attempted/unattempted status)

**APIs**

| Method | Endpoint | Description | Access |
|---|---|---|---|
| POST | `/api/contests/{contestId}/session/start` | Start or resume session | Candidate |
| GET | `/api/contests/{contestId}/session` | Get current session status/time remaining | Candidate |
| POST | `/api/contests/{contestId}/session/submit` | Manual final submit (ends session) | Candidate |

**Database Relationships**
- `assessment_sessions.contest_id` → `contests.id`
- `assessment_sessions.candidate_id` → `users.id`

**Sequence of Implementation**
1. `AssessmentSession` entity + start/resume logic (server computes and owns `ends_at`).
2. `SessionExpiryScheduler` — build and test this early with a short-duration test contest (e.g., 1-minute) rather than waiting until a real exam length to verify it.
3. Object-level authorization check (candidate can only touch their own session).
4. Frontend timer synced to server time, with periodic re-sync (e.g., every 30s) to correct client drift.

**Dependencies:** Module 3 (Contest — need ONGOING status), Module 2 (candidate must be assigned).

**Definition of Done**
- [ ] Session correctly computed from server time regardless of client clock
- [ ] Refreshing the browser mid-exam resumes the same session with correct remaining time
- [ ] Session auto-submits within a few seconds of expiry (test with short window) and this correctly triggers Submission Service scoring for whatever was last saved

**Future Enhancements:** Per-question time tracking/analytics, "save draft" auto-save every N seconds so unsubmitted code isn't lost on crash.

---

## Module 7: Judge0 Execution Service

**Purpose:** The technical core of the platform — integrates with self-hosted Judge0 to compile/run candidate code against test cases. This is the highest-risk module; give it focused, uninterrupted time.

**Functional Requirements**
- [ ] Submit code + input to Judge0, poll (or use Judge0's callback/webhook mode) for result
- [ ] Support "Run" (against sample test cases only, synchronous-feeling) and "Submit" (against all test cases, can be async)
- [ ] Handle all Judge0 status outcomes: Accepted, Wrong Answer, Time Limit Exceeded, Memory Limit Exceeded, Runtime Error, Compilation Error
- [ ] Queue submissions through Redis to avoid overwhelming Judge0 during concurrent exam load
- [ ] Map Judge0 language IDs to your platform's supported language list

**Database Tables**
- No new primary tables (this module is a service layer); it writes results into `submissions` / `submission_test_case_results` (Module 8), and may use a Redis-backed queue rather than a DB table for in-flight tracking.

**Backend Components**
- **Entities:** None new (interacts with Submission entities from Module 8)
- **Repositories:** None new
- **DTOs:** `Judge0SubmissionRequest` (internal, maps to Judge0's API shape), `Judge0ResultResponse`, `ExecutionResult` (your platform's normalized result shape, decoupled from Judge0's raw response — **important**: don't leak Judge0's exact response schema into your own DTOs, wrap it, so a future judge-engine swap doesn't ripple through the whole app)
- **Services:** `Judge0ClientService` (HTTP client wrapper around Judge0's REST API using `RestTemplate`/`WebClient`), `CodeExecutionService` (orchestrates: build Judge0 payload → submit → poll/await → normalize result → return `ExecutionResult`), `SubmissionQueueService` (Redis-backed queue: push submission, worker/consumer polls and processes — protects Judge0 from being flooded when 30 candidates hit "Submit" simultaneously)
- **Controllers:** None directly public-facing — this is consumed internally by `SubmissionController` (Module 8)
- **Security Components:** N/A (internal service), but the Judge0 instance itself should only be reachable from the backend network (not exposed publicly via Nginx)
- **Configuration:** `Judge0Config` (base URL, auth token if configured, timeout values, retry policy), language ID mapping config (a simple enum-to-Judge0-ID map)

**Frontend Components**
- No dedicated pages — this powers the "Run"/"Submit" buttons inside the Assessment Session UI (Module 6) and Submission history (Module 8).

**APIs**
- No new public REST endpoints — internal service layer only. (Submission-facing endpoints live in Module 8.)

**Database Relationships:** N/A directly — this service is stateless with respect to persistence; state lives in Modules 6 & 8.

**Sequence of Implementation**
1. Get Judge0 running standalone via Docker Compose first — **verify it works via `curl`/Postman before writing any Spring code.**
2. `Judge0ClientService`: submit a hardcoded snippet, poll for result, print raw response. Prove connectivity before building abstractions on top.
3. `ExecutionResult` normalized DTO — design this before wiring it into anything, since Module 8 depends on its shape.
4. `CodeExecutionService`: orchestration logic, error handling for each Judge0 status.
5. `SubmissionQueueService`: add the Redis queue layer once direct execution works — don't build queueing before proving the base integration.
6. Load-test with several concurrent fake submissions to confirm the queue prevents Judge0 overload.

**Dependencies:** Modules 1 (auth, for calling context), Redis + Judge0 infra must be running.

**Definition of Done**
- [ ] Code in at least 3 languages (e.g., C++, Python, Java) executes correctly end-to-end
- [ ] All Judge0 status outcomes are correctly mapped and surfaced (not just "success/fail")
- [ ] 10+ concurrent submissions don't crash or hang Judge0 (queue absorbs the burst)
- [ ] Judge0 network access is restricted to the backend only (verified in Nginx/Docker network config)

**Future Enhancements:** Custom compiler flags per language, resource limit tuning per question difficulty, caching identical repeated submissions (rare but possible optimization).

---

## Module 8: Submission Service

**Purpose:** Persists candidate code submissions and their per-test-case results; the record of what a candidate actually did.

**Functional Requirements**
- [ ] "Run" creates a submission of type `RUN`, scored against sample test cases only, not counted toward final score
- [ ] "Submit" creates a submission of type `SUBMIT`, scored against all test cases, counts toward score
- [ ] Store per-test-case result: pass/fail, actual output (truncated if huge), execution time, memory used
- [ ] Submission history per candidate per question (they can submit multiple times; typically the **last** or **best** submission counts — decide and document this rule explicitly)

**Database Tables**
- `submissions` (id, session_id FK, question_id FK, candidate_id FK, language_id, source_code, submission_type, status, score, submitted_at)
- `submission_test_case_results` (id, submission_id FK, test_case_id FK, status, actual_output, execution_time_ms, memory_used_kb)

**Backend Components**
- **Entities:** `Submission`, `SubmissionTestCaseResult`
- **Repositories:** `SubmissionRepository`, `SubmissionTestCaseResultRepository`
- **DTOs:** `RunCodeRequest`, `SubmitCodeRequest`, `SubmissionResponse`, `SubmissionDetailResponse` (includes per-test-case breakdown — sample-only detail for candidates, full detail for evaluators)
- **Services:** `SubmissionService` (create submission, invoke `CodeExecutionService` from Module 7, persist results, compute score), `ScoringService` (weight-based score calculation, decoupled from `SubmissionService` so scoring rules can change independently — e.g., all-or-nothing vs weighted partial credit)
- **Controllers:** `SubmissionController` (`/api/submissions/**`)
- **Security Components:** Object-level check — candidate can only submit within their own active session; Evaluator has read access to all submissions in contests they're assigned to evaluate

**Frontend Components**
- **Pages:** Embedded in `AssessmentPage` (Run/Submit buttons + output panel), `SubmissionHistoryPage` (candidate's own past submissions), `EvaluatorSubmissionListPage`
- **Hooks:** `useRunCode()`, `useSubmitCode()`, `useSubmissionHistory(questionId)`
- **API Calls:** `submissionApi.*`
- **UI Components:** `CodeEditorPanel` (Monaco wrapper with language selector), `TestCaseResultPanel` (pass/fail per test case, output diff view), `SubmissionHistoryTable`

**APIs**

| Method | Endpoint | Description | Access |
|---|---|---|---|
| POST | `/api/submissions/run` | Run against sample test cases | Candidate |
| POST | `/api/submissions/submit` | Submit for scoring | Candidate |
| GET | `/api/submissions/{id}` | Detail | Owner / Evaluator |
| GET | `/api/questions/{questionId}/submissions/me` | Candidate's own history for a question | Candidate |
| GET | `/api/contests/{contestId}/submissions` | All submissions (for evaluation) | Evaluator/Admin |

**Database Relationships**
- `submissions.session_id` → `assessment_sessions.id`
- `submissions.question_id` → `questions.id`
- `submissions.candidate_id` → `users.id`
- `submission_test_case_results.submission_id` → `submissions.id`
- `submission_test_case_results.test_case_id` → `test_cases.id`

**Sequence of Implementation**
1. `Submission` + `SubmissionTestCaseResult` entities.
2. "Run" flow first (simpler — sample test cases only, no scoring implications).
3. "Submit" flow — wire in `CodeExecutionService`, all test cases, `ScoringService`.
4. Submission history endpoints and UI.
5. Evaluator-facing listing.

**Dependencies:** Modules 5 (Test Cases), 6 (Assessment Session), 7 (Judge0 Execution Service).

**Definition of Done**
- [ ] Candidate can Run code and see sample results within a few seconds
- [ ] Candidate can Submit and see a final score computed from hidden test cases
- [ ] Submission history correctly lists all attempts, clearly marking which one is "final"/counted
- [ ] Evaluator can view any candidate's submission with full test-case breakdown

**Future Enhancements:** Code similarity/plagiarism detection between submissions, submission diffing across a candidate's own attempts.

---

## Module 9: Result & Evaluation Service

**Purpose:** Aggregate scores per candidate per contest, support manual evaluator adjustment, and control publishing.

**Functional Requirements**
- [ ] Auto-computed total score per candidate per contest (sum of best/final submission scores per question)
- [ ] Evaluator can manually review a submission and adjust score with comments (e.g., partial credit for code quality, or penalizing detected issues)
- [ ] Admin publishes results — candidates cannot see scores before publishing
- [ ] Rank computation within a contest

**Database Tables**
- `results` (id, contest_id FK, candidate_id FK, total_score, rank, published, published_at)
- `manual_evaluations` (id, submission_id FK, evaluator_id FK, adjusted_score, comments, evaluated_at)

**Backend Components**
- **Entities:** `Result`, `ManualEvaluation`
- **Repositories:** `ResultRepository`, `ManualEvaluationRepository`
- **DTOs:** `ResultResponse`, `PublishResultsRequest`, `ManualEvaluationRequest`, `LeaderboardEntry`
- **Services:** `ResultService` (aggregate scores, compute rank, publish/unpublish), `ManualEvaluationService` (apply evaluator overrides, recompute affected result), `RankingService` (decoupled ranking logic — tie-breaking rules like "faster submission wins ties" live here, isolated from aggregation logic)
- **Controllers:** `ResultController` (`/api/contests/{contestId}/results/**`), `ManualEvaluationController`
- **Security Components:** Publish action Admin-only; manual evaluation Evaluator/Admin; candidates can only view their **own** published result (object-level + publish-state check combined)

**Frontend Components**
- **Pages:** `EvaluationPage` (Evaluator reviews submissions, adjusts scores), `ResultsPage` (Admin: leaderboard + publish control), `MyResultPage` (Candidate)
- **Hooks:** `useResults(contestId)`, `usePublishResults()`, `useManualEvaluation()`
- **API Calls:** `resultApi.*`
- **UI Components:** `LeaderboardTable`, `EvaluationPanel` (side-by-side code + test results + score override form), `PublishConfirmDialog`

**APIs**

| Method | Endpoint | Description | Access |
|---|---|---|---|
| GET | `/api/contests/{contestId}/results` | Full leaderboard | Admin/Evaluator |
| GET | `/api/contests/{contestId}/results/me` | Own result | Candidate (only if published) |
| POST | `/api/contests/{contestId}/results/publish` | Publish | Admin |
| POST | `/api/submissions/{id}/evaluate` | Manual score adjustment | Evaluator |

**Database Relationships**
- `results.contest_id` → `contests.id`
- `results.candidate_id` → `users.id`
- `manual_evaluations.submission_id` → `submissions.id`
- `manual_evaluations.evaluator_id` → `users.id`

**Sequence of Implementation**
1. `Result` entity + aggregation logic (sum scores per candidate per contest) — build as a service method, triggerable on demand first.
2. Ranking logic with defined tie-breaking rule.
3. Publish/unpublish endpoint + candidate visibility gating.
4. `ManualEvaluation` — override flow, triggers result recomputation.
5. Frontend leaderboard + evaluation panel.

**Dependencies:** Module 8 (Submission Service must be producing scored submissions).

**Definition of Done**
- [ ] Leaderboard correctly ranks candidates by total score with a defined tie-break
- [ ] Candidates cannot see any result data before publish
- [ ] Evaluator override correctly updates total score and re-ranks

**Future Enhancements:** Per-question analytics on the results page, exportable results (CSV/PDF certificate generation).

---

## Module 10: Analytics Service

**Purpose:** Aggregate insights across contests/questions/candidates for Admin/Evaluator decision-making.

**Functional Requirements**
- [ ] Score distribution chart per contest
- [ ] Per-question pass rate / difficulty validation (are your "easy" questions actually easy?)
- [ ] Time-taken analysis
- [ ] Flagged-candidate summary (ties into Module 13's proctoring events)

**Database Tables**
- No new tables ideally — this module reads/aggregates from `submissions`, `results`, `assessment_sessions`, `proctoring_events` (Module 13) via optimized queries/views. Consider a few native SQL views (`contest_analytics_view`) for heavier aggregations rather than pulling raw rows into Java and computing in-memory.

**Backend Components**
- **Entities:** None new (read-only aggregation layer); optionally JPA-mapped read-only entities/`@Immutable` views
- **Repositories:** `AnalyticsRepository` (custom native/JPQL aggregate queries)
- **DTOs:** `ContestAnalyticsResponse` (score distribution buckets, avg time, pass rates array), `QuestionAnalyticsResponse`
- **Services:** `AnalyticsService`
- **Controllers:** `AnalyticsController` (`/api/contests/{contestId}/analytics/**`)
- **Security Components:** Admin/Evaluator only

**Frontend Components**
- **Pages:** `AnalyticsDashboardPage`
- **Hooks:** `useContestAnalytics(contestId)`
- **API Calls:** `analyticsApi.*`
- **UI Components:** `ScoreDistributionChart`, `QuestionPassRateChart`, `TimeAnalysisChart` (all via Recharts — pairs cleanly with shadcn/ui)

**APIs**

| Method | Endpoint | Description | Access |
|---|---|---|---|
| GET | `/api/contests/{contestId}/analytics/overview` | Score distribution, averages | Admin/Evaluator |
| GET | `/api/contests/{contestId}/analytics/questions` | Per-question stats | Admin/Evaluator |

**Database Relationships:** Read-only aggregation across `submissions`, `results`, `assessment_sessions`, `proctoring_events`.

**Sequence of Implementation**
1. Define exactly which metrics matter (don't over-build — 3-4 solid charts beat 10 shallow ones for a viva demo).
2. Write and test the aggregate queries directly in SQL first (faster iteration than through JPA), then wrap in `AnalyticsRepository`.
3. Build endpoints + frontend charts.

**Dependencies:** Modules 8 & 9 (needs real submission/result data to aggregate); ideally built after Module 13 exists so proctoring analytics can be included, or built in two passes.

**Definition of Done**
- [ ] Dashboard renders correctly for a contest with real (or seeded test) data
- [ ] Queries perform acceptably with a few hundred simulated submissions (test this — it's a good thing to mention in your report as "performance consideration")

**Future Enhancements:** Candidate-level performance trend across multiple contests, exportable analytics reports.

---

## Module 11: WebSocket Service

**Purpose:** Real-time layer for live timers, candidate presence, and event notifications — the foundation Module 13 (Live Monitoring) builds on.

**Functional Requirements**
- [ ] Candidate presence: who is currently active in a contest
- [ ] Server-push notifications: session about to expire, contest state change, manual evaluation published
- [ ] Admin dashboard receives live updates without polling

**Database Tables:** None — this is a transport/session layer. Presence state cached in Redis (`contest:{id}:active-candidates` set), not persisted to Postgres.

**Backend Components**
- **Entities:** None
- **Repositories:** None (Redis operations via `RedisTemplate`, not JPA)
- **DTOs:** `WebSocketEvent` (generic envelope: `{ type, payload, timestamp }`), specific event payloads (`SessionExpiringEvent`, `CandidateJoinedEvent`, `ContestStatusChangedEvent`)
- **Services:** `WebSocketPresenceService` (track connect/disconnect in Redis), `NotificationBroadcastService` (pushes events to relevant STOMP topics)
- **Controllers:** N/A REST — instead: `WebSocketConfig` (`@EnableWebSocketMessageBroker`, STOMP endpoint registration, e.g. `/ws`), `@MessageMapping`-annotated handler for candidate presence pings
- **Security Components:** WebSocket handshake must validate the JWT (via a handshake interceptor) — don't leave the socket endpoint unauthenticated just because it's a different protocol
- **Configuration:** STOMP broker relay config, topic naming convention (e.g. `/topic/contest/{id}/presence`, `/topic/contest/{id}/events`)

**Frontend Components**
- **Pages:** No dedicated page — a shared connection used across `AssessmentPage` and `LiveMonitoringPage`
- **Hooks:** `useWebSocket()` (connection lifecycle), `useContestPresence(contestId)`, `useContestEvents(contestId)`
- **API Calls:** N/A (STOMP client, e.g. `@stomp/stompjs` + `sockjs-client`)
- **UI Components:** `ConnectionStatusIndicator`, `LiveNotificationToast`

**APIs (WebSocket topics, not REST)**

| Destination | Direction | Description |
|---|---|---|
| `/ws` (handshake) | Client → Server | Connect, JWT validated at handshake |
| `/app/contest/{id}/join` | Client → Server | Announce presence |
| `/topic/contest/{id}/presence` | Server → Client | Broadcast active candidate list |
| `/topic/contest/{id}/events` | Server → Client | Session/contest state change notifications |

**Database Relationships:** N/A

**Sequence of Implementation**
1. Basic `WebSocketConfig` + one test topic (e.g., broadcast a timestamp every 5s) to prove connectivity end-to-end before building real features.
2. JWT handshake interceptor — secure it before adding real data.
3. Presence tracking via Redis.
4. Event broadcasting for session expiry warnings and contest state changes (hook into Module 6's scheduler and Module 3's scheduler to *also* emit WebSocket events, not just update the DB silently).

**Dependencies:** Module 1 (JWT for handshake auth), Redis running.

**Definition of Done**
- [ ] Two browser sessions joining the same contest see each other in a live presence list
- [ ] A session-expiry-warning event reaches the candidate's browser within a couple seconds of being triggered server-side
- [ ] Unauthenticated WebSocket connection attempts are rejected

**Future Enhancements:** Typing/activity indicators, chat between candidate and evaluator for exam-time queries.

---

## Module 12: WebRTC Signaling Service

**Purpose:** Enable live webcam + screen share streaming from Candidate to Admin/Evaluator, **with no recording or storage** — pure live signaling.

**Functional Requirements**
- [ ] Candidate's browser captures webcam (`getUserMedia`) and screen (`getDisplayMedia`)
- [ ] WebRTC peer connection established between Candidate and monitoring Admin/Evaluator, brokered by your signaling server
- [ ] Signaling exchanges SDP offers/answers and ICE candidates — **your backend never touches the actual media stream**, only the connection metadata
- [ ] Multiple evaluators can potentially observe the same candidate stream (fan-out) — note as a scaling consideration, simplest version is 1 evaluator : 1 candidate observation

**Database Tables:** None — signaling is ephemeral, no persistence required (explicitly no recording per your requirements).

**Backend Components**
- **Entities:** None
- **Repositories:** None
- **DTOs:** `SignalMessage` (`{ type: OFFER|ANSWER|ICE_CANDIDATE, senderId, targetId, payload }`)
- **Services:** `WebRTCSignalingService` (routes signaling messages between the correct candidate/evaluator pair, using the same STOMP infrastructure from Module 11 or a dedicated `/ws/signaling` handler)
- **Controllers:** Extends `WebSocketConfig`'s message mapping — `@MessageMapping("/signal/{targetUserId}")`
- **Security Components:** Verify sender is either the candidate whose session it is, or an Evaluator/Admin assigned to that contest — **critical**: without this check, anyone could subscribe to any candidate's stream
- **Configuration:** STUN server config (public STUN like Google's is fine since you're not recording; TURN server only needed if you hit NAT traversal issues on your self-hosted network — note this as a possible gotcha since you're on a single Ubuntu server/laptop, likely same-network testing, so TURN probably isn't even needed for your demo)

**Frontend Components**
- **Pages:** No separate page — webcam capture happens inside `AssessmentPage` (candidate side, silent background operation), viewing happens inside `LiveMonitoringPage` (Module 13, evaluator side)
- **Hooks:** `useWebRTCBroadcast()` (candidate side: capture + create offer), `useWebRTCViewer(candidateId)` (evaluator side: receive offer, create answer, render stream)
- **API Calls:** N/A (STOMP signaling channel)
- **UI Components:** `WebcamPreview` (small local check on candidate side, "your camera is being monitored" — an honest, visible indicator matters ethically and will likely be asked about in viva), `RemoteStreamViewer` (evaluator side `<video>` element bound to the received `MediaStream`)

**APIs (signaling channel, not REST)**

| Destination | Direction | Description |
|---|---|---|
| `/app/signal/{targetUserId}` | Client → Server | Send SDP offer/answer or ICE candidate |
| `/topic/signal/{userId}` (or user-specific queue) | Server → Client | Deliver signaling message to intended recipient |

**Database Relationships:** N/A

**Sequence of Implementation**
1. Prove basic WebRTC works **without your signaling server first** — two browser tabs manually exchanging SDP via console/copy-paste — to isolate WebRTC issues from signaling-server issues while learning.
2. Build `WebRTCSignalingService` message routing using existing WebSocket infra.
3. Candidate-side capture + offer creation.
4. Evaluator-side viewer + answer handling.
5. Authorization check: evaluator can only request a stream from a candidate in a contest they're assigned to monitor.
6. Test screen share (`getDisplayMedia`) alongside webcam — decide if it's one combined stream or two separate peer connections (two is simpler to reason about, slightly more overhead).

**Dependencies:** Module 11 (WebSocket infrastructure), Module 6 (need active assessment sessions to monitor).

**Definition of Done**
- [ ] Evaluator can view a live webcam feed from a candidate in real time (a few seconds of latency is acceptable)
- [ ] Screen share stream also viewable
- [ ] No media is ever written to disk or database anywhere in the flow (explicitly verify — check Judge0/logs/Redis for accidental buffering)
- [ ] Unauthorized evaluator (not assigned to that contest) cannot subscribe to a candidate's stream

**Future Enhancements:** Multi-evaluator fan-out via an SFU (e.g., mediasoup) if you ever need >1 viewer per stream at scale — explicitly out of scope for this project, worth one line in your report's "future work."

---

## Module 13: Live Monitoring Dashboard

**Purpose:** The Admin/Evaluator-facing UI that ties together presence (Module 11), live streams (Module 12), and behavioral flags into one monitoring view.

**Functional Requirements**
- [ ] Grid/list view of all currently active candidates in a contest
- [ ] Click a candidate to open their live webcam + screen share
- [ ] Behavioral flags: tab-switch, copy-paste into editor, fullscreen exit, dev-tools open — logged and shown as a timeline per candidate
- [ ] Filter/sort candidates by "most flagged"

**Database Tables**
- `proctoring_events` (id, session_id FK, event_type, occurred_at, metadata JSONB)

**Backend Components**
- **Entities:** `ProctoringEvent`
- **Repositories:** `ProctoringEventRepository`
- **DTOs:** `LogProctoringEventRequest`, `ProctoringEventResponse`, `CandidateMonitoringSummary` (active status + flag count)
- **Services:** `ProctoringEventService` (log events, query timeline per session), aggregation method reused by Module 10's analytics
- **Controllers:** `ProctoringEventController` (`/api/sessions/{sessionId}/proctoring-events/**`)
- **Security Components:** Candidate can only POST events for their **own** active session; Admin/Evaluator read-only, scoped to contests they're assigned to

**Frontend Components**
- **Pages:** `LiveMonitoringPage` (Admin/Evaluator)
- **Hooks:** `useActiveCandidates(contestId)` (built on Module 11's presence), `useCandidateStream(candidateId)` (built on Module 12), `useProctoringEvents(sessionId)`, `useLogProctoringEvent()` (candidate side — fires on `visibilitychange`, `copy`/`paste`, `fullscreenchange`, and a best-effort dev-tools-open heuristic)
- **API Calls:** `proctoringApi.*`
- **UI Components:** `CandidateGrid`, `CandidateMonitorCard` (thumbnail + flag count badge), `LiveStreamModal`, `ProctoringTimeline`

**APIs**

| Method | Endpoint | Description | Access |
|---|---|---|---|
| POST | `/api/sessions/{sessionId}/proctoring-events` | Log a behavioral event | Candidate (own session only) |
| GET | `/api/sessions/{sessionId}/proctoring-events` | Timeline for one candidate | Admin/Evaluator |
| GET | `/api/contests/{contestId}/monitoring` | Active candidates + flag summary | Admin/Evaluator |

**Database Relationships**
- `proctoring_events.session_id` → `assessment_sessions.id`

**Sequence of Implementation**
1. `ProctoringEvent` entity + logging endpoint; wire up frontend event listeners (`visibilitychange` etc.) on the candidate side first — this is independent of WebRTC and can be built/tested in isolation.
2. `LiveMonitoringPage` shell using Module 11's presence data (list of who's active) — works even before WebRTC is wired in.
3. Integrate Module 12's stream viewer into the monitoring page.
4. Flag-count aggregation + sort/filter.

**Dependencies:** Modules 6, 11, 12.

**Definition of Done**
- [ ] Candidate switching tabs during an active session generates a visible flag on the Admin dashboard within a couple seconds
- [ ] Admin can view live video for any active candidate in a contest they administer
- [ ] Flag counts correctly aggregate per candidate per contest

**Future Enhancements:** Configurable flag severity/weighting, automatic session pause on repeated severe flags (careful — this is a significant behavior change, treat as clearly optional/future).

---

## Module 14: Deployment

**Purpose:** Package and run the entire system reliably on your self-hosted Ubuntu server.

**Functional Requirements**
- [ ] Single `docker-compose.yml` orchestrating: Spring Boot app, PostgreSQL, Redis, Judge0 (+ its own sub-services), Nginx
- [ ] Nginx reverse-proxies `/api` and `/ws` to Spring Boot, serves built React static assets directly, handles TLS if you set up a domain/cert (optional for a local demo)
- [ ] Environment-specific config via `.env` file, never committed secrets
- [ ] Database migrations via Flyway or Liquibase (**do this from the start**, not retrofitted — you'll thank yourself when your schema inevitably changes across 17 modules)

**Components**
- `docker-compose.yml` (top-level orchestration)
- `Dockerfile` for the Spring Boot app (multi-stage build: Maven build → slim JRE runtime image)
- `Dockerfile`/build config for the React app (Vite build → static files served by Nginx)
- `nginx.conf` (routing rules, gzip, basic security headers)
- Judge0's own official `docker-compose` (included/composed alongside yours — don't reinvent their setup)
- Flyway migration scripts (`V1__init_schema.sql`, `V2__add_proctoring_events.sql`, etc. — one per meaningful schema change, mapping roughly to your module sequence)
- `.env.example` committed to git, real `.env` gitignored

**Sequence of Implementation**
1. Get Judge0's own Docker Compose running standalone first (you actually need this working from Module 7 onward, so this isn't really "last" — deployment infra for Judge0 specifically should exist early; this module is about **finalizing the full-stack orchestration**).
2. Dockerize the Spring Boot app.
3. Dockerize/build the React app, wire into Nginx.
4. Compose everything together, test full stack from a clean `docker compose up`.
5. Set up Flyway migrations retroactively if not done incrementally (better: do this alongside Module 1, not here).
6. Basic Nginx security headers, gzip, and (optional) self-signed TLS cert for a "production-like" demo.

**Dependencies:** All functional modules should be feature-complete or near-complete before final orchestration, though Judge0's Docker setup is needed as early as Module 7.

**Definition of Done**
- [ ] `docker compose up` from a clean checkout brings up the entire working system with no manual steps beyond `.env` setup
- [ ] A fresh database is correctly migrated to the latest schema automatically on startup
- [ ] The app is reachable via Nginx on the server's LAN IP (or domain, if configured) with no direct port access needed to individual services

**Future Enhancements:** CI/CD pipeline (GitHub Actions building/pushing images), horizontal scaling considerations (out of scope for a single-laptop deployment, but a good one-paragraph "future work" note in your report).

---

## Module 15: Integration & System Testing

**Purpose:** Verify the system as a whole, not just individual modules — this is distinct from the unit tests each module already includes in its own Definition of Done.

**Functional Requirements**
- [ ] End-to-end test: Admin creates contest → assigns candidates → candidates take exam concurrently → auto-submit → evaluation → publish → analytics reflect real data
- [ ] Load test: simulate N concurrent candidates submitting code around the same time, verify Judge0 queue holds up
- [ ] Security test pass: verify object-level authorization holes are closed (candidate can't access another candidate's session/submission by guessing IDs), hidden test cases never leak, WebRTC streams can't be joined by unauthorized users

**Components**
- A test script/harness (can be a simple Node/Python script or Postman/Newman collection) simulating multiple concurrent candidate sessions
- Manual test checklist covering every module's Definition of Done, re-verified together rather than in isolation
- Basic load-testing tool (e.g., k6 or JMeter) targeted at the `/api/submissions/submit` endpoint specifically, since it's your highest-load path

**Sequence of Implementation**
1. Write the full concurrent-candidate simulation script early enough to catch integration issues before deployment week, not after.
2. Run it against a contest with 10-20 simulated candidates.
3. Fix issues found (likely candidates: race conditions in session expiry, Judge0 queue backpressure, WebSocket reconnection edge cases).
4. Security checklist pass — go through Section "Security Components" of every module above and verify explicitly.

**Dependencies:** All prior modules functionally complete.

**Definition of Done**
- [ ] Full concurrent simulation completes without data corruption or crashed services
- [ ] Security checklist fully passed with each item explicitly verified (not assumed)
- [ ] Known issues/limitations documented (every real system has some — documenting them well is better than hiding them for viva)

---

## Module 16: Documentation

**Purpose:** Your final report, architecture documentation, and viva prep — treat this as a deliverable module with its own checklist, not an afterthought.

**Components to Produce**
- [ ] System architecture diagram (Section 1 of this doc, refined with your actual final decisions)
- [ ] ER diagram (full schema across all modules — generate from your actual Postgres schema via a tool like `pg_dump` + a schema visualizer, not hand-drawn, for accuracy)
- [ ] API documentation (Springdoc/OpenAPI/Swagger — auto-generated from your controllers, minimal extra effort, looks very professional)
- [ ] Module-by-module writeup in your report (this document is most of that work already done)
- [ ] Judge0 integration deep-dive section (your strongest technical differentiator — explain the queueing, the normalized `ExecutionResult` abstraction, and how you handle failure modes)
- [ ] Security section (JWT + refresh rotation, object-level authorization, hidden test case isolation, WebRTC authorization) — examiners specifically like seeing security addressed as its own topic
- [ ] Known limitations / future work section (no video recording, no AI-based cheating detection, single-server deployment, no TURN server — being upfront about scope boundaries reads as maturity, not weakness)
- [ ] Rehearsed demo script (pick one realistic end-to-end scenario, time it, know exactly what to click)

**Dependencies:** All other modules complete.

**Definition of Done**
- [ ] Report reviewed against your college's format requirements
- [ ] Live demo rehearsed at least twice end-to-end
- [ ] Team can each answer questions about the module(s) they built, plus a basic understanding of the whole system

---

## Appendix: Full Entity Relationship Summary

```
users ──< refresh_tokens
users ──< contests (created_by)
users ──< contest_candidates >── contests
users ──< assessment_sessions >── contests
contests ──< questions ──< test_cases
assessment_sessions ──< submissions >── questions
submissions ──< submission_test_case_results >── test_cases
submissions ──< manual_evaluations >── users (evaluator)
contests ──< results >── users (candidate)
assessment_sessions ──< proctoring_events
users ──< audit_log
```

---

## Appendix: Cross-Module Consistency Checklist

Run through this whenever starting a new module — it should feel repetitive, that's the point:

- [ ] New entity extends `BaseEntity`
- [ ] New endpoints return `ApiResponse<T>` / `PagedResponse<T>`
- [ ] New exceptions extend the shared `AppException` hierarchy, handled by `GlobalExceptionHandler`
- [ ] Object-level authorization checked, not just role-based (`@PreAuthorize` is necessary but often not sufficient)
- [ ] New frontend API calls go through the shared `apiClient`, wrapped in a typed hook
- [ ] New list views use the shared `DataTable`
- [ ] New forms use the established React Hook Form + Zod pattern
- [ ] Any new schema change has a corresponding Flyway migration, committed alongside the code that needs it