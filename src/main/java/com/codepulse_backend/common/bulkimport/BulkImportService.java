package com.codepulse_backend.common.bulkimport;

import com.codepulse_backend.common.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class BulkImportService {

    private static final Logger log = LoggerFactory.getLogger(BulkImportService.class);

    /**
     * @param writer null = dry run: nothing is written.
     */
    public <R, T> BulkRun<T> run(List<R> rawRows,
                                 RowParser<R, T> parser,
                                 RowValidator<T> validator,
                                 RowWriter<T> writer) {
        List<RowOutcome<T>> outcomes = new ArrayList<>(rawRows.size());
        int rowNumber = 0;
        for (R raw : rawRows) {
            rowNumber++;
            T parsed = null;
            List<String> errors = new ArrayList<>();
            boolean written = false;

            try {
                parsed = parser.parse(raw);
                errors.addAll(validator.validate(parsed));
            } catch (RowRejectedException e) {
                errors.addAll(e.reasons());
            } catch (RuntimeException e) {
                log.error("Unexpected error reading import row {}", rowNumber, e);
                errors.add("Row could not be read (unexpected format)");
            }

            if (errors.isEmpty() && writer != null) {
                try {
                    writer.write(parsed);
                    written = true;
                } catch (AppException e) {
                    errors.add(e.getMessage());
                } catch (RuntimeException e) {
                    log.error("Unexpected error saving import row {}", rowNumber, e);
                    errors.add("Row could not be saved (internal error)");
                }
            }
            outcomes.add(new RowOutcome<>(rowNumber, parsed, List.copyOf(errors), written));
        }
        return new BulkRun<>(outcomes);
    }
}
