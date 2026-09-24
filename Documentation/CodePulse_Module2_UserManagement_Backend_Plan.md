# CodePulse Enterprise — Module 2 Backend Build Plan
### User Management Service

**Purpose:** Admin-controlled creation and management of Evaluator and Candidate accounts, since this is an institutional exam platform with no public self-registration.

**Depends on:** Module 0 (Cross-Cutting Foundation) + Module 1 (Authentication Service) — both must be functionally complete before starting.

---

## 1. What This Module Inherits (Do Not Rebuild)

| From | Component | How it's used here |
|---|---|---|
| Module 0 | `BaseEntity` | `User` already extends this — id (UUID), createdAt, updatedAt, createdBy, updatedBy |
| Module 0 | `ApiResponse<T>` | Every new endpoint wraps its response in this envelope |
| Module 0 | `PagedResponse<T>` | Used by `GET /api/users` list endpoint |
| Module 0 | `GlobalExceptionHandler` | Handles all exceptions this module throws — no new handler needed |
| Module 0 | Exception hierarchy | Reuse `DuplicateResourceException` (duplicate email), `ResourceNotFoundException` (user not found), `AccessDeniedException` |
| Module 0 | `Role` enum | Reused for role assignment on user creation |
| Module 0 | `AuditLog` / `AuditService` | Log user creation, deactivation, role changes — feeds your report's "who did what" narrative |
| Module 1 | `User` entity | Extended with search/filter query support, not recreated |
| Module 1 | `UserRepository` | Extended with new query methods |
| Module 1 | `PasswordService` | Reused for hashing on create + verifying on password change |
| Module 1 | `SecurityConfig` | Extended (not rebuilt) — this module adds your first real use of method-level `@PreAuthorize` on top of the existing filter chain |
| Module 1 | `JwtAuthenticationFilter` | Untouched — every endpoint here already sits behind auth by default |

**Blocker check:** if any of the above aren't solid and tested, fix that first. Module 2's roadmap entry explicitly lists Module 1 as its only dependency — meaning it inherits Module 1's gaps too.

---

## 2. Decision to Make Before Writing Any Code

**How does an Admin-created user get their initial password?**

Two options, and this changes `CreateUserRequest`'s shape and `UserService.createUser()`'s logic:

- **Option A — Admin sets it directly.** `CreateUserRequest` includes a `password` field. Simple, no email infrastructure needed, but Admin has to communicate it out-of-band.
- **Option B — Auto-generated.** Backend generates a random password, hashes it for storage, and returns the plaintext once in the create response (or emails it, if you want to build that infra). No password field in the request.

The roadmap doesn't specify — pick one now. Option A is the lower-effort path for a student project scope; Option B reads better in a viva as "enterprise practice." Whichever you pick, it also determines whether `BulkImportResult` needs to surface generated passwords per row.

---

## 3. Database

**Tables:** none new. This module builds entirely on the `users` table created in Module 1.

No migration needed unless you add a column — check whether `is_active` already exists on `User` from Module 1 (the roadmap includes it in Module 1's `users` schema). If it's missing, that's a small Flyway migration to add before anything else here.

---

## 4. File-by-File Build Order

### Step 1 — DTOs

Lock these contracts before writing any service logic.

| File | Contents / Purpose |
|---|---|
| `CreateUserRequest` | email, full name, role, (password field, if Option A above) |
| `UpdateUserRequest` | Admin-path fields: role, is_active. Keep this **separate** from the self-update DTO below — don't let one bloated DTO carry both admin-only and self-service fields with conditional validation, it gets messy fast and makes the authorization boundary implicit instead of explicit. |
| `UpdateOwnProfileRequest` | Self-service fields only: full name. Nothing role- or status-related belongs here. |
| `ChangePasswordRequest` | currentPassword, newPassword — used by the self-service password change flow |
| `UserSummaryResponse` | Safe outward-facing shape: id, email, fullName, role, isActive, createdAt. **Never** include `passwordHash`. |
| `BulkImportResult` | totalRows, succeededCount, failedCount, `List<RowError>` where each `RowError` has rowNumber + reason (e.g. "duplicate email", "invalid role") |

**Why DTOs first:** every downstream file (service, controller, tests) is written against these shapes. Deciding them upfront avoids reshaping method signatures mid-build.

---

### Step 2 — `UserRepository` extension

Extend the existing repository (don't create a new one) with:

- Filter by role
- Filter by active status
- Search by name/email substring
- A combined paginated query (Spring Data `Specification` or a `@Query` with `Pageable`) backing the admin list view

This is the query layer `GET /api/users` depends on — build it before the service method that calls it.

---

### Step 3 — `UserService`

The core logic layer. One method per responsibility, kept explicit rather than clever:

| Method | Responsibility |
|---|---|
| `createUser(CreateUserRequest)` | Check email uniqueness → throw `DuplicateResourceException` if taken. Hash password via `PasswordService`. Assign role. Persist. Log via `AuditService`. |
| `updateUser(id, UpdateUserRequest)` | **Admin-only path.** Updates role/status. Throws `ResourceNotFoundException` if id doesn't exist. |
| `updateOwnProfile(UpdateOwnProfileRequest)` | **Self-service path.** Resolves "current user" from `SecurityContext`, updates only name. |
| `changePassword(ChangePasswordRequest)` | Re-verifies `currentPassword` against stored hash before allowing the change — don't skip this check even though the user is already authenticated. |
| `deactivateUser(id)` / `reactivateUser(id)` | Flips `is_active`. This is a soft delete — the row is never removed. |
| `getUsers(filters, pageable)` | Returns `PagedResponse<UserSummaryResponse>`, backed by the Step 2 repository query. |
| `getCurrentUser()` | Backs `GET /api/users/me`. |
| `bulkImportUsers(MultipartFile)` | Delegates parsing to `CsvImportService`, then per-row calls into the same validation/creation path as `createUser()` — don't duplicate the creation logic here. Assembles `BulkImportResult`. |

Keep `updateUser` and `updateOwnProfile` as two separate methods even though they both "update a user" — the authorization boundary (Admin-on-anyone vs self-on-self) should be visible in the method split, not buried inside one method with an `if (isAdmin)` branch.

---

### Step 4 — `CsvImportService`

Build this in a **shared/common package**, not inside the user module — the roadmap flags this explicitly because Module 4 (bulk question import) reuses the exact same pattern.

Design it generic from day one:
- Accepts a row-parsing function (raw CSV row → typed object)
- Accepts a per-row validation/creation function (typed object → success or failure)
- Wraps each row in its own try/catch so one bad row doesn't abort the whole batch
- Returns a generic result structure (row index + outcome) that `UserService` maps into `BulkImportResult`

Retrofitting this into something generic *after* it's hardcoded to `User` is more work than designing it generic now — this is the one file in this module worth over-engineering slightly upfront.

---

### Step 5 — `UserController`

Maps directly to the roadmap's API table. Every response wrapped in `ApiResponse<T>`.

| Method | Endpoint | Access | Notes |
|---|---|---|---|
| GET | `/api/users` | Admin | Paginated + filterable, returns `ApiResponse<PagedResponse<UserSummaryResponse>>` |
| POST | `/api/users` | Admin | Returns created `UserSummaryResponse` |
| PUT | `/api/users/{id}` | Admin | Uses `UpdateUserRequest` |
| PATCH | `/api/users/{id}/deactivate` | Admin | Soft delete |
| POST | `/api/users/bulk-import` | Admin | Multipart file upload, returns `BulkImportResult` |
| GET | `/api/users/me` | Authenticated | Any role |
| PUT | `/api/users/me` | Authenticated | Uses `UpdateOwnProfileRequest` |

Consider whether `changePassword` needs its own endpoint (e.g. `PATCH /api/users/me/password`) — the roadmap's table doesn't list one explicitly but the functional requirements mention password change, so add it here.

---

### Step 6 — Security

No new filter or security component. This module's security work is:
- `@PreAuthorize("hasRole('ADMIN')")` on every admin-only controller method
- `/me` endpoints resolve "current user" from the existing `SecurityContext` (populated by Module 1's `JwtAuthenticationFilter`) — no manual token parsing here

Note on scope: this module is role-based authorization only. Object-level authorization (verifying a user can only touch *their own* resource by ID, not just "any resource because they have the right role") isn't a real risk yet in Module 2 since every endpoint is either "self" or "Admin" — it becomes critical starting Module 4 onward when candidates start accessing contest-scoped resources by ID.

---

### Step 7 — Configuration

- Raise the multipart file size limit in `application-{profile}.yml` for the CSV upload endpoint — Spring's default is small and will silently reject anything but a tiny file if untouched.
- If `AuditService` needs any new action-type enum values (e.g. `USER_CREATED`, `USER_DEACTIVATED`), add them to the shared enum from Module 0 rather than a module-local one.

---

### Step 8 — Tests

| Test | Covers |
|---|---|
| `UserServiceTest` | CRUD happy paths, duplicate-email rejection, deactivate/reactivate |
| Integration test spanning `UserService` + `AuthService` | Deactivated user is blocked at login with a **specific, clear error** — not a generic 401. This crosses into Module 1's `AuthService`, so it may require a small addition there: check `is_active` before password verification and throw a distinct exception. |
| `CsvImportServiceTest` | Valid file, file containing a duplicate row, file with a malformed row — assert partial success + row-level error reporting, not all-or-nothing failure |
| Controller-level test (MockMvc or equivalent) | Explicitly assert `@PreAuthorize` blocks non-Admin roles from admin endpoints — don't just trust the annotation compiled correctly |

---

## 5. Sequence Summary (Build in This Order)

1. Decide password-on-create strategy (Section 2)
2. DTOs
3. `UserRepository` query extensions
4. `UserService` CRUD + self-service methods
5. `@PreAuthorize` checks on admin endpoints
6. `CsvImportService` (generic)
7. Wire `bulkImportUsers()` into `UserService`
8. `UserController`
9. Multipart config
10. Tests (service → integration → controller-level security assertions)

---

## 6. Definition of Done

- [ ] Admin can create Evaluator and Candidate accounts, and those accounts can log in
- [ ] Deactivated users cannot log in, and receive a clear, specific error (not a generic 401)
- [ ] CSV import correctly creates N candidates and returns a validation report of failures (e.g. duplicate emails), rather than failing the whole batch on one bad row
- [ ] Non-Admin users get a 403 on every admin-only endpoint (explicitly tested, not assumed)
- [ ] `/api/users/me` correctly resolves the authenticated user from the JWT with no additional lookup parameters

---

## 7. Future Enhancements (Out of Scope for This Module)

- Self-service password reset via email
- User activity audit trail view in the admin UI (the `AuditLog` data will already exist from Module 0 — this is just a UI/query layer on top, deferrable to later or to the Documentation module's "future work" section)
