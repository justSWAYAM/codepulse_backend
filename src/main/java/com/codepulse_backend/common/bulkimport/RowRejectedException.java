package com.codepulse_backend.common.bulkimport;

import java.util.List;

public class RowRejectedException extends RuntimeException {
    private final List<String> reasons;

    public RowRejectedException(String reason) {
        this(List.of(reason));
    }

    public RowRejectedException(List<String> reasons) {
        super(String.join("; ", reasons), null, false, false);   // no stack trace: expected control flow
        this.reasons = List.copyOf(reasons);
    }

    public List<String> reasons() {
        return reasons;
    }
}
