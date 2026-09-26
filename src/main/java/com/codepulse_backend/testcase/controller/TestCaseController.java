package com.codepulse_backend.testcase.controller;

import com.codepulse_backend.common.dto.ApiResponse;
import com.codepulse_backend.testcase.dto.CreateTestCaseRequest;
import com.codepulse_backend.testcase.dto.TestCaseAdminResponse;
import com.codepulse_backend.testcase.dto.TestCaseBulkUploadResult;
import com.codepulse_backend.testcase.service.TestCaseBulkUploadService;
import com.codepulse_backend.testcase.service.TestCaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/questions/{questionId}/test-cases")
@RequiredArgsConstructor
public class TestCaseController {

    private final TestCaseService testCaseService;
    private final TestCaseBulkUploadService testCaseBulkUploadService;

    @GetMapping
    public ApiResponse<Object> getTestCases(@PathVariable UUID questionId) {
        return new ApiResponse<>(
                true,
                testCaseService.getTestCasesForQuestion(questionId),
                "Test cases retrieved successfully",
                Instant.now(),
                null
        );
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<TestCaseAdminResponse> createTestCase(
            @PathVariable UUID questionId,
            @Valid @RequestBody CreateTestCaseRequest request
    ) {
        return new ApiResponse<>(
                true,
                testCaseService.createTestCase(questionId, request),
                "Test case created successfully",
                Instant.now(),
                null
        );
    }

    @PostMapping(value = "/bulk",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<TestCaseBulkUploadResult> bulkUpload(
            @PathVariable UUID questionId,
            @RequestParam("file") MultipartFile file
    ) {
        return new ApiResponse<>(
                true,
                testCaseBulkUploadService.uploadTestCases(questionId, file),
                "Test case CSV upload processed successfully",
                Instant.now(),
                null
        );
    }
}