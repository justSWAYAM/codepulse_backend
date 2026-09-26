package com.codepulse_backend.testcase.controller;

import com.codepulse_backend.common.dto.ApiResponse;
import com.codepulse_backend.testcase.service.TestCaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/test-cases")
@RequiredArgsConstructor
public class TestCaseAdminController {

    private final TestCaseService testCaseService;

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteTestCase(@PathVariable UUID id) {
        testCaseService.deleteTestCase(id);

        return new ApiResponse<>(
                true,
                null,
                "Test case deleted successfully",
                Instant.now(),
                null
        );
    }
}