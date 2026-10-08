package com.codepulse_backend.common.bulkimport;

import java.util.List;

public record RowOutcome<T>(int rowNumber, T parsed, List<String> errors, boolean written) {
    public boolean valid() {
        return errors.isEmpty();
    }
}
