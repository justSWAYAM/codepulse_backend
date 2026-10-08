# CodePulse — Module 5B: Bulk Question Import (AI Prompt Workflow)
### Backend-Only Implementation Plan — file by file, reference by reference

**Stack (backend only):** Spring Boot 3 (Java 21) · Spring Security + `@PreAuthorize` · Spring Data JPA/Hibernate · PostgreSQL · Jackson · Bean Validation (Jakarta) · JUnit 5 + MockMvc
**Depends on:** Module 5A (Question Library) and Module 5 (type-aware test-case validation). Reuses Modules 1, 2, 4, 5A and the Foundation layer.
**Database changes:** **none.** No new table, no Flyway migration.
**Frontend:** **not covered here.** (Import dialog, Copy Prompt button, Test Case Prompt button, import-result table — separate document.)

---

## 0. Scope and ground rules

### 0.1 What this module does (backend)

A teacher pastes the JSON that their own LLM produced. The backend:

1. serves the **type-specific prompt text** the teacher copies into the LLM (`GET /api/library/import-template`),
2. **parses** the pasted output, tolerating Markdown code fences and common LLM sloppiness,
3. **validates every row** with the same Bean Validation rules as manual question creation, plus per-type rules,
4. returns a **preview** (`dryRun=true`) — nothing stored — or **imports** the valid rows (`dryRun=false`) into the library folder and reports invalid rows by row number and reason,
5. never creates test cases (DSA/SQL import without them; Module 3 publish validation enforces them later).

### 0.2 What this module does NOT do

- No frontend (dialog, buttons, `TestCasePromptButton`, `CopyPromptButton`).
- No new tables/columns, no new entity, no new repository.
- No test-case import code. The **Test Case Prompt** only needs Module 5's type-aware CSV validation to already exist; this document only **verifies** it (Step 10).
- No CSV/Excel question import, no import-into-contest (both are "Future Enhancements" in the roadmap).

### 0.3 Decisions locked in this plan (so nobody has to guess)

| # | Decision | Why |
|---|---|---|
| D1 | One endpoint serves preview and import; `dryRun` **defaults to `true`** | A forgotten parameter can never write data |
| D2 | **Partial success**: valid rows are saved, invalid rows are reported | Roadmap: "Valid rows import, invalid rows are reported" |
| D3 | **Each row is saved in its own transaction** (`REQUIRES_NEW`), the orchestrator is **not** `@Transactional` | One bad row must not roll back or poison the others |
| D4 | Row numbers are **1-based positions in the JSON array** | What the teacher can count in the LLM output |
| D5 | The request-level `type` applies to **every row** in the batch; items carry no `type` field | One prompt per type; no mixed batches |
| D6 | Malformed JSON, >200 questions or >1 MB reject the **whole request** (cannot be reported per row) | Cannot attribute errors to rows |
| D7 | Rows are persisted by calling the **same `QuestionService` method as `POST /api/library/questions`** | Same validation, same author auditing, no drift |
| D8 | Prompt text lives in `resources/prompts/{type}.txt`, and a **startup check** fails the app if a prompt and the parser's field list disagree | Roadmap: "prompt and parser cannot drift" |
| D9 | The import JSON mapper is **private to the parser, not a Spring bean** | A second `ObjectMapper` bean silently disables Spring Boot's auto-configured one and changes JSON for the whole API (see §9, pitfall P1) |
| D10 | Imported questions are authored by the **importing user** (auditing → `created_by`) | Library shows author name (Module 5A) |

---

## 1. How the pieces fit

```
Evaluator/Admin
   │  GET  /api/library/import-template?type=MCQ ───────────────► PromptTemplateService ──► resources/prompts/mcq.txt
   │      (copies prompt into Claude / Gemini / ChatGPT, pastes questions, gets JSON)
   │
   │  POST /api/library/questions/import?dryRun=true|false
   │      body { subjectId, type, payload }
   ▼
LibraryImportController  (@PreAuthorize hasAnyRole ADMIN,EVALUATOR)
   ▼
QuestionImportService.process()                 ← NOT @Transactional
   ├─ SubjectRepository.findById        (404 SUBJECT_NOT_FOUND)           [Module 5A]
   ├─ QuestionImportParser.parse        (size, fences, JSON, ≤200 rows)
   ├─ BulkImportService.run(...)        (generic loop: parse → validate → write, per-row errors)
   │      ├─ parse stage:    QuestionImportValidator.validateItem  +  QuestionImportMapper.toRequest
   │      ├─ validate stage: jakarta Validator on CreateQuestionRequest            [Module 4 rules]
   │      └─ write stage:    QuestionImportRowWriter.write  (REQUIRES_NEW) ─► QuestionService.createLibraryQuestion  [Module 5A]
   ├─ AuditService.log (real import only)                                    [Foundation]
   └─ QuestionImportResponse { dryRun, BulkImportResult, rows[] }            [BulkImportResult from Module 2]
```

### 1.1 Reuse map (nothing here is rebuilt)

| Need | Reused from | How |
|---|---|---|
| Import report shape (row number + reason) | `BulkImportResult` (Module 2) | Unchanged; wrapped in the new response |
| Generic "rows → validate → report" loop | `CsvImportService` pattern (Module 2) | Generalized into `BulkImportService` (Step 2) |
| Create a library question (all types, options, author) | `QuestionService` create path used by `POST /api/library/questions` (Module 5A/4) | Called per row, never re-implemented |
| Field validation rules | Bean Validation on `CreateQuestionRequest` (Module 4) | Executed via `jakarta.validation.Validator` |
| Subject lookup + 404 | `SubjectRepository`, `ResourceNotFoundException` (Module 5A / Foundation) | As is |
| Response envelope | `ApiResponse<T>` (Foundation) | Every endpoint |
| Errors → HTTP | `AppException` hierarchy, `ErrorCode`, `GlobalExceptionHandler` (Foundation) | One new exception subclass + 3 error codes |
| Role gate | `hasAnyRole('ADMIN','EVALUATOR')` pattern (Module 5A `LibraryController`) | Same expression |
| Audit trail | `AuditService` (Foundation) | One call on real imports |
| Author / `created_by` | JPA auditing from `BaseEntity` (Foundation) | Automatic via the reused service |
| "No test cases" badge | Test-case count in library list DTOs (Module 5A) | Nothing to build; verified in Step 9 |
| Test-case CSV validation per type | `TestCaseBulkUploadService`, `DsaRowValidator`, `SqlRowValidator` (Module 5) | **Verified only** (Step 10) |

---

## 2. API contract

### 2.1 `GET /api/library/import-template?type={DSA|SQL|MCQ|THEORY}`

Access: Evaluator / Admin. Response `200`:

```json
{
  "success": true,
  "data": {
    "type": "MCQ",
    "prompt": "Convert the questions in the text below into a JSON array. ...\nTEXT:\n<paste your questions here>",
    "maxQuestions": 200,
    "maxPayloadBytes": 1048576
  },
  "message": "OK",
  "timestamp": "…",
  "traceId": "…"
}
```

### 2.2 `POST /api/library/questions/import?dryRun=true|false` (default `true`)

Access: Evaluator / Admin. Request body:

```json
{
  "subjectId": "3f2b8f4e-0000-0000-0000-000000000001",
  "type": "MCQ",
  "payload": "```json\n[ {\"title\":\"…\", … } ]\n```"
}
```

`payload` is the **raw text** the teacher pasted (fences and surrounding prose allowed).

Response `200` (both preview and real import — partial failures are still `200`):

```json
{
  "success": true,
  "data": {
    "dryRun": true,
    "result": { "…": "BulkImportResult exactly as Module 2 defines it (total / success / failure counts + errors with rowNumber + reason)" },
    "rows": [
      { "rowNumber": 1, "valid": true,  "title": "Which keyword …", "difficulty": "EASY", "points": 5,
        "descriptionPreview": "Which keyword declares …", "detail": "4 options · correct: 2", "errors": [] },
      { "rowNumber": 2, "valid": false, "title": "Broken one", "difficulty": null, "points": null,
        "descriptionPreview": "…", "detail": "", "errors": ["correct contains 5 but there are only 4 options"] }
    ]
  },
  "message": "Preview generated",
  "timestamp": "…",
  "traceId": "…"
}
```

Counting semantics (document in Swagger): in a **dry run**, `successCount` = rows that *would* be created. In a **real import**, `successCount` = rows actually saved; a row that validated but failed to save is counted as a failure with the reason.

### 2.3 Errors

| Situation | HTTP | `ApiError.code` | New? |
|---|---|---|---|
| Not logged in | 401 | (existing entry-point) | no |
| Candidate role | 403 | existing access-denied code | no |
| `subjectId` unknown | 404 | `SUBJECT_NOT_FOUND` | no (5A) — confirm in Step 0 |
| Missing/invalid `type`, `subjectId`, blank `payload` | 400 | existing validation code (`fieldErrors[]`) | no |
| Payload > 1 MB (UTF-8 bytes) | 413 | `IMPORT_PAYLOAD_TOO_LARGE` | **yes** |
| No JSON array found / invalid JSON / empty array | 400 | `IMPORT_PAYLOAD_INVALID` | **yes** |
| More than 200 questions | 400 | `IMPORT_LIMIT_EXCEEDED` | **yes** |
| Bad row (any reason) | 200 | — (reported inside `result` and `rows[]`) | — |

---

## 3. Step 0 — Pre-flight (do this before writing any code)

I wrote this plan against the roadmap, not against your repository. The table lists every name or fact that the code below depends on. **Look each one up, write the real value in the "Locked value" column, and adjust the code snippets accordingly.** Do not skip this — it is what keeps the build error-free.

| # | Fact to lock | How to find it | Locked value |
|---|---|---|---|
| L1 | Root package and the package/location of `CsvImportService` | `grep -rn "class CsvImportService" src/main` | |
| L2 | `BulkImportResult` shape (fields, constructor/builder/factory, error-item type with row number + reason) | open the class | |
| L3 | `ApiResponse` factory method names (e.g. `ok(data)`, `success(data, msg)`) | `grep -rn "class ApiResponse" src/main` | |
| L4 | `AppException` constructor signature; name of the error-code enum (`ErrorCode`?); whether `AppException` carries an `HttpStatus` or the handler maps per subclass | open `AppException`, `GlobalExceptionHandler` | |
| L5 | Does `GlobalExceptionHandler` handle `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException`? | read the handler | |
| L6 | Exact components of `CreateQuestionRequest` (names, types), the MCQ option request type, whether it holds `subjectId`, `questionType` field name | open the DTO | |
| L7 | The `QuestionService` method that `POST /api/library/questions` calls (name, parameters, return) | open `LibraryController` | |
| L8 | Type of `difficulty` (enum name or `String`); constraints on `title` / `description` / `points` (`@Size`, `@Min`) | open `CreateQuestionRequest` + `Question` | |
| L9 | The default DSA `timeLimitMs` / `memoryLimitKb` used by the existing question form/service; whether `time_limit_ms` / `memory_limit_kb` columns are `NOT NULL` | frontend `QuestionForm` defaults + `V1`/`V2` migration | |
| L10 | `mcq_options.order_index` base (0 or 1) used by the existing MCQ create path | read the code that saves options | |
| L11 | `SubjectRepository` exists; `SUBJECT_NOT_FOUND` error code exists | grep | |
| L12 | `SecurityConfig` covers `/api/library/**` (authenticated, or role matcher) | open `SecurityConfig` | |
| L13 | `AuditService` method signature and how Modules 3/4 call it | grep usages | |
| L14 | How the current user is obtained (helper/`AuditorAware`) — only needed for the log line | grep | |
| L15 | Integration-test setup: Testcontainers or a test profile DB; helper to obtain a JWT per role | open an existing `*IT` test | |
| L16 | `questions.contest_id` is nullable, `subjects` table and Module 4 columns exist (V2 migration applied) | `\d questions` in psql | |
| L17 | Multipart field name of `POST /api/questions/{id}/test-cases/bulk` (for the Step 10 regression test) | open `TestCaseController` | |

### 3.1 Gate checks

1. **Module 5A Definition of Done is green** (library CRUD works, Candidate gets 403 on `/api/library/**`). If not, stop — 5B has nothing to import into.
2. **Run the whole existing test suite now** and record the result. Every later step must keep it green. This is the baseline for "existing DSA importer behaves exactly as before".
3. Confirm L5. If the handler lacks `HttpMessageNotReadableException` / `MethodArgumentTypeMismatchException`, a bad `type` value would surface as a 500. Add both handlers to `GlobalExceptionHandler` mapped to the existing 400 validation code (small additive change; mention it in the PR).

---

## 4. File inventory

All new classes live under `com.codepulse.library.imports` (adjust the root to L1) unless noted.

### 4.1 New files

| # | File | Purpose |
|---|---|---|
| 1 | `common/bulkimport/BulkImportService.java` (+ `RowParser`, `RowValidator`, `RowWriter`, `RowRejectedException`, `RowOutcome`, `BulkRun`) | Generic per-row loop shared by all bulk imports |
| 2 | `imports/config/ImportProperties.java` | `codepulse.import.*` settings |
| 3 | `imports/config/ImportConfig.java` | Enables `ImportProperties` |
| 4 | `imports/exception/ImportException.java` | Whole-request import failures |
| 5 | `imports/dto/ImportQuestionsRequest.java` | Request body |
| 6 | `imports/dto/ImportQuestionItem.java` | One parsed JSON question |
| 7 | `imports/dto/ImportRowPreview.java` | One preview row |
| 8 | `imports/dto/QuestionImportResponse.java` | Response data |
| 9 | `imports/dto/ImportTemplateResponse.java` | Template response data |
| 10 | `imports/ImportFieldSpec.java` | Single source of truth for field names per type |
| 11 | `imports/QuestionImportParser.java` | Text → rows (fences, JSON, limits) |
| 12 | `imports/QuestionImportValidator.java` | Per-type row rules |
| 13 | `imports/QuestionImportMapper.java` | Item → `CreateQuestionRequest`, preview helpers |
| 14 | `imports/QuestionImportRowWriter.java` | `REQUIRES_NEW` persistence of one row |
| 15 | `imports/QuestionImportService.java` | Orchestrator |
| 16 | `imports/PromptTemplateService.java` | Loads/serves/validates prompt files |
| 17 | `imports/LibraryImportController.java` | The two endpoints |
| 18–21 | `src/main/resources/prompts/dsa.txt`, `sql.txt`, `mcq.txt`, `theory.txt` | Prompt templates |

### 4.2 Modified files (small, additive)

| File | Change |
|---|---|
| `ErrorCode` (name per L4) | Add `IMPORT_PAYLOAD_INVALID`, `IMPORT_PAYLOAD_TOO_LARGE`, `IMPORT_LIMIT_EXCEEDED` |
| `GlobalExceptionHandler` | Only if L4 says status is not carried by `AppException`: handler for `ImportException`. Only if L5 says missing: two handlers for bad request body / bad param type |
| `application.yml` | Add `codepulse.import.*` block |
| `SecurityConfig` | Only if L12 shows `/api/library/**` is not already covered |
| `nginx.conf` (Module 14 file) | `client_max_body_size 2m;` |
| `CsvImportService` | **Optional**, Step 11 only, behind characterization tests |

---

## 5. Implementation — step by step

> After every step: compile, run the **full** test suite, commit. Each step ends with a checkpoint; don't start the next one until it passes.

### Step 1 — Configuration, error codes, exception

**1.1 `application.yml`** (base file, applies to all profiles)

```yaml
codepulse:
  import:
    max-payload-bytes: 1048576      # 1 MB, measured in UTF-8 bytes
    max-questions: 200
    default-time-limit-ms: 2000      # LOCK (L9): copy the value the DSA question form uses
    default-memory-limit-kb: 262144  # LOCK (L9): copy the value the DSA question form uses
```

**1.2 `ImportProperties.java`**

```java
package com.codepulse.library.imports.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "codepulse.import")
public record ImportProperties(
        @DefaultValue("1048576") long maxPayloadBytes,
        @DefaultValue("200") int maxQuestions,
        int defaultTimeLimitMs,       // no default on purpose: app fails fast if you forgot L9
        int defaultMemoryLimitKb) {
}
```

**1.3 `ImportConfig.java`**

```java
package com.codepulse.library.imports.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ImportProperties.class)
public class ImportConfig {
}
```

**1.4 `ErrorCode`** — add three constants next to the existing ones (e.g. `CONTEST_NOT_FOUND`):

```java
IMPORT_PAYLOAD_INVALID,
IMPORT_PAYLOAD_TOO_LARGE,
IMPORT_LIMIT_EXCEEDED,
```

**1.5 `ImportException.java`** — extends the shared hierarchy (constructor per L4):

```java
package com.codepulse.library.imports.exception;

import org.springframework.http.HttpStatus;

public class ImportException extends AppException {

    public ImportException(ErrorCode code, String message) {
        super(code, message);                       // adapt to the real AppException constructor (L4)
    }

    public static ImportException invalid(String message) {
        return new ImportException(ErrorCode.IMPORT_PAYLOAD_INVALID, message);
    }
    public static ImportException tooLarge(long maxBytes) {
        return new ImportException(ErrorCode.IMPORT_PAYLOAD_TOO_LARGE,
                "Pasted text is larger than " + (maxBytes / 1024) + " KB. Import fewer questions at a time.");
    }
    public static ImportException limit(int found, int max) {
        return new ImportException(ErrorCode.IMPORT_LIMIT_EXCEEDED,
                "Found " + found + " questions; the maximum is " + max + " per import. Split the list and import in batches.");
    }
}
```

HTTP status mapping:

- If `AppException` carries a status, pass `HttpStatus.BAD_REQUEST` / `HttpStatus.PAYLOAD_TOO_LARGE` from the factory methods above.
- Otherwise add to `GlobalExceptionHandler`:

```java
@ExceptionHandler(ImportException.class)
public ResponseEntity<ApiError> handleImport(ImportException ex) {
    HttpStatus status = ex.getCode() == ErrorCode.IMPORT_PAYLOAD_TOO_LARGE
            ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.BAD_REQUEST;
    return ResponseEntity.status(status).body(/* build ApiError the same way the other handlers do (code, message, traceId) */);
}
```

**Checkpoint 1:** app starts; a missing `default-time-limit-ms` makes startup fail (prove it once, then restore).

---

### Step 2 — Generic `BulkImportService` (generalizes Module 2's `CsvImportService`)

Package `com.codepulse.common.bulkimport` (or next to `CsvImportService`, per L1). The class **does not parse CSV or JSON** — it only runs the per-row loop. CSV/JSON parsing stays with each caller.

**2.1 Small types**

```java
@FunctionalInterface
public interface RowParser<R, T> {
    T parse(R raw) throws RowRejectedException;
}

@FunctionalInterface
public interface RowValidator<T> {
    List<String> validate(T parsed);     // empty list = valid
}

@FunctionalInterface
public interface RowWriter<T> {
    void write(T parsed);
}

public class RowRejectedException extends RuntimeException {
    private final List<String> reasons;
    public RowRejectedException(String reason) { this(List.of(reason)); }
    public RowRejectedException(List<String> reasons) {
        super(String.join("; ", reasons), null, false, false);   // no stack trace: expected control flow
        this.reasons = List.copyOf(reasons);
    }
    public List<String> reasons() { return reasons; }
}

public record RowOutcome<T>(int rowNumber, T parsed, List<String> errors, boolean written) {
    public boolean valid() { return errors.isEmpty(); }
}

public record BulkRun<T>(List<RowOutcome<T>> rows) {
    public int total()   { return rows.size(); }
    public long validCount()   { return rows.stream().filter(RowOutcome::valid).count(); }
    public long invalidCount() { return rows.size() - validCount(); }
    public long writtenCount() { return rows.stream().filter(RowOutcome::written).count(); }
}
```

**2.2 `BulkImportService.java`**

```java
@Service
public class BulkImportService {

    private static final Logger log = LoggerFactory.getLogger(BulkImportService.class);

    /**
     * @param writer null = dry run: nothing is written.
     */
    public <R, T> BulkRun<T> run(List<R> rawRows,
                                 RowParser<R, T> parser,
                                 RowValidator<T> validator,
                                 RowWriter<T> writer) {
        List<RowOutcome<T>> outcomes = new ArrayList<>(rawRows.size());
        int rowNumber = 0;
        for (R raw : rawRows) {
            rowNumber++;
            T parsed = null;
            List<String> errors = new ArrayList<>();
            boolean written = false;

            try {
                parsed = parser.parse(raw);
                errors.addAll(validator.validate(parsed));
            } catch (RowRejectedException e) {
                errors.addAll(e.reasons());
            } catch (RuntimeException e) {
                log.error("Unexpected error reading import row {}", rowNumber, e);
                errors.add("Row could not be read (unexpected format)");
            }

            if (errors.isEmpty() && writer != null) {
                try {
                    writer.write(parsed);
                    written = true;
                } catch (AppException e) {
                    errors.add(e.getMessage());
                } catch (RuntimeException e) {
                    log.error("Unexpected error saving import row {}", rowNumber, e);
                    errors.add("Row could not be saved (internal error)");
                }
            }
            outcomes.add(new RowOutcome<>(rowNumber, parsed, List.copyOf(errors), written));
        }
        return new BulkRun<>(outcomes);
    }
}
```

Notes:

- `writer == null` is the single switch for dry run, so preview and import share every line of validation logic.
- The loop must **never** be `@Transactional`; see D3.
- Raw exception messages from the database are never returned to the client (`"internal error"` only; details go to the log with the trace id).

**2.3 Unit test `BulkImportServiceTest`**

| Case | Expectation |
|---|---|
| 3 rows, row 2 throws `RowRejectedException("bad")` | row 2 error `bad`; rows 1 and 3 valid; row numbers 1,2,3 |
| `writer == null` | writer never invoked (use a Mockito mock/counter); `writtenCount()==0` |
| writer throws `AppException` on row 2 | row 2 has the exception message and `written=false`; row 3 still written |
| writer throws `IllegalStateException` | row error is the generic text, not the exception message |
| validator returns 2 messages | both appear in `errors` |

**Checkpoint 2:** unit tests green; `CsvImportService` untouched, existing user-import and test-case-import tests still green.

---

### Step 3 — DTOs and `ImportFieldSpec`

**3.1 `ImportQuestionsRequest`**

```java
public record ImportQuestionsRequest(
        @NotNull UUID subjectId,
        @NotNull QuestionType type,        // com.codepulse.common.enums.QuestionType
        @NotBlank String payload) {
}
```

**3.2 `ImportQuestionItem`** — the parse target. Every field is optional at parse level so a missing field becomes a *row* error, not a request failure.

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImportQuestionItem(
        String title,
        String description,
        String difficulty,          // raw text; normalised/validated later (friendlier errors than an enum parse failure)
        Integer points,
        Integer timeLimitMs,        // DSA
        Integer memoryLimitKb,      // DSA
        String schemaSql,           // SQL
        Boolean orderMatters,       // SQL
        List<String> options,       // MCQ
        List<Integer> correct,      // MCQ, 1-based option numbers
        String modelAnswer) {       // THEORY
}
```

**3.3 `ImportRowPreview`**

```java
public record ImportRowPreview(
        int rowNumber,
        boolean valid,
        String title,
        String difficulty,
        Integer points,
        String descriptionPreview,   // first 160 chars
        String detail,               // e.g. "4 options · correct: 2,3"
        List<String> errors) {
}
```

**3.4 `QuestionImportResponse`**

```java
public record QuestionImportResponse(
        boolean dryRun,
        BulkImportResult result,          // Module 2 type, unchanged
        List<ImportRowPreview> rows) {
}
```

**3.5 `ImportTemplateResponse`**

```java
public record ImportTemplateResponse(
        QuestionType type,
        String prompt,
        int maxQuestions,
        long maxPayloadBytes) {
}
```

**3.6 `ImportFieldSpec`** — the single source of truth for which JSON fields exist per type. The prompt-drift guard (Step 7) and the tests both read it.

```java
public final class ImportFieldSpec {

    private static final List<String> COMMON = List.of("title", "description", "difficulty", "points");

    private ImportFieldSpec() {}

    public static List<String> fieldsFor(QuestionType type) {
        List<String> fields = new ArrayList<>(COMMON);
        switch (type) {
            case DSA    -> fields.addAll(List.of("timeLimitMs", "memoryLimitKb"));
            case SQL    -> fields.addAll(List.of("schemaSql", "orderMatters"));
            case MCQ    -> fields.addAll(List.of("options", "correct"));
            case THEORY -> fields.add("modelAnswer");
        }
        return List.copyOf(fields);
    }
}
```

> If `QuestionType` has more constants than these four, the `switch` expression will not compile — which is the desired alarm.

**Checkpoint 3:** compiles. Nothing is wired yet.

---

### Step 4 — `QuestionImportParser`

Turns the pasted text into rows. **Whole-request** problems throw `ImportException`; **per-row** problems become a `ParsedRow` with an error.

**4.1 Output type** (nested in the parser file or its own file)

```java
public record ParsedRow(int rowNumber, ImportQuestionItem item, String parseError) {
    public static ParsedRow ok(int n, ImportQuestionItem item) { return new ParsedRow(n, item, null); }
    public static ParsedRow bad(int n, String error)           { return new ParsedRow(n, null, error); }
}
```

**4.2 `QuestionImportParser.java`**

```java
@Component
public class QuestionImportParser {

    private static final Pattern FENCE =
            Pattern.compile("(?s)```[A-Za-z0-9_+-]*[ \\t]*\\R?(.*?)```");

    // Private on purpose — NOT a Spring bean (see D9 / pitfall P1).
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)   // LLMs put raw newlines inside strings
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)   // "correct": 2  → [2]
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final ImportProperties props;

    public QuestionImportParser(ImportProperties props) { this.props = props; }

    public List<ParsedRow> parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw ImportException.invalid("Nothing was pasted.");
        }
        if (payload.getBytes(StandardCharsets.UTF_8).length > props.maxPayloadBytes()) {
            throw ImportException.tooLarge(props.maxPayloadBytes());
        }

        ArrayNode array = findQuestionArray(payload);

        if (array.isEmpty()) {
            throw ImportException.invalid("The JSON array is empty — no questions found.");
        }
        if (array.size() > props.maxQuestions()) {
            throw ImportException.limit(array.size(), props.maxQuestions());
        }

        List<ParsedRow> rows = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            int rowNumber = i + 1;
            JsonNode node = array.get(i);
            if (!node.isObject()) {
                rows.add(ParsedRow.bad(rowNumber, "Row is not a JSON object"));
                continue;
            }
            try {
                rows.add(ParsedRow.ok(rowNumber, mapper.treeToValue(node, ImportQuestionItem.class)));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                rows.add(ParsedRow.bad(rowNumber, friendly(e)));
            }
        }
        return rows;
    }

    /** Tries: whole text → each fenced block → first '[' to last ']'. First one that yields an array wins. */
    private ArrayNode findQuestionArray(String payload) {
        String text = payload.startsWith("\uFEFF") ? payload.substring(1) : payload;   // strip BOM
        List<String> candidates = new ArrayList<>();
        candidates.add(text.trim());

        Matcher m = FENCE.matcher(text);
        while (m.find()) {
            candidates.add(m.group(1).trim());
        }

        int start = text.indexOf('['), end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            candidates.add(text.substring(start, end + 1));
        }

        String lastProblem = "No JSON array found. Paste the exact output of the AI.";
        for (String candidate : candidates) {
            if (candidate.isEmpty()) continue;
            try {
                JsonNode root = mapper.readTree(candidate);
                if (root.isArray()) return (ArrayNode) root;
                if (root.isObject() && root.path("questions").isArray()) {
                    return (ArrayNode) root.get("questions");        // tolerated wrapper: {"questions":[...]}
                }
                lastProblem = "The top-level JSON must be an array of questions.";
            } catch (JsonProcessingException e) {
                lastProblem = "The pasted text is not valid JSON: " + e.getOriginalMessage()
                        + " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
            }
        }
        throw ImportException.invalid(lastProblem);
    }

    private String friendly(Exception e) {
        if (e instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
            String field = jme.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("."));
            if (!field.isEmpty()) return "Field '" + field + "' has an invalid value";
        }
        return "Row has an invalid format";
    }
}
```

Behaviours you must keep (each has a test in §7):

- Fenced ```` ```json ```` block, bare array, prose before/after, `{"questions":[…]}` wrapper, trailing commas, raw newlines inside strings, BOM — all accepted.
- Size is measured in **UTF-8 bytes**, not characters.
- A single `readTree` failure on one candidate does not abort; the next candidate is tried. The message reported is the last attempt's.
- Jackson internals (class names, stack text) are never shown to the user.

**Checkpoint 4:** `QuestionImportParserTest` green (cases in §7.1).

---

### Step 5 — `QuestionImportValidator` and `QuestionImportMapper`

**5.1 `QuestionImportValidator.java`** — item-level, per-type rules. Returns **all** messages for the row (not just the first).

```java
@Component
public class QuestionImportValidator {

    public List<String> validateItem(QuestionType type, ImportQuestionItem it) {
        List<String> e = new ArrayList<>();

        if (isBlank(it.title()))        e.add("title is required");
        if (isBlank(it.description()))  e.add("description is required");

        if (isBlank(it.difficulty())) {
            e.add("difficulty is required (EASY, MEDIUM or HARD)");
        } else if (!Set.of("EASY", "MEDIUM", "HARD").contains(normalizeDifficulty(it.difficulty()))) {
            e.add("difficulty must be EASY, MEDIUM or HARD (got '" + it.difficulty().trim() + "')");
        }

        if (it.points() == null)  e.add("points is required");
        else if (it.points() <= 0) e.add("points must be greater than 0");

        switch (type) {
            case DSA -> {
                if (it.timeLimitMs() != null && it.timeLimitMs() <= 0)       e.add("timeLimitMs must be greater than 0");
                if (it.memoryLimitKb() != null && it.memoryLimitKb() <= 0)   e.add("memoryLimitKb must be greater than 0");
            }
            case SQL -> {
                if (isBlank(it.schemaSql())) {
                    e.add("schemaSql is required");
                } else if (!CREATE_TABLE.matcher(it.schemaSql()).find()) {
                    e.add("schemaSql must contain at least one CREATE TABLE statement");
                }
            }
            case MCQ -> validateMcq(it, e);
            case THEORY -> { /* modelAnswer optional */ }
        }
        return e;
    }

    private void validateMcq(ImportQuestionItem it, List<String> e) {
        List<String> options = it.options();
        if (options == null || options.size() < 2) {
            e.add("at least 2 options are required");
        } else {
            for (int i = 0; i < options.size(); i++) {
                if (isBlank(options.get(i))) e.add("option " + (i + 1) + " is blank");
            }
        }

        List<Integer> correct = it.correct();
        if (correct == null || correct.isEmpty()) {
            e.add("correct is required (1-based option numbers, e.g. [2])");
        } else {
            int optionCount = options == null ? 0 : options.size();
            for (Integer n : correct) {
                if (n == null || n < 1 || n > optionCount) {
                    e.add("correct contains " + n + " but there are only " + optionCount + " options");
                }
            }
            if (new HashSet<>(correct).size() != correct.size()) {
                e.add("correct contains duplicate numbers");
            }
        }
    }

    public static String normalizeDifficulty(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?i)\\bCREATE\\s+(TEMP\\s+|TEMPORARY\\s+)?TABLE\\b");

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
```

**5.2 `QuestionImportMapper.java`** — pure mapping, no I/O. **Names below follow the roadmap; adapt to L6/L8/L10.**

```java
@Component
public class QuestionImportMapper {

    private final ImportProperties props;

    public QuestionImportMapper(ImportProperties props) { this.props = props; }

    public CreateQuestionRequest toRequest(QuestionType type, UUID subjectId, ImportQuestionItem it) {
        CreateQuestionRequest.CreateQuestionRequestBuilder b = CreateQuestionRequest.builder()   // adapt to real DTO (L6)
                .questionType(type)
                .subjectId(subjectId)                                    // contestId stays null → library question
                .title(it.title().trim())
                .description(it.description().strip())
                .difficulty(Difficulty.valueOf(QuestionImportValidator.normalizeDifficulty(it.difficulty())))  // L8
                .points(it.points());

        switch (type) {
            case DSA -> b.timeLimitMs(it.timeLimitMs() != null ? it.timeLimitMs() : props.defaultTimeLimitMs())
                         .memoryLimitKb(it.memoryLimitKb() != null ? it.memoryLimitKb() : props.defaultMemoryLimitKb());
            case SQL -> b.schemaSql(it.schemaSql())
                         .orderMatters(Boolean.TRUE.equals(it.orderMatters()));
                         // if time/memory columns are NOT NULL (L9), also set the same defaults as DSA here
            case MCQ -> b.options(toOptions(it));
            case THEORY -> b.modelAnswer(blankToNull(it.modelAnswer()));
        }
        return b.build();
    }

    /** options[i] ↔ correct contains (i+1). Order and text preserved exactly (trimmed only). */
    private List<McqOptionRequest> toOptions(ImportQuestionItem it) {            // option DTO name per L6
        Set<Integer> correct = new HashSet<>(it.correct());
        List<McqOptionRequest> out = new ArrayList<>();
        for (int i = 0; i < it.options().size(); i++) {
            out.add(McqOptionRequest.builder()
                    .text(it.options().get(i).trim())
                    .correct(correct.contains(i + 1))
                    .orderIndex(i /* or i + 1 — LOCK L10: same base the manual MCQ create uses */)
                    .build());
        }
        return out;
    }

    // ---- preview helpers (display only) ----

    public String detail(QuestionType type, ImportQuestionItem it) {
        return switch (type) {
            case DSA -> "time " + (it.timeLimitMs() != null ? it.timeLimitMs() : props.defaultTimeLimitMs()) + " ms · memory "
                    + (it.memoryLimitKb() != null ? it.memoryLimitKb() : props.defaultMemoryLimitKb()) + " KB";
            case SQL -> "schema: " + countStatements(it.schemaSql()) + " statements · orderMatters=" + Boolean.TRUE.equals(it.orderMatters());
            case MCQ -> (it.options() == null ? 0 : it.options().size()) + " options · correct: "
                    + (it.correct() == null ? "-" : it.correct().stream().map(String::valueOf).collect(Collectors.joining(",")));
            case THEORY -> "model answer: " + (blankToNull(it.modelAnswer()) != null ? "yes" : "no");
        };
    }

    public String descriptionPreview(String description) {
        if (description == null) return "";
        String flat = description.strip().replaceAll("\\s+", " ");
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }

    private static int countStatements(String sql) {
        return sql == null ? 0 : (int) Arrays.stream(sql.split(";")).filter(s -> !s.isBlank()).count();
    }
    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s.strip(); }
}
```

Rules to keep:

- **No Bean Validation annotations are copied or re-declared** here. Rules that exist on `CreateQuestionRequest` stay there; Step 6 runs them.
- If L8 shows `difficulty` is a `String`, drop the `Difficulty.valueOf` and pass the normalized upper-case string.
- The mapper never sets `contestId`, `sourceQuestionId`, or test cases.

**Checkpoint 5:** `QuestionImportValidatorTest` and `QuestionImportMapperTest` green (§7.1).

---

### Step 6 — Row writer and orchestrator

**6.1 `QuestionImportRowWriter.java`** — its own bean so the transaction proxy applies.

```java
@Component
public class QuestionImportRowWriter {

    private final QuestionService questionService;

    public QuestionImportRowWriter(QuestionService questionService) {
        this.questionService = questionService;
    }

    /** One row = one transaction. A failure here rolls back only this row. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(CreateQuestionRequest request) {
        questionService.createLibraryQuestion(request);    // LOCK L7: the exact method POST /api/library/questions calls
    }
}
```

> If the real method takes `(CreateQuestionRequest, UUID subjectId)` or similar, pass what it needs; the writer is the only place that knows.
> `REQUIRES_NEW` also protects you if `spring.jpa.open-in-view` is `true` — a failed row will not leave a broken Hibernate session behind for later rows.

**6.2 `QuestionImportService.java`**

```java
@Service
public class QuestionImportService {

    private static final Logger log = LoggerFactory.getLogger(QuestionImportService.class);

    private final SubjectRepository subjectRepository;
    private final QuestionImportParser parser;
    private final QuestionImportValidator validator;
    private final QuestionImportMapper mapper;
    private final BulkImportService bulkImportService;
    private final QuestionImportRowWriter rowWriter;
    private final jakarta.validation.Validator beanValidator;
    private final AuditService auditService;

    // constructor injection of all of the above

    // Deliberately NOT @Transactional: each row commits on its own (D3).
    public QuestionImportResponse process(ImportQuestionsRequest request, boolean dryRun) {
        subjectRepository.findById(request.subjectId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUBJECT_NOT_FOUND,
                        "Subject folder not found"));                      // adapt ctor to L4

        List<ParsedRow> parsed = parser.parse(request.payload());
        QuestionType type = request.type();

        BulkRun<CreateQuestionRequest> run = bulkImportService.run(
                parsed,
                row -> toRequest(type, request.subjectId(), row),          // parse stage (+ item rules)
                this::beanValidate,                                        // validate stage
                dryRun ? null : rowWriter::write);                         // write stage

        List<ImportRowPreview> previews = buildPreviews(type, parsed, run);
        BulkImportResult result = toBulkImportResult(run);                  // adapt to L2

        if (!dryRun) {
            auditService.log(/* action */ "LIBRARY_QUESTIONS_IMPORTED", /* target */ request.subjectId(),
                    "type=" + type + ", imported=" + run.writtenCount() + ", failed=" + run.invalidCount()); // adapt to L13
        }
        log.info("question-import subject={} type={} dryRun={} total={} valid={} invalid={} written={}",
                request.subjectId(), type, dryRun, run.total(), run.validCount(), run.invalidCount(), run.writtenCount());
        // never log the payload: it can be large and contains answer keys

        return new QuestionImportResponse(dryRun, result, previews);
    }

    private CreateQuestionRequest toRequest(QuestionType type, UUID subjectId, ParsedRow row) {
        if (row.parseError() != null) {
            throw new RowRejectedException(row.parseError());
        }
        List<String> problems = validator.validateItem(type, row.item());
        if (!problems.isEmpty()) {
            throw new RowRejectedException(problems);
        }
        return mapper.toRequest(type, subjectId, row.item());
    }

    private List<String> beanValidate(CreateQuestionRequest req) {
        return beanValidator.validate(req).stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .toList();
    }

    private List<ImportRowPreview> buildPreviews(QuestionType type, List<ParsedRow> parsed, BulkRun<CreateQuestionRequest> run) {
        List<ImportRowPreview> out = new ArrayList<>(parsed.size());
        for (int i = 0; i < parsed.size(); i++) {
            ParsedRow row = parsed.get(i);
            RowOutcome<CreateQuestionRequest> o = run.rows().get(i);     // same order, same length
            ImportQuestionItem it = row.item();
            out.add(new ImportRowPreview(
                    o.rowNumber(),
                    o.valid(),
                    it == null ? null : it.title(),
                    it == null ? null : it.difficulty(),
                    it == null ? null : it.points(),
                    it == null ? "" : mapper.descriptionPreview(it.description()),
                    it == null ? "" : mapper.detail(type, it),
                    o.errors()));
        }
        return out;
    }

    private BulkImportResult toBulkImportResult(BulkRun<CreateQuestionRequest> run) {
        // Build with the EXISTING BulkImportResult API (L2). Mapping:
        //   total      = run.total()
        //   success    = dryRun ? run.validCount() : run.writtenCount()
        //   failure    = run.total() - success
        //   errors[]   = one entry per outcome with errors: (rowNumber, String.join("; ", errors))
        // Do NOT change BulkImportResult's shape; if it lacks something, extend it additively.
        throw new UnsupportedOperationException("wire to BulkImportResult (L2)");
    }
}
```

> `toBulkImportResult` is the **one deliberate stub** — it is intentionally left for you to wire to the real `BulkImportResult` once L2 is known. Remove the `throw` as part of this step; the build must not ship with it.
> In the failure count for a real import use `total - writtenCount`, so a row that validated but failed to save is a failure, as stated in §2.2.

**Checkpoint 6:** `QuestionImportServiceTest` (Mockito) green: dry run never calls `rowWriter`; real import calls it only for valid rows; audit is called only for real imports; unknown subject → `ResourceNotFoundException` before parsing.

---

### Step 7 — Prompt templates and `PromptTemplateService`

**7.1 Files** (full text in §6). Location: `src/main/resources/prompts/` — `dsa.txt`, `sql.txt`, `mcq.txt`, `theory.txt` (lower-case enum name + `.txt`).

Add `.gitattributes` (repo root) so Windows checkouts do not inject CRLF:

```
*.txt text eol=lf
```

**7.2 `PromptTemplateService.java`**

```java
@Service
public class PromptTemplateService {

    private final Map<QuestionType, String> templates = new EnumMap<>(QuestionType.class);
    private final ImportProperties props;

    public PromptTemplateService(ImportProperties props) { this.props = props; }

    @PostConstruct
    void load() {
        for (QuestionType type : QuestionType.values()) {
            String path = "prompts/" + type.name().toLowerCase(Locale.ROOT) + ".txt";
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                throw new IllegalStateException("Missing prompt template: " + path);
            }
            String text;
            try (InputStream in = resource.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read prompt template: " + path, e);
            }
            // Drift guard (D8): every field the parser understands must be named in the prompt.
            for (String field : ImportFieldSpec.fieldsFor(type)) {
                if (!text.contains("\"" + field + "\"")) {
                    throw new IllegalStateException("Prompt " + path + " does not mention field \"" + field + "\"");
                }
            }
            templates.put(type, text);
        }
    }

    public ImportTemplateResponse get(QuestionType type) {
        return new ImportTemplateResponse(type, templates.get(type), props.maxQuestions(), props.maxPayloadBytes());
    }
}
```

Important: these backend templates contain **no** `{title}`-style placeholders (those exist only in the frontend Test Case Prompt strings), so no string substitution happens here.

**Checkpoint 7:** app starts; deleting a field name from a template makes startup fail (prove once, restore).

---

### Step 8 — Controller and security

**8.1 `LibraryImportController.java`**

```java
@RestController
@RequestMapping("/api/library")
@PreAuthorize("hasAnyRole('ADMIN','EVALUATOR')")
public class LibraryImportController {

    private final PromptTemplateService promptTemplateService;
    private final QuestionImportService questionImportService;

    // constructor injection

    @GetMapping("/import-template")
    public ApiResponse<ImportTemplateResponse> template(@RequestParam QuestionType type) {
        return ApiResponse.success(promptTemplateService.get(type), "OK");              // factory per L3
    }

    @PostMapping("/questions/import")
    public ApiResponse<QuestionImportResponse> importQuestions(
            @Valid @RequestBody ImportQuestionsRequest body,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        QuestionImportResponse data = questionImportService.process(body, dryRun);
        return ApiResponse.success(data, dryRun ? "Preview generated" : "Import finished");
    }
}
```

- Path check: `POST /api/library/questions/import` is a literal path and does not clash with `POST /api/library/questions` or `PUT|DELETE /api/library/questions/{id}`. Confirm with the Spring startup mapping log.
- The class-level `@PreAuthorize` mirrors Module 5A's `LibraryController`. Candidates get 403 on both endpoints.
- Object-level authorization: folders are global in Module 5A, so any Evaluator/Admin may import into any folder; the only object check is that the subject exists. Authorship is the importing user (D10).

**8.2 `SecurityConfig`** — only if L12 shows `/api/library/**` is not covered. Do not add a new `permitAll`. The default must remain "deny everything else".

**Checkpoint 8:** Swagger shows both endpoints with `ApiResponse` envelopes; MockMvc 401/403/200 smoke tests green.

---

### Step 9 — Integration tests with a real database

Run these in the same style as existing `*IT` tests (L15). Use fixtures in `src/test/resources/import/`.

| Test | Setup | Assertions |
|---|---|---|
| `mixed_20_rows_reports_bad_rows_by_number` | MCQ batch: 14 valid + 6 invalid (bad `correct`, blank option, missing title, bad difficulty, non-object row, points 0), `dryRun=false` | `successCount=14`, `failureCount=6`, each failing row number equals its position, library has exactly 14 new questions |
| `dry_run_persists_nothing` | Same payload, `dryRun=true` | `questions` count unchanged; response shows 14 valid |
| `default_is_dry_run` | Omit the param | Nothing persisted |
| `dsa_import_has_no_test_cases` | DSA batch of 3, real import | 3 questions, `contest_id IS NULL`, `subject_id` set, `source_question_id IS NULL`, **0 rows** in `test_cases` for them; list DTO exposes the "no test cases" flag/count |
| `sql_import_stores_schema_and_order_flag` | SQL batch | `schema_sql`, `order_matters` stored; 0 test cases |
| `mcq_import_keeps_options_exactly` | Options `["A","B","C","D"]`, `correct:[2,4]` | 4 `mcq_options` rows, text/order preserved, `is_correct` true for B and D only, `order_index` per L10 |
| `theory_import_model_answer_optional` | One row with, one without `modelAnswer` | Both saved; second has null |
| `author_is_importer` | Evaluator B imports | `created_by` = B; library list shows B's name (5A) |
| `candidate_gets_403` | Candidate JWT on both endpoints | 403 each; nothing persisted |
| `anonymous_gets_401` | No token | 401 |
| `unknown_subject_404` | Random UUID | 404 `SUBJECT_NOT_FOUND`, parser never reached |
| `invalid_type_400` | `type=FOO` / `?type=FOO` | 400, not 500 |
| `malformed_json_400` | `"[{"` | 400 `IMPORT_PAYLOAD_INVALID` with line/column text |
| `over_200_400` | 201 tiny items | 400 `IMPORT_LIMIT_EXCEEDED`, nothing persisted |
| `over_1mb_413` | > 1,048,576 UTF-8 bytes | 413 `IMPORT_PAYLOAD_TOO_LARGE` |
| `one_failing_save_does_not_poison_batch` | Force row 2's save to fail (e.g. a title longer than the column when no `@Size` guard exists, or a spy on the writer) | Row 2 reported, rows 1 and 3 committed |
| `import_is_audited` | Real import | Audit entry exists; dry run creates none |

Example skeleton (adapt helpers to L15):

```java
@Test
void dsa_import_has_no_test_cases() throws Exception {
    String payload = Files.readString(Path.of("src/test/resources/import/dsa-3.json"));
    mockMvc.perform(post("/api/library/questions/import?dryRun=false")
                    .header("Authorization", "Bearer " + evaluatorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("subjectId", subjectId, "type", "DSA", "payload", payload))))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.result.successCount").value(3));   // field name per L2

    List<Question> created = questionRepository.findBySubjectIdAndContestIdIsNull(subjectId);
    assertThat(created).hasSize(3);
    created.forEach(q -> assertThat(testCaseRepository.countByQuestionId(q.getId())).isZero());
}
```

**Real-LLM fixtures (Definition of Done item).** Run the served MCQ prompt in **two** real LLMs (e.g. Claude and Gemini) on the same short question list, save each raw reply untouched as `llm-claude-mcq.json` / `llm-gemini-mcq.json`, and add a test that imports each with `dryRun=true` and asserts **zero row errors and the expected row count without editing the files**. Repeat once for SQL. Keep the files as regression fixtures; if a prompt change breaks them, the prompt is wrong, not the fixture.

**Checkpoint 9:** all of the above green; full suite green.

---

### Step 10 — Verify Module 5 is untouched and the Test Case Prompt output imports

This step adds **no production code**. It proves the roadmap claims "existing DSA importer unchanged" and "no backend change beyond Module 5's type-aware validation".

1. Run all existing Module 5 test-case tests unchanged.
2. Add `TestCasePromptCsvCompatibilityIT`:
   - Create a DSA library question and a SQL library question (via the new import or the existing create).
   - Fixture `dsa-prompt-output.csv`: header exactly `input,expected_output,is_sample,weight`, 8 rows, first 2 `is_sample=true`, multi-line input quoted, an edge case with empty input if the question allows it. `POST /api/questions/{id}/test-cases/bulk` (multipart field per L17) → all 8 accepted.
   - Fixture `sql-prompt-output.csv`: header identical, 6 rows, `input` = `INSERT …;` or blank, `expected_output` = JSON `{"columns":[…],"rows":[[…]]}` with doubled quotes inside CSV cells (copy the example from Module 5). → all 6 accepted.
   - Negative control: an SQL row whose `expected_output` is not valid JSON is reported by row; an upload to an MCQ question returns `TEST_CASES_NOT_APPLICABLE`.
3. If any assertion fails, the defect is in Module 5's type-aware validation (`DsaRowValidator` / `SqlRowValidator`) — fix it **there**, not in 5B.
4. Best fixtures: use real LLM output produced with the frontend Test Case Prompt, saved untouched (same rule as Step 9).

**Checkpoint 10:** green.

---

### Step 11 — (Optional, last) Make `CsvImportService` use `BulkImportService`

The roadmap says "generalize `CsvImportService`". Everything above already works without touching it. Only do this if you want one shared loop, and only in this order:

1. Write **characterization tests** for the current user CSV import and test-case CSV import (inputs → exact `BulkImportResult` including counts, row numbers and message text). Run them green **before** editing.
2. Change only the **internals** of `CsvImportService` so its loop delegates to `BulkImportService.run(...)`. Its public method signatures and the `BulkImportResult` it returns must not change.
3. Re-run the characterization tests. Any diff in output → revert the refactor.

If time is short, skip this step and note it as technical debt; nothing in 5B depends on it.

---

### Step 12 — Request-size plumbing (deployment config)

A 1 MB payload becomes larger once JSON-escaped inside the request body, and Nginx's default `client_max_body_size` is 1 MB, which would return a plain Nginx 413 before Spring ever sees the request. In `nginx.conf` (Module 14) set:

```nginx
client_max_body_size 2m;
```

Spring itself has no extra limit for JSON bodies here. Verify with one real request through Nginx using a ~900 KB payload.

---

## 6. Prompt templates (exact file contents)

Each file ends with `TEXT:` and a placeholder line; the teacher pastes their material after copying. Keep the wording stable — the fixtures in Step 9 depend on it.

### 6.1 `prompts/mcq.txt`

```text
Convert the multiple-choice questions in the text below into a JSON array. Return ONLY the JSON (one code block, no commentary).
Each item must look like:
{"title":"Short question title","description":"Full question text in Markdown","difficulty":"EASY","points":5,"options":["Option A text","Option B text","Option C text","Option D text"],"correct":[2]}
Rules:
- title: a short label for the question.
- description: the full question text in Markdown. Escape line breaks as \n inside the JSON string.
- options: all answer choices in their original order, without the "A)", "B)" labels.
- correct: the 1-based numbers of the right option(s), e.g. [2], or [1,3] when several are correct.
- difficulty must be exactly EASY, MEDIUM or HARD.
- points: a positive whole number; use the marks given in the text, otherwise 5.
- Do not invent questions that are not in the text. If an answer is not given, work out the correct option.
TEXT:
<paste your questions here>
```

### 6.2 `prompts/dsa.txt`

```text
Convert the coding problems in the text below into a JSON array. Return ONLY the JSON (one code block, no commentary).
Each item must look like:
{"title":"Problem name","description":"Full problem statement in Markdown","difficulty":"MEDIUM","points":10,"timeLimitMs":2000,"memoryLimitKb":262144}
Rules:
- title: a short problem name.
- description: the full statement in Markdown: story, input format, output format, constraints, and sample input/output. Escape line breaks as \n inside the JSON string.
- difficulty must be exactly EASY, MEDIUM or HARD.
- points: a positive whole number; use the marks given in the text, otherwise 10.
- timeLimitMs (milliseconds) and memoryLimitKb (kilobytes): include them only if the text states limits; otherwise leave both out.
- Do NOT include test cases. Do not invent problems that are not in the text.
TEXT:
<paste your problems here>
```

### 6.3 `prompts/sql.txt`

```text
Convert the SQL questions in the text below into a JSON array. Return ONLY the JSON (one code block, no commentary).
Each item must look like:
{"title":"Question name","description":"Full question in Markdown","difficulty":"EASY","points":10,"schemaSql":"CREATE TABLE emp (id INTEGER, name TEXT, age INTEGER); INSERT INTO emp VALUES (1,'Alice',30);","orderMatters":false}
Rules:
- description: the question in Markdown; describe the tables in words and state exactly what the query must return. Escape line breaks as \n inside the JSON string.
- schemaSql: one SQLite-compatible script that creates every table the question uses (CREATE TABLE ...) and inserts sample rows (INSERT INTO ...). End every statement with a semicolon. Do NOT include the answer query.
- orderMatters: true only if the question requires a specific row order (for example "sorted by ..."); otherwise false.
- difficulty must be exactly EASY, MEDIUM or HARD.
- points: a positive whole number; use the marks given in the text, otherwise 10.
- Do NOT include test cases. Do not invent questions that are not in the text.
TEXT:
<paste your questions here>
```

### 6.4 `prompts/theory.txt`

```text
Convert the theory questions in the text below into a JSON array. Return ONLY the JSON (one code block, no commentary).
Each item must look like:
{"title":"Short question title","description":"Full question text in Markdown","difficulty":"MEDIUM","points":10,"modelAnswer":"Ideal answer or marking scheme"}
Rules:
- description: the full question text in Markdown. Escape line breaks as \n inside the JSON string.
- modelAnswer: include only if the text provides an answer or marking scheme; otherwise leave this field out.
- difficulty must be exactly EASY, MEDIUM or HARD.
- points: a positive whole number; use the marks given in the text, otherwise 10.
- Do not invent questions that are not in the text.
TEXT:
<paste your questions here>
```

> Align the default point values (5 / 10) with the defaults the frontend question forms use (L8/L9) so imported and hand-made questions feel consistent.

---

## 7. Test inventory

### 7.1 Unit tests

**`QuestionImportParserTest`**

| Input | Expected |
|---|---|
| bare `[{"title":"a"}]` | 1 row |
| ```` ```json\n[…]\n``` ```` | rows parsed |
| prose + fenced block + prose | rows parsed |
| two fenced blocks, first is not an array | second block used |
| `{"questions":[…]}` | rows parsed |
| trailing comma `[{"title":"a"},]` | rows parsed |
| raw newline inside a JSON string | rows parsed |
| leading BOM `\uFEFF[…]` | rows parsed |
| `"correct": 2` (not an array) | row item has `[2]` |
| `"points": "5"` | `5` |
| unknown extra field | ignored |
| element `42` in the array | row error "Row is not a JSON object", other rows fine |
| `"options": [{"text":"x"}]` | row error mentioning field `options` |
| blank / `null` | `IMPORT_PAYLOAD_INVALID` |
| `[]` | `IMPORT_PAYLOAD_INVALID` |
| `{"a":1}` | `IMPORT_PAYLOAD_INVALID` ("top-level JSON must be an array") |
| `[{"title":` | `IMPORT_PAYLOAD_INVALID` with line/column |
| 201 items | `IMPORT_LIMIT_EXCEEDED` |
| > max bytes (use multi-byte chars to prove bytes, not chars) | `IMPORT_PAYLOAD_TOO_LARGE` |

**`QuestionImportValidatorTest`** — one test per rule in Step 5.1, plus: several problems in one row are all returned; `difficulty` `"easy"` and `" Hard "` pass; `correct` `[0]`, `[5]` with 4 options, and `[1,1]` fail; `CREATE TABLE` detection is case-insensitive.

**`QuestionImportMapperTest`** — MCQ `options`/`correct` map to `is_correct` by position; DSA defaults applied only when limits are missing; SQL `orderMatters` null → false; THEORY blank `modelAnswer` → null; `contestId` absent; `detail()` strings.

**`PromptTemplateServiceTest`** — all four templates load; each contains `Return ONLY the JSON`; every `ImportFieldSpec` field appears in its template; a template with a missing field makes `load()` throw (use a test resource override).

**`BulkImportServiceTest`** — see Step 2.3.

### 7.2 Integration tests

See Step 9 and Step 10.

---

## 8. Security checklist (verify explicitly, do not assume)

- [ ] Both endpoints return **403** for Candidate and **401** without a token (tested).
- [ ] Neither endpoint is reachable via a `permitAll` matcher.
- [ ] Imported MCQ answer keys are stored only in `mcq_options.is_correct` and never appear in any candidate-facing response (existing Module 4/8A absence tests still green).
- [ ] The payload is never logged; row errors never contain stack traces, SQL, or class names.
- [ ] A hostile payload cannot exceed 1 MB (parser) nor 200 rows; Nginx allows 2 MB so the Spring limit — not a raw Nginx error — is what users see.
- [ ] Descriptions are stored exactly as manual creation stores them (same Markdown handling downstream; no separate sanitization path).
- [ ] Import writes only to `questions` / `mcq_options` through the existing service; it cannot create test cases, contest copies, or touch `contest_id`.
- [ ] Real imports are audited with user, subject, type and counts.

---

## 9. Pitfalls that break this module (read before you code)

| # | Pitfall | Prevention in this plan |
|---|---|---|
| P1 | Declaring an extra `ObjectMapper` `@Bean` disables Spring Boot's auto-configured mapper and changes JSON output app-wide | Parser owns a **private** mapper, no bean (D9) |
| P2 | `@Transactional` on the import method: one failed row marks the whole transaction rollback-only, or the shared Hibernate session becomes unusable | Orchestrator is non-transactional; `REQUIRES_NEW` writer in a **separate bean** (self-invocation would bypass the proxy) |
| P3 | Preview and import using different code paths so "valid" in preview fails on confirm | One loop, `writer == null` is the only difference |
| P4 | Accidental writes when the client forgets `dryRun` | Default is `true` |
| P5 | `1 MB` measured in characters, or Nginx rejecting first | UTF-8 byte check + `client_max_body_size 2m` |
| P6 | Bad enum/type values returning 500 | Step 0 L5 check; handlers added if missing |
| P7 | 0-based vs 1-based confusion for `correct` and `order_index` | `correct` is 1-based (prompt + validator + mapper tested); `order_index` base locked in L10 |
| P8 | LLM output with raw newlines, trailing commas, fences, wrapper object | Tolerant parser features + tests |
| P9 | Prompt text and parser fields drifting apart | `ImportFieldSpec` + startup guard + fixtures from real LLMs |
| P10 | Breaking the existing DSA test-case importer while "generalizing" the CSV service | Optional last step, characterization tests first (Step 11) |
| P11 | Returning raw DB/Hibernate messages to teachers | Generic message for non-`AppException` failures; details only in logs with trace id |
| P12 | Windows CRLF in prompt files showing up in the copied prompt | `.gitattributes` + normalization in `PromptTemplateService` |

---

## 10. Definition of Done (maps 1:1 to the roadmap, backend side)

- [ ] Importing 20 mixed rows creates the valid ones and reports the bad ones by row number (`mixed_20_rows_reports_bad_rows_by_number`)
- [ ] Imported DSA/SQL questions carry no test cases, and the list DTO exposes the "no test cases" flag (`dsa_import_has_no_test_cases`, `sql_import_stores_schema_and_order_flag`)
- [ ] MCQ import keeps options and correct answers exactly (`mcq_import_keeps_options_exactly`)
- [ ] Output from at least two real LLMs, using the served prompt, imports without manual edits (Step 9 fixtures)
- [ ] The existing DSA test-case bulk importer behaves exactly as before (Step 10 + unchanged Module 5 tests)
- [ ] A CSV produced by the DSA prompt and one by the SQL prompt each import without manual edits (Step 10)
- [ ] Preview (`dryRun=true`) stores nothing and is the default (`dry_run_persists_nothing`, `default_is_dry_run`)
- [ ] Candidate gets 403 / anonymous gets 401 on both endpoints
- [ ] Limits enforced: 201 rows → 400, > 1 MB → 413, malformed JSON → 400
- [ ] A failing row never affects other rows (`one_failing_save_does_not_poison_batch`)
- [ ] Full existing test suite is green; no Flyway migration was added
- [ ] Cross-Module Consistency Checklist from the roadmap ticked: `ApiResponse` envelope used, new exception extends `AppException`, role check present, no entity added

---

## 11. Out of scope / future

- CSV or Excel direct question import; import straight into a contest (roadmap "Future Enhancements").
- Duplicate detection (same title in the same folder). Today, confirming the same payload twice creates the questions twice; the frontend should disable the Confirm button after the first click. A non-blocking "possible duplicate" warning in the preview is a cheap later addition.
- Asynchronous import for batches beyond 200 rows.
- Maximum option count for MCQ (not specified by the roadmap, so not enforced).
