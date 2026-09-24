package com.codepulse_backend.question.controller;

import com.codepulse_backend.common.dto.ApiResponse;
import com.codepulse_backend.question.dto.*;
import com.codepulse_backend.question.service.QuestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/contests/{contestId}/questions")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;

    @GetMapping
    public ApiResponse<List<?>> getQuestions(@PathVariable UUID contestId) {
        return new ApiResponse<>(true, questionService.getQuestions(contestId), "Questions retrieved successfully", Instant.now(), null);
    }

    @GetMapping("/{questionId}")
    public ApiResponse<Object> getQuestionDetail(
            @PathVariable UUID contestId,
            @PathVariable UUID questionId) {
        return new ApiResponse<>(true, questionService.getQuestionDetail(contestId, questionId), "Question retrieved successfully", Instant.now(), null);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<QuestionAdminResponse> createQuestion(
            @PathVariable UUID contestId,
            @Valid @RequestBody CreateQuestionRequest request) {
        return new ApiResponse<>(true, questionService.createQuestion(contestId, request), "Question created successfully", Instant.now(), null);
    }

    @PutMapping("/{questionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<QuestionAdminResponse> updateQuestion(
            @PathVariable UUID contestId,
            @PathVariable UUID questionId,
            @Valid @RequestBody UpdateQuestionRequest request) {
        return new ApiResponse<>(true, questionService.updateQuestion(contestId, questionId, request), "Question updated successfully", Instant.now(), null);
    }

    @DeleteMapping("/{questionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteQuestion(
            @PathVariable UUID contestId,
            @PathVariable UUID questionId) {
        questionService.deleteQuestion(contestId, questionId);
        return new ApiResponse<>(true, null, "Question deleted successfully", Instant.now(), null);
    }

    @PatchMapping("/reorder")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<QuestionAdminResponse>> reorderQuestions(
            @PathVariable UUID contestId,
            @Valid @RequestBody ReorderQuestionsRequest request) {
        return new ApiResponse<>(true, questionService.reorderQuestions(contestId, request), "Questions reordered successfully", Instant.now(), null);
    }
}
