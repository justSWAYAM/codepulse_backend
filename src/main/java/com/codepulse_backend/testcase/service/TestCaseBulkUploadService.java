package com.codepulse_backend.testcase.service;

import com.codepulse_backend.common.dto.CsvImportResult;
import com.codepulse_backend.common.exception.ResourceNotFoundException;
import com.codepulse_backend.common.util.CsvImportService;
import com.codepulse_backend.question.repository.QuestionRepository;
import com.codepulse_backend.testcase.dto.CreateTestCaseRequest;
import com.codepulse_backend.testcase.dto.TestCaseBulkUploadResult;
import com.codepulse_backend.testcase.dto.TestCaseCsvRow;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TestCaseBulkUploadService {

    private final CsvImportService csvImportService;
    private final QuestionRepository questionRepository;
    private final TestCaseService testCaseService;

    public TestCaseBulkUploadResult uploadTestCases(
            UUID questionId,
            MultipartFile file
    ) {
        if (!questionRepository.existsById(questionId)) {
            throw new ResourceNotFoundException(
                    "Question not found with id: " + questionId
            );
        }

        CsvImportResult result = csvImportService.processGeneric(
                file,
                this::mapCsvRow,
                row -> saveRow(questionId, row)
        );

        return new TestCaseBulkUploadResult(
                result.totalRows(),
                result.succeededCount(),
                result.failedCount(),
                result.errors()
        );
    }

    private TestCaseCsvRow mapCsvRow(CSVRecord record) {
        String input = record.get("input").trim();
        String expectedOutput = record.get("expected_output").trim();

        String sampleValue = record.get("is_sample")
                .trim()
                .toLowerCase(Locale.ROOT);

        if (!sampleValue.equals("true") && !sampleValue.equals("false")) {
            throw new IllegalArgumentException(
                    "is_sample must be true or false"
            );
        }

        int weight;
        try {
            weight = Integer.parseInt(record.get("weight").trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "weight must be a valid integer"
            );
        }

        return new TestCaseCsvRow(
                input,
                expectedOutput,
                Boolean.parseBoolean(sampleValue),
                weight
        );
    }

    private String saveRow(UUID questionId, TestCaseCsvRow row) {
        if (row.input() == null || row.input().isBlank()) {
            return "Input is required";
        }

        if (row.expectedOutput() == null || row.expectedOutput().isBlank()) {
            return "Expected output is required";
        }

        if (row.weight() < 0) {
            return "Weight cannot be negative";
        }

        testCaseService.createTestCase(
                questionId,
                new CreateTestCaseRequest(
                        row.input(),
                        row.expectedOutput(),
                        row.isSample(),
                        row.weight()
                )
        );

        return null;
    }
}