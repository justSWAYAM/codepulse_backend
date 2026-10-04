package com.codepulse_backend.question.controller;

import com.codepulse_backend.common.dto.ApiResponse;
import com.codepulse_backend.common.dto.PagedResponse;
import com.codepulse_backend.common.enums.QuestionType;
import com.codepulse_backend.question.dto.*;
import com.codepulse_backend.question.service.QuestionLibraryService;
import com.codepulse_backend.question.service.SubjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for Module 5A – Question Library.
 *
 * Base URL: /api/library
 *
 * All endpoints are restricted to ADMIN and EVALUATOR roles via the class-level
 * {@code @PreAuthorize}. Candidates receive 403 from the security filter chain
 * before any business logic runs.
 */
@RestController
@RequestMapping("/api/library")
@PreAuthorize("hasAnyRole('ADMIN', 'EVALUATOR')")
@RequiredArgsConstructor
public class LibraryController {

    private final SubjectService subjectService;
    private final QuestionLibraryService libraryService;

    // ─── Subjects ─────────────────────────────────────────────────────────────

    /**
     * GET /api/library/subjects
     * Returns all subject folders (global, shared list).
     */
    @GetMapping("/subjects")
    public ApiResponse<List<SubjectResponse>> listSubjects() {
        return new ApiResponse<>(true,
                subjectService.getAllSubjects(),
                "Subjects retrieved successfully",
                Instant.now(), null);
    }

    /**
     * POST /api/library/subjects
     * Creates a new subject folder. Name must be globally unique (case-insensitive).
     */
    @PostMapping("/subjects")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SubjectResponse> createSubject(
            @Valid @RequestBody CreateSubjectRequest request) {
        return new ApiResponse<>(true,
                subjectService.createSubject(request),
                "Subject created successfully",
                Instant.now(), null);
    }

    // ─── Library Questions ────────────────────────────────────────────────────

    /**
     * GET /api/library/questions?subjectId=&amp;type=&amp;page=&amp;size=&amp;sort=
     * Paged browse of all library questions with optional filters.
     * Defaults: page 0, size 20, sorted by createdAt DESC.
     */
    @GetMapping("/questions")
    public ApiResponse<PagedResponse<LibraryQuestionResponse>> listLibraryQuestions(
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(required = false) QuestionType type,
            @PageableDefault(size = 20, sort = "createdAt",
                    direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable) {
        return new ApiResponse<>(true,
                libraryService.getLibraryQuestions(subjectId, type, pageable),
                "Library questions retrieved successfully",
                Instant.now(), null);
    }

    /**
     * POST /api/library/questions
     * Creates a library question (contest_id = NULL) inside a subject folder.
     * MCQ options can be included in the body; test cases are added separately
     * via POST /api/questions/{id}/test-cases.
     */
    @PostMapping("/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LibraryQuestionResponse> createLibraryQuestion(
            @Valid @RequestBody CreateLibraryQuestionRequest request) {
        return new ApiResponse<>(true,
                libraryService.createLibraryQuestion(request),
                "Library question created successfully",
                Instant.now(), null);
    }

    /**
     * PUT /api/library/questions/{id}
     * Patch-semantics update (only non-null fields applied).
     * Restricted to the question's author or an Admin.
     * Existing contest copies are NOT affected.
     */
    @PutMapping("/questions/{id}")
    public ApiResponse<LibraryQuestionResponse> updateLibraryQuestion(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateLibraryQuestionRequest request) {
        return new ApiResponse<>(true,
                libraryService.updateLibraryQuestion(id, request),
                "Library question updated successfully",
                Instant.now(), null);
    }

    /**
     * DELETE /api/library/questions/{id}
     * Deletes the library question. source_question_id ON DELETE SET NULL
     * means all contest copies survive with their data intact.
     * Restricted to the question's author or an Admin.
     */
    @DeleteMapping("/questions/{id}")
    public ApiResponse<Void> deleteLibraryQuestion(@PathVariable UUID id) {
        libraryService.deleteLibraryQuestion(id);
        return new ApiResponse<>(true, null,
                "Library question deleted successfully",
                Instant.now(), null);
    }
}
