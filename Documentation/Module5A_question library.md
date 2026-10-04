Here is the detailed, file-by-file backend implementation plan for **Module 5A (Question Library)**, designed for your **Spring Boot 3 (Java 21) + PostgreSQL** backend stack.

---

## 1. Database Schema & Migration (`V2__question_library.sql`)

**Location:** `src/main/resources/db/migration/V2__question_library.sql`

Add the `subjects` table and update the existing `questions` table to support library storage and deep-copy referencing.

```sql
CREATE TABLE subjects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) UNIQUE NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Alter existing questions table to support library questions and tracking copies
ALTER TABLE questions 
    ALTER COLUMN contest_id DROP NOT NULL,
    ADD COLUMN subject_id UUID REFERENCES subjects(id) ON DELETE SET NULL,
    ADD COLUMN source_question_id UUID REFERENCES questions(id) ON DELETE SET NULL;

CREATE INDEX idx_questions_library ON questions(contest_id) WHERE contest_id IS NULL;
CREATE INDEX idx_questions_subject ON questions(subject_id);

```

---

## 2. Entities (`com.codepulse.domain`)

### `Subject.java`

**Location:** `src/main/java/com/codepulse/domain/Subject.java`

* Extends `BaseEntity` (`id`, `createdAt`, `updatedAt`, etc.).

```java
@Entity
@Table(name = "subjects")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Subject extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
}

```

### `Question.java` (Updates)

**Location:** `src/main/java/com/codepulse/domain/Question.java`

* Add fields for library referencing:

```java
@Column(name = "contest_id")
private UUID contestId; // Nullable: NULL = library question

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "subject_id")
private Subject subject;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "source_question_id")
private Question sourceQuestion;

```

---

## 3. Repositories (`com.codepulse.repository`)

### `SubjectRepository.java`

**Location:** `src/main/java/com/codepulse/repository/SubjectRepository.java`

```java
@Repository
public interface SubjectRepository extends JpaRepository<Subject, UUID> {
    boolean existsByNameIgnoreCase(String name);
}

```

### `QuestionRepository.java` (Library Queries)

**Location:** `src/main/java/com/codepulse/repository/QuestionRepository.java`
Add query methods to fetch library questions filtered by subject and type:

```java
@Query("SELECT q FROM Question q WHERE q.contestId IS NULL " +
       "AND (:subjectId IS NULL OR q.subject.id = :subjectId) " +
       "AND (:type IS NULL OR q.questionType = :type)")
Page<Question> findLibraryQuestions(@Param("subjectId") UUID subjectId, 
                                    @Param("type") QuestionType type, 
                                    Pageable pageable);

List<Question> findByContestId(UUID contestId);

```

---

## 4. DTOs (`com.codepulse.dto`)

### `SubjectResponse.java`

**Location:** `src/main/java/com/codepulse/dto/SubjectResponse.java`

```java
public record SubjectResponse(
    UUID id,
    String name,
    UUID createdBy,
    Instant createdAt
) {}

```

### `LibraryQuestionResponse.java`

**Location:** `src/main/java/com/codepulse/dto/LibraryQuestionResponse.java`

```java
public record LibraryQuestionResponse(
    UUID id,
    String title,
    QuestionType questionType,
    String difficulty,
    Integer points,
    UUID subjectId,
    String subjectName,
    String authorName,
    Instant createdAt
) {}

```

### `AddFromLibraryRequest.java`

**Location:** `src/main/java/com/codepulse/dto/AddFromLibraryRequest.java`

```java
public record AddFromLibraryRequest(
    @NotEmpty(message = "Question IDs cannot be empty")
    List<UUID> questionIds
) {}

```

---

## 5. Services (`com.codepulse.service`)

### `SubjectService.java`

**Location:** `src/main/java/com/codepulse/service/SubjectService.java`

* Handles subject folder creation and listing. Validates unique folder names case-insensitively.

### `QuestionLibraryService.java`

**Location:** `src/main/java/com/codepulse/service/QuestionLibraryService.java`

* Implements the **deep-copy transaction** when adding library questions to a contest.

```java
@Service
@RequiredArgsConstructor
public class QuestionLibraryService {

    private final QuestionRepository questionRepository;
    private final TestCaseRepository testCaseRepository;
    private final McqOptionRepository mcqOptionRepository;
    private final ContestRepository contestRepository;

    @Transactional
    public void addQuestionsToContest(UUID contestId, List<UUID> questionIds) {
        Contest contest = contestRepository.findById(contestId)
            .orElseThrow(() -> new ResourceNotFoundException("Contest not found"));

        List<Question> sourceQuestions = questionRepository.findAllById(questionIds);

        for (Question source : sourceQuestions) {
            // Prevent duplicate copies in the same contest
            boolean alreadyCopied = questionRepository.existsByContestIdAndSourceQuestionId(contestId, source.getId());
            if (alreadyCopied) continue;

            // Deep Copy Question
            Question copy = Question.builder()
                .contestId(contestId)
                .subject(source.getSubject())
                .sourceQuestion(source)
                .title(source.getTitle())
                .description(source.getDescription())
                .difficulty(source.getDifficulty())
                .points(source.getPoints())
                .timeLimitMs(source.getTimeLimitMs())
                .memoryLimitKb(source.getMemoryLimitKb())
                .questionType(source.getQuestionType())
                .schemaSql(source.getSchemaSql())
                .orderMatters(source.getOrderMatters())
                .modelAnswer(source.getModelAnswer())
                .build();
            
            questionRepository.save(copy);

            // Deep Copy Test Cases (if DSA/SQL)
            List<TestCase> testCases = testCaseRepository.findByQuestionId(source.getId());
            for (TestCase tc : testCases) {
                TestCase tcCopy = TestCase.builder()
                    .question(copy)
                    .input(tc.getInput())
                    .expectedOutput(tc.getExpectedOutput())
                    .isSample(tc.getIsSample())
                    .weight(tc.getWeight())
                    .orderIndex(tc.getOrderIndex())
                    .build();
                testCaseRepository.save(tcCopy);
            }

            // Deep Copy MCQ Options (if MCQ)
            List<McqOption> options = mcqOptionRepository.findByQuestionId(source.getId());
            for (McqOption opt : options) {
                McqOption optCopy = McqOption.builder()
                    .question(copy)
                    .text(opt.getText())
                    .isCorrect(opt.getIsCorrect())
                    .orderIndex(opt.getOrderIndex())
                    .build();
                mcqOptionRepository.save(optCopy);
            }
        }
    }
}

```

---

## 6. Controllers & Security (`com.codepulse.controller`)

### `LibraryController.java`

**Location:** `src/main/java/com/codepulse/controller/LibraryController.java`

* Whole controller restricted via `@PreAuthorize("hasAnyRole('ADMIN', 'EVALUATOR')")`.

```java
@RestController
@RequestMapping("/api/library")
@PreAuthorize("hasAnyRole('ADMIN', 'EVALUATOR')")
@RequiredArgsConstructor
public class LibraryController {

    private final SubjectService subjectService;
    private final QuestionLibraryService libraryService;

    @GetMapping("/subjects")
    public ApiResponse<List<SubjectResponse>> listSubjects() {
        return ApiResponse.success(subjectService.getAllSubjects());
    }

    @PostMapping("/subjects")
    public ApiResponse<SubjectResponse> createSubject(@Valid @RequestBody CreateSubjectRequest request) {
        return ApiResponse.success(subjectService.createSubject(request));
    }

    @GetMapping("/questions")
    public ApiResponse<PagedResponse<LibraryQuestionResponse>> listLibraryQuestions(
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) QuestionType type,
            Pageable pageable) {
        return ApiResponse.success(libraryService.getLibraryQuestions(subjectId, type, pageable));
    }
}

```

### Contest Integration Endpoint

Add to your existing `ContestController` or a dedicated sub-controller:

```java
@PostMapping("/api/contests/{contestId}/questions/from-library")
@PreAuthorize("hasRole('ADMIN') or @contestSecurity.isEvaluatorForContest(#contestId, principal)")
public ApiResponse<Void> addFromLibrary(
        @PathVariable UUID contestId,
        @Valid @RequestBody AddFromLibraryRequest request) {
    libraryService.addQuestionsToContest(contestId, request.getquestionIds());
    return ApiResponse.success(null, "Questions successfully added to contest");
}

```

---

## 7. Security Enforcement Checklist

* **Role Check:** `@PreAuthorize("hasAnyRole('ADMIN', 'EVALUATOR')")` on all `/api/library/**` routes.
* **Candidate Lockout:** Candidates attempting to hit any library endpoint automatically receive a `403 Forbidden` response via Spring Security filter chains.
* **Isolation Guarantee:** Editing or deleting a library question after it has been copied into a contest has zero effect on active or running exams due to the independent deep-copy record stored with `contest_id = contestId`.