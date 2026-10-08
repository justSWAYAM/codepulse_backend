package com.codepulse_backend.common.bulkimport;

import com.codepulse_backend.common.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BulkImportServiceTest {

    private BulkImportService service;

    @BeforeEach
    void setUp() {
        service = new BulkImportService();
    }

    @Test
    @DisplayName("3 rows, row 2 throws RowRejectedException('bad'): row 2 has error 'bad', rows 1 and 3 valid")
    void rowRejectedExceptionRecordedForSpecificRow() {
        List<String> rawRows = List.of("row1", "row2", "row3");

        BulkRun<String> run = service.run(
                rawRows,
                raw -> {
                    if ("row2".equals(raw)) {
                        throw new RowRejectedException("bad");
                    }
                    return raw.toUpperCase();
                },
                parsed -> List.of(),
                parsed -> {}
        );

        assertThat(run.total()).isEqualTo(3);
        assertThat(run.validCount()).isEqualTo(2);
        assertThat(run.invalidCount()).isEqualTo(1);
        assertThat(run.writtenCount()).isEqualTo(2);

        assertThat(run.rows().get(0).rowNumber()).isEqualTo(1);
        assertThat(run.rows().get(0).valid()).isTrue();
        assertThat(run.rows().get(0).errors()).isEmpty();
        assertThat(run.rows().get(0).written()).isTrue();

        assertThat(run.rows().get(1).rowNumber()).isEqualTo(2);
        assertThat(run.rows().get(1).valid()).isFalse();
        assertThat(run.rows().get(1).errors()).containsExactly("bad");
        assertThat(run.rows().get(1).written()).isFalse();

        assertThat(run.rows().get(2).rowNumber()).isEqualTo(3);
        assertThat(run.rows().get(2).valid()).isTrue();
        assertThat(run.rows().get(2).errors()).isEmpty();
        assertThat(run.rows().get(2).written()).isTrue();
    }

    @Test
    @DisplayName("writer == null (dry run): writer is never invoked and writtenCount == 0")
    void dryRunWithNullWriterNeverWrites() {
        List<String> rawRows = List.of("r1", "r2");

        BulkRun<String> run = service.run(
                rawRows,
                raw -> raw,
                parsed -> List.of(),
                null
        );

        assertThat(run.total()).isEqualTo(2);
        assertThat(run.validCount()).isEqualTo(2);
        assertThat(run.writtenCount()).isEqualTo(0);
        assertThat(run.rows().get(0).written()).isFalse();
        assertThat(run.rows().get(1).written()).isFalse();
    }

    @Test
    @DisplayName("writer throws AppException on row 2: row 2 has exception message and written=false; row 3 still written")
    void writerThrowsAppExceptionRecordedAndContinues() {
        List<String> rawRows = List.of("r1", "r2", "r3");

        BulkRun<String> run = service.run(
                rawRows,
                raw -> raw,
                parsed -> List.of(),
                parsed -> {
                    if ("r2".equals(parsed)) {
                        throw new BadRequestException("Duplicate title in subject");
                    }
                }
        );

        assertThat(run.total()).isEqualTo(3);
        assertThat(run.writtenCount()).isEqualTo(2);

        assertThat(run.rows().get(0).written()).isTrue();
        assertThat(run.rows().get(0).errors()).isEmpty();

        assertThat(run.rows().get(1).written()).isFalse();
        assertThat(run.rows().get(1).errors()).containsExactly("Duplicate title in subject");

        assertThat(run.rows().get(2).written()).isTrue();
        assertThat(run.rows().get(2).errors()).isEmpty();
    }

    @Test
    @DisplayName("writer throws IllegalStateException: row error is generic internal error, not raw exception message")
    void writerThrowsRuntimeExceptionGivesGenericError() {
        List<String> rawRows = List.of("r1");

        BulkRun<String> run = service.run(
                rawRows,
                raw -> raw,
                parsed -> List.of(),
                parsed -> {
                    throw new IllegalStateException("DB connection dropped: sensitive credentials");
                }
        );

        assertThat(run.writtenCount()).isEqualTo(0);
        assertThat(run.rows().get(0).written()).isFalse();
        assertThat(run.rows().get(0).errors()).containsExactly("Row could not be saved (internal error)");
    }

    @Test
    @DisplayName("validator returns 2 messages: both appear in errors")
    void validatorMultipleMessagesRecorded() {
        List<String> rawRows = List.of("r1");

        BulkRun<String> run = service.run(
                rawRows,
                raw -> raw,
                parsed -> List.of("title is required", "points must be greater than 0"),
                null
        );

        assertThat(run.validCount()).isEqualTo(0);
        assertThat(run.rows().get(0).valid()).isFalse();
        assertThat(run.rows().get(0).errors()).containsExactly("title is required", "points must be greater than 0");
    }
}
