package com.codepulse_backend.common.util;

import com.codepulse_backend.common.dto.CsvImportResult;
import com.codepulse_backend.common.dto.RowError;
import com.codepulse_backend.user.dto.BulkImportResult;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Service
public class CsvImportService {

    /**
     * Generic CSV processor with a shared result type.
     */
    public <T> CsvImportResult processGeneric(
            MultipartFile file,
            Function<CSVRecord, T> rowMapper,
            Function<T, String> rowProcessor) {

        List<RowError> errors = new ArrayList<>();
        int totalRows = 0;
        int succeededCount = 0;
        int failedCount = 0;

        try (InputStream inputStream = file.getInputStream();
             CSVParser parser = CSVParser.parse(
                     new InputStreamReader(
                             inputStream,
                             StandardCharsets.UTF_8
                     ),
                     CSVFormat.DEFAULT.builder()
                             .setHeader()
                             .setSkipHeaderRecord(true)
                             .build())) {

            for (CSVRecord record : parser) {
                totalRows++;
                int rowNumber = (int) record.getRecordNumber();

                try {
                    T mappedObject = rowMapper.apply(record);
                    String errorMessage = rowProcessor.apply(mappedObject);

                    if (errorMessage == null) {
                        succeededCount++;
                    } else {
                        failedCount++;
                        errors.add(
                                new RowError(rowNumber, errorMessage)
                        );
                    }
                } catch (Exception e) {
                    failedCount++;
                    errors.add(
                            new RowError(
                                    rowNumber,
                                    "Format error: " + e.getMessage()
                            )
                    );
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to read CSV file: " + e.getMessage(),
                    e
            );
        }

        return new CsvImportResult(
                totalRows,
                succeededCount,
                failedCount,
                errors
        );
    }

    /**
     * Backward-compatible adapter for the existing User Management module.
     */
    public <T> BulkImportResult process(
            MultipartFile file,
            Function<CSVRecord, T> rowMapper,
            Function<T, String> rowProcessor) {

        CsvImportResult result = processGeneric(
                file,
                rowMapper,
                rowProcessor
        );

        List<BulkImportResult.RowError> userErrors =
                result.errors().stream()
                        .map(error ->
                                new BulkImportResult.RowError(
                                        error.rowNumber(),
                                        error.reason()
                                )
                        )
                        .toList();

        return new BulkImportResult(
                result.totalRows(),
                result.succeededCount(),
                result.failedCount(),
                userErrors
        );
    }
}