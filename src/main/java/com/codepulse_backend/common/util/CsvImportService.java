package com.codepulse_backend.common.util;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class CsvImportService {

    public record ImportResult<T>(List<T> succeeded, List<String> errors) {}

    public static <T> ImportResult<T> parse(InputStream inputStream, Function<CSVRecord, T> mapper) {
        List<T> succeeded = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try (CSVParser parser = CSVParser.parse(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {

            for (CSVRecord record : parser) {
                try {
                    succeeded.add(mapper.apply(record));
                } catch (Exception e) {
                    errors.add("Row " + record.getRecordNumber() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            errors.add("Failed to parse file: " + e.getMessage());
        }
        return new ImportResult<>(succeeded, errors);
    }
}
