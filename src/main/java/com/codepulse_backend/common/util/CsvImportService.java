package com.codepulse_backend.common.util;

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
     * Generic CSV processor for bulk imports.
     *
     * @param file         The uploaded CSV file.
     * @param rowMapper    Function to convert a CSVRecord into a typed DTO.
     * @param rowProcessor Function to save the DTO. Returns an error message if it fails, or null if successful.
     * @param <T>          The type of the DTO being processed.
     * @return BulkImportResult summarizing successes and failures.
     */
    public <T> BulkImportResult process(
            MultipartFile file,
            Function<CSVRecord, T> rowMapper,
            Function<T, String> rowProcessor) {

        List<BulkImportResult.RowError> errors = new ArrayList<>();
        int totalRows = 0;
        int succeededCount = 0;
        int failedCount = 0;

        try (InputStream inputStream = file.getInputStream();
             CSVParser parser = CSVParser.parse(
                     new InputStreamReader(inputStream, StandardCharsets.UTF_8),
                     CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {

            for (CSVRecord record : parser) {
                totalRows++;
                int rowNumber = (int) record.getRecordNumber();

                try {
                    // 1. Map row to DTO
                    T mappedObject = rowMapper.apply(record);

                    // 2. Process/Save DTO
                    String errorMessage = rowProcessor.apply(mappedObject);

                    if (errorMessage == null) {
                        succeededCount++;
                    } else {
                        failedCount++;
                        errors.add(new BulkImportResult.RowError(rowNumber, errorMessage));
                    }
                } catch (Exception e) {
                    failedCount++;
                    errors.add(new BulkImportResult.RowError(rowNumber, "Format error: " + e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read CSV file: " + e.getMessage());
        }

        return new BulkImportResult(totalRows, succeededCount, failedCount, errors);
    }
}