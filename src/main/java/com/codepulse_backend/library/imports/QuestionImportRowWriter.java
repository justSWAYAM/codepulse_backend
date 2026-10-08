package com.codepulse_backend.library.imports;

import com.codepulse_backend.question.dto.CreateLibraryQuestionRequest;
import com.codepulse_backend.question.service.QuestionLibraryService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class QuestionImportRowWriter {

    private final QuestionLibraryService questionLibraryService;

    public QuestionImportRowWriter(QuestionLibraryService questionLibraryService) {
        this.questionLibraryService = questionLibraryService;
    }

    /** One row = one transaction. A failure here rolls back only this row. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(CreateLibraryQuestionRequest request) {
        questionLibraryService.createLibraryQuestion(request);
    }
}
