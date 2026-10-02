package com.codepulse_backend.common.util;

import com.codepulse_backend.common.dto.CsvImportResult;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvImportServiceTest {

    private final CsvImportService service = new CsvImportService();

    private CsvImportResult importInputs(String csv, List<String> seen) {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cases.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        return service.processGeneric(
                file,
                record -> record.get("input") + "->" + record.get("expected_output"),
                row -> {
                    seen.add(row);
                    return null;
                });
    }

    @Test
    void headersMatchRegardlessOfCaseAndSurroundingSpaces() {
        List<String> seen = new ArrayList<>();

        // What a spreadsheet typically exports; the upload preview already accepts it
        CsvImportResult result = importInputs(
                "Input, Expected_Output ,Is_Sample,WEIGHT\nabc,cba,false,30\nxyz,zyx,true,10\n", seen);

        assertEquals(2, result.succeededCount());
        assertEquals(0, result.failedCount());
        assertEquals(List.of("abc->cba", "xyz->zyx"), seen);
    }

    @Test
    void lowercaseHeadersStillWork() {
        List<String> seen = new ArrayList<>();

        CsvImportResult result = importInputs("input,expected_output,is_sample,weight\n1 2,3,true,10\n", seen);

        assertEquals(1, result.succeededCount());
        assertEquals(List.of("1 2->3"), seen);
    }
}
