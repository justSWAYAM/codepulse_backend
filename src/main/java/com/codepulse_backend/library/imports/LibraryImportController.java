package com.codepulse_backend.library.imports;

import com.codepulse_backend.common.dto.ApiResponse;
import com.codepulse_backend.common.enums.QuestionType;
import com.codepulse_backend.library.imports.dto.ImportQuestionsRequest;
import com.codepulse_backend.library.imports.dto.ImportTemplateResponse;
import com.codepulse_backend.library.imports.dto.QuestionImportResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/library")
@PreAuthorize("hasAnyRole('ADMIN','EVALUATOR')")
public class LibraryImportController {

    private final PromptTemplateService promptTemplateService;
    private final QuestionImportService questionImportService;

    public LibraryImportController(PromptTemplateService promptTemplateService,
                                   QuestionImportService questionImportService) {
        this.promptTemplateService = promptTemplateService;
        this.questionImportService = questionImportService;
    }

    @GetMapping("/import-template")
    public ApiResponse<ImportTemplateResponse> template(@RequestParam QuestionType type) {
        return new ApiResponse<>(true, promptTemplateService.get(type), "OK", Instant.now(), null);
    }

    @PostMapping("/questions/import")
    public ApiResponse<QuestionImportResponse> importQuestions(
            @Valid @RequestBody ImportQuestionsRequest body,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        QuestionImportResponse data = questionImportService.process(body, dryRun);
        return new ApiResponse<>(true, data, dryRun ? "Preview generated" : "Import finished", Instant.now(), null);
    }
}
